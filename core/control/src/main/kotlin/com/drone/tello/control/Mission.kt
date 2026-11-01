package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.StickInput
import kotlin.math.abs
import kotlin.math.sign

/** Horizontal direction of a [MissionStep.Move], relative to where the drone's nose points. */
enum class MoveDirection { FORWARD, BACK, LEFT, RIGHT }

/**
 * One step of a programmed mission. The Tello has no GPS: heights are measured (height sensor),
 * distances by integrating the ground speed of the downward vision system, rotations are timed.
 */
sealed interface MissionStep {
    /** Climbs (or descends if negative) by [meters] from the current height. */
    data class Climb(val meters: Float) : MissionStep

    data class Move(val direction: MoveDirection, val meters: Float) : MissionStep

    /** Turns by [degrees], clockwise if positive (timed, so approximate). */
    data class Rotate(val degrees: Float) : MissionStep

    data class Hover(val seconds: Float) : MissionStep

    /** Compact text form, used to store missions: `C1.5`, `MF2`, `R-90`, `H3`. */
    fun encode(): String = when (this) {
        is Climb -> "C$meters"
        is Move -> "M${direction.name.first()}$meters"
        is Rotate -> "R$degrees"
        is Hover -> "H$seconds"
    }

    companion object {
        fun decode(text: String): MissionStep? = runCatching {
            when (text.firstOrNull()) {
                'C' -> Climb(text.drop(1).toFloat())
                'M' -> Move(
                    MoveDirection.entries.first { it.name.first() == text[1] },
                    text.drop(2).toFloat(),
                )
                'R' -> Rotate(text.drop(1).toFloat())
                'H' -> Hover(text.drop(1).toFloat())
                else -> null
            }
        }.getOrNull()?.takeIf { it.isValid() }

        /** Bounds that keep a mission reasonable indoors as outdoors. */
        val CLIMB_RANGE = -5f..5f
        val MOVE_RANGE = 0.5f..10f
        val ROTATE_RANGE = -360f..360f
        val HOVER_RANGE = 0.5f..30f
    }

    fun isValid(): Boolean = when (this) {
        is Climb -> meters in CLIMB_RANGE && meters != 0f
        is Move -> meters in MOVE_RANGE
        is Rotate -> degrees in ROTATE_RANGE && degrees != 0f
        is Hover -> seconds in HOVER_RANGE
    }
}

data class Mission(val name: String, val steps: List<MissionStep>) {
    /** `name|step;step;…` — the name never contains `|`, `;` or a line break. */
    fun encode(): String = cleanName(name) + "|" + steps.joinToString(";") { it.encode() }

    companion object {
        fun cleanName(name: String) = name.replace(Regex("[|;\\n\\r]"), " ").trim().take(40)

        fun decode(text: String): Mission? {
            val name = text.substringBefore('|', missingDelimiterValue = "").trim()
            val steps = text.substringAfter('|', missingDelimiterValue = "").split(';')
                .filter { it.isNotBlank() }.map { MissionStep.decode(it) ?: return null }
            return if (name.isEmpty() || steps.isEmpty()) null else Mission(name, steps)
        }

        /** Missions offered out of the box. */
        val Presets = listOf(
            Mission("Carré 2 m", listOf(
                MissionStep.Move(MoveDirection.FORWARD, 2f), MissionStep.Move(MoveDirection.RIGHT, 2f),
                MissionStep.Move(MoveDirection.BACK, 2f), MissionStep.Move(MoveDirection.LEFT, 2f),
            )),
            Mission("Aller-retour 3 m", listOf(
                MissionStep.Move(MoveDirection.FORWARD, 3f), MissionStep.Hover(2f),
                MissionStep.Rotate(180f), MissionStep.Move(MoveDirection.FORWARD, 3f), MissionStep.Rotate(180f),
            )),
            Mission("Panorama 360°", listOf(MissionStep.Rotate(360f))),
            Mission("Révélation : recul et montée", listOf(
                MissionStep.Move(MoveDirection.BACK, 3f), MissionStep.Climb(1.5f), MissionStep.Hover(2f),
                MissionStep.Climb(-1.5f), MissionStep.Move(MoveDirection.FORWARD, 3f),
            )),
        )
    }
}

/** Tuning of the mission runner; the defaults are deliberately slow (indoor-safe). */
data class MissionConfig(
    /** Stick level of horizontal moves (about 0.6 m/s on a Tello). */
    val moveStick: Float = 0.35f,
    /** Distance before the target where the stick is released (the drone glides on). */
    val brakingMeters: Float = 0.25f,
    /** End of a move: ground speed under which the drone is considered stopped. */
    val stoppedSpeed: Float = 0.15f,
    val maxBrakeMs: Long = 2_500,
    /** Height loop: stick per metre of error, limited to [maxClimbStick]. */
    val climbGain: Float = 0.8f,
    val maxClimbStick: Float = 0.5f,
    val heightTolerance: Float = 0.15f,
    val heightSettleMs: Long = 400,
    /** Lowest target height of a descent (never lands by itself). */
    val minHeight: Float = 0.4f,
    /** Rotation speed at [yawStick] (timed rotations). */
    val yawStick: Float = 0.5f,
    val yawDegreesPerSecond: Float = 70f,
    /** Average speed at [moveStick]: a move taking 3x longer than expected is abandoned. */
    val expectedSpeed: Float = 0.5f,
    /** Telemetry older than this aborts the mission (link lost). */
    val telemetryTimeoutMs: Long = 700,
    /** Every step, whatever it is, is abandoned after this long (longer than the longest hover). */
    val stepTimeoutMs: Long = 45_000,
)

