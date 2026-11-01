package com.drone.tello.control

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Thresholds of [ImpactDetector]. The Tello tilts about 15-20° in normal flight and up to
 * ~30° when braking hard in fast mode (horizontal acceleration g·tan 30° ≈ 5.7 m/s²): every
 * threshold keeps a wide margin above that, at the cost of missing gentle touches.
 */
data class ImpactConfig(
    /** Roll or pitch beyond this, on [tiltSamples] consecutive samples: the drone is tumbling. */
    val maxTiltDeg: Float = 55f,
    val tiltSamples: Int = 2,
    /** Roll or pitch change between two samples (≤ [maxSampleGapMs] apart) beyond this… */
    val attitudeJumpDeg: Float = 60f,
    /** …still this far from the previous attitude one sample later (a glitch comes back). */
    val attitudeJumpConfirmDeg: Float = 40f,
    /** Velocity change rate (from the IMU velocities) beyond this, m/s²… */
    val maxAccel: Float = 10f,
    /** …for a velocity change of at least this much (noise on close samples), m/s. */
    val minDeltaV: Float = 0.7f,
    /** Samples closer than this are not differentiated (the reference is kept). */
    val minSampleGapMs: Long = 20,
    /** Samples further apart than this are not compared (a link gap is not a shock). */
    val maxSampleGapMs: Long = 300,
    /** No detection while not flying, then during the takeoff (or throw) that follows. */
    val takeoffQuietMs: Long = 4_000,
    /** No detection during a manoeuvre (landing, flip, smart video, bounce) and this long after. */
    val maneuverQuietMs: Long = 1_000,
    /** After a detection, nothing more for this long (one alert per shock). */
    val cooldownMs: Long = 3_000,
)

enum class ImpactKind {
    /** Attitude beyond what a flying Tello reaches. */
    TILT,
    /** Sudden attitude change. */
    ATTITUDE_JUMP,
    /** Sudden velocity change. */
    SHOCK,
}

/**
 * Detects a collision or a propeller strike from the drone's internal log stream (IMU
 * attitude and velocities north / east / down: there is no raw accelerometer in the
 * telemetry, so the acceleration is the derivative of the filtered velocities). Pure and
 * clock-driven. Silent while not flying, during takeoffs, landings, flips and the drone's
 * own manoeuvres, and for a while after them ([ImpactConfig.takeoffQuietMs],
 * [ImpactConfig.maneuverQuietMs], [suppress]).
 */
class ImpactDetector(val config: ImpactConfig = ImpactConfig()) {

    private class Sample(val t: Long, val vn: Float, val ve: Float, val vd: Float, val roll: Float, val pitch: Float)

    private var quietUntil = Long.MIN_VALUE
    private var reference: Sample? = null
    private var tiltCount = 0
    /** Attitude before an unconfirmed jump, and when it happened. */
    private var jumpFrom: Sample? = null
    /** Velocities before an unconfirmed shock. */
    private var shockFrom: Sample? = null

    /** No detection until [nowMs] + [durationMs] (e.g. a flip was just commanded). */
    fun suppress(nowMs: Long, durationMs: Long) {
        quietUntil = max(quietUntil, nowMs + durationMs)
        reset()
    }

    /**
     * Feeds one IMU sample. [flying]: the drone reports flying; [maneuver]: something the drone
     * flies by itself or that legitimately shakes it (landing, flip, smart video, bounce, throw).
     * Returns the kind of impact detected by this sample, if any.
     */
    fun onSample(
        nowMs: Long,
        velocityNorth: Float,
        velocityEast: Float,
        velocityDown: Float,
        rollDeg: Float,
        pitchDeg: Float,
        flying: Boolean,
        maneuver: Boolean,
    ): ImpactKind? {
        if (!flying) {
            suppress(nowMs, config.takeoffQuietMs)
            return null
        }
        if (maneuver) {
            suppress(nowMs, config.maneuverQuietMs)
            return null
        }
        if (nowMs < quietUntil) {
            reset()
            return null
        }
        val values = floatArrayOf(velocityNorth, velocityEast, velocityDown, rollDeg, pitchDeg)
        if (!values.all { it.isFinite() }) return null
        val s = Sample(nowMs, velocityNorth, velocityEast, velocityDown, rollDeg, pitchDeg)
        val kind = detect(s)
        if (kind != null) suppress(nowMs, config.cooldownMs)
        return kind
    }

    private fun detect(s: Sample): ImpactKind? {
        // 1. Tilt held beyond what flight needs.
        tiltCount = if (max(abs(s.roll), abs(s.pitch)) >= config.maxTiltDeg) tiltCount + 1 else 0
        if (tiltCount >= config.tiltSamples) return ImpactKind.TILT

        // 2. Attitude jump or velocity shock seen on the previous sample, confirmed by this one
        //    (a corrupt record comes back at once).
        //    An unconfirmed sample is dropped: the comparison goes on from the sample before it.
        var before: Sample? = null
        jumpFrom?.let { from ->
            jumpFrom = null
            if (s.t - from.t <= 2 * config.maxSampleGapMs && attitudeChange(from, s) >= config.attitudeJumpConfirmDeg) {
                return ImpactKind.ATTITUDE_JUMP
            }
            before = from
        }
        shockFrom?.let { from ->
            shockFrom = null
            if (s.t - from.t <= 2 * config.maxSampleGapMs && velocityChange(from, s) >= config.minDeltaV * SHOCK_CONFIRM) {
                return ImpactKind.SHOCK
            }
            before = from
        }
        before?.let { reference = it }

        val ref = reference
        if (ref == null) {
            reference = s
            return null
        }
        val dt = s.t - ref.t
        if (dt < config.minSampleGapMs) return null // keep the older reference
        reference = s
        if (dt > config.maxSampleGapMs) return null

        if (attitudeChange(ref, s) >= config.attitudeJumpDeg) jumpFrom = ref

        // 3. Velocity shock: a sudden change, then confirmed like the attitude jump (the
        //    velocity of a drone stopped or bounced back by an obstacle does not come back).
        val dv = velocityChange(ref, s)
        if (dv >= config.minDeltaV && dv / (dt / 1000f) >= config.maxAccel) shockFrom = ref
        return null
    }

    private fun velocityChange(a: Sample, b: Sample): Float {
        val dvn = b.vn - a.vn
        val dve = b.ve - a.ve
        val dvd = b.vd - a.vd
        return sqrt(dvn * dvn + dve * dve + dvd * dvd)
    }

    private fun attitudeChange(a: Sample, b: Sample) = max(abs(angleDiff(a.roll, b.roll)), abs(angleDiff(a.pitch, b.pitch)))

    /** Difference of two angles in (-180, 180] (roll wraps at ±180°). */
    private fun angleDiff(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    private fun reset() {
        reference = null
        tiltCount = 0
        jumpFrom = null
        shockFrom = null
    }

    private companion object {
        /** Part of the velocity change still present one sample later. */
        const val SHOCK_CONFIRM = 0.5f
    }
}
