package com.drone.tello.control

import kotlin.math.abs
import kotlin.math.sign

/** The four stick axes as the drone sees them (whatever the stick layout). */
enum class StickAxis { PITCH, ROLL, YAW, THROTTLE }

/**
 * Response of one axis to the pilot's stick, applied in this order (each step in 0..1 on the
 * magnitude, the sign of the stick is kept):
 *
 * 1. dead zone (global): `|v| <= deadZone` gives an exact 0, the rest is stretched to 0..1;
 * 2. gain ([gain], "sensibilité"): `x = min(1, x * gain)`. Above 100 % the stick reaches the
 *    maximum before the end of its travel (at `1 / gain`), then the output stays there;
 * 3. expo ([expo]): `y = (1 - e) * x + e * x³`, finer control around the centre;
 * 4. amplitude ([amplitude], "sortie max"): `y * amplitude`, caps the command sent;
 * 5. tripod: the [StickMixer.scale] applied by the mixer afterwards.
 *
 * Why the gain before the expo: the gain acts like a shorter stick travel, so the whole expo
 * curve (soft centre, then progressive) is kept and simply squeezed into the first `1 / gain`
 * of the travel: the full command is reached at a predictable point, and the expo slider keeps
 * the same meaning whatever the gain. Applied after the expo, the gain would scale the curve up
 * and clip it, the saturation point would then depend on the expo too.
 * The amplitude comes after the expo so that a lower maximum keeps the whole stick travel
 * useful (no dead end of travel) with the same curve shape.
 *
 * With gain 1 and amplitude 1 the result is bit for bit the historical [StickShaping] output.
 */
data class AxisTuning(
    val gain: Float = 1f,
    val amplitude: Float = 1f,
    val expo: Float = DEFAULT_EXPO,
) {
    /** Finite values within their ranges (NaN gives the default). */
    fun sanitized(defaultExpo: Float = DEFAULT_EXPO): AxisTuning = AxisTuning(
        gain = gain.bounded(GAIN_RANGE, 1f),
        amplitude = amplitude.bounded(AMPLITUDE_RANGE, 1f),
        expo = expo.bounded(EXPO_RANGE, defaultExpo.bounded(EXPO_RANGE, DEFAULT_EXPO)),
    )

    /**
     * Stick command for a raw deflection [raw] (-1..1, NaN and out of range tolerated),
     * always in -1..1 and never NaN. [deadZone] is clamped to 0..0.5.
     */
    fun apply(raw: Float, deadZone: Float): Float {
        if (raw.isNaN()) return 0f
        val dz = deadZone.bounded(0f..0.5f, 0f)
        val g = gain.bounded(GAIN_RANGE, 1f)
        val a = amplitude.bounded(AMPLITUDE_RANGE, 1f)
        val e = expo.bounded(EXPO_RANGE, DEFAULT_EXPO)
        val v = raw.coerceIn(-1f, 1f)
        val magnitude = abs(v)
        if (magnitude <= dz) return 0f
        val scaled = (magnitude - dz) / (1f - dz)
        val gained = (scaled * g).coerceAtMost(1f)
        val curved = (1f - e) * gained + e * gained * gained * gained
        return sign(v) * curved * a
    }

    companion object {
        const val DEFAULT_EXPO = 0.3f
        val GAIN_RANGE = 0.5f..2f
        val AMPLITUDE_RANGE = 0.2f..1f
        val EXPO_RANGE = 0f..0.8f
        val Default = AxisTuning()

        internal fun Float.bounded(range: ClosedFloatingPointRange<Float>, default: Float): Float =
            if (isNaN()) default else coerceIn(range)
    }
}

/** The response of the four axes for one flight mode (normal / slow, or fast). */
data class FlightTuning(
    val pitch: AxisTuning = AxisTuning.Default,
    val roll: AxisTuning = AxisTuning.Default,
    val yaw: AxisTuning = AxisTuning.Default,
    val throttle: AxisTuning = AxisTuning.Default,
) {
    operator fun get(axis: StickAxis): AxisTuning = when (axis) {
        StickAxis.PITCH -> pitch
        StickAxis.ROLL -> roll
        StickAxis.YAW -> yaw
        StickAxis.THROTTLE -> throttle
    }

    fun with(axis: StickAxis, tuning: AxisTuning): FlightTuning = when (axis) {
        StickAxis.PITCH -> copy(pitch = tuning)
        StickAxis.ROLL -> copy(roll = tuning)
        StickAxis.YAW -> copy(yaw = tuning)
        StickAxis.THROTTLE -> copy(throttle = tuning)
    }

    fun sanitized(defaultExpo: Float = AxisTuning.DEFAULT_EXPO) = FlightTuning(
        pitch.sanitized(defaultExpo), roll.sanitized(defaultExpo), yaw.sanitized(defaultExpo), throttle.sanitized(defaultExpo),
    )

    companion object {
        val Default = FlightTuning()

        /** Every axis with gain and amplitude 100 % and the same [expo] (the historical behaviour). */
        fun uniform(expo: Float = AxisTuning.DEFAULT_EXPO) = AxisTuning(expo = expo).let { FlightTuning(it, it, it, it) }
    }
}

