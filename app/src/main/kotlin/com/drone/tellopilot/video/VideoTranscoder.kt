package com.drone.tellopilot.video

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
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
import kotlinx.coroutines.withContext
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

    /**
     * Transformer runs on its own Looper: its synchronous cancel (codec release) must never
     * stall the main thread at takeoff.
     */
    private val looper by lazy { HandlerThread("tello-transcode").apply { start() }.looper }
    private val dispatcher by lazy { Handler(looper).asCoroutineDispatcher() }

    /** Whether the phone has an encoder for [codec] at all. */
    fun isSupported(codec: TranscodeCodec): Boolean = encoders(codec).isNotEmpty()

    /** Hardware encoder available (fast, low power)? */
    fun isHardware(codec: TranscodeCodec): Boolean = encoders(codec).any { it.isHardwareAccelerated }

    private fun encoders(codec: TranscodeCodec) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime(codec), true) } }

    /**
     * Transcodes [input] into [output]; [onProgress] gets 0..1. Cancelling the calling coroutine
     * cancels the export. Throws on failure; the output is then deleted. [overlay] (optional)
     * is burnt into every frame (telemetry); it is released by Media3 with the export.
     */
    @OptIn(UnstableApi::class)
    suspend fun transcode(
        input: Uri,
        output: File,
        codec: TranscodeCodec,
        sourceBitrate: Int,
        overlay: TextureOverlay? = null,
        onProgress: (Float) -> Unit,
    ) = coroutineScope {
        val bitrate = (sourceBitrate * when (codec) {
            TranscodeCodec.HEVC -> HEVC_RATIO
            TranscodeCodec.AV1 -> AV1_RATIO
        }).toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)
        val done = CompletableDeferred<Unit>()
        // NonCancellable: once started, the Transformer reference must never be lost (it could
        // not be cancelled any more and would keep encoding in flight).
        val transformer = withContext(dispatcher + NonCancellable) {
            Transformer.Builder(context)
                .setLooper(looper)
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
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(input)).setRemoveAudio(true)
                        .apply { overlay?.let { setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(it))))) } }
                        .build()
                    t.start(item, output.absolutePath)
                }
        }
        val progress = launch(dispatcher) {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(PROGRESS_POLL_MS)
            }
        }
        try {
            ensureActive()
            done.await()
        } catch (e: Throwable) {
            withContext(dispatcher + NonCancellable) { transformer.cancel() }
            output.delete()
            throw e
        } finally {
            progress.cancel()
        }
        onProgress(1f)
    }

    /**
     * The output is in the requested format (the encoder fallback must not silently produce
     * H.264) and lasts as long as the input (within 1 s or 3 %).
     */
    suspend fun verify(output: File, codec: TranscodeCodec, expectedDurationMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!output.isFile || output.length() == 0L) return@withContext false
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
        if (!mime(codec).equals(videoMime, ignoreCase = true)) return@withContext false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext false
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
    }
}
