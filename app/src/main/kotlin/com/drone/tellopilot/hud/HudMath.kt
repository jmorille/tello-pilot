package com.drone.tellopilot.hud

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure HUD computations shared by the flight screen (Compose) and the overlay burnt into the
 * re-encoded videos (Android Canvas): both draw the same instruments from the same numbers.
 * No Android, unit tested on the JVM.
 */
object HudMath {
    const val FEET_PER_METER = 3.28084f
    const val MPH_PER_MS = 2.23694f

    /** Climb rates below this are noise: no direction arrow. */
    const val VARIO_DEADBAND = 0.1f

    /** Battery shown in red at or below this level. */
    const val LOW_BATTERY_PCT = 20

    /** Drone temperature shown in amber from this value. */
    const val HOT_DRONE_C = 60f

    /** The offset of the IMU temperature field is not confirmed: implausible values are dropped. */
    val PLAUSIBLE_IMU_TEMP_C = -10f..100f

    /** Pitch beyond which the horizon stops moving (it stays visible in the disc). */
    const val PITCH_LIMIT_DEG = 40f

    /** Horizon shift at [PITCH_LIMIT_DEG], as a fraction of the disc radius. */
    const val PITCH_SHIFT_RATIO = 0.6f

    private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    fun isPlausibleImuTemp(celsius: Float): Boolean = celsius.isFinite() && celsius in PLAUSIBLE_IMU_TEMP_C

    fun batteryLow(percent: Int): Boolean = percent <= LOW_BATTERY_PCT

    fun droneHot(celsius: Float): Boolean = celsius >= HOT_DRONE_C

    /** Direction arrow of the vario. */
    fun varioArrow(climbMps: Float): String = when {
        climbMps >= VARIO_DEADBAND -> "↑"
        climbMps <= -VARIO_DEADBAND -> "↓"
        else -> "↕"
    }

    /** Heading in `[0, 360)` from any yaw (the drone reports -180..180). */
    fun heading(yawDeg: Float): Float {
        val h = ((yawDeg % 360f) + 360f) % 360f
        return if (h >= 360f) 0f else h
    }

    /** Whole degrees as written under the horizon (359.6° is written 0°). */
    fun headingDegrees(yawDeg: Float): Int = heading(yawDeg).roundToInt() % 360

    /** Cardinal point (French initials: O = ouest) of a heading in degrees. */
    fun cardinal(headingDeg: Float): String = CARDINALS[((heading(headingDeg) + 22.5f) / 45f).toInt() % 8]

    /**
     * Downward shift of the horizon line for [pitchDeg], in the unit of [radius]: nose up
     * moves the horizon down. Clamped to ±[PITCH_LIMIT_DEG] so the horizon stays in the disc.
     */
    fun horizonShift(pitchDeg: Float, radius: Float): Float =
        (pitchDeg.coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG) / PITCH_LIMIT_DEG) * radius * PITCH_SHIFT_RATIO

    /** Canvas rotation of the horizon (degrees, clockwise) for a roll angle: it counters the roll. */
    fun horizonRotation(rollDeg: Float): Float = -rollDeg

    /**
     * Interpolation between two angles in degrees along the shortest way (359° -> 1° goes
     * through 0°, never back through 180°). Result in `[0, 360)`.
     */
    fun lerpAngle(a: Float, b: Float, f: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return heading(a + d * f)
    }

    /** [lerpAngle] for angles in `[-180, 180)` (roll): 170° -> -170° goes through 180°. */
    fun lerpSignedAngle(a: Float, b: Float, f: Float): Float {
        val h = lerpAngle(a, b, f)
        return if (h >= 180f) h - 360f else h
    }

    /** [value] rounded to a multiple of [step] (limits the redraws of a moving instrument). */
    fun quantize(value: Float, step: Float): Float = Math.round(value / step) * step
}

/**
 * Mini-map of the visual-odometry track: scale, distance and arrow back to the takeoff point.
 * Odometry axes: x to the right, y forward (screen up), as reported by the drone.
 */
object TrackMapMath {
    /** The map shows at least ±[MIN_SPAN_M] around the takeoff point. */
    const val MIN_SPAN_M = 2f

    /** Under this distance the drone is "home": no arrow. */
    const val ARROW_MIN_DISTANCE_M = 0.3f

    /** A new track point every 10 cm. */
    const val STEP_M = 0.1f

    /** Bounded track: the oldest points go first. */
    const val MAX_POINTS = 2_000

    /** Half-size of the map, in metres around the takeoff point: the whole track fits. */
    inline fun span(count: Int, homeX: Float, homeY: Float, x: (Int) -> Float, y: (Int) -> Float): Float {
        var span = MIN_SPAN_M
        for (i in 0 until count) span = max(span, max(abs(x(i) - homeX), abs(y(i) - homeY)))
        return span
    }

    /** Pixels per metre for a map of half-size [halfSizePx] showing ±[spanM]. */
    fun scale(halfSizePx: Float, spanM: Float): Float = halfSizePx / spanM

    /** Screen x of a track point (the takeoff point at [centerX]). */
    fun screenX(x: Float, homeX: Float, centerX: Float, scale: Float): Float = centerX + (x - homeX) * scale

    /** Screen y of a track point: forward is up. */
    fun screenY(y: Float, homeY: Float, centerY: Float, scale: Float): Float = centerY - (y - homeY) * scale

    fun distance(homeX: Float, homeY: Float, x: Float, y: Float): Float = hypot(x - homeX, y - homeY)

    fun showsArrow(distanceM: Float): Boolean = distanceM > ARROW_MIN_DISTANCE_M

    /** Screen angle (radians) of the arrow from the drone towards the takeoff point. */
    fun arrowAngle(droneX: Float, droneY: Float, homeX: Float, homeY: Float): Float = atan2(homeY - droneY, homeX - droneX)

    /** A new point is kept when it is at least [STEP_M] away from the last one. */
    fun isNewPoint(lastX: Float, lastY: Float, x: Float, y: Float): Boolean = hypot(x - lastX, y - lastY) >= STEP_M
}

/** ARGB colours of the HUD, shared by the flight screen and the video overlay. */
object HudColors {
    const val SKY = 0xCC1E88E5.toInt()
    const val GROUND = 0xCC8D6E63.toInt()
    /** Aircraft symbol, drone on the map, amber texts. */
    const val MARKER = 0xFFFFCA28.toInt()
    const val RING = 0x99FFFFFF.toInt()
    const val TRACK = 0xFF4FC3F7.toInt()
    const val HOME = 0xFF66BB6A.toInt()
    const val MAP_BG = 0x99000000.toInt()
    const val LABEL_BG = 0x99000000.toInt()
    /** Top panel: black at 30 %. */
    const val PANEL_BG = 0x4D000000
    const val STATUS_OK = 0xFF66BB6A.toInt()
    const val BATTERY_LOW = 0xFFEF5350.toInt()
    const val WARNING_CRITICAL = 0xCCD32F2F.toInt()
    const val WARNING = 0xCCF57C00.toInt()
}
