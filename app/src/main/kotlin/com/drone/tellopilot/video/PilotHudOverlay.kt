package com.drone.tellopilot.video

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.drone.tello.control.PilotSettings
import com.drone.tellopilot.R
import com.drone.tellopilot.hud.HudColors
import com.drone.tellopilot.hud.HorizonLayout
import com.drone.tellopilot.hud.HudMath
import com.drone.tellopilot.hud.TrackMapMath
import com.drone.tellopilot.ui.flight.Warning
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Pilot view" overlay: the flight screen HUD (top-left panel, alerts and mini-map; artificial
 * horizon at the bottom, centre or corner as placed by the pilot) burnt into a re-encoded video, at the same places and in the
 * same colours, scaled to the frame. The toolbar icons and the sticks are not drawn.
 *
 * Two bitmaps, so that the horizon (which moves at almost every frame) does not redraw and
 * re-upload the larger top-left one: each is redrawn only when its part of [PilotHudFrame]
 * changes (Media3 re-uploads a bitmap only when its generation id changes).
 */
object PilotHudOverlays {
    /** The overlays of one video, in drawing order. */
    @OptIn(UnstableApi::class)
    fun create(track: OverlayTrack, map: OverlayMapTrack, startEpochMs: Long, pilot: PilotSettings, texts: HudTexts): List<BitmapOverlay> {
        val source = PilotHudSource(track, map, startEpochMs, pilot, texts)
        return listOf(TopLeftHudOverlay(source, map), HorizonHudOverlay(source, HorizonLayout.videoSlot(pilot.horizon)))
    }

    /** Format strings: the flight screen's own resources. */
    fun texts(res: Resources): HudTexts = HudTexts(
        battery = res.getString(R.string.hud_battery),
        height = res.getString(R.string.hud_height),
        heightFt = res.getString(R.string.hud_height_ft),
        speed = res.getString(R.string.hud_speed),
        speedMph = res.getString(R.string.hud_speed_mph),
        vario = res.getString(R.string.hud_vario),
        varioMph = res.getString(R.string.hud_vario_mph),
        time = res.getString(R.string.hud_time),
        temperature = res.getString(R.string.hud_temperature),
        wifi = res.getString(R.string.hud_wifi),
        heading = res.getString(R.string.hud_heading),
        homeDistance = res.getString(R.string.hud_home_distance),
        warning = { w, code -> if (w == Warning.MOTOR_ERROR) res.getString(w.textRes, code) else res.getString(w.textRes) },
    )
}

/** HUD frame of the video frame being processed, computed once for both overlays. */
internal class PilotHudSource(
    private val track: OverlayTrack,
    private val map: OverlayMapTrack,
    private val startEpochMs: Long,
    private val pilot: PilotSettings,
    private val texts: HudTexts,
) {
    private var lastPtsUs = Long.MIN_VALUE
    private var last: PilotHudFrame? = null

    fun at(presentationTimeUs: Long): PilotHudFrame? {
        if (presentationTimeUs != lastPtsUs) {
            val epochMs = startEpochMs + presentationTimeUs / 1_000
            last = PilotHud.frame(track.at(epochMs), map, map.at(epochMs), pilot, texts)
            lastPtsUs = presentationTimeUs
        }
        return last
    }
}

/**
 * Flight screen dp at the scale of the video: the 4:3 video fills the screen height in the
 * default framing, i.e. ~412 dp on a usual phone in landscape (1.75 px per dp at 720 px).
 */
internal class HudScale(frameHeight: Int) {
    private val pxPerDp = frameHeight / REFERENCE_SCREEN_HEIGHT_DP

    fun dp(v: Float): Float = v * pxPerDp

    private companion object {
        const val REFERENCE_SCREEN_HEIGHT_DP = 412f
    }
}

/**
 * Bitmap overlay that never breaks the export: a drawing error is logged once and the overlay
 * goes transparent for the rest of the video (the frames themselves are unharmed). Counts its
 * redraws and their cost (logged at the end of the export).
 */
