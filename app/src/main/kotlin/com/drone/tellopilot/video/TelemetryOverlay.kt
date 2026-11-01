package com.drone.tellopilot.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.drone.tello.control.OverlayStyle
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Units
import com.drone.tellopilot.flightlog.FlightLogStore
import com.drone.tellopilot.flightlog.FlightSampler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

/**
 * Builds the telemetry overlay of one video: resolves its wall-clock start, loads the
 * overlapping flight logs once and hands the overlay bitmaps to the transcoder, in the style
 * chosen by the pilot ([PilotSettings.overlayStyle]):
 * - [OverlayStyle.PILOT]: the flight screen HUD ([PilotHudOverlays]), with the items the pilot
 *   shows on screen ([PilotSettings.hiddenHud]);
 * - [OverlayStyle.COMPACT]: the bottom-left text box ([TelemetryBitmapOverlay]).
 *
 * - No usable start time (not a recorder file, no DATE_TAKEN): no overlay, plain re-encoding.
 * - No overlapping flight log: the compact box with the time of day only (still useful, and
 *   the pilot asked for an overlay), whatever the style.
 */
@Singleton
class TelemetryOverlayFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flights: FlightLogStore,
) {

    /** Overlays of one video ([overlays] empty: plain re-encoding) and why ([status]). */
    @OptIn(UnstableApi::class)
    class Built(val overlays: List<TextureOverlay>, val status: OverlayStatus) {
        constructor(overlay: TextureOverlay?, status: OverlayStatus) : this(listOfNotNull(overlay), status)
    }

    /** [onWaitingFlightLog]: called once if the flight file is still open (landing tail). */
    @OptIn(UnstableApi::class)
    suspend fun build(video: DroneVideo, pilot: PilotSettings, onWaitingFlightLog: () -> Unit = {}): Built {
        val zone = ZoneId.systemDefault()
        val nameMs = VideoStartTime.fromName(video.name, zone)
        val start = VideoStartTime.resolveDetailed(nameMs, video.dateTakenMs, video.durationMs) ?: run {
            Log.i(TAG, "${video.name}: overlay OFF, start time unknown (name=$nameMs, taken=${video.dateTakenMs})")
            return Built(emptyList(), OverlayStatus.NO_START_TIME)
        }
        Log.i(
            TAG,
            "${video.name}: start=${start.epochMs} from ${start.source} (name=$nameMs, taken=${video.dateTakenMs}, " +
                "duration=${video.durationMs} ms)",
        )
        if (flights.activeId != null) {
            Log.i(TAG, "${video.name}: flight log ${flights.activeId} still open, waiting for it")
            onWaitingFlightLog()
            awaitClosedFlight()
        }
        val endMs = start.epochMs + video.durationMs
        val entries = flights.list()
        val ranges = entries.map { FlightLogMatch.Range(it.id, it.startEpochMs, it.summary.durationMs) }
        val ids = FlightLogMatch.overlapping(ranges, start.epochMs, endMs)
        val logs = ids.mapNotNull { flights.load(it) }
        val track = OverlayTrack.of(logs)
        val covered = track.countBetween(start.epochMs, endMs)
        Log.i(
            TAG,
            "${video.name}: ${entries.size} flight log(s) on file, overlapping=$ids, loaded=${logs.size}, " +
                "samples=${track.size} (in the video: $covered)",
        )
        val status = if (covered == 0) OverlayStatus.TIME_ONLY else OverlayStatus.ACTIVE
        val pilotView = pilot.overlayStyle == OverlayStyle.PILOT && status == OverlayStatus.ACTIVE
        Log.i(TAG, "${video.name}: overlay ON, $status, ${if (pilotView) "pilot view, hidden=${pilot.hiddenHud}" else "compact"}")
        val compact = { listOf(TelemetryBitmapOverlay(track, start.epochMs, pilot.units, zone, Locale.getDefault())) }
        val overlays = if (pilotView) {
            try {
                PilotHudOverlays.create(track, OverlayMapTrack.of(logs), start.epochMs, pilot, PilotHudOverlays.texts(context.resources))
            } catch (e: Exception) {
                // Still an overlay: the compact box rather than none.
                Log.w(TAG, "${video.name}: pilot view unavailable, compact overlay", e)
                compact()
            }
        } else {
            compact()
        }
        return Built(overlays, status)
    }

    /**
     * Right after landing the flight file stays open for a few seconds (landing tail) and is
     * not listed yet: wait for it, so the automatic re-encoding gets the whole flight. Link
     * lost before the landing tail: the recorder only closes it after its no-data timeout.
     */
    private suspend fun awaitClosedFlight() {
        var waited = 0L
        while (flights.activeId != null && waited < MAX_FLIGHT_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        if (flights.activeId != null) Log.w(TAG, "flight log still open after $waited ms, going on without it")
    }

    private companion object {
        const val TAG = "TelemetryOverlay"
        const val MAX_FLIGHT_WAIT_MS = FlightSampler.NO_DATA_TIMEOUT_MS + FlightSampler.TAIL_MS + 10_000L
        const val POLL_MS = 500L
    }
}

/**
 * Bottom-left semi-transparent box with the overlay lines. One bitmap, redrawn only when the
 * text changes (a few times a second at most); Media3 re-uploads it on a new generation id.
 * Called on the video frame processing GL thread.
 */
@OptIn(UnstableApi::class)
class TelemetryBitmapOverlay(
    private val track: OverlayTrack,
    private val startEpochMs: Long,
    private val units: Units,
    private val zone: ZoneId,
    private val locale: Locale,
) : BitmapOverlay() {

    private var frameWidth = DEFAULT_WIDTH
    private var frameHeight = DEFAULT_HEIGHT
    private var bitmap: Bitmap? = null
    private var lastLines: List<String>? = null
    private var settings: OverlaySettings? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(BOX_ALPHA, 0, 0, 0) }
    private val box = RectF()

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize.width > 0 && videoSize.height > 0) {
            frameWidth = videoSize.width
            frameHeight = videoSize.height
        }
        bitmap = null
        settings = null
        lastLines = null
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val current = bitmap ?: try {
            createBitmap().also {
                bitmap = it
                // Proof that Media3 really draws the overlay (and with which geometry).
                Log.i(OVERLAY_TAG, "overlay drawn: frame ${frameWidth}x$frameHeight, box ${it.width}x${it.height}, first t=$presentationTimeUs us")
            }
        } catch (e: Exception) {
            failed(e)
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { bitmap = it }
        } catch (e: OutOfMemoryError) {
            failed(e)
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }
        if (failed) return current
        // A drawing error never breaks the export: the box is left out for the rest of the video.
        try {
            val epochMs = startEpochMs + presentationTimeUs / 1_000
            val lines = OverlayText.lines(epochMs, track.at(epochMs), units, zone, locale)
            if (lines != lastLines) {
                draw(current, lines)
                lastLines = lines
            }
        } catch (e: Exception) {
            failed(e)
            current.eraseColor(Color.TRANSPARENT)
        }
        return current
    }

    private var failed = false

    private fun failed(e: Throwable) {
        if (!failed) Log.w(OVERLAY_TAG, "overlay drawing failed, box left out", e)
        failed = true
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings ?: run {
        val margin = textSize()
        // NDC: (-1, -1) is the bottom-left corner of the frame.
        StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(-1f, -1f)
            .setBackgroundFrameAnchor(-1f + 2f * margin / frameWidth, -1f + 2f * margin / frameHeight)
            .build()
            .also { settings = it }
    }

    /** Readable at 960x720 (about 23 px) and proportional on larger frames. */
    private fun textSize(): Float = max(MIN_TEXT_PX, frameHeight * TEXT_RATIO)

    private fun createBitmap(): Bitmap {
        textPaint.textSize = textSize()
        val padding = textPaint.textSize * PADDING_RATIO
        val width = TEMPLATES.maxOf { textPaint.measureText(it) } + 2 * padding
        val height = MAX_LINES * lineHeight() + 2 * padding
        return Bitmap.createBitmap(ceil(width).toInt(), ceil(height).toInt(), Bitmap.Config.ARGB_8888)
    }

    private fun lineHeight() = textPaint.fontSpacing

    private fun draw(target: Bitmap, lines: List<String>) {
        target.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(target)
        val padding = textPaint.textSize * PADDING_RATIO
        val width = lines.maxOf { textPaint.measureText(it) } + 2 * padding
        val height = lines.size * lineHeight() + 2 * padding
        // Box at the bottom-left of the bitmap: the bitmap's bottom-left is anchored to the frame's.
        box.set(0f, target.height - height, minOf(width, target.width.toFloat()), target.height.toFloat())
        canvas.drawRoundRect(box, padding, padding, boxPaint)
        var baseline = box.top + padding - textPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, padding, baseline, textPaint)
            baseline += lineHeight()
        }
    }

    private companion object {
        const val OVERLAY_TAG = "TelemetryOverlay"
        const val DEFAULT_WIDTH = 960
        const val DEFAULT_HEIGHT = 720
        const val TEXT_RATIO = 0.032f
        const val MIN_TEXT_PX = 14f
        const val PADDING_RATIO = 0.4f
        const val BOX_ALPHA = 140
        const val MAX_LINES = 3
        /** Widest lines the box may hold (the bitmap keeps one size). */
        val TEMPLATES = listOf(
            "H 999.9 m   V 99.9 m/s",
            "H 9999 ft   V 99.9 mph",
            "↓ 99.9 m/s   Bat. 100 %",
            "↓ 99.9 mph   Bat. 100 %",
        )
    }
}
