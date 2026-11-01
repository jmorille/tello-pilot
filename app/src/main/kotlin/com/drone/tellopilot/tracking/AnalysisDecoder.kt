package com.drone.tellopilot.tracking

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.drone.tello.protocol.H264
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Second, independent decoder of the drone stream, for image analysis only: frames are decoded
 * into an [ImageReader] (YUV) and handed to [onImage] at most [maxRate] times per second.
 * It never touches the live view: its input is a bounded queue (frames are dropped, never
 * waited for), and it restarts on the next key frame after any drop.
 */
class AnalysisDecoder(
    private val maxRate: Int = 10,
    /**
     * Receives an image to analyse and a `release` callback that the receiver must call exactly
     * once when done (it closes the image and lets the next one through).
     */
    private val onImage: (image: Image, release: () -> Unit) -> Unit,
    /** Called (from any thread) when the decoder needs a key frame to restart. */
    private val requestKeyFrame: () -> Unit = {},
) : AutoCloseable {

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val needKeyFrame = AtomicBoolean(true)
    private val running = AtomicBoolean(true)
    private val imageThread = HandlerThread("tello-analysis").apply { start() }
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES)
    @Volatile private var lastDelivered = 0L
    private val busy = AtomicBoolean(false)

    private val decodeThread = Thread(::decodeLoop, "tello-analysis-dec")

    init {
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (!running.get() || busy.get() || now - lastDelivered < 1000L / maxRate) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastDelivered = now
            busy.set(true)
            val released = AtomicBoolean(false)
            onImage(image) {
                if (!released.getAndSet(true)) {
                    image.close()
                    busy.set(false)
                }
            }
        }, Handler(imageThread.looper))
        decodeThread.start()
    }

    /** Called from the video receive thread; never blocks. */
    fun offer(frame: ByteArray) {
        if (!running.get()) return
        if (needKeyFrame.get()) {
            if (!H264.containsConfig(frame)) return
            queue.clear()
            needKeyFrame.set(false)
        }
        if (!queue.offer(frame)) {
            // Decoder late: restart cleanly on the next key frame instead of decoding garbage.
            resync()
        }
    }

    private fun decodeLoop() {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT), reader.surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "analysis decoder unavailable", e)
            return
        }
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        try {
            while (running.get()) {
                val frame = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    val buffer = if (index >= 0) codec.getInputBuffer(index) else null
                    if (buffer != null && frame.size <= buffer.capacity()) {
                        buffer.clear()
                        buffer.put(frame)
                        codec.queueInputBuffer(index, 0, frame.size, pts, 0)
                        pts += FRAME_US
                    } else {
                        // No input buffer, or a frame too big for it: give the buffer back, skip to a key frame.
                        if (index >= 0) codec.queueInputBuffer(index, 0, 0, pts, 0)
                        resync()
                    }
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    codec.releaseOutputBuffer(out, true)
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "analysis decoder failed", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun resync() {
        queue.clear()
        needKeyFrame.set(true)
        requestKeyFrame()
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        decodeThread.interrupt()
        runCatching { decodeThread.join(JOIN_MS) }
        // An image may still be in the detector: closing the reader would free it under its feet.
        val deadline = SystemClock.elapsedRealtime() + JOIN_MS
        while (busy.get() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        imageThread.quitSafely()
        runCatching { imageThread.join(JOIN_MS) }
        reader.close()
    }

    private companion object {
        const val TAG = "AnalysisDecoder"
        const val WIDTH = 960
        const val HEIGHT = 720
        const val MAX_IMAGES = 3
        const val QUEUE_CAPACITY = 8
        const val POLL_MS = 50L
        const val INPUT_TIMEOUT_US = 10_000L
        const val FRAME_US = 33_333L
        const val JOIN_MS = 500L
    }
}