/** What the mission runner wants this tick. */
sealed interface MissionTick {
    data class Running(val sticks: StickInput, val stepIndex: Int) : MissionTick
    data object Finished : MissionTick
    data class Aborted(val reason: MissionAbort) : MissionTick
}

enum class MissionAbort { NOT_FLYING, LINK_LOST, LOW_BATTERY, NO_POSITIONING, STEP_TIMEOUT }

/**
 * Executes a [Mission] one step at a time, by computing stick positions from telemetry.
 * Pure and clock-driven ([tick] receives the time): fully testable, called by the app at the
 * stick rate. It never lands nor takes off: at the end (or on any problem) it hands back
 * neutral sticks, i.e. a hover, and the pilot is in charge again.
 */
class MissionRunner(val mission: Mission, private val config: MissionConfig = MissionConfig()) {

    private var index = 0
    private var stepStart = -1L
    private var lastTick = -1L
    private var lastTelemetryAt = -1L
    private var lastFlight: FlightData? = null

    // Per-step state.
    private var targetHeight = 0f
    private var settledSince = -1L
    private var travelled = 0f
    private var brakingSince = -1L
    private var aborted: MissionAbort? = null

    val stepIndex: Int get() = index

    /** Feeds a telemetry sample (call on each flight data packet). */
    fun onTelemetry(flight: FlightData, nowMs: Long) {
        lastFlight = flight
        lastTelemetryAt = nowMs
    }

    fun tick(nowMs: Long): MissionTick {
        aborted?.let { return MissionTick.Aborted(it) }
        if (index >= mission.steps.size) return MissionTick.Finished
        val flight = lastFlight
        val problem = when {
            flight == null || nowMs - lastTelemetryAt > config.telemetryTimeoutMs -> MissionAbort.LINK_LOST
            !flight.flying -> MissionAbort.NOT_FLYING
            flight.batteryCritical -> MissionAbort.LOW_BATTERY
            else -> null
        }
        if (problem != null) return abort(problem)
        flight!!
        val dt = if (lastTick < 0) 0L else (nowMs - lastTick).coerceIn(0, 200)
        lastTick = nowMs
        val step = mission.steps[index]
        if (stepStart < 0) startStep(step, flight, nowMs)
        if (nowMs - stepStart > config.stepTimeoutMs) return abort(MissionAbort.STEP_TIMEOUT)

        val sticks = when (step) {
            is MissionStep.Hover -> if (nowMs - stepStart >= (step.seconds * 1000).toLong()) null else StickInput.Neutral
            is MissionStep.Rotate -> {
                val duration = (abs(step.degrees) / config.yawDegreesPerSecond * 1000).toLong()
                if (nowMs - stepStart >= duration) null else StickInput(yaw = config.yawStick * sign(step.degrees))
            }
            is MissionStep.Climb -> climb(flight, nowMs)
            is MissionStep.Move -> {
                if (flight.positioningUnavailable) return abort(MissionAbort.NO_POSITIONING)
                move(step, flight, nowMs, dt)
                    ?: return if (stepTimedOut(step, nowMs)) abort(MissionAbort.STEP_TIMEOUT) else nextStep(nowMs)
            }
        }
        if (sticks == null) return nextStep(nowMs)
        if (step is MissionStep.Move && stepTimedOut(step, nowMs)) return abort(MissionAbort.STEP_TIMEOUT)
        return MissionTick.Running(sticks, index)
    }

    private fun stepTimedOut(step: MissionStep.Move, nowMs: Long): Boolean {
        val expectedMs = (step.meters / config.expectedSpeed * 1000).toLong()
        return nowMs - stepStart > expectedMs * 3 + config.maxBrakeMs
    }

    private fun startStep(step: MissionStep, flight: FlightData, nowMs: Long) {
        stepStart = nowMs
        settledSince = -1L
        travelled = 0f
        brakingSince = -1L
        if (step is MissionStep.Climb) {
            targetHeight = (flight.heightMeters + step.meters).coerceAtLeast(config.minHeight)
        }
    }

    private fun nextStep(nowMs: Long): MissionTick {
        index++
        stepStart = -1L
        return if (index >= mission.steps.size) MissionTick.Finished else tick(nowMs)
    }

    private fun abort(reason: MissionAbort): MissionTick {
        aborted = reason
        return MissionTick.Aborted(reason)
    }

    private fun climb(flight: FlightData, nowMs: Long): StickInput? {
        val error = targetHeight - flight.heightMeters
        if (abs(error) <= config.heightTolerance) {
            if (settledSince < 0) settledSince = nowMs
            if (nowMs - settledSince >= config.heightSettleMs) return null
        } else {
            settledSince = -1L
        }
        val throttle = (error * config.climbGain).coerceIn(-config.maxClimbStick, config.maxClimbStick)
        return StickInput(throttle = throttle)
    }

    /** Null when the move (including the braking glide) is over. */
    private fun move(step: MissionStep.Move, flight: FlightData, nowMs: Long, dt: Long): StickInput? {
        travelled += flight.horizontalSpeed * dt / 1000f
        val braking = minOf(config.brakingMeters, step.meters / 2)
        if (brakingSince < 0 && travelled >= step.meters - braking) brakingSince = nowMs
        if (brakingSince >= 0) {
            val stopped = flight.horizontalSpeed <= config.stoppedSpeed
            return if (stopped || nowMs - brakingSince >= config.maxBrakeMs) null else StickInput.Neutral
        }
        val s = config.moveStick
        return when (step.direction) {
            MoveDirection.FORWARD -> StickInput(pitch = s)
            MoveDirection.BACK -> StickInput(pitch = -s)
            MoveDirection.RIGHT -> StickInput(roll = s)
            MoveDirection.LEFT -> StickInput(roll = -s)
        }
    }
}
