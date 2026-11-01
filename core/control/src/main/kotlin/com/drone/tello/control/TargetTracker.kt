package com.drone.tello.control

import com.drone.tello.protocol.StickInput
import kotlin.math.abs

/** How the drone follows the locked target. */
enum class TrackingMode {
    /** Turns to keep the target centred and moves to keep its distance. */
    FOLLOW,

    /** Stays in place: only turns and climbs/descends to keep the target centred. */
    SPOTLIGHT,

    /** Circles around the target at constant distance, camera on it. */
    ORBIT,
}

/**
 * Target seen in one video frame, in normalised image coordinates (0..1, origin top-left).
 * [size] is the box height / image height: it grows as the target gets closer.
 */
data class TargetObservation(val centerX: Float, val centerY: Float, val size: Float)

data class TrackerConfig(
    /** Yaw stick per unit of horizontal error (error = centerX - 0.5, so -0.5..0.5). */
    val yawGain: Float = 1.2f,
    val maxYaw: Float = 0.5f,
    /** Throttle per unit of vertical error. */
    val throttleGain: Float = 1.0f,
    val maxThrottle: Float = 0.35f,
    /** Pitch per unit of relative size error ((ref - size) / ref). */
    val distanceGain: Float = 0.6f,
    val maxPitch: Float = 0.3f,
    /** Errors below these are ignored (no hunting around the centre). */
    val centreDeadBand: Float = 0.04f,
    val sizeDeadBand: Float = 0.12f,
    /** Sideways stick while orbiting (positive = to the right, i.e. counter-clockwise seen from above). */
    val orbitRoll: Float = 0.25f,
    /** Smoothing of the observations (0 = none, close to 1 = heavy). */
    val smoothing: Float = 0.5f,
    /** No observation for this long: sticks neutral (hover) while waiting for the target. */
    val holdMs: Long = 400,
    /** No observation for this long: target lost, tracking stops. */
    val lostMs: Long = 4_000,
    /** Where the target should sit vertically (a bit above centre frames a standing person well). */
    val targetY: Float = 0.45f,
)

sealed interface TrackerTick {
    data class Tracking(val sticks: StickInput, val target: TargetObservation?) : TrackerTick

    /** No target for [TrackerConfig.lostMs]: the app ends tracking (drone hovers). */
    data object Lost : TrackerTick
}

/**
 * Turns target observations into stick positions (proportional control, slow and bounded).
 * Pure and clock-driven, like [MissionRunner]: the app feeds [observe] from the detector and
 * calls [tick] at the stick rate. The reference distance is the target size when tracking starts.
 */
class TargetTracker(
    val mode: TrackingMode,
    private val config: TrackerConfig = TrackerConfig(),
    /** Orbit direction: +1 = to the right, -1 = to the left. */
    private val orbitDirection: Int = 1,
) {
    private var smoothed: TargetObservation? = null
    private var lastSeenAt = -1L
    private var referenceSize = -1f
    private var startedAt = -1L

    fun observe(observation: TargetObservation, nowMs: Long) {
        val previous = smoothed
        val a = config.smoothing
        smoothed = if (previous == null) observation else TargetObservation(
            centerX = previous.centerX * a + observation.centerX * (1 - a),
            centerY = previous.centerY * a + observation.centerY * (1 - a),
            size = previous.size * a + observation.size * (1 - a),
        )
        if (referenceSize < 0) referenceSize = observation.size
        lastSeenAt = nowMs
    }

    fun tick(nowMs: Long): TrackerTick {
        if (startedAt < 0) startedAt = nowMs
        val target = smoothed
        val sinceSeen = if (lastSeenAt < 0) nowMs - startedAt else nowMs - lastSeenAt
        if (sinceSeen >= config.lostMs) return TrackerTick.Lost
        if (target == null || sinceSeen >= config.holdMs) return TrackerTick.Tracking(StickInput.Neutral, null)

        val xError = (target.centerX - 0.5f).deadBand(config.centreDeadBand)
        val yError = (config.targetY - target.centerY).deadBand(config.centreDeadBand)
        val yaw = (xError * config.yawGain).coerceIn(-config.maxYaw, config.maxYaw)
        val throttle = (yError * config.throttleGain).coerceIn(-config.maxThrottle, config.maxThrottle)
        val pitch = if (mode == TrackingMode.SPOTLIGHT || referenceSize <= 0f) {
            0f
        } else {
            val sizeError = ((referenceSize - target.size) / referenceSize).deadBand(config.sizeDeadBand)
            (sizeError * config.distanceGain).coerceIn(-config.maxPitch, config.maxPitch)
        }
        val roll = if (mode == TrackingMode.ORBIT) config.orbitRoll * orbitDirection else 0f
        return TrackerTick.Tracking(StickInput(roll = roll, pitch = pitch, throttle = throttle, yaw = yaw), target)
    }

    private fun Float.deadBand(band: Float) = if (abs(this) <= band) 0f else this - band * kotlin.math.sign(this)
}
