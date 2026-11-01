package com.drone.tellopilot.video

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.drone.tello.control.TranscodeCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Re-encodes a flight video (H.264 from the drone) to HEVC or AV1 with Media3 Transformer:
 * hardware decoder → GPU frame pipeline → encoder (HEVC: Pixel hardware encoder; AV1: software
 * encoder, slow). The bitrate is chosen from the original one for a visually close result.
 */
@Singleton
class VideoTranscoder @Inject constructor(@ApplicationContext private val context: Context) {

    /** Whether the phone has an encoder for [codec] at all. */
    fun isSupported(codec: TranscodeCodec): Boolean = encoders(codec).isNotEmpty()

    /** Hardware encoder available (fast, low power)? */
    fun isHardware(codec: TranscodeCodec): Boolean = encoders(codec).any { it.isHardwareAccelerated }

    private fun encoders(codec: TranscodeCodec) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime(codec), true) } }

    /**
     * Transcodes [input] into [output]; [onProgress] gets 0..1. Cancelling the calling coroutine
     * cancels the export. Throws on failure; the output is then deleted. [overlays] (optional)
     * are burnt into every frame, in order (telemetry); Media3 releases them with the export.
     * [onStall]: true when the export has made no progress for a while (false once it moves
     * again); after [STALL_MS] without progress it is stopped ([TranscodeStalledException]).
     */
    @OptIn(UnstableApi::class)
    suspend fun transcode(
        input: Uri,
        output: File,
        codec: TranscodeCodec,
        sourceBitrate: Int,
        overlays: List<TextureOverlay> = emptyList(),
        onStall: (Boolean) -> Unit = {},
        onProgress: (Float) -> Unit,
    ) = coroutineScope {
        Log.i(TAG, "export ${output.name}: $codec, overlays=${overlays.size}")
        val bitrate = (sourceBitrate * when (codec) {
            TranscodeCodec.HEVC -> HEVC_RATIO
            TranscodeCodec.AV1 -> AV1_RATIO
        }).toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)
        // One Looper per export: Transformer's synchronous cancel (codec release) never stalls
        // the main thread at takeoff, and an export that hangs its thread cannot block the next.
        val thread = HandlerThread("tello-transcode").apply { start() }
        val handler = Handler(thread.looper)
        val dispatcher = handler.asCoroutineDispatcher()
        val done = CompletableDeferred<Unit>()
        // NonCancellable: once started, the Transformer reference must never be lost (it could
        // not be cancelled any more and would keep encoding in flight).
        val transformer = try {
            withContext(dispatcher + NonCancellable) { start(thread, codec, bitrate, done, input, overlays, output) }
        } catch (e: Throwable) {
            thread.quitSafely()
            throw e
        }
        // Watchdog (off the export thread, which may be the one hanging): an export may stop
        // without ever calling back (codec, GL, overlay...). No progress for too long: stopped.
        val stallMs = if (codec == TranscodeCodec.AV1) STALL_MS_SOFTWARE else STALL_MS
        val watchdog = StallWatchdog(STALL_WARN_MS, stallMs, SystemClock.elapsedRealtime())
        val progress = launch(Dispatchers.Default) {
            val holder = ProgressHolder()
            var warned = false
            while (true) {
                // getProgress must run on the export thread; a blocked thread gives no value
                // (posted, not withContext: the wait must end even if the block never runs).
                val answer = CompletableDeferred<Int?>()
                handler.post {
                    answer.complete(
                        runCatching {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
                        }.getOrNull(),
                    )
                }
                val percent = withTimeoutOrNull(PROGRESS_POLL_MS * 4) { answer.await() }
                percent?.let { onProgress(it / 100f) }
                when (watchdog.update(SystemClock.elapsedRealtime(), percent?.let { it / 100f })) {
                    StallWatchdog.State.OK -> if (warned) {
                        warned = false
                        onStall(false)
                    }
                    StallWatchdog.State.WARN -> if (!warned) {
                        warned = true
                        Log.w(TAG, "no export progress for ${STALL_WARN_MS / 1000} s (last $percent %)")
                        onStall(true)
                    }
                    StallWatchdog.State.STALLED -> {
                        Log.w(TAG, "export stalled: no progress for ${stallMs / 1000} s (last $percent %), stopping it")
                        done.completeExceptionally(TranscodeStalledException(stallMs))
                        break
                    }
                }
                delay(PROGRESS_POLL_MS)
            }
        }
        try {
            ensureActive()
            done.await()
        } catch (e: Throwable) {
            withContext(NonCancellable) { cancelBounded(transformer, handler) }
            output.delete()
            throw e
        } finally {
            // Joined: a late progress value must not land on the next export's board.
            withContext(NonCancellable) { progress.cancelAndJoin() }
            thread.quitSafely()
        }
        onProgress(1f)
    }

    /** Builds and starts the export on [thread] (its Looper receives the callbacks). */
    @OptIn(UnstableApi::class)
    private fun start(
        thread: HandlerThread,
        codec: TranscodeCodec,
        bitrate: Int,
        done: CompletableDeferred<Unit>,
        input: Uri,
        overlays: List<TextureOverlay>,
        output: File,
    ): Transformer = Transformer.Builder(context)
        .setLooper(thread.looper)
        .setVideoMimeType(mime(codec))
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .setEnableFallback(true)
                .build(),
        )
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                done.complete(Unit)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                done.completeExceptionally(exportException)
            }
        })
        .build()
        .also { t ->
            // The overlay is an effect of the video item: the source is always decoded and
            // re-encoded (H.264 to HEVC/AV1, never a transmux), so it reaches every frame.
            val item = EditedMediaItem.Builder(MediaItem.fromUri(input)).setRemoveAudio(true)
                .apply { if (overlays.isNotEmpty()) setEffects(Effects(emptyList(), listOf(OverlayEffect(overlays)))) }
                .build()
            t.start(item, output.absolutePath)
        }

    /**
     * Cancels [transformer] on its thread, waiting at most [CANCEL_TIMEOUT_MS]: Transformer's
     * cancel blocks until its internal thread is released, which a hung export may never do.
     * The thread is then abandoned (one per export) and the queue goes on.
     */
    @OptIn(UnstableApi::class)
    private suspend fun cancelBounded(transformer: Transformer, handler: Handler) {
        val cancelled = CompletableDeferred<Unit>()
        val posted = handler.post {
            runCatching { transformer.cancel() }.onFailure { Log.w(TAG, "cancel failed", it) }
            cancelled.complete(Unit)
        }
        if (!posted || withTimeoutOrNull(CANCEL_TIMEOUT_MS) { cancelled.await() } == null) {
            Log.w(TAG, "export not cancelled within ${CANCEL_TIMEOUT_MS / 1000} s, its thread is abandoned")
        }
    }

    /**
     * The output is in the requested format (the encoder fallback must not silently produce
     * H.264) and lasts as long as the input (within 1 s or 3 %).
     */
    suspend fun verify(output: File, codec: TranscodeCodec, expectedDurationMs: Long): Boolean {
        // Bounded: a native media call that never returns (it ignores interruption) must not
        // block the queue, so the check runs detached and is abandoned on timeout.
        val job = CoroutineScope(Dispatchers.IO).async { check(output, codec, expectedDurationMs) }
        return withTimeoutOrNull(VERIFY_TIMEOUT_MS) { job.await() }
            ?: false.also {
                job.cancel()
                Log.w(TAG, "output check of ${output.name} timed out")
            }
    }

    private fun check(output: File, codec: TranscodeCodec, expectedDurationMs: Long): Boolean = run {
        if (!output.isFile || output.length() == 0L) return@run false
        val videoMime = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    .firstOrNull { it?.startsWith("video/") == true }
            } finally {
                extractor.release()
            }
        }.getOrNull()
        if (!mime(codec).equals(videoMime, ignoreCase = true)) return@run false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@run false
            expectedDurationMs <= 0 || abs(duration - expectedDurationMs) <= maxOf(1_000L, expectedDurationMs * 3 / 100)
        } catch (_: Exception) {
            false
        } finally {
            retriever.release()
        }
    }

    private fun mime(codec: TranscodeCodec) = when (codec) {
        TranscodeCodec.HEVC -> MimeTypes.VIDEO_H265
        TranscodeCodec.AV1 -> MimeTypes.VIDEO_AV1
    }

    private companion object {
        /** Same visual quality at about half the H.264 bitrate (HEVC), a bit less for AV1. */
        const val HEVC_RATIO = 0.55
        const val AV1_RATIO = 0.45
        const val MIN_BITRATE = 600_000
        const val MAX_BITRATE = 3_000_000
        const val PROGRESS_POLL_MS = 500L
        const val TAG = "Transcode"
        /** No progress for this long: shown as stalled. */
        const val STALL_WARN_MS = 30_000L
        /** No progress for this long: the export is stopped (hardware encoder). */
        const val STALL_MS = 120_000L
        /** Software encoder (AV1): much slower, 1 % of a long video may take minutes. */
        const val STALL_MS_SOFTWARE = 360_000L
        const val VERIFY_TIMEOUT_MS = 30_000L
        const val CANCEL_TIMEOUT_MS = 10_000L
    }
}

/** The export made no progress for [stallMs] and was stopped. */
class TranscodeStalledException(val stallMs: Long) : Exception("no progress for ${stallMs / 1000} s")
