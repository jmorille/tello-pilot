package com.drone.tellopilot.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentLinkedQueue

/** Something that accepts complete H.264 access units. */
fun interface FrameSink {
    /** Returns `false` when the frame could not be queued (decoder busy, broken or released). */
    fun decode(frame: ByteArray): Boolean
}

/**
 * Hardware H.264 decoder rendering straight to [surface], tuned for latency:
 * asynchronous mode, low-latency + real-time priority keys, and every output buffer is
 * rendered as soon as it is ready (no presentation-time scheduling, no frame queue).
 *
 * Thread-safety: [decode] runs on the video thread, [release] on the main thread and codec
 * callbacks on a private thread. Every codec access is done under [lock] after checking
 * [released], so the codec can never be used after (or during) its release.
 * A fatal codec error triggers an automatic reset (the next key frame restarts the picture).
 */
class H264Decoder(private val surface: Surface) : FrameSink {

    private val lock = Any()
    private val thread = HandlerThread("tello-decoder", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val freeInputs = ConcurrentLinkedQueue<Int>()
    private val codec: MediaCodec
    private var released = false
    private var broken = false
    private var pts = 0L

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            freeInputs.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (!released && !broken) runCatching { codec.releaseOutputBuffer(index, true) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            if (e.isTransient) return
            synchronized(lock) {
                if (released) return
                broken = true
                freeInputs.clear()
                // Recover in place: the session will feed a key frame once decode() succeeds again.
                runCatching {
                    codec.reset()
                    configureAndStart(codec)
                    broken = false
                }.onFailure { Log.e(TAG, "decoder reset failed", it) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    init {
        val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            configureAndStart(created)
        } catch (e: Exception) {
            created.release()
            thread.quitSafely()
            throw e
        }
        codec = created
    }

    private fun configureAndStart(codec: MediaCodec) {
        codec.setCallback(callback, handler)
        try {
            codec.configure(format(withOperatingRate = true, codec), surface, null, 0)
        } catch (e: Exception) {
            // Some codecs reject KEY_OPERATING_RATE = max: retry without it.
            Log.w(TAG, "configure failed, retrying without operating rate", e)
            codec.reset()
            codec.setCallback(callback, handler)
            codec.configure(format(withOperatingRate = false, codec), surface, null, 0)
        }
        codec.start()
    }

    private fun format(withOperatingRate: Boolean, codec: MediaCodec) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            if (withOperatingRate) setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            if (supportsLowLatency(codec)) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

    override fun decode(frame: ByteArray): Boolean = synchronized(lock) {
        if (released || broken) return false
        val index = freeInputs.poll() ?: return false
        try {
            val buffer = codec.getInputBuffer(index) ?: return false
            if (frame.size > buffer.capacity()) return false
            buffer.clear()
            buffer.put(frame)
            pts += FRAME_US
            codec.queueInputBuffer(index, 0, frame.size, pts, 0)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queue failed", e)
            false
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        thread.quitSafely()
    }

    private fun supportsLowLatency(codec: MediaCodec): Boolean = runCatching {
        codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }.getOrDefault(false)

    private companion object {
        const val TAG = "H264Decoder"
        const val WIDTH = 960
        const val HEIGHT = 720
        const val FRAME_US = 33_333L
    }
}
