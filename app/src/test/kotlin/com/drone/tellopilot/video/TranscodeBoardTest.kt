package com.drone.tellopilot.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TranscodeBoardTest {

    private fun q(id: String, overlay: Boolean? = null) = QueuedVideo(id, "$id.mp4", overlay)

    private fun running(id: String = "a") = TranscodeBoard().enqueued(listOf(q(id), q("b")))
        .started(id, "$id.mp4", 1_000, OverlayStatus.PENDING, nowMs = 100, paused = false)

    @Test
    fun `enqueue keeps order, ignores duplicates and lets a gallery choice win`() {
        val b = TranscodeBoard().enqueued(listOf(q("a"), q("b"))).enqueued(listOf(q("a", true), q("c"))).enqueued(listOf(q("a")))
        assertEquals(listOf("a", "b", "c"), b.queued.map { it.id })
        // "As the setting says" (null) never overrides an explicit choice.
        assertEquals(true, b.queued.first().overlay)
    }

    @Test
    fun `the active video is not queued twice`() {
        val b = running().enqueued(listOf(q("a", true)))
        assertEquals(listOf("b"), b.queued.map { it.id })
        assertEquals(2, b.total)
    }

    @Test
    fun `start moves the video from the queue to the active slot`() {
        val b = running()
        assertEquals("a", b.active?.id)
        assertEquals(TranscodeStage.EXPORTING, b.active?.stage)
        assertEquals(listOf("b"), b.queued.map { it.id })
        assertFalse(b.isIdle)
    }

    @Test
    fun `progress only rises and resets the stall`() {
        val b = running().stalled(true).progress(0.4f, 200).progress(0.3f, 300)
        assertEquals(0.4f, b.active!!.progress)
        assertEquals(200L, b.active!!.lastProgressAtMs)
        assertFalse(b.active!!.stalled)
        // Same value: still stalled-able, the timer is not reset.
        val same = b.stalled(true).progress(0.4f, 900)
        assertTrue(same.active!!.stalled)
        assertEquals(200L, same.active!!.lastProgressAtMs)
    }

    @Test
    fun `a new export stage restarts progress and estimate`() {
        val b = running().progress(0.9f, 200).stage(TranscodeStage.EXPORTING, 500)
        assertEquals(0f, b.active!!.progress)
        assertEquals(500L, b.active!!.exportStartedAtMs)
        val v = b.stage(TranscodeStage.VERIFYING, 600)
        assertEquals(TranscodeStage.VERIFYING, v.active!!.stage)
        assertEquals(500L, v.active!!.exportStartedAtMs)
    }

    @Test
    fun `finished videos are listed most recent first, capped`() {
        var b = TranscodeBoard()
        repeat(TranscodeBoard.MAX_FINISHED + 5) { i ->
            b = b.enqueued(listOf(q("v$i"))).started("v$i", "v$i", 10, OverlayStatus.OFF, i.toLong(), false)
                .finished(FinishedOutcome.Converted(5, true), null, i.toLong())
        }
        assertEquals(TranscodeBoard.MAX_FINISHED, b.finished.size)
        assertEquals("v${TranscodeBoard.MAX_FINISHED + 4}", b.finished.first().id)
        assertNull(b.active)
        assertTrue(b.isIdle)
    }

    @Test
    fun `failure keeps the reason, overlay status and choice for a retry`() {
        val b = running().overlay(OverlayStatus.DROPPED).finished(FinishedOutcome.Failed(FailReason.STALLED, "x"), true, 700)
        val f = b.finished.single()
        assertEquals(FinishedOutcome.Failed(FailReason.STALLED, "x"), f.outcome)
        assertEquals(OverlayStatus.DROPPED, f.overlay)
        assertEquals(true, f.overlayChoice)
        assertEquals(1_000L, f.beforeBytes)
        // Retrying puts it back in the queue and out of the finished list.
        val retried = b.enqueued(listOf(q("a", true)))
        assertTrue(retried.finished.isEmpty())
        assertEquals(listOf("b", "a"), retried.queued.map { it.id })
    }

    @Test
    fun `takeoff puts the active video back at the head of the queue`() {
        val b = running().requeued(false)
        assertNull(b.active)
        assertEquals(listOf("a", "b"), b.queued.map { it.id })
        assertEquals(false, b.queued.first().overlay)
    }

    @Test
    fun `remove and failure before start`() {
        val b = TranscodeBoard().enqueued(listOf(q("a"), q("b"))).removed("a")
        assertEquals(listOf("b"), b.queued.map { it.id })
        val gone = b.failedBeforeStart("b", "b.mp4", FailReason.SOURCE_MISSING, null, 5)
        assertTrue(gone.queued.isEmpty())
        assertEquals(FailReason.SOURCE_MISSING, (gone.finished.single().outcome as FinishedOutcome.Failed).reason)
    }

    @Test
    fun `named fills in the display name of a queued video`() {
        val b = TranscodeBoard().enqueued(listOf(QueuedVideo("a", "123", null))).named("a", "tello_1.mp4")
        assertEquals("tello_1.mp4", b.queued.single().name)
    }

    @Test
    fun `remaining time is extrapolated from the export so far`() {
        assertNull(TranscodeEta.remainingMs(10_000, 0.01f))
        assertNull(TranscodeEta.remainingMs(10_000, 1f))
        assertEquals(30_000L, TranscodeEta.remainingMs(10_000, 0.25f))
    }

    @Test
    fun `watchdog warns then stalls without progress, and recovers when it moves`() {
        val w = StallWatchdog(warnMs = 30, stallMs = 120, startMs = 0)
        assertEquals(StallWatchdog.State.OK, w.update(10, null))
        assertEquals(StallWatchdog.State.WARN, w.update(40, null))
        assertEquals(StallWatchdog.State.OK, w.update(50, 0.1f))
        assertEquals(StallWatchdog.State.WARN, w.update(90, 0.1f))
        // Same percentage over and over (the 96 % case): stalled.
        assertEquals(StallWatchdog.State.STALLED, w.update(170, 0.1f))
    }

    @Test
    fun `watchdog stalls an export that never reports progress`() {
        val w = StallWatchdog(warnMs = 30, stallMs = 120, startMs = 1_000)
        assertEquals(StallWatchdog.State.STALLED, w.update(1_120, null))
    }

    @Test
    fun `withStateOf returns once its block is done (the queue used to hang here)`() = runBlocking {
        val source = MutableStateFlow(false) // never completes, like the pause flow
        // Times out (test failure) if the sharing collector or a child kept the scope alive.
        val result = withTimeout(5_000) {
            withStateOf(source) { state ->
                launch { state.collect { } } // a watcher child, like the pause UI updater
                "done"
            }
        }
        assertEquals("done", result)
    }

    @Test
    fun `orphan policy publishes readable files, deletes broken ones, skips fresh ones`() {
        assertEquals(OrphanPolicy.Action.SKIP, OrphanPolicy.decide(ageMs = 5_000, readable = false))
        assertEquals(OrphanPolicy.Action.PUBLISH, OrphanPolicy.decide(ageMs = 600_000, readable = true))
        // Muxer never stopped (process killed mid-recording): no index, unreadable.
        assertEquals(OrphanPolicy.Action.DELETE, OrphanPolicy.decide(ageMs = 600_000, readable = false))
        // An interrupted re-encoded copy may look readable but be truncated: deleted, redone.
        assertEquals(OrphanPolicy.Action.DELETE, OrphanPolicy.decide(ageMs = 600_000, readable = true, reencodedCopy = true))
        assertTrue(OrphanPolicy.isReencodedCopy("tello_20261101_163018-hevc.mp4"))
        assertTrue(OrphanPolicy.isReencodedCopy("tello_20261101_163018-av1.mp4"))
        assertFalse(OrphanPolicy.isReencodedCopy("tello_20261101_162707.mp4"))
    }

    @Test
    fun `start time source - scanner end time rejected, name used`() {
        val zone = ZoneId.of("Europe/Paris")
        val name = VideoStartTime.fromName("tello_20261101_164101.mp4", zone)!!
        // Seen on the phone: DATE_TAKEN = end of the 138 s recording (media scanner).
        val start = VideoStartTime.resolveDetailed(name, name + 138_000, 138_154)!!
        assertEquals(VideoStartTime.Source.NAME, start.source)
        assertEquals(name + VideoStartTime.NAME_ROUNDING_MS, start.epochMs)
        val exact = VideoStartTime.resolveDetailed(name, name + 1_200, 138_154)!!
        assertEquals(VideoStartTime.Source.DATE_TAKEN, exact.source)
        assertEquals(VideoStartTime.Source.DATE_TAKEN_ONLY, VideoStartTime.resolveDetailed(null, 42, 0)!!.source)
        assertNull(VideoStartTime.resolveDetailed(null, null, 0))
    }
}
