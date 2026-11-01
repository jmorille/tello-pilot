package com.drone.tellopilot.hud

import com.drone.tello.control.HorizonAnchor
import com.drone.tello.control.HorizonPlacement
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle (any unit, y down). */
data class HudRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = !(width > 0f && height > 0f)

    /** Strict overlap: rectangles that only touch do not overlap (with a rounding tolerance). */
    fun overlaps(o: HudRect): Boolean =
        left < o.right - EPS && o.left < right - EPS && top < o.bottom - EPS && o.top < bottom - EPS

    fun inflate(d: Float) = HudRect(left - d, top - d, right + d, bottom + d)

    companion object {
        const val EPS = 0.01f

        fun centered(x: Float, y: Float, width: Float, height: Float) =
            HudRect(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
    }
}

data class HudPoint(val x: Float, val y: Float) {
    fun distanceTo(o: HudPoint): Float = hypot(x - o.x, y - o.y)
}

/**
 * Flight screen layout the artificial horizon must fit in. All values in the same unit (pixels
 * on screen), relative to the screen's top-left corner.
 */
data class HorizonGeometry(
    val screenWidth: Float,
    val screenHeight: Float,
    /** Where the instrument may go: the screen minus the system bars / cutout and a margin. */
    val bounds: HudRect,
    /** Visible video: its sides delimit the black bands (none when it fills the width). */
    val video: HudRect,
    /** Size of the instrument (disc and heading label). */
    val width: Float,
    val height: Float,
    /** Never covered: the sticks, the takeoff / land button, the top bar. */
    val obstacles: List<HudRect>,
    /** Distance kept between the instrument and the obstacles. */
    val gap: Float,
    /** Bottom-centre snap: distance between the bottom of the instrument and the bottom of the screen. */
    val centerBottomGap: Float,
)

/**
 * Placement of the movable artificial horizon (pure, unit tested on the JVM): snap positions,
 * bounds, avoidance of the sticks and of the takeoff / land button, and drop of a drag.
 *
 * Rules:
 * - the instrument always stays entirely inside [HorizonGeometry.bounds];
 * - it never covers an obstacle once placed: it moves to the nearest free spot (it may cross
 *   them while being dragged, it is pushed off on release);
 * - snapped places are recomputed from the layout, so they follow the framing and the screen.
 */
object HorizonLayout {

    /** Centre of the instrument for [placement] in [g]. */
    fun center(placement: HorizonPlacement, g: HorizonGeometry): HudPoint {
        placement.anchor?.let { return snapTargets(g).getValue(it) }
        val p = placement.sanitized()
        return constrain(HudPoint(p.x * g.screenWidth, p.y * g.screenHeight), g)
    }

    /**
     * Snap positions, each already kept on screen and off the obstacles:
     * - [HorizonAnchor.CENTER]: bottom centre, [HorizonGeometry.centerBottomGap] above the bottom;
     * - [HorizonAnchor.LEFT] / [HorizonAnchor.RIGHT]: middle of the black band (or against the
     *   screen side when the video fills the width), vertically in the middle of the largest free
     *   space of that column (between the top bar and the stick).
     */
    fun snapTargets(g: HorizonGeometry): Map<HorizonAnchor, HudPoint> {
        val b = g.bounds
        val leftX = (b.left + max(b.left, min(g.video.left, b.right))) / 2f
        val rightX = (b.right + min(b.right, max(g.video.right, b.left))) / 2f
        val raw = mapOf(
            HorizonAnchor.LEFT to HudPoint(leftX, freeColumnMiddle(clampX(leftX, g), g)),
            HorizonAnchor.CENTER to HudPoint(g.video.centerX, g.screenHeight - g.centerBottomGap - g.height / 2f),
            HorizonAnchor.RIGHT to HudPoint(rightX, freeColumnMiddle(clampX(rightX, g), g)),
        )
        return raw.mapValues { constrain(it.value, g) }
    }

