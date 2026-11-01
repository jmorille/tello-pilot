package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.StickInput
import kotlin.math.abs
import kotlin.math.sign

/** Horizontal direction of a [MissionStep.Move], relative to where the drone's nose points. */
enum class MoveDirection(
    /** Direction of the move relative to the nose, degrees clockwise. */
    val angle: Float,
) { FORWARD(0f), BACK(180f), LEFT(-90f), RIGHT(90f) }

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

    /**
     * Farthest horizontal distance from the start of the planned path (metres), assuming
     * exact moves and rotations. Real flights drift (timed rotations, braking glide).
     */
    fun plannedMaxDistance(): Float {
        var heading = 0.0 // degrees, clockwise
        var x = 0.0 // right
        var y = 0.0 // forward
        var max = 0.0
        steps.forEach { step ->
            when (step) {
                is MissionStep.Rotate -> heading += step.degrees
                is MissionStep.Move -> {
                    val a = Math.toRadians(heading + step.direction.angle)
                    x += step.meters * kotlin.math.sin(a)
                    y += step.meters * kotlin.math.cos(a)
                    max = maxOf(max, kotlin.math.hypot(x, y))
                }
                else -> Unit
            }
        }
        return max.toFloat()
    }

    /**
     * Highest planned climb above the start height (metres, >= 0). A descent cut short by
     * [MissionConfig.minHeight] can make the real path higher: each climb is checked again.
     */
    fun plannedMaxClimb(): Float {
        var h = 0f
        var max = 0f
        steps.forEach { if (it is MissionStep.Climb) { h += it.meters; max = maxOf(max, h) } }
        return max
    }

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
    /**
     * Nose first: each horizontal move is flown forward, after turning toward it, so that the
     * camera sees where the drone goes (the Tello has no obstacle sensor; the pilot does).
     * See [MissionRunner] for how the heading is restored.
     */
    val noseFirst: Boolean = false,
    /**
     * Horizontal speed limit of the moves, m/s (null = [moveStick], no limit). The stick is
     * derived from it ([stickPerMps]) and released while the measured speed is above it.
     */
    val maxHorizontalSpeed: Float? = null,
    /** Stick per m/s of horizontal speed (about 0.6 m/s at 0.35, slow mode). */
    val stickPerMps: Float = 0.58f,
    /** Measured speed above the limit at which the stick is fully released (linear in between). */
    val speedMargin: Float = 0.3f,
)

/** What the mission runner wants this tick. */
sealed interface MissionTick {
    data class Running(val sticks: StickInput, val stepIndex: Int) : MissionTick
    data object Finished : MissionTick
    data class Aborted(val reason: MissionAbort) : MissionTick
}

enum class MissionAbort {
    NOT_FLYING, LINK_LOST, LOW_BATTERY, NO_POSITIONING, STEP_TIMEOUT,
    /** Out of (or planned out of) the safety bubble. */
    GEOFENCE,
    /** Shock or abnormal attitude detected (collision, propeller strike). */
    IMPACT,
}

/**
 * Executes a [Mission] one step at a time, by computing stick positions from telemetry.
 * Pure and clock-driven ([tick] receives the time): fully testable, called by the app at the
 * stick rate. It never lands nor takes off: at the end (or on any problem) it hands back
 * neutral sticks, i.e. a hover, and the pilot is in charge again.
 *
 * Nose first ([MissionConfig.noseFirst]): the steps keep their meaning (directions relative to
 * the nose at the start of the mission, turned by the mission's own rotations), but each
 * horizontal move is flown forward after turning toward it. The heading is not restored after
 * each move (that would turn the camera away again and double the timed, hence approximate,
 * rotations): the next move turns by the difference only, and the original heading is
 * restored once, at the end of the mission, so that the drone ends facing where it would
 * have without the option. A mission aborted midway leaves the drone facing its last move.
 *
 * Safety bubble ([geofence]): the planned path must fit in it (checked before the first step),
 * every climb target must stay under its ceiling, and the measured position (visual
 * odometry, fed by [onOdometry]) must stay inside: otherwise [MissionAbort.GEOFENCE]. Without
 * a trustworthy position, moves wait and then abort with [MissionAbort.NO_POSITIONING] (as
 * when the drone itself cannot position); hovers, climbs and rotations go on.
 */
