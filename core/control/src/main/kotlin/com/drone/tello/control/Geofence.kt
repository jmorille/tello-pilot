package com.drone.tello.control

import kotlin.math.hypot

/** Safety bubble: a vertical cylinder around the start point. */
data class GeofenceConfig(
    val radiusMeters: Float = PilotSettings.GEOFENCE_RADIUS_DEFAULT,
    /** Ceiling, height sensor of the drone (relative to the ground under it). */
    val maxHeightMeters: Float = 10f,
    /** Fraction of the radius from which the pilot is warned. */
    val warnRatio: Float = 0.8f,
    /** Without a valid position for this long, the position is unknown. */
    val positionTimeoutMs: Long = 1_500,
    /**
     * Largest believable jump between two positions (plus [maxSpeed] x elapsed time); a gap
     * longer than [positionTimeoutMs] also makes the position untrustworthy for good.
     */
    val maxJumpMeters: Float = 1.0f,
    val maxSpeed: Float = 5f,
)

enum class FenceStatus {
    INSIDE,
    /** Beyond [GeofenceConfig.warnRatio] of the radius. */
    NEAR,
    /** At or beyond the radius. */
    OUTSIDE,
    /** Above the ceiling. */
    TOO_HIGH,
    /** No trustworthy position (no odometry, invalid, stale or jumping): the distance is unknown. */
    NO_POSITION,
}

/**
 * Distance from the start point, from the visual odometry of the drone's log stream. Only
 * differences to the first position are used: the orientation of the odometry axes does not
 * matter (their scale, assumed to be metres, is not validated in flight yet). Pure, clock-driven.
 *
 * A position jump that the drone cannot have flown (odometry reset, corrupt record), or a
 * position coming back after a long gap, makes the position untrustworthy for the rest of the
 * flight: [FenceStatus.NO_POSITION], never a silent re-anchoring. A reset that lands close to
 * the last position cannot be told apart from real motion.
 */
class Geofence(val config: GeofenceConfig) {
    private var startedAt = -1L
    private var originX = 0f
    private var originY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastValidAt = -1L
    private var lost = false

    /** Horizontal distance from the start point, null while unknown. */
    var distance: Float? = null
        private set

    /** A trustworthy distance is known. */
    val positionKnown: Boolean get() = distance != null && !lost

    /** Starts the clock of the position timeout (the first [onOdometry] or [status] call otherwise). */
    fun start(nowMs: Long) {
        if (startedAt < 0) startedAt = nowMs
    }

    fun onOdometry(x: Float, y: Float, valid: Boolean, nowMs: Long) {
        start(nowMs)
        if (!valid || !x.isFinite() || !y.isFinite() || lost) return
        if (lastValidAt < 0) {
            originX = x
            originY = y
        } else {
            val gapMs = (nowMs - lastValidAt).coerceAtLeast(0)
            val elapsed = gapMs / 1000f
            // After a long gap, continuity cannot be checked: an odometry restarted from a new
            // origin would look like a plausible position and hide the real distance.
            if (gapMs > config.positionTimeoutMs ||
                hypot(x - lastX, y - lastY) > config.maxJumpMeters + config.maxSpeed * elapsed
            ) {
                lost = true
                distance = null
                return
            }
        }
        lastX = x
        lastY = y
        lastValidAt = nowMs
        distance = hypot(x - originX, y - originY)
    }

    /**
     * [heightMeters]: current height (null = unknown, not checked). The ceiling is checked
     * first; then the distance, or [FenceStatus.NO_POSITION] once the position has been
     * unknown for [GeofenceConfig.positionTimeoutMs] (counted from [start] before the first fix).
     */
    fun status(heightMeters: Float?, nowMs: Long): FenceStatus {
        start(nowMs)
        if (heightMeters != null && heightMeters > config.maxHeightMeters) return FenceStatus.TOO_HIGH
        val since = if (lastValidAt < 0) startedAt else lastValidAt
        val d = distance
        if (lost || d == null || nowMs - since > config.positionTimeoutMs) {
            // Short gaps (a lost packet) and the first instants are not a positioning failure.
            return if (lost || nowMs - since > config.positionTimeoutMs) FenceStatus.NO_POSITION else FenceStatus.INSIDE
        }
        return when {
            d >= config.radiusMeters -> FenceStatus.OUTSIDE
            d >= config.radiusMeters * config.warnRatio -> FenceStatus.NEAR
            else -> FenceStatus.INSIDE
        }
    }
}
