package com.drone.tellopilot.video

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
import com.drone.tello.control.Units
import com.drone.tellopilot.flightlog.FlightLogStore
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

/**
 * Builds the telemetry overlay of one video: resolves its wall-clock start, loads the
 * overlapping flight logs once and hands a [TelemetryBitmapOverlay] to the transcoder.
 *
 * - No usable start time (not a recorder file, no DATE_TAKEN): `null`, plain re-encoding.
 * - No overlapping flight log: the overlay shows the time of day only (still useful, and the
 *   pilot asked for an overlay); the same holds for the frames before the takeoff.
 */
@Singleton
class TelemetryOverlayFactory @Inject constructor(private val flights: FlightLogStore) {

    @OptIn(UnstableApi::class)
    suspend fun build(video: DroneVideo, units: Units): TextureOverlay? {
        val zone = ZoneId.systemDefault()
        val nameMs = VideoStartTime.fromName(video.name, zone)
        val startMs = VideoStartTime.resolve(nameMs, video.dateTakenMs) ?: run {
            Log.i(TAG, "${video.name}: start time unknown, no overlay")
            return null
        }
        awaitClosedFlight()
        val endMs = startMs + video.durationMs
        val ranges = flights.list().map { FlightLogMatch.Range(it.id, it.startEpochMs, it.summary.durationMs) }
        val logs = FlightLogMatch.overlapping(ranges, startMs, endMs).mapNotNull { flights.load(it) }
        val track = OverlayTrack.of(logs)
        Log.i(
            TAG,
            "${video.name}: start=$startMs (name=$nameMs, taken=${video.dateTakenMs}), ${logs.size} flight log(s)" +
                if (track.isEmpty) ", time only" else "",
        )
        return TelemetryBitmapOverlay(track, startMs, units, zone, Locale.getDefault())
    }

    /**
     * Right after landing the flight file stays open for a few seconds (landing tail) and is
     * not listed yet: wait for it, so the automatic re-encoding gets the whole flight.
     */
    private suspend fun awaitClosedFlight() {
        var waited = 0L
        while (flights.activeId != null && waited < MAX_FLIGHT_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    private companion object {
        const val TAG = "TelemetryOverlay"
        const val MAX_FLIGHT_WAIT_MS = 20_000L
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
        val epochMs = startEpochMs + presentationTimeUs / 1_000
        val lines = OverlayText.lines(epochMs, track.at(epochMs), units, zone, locale)
        val current = bitmap ?: createBitmap().also { bitmap = it }
        if (lines != lastLines) {
            draw(current, lines)
            lastLines = lines
        }
        return current
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
