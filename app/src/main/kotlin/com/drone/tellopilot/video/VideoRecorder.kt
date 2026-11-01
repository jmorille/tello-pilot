package com.drone.tellopilot.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.drone.tello.protocol.H264
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RecordingState {
    data object Idle : RecordingState
    /** Started, waiting for a key frame (SPS/PPS) to begin the file. */
    data object Starting : RecordingState
    data class Recording(val startedAtMs: Long) : RecordingState
    data class Saved(val uri: Uri) : RecordingState
    data class Failed(val reason: String) : RecordingState
}

/**
 * Records the drone's H.264 stream to an MP4 in `Movies/TelloPilot` — remuxing only,
 * no re-encoding. Frames are handed over through a bounded queue to a dedicated writer
 * thread: the video thread never waits for the disk (if the disk stalls, frames are
 * dropped from the recording, never from the live view).
 */
@Singleton
class VideoRecorder @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** Frame and its arrival time (timestamps must not depend on when the disk writes them). */
    /** [gapBefore]: frames were dropped (queue full) just before this one. */
    private class Frame(val bytes: ByteArray, val arrivalNs: Long, val gapBefore: Boolean)

    /**
     * One recording = one writer thread with its own queue: a stop followed by an immediate
     * restart can never mix two files, and an old writer never touches the new one's state.
     */
    private inner class Session : Thread("tello-recorder") {
        val queue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)
        /** Video thread only: a frame was dropped, flagged on the next accepted one. */
        var pendingGap = false
        @Volatile var stopped = false

        override fun run() = writeLoop(this)

        /** Publishes [state] only while this session is the current one. */
        fun publish(state: RecordingState) {
            synchronized(this@VideoRecorder) { if (current === this) _state.value = state }
        }
    }

    @Volatile private var current: Session? = null

    val isActive: Boolean get() = current != null

    /** Called from the video thread for every frame that reaches the decoder. Never blocks. */
    fun onFrame(frame: ByteArray) {
        val session = current ?: return
        // The gap travels with the frames, so the writer knows exactly where it is.
        session.pendingGap = !session.queue.offer(Frame(frame, System.nanoTime(), session.pendingGap))
    }

    @Synchronized
    fun start() {
        if (current != null) return
        val session = Session().apply {
            // Last line of defence: a recording problem must never kill the app mid-flight.
            setUncaughtExceptionHandler { _, e -> Log.e(TAG, "recorder crashed", e); finish(this, null) }
        }
        current = session
        _state.value = RecordingState.Starting
        session.start()
    }

    /** Asks the writer to finalise the file; it publishes Saved/Failed/Idle when done. */
    @Synchronized
    fun stop() {
        val session = current ?: return
        session.stopped = true
        session.interrupt()
    }

    /** Detaches [session] and publishes its final [state] (Idle when null). */
    private fun finish(session: Session, state: RecordingState?) {
        synchronized(this) {
            if (current === session) {
                current = null
                _state.value = state ?: if (_state.value is RecordingState.Failed) _state.value else RecordingState.Idle
            }
        }
    }

    private fun writeLoop(session: Session) {
        val (uri, pfd) = try {
            createOutput()
        } catch (e: Exception) {
            Log.e(TAG, "cannot create output", e)
            finish(session, RecordingState.Failed(e.message ?: "stockage indisponible"))
            return
        }
        var muxer: MediaMuxer? = null
        var track = -1
        var firstPtsNs = 0L
        /** Wall-clock time of the first frame (presentation time 0), for the telemetry overlay. */
        var firstFrameEpochMs = 0L
        var written = 0
        var waitingKey = false
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var afterConfig = false
        var csd: Pair<ByteArray, ByteArray>? = null
        var pendingConfig: ByteArray? = null
        var waitLogged = 0
        var failure: String? = null
        val info = MediaCodec.BufferInfo()
        try {
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            while (!session.stopped) {
                val frame = session.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val types = H264.nalTypes(frame.bytes)
                if (frame.gapBefore) {
                    // Frames were lost (writer late): skip to the next key frame, and a picture
                    // following parameter sets seen before the gap is not a key frame.
                    waitingKey = true
                    afterConfig = false
                }
                if (track < 0 && waitLogged++ < WAIT_LOG_FRAMES) Log.d(TAG, "waiting for key frame, NAL types $types")
                // The Tello sends SPS, PPS and the picture in separate packets: remember the
                // parameter sets as they come, the picture right after them is the key frame.
                if (H264.NAL_SPS in types) {
                    H264.extractNal(frame.bytes, H264.NAL_SPS)?.let { sps = it }
                    afterConfig = true
                }
                if (H264.NAL_PPS in types) H264.extractNal(frame.bytes, H264.NAL_PPS)?.let { pps = it }
                val hasPicture = types.any { it == H264.NAL_IDR || it == H264.NAL_SLICE }
                // Parameter sets alone are not a sample: they go to the track format (csd).
                if (!hasPicture) continue
                val key = H264.NAL_IDR in types || afterConfig
                afterConfig = false
                // Parameter sets changed during the recording (bitrate change, drone encoder
                // restart): the track keeps its first csd, the new ones go in-band before the
                // next key frame.
                val current = csd
                val s0 = sps
                val p0 = pps
                if (current != null && s0 != null && p0 != null &&
                    !(s0.contentEquals(current.first) && p0.contentEquals(current.second))
                ) {
                    pendingConfig = s0 + p0
                    csd = s0 to p0
                }
                if (track < 0) {
                    val s = sps
                    val p = pps
                    if (s == null || p == null || !key) continue // wait for SPS + PPS, then a key frame
                    track = muxer.addTrack(formatOf(s, p))
                    csd = s to p
                    muxer.start()
                    firstPtsNs = frame.arrivalNs
                    firstFrameEpochMs = System.currentTimeMillis() - (System.nanoTime() - frame.arrivalNs) / 1_000_000
                    Log.i(TAG, "recording started (sps ${s.size} B, pps ${p.size} B)")
                    session.publish(RecordingState.Recording(System.currentTimeMillis()))
                }
                if (waitingKey && !key) continue
                waitingKey = false
                val ptsUs = (frame.arrivalNs - firstPtsNs) / 1_000
                val config = if (key) pendingConfig else null
                if (config != null) {
                    pendingConfig = null
                    Log.i(TAG, "parameter sets changed: written in-band")
                }
                val sample = if (config != null) config + frame.bytes else frame.bytes
                info.set(0, sample.size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, ByteBuffer.wrap(sample), info)
                written++
            }
        } catch (_: InterruptedException) {
            // stop requested
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
            failure = e.message ?: "erreur d'enregistrement"
        }
        // Finalisation: every step guarded, nothing may escape this thread.
        Log.i(TAG, "recording finished: $written frames, failure=$failure")
        val ok = failure == null && written > 0 && runCatching { muxer?.stop() }.isSuccess
        runCatching { muxer?.release() }
        runCatching { pfd.close() }
        val published = ok && runCatching {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    // Exact start (ms) for the overlay alignment (see VideoStartTime.resolve).
                    if (firstFrameEpochMs > 0) put(MediaStore.Video.Media.DATE_TAKEN, firstFrameEpochMs)
                },
                null,
                null,
            )
        }.isSuccess
        if (!published) runCatching { context.contentResolver.delete(uri, null, null) }
        finish(
            session,
            when {
                published -> RecordingState.Saved(uri)
                failure != null -> RecordingState.Failed(failure)
                ok -> RecordingState.Failed("publication impossible")
                else -> null // stopped before any frame: nothing to save
            },
        )
    }

    private fun createOutput(): Pair<Uri, ParcelFileDescriptor> {
        val name = "tello_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TelloPilot")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: run {
            resolver.delete(uri, null, null); error("cannot open $uri")
        }
        return uri to pfd
    }

    /** MP4 track format with SPS/PPS (with start codes) as codec-specific data. */
    private fun formatOf(sps: ByteArray, pps: ByteArray): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }

    private companion object {
        const val TAG = "VideoRecorder"
        const val WIDTH = 960
        const val HEIGHT = 720
        const val QUEUE_CAPACITY = 90 // ~3 s at 30 fps
        const val WAIT_LOG_FRAMES = 40
    }
}