    /** The snap place within [radius] of [p] (the nearest one), or null. */
    fun nearestSnap(p: HudPoint, g: HorizonGeometry, radius: Float): HorizonAnchor? =
        snapTargets(g).entries
            .map { it.key to it.value.distanceTo(p) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first

    /**
     * End of a drag at [p]: snapped when near a snap place, otherwise free, at the nearest valid
     * spot, stored as fractions of the screen.
     */
    fun drop(p: HudPoint, g: HorizonGeometry, snapRadius: Float): HorizonPlacement {
        nearestSnap(p, g, snapRadius)?.let { return HorizonPlacement.snapped(it) }
        val c = constrain(p, g)
        return HorizonPlacement(
            anchor = null,
            x = if (g.screenWidth > 0f) c.x / g.screenWidth else 0.5f,
            y = if (g.screenHeight > 0f) c.y / g.screenHeight else HorizonPlacement.DEFAULT_Y,
        ).sanitized()
    }

    /** [p] kept so that the instrument stays inside the bounds (used while dragging). */
    fun clamp(p: HudPoint, g: HorizonGeometry): HudPoint = HudPoint(clampX(p.x, g), clampY(p.y, g))

    /** The instrument at [p] is inside the bounds and covers no obstacle. */
    fun isValid(p: HudPoint, g: HorizonGeometry): Boolean {
        val r = HudRect.centered(p.x, p.y, g.width, g.height)
        val b = g.bounds
        val inside = (g.width > b.width || (r.left >= b.left - HudRect.EPS && r.right <= b.right + HudRect.EPS)) &&
            (g.height > b.height || (r.top >= b.top - HudRect.EPS && r.bottom <= b.bottom + HudRect.EPS))
        return inside && g.obstacles.none { !it.isEmpty && r.overlaps(it.inflate(g.gap)) }
    }

    /**
     * Nearest valid spot to [p]: [p] kept inside the bounds, then, if it covers an obstacle,
     * moved just beside one (or two) of them, whichever is closest. When nothing is free
     * (tiny screen, huge sticks), it is only kept inside the bounds.
     */
    fun constrain(p: HudPoint, g: HorizonGeometry): HudPoint {
        val c = clamp(p, g)
        if (isValid(c, g)) return c
        val halfW = g.width / 2f
        val halfH = g.height / 2f
        val zones = g.obstacles.filterNot { it.isEmpty }.map { it.inflate(g.gap) }
        val xs = listOf(c.x) + zones.flatMap { listOf(it.left - halfW, it.right + halfW) }
        val ys = listOf(c.y) + zones.flatMap { listOf(it.top - halfH, it.bottom + halfH) }
        var best: HudPoint? = null
        var bestDistance = Float.MAX_VALUE
        for (x in xs) for (y in ys) {
            val q = clamp(HudPoint(x, y), g)
            if (!isValid(q, g)) continue
            val d = q.distanceTo(c)
            if (d < bestDistance) {
                best = q
                bestDistance = d
            }
        }
        return best ?: c
    }

    private fun clampX(x: Float, g: HorizonGeometry): Float = clampAxis(x, g.width, g.bounds.left, g.bounds.right)

    private fun clampY(y: Float, g: HorizonGeometry): Float = clampAxis(y, g.height, g.bounds.top, g.bounds.bottom)

    /** Centre kept so that a segment of [size] stays in [lo]..[hi] (centred when it is too large). */
    private fun clampAxis(v: Float, size: Float, lo: Float, hi: Float): Float {
        val min = lo + size / 2f
        val max = hi - size / 2f
        if (min > max) return (lo + hi) / 2f
        return if (v.isFinite()) v.coerceIn(min, max) else (lo + hi) / 2f
    }

    /**
     * Vertical middle of the largest free space, within the bounds, of the column of the
     * instrument's width centred on [x] (the obstacles that cross that column cut it).
     */
    internal fun freeColumnMiddle(x: Float, g: HorizonGeometry): Float {
        val b = g.bounds
        val left = x - g.width / 2f
        val right = x + g.width / 2f
        val cuts = g.obstacles.filterNot { it.isEmpty }.map { it.inflate(g.gap) }
            .filter { it.left < right - HudRect.EPS && left < it.right - HudRect.EPS }
            .sortedBy { it.top }
        var bestTop = b.top
        var bestBottom = b.top
        var cursor = b.top
        for (o in cuts) {
            val gapBottom = min(o.top, b.bottom)
            if (gapBottom - cursor > bestBottom - bestTop) {
                bestTop = cursor
                bestBottom = gapBottom
            }
            cursor = max(cursor, o.bottom)
        }
        if (b.bottom - cursor > bestBottom - bestTop) {
            bestTop = cursor
            bestBottom = b.bottom
        }
        return (bestTop + bestBottom) / 2f
    }

    /**
     * Where the "pilot view" overlay draws the horizon in a re-encoded video. The video has no
     * black bands: a snapped horizon takes the matching bottom corner (LEFT: bottom left, CENTER:
     * bottom centre, RIGHT: bottom right); a free one takes the slot of the third of the screen
     * its centre is in. Always at the bottom: the top left holds the panel, alerts and mini-map.
     */
    fun videoSlot(placement: HorizonPlacement): HorizonAnchor {
        placement.anchor?.let { return it }
        val x = placement.sanitized().x
        return when {
            x < 1f / 3f -> HorizonAnchor.LEFT
            x > 2f / 3f -> HorizonAnchor.RIGHT
            else -> HorizonAnchor.CENTER
        }
    }

    /**
     * Left edge, in the video frame, of a horizon box of [boxWidth] in [slot]: [margin] from
     * the side for LEFT / RIGHT, centred for CENTER, always entirely inside the frame.
     */
    fun videoLeft(slot: HorizonAnchor, frameWidth: Float, boxWidth: Float, margin: Float): Float {
        val x = when (slot) {
            HorizonAnchor.LEFT -> margin
            HorizonAnchor.CENTER -> (frameWidth - boxWidth) / 2f
            HorizonAnchor.RIGHT -> frameWidth - boxWidth - margin
        }
        return if (boxWidth >= frameWidth) (frameWidth - boxWidth) / 2f else x.coerceIn(0f, frameWidth - boxWidth)
    }
}