/**
 * Everything the [StickMixer] needs to turn the pilot's sticks into commands: the global dead
 * zone and one [FlightTuning] per flight mode, chosen by the fast mode flag at each mix.
 */
data class StickTuning(
    val deadZone: Float = 0.05f,
    val normal: FlightTuning = FlightTuning.Default,
    val fast: FlightTuning = FlightTuning.Default,
) {
    fun forMode(fastMode: Boolean): FlightTuning = if (fastMode) fast else normal

    companion object {
        /** The historical shaping: same dead zone and expo on every axis of both modes. */
        fun uniform(deadZone: Float = 0.05f, expo: Float = AxisTuning.DEFAULT_EXPO) =
            FlightTuning.uniform(expo).let { StickTuning(deadZone, it, it) }
    }
}

/**
 * One-tap starting points. Each one gives a [FlightTuning] for the normal mode and one for the
 * fast mode; the settings screen applies the one of the mode being edited.
 */
enum class TuningPreset {
    /** Smooth shots: lower maximum on every axis, soft centre, slow yaw. */
    CINEMA,
    /** The app defaults (gain and amplitude 100 %, expo 0.3). */
    STANDARD,
    /**
     * Feel of the official app: full amplitude and a squared curve (`y = x²`), which the cubic
     * expo approximates best (least squares on 0..1) with e ≈ 0.65.
     */
    OFFICIAL,
    /** Livelier pitch: same stick travel, more forward / backward command, less expo. */
    PITCH_BOOST,
    /** Every axis reaches its maximum early, little expo. */
    SPORT,
    ;

    fun tuning(fastMode: Boolean): FlightTuning = when (this) {
        CINEMA -> if (!fastMode) {
            FlightTuning(
                pitch = AxisTuning(gain = 1f, amplitude = 0.6f, expo = 0.5f),
                roll = AxisTuning(gain = 1f, amplitude = 0.6f, expo = 0.5f),
                yaw = AxisTuning(gain = 1f, amplitude = 0.4f, expo = 0.5f),
                throttle = AxisTuning(gain = 1f, amplitude = 0.6f, expo = 0.4f),
            )
        } else {
            FlightTuning(
                pitch = AxisTuning(gain = 1f, amplitude = 0.5f, expo = 0.6f),
                roll = AxisTuning(gain = 1f, amplitude = 0.5f, expo = 0.6f),
                yaw = AxisTuning(gain = 1f, amplitude = 0.35f, expo = 0.6f),
                throttle = AxisTuning(gain = 1f, amplitude = 0.5f, expo = 0.5f),
            )
        }
        STANDARD -> FlightTuning.Default
        OFFICIAL -> FlightTuning.uniform(OFFICIAL_EXPO)
        PITCH_BOOST -> if (!fastMode) {
            FlightTuning(pitch = AxisTuning(gain = 1.4f, amplitude = 1f, expo = 0.15f))
        } else {
            FlightTuning(pitch = AxisTuning(gain = 1.2f, amplitude = 1f, expo = 0.25f))
        }
        SPORT -> if (!fastMode) {
            FlightTuning(
                pitch = AxisTuning(gain = 1.4f, amplitude = 1f, expo = 0.15f),
                roll = AxisTuning(gain = 1.4f, amplitude = 1f, expo = 0.15f),
                yaw = AxisTuning(gain = 1.2f, amplitude = 1f, expo = 0.2f),
                throttle = AxisTuning(gain = 1.2f, amplitude = 1f, expo = 0.2f),
            )
        } else {
            FlightTuning(
                pitch = AxisTuning(gain = 1.25f, amplitude = 1f, expo = 0.3f),
                roll = AxisTuning(gain = 1.25f, amplitude = 1f, expo = 0.3f),
                yaw = AxisTuning(gain = 1.1f, amplitude = 1f, expo = 0.3f),
                throttle = AxisTuning(gain = 1.2f, amplitude = 1f, expo = 0.3f),
            )
        }
    }

    companion object {
        /** Cubic expo closest to the official `x²` curve. */
        const val OFFICIAL_EXPO = 0.65f
    }
}
