package com.drone.tellopilot.video

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Recording life cycle on the real writer thread, with MediaStore / MediaMuxer test doubles:
 * saved file, nothing left behind on failure or without any picture, publication time.
 */
class VideoRecorderTest {

    private fun nal(header: Int, vararg body: Int) = byteArrayOf(0, 0, 0, 1, header.toByte()) + body.map { it.toByte() }.toByteArray()
    private val keyFrame = nal(0x67, 0x42, 0x00, 0x1F) + nal(0x68, 0xCE, 0x3C) + nal(0x65, 0x88, 0x80)
    private val slice = nal(0x41, 0x9A, 0x02)

    private val uri: Uri = mockk(relaxed = true)
    private val pfd: ParcelFileDescriptor = mockk(relaxed = true)
    private var insertResult: Uri? = uri
    private var publishFails = false
    private val published = Collections.synchronizedList(mutableListOf<Pair<String, Any?>>())
    private val resolver: ContentResolver = mockk(relaxed = true) {
        every { insert(any(), any()) } answers { insertResult }
        every { openFileDescriptor(any(), "rw") } returns pfd
        every { update(any(), any(), any(), any()) } answers { if (publishFails) throw SecurityException("revoked") else 1 }
    }
    private val context: Context = mockk { every { contentResolver } returns resolver }

    private val samples = Collections.synchronizedList(mutableListOf<Int>())
    private var diskFull = false

    @Before
    fun setUp() {
        mockkStatic(MediaFormat::class)
        every { MediaFormat.createVideoFormat(any(), any(), any()) } returns mockk(relaxed = true)
        mockkConstructor(MediaMuxer::class, ContentValues::class)
        every { anyConstructed<MediaMuxer>().addTrack(any()) } returns 0
        every { anyConstructed<MediaMuxer>().writeSampleData(any(), any(), any()) } answers {
            if (diskFull) throw IllegalStateException("No space left on device")
            samples += secondArg<java.nio.ByteBuffer>().remaining()
        }
        // Only the values given to update() (publication) matter here.
        every { anyConstructed<ContentValues>().put(any<String>(), any<Long>()) } answers { published += firstArg<String>() to secondArg<Long>() }
        every { anyConstructed<ContentValues>().put(any<String>(), any<Int>()) } answers { published += firstArg<String>() to secondArg<Int>() }
    }

    @After fun tearDown() = unmockkAll()

    private val recorder by lazy { VideoRecorder(context) }

    private fun awaitState(predicate: (RecordingState) -> Boolean): RecordingState = runBlocking {
        withTimeout(5_000) { recorder.state.first(predicate) }
    }

    private fun awaitIdleOrDone(): RecordingState = awaitState { it is RecordingState.Saved || it is RecordingState.Failed || it == RecordingState.Idle }

    @Test
    fun `start, first key frame, stop - the video is saved and published with its exact start`() {
        val before = System.currentTimeMillis()
        recorder.start()
        assertTrue(recorder.isActive)
        assertEquals(RecordingState.Starting, recorder.state.value)
        recorder.onFrame(slice) // no picture can start the file before a key frame
        recorder.onFrame(keyFrame)
        val recording = awaitState { it is RecordingState.Recording } as RecordingState.Recording
        assertTrue(recording.startedAtMs >= before)
        recorder.onFrame(slice)
        recorder.onFrame(slice)
        Thread.sleep(300)
        recorder.stop()
        assertEquals(RecordingState.Saved(uri), awaitState { it is RecordingState.Saved })
        assertFalse(recorder.isActive)
        assertEquals(3, samples.size)
        verify { anyConstructed<MediaMuxer>().stop() }
        verify { pfd.close() }
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
        // Published (not pending any more), with the time of the first frame for the overlay.
        assertTrue(MediaStore.Video.Media.IS_PENDING to 0 in published)
        val taken = published.single { it.first == MediaStore.Video.Media.DATE_TAKEN }.second as Long
        assertTrue(taken in before..System.currentTimeMillis())
    }

    @Test
    fun `stopped before any picture - nothing is saved, no empty file is left`() {
        recorder.start()
        recorder.onFrame(slice) // P-frames only: the file never starts
        Thread.sleep(300)
        recorder.stop()
        assertEquals(RecordingState.Idle, awaitIdleOrDone())
        assertTrue(samples.isEmpty())
        verify { resolver.delete(uri, null, null) }
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `the output cannot be created - failed, nothing recorded`() {
        insertResult = null
        recorder.start()
        val failed = awaitState { it is RecordingState.Failed } as RecordingState.Failed
        assertEquals("MediaStore insert failed", failed.reason)
        assertFalse(recorder.isActive)
        recorder.onFrame(keyFrame) // ignored
    }

    @Test
    fun `the file cannot be opened - failed and the entry removed`() {
        every { resolver.openFileDescriptor(any(), "rw") } returns null
        recorder.start()
        awaitState { it is RecordingState.Failed }
        verify { resolver.delete(uri, null, null) }
    }

    @Test
    fun `storage full while recording - failed, the partial file removed, the app goes on`() {
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        diskFull = true
        recorder.onFrame(slice)
        val failed = awaitState { it is RecordingState.Failed } as RecordingState.Failed
        assertEquals("No space left on device", failed.reason)
        assertFalse(recorder.isActive)
        verify { resolver.delete(uri, null, null) }
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
        // A new recording works.
        diskFull = false
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        recorder.stop()
        awaitState { it is RecordingState.Saved }
    }

    @Test
    fun `link lost while recording - the recording waits and continues from the next key frame`() {
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        recorder.onFrame(slice)
        Thread.sleep(700) // no frame at all: the writer just waits
        assertTrue(recorder.state.value is RecordingState.Recording)
        recorder.onFrame(keyFrame)
        recorder.onFrame(slice)
        Thread.sleep(300)
        recorder.stop()
        awaitState { it is RecordingState.Saved }
        assertEquals(4, samples.size)
    }

    @Test
    fun `publication refused - failed and removed`() {
        publishFails = true
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        recorder.stop()
        val failed = awaitState { it is RecordingState.Failed } as RecordingState.Failed
        assertEquals("publication impossible", failed.reason)
        verify { resolver.delete(uri, null, null) }
    }

    @Test
    fun `start twice is one recording, stop when idle does nothing`() {
        recorder.stop()
        assertEquals(RecordingState.Idle, recorder.state.value)
        recorder.start()
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        recorder.stop()
        awaitState { it is RecordingState.Saved }
        verify(exactly = 1) { resolver.insert(any(), any()) }
    }

    @Test
    fun `a start while the previous file is being finalised is ignored, a later one makes a second file`() {
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        recorder.stop()
        recorder.start() // the first writer is still finishing: still the same recording
        assertEquals(RecordingState.Saved(uri), awaitState { it is RecordingState.Saved })
        assertFalse(recorder.isActive)
        recorder.start()
        recorder.onFrame(keyFrame)
        awaitState { it is RecordingState.Recording }
        recorder.stop()
        awaitState { it is RecordingState.Saved }
        verify(exactly = 2) { resolver.insert(any(), any()) }
        verify(exactly = 2) { anyConstructed<MediaMuxer>().stop() }
    }
}
