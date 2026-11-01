package com.drone.tellopilot.video

import android.content.ContextWrapper
import android.content.Context
import android.net.Uri
import androidx.media3.effect.TextureOverlay
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TranscodeCodec
import com.drone.tellopilot.data.AppSettings
import com.drone.tellopilot.data.PhoneHeat
import com.drone.tellopilot.data.PhoneThermal
import com.drone.tellopilot.data.SettingsRepository
import com.drone.tellopilot.flightlog.FlightLogStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Post-flight re-encoding queue: what is queued, overlay choice and fallbacks, pauses (flight, heat). */
@OptIn(ExperimentalCoroutinesApi::class, androidx.media3.common.util.UnstableApi::class)
class TranscodeQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private inner class Rig(val scope: TestScope, pilot: PilotSettings = PilotSettings()) {
        val settings = MutableStateFlow(AppSettings(pilot = pilot))
        val recorderState = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val heat = MutableStateFlow(PhoneHeat.NORMAL)
        val videos = HashMap<Uri, DroneVideo>()
        val published = mutableListOf<DroneVideo>()
        val deleted = mutableListOf<Uri>()
        /** Overlay passed to each export (null: plain). */
        val exports = mutableListOf<TextureOverlay?>()
        var exportMs = 1_000L
        var outputBytes = 400L
        var failWithOverlay = false
        var overlayError: Exception = IllegalStateException("GL error")
        var plainError: Exception? = null
        var onExportEnd: () -> Unit = {}
        var verifyWithOverlay = true
        var verifyPlain = true
        val overlay: TextureOverlay = mockk(relaxed = true)

        val library: VideoLibrary = mockk()

        init {
            coEvery { library.get(any()) } answers { videos[firstArg()] }
            coEvery { library.list() } answers { videos.values.toList() }
            coEvery { library.publish(any(), any(), any()) } answers { published += secondArg<DroneVideo>(); mockk() }
            coEvery { library.delete(any()) } answers { deleted += firstArg<Uri>(); true }
            coEvery { library.recoverOrphans(any()) } returns emptyList()
        }
        val transcoder: VideoTranscoder = mockk {
            every { isSupported(any()) } returns true
            coEvery { transcode(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val withOverlay = arg<TextureOverlay?>(4)
                exports += withOverlay
                arg<(Float) -> Unit>(6)(0.5f)
                delay(exportMs)
                if (withOverlay != null && failWithOverlay) throw overlayError
                if (withOverlay == null) plainError?.let { throw it }
                onExportEnd()
                arg<File>(1).writeBytes(ByteArray(outputBytes.toInt()))
            }
            coEvery { verify(any(), any(), any()) } answers { if (exports.last() != null) verifyWithOverlay else verifyPlain }
        }
        val overlays: TelemetryOverlayFactory = mockk { coEvery { build(any(), any(), any()) } returns TelemetryOverlayFactory.Built(overlay, OverlayStatus.ACTIVE) }
        val context: Context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = tmp.root
            override fun startForegroundService(service: android.content.Intent?) = null
            override fun startService(service: android.content.Intent?) = null
            override fun getPackageName() = "com.drone.tellopilot"
        }

        fun queue(overlayFactory: TelemetryOverlayFactory = overlays) = TranscodeQueue(
            context, library, transcoder,
            mockk<SettingsRepository> { every { settings } returns this@Rig.settings },
            mockk<VideoRecorder> { every { state } returns recorderState },
            mockk<PhoneThermal> { every { heat } returns this@Rig.heat },
            overlayFactory,
            StandardTestDispatcher(scope.testScheduler),
        )