@OptIn(UnstableApi::class)
internal abstract class SafeHudOverlay(private val name: String) : BitmapOverlay() {
    protected var frameWidth = DEFAULT_WIDTH
        private set
    protected var frameHeight = DEFAULT_HEIGHT
        private set
    protected var scale = HudScale(DEFAULT_HEIGHT)
        private set
    private var bitmap: Bitmap? = null
    private var settings: OverlaySettings? = null
    private var failed = false
    private var frames = 0L
    private var redraws = 0L
    private var drawNs = 0L

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize.width > 0 && videoSize.height > 0) {
            frameWidth = videoSize.width
            frameHeight = videoSize.height
            scale = HudScale(frameHeight)
        }
        bitmap = null
        settings = null
        onConfigure()
    }

    final override fun getBitmap(presentationTimeUs: Long): Bitmap {
        frames++
        val current = bitmap ?: try {
            createBitmap().also {
                bitmap = it
                Log.i(TAG, "$name: frame ${frameWidth}x$frameHeight, bitmap ${it.width}x${it.height}")
            }
        } catch (e: Exception) {
            fail(e)
            return blank().also { bitmap = it }
        } catch (e: OutOfMemoryError) {
            fail(e)
            return blank().also { bitmap = it }
        }
        if (failed) return current
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            if (update(current, presentationTimeUs)) {
                redraws++
                drawNs += SystemClock.elapsedRealtimeNanos() - start
            }
        } catch (e: Exception) {
            fail(e)
            current.eraseColor(Color.TRANSPARENT)
        } catch (e: OutOfMemoryError) {
            fail(e)
            current.eraseColor(Color.TRANSPARENT)
        }
        return current
    }

    final override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings ?: anchor().also { settings = it }

    override fun release() {
        super.release()
        if (frames > 0) {
            Log.i(TAG, "$name: $frames frames, $redraws redraws, ${if (redraws > 0) drawNs / redraws / 1_000 else 0} us per redraw")
        }
    }

    private fun fail(e: Throwable) {
        if (!failed) Log.w(TAG, "$name: drawing failed, overlay part left out", e)
        failed = true
    }

    private fun blank() = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    /** Frame size known (cached state to drop). */
    protected open fun onConfigure() = Unit

    protected abstract fun createBitmap(): Bitmap

    /** Redraws [bitmap] if its content changed; true if it did. */
    protected abstract fun update(bitmap: Bitmap, presentationTimeUs: Long): Boolean

    protected abstract fun anchor(): OverlaySettings

    /** Overlay whose top-left corner is [x], [y] pixels from the frame's top-left corner. */
    protected fun topLeftAt(x: Float, y: Float): OverlaySettings = StaticOverlaySettings.Builder()
        // NDC: (-1, -1) is the bottom-left corner, (-1, 1) the top-left one.
        .setOverlayFrameAnchor(-1f, 1f)
        .setBackgroundFrameAnchor(-1f + 2f * x / frameWidth, 1f - 2f * y / frameHeight)
        .build()

    /** Overlay whose bottom-left corner is [left] pixels right of and [bottom] pixels above the frame's bottom-left corner. */
    protected fun bottomLeftAt(left: Float, bottom: Float): OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(-1f, -1f)
        .setBackgroundFrameAnchor(-1f + 2f * left / frameWidth, -1f + 2f * bottom / frameHeight)
        .build()

    protected fun textPaint(sizeSp: Float, weight: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = scale.dp(sizeSp)
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        // Video compression softens thin text on a bright sky: a faint shadow keeps it readable.
        setShadowLayer(scale.dp(1.2f), 0f, scale.dp(0.5f), TEXT_SHADOW)
    }

    protected fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    protected fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = scale.dp(widthDp)
        strokeCap = Paint.Cap.BUTT
    }

    /** Text vertically centred in a line of height [lineHeight] starting at [top]. */
    protected fun baseline(paint: Paint, top: Float, lineHeight: Float): Float {
        val fm = paint.fontMetrics
        return top + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    /** Small rounded label under an instrument (flight screen `HudLabel`), centred on [centerX]. */
    protected fun drawLabel(canvas: Canvas, text: String, centerX: Float, top: Float, paint: TextPaint, bg: Paint, rect: RectF) {
        val h = labelHeight()
        val w = paint.measureText(text) + 2 * scale.dp(6f)
        val y = top + scale.dp(2f)
        rect.set(centerX - w / 2, y, centerX + w / 2, y + h - scale.dp(2f))
        canvas.drawRoundRect(rect, scale.dp(6f), scale.dp(6f), bg)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, centerX, baseline(paint, rect.top, rect.height()), paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** Height of [drawLabel] (with its top gap). */
    protected fun labelHeight(): Float =
        scale.dp(2f) + scale.dp(TEXT_LINE_DP) + 2 * scale.dp(1f)

    companion object {
        const val TAG = "TelemetryOverlay"
        /** Line height of the screen's `Text` without a style (bodyLarge: 24 sp, kept with a smaller font). */
        const val TEXT_LINE_DP = 24f
        const val DEFAULT_WIDTH = 960
        const val DEFAULT_HEIGHT = 720
        val TEXT_SHADOW = Color.argb(150, 0, 0, 0)
    }
}

/**
 * Top-left corner: the panel (status dot, battery, height, speed, vario, flight time,
 * temperature, Wi-Fi) wrapping like the screen's, the two most severe alerts under it, then
 * the mini-map with the distance back to the takeoff point.
 */
@OptIn(UnstableApi::class)
internal class TopLeftHudOverlay(private val source: PilotHudSource, private val map: OverlayMapTrack) : SafeHudOverlay("pilot HUD panel") {
    private var lastKey: Any? = UNSET
    private lateinit var panelText: TextPaint
    private lateinit var warningText: TextPaint
    private lateinit var labelText: TextPaint
    private val panelBg = fill(HudColors.PANEL_BG)
    private val dot = fill(HudColors.STATUS_OK)
    private val criticalBg = fill(HudColors.WARNING_CRITICAL)
    private val warningBg = fill(HudColors.WARNING)
    private val mapBg = fill(HudColors.MAP_BG)
    private val labelBg = fill(HudColors.LABEL_BG)
    private val home = fill(HudColors.HOME)
    private val drone = fill(HudColors.MARKER)
    private lateinit var trackPaint: Paint
    private lateinit var arrowPaint: Paint
    private val rect = RectF()
    private val path = Path()
    /** Alert banners: a handful of distinct texts per video, laid out once. */
    private val layouts = HashMap<Pair<String, Int>, StaticLayout>()
    /** Map path built for this range and box (rebuilt only when the track grows). */
    private var pathKey: Any? = null

    override fun onConfigure() {
        lastKey = UNSET
        layouts.clear()
        pathKey = null
        // labelLarge, SemiBold (panel); bodyLarge (alerts); 12 sp (instrument labels).
        panelText = textPaint(14f, 600)
        warningText = textPaint(16f, 400)
        labelText = textPaint(12f, 400)
        trackPaint = stroke(HudColors.TRACK, 2f)
        arrowPaint = stroke(Color.WHITE, 2f)
    }

    /** Panel width before wrapping: the screen's HUD shares its row with the toolbar icons. */
    private fun panelMaxWidth() = frameWidth * PANEL_WIDTH_RATIO

    override fun createBitmap(): Bitmap {
        val margin = scale.dp(MARGIN_DP)
        val width = min(frameWidth - 2 * margin, max(panelMaxWidth(), scale.dp(WARNING_MAX_DP)))
        val height = frameHeight * HEIGHT_RATIO
        return Bitmap.createBitmap(ceil(width).toInt(), ceil(height).toInt(), Bitmap.Config.ARGB_8888)
    }

    override fun anchor(): OverlaySettings = scale.dp(MARGIN_DP).let { topLeftAt(it, it) }

    override fun update(bitmap: Bitmap, presentationTimeUs: Long): Boolean {
        val frame = source.at(presentationTimeUs)
        val key = frame?.let { Triple(it.panel, it.warnings, it.map) }
        if (key == lastKey) return false
        lastKey = key
        bitmap.eraseColor(Color.TRANSPARENT)
        frame ?: return true
        val canvas = Canvas(bitmap)
        var y = drawPanel(canvas, frame.panel, bitmap.width.toFloat())
        frame.warnings.forEach { y = drawWarning(canvas, it, y + scale.dp(6f), bitmap.width.toFloat()) }
        frame.map?.let {
            // The screen puts the map 64 dp from the top, the panel 12 dp: same place unless the
            // panel and the alerts reach lower (then right under them, never over them).
            drawMap(canvas, it, max(scale.dp(MAP_TOP_DP - MARGIN_DP), y + scale.dp(6f)), bitmap.height.toFloat())
        }
        return true
    }

    /** FlowRow of the screen: padding 10/4 dp, 12 dp between items, 2 dp between lines. Returns its bottom. */
    private fun drawPanel(canvas: Canvas, items: List<PanelItem>, maxWidth: Float): Float {
        val padH = scale.dp(10f)
        val padV = scale.dp(4f)
        val gapH = scale.dp(12f)
        val gapV = scale.dp(2f)
        val lineHeight = scale.dp(20f)
        val dotSize = scale.dp(10f)
        val limit = min(maxWidth, panelMaxWidth()) - 2 * padH
        // Layout: (x, line) of the status dot then of each item.
        val widths = listOf(dotSize) + items.map { panelText.measureText(it.text) }
        val xs = FloatArray(widths.size)
        val lines = IntArray(widths.size)
        var x = 0f
        var line = 0
        var widest = 0f
        widths.forEachIndexed { i, w ->
            if (x > 0f && x + w > limit) {
                line++
                x = 0f
            }
            xs[i] = x
            lines[i] = line
            widest = max(widest, x + w)
            x += w + gapH
        }
        val height = 2 * padV + (line + 1) * lineHeight + line * gapV
        rect.set(0f, 0f, widest + 2 * padH, height)
        canvas.drawRoundRect(rect, scale.dp(10f), scale.dp(10f), panelBg)
        fun top(i: Int) = padV + lines[i] * (lineHeight + gapV)
        canvas.drawCircle(padH + dotSize / 2, top(0) + lineHeight / 2, dotSize / 2, dot)
        items.forEachIndexed { k, item ->
            val i = k + 1
            panelText.color = when (item.accent) {
                HudAccent.NORMAL -> Color.WHITE
                HudAccent.ALERT -> HudColors.BATTERY_LOW
                HudAccent.WARM -> HudColors.MARKER
            }
            canvas.drawText(item.text, padH + xs[i], baseline(panelText, top(i), lineHeight), panelText)
        }
        panelText.color = Color.WHITE
        return height
    }

    /** Alert banner: rounded, red (critical) or orange, padding 12/4 dp, at most 320 dp wide. Returns its bottom. */
    private fun drawWarning(canvas: Canvas, w: WarningFrame, top: Float, maxWidth: Float): Float {
        val padH = scale.dp(12f)
        val padV = scale.dp(4f)
        val textWidth = (min(maxWidth, scale.dp(WARNING_MAX_DP)) - 2 * padH).toInt().coerceAtLeast(1)
        val layout = layouts.getOrPut(w.text to textWidth) {
            // bodyLarge: 24 sp per line, as on screen.
            val extra = scale.dp(TEXT_LINE_DP) - warningText.fontSpacing
            StaticLayout.Builder.obtain(w.text, 0, w.text.length, warningText, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(extra, 1f)
                .setMaxLines(MAX_WARNING_LINES)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        var lineWidth = 0f
        for (i in 0 until layout.lineCount) lineWidth = max(lineWidth, layout.getLineWidth(i))
        rect.set(0f, top, lineWidth + 2 * padH, top + layout.height + 2 * padV)
        canvas.drawRoundRect(rect, scale.dp(8f), scale.dp(8f), if (w.warning.critical) criticalBg else warningBg)
        canvas.withTranslation(padH, top + padV) { layout.draw(this) }
        return rect.bottom
    }

    /** Screen `TrackMap`: 110 dp box, 8 dp inner padding, the track, takeoff point, drone, arrow home. */
    private fun drawMap(canvas: Canvas, m: MapFrame, top: Float, maxBottom: Float) {
        val box = scale.dp(110f)
        if (top + box + labelHeight() > maxBottom) return
        rect.set(0f, top, box, top + box)
        canvas.drawRoundRect(rect, scale.dp(8f), scale.dp(8f), mapBg)
        val inner = box - 2 * scale.dp(8f)
        val cx = box / 2
        val cy = top + box / 2
        val r = m.range
        val homeX = map.x(r.from)
        val homeY = map.y(r.from)
        val span = TrackMapMath.span(r.count, homeX, homeY, { map.x(r.from + it) }, { map.y(r.from + it) })
        val k = TrackMapMath.scale(inner / 2, span)
        fun sx(i: Int) = TrackMapMath.screenX(map.x(i), homeX, cx, k)
        fun sy(i: Int) = TrackMapMath.screenY(map.y(i), homeY, cy, k)
        val key = Triple(r, top, inner)
        if (key != pathKey) {
            path.rewind()
            path.moveTo(sx(r.from), sy(r.from))
            for (i in r.from + 1..r.to) path.lineTo(sx(i), sy(i))
            pathKey = key
        }
        canvas.drawPath(path, trackPaint)
        val dotR = scale.dp(4f)
        canvas.drawCircle(cx, cy, dotR, home)
        val dx = sx(r.to)
        val dy = sy(r.to)
        canvas.drawCircle(dx, dy, dotR, drone)
        if (TrackMapMath.showsArrow(TrackMapMath.distance(homeX, homeY, map.x(r.to), map.y(r.to)))) {
            val angle = TrackMapMath.arrowAngle(dx, dy, cx, cy)
            val len = scale.dp(14f)
            canvas.drawLine(dx, dy, dx + cos(angle) * len, dy + sin(angle) * len, arrowPaint)
        }
        drawLabel(canvas, m.label, cx, top + box, labelText, labelBg, rect)
    }

    private companion object {
        val UNSET = Any()
        const val MARGIN_DP = 12f
        const val MAP_TOP_DP = 64f
        const val WARNING_MAX_DP = 320f
        const val MAX_WARNING_LINES = 2
        const val PANEL_WIDTH_RATIO = 0.62f
        /** Room for a wrapped panel, two alerts and the map; the rest of the frame stays clear. */
        const val HEIGHT_RATIO = 0.8f
    }
}

/**
 * Screen `AttitudeIndicator`: 96 dp disc, heading label under it, in the [slot] matching where the
 * pilot put it on screen ([HorizonLayout.videoSlot]): 96 dp above the bottom centre (as on the
 * screen, above the land button), or in the bottom left / right corner of the video.
 */
@OptIn(UnstableApi::class)
internal class HorizonHudOverlay(
    private val source: PilotHudSource,
    private val slot: com.drone.tello.control.HorizonAnchor = com.drone.tello.control.HorizonAnchor.CENTER,
) : SafeHudOverlay("pilot HUD horizon") {
    private var lastKey: Any? = UNSET
    private lateinit var labelText: TextPaint
    private val sky = fill(HudColors.SKY)
    private val ground = fill(HudColors.GROUND)
    private val marker = fill(HudColors.MARKER)
    private val labelBg = fill(HudColors.LABEL_BG)
    private lateinit var horizonLine: Paint
    private lateinit var markerLine: Paint
    private lateinit var ring: Paint
    private val rect = RectF()
    private val clip = Path()

    override fun onConfigure() {
        lastKey = UNSET
        labelText = textPaint(12f, 400)
        horizonLine = stroke(Color.WHITE, 2f)
        markerLine = stroke(HudColors.MARKER, 3f)
        ring = stroke(HudColors.RING, 1f)
    }

    override fun createBitmap(): Bitmap {
        val width = scale.dp(WIDTH_DP)
        val height = scale.dp(DISC_DP) + labelHeight()
        return Bitmap.createBitmap(ceil(width).toInt(), ceil(height).toInt(), Bitmap.Config.ARGB_8888)
    }

    override fun anchor(): OverlaySettings {
        val width = ceil(scale.dp(WIDTH_DP))
        // The disc is centred in a wider box: the side margin is measured from the disc.
        val margin = (scale.dp(SIDE_MARGIN_DP) - (width - scale.dp(DISC_DP)) / 2f).coerceAtLeast(0f)
        val left = HorizonLayout.videoLeft(slot, frameWidth.toFloat(), width, margin)
        val bottom = if (slot == com.drone.tello.control.HorizonAnchor.CENTER) BOTTOM_DP else SIDE_BOTTOM_DP
        return bottomLeftAt(left, scale.dp(bottom))
    }

    override fun update(bitmap: Bitmap, presentationTimeUs: Long): Boolean {
        val h = source.at(presentationTimeUs)?.horizon
        if (h == lastKey) return false
        lastKey = h
        bitmap.eraseColor(Color.TRANSPARENT)
        h ?: return true
        val canvas = Canvas(bitmap)
        val size = scale.dp(DISC_DP)
        val r = size / 2
        val left = (bitmap.width - size) / 2
        val cx = left + r
        val cy = r
        clip.rewind()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.withSave {
            clipPath(clip)
            rotate(HudMath.horizonRotation(h.rollDeg), cx, cy)
            translate(left, HudMath.horizonShift(h.pitchDeg, r))
            // Same rectangles as the screen, in the disc's own coordinates.
            drawRect(-r, -r * 2, r * 3, r, sky)
            drawRect(-r, r, r * 3, r * 4, ground)
            drawLine(-r, r, r * 3, r, horizonLine)
        }
        // Fixed aircraft symbol.
        canvas.drawLine(left + r * 0.35f, cy, left + r * 0.8f, cy, markerLine)
        canvas.drawLine(left + r * 1.2f, cy, left + r * 1.65f, cy, markerLine)
        canvas.drawCircle(cx, cy, scale.dp(3f), marker)
        canvas.drawCircle(cx, cy, r - scale.dp(1f), ring)
        drawLabel(canvas, h.label, cx, size, labelText, labelBg, rect)
        return true
    }

    private companion object {
        val UNSET = Any()
        const val DISC_DP = 96f
        const val BOTTOM_DP = 96f
        /** Bottom corners: no land button under it in the video, closer to the edges. */
        const val SIDE_BOTTOM_DP = 24f
        const val SIDE_MARGIN_DP = 12f
        /** Disc and the widest heading label ("Cap 359° NO"). */
        const val WIDTH_DP = 140f
    }
}
