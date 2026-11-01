package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.StickInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissionTest {

    private fun flight(
        height: Float = 1f,
        speed: Float = 0f,
        flying: Boolean = true,
        critical: Boolean = false,
        flyMode: Int = 6,
    ) = FlightData(
        heightMeters = height, northSpeed = speed, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 10f,
        batteryPercent = 80, flying = flying, onGround = !flying, hovering = true,
        batteryLow = false, batteryCritical = critical, flyMode = flyMode,
    )

    /** Crude drone model: speed follows the sticks, height integrates the throttle. */
    private class SimDrone {
        var height = 1f
        var speed = 0f
        fun apply(s: StickInput, dtMs: Long) {
            val target = maxOf(kotlin.math.abs(s.pitch), kotlin.math.abs(s.roll)) * 1.5f
            speed += (target - speed) * 0.2f
            height += s.throttle * 1.0f * dtMs / 1000f
        }
    }

    private fun run(mission: Mission, maxMs: Long = 120_000): Pair<MissionTick, SimDrone> {
        val runner = MissionRunner(mission)
        val sim = SimDrone()
        var now = 0L
        while (now < maxMs) {
            runner.onTelemetry(flight(height = sim.height, speed = sim.speed), now)
            val tick = runner.tick(now)
            if (tick !is MissionTick.Running) return tick to sim
            sim.apply(tick.sticks, 20)
            now += 20
        }
        error("mission did not end")
    }

    @Test
    fun `steps encode and decode`() {
        val m = Mission("Test", listOf(
            MissionStep.Climb(1.5f), MissionStep.Move(MoveDirection.LEFT, 2f),
            MissionStep.Rotate(-90f), MissionStep.Hover(3f),
        ))
        assertEquals(m, Mission.decode(m.encode()))
        Mission.Presets.forEach { assertEquals(it, Mission.decode(it.encode())) }
    }

    @Test
    fun `invalid or out of range steps are rejected`() {
        assertNull(Mission.decode("x|C99"))
        assertNull(Mission.decode("x|Z1"))
        assertNull(Mission.decode("|C1"))
        assertNull(Mission.decode("x|"))
    }

    @Test
    fun `climb reaches the target height then finishes`() {
        val (end, sim) = run(Mission("c", listOf(MissionStep.Climb(1f))))
        assertEquals(MissionTick.Finished, end)
        assertEquals(2f, sim.height, 0.2f)
    }

    @Test
    fun `descent never goes under the minimum height`() {
        val (end, sim) = run(Mission("d", listOf(MissionStep.Climb(-5f))))
        assertEquals(MissionTick.Finished, end)
        assertTrue(sim.height >= MissionConfig().minHeight - 0.2f)
    }

    @Test
    fun `move travels about the distance and ends stopped`() {
        val (end, sim) = run(Mission("m", listOf(MissionStep.Move(MoveDirection.FORWARD, 2f))))
        assertEquals(MissionTick.Finished, end)
        assertTrue(sim.speed <= MissionConfig().stoppedSpeed + 0.01f)
    }

    @Test
    fun `move without positioning aborts`() {
        val runner = MissionRunner(Mission("m", listOf(MissionStep.Move(MoveDirection.FORWARD, 2f))))
        runner.onTelemetry(flight(flyMode = FlightData.FLY_MODE_NO_POSITIONING), 0)
        assertEquals(MissionTick.Aborted(MissionAbort.NO_POSITIONING), runner.tick(0))
    }

    @Test
    fun `stale telemetry aborts and stays aborted`() {
        val runner = MissionRunner(Mission("h", listOf(MissionStep.Hover(5f))))
        runner.onTelemetry(flight(), 0)
        assertInstanceOf(MissionTick.Running::class.java, runner.tick(100))
        assertEquals(MissionTick.Aborted(MissionAbort.LINK_LOST), runner.tick(2_000))
        runner.onTelemetry(flight(), 2_100)
        assertEquals(MissionTick.Aborted(MissionAbort.LINK_LOST), runner.tick(2_100))
    }

    @Test
    fun `landed or critical battery aborts`() {
        val a = MissionRunner(Mission("h", listOf(MissionStep.Hover(5f))))
        a.onTelemetry(flight(flying = false), 0)
        assertEquals(MissionTick.Aborted(MissionAbort.NOT_FLYING), a.tick(0))
        val b = MissionRunner(Mission("h", listOf(MissionStep.Hover(5f))))
        b.onTelemetry(flight(critical = true), 0)
        assertEquals(MissionTick.Aborted(MissionAbort.LOW_BATTERY), b.tick(0))
    }

    @Test
    fun `rotation is timed and signed`() {
        val runner = MissionRunner(Mission("r", listOf(MissionStep.Rotate(-70f))))
        runner.onTelemetry(flight(), 0)
        val first = runner.tick(0) as MissionTick.Running
        assertTrue(first.sticks.yaw < 0)
        runner.onTelemetry(flight(), 1_100)
        assertEquals(MissionTick.Finished, runner.tick(1_100))
    }
}

