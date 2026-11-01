package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.StickInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Nose-first moves, speed limit and safety bubble of the mission runner. */
class MissionSafetyTest {

    private fun flight(height: Float, speed: Float) = FlightData(
        heightMeters = height, northSpeed = speed, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 10f,
        batteryPercent = 80, flying = true, onGround = false, hovering = true,
        batteryLow = false, batteryCritical = false, flyMode = 6,
    )

    /** Drone model with a heading and a position (odometry in an arbitrary rotated frame). */
    private class Sim(val maxSpeedPerStick: Float = 1.7f) {
        var height = 1.0
        var heading = 0.0 // degrees clockwise
        var x = 0.0 // right of the initial nose
        var y = 0.0 // ahead of the initial nose
        var vx = 0.0
        var vy = 0.0
        val yaws = mutableListOf<Float>()
        var maxSpeed = 0.0
        val speed get() = hypot(vx, vy).toFloat()

        fun apply(s: StickInput, dtMs: Long) {
            val dt = dtMs / 1000.0
            heading += s.yaw / 0.5 * 70.0 * dt
            if (s.yaw != 0f) yaws += s.yaw
            val h = Math.toRadians(heading)
            // Body frame: pitch forward, roll right.
            val fwd = s.pitch * maxSpeedPerStick
            val right = s.roll * maxSpeedPerStick
            val tx = fwd * sin(h) + right * cos(h)
            val ty = fwd * cos(h) - right * sin(h)
            vx += (tx - vx) * 0.2
            vy += (ty - vy) * 0.2
            x += vx * dt
            y += vy * dt
            height += s.throttle * dt
            maxSpeed = maxOf(maxSpeed, hypot(vx, vy))
        }
    }

    private class Result(val tick: MissionTick, val sim: Sim, val ticks: List<MissionTick.Running>)

    private fun run(
        mission: Mission,
        config: MissionConfig = MissionConfig(),
        geofence: Geofence? = null,
        sim: Sim = Sim(),
        odometry: (Sim) -> Triple<Float, Float, Boolean>? = { Triple((it.x * 0.6 - it.y * 0.8).toFloat(), (it.x * 0.8 + it.y * 0.6).toFloat(), true) },
        maxMs: Long = 200_000,
    ): Result {
        val runner = MissionRunner(mission, config, geofence)
        val ticks = mutableListOf<MissionTick.Running>()
        var now = 0L
        while (now < maxMs) {
            runner.onTelemetry(flight(sim.height.toFloat(), sim.speed), now)
            if (now % 100 == 0L) odometry(sim)?.let { (x, y, v) -> runner.onOdometry(x, y, v, now) }
            val tick = runner.tick(now)
            if (tick !is MissionTick.Running) return Result(tick, sim, ticks)
            ticks += tick
            sim.apply(tick.sticks, 20)
            now += 20
        }
        error("mission did not end")
    }

    private val square = Mission("Carré", listOf(
        MissionStep.Move(MoveDirection.FORWARD, 2f), MissionStep.Move(MoveDirection.RIGHT, 2f),
        MissionStep.Move(MoveDirection.BACK, 2f), MissionStep.Move(MoveDirection.LEFT, 2f),
    ))

    @Test
    fun `nose first flies every move forward and ends on the original heading`() {
        val r = run(square, MissionConfig(noseFirst = true))
        assertEquals(MissionTick.Finished, r.tick)
        // Only pitch-forward moves: never sideways nor backwards.
        assertTrue(r.ticks.none { it.sticks.roll != 0f || it.sticks.pitch < 0f })
        // Turns: +90 three times (right, back, left) then +90 back to the start heading.
        assertEquals(0.0, ((r.sim.heading % 360) + 360) % 360, 6.0) // timed turns, 20 ms ticks
        // Same square as without the option: back near the start.
        assertTrue(hypot(r.sim.x, r.sim.y) < 0.8, "end ${r.sim.x}, ${r.sim.y}")
    }

    @Test
    fun `nose first turns by the shortest way and keeps step indexes`() {
        val m = Mission("L", listOf(MissionStep.Hover(0.5f), MissionStep.Move(MoveDirection.LEFT, 1f)))
        val r = run(m, MissionConfig(noseFirst = true))
        assertEquals(MissionTick.Finished, r.tick)
        assertTrue(r.sim.yaws.isNotEmpty())
        // Left: turn left first (negative yaw), back right at the end.
        assertTrue(r.sim.yaws.first() < 0f && r.sim.yaws.last() > 0f)
        assertEquals(setOf(0, 1), r.ticks.map { it.stepIndex }.toSet())
        assertTrue(r.sim.x < -0.6, "moved left: ${r.sim.x}")
    }

    @Test
    fun `without nose first the sticks are the historical ones`() {
        val r = run(square)
        assertEquals(MissionTick.Finished, r.tick)
        assertTrue(r.sim.yaws.isEmpty())
        assertTrue(r.ticks.any { it.sticks.roll != 0f })
        assertTrue(r.ticks.all { maxOf(abs(it.sticks.pitch), abs(it.sticks.roll)) in listOf(0f, 0.35f) })
    }

    @Test
    fun `nose first keeps the mission's own rotations`() {
        val m = Mission("R", listOf(MissionStep.Rotate(90f), MissionStep.Move(MoveDirection.FORWARD, 1f)))
        val r = run(m, MissionConfig(noseFirst = true))
        assertEquals(MissionTick.Finished, r.tick)
        assertEquals(90.0, r.sim.heading, 3.0)
        assertTrue(r.sim.x > 0.6) // went right, as without the option
    }