class MissionRunner(
    val mission: Mission,
    private val config: MissionConfig = MissionConfig(),
    private val geofence: Geofence? = null,
) {

    /** A step as flown, with the index of the mission step it comes from. */
    private class Planned(val step: MissionStep, val source: Int)

    private val plan: List<Planned> = plan(mission, config.noseFirst)

    private var index = 0
    private var stepStart = -1L
    private var lastTick = -1L
    private var lastTelemetryAt = -1L
    private var lastFlight: FlightData? = null
    private var checkedPlan = false

    // Per-step state.
    private var targetHeight = 0f
    private var settledSince = -1L
    private var travelled = 0f
    private var brakingSince = -1L
    private var aborted: MissionAbort? = null

    /** Index of the mission step being flown (inserted rotations count for their move). */
    val stepIndex: Int get() = plan.getOrNull(index)?.source ?: mission.steps.size

    /** The drone is close to the edge of the safety bubble (warn the pilot). */
    var nearFence: Boolean = false
        private set

    /** Feeds a telemetry sample (call on each flight data packet). */
    fun onTelemetry(flight: FlightData, nowMs: Long) {
        lastFlight = flight
        lastTelemetryAt = nowMs
    }

    /** Feeds a visual-odometry sample (call on each new one); ignored without a geofence. */
    fun onOdometry(x: Float, y: Float, valid: Boolean, nowMs: Long) {
        geofence?.onOdometry(x, y, valid, nowMs)
    }

    fun tick(nowMs: Long): MissionTick {
        aborted?.let { return MissionTick.Aborted(it) }
        if (index >= plan.size) return MissionTick.Finished
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
        val step = plan[index].step
        if (geofence != null) {
            if (!checkedPlan) {
                checkedPlan = true
                geofence.start(nowMs)
                val c = geofence.config
                if (mission.plannedMaxDistance() > c.radiusMeters ||
                    flight.heightMeters + mission.plannedMaxClimb() > c.maxHeightMeters - CEILING_MARGIN
                ) {
                    return abort(MissionAbort.GEOFENCE)
                }
            }
            val fenceStatus = geofence.status(flight.heightMeters, nowMs)
            nearFence = fenceStatus == FenceStatus.NEAR
            when (fenceStatus) {
                FenceStatus.OUTSIDE, FenceStatus.TOO_HIGH -> return abort(MissionAbort.GEOFENCE)
                FenceStatus.NO_POSITION -> if (step is MissionStep.Move) return abort(MissionAbort.NO_POSITIONING)
                else -> Unit
            }
        }
        if (stepStart < 0) {
            startStep(step, flight, nowMs)
            if (geofence != null && step is MissionStep.Climb && step.meters > 0f &&
                targetHeight > geofence.config.maxHeightMeters - CEILING_MARGIN
            ) {
                return abort(MissionAbort.GEOFENCE)
            }
        }
        val stepTimeout = if (step is MissionStep.Move && config.maxHorizontalSpeed != null) {
            maxOf(config.stepTimeoutMs, moveTimeoutMs(step)) // slow moves may legitimately take longer
        } else {
            config.stepTimeoutMs
        }
        if (nowMs - stepStart > stepTimeout) return abort(MissionAbort.STEP_TIMEOUT)

        val sticks = when (step) {
            is MissionStep.Hover -> if (nowMs - stepStart >= (step.seconds * 1000).toLong()) null else StickInput.Neutral
            is MissionStep.Rotate -> {
                val duration = (abs(step.degrees) / config.yawDegreesPerSecond * 1000).toLong()
                if (nowMs - stepStart >= duration) null else StickInput(yaw = config.yawStick * sign(step.degrees))
            }
            is MissionStep.Climb -> climb(flight, nowMs)
            is MissionStep.Move -> {
                if (flight.positioningUnavailable) return abort(MissionAbort.NO_POSITIONING)
                if (geofence != null && !geofence.positionKnown && travelled == 0f) {
                    // No position yet: do not start a move that the bubble could not follow.
                    StickInput.Neutral
                } else {
                    move(step, flight, nowMs, dt)
                        ?: return if (stepTimedOut(step, nowMs)) abort(MissionAbort.STEP_TIMEOUT) else nextStep(nowMs)
                }
            }
        }
        if (sticks == null) return nextStep(nowMs)
        if (step is MissionStep.Move && stepTimedOut(step, nowMs)) return abort(MissionAbort.STEP_TIMEOUT)
        return MissionTick.Running(sticks, stepIndex)
    }

    private val expectedSpeed: Float
        get() = config.maxHorizontalSpeed?.let { minOf(config.expectedSpeed, it * 0.8f) } ?: config.expectedSpeed

    private fun moveTimeoutMs(step: MissionStep.Move): Long =
        (step.meters / expectedSpeed * 1000).toLong() * 3 + config.maxBrakeMs

    private fun stepTimedOut(step: MissionStep.Move, nowMs: Long): Boolean = nowMs - stepStart > moveTimeoutMs(step)

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
        return if (index >= plan.size) MissionTick.Finished else tick(nowMs)
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
        val s = moveStick(flight.horizontalSpeed)
        return when (step.direction) {
            MoveDirection.FORWARD -> StickInput(pitch = s)
            MoveDirection.BACK -> StickInput(pitch = -s)
            MoveDirection.RIGHT -> StickInput(roll = s)
            MoveDirection.LEFT -> StickInput(roll = -s)
        }
    }

    /**
     * Stick of a move. With a speed limit: the stick expected to give that speed, released
     * progressively while the measured speed exceeds it (fast mode left on, tailwind…).
     */
    private fun moveStick(speed: Float): Float {
        val limit = config.maxHorizontalSpeed ?: return config.moveStick
        // Never above the historical stick: braking distance and timeouts were tuned for it.
        val base = (limit * config.stickPerMps).coerceIn(MIN_MOVE_STICK, maxOf(MIN_MOVE_STICK, config.moveStick))
        val over = speed - limit
        if (over <= 0f) return base
        return base * (1f - over / config.speedMargin).coerceIn(0f, 1f)
    }

    private companion object {
        /** Below this, the Tello barely moves. */
        const val MIN_MOVE_STICK = 0.08f
        /** Margin under the ceiling for a climb target (overshoot of the height loop). */
        const val CEILING_MARGIN = 0.3f

        /** Turn of a nose-first plan, in (-180, 180] degrees. */
        fun normalize(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        fun plan(mission: Mission, noseFirst: Boolean): List<Planned> {
            if (!noseFirst) return mission.steps.mapIndexed { i, s -> Planned(s, i) }
            val out = mutableListOf<Planned>()
            // Angle of the real nose relative to the mission's nose (both turn with Rotate steps).
            var offset = 0f
            mission.steps.forEachIndexed { i, step ->
                if (step is MissionStep.Move) {
                    val turn = normalize(step.direction.angle - offset)
                    if (turn != 0f) out += Planned(MissionStep.Rotate(turn), i)
                    offset = normalize(offset + turn)
                    out += Planned(MissionStep.Move(MoveDirection.FORWARD, step.meters), i)
                } else {
                    out += Planned(step, i)
                }
            }
            val back = normalize(-offset)
            if (back != 0f) out += Planned(MissionStep.Rotate(back), mission.steps.lastIndex)
            return out
        }
    }
}