class TargetTrackerTest {

    @Test
    fun `centred target at reference size gives neutral sticks`() {
        val t = TargetTracker(TrackingMode.FOLLOW)
        t.observe(TargetObservation(0.5f, 0.45f, 0.4f), 0)
        val tick = t.tick(10) as TrackerTick.Tracking
        assertEquals(StickInput.Neutral, tick.sticks)
    }

    @Test
    fun `target on the right turns right, far target moves forward`() {
        val t = TargetTracker(TrackingMode.FOLLOW, TrackerConfig(smoothing = 0f))
        t.observe(TargetObservation(0.5f, 0.45f, 0.4f), 0)
        t.observe(TargetObservation(0.8f, 0.45f, 0.2f), 50)
        val s = (t.tick(60) as TrackerTick.Tracking).sticks
        assertTrue(s.yaw > 0)
        assertTrue(s.pitch > 0)
        assertEquals(0f, s.roll)
    }

    @Test
    fun `spotlight never translates, orbit always slides sideways`() {
        val spot = TargetTracker(TrackingMode.SPOTLIGHT, TrackerConfig(smoothing = 0f))
        spot.observe(TargetObservation(0.5f, 0.45f, 0.4f), 0)
        spot.observe(TargetObservation(0.5f, 0.45f, 0.1f), 10)
        assertEquals(0f, (spot.tick(20) as TrackerTick.Tracking).sticks.pitch)

        val orbit = TargetTracker(TrackingMode.ORBIT, orbitDirection = -1)
        orbit.observe(TargetObservation(0.5f, 0.45f, 0.4f), 0)
        assertTrue((orbit.tick(10) as TrackerTick.Tracking).sticks.roll < 0)
    }

    @Test
    fun `sticks stay bounded`() {
        val config = TrackerConfig(smoothing = 0f)
        val t = TargetTracker(TrackingMode.FOLLOW, config)
        t.observe(TargetObservation(0.5f, 0.5f, 0.9f), 0)
        t.observe(TargetObservation(1f, 0f, 0.01f), 10)
        val s = (t.tick(20) as TrackerTick.Tracking).sticks
        assertTrue(kotlin.math.abs(s.yaw) <= config.maxYaw)
        assertTrue(kotlin.math.abs(s.throttle) <= config.maxThrottle)
        assertTrue(kotlin.math.abs(s.pitch) <= config.maxPitch)
    }

    @Test
    fun `target hidden hovers then is lost`() {
        val t = TargetTracker(TrackingMode.FOLLOW)
        t.observe(TargetObservation(0.9f, 0.45f, 0.4f), 0)
        assertEquals(StickInput.Neutral, (t.tick(500) as TrackerTick.Tracking).sticks)
        assertEquals(TrackerTick.Lost, t.tick(4_100))
    }

    @Test
    fun `never seen target is lost after the timeout`() {
        val t = TargetTracker(TrackingMode.FOLLOW)
        assertEquals(StickInput.Neutral, (t.tick(0) as TrackerTick.Tracking).sticks)
        assertEquals(TrackerTick.Lost, t.tick(4_000))
    }
}