    @Test
    fun `speed limit keeps the drone slow, even with a fast drone`() {
        val fast = Sim(maxSpeedPerStick = 4f) // fast mode left on
        val r = run(Mission("F", listOf(MissionStep.Move(MoveDirection.FORWARD, 5f))), MissionConfig(maxHorizontalSpeed = 0.5f), sim = fast)
        assertEquals(MissionTick.Finished, r.tick)
        assertTrue(fast.maxSpeed < 0.5 + 0.3, "max ${fast.maxSpeed}")
        assertTrue(fast.y > 4.0)
    }

    @Test
    fun `slow limit does not time out on the longest move`() {
        val r = run(Mission("F", listOf(MissionStep.Move(MoveDirection.FORWARD, 10f))), MissionConfig(maxHorizontalSpeed = 0.2f))
        assertEquals(MissionTick.Finished, r.tick)
    }

    @Test
    fun `a planned path larger than the bubble is refused before moving`() {
        val m = Mission("Loin", listOf(MissionStep.Move(MoveDirection.FORWARD, 6f)))
        val r = run(m, geofence = Geofence(GeofenceConfig(radiusMeters = 5f)))
        assertEquals(MissionTick.Aborted(MissionAbort.GEOFENCE), r.tick)
        assertTrue(r.ticks.isEmpty())
    }

    @Test
    fun `presets fit in the default bubble`() {
        Mission.Presets.forEach { assertTrue(it.plannedMaxDistance() < PilotSettings.GEOFENCE_RADIUS_DEFAULT, it.name) }
        assertEquals(3f, Mission.Presets[1].plannedMaxDistance(), 1e-4f) // round trip: never 6 m
    }

    @Test
    fun `drifting out of the bubble aborts`() {
        // Odometry scale twice the truth: the bubble is reached halfway through the planned path.
        val m = Mission("F", listOf(MissionStep.Move(MoveDirection.FORWARD, 4f)))
        val r = run(m, geofence = Geofence(GeofenceConfig(radiusMeters = 5f)), odometry = { Triple(0f, (it.y * 2).toFloat(), true) })
        assertEquals(MissionTick.Aborted(MissionAbort.GEOFENCE), r.tick)
        assertTrue(r.sim.y in 2.0..3.0, "stopped at ${r.sim.y}")
    }

    @Test
    fun `climbing above the ceiling aborts before climbing`() {
        val m = Mission("Haut", listOf(MissionStep.Climb(3f)))
        val r = run(m, geofence = Geofence(GeofenceConfig(maxHeightMeters = 3f)))
        assertEquals(MissionTick.Aborted(MissionAbort.GEOFENCE), r.tick)
        assertEquals(1.0, r.sim.height, 1e-6)
    }

    @Test
    fun `a descent cut at the minimum height then a climb is caught at the climb`() {
        val m = Mission("Yo-yo", listOf(MissionStep.Climb(-2f), MissionStep.Climb(2.5f)))
        val r = run(m, geofence = Geofence(GeofenceConfig(maxHeightMeters = 2.5f)))
        assertEquals(MissionTick.Aborted(MissionAbort.GEOFENCE), r.tick)
        assertTrue(r.sim.height < 1.0)
    }

    @Test
    fun `no odometry, moves never start and abort as no positioning`() {
        val m = Mission("F", listOf(MissionStep.Hover(1f), MissionStep.Move(MoveDirection.FORWARD, 2f)))
        val r = run(m, geofence = Geofence(GeofenceConfig()), odometry = { null })
        assertEquals(MissionTick.Aborted(MissionAbort.NO_POSITIONING), r.tick)
        assertTrue(r.ticks.all { it.sticks == StickInput.Neutral })
    }

    @Test
    fun `no odometry does not stop hovers, climbs and rotations`() {
        val m = Mission("Sur place", listOf(MissionStep.Hover(3f), MissionStep.Climb(1f), MissionStep.Rotate(90f)))
        val r = run(m, geofence = Geofence(GeofenceConfig()), odometry = { null })
        assertEquals(MissionTick.Finished, r.tick)
    }

    @Test
    fun `invalid odometry counts as none`() {
        val m = Mission("F", listOf(MissionStep.Move(MoveDirection.FORWARD, 2f)))
        val r = run(m, geofence = Geofence(GeofenceConfig()), odometry = { Triple(it.x.toFloat(), it.y.toFloat(), false) })
        assertEquals(MissionTick.Aborted(MissionAbort.NO_POSITIONING), r.tick)
    }

    @Test
    fun `near the edge is reported`() {
        val runner = MissionRunner(Mission("H", listOf(MissionStep.Hover(5f))), geofence = Geofence(GeofenceConfig(radiusMeters = 5f)))
        runner.onTelemetry(flight(1f, 0f), 0)
        runner.onOdometry(0f, 0f, true, 0)
        runner.tick(0)
        assertFalse(runner.nearFence)
        runner.onTelemetry(flight(1f, 0f), 1_000)
        runner.onOdometry(4.2f, 0f, true, 1_000)
        assertInstanceOf(MissionTick.Running::class.java, runner.tick(1_000))
        assertTrue(runner.nearFence)
    }

    @Test
    fun `settings build the runner and bubble configuration`() {
        val on = PilotSettings()
        assertEquals(MissionConfig(noseFirst = true, maxHorizontalSpeed = 0.5f), on.missionConfig())
        assertEquals(GeofenceConfig(5f, 10f), on.geofenceConfig(10))
        val off = PilotSettings(missionNoseFirst = false, missionSpeedLimit = false, geofence = false)
        assertEquals(MissionConfig(), off.missionConfig())
        assertEquals(null, off.geofenceConfig(10))
        assertEquals(0.6f, PilotSettings(missionMaxSpeed = 9f).missionConfig().maxHorizontalSpeed)
    }
}