        fun video(name: String = "tello_20261101_120000.mp4", h264: Boolean = true, size: Long = 1_000): DroneVideo {
            val uri: Uri = mockk(relaxed = true)
            return DroneVideo(uri, name, size, 10_000, null, if (h264) "video/avc" else "video/hevc").also { videos[uri] = it }
        }
    }

    private fun test(pilot: PilotSettings = PilotSettings(), block: suspend Rig.() -> Unit) = runTest { Rig(this, pilot).block() }

    @Test
    fun `a saved recording is re-encoded, published, then the original deleted`() = test {
        val q = queue()
        runCurrentAll()
        val v = video()
        recorderState.value = RecordingState.Saved(v.uri)
        scope.runCurrent()
        assertTrue(q.busy.value)
        val running = q.state.value as TranscodeState.Running
        assertEquals(v.name, running.name)
        assertEquals(0.5f, running.progress, 0f)
        scope.advanceTimeBy(2_000)
        assertEquals(TranscodeState.Done(1, 0, 0, 600), q.state.value)
        assertEquals(listOf(v), published)
        assertEquals(listOf(v.uri), deleted)
        assertFalse(q.busy.value)
        assertTrue(tmp.root.resolve("transcode").listFiles()!!.isEmpty()) // no temporary file left
    }

    private fun Rig.runCurrentAll() = scope.runCurrent()

    @Test
    fun `automatic re-encoding off - a saved recording is not queued`() = test(PilotSettings(autoTranscode = false)) {
        val q = queue()
        runCurrentAll()
        recorderState.value = RecordingState.Saved(video().uri)
        scope.advanceTimeBy(5_000)
        assertEquals(TranscodeState.Idle, q.state.value)
        assertTrue(exports.isEmpty())
    }

    @Test
    fun `keep original - nothing deleted, no space saved`() = test(PilotSettings(keepOriginal = true)) {
        val q = queue()
        q.enqueueVideo(video().uri)
        scope.advanceTimeBy(2_000)
        assertEquals(TranscodeState.Done(1, 0, 0, 0), q.state.value)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `no gain - the original is kept, nothing published`() = test {
        outputBytes = 2_000
        val q = queue()
        q.enqueueVideo(video(size = 1_000).uri)
        scope.advanceTimeBy(2_000)
        assertEquals(TranscodeState.Done(0, 1, 0, 0), q.state.value)
        assertTrue(published.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `failed output check - failed, the original untouched`() = test {
        verifyPlain = false
        val q = queue()
        q.enqueueVideo(video().uri)
        scope.advanceTimeBy(2_000)
        assertEquals(TranscodeState.Done(0, 0, 1, 0), q.state.value)
        assertTrue(published.isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `existing H264 videos without a copy are queued, others skipped`() = test {
        val q = queue()
        video("tello_a.mp4")
        video("tello_b.mp4")
        video("tello_b-hevc.mp4", h264 = false)
        q.enqueueExisting()
        scope.advanceTimeBy(5_000)
        assertEquals(listOf("tello_a.mp4"), published.map { it.name })
        // Nothing left: done at once.
        videos.clear()
        q.enqueueExisting()
        scope.advanceTimeBy(100)
        assertEquals(TranscodeState.Done(0, 0, 0, 0), q.state.value)
    }

    @Test
    fun `a video gone or not H264 is skipped`() = test {
        val q = queue()
        q.enqueueVideo(video(h264 = false).uri)
        q.enqueueVideo(mockk(relaxed = true))
        scope.advanceTimeBy(2_000)
        assertEquals(TranscodeState.Done(0, 0, 0, 0), q.state.value)
        assertTrue(exports.isEmpty())
    }

    // ------------------------------------------------------------- overlay

    @Test
    fun `overlay - the setting applies, a gallery choice wins and is never reset by a bulk enqueue`() = test(PilotSettings(overlayOnTranscode = true)) {
        val q = queue()
        runCurrentAll()
        q.setFlying(this, true) // hold the queue
        val v = video()
        q.enqueueVideo(v.uri, overlay = false)
        recorderState.value = RecordingState.Saved(v.uri) // same video queued again "as the setting says"
        q.enqueueExisting()
        scope.runCurrent()
        q.setFlying(this, false)
        scope.advanceTimeBy(2_000)
        assertEquals(listOf<TextureOverlay?>(null), exports)
    }

    @Test
    fun `overlay - the setting applies without an explicit choice`() = test(PilotSettings(overlayOnTranscode = true)) {
        val q = queue()
        q.enqueueVideo(video().uri)
        scope.advanceTimeBy(2_000)
        assertEquals(listOf<TextureOverlay?>(overlay), exports)
    }

    @Test
    fun `overlay asked from the gallery while the setting is off`() = test {
        val q = queue()
        q.enqueueVideo(video().uri, overlay = true)
        scope.advanceTimeBy(2_000)
        assertEquals(listOf<TextureOverlay?>(overlay), exports)
        assertEquals(1, published.size)
    }

    @Test
    fun `overlay export fails - redone without it`() = test {
        failWithOverlay = true
        val q = queue()
        q.enqueueVideo(video().uri, overlay = true)
        scope.advanceTimeBy(5_000)
        assertEquals(listOf(overlay, null), exports)
        assertEquals(TranscodeState.Done(1, 0, 0, 600), q.state.value)
    }

    @Test
    fun `overlay output check fails - redone without it`() = test {
        verifyWithOverlay = false
        val q = queue()
        q.enqueueVideo(video().uri, overlay = true)
        scope.advanceTimeBy(5_000)
        assertEquals(listOf(overlay, null), exports)
        assertEquals(1, published.size)
    }

    @Test
    fun `overlay unavailable - plain re-encoding`() = test {
        coEvery { overlays.build(any(), any(), any()) } throws IllegalStateException("no flight log")
        val q = queue()
        q.enqueueVideo(video().uri, overlay = true)
        scope.advanceTimeBy(2_000)
        assertEquals(listOf<TextureOverlay?>(null), exports)
        assertEquals(1, published.size)
    }

    @Test
    fun `the overlay waits for the flight log still being written after landing`() = test {
        val store = FlightLogStore(object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.root
        })
        store.activeId = "flight-20261101-115900" // landing tail in progress
        val q = queue(TelemetryOverlayFactory(store))
        q.enqueueVideo(video().uri, overlay = true)
        scope.advanceTimeBy(4_000)
        assertTrue(exports.isEmpty())
        store.activeId = null // the flight file is closed
        scope.advanceTimeBy(600)
        // The flight list is read on the I/O pool (real threads).
        repeat(200) { if (exports.isEmpty()) { Thread.sleep(10); scope.advanceTimeBy(10) } }
        assertEquals(1, exports.size)
    }

    // ------------------------------------------------------------- pauses

    @Test
    fun `paused in flight - abandoned at takeoff, redone once landed, published once`() = test {
        val q = queue()
        val owner = Any()
        q.setFlying(owner, true)
        q.enqueueVideo(video().uri)
        scope.advanceTimeBy(3_000)
        assertTrue((q.state.value as TranscodeState.Running).paused)
        assertTrue(exports.isEmpty())
        q.setFlying(owner, false)
        scope.advanceTimeBy(500) // export half done
        assertEquals(1, exports.size)
        q.setFlying(owner, true) // takeoff
        scope.advanceTimeBy(3_000)
        assertTrue(published.isEmpty())
        assertTrue((q.state.value as TranscodeState.Running).paused)
        // Another flight screen closing does not resume it.
        q.setFlying(Any(), false)
        scope.advanceTimeBy(3_000)
        assertEquals(1, exports.size)
        q.setFlying(owner, false)
        scope.advanceTimeBy(2_000)
        assertEquals(2, exports.size)
        assertEquals(1, published.size)
        assertEquals(TranscodeState.Done(1, 0, 0, 600), q.state.value)
    }

    @Test
    fun `thermal pause - from hot until back to normal`() = test {
        val q = queue()
        val owner = Any()
        q.setFlying(owner, true)
        q.enqueueVideo(video().uri)
        scope.runCurrent()
        heat.value = PhoneHeat.HOT
        scope.runCurrent()
        q.setFlying(owner, false) // landed, but the phone is hot
        scope.advanceTimeBy(2_000)
        assertTrue(exports.isEmpty())
        assertTrue((q.state.value as TranscodeState.Running).paused)
        heat.value = PhoneHeat.WARM // still warm: resuming now would heat it again
        scope.advanceTimeBy(2_000)
        assertTrue(exports.isEmpty())
        heat.value = PhoneHeat.NORMAL
        scope.advanceTimeBy(2_000)
        assertEquals(1, published.size)
    }

    @Test
    fun `a video that overheats the phone three times is given up, the next one goes on`() = test {
        val q = queue()
        val hot = video("tello_hot.mp4")
        q.enqueueVideo(hot.uri)
        q.enqueueVideo(video("tello_next.mp4").uri)
        repeat(3) {
            scope.advanceTimeBy(500)
            heat.value = PhoneHeat.HOT
            scope.runCurrent()
            heat.value = PhoneHeat.NORMAL
            scope.runCurrent()
        }
        scope.advanceTimeBy(3_000)
        assertEquals(listOf("tello_next.mp4"), published.map { it.name })
        assertEquals(TranscodeState.Done(1, 0, 1, 600), q.state.value)
    }

    @Test
    fun `a video queued while the worker finishes is processed too`() = test {
        val q = queue()
        q.enqueueVideo(video("tello_1.mp4").uri)
        scope.advanceTimeBy(500)
        q.enqueueVideo(video("tello_2.mp4").uri)
        scope.advanceTimeBy(3_000)
        assertEquals(listOf("tello_1.mp4", "tello_2.mp4"), published.map { it.name })
        assertEquals(TranscodeState.Done(2, 0, 0, 1_200), q.state.value)
        coVerify(exactly = 2) { transcoder.verify(any(), TranscodeCodec.HEVC, 10_000) }
    }

    // ------------------------------------------- fixed bugs (see the commits)

    @Test
    fun `the worker ends with the queue - not busy any more, a later video is processed`() = test {
        val q = queue()
        q.enqueueVideo(video("tello_1.mp4").uri)
        scope.advanceTimeBy(2_000)
        assertEquals(TranscodeState.Done(1, 0, 0, 600), q.state.value)
        assertFalse(q.busy.value) // the foreground service can stop
        q.enqueueVideo(video("tello_2.mp4").uri)
        scope.advanceTimeBy(2_000)
        assertEquals(listOf("tello_1.mp4", "tello_2.mp4"), published.map { it.name })
        assertFalse(q.busy.value)
    }

    @Test
    fun `a queue started on a hot phone waits without starting an export`() = test {
        val q = queue()
        heat.value = PhoneHeat.HOT
        q.enqueueVideo(video().uri)
        scope.advanceTimeBy(2_000)
        assertTrue(exports.isEmpty())
        heat.value = PhoneHeat.NORMAL
        scope.advanceTimeBy(2_000)
        assertEquals(1, exports.size)
        assertEquals(1, published.size)
    }

    // ------------------------------------------------- follow-up board

    @Test
    fun `board - active video with step and progress, then listed as converted`() = test {
        val q = queue()
        val v = video(size = 1_000)
        q.enqueueVideo(v.uri, overlay = false)
        scope.runCurrent()
        val active = q.board.value.active!!
        assertEquals(v.name, active.name)
        assertEquals(TranscodeStage.EXPORTING, active.stage)
        assertEquals(OverlayStatus.OFF, active.overlay)
        assertEquals(0.5f, active.progress, 0f)
        scope.advanceTimeBy(2_000)
        val done = q.board.value.finished.single()
        assertEquals(FinishedOutcome.Converted(400, true), done.outcome)
        assertEquals(1_000L, done.beforeBytes)
        assertTrue(q.board.value.isIdle)
    }

    @Test
    fun `board - a stalled export with overlay is redone without it`() = test {
        val q = queue()
        failWithOverlay = true
        overlayError = TranscodeStalledException(120_000)
        q.enqueueVideo(video().uri, overlay = true)
        scope.advanceTimeBy(5_000)
        assertEquals(listOf(overlay, null), exports)
        val done = q.board.value.finished.single()
        assertEquals(OverlayStatus.DROPPED, done.overlay)
        assertTrue(done.outcome is FinishedOutcome.Converted)
        assertFalse(q.busy.value)
    }

    @Test
    fun `board - a stalled plain export fails cleanly and the queue goes on`() = test {
        val q = queue()
        plainError = TranscodeStalledException(120_000)
        q.enqueueVideo(video("tello_1.mp4").uri, overlay = false)
        q.enqueueVideo(video("tello_2.mp4").uri, overlay = false)
        scope.advanceTimeBy(5_000)
        assertEquals(
            listOf(FailReason.STALLED, FailReason.STALLED),
            q.board.value.finished.map { (it.outcome as FinishedOutcome.Failed).reason },
        )
        assertFalse(q.busy.value)
        assertEquals(TranscodeState.Done(0, 0, 2, 0), q.state.value)
    }

    @Test
    fun `board - original deleted during the export - the copy is not published`() = test {
        val q = queue()
        val v = video()
        onExportEnd = { videos.remove(v.uri) }
        q.enqueueVideo(v.uri, overlay = false)
        scope.advanceTimeBy(2_000)
        assertTrue(published.isEmpty())
        assertEquals(FailReason.SOURCE_MISSING, (q.board.value.finished.single().outcome as FinishedOutcome.Failed).reason)
    }

    @Test
    fun `board - a video gone before its turn is reported`() = test {
        val q = queue()
        val gone: Uri = mockk(relaxed = true)
        q.enqueueVideo(gone)
        scope.advanceTimeBy(1_000)
        assertEquals(FailReason.SOURCE_MISSING, (q.board.value.finished.single().outcome as FinishedOutcome.Failed).reason)
    }

    @Test
    fun `board - cancel the current video, remove a waiting one, retry the cancelled one`() = test {
        val q = queue()
        val a = video("tello_1.mp4")
        val b = video("tello_2.mp4")
        val c = video("tello_3.mp4")
        q.enqueueVideo(a.uri, overlay = false)
        q.enqueueVideo(b.uri, overlay = false)
        q.enqueueVideo(c.uri, overlay = false)
        scope.runCurrent()
        assertEquals(a.name, q.board.value.active?.name)
        assertEquals(2, q.board.value.queued.size)
        q.remove(b.uri.toString())
        q.cancelCurrent()
        scope.advanceTimeBy(3_000)
        val cancelled = q.board.value.finished.first { it.id == a.uri.toString() }
        assertEquals(FailReason.CANCELLED, (cancelled.outcome as FinishedOutcome.Failed).reason)
        assertEquals(listOf(c), published) // b removed, a cancelled
        q.retry(a.uri.toString())
        scope.advanceTimeBy(3_000)
        assertEquals(listOf(c, a), published)
        assertFalse(q.busy.value)
    }

    @Test
    fun `board - the last video cancelled then retried is really redone`() = test {
        val q = queue()
        val a = video()
        q.enqueueVideo(a.uri, overlay = false)
        scope.runCurrent()
        q.cancelCurrent()
        scope.advanceTimeBy(2_000)
        assertFalse(q.busy.value)
        q.retry(a.uri.toString())
        scope.advanceTimeBy(3_000)
        assertEquals(listOf(a), published)
        assertTrue(q.board.value.finished.single().outcome is FinishedOutcome.Converted)
    }

    @Test
    fun `board - takeoff puts the video back in the queue, shown paused`() = test {
        val q = queue()
        val v = video()
        q.enqueueVideo(v.uri, overlay = false)
        scope.advanceTimeBy(500)
        q.setFlying(this, true)
        scope.advanceTimeBy(500)
        val active = q.board.value.active!!
        assertTrue(active.paused)
        assertEquals(v.name, active.name)
        q.setFlying(this, false)
        scope.advanceTimeBy(3_000)
        assertEquals(listOf(v), published)
    }
}
