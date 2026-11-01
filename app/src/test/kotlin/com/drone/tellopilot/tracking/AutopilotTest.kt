package com.drone.tellopilot.tracking

import com.drone.tello.control.LinkState
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionAbort
import com.drone.tello.control.MissionStep
import com.drone.tello.control.MoveDirection
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TelloController
import com.drone.tello.control.TrackingMode
import com.drone.tello.control.TrackingTarget
import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.data.AppSettings
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.SettingsRepository
import com.drone.tellopilot.testutil.FakeDrone
import com.drone.tellopilot.testutil.flightData
import com.drone.tellopilot.testutil.odometry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic flights: start, natural ends, aborts, explicit stops and the stick ownership token. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutopilotTest {

    private class Rig(val scope: TestScope, pilot: PilotSettings = PilotSettings()) {
        val drone = FakeDrone()
        val controller = MutableStateFlow<TelloController?>(drone.controller)
        val settings = MutableStateFlow(AppSettings(pilot = pilot))
        private val session: DroneSession = mockk(relaxed = true) { every { controller } returns this@Rig.controller }
        private val repo: SettingsRepository = mockk { every { settings } returns this@Rig.settings }
        val autopilot = Autopilot(session, repo, StandardTestDispatcher(scope.testScheduler)) { scope.testScheduler.currentTime }
        val ends = mutableListOf<AutopilotEnd>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { autopilot.ends.toList(ends) }
            scope.runCurrent()
        }

        /** Flying at 1 m, 10 Hz flight data (the height follows the throttle). */
        fun flying() {
            drone.simulate(scope)
            scope.advanceTimeBy(150)
        }
    }

    private fun test(pilot: PilotSettings = PilotSettings(), block: suspend Rig.() -> Unit) = runTest {
        val rig = Rig(this, pilot)
        try {
            rig.block()
        } finally {
            rig.autopilot.stop()
        }
    }

    private val hover = Mission("Hover", listOf(MissionStep.Hover(30f)))

    @Test
    fun `a mission runs step by step, then hovers and reports its end`() = test {
        flying()
        autopilot.startMission(Mission("Climb + hover", listOf(MissionStep.Climb(0.5f), MissionStep.Hover(5f))))
        assertTrue(autopilot.active) // at once: the sticks of the pilot are checked against it
        scope.advanceTimeBy(100)
        assertEquals(AutopilotState.RunningMission("Climb + hover", 1, 2), autopilot.state.value)
        assertTrue(drone.lastSticks!!.throttle > 0f)
        scope.advanceTimeBy(4_000)
        assertEquals(AutopilotState.RunningMission("Climb + hover", 2, 2), autopilot.state.value)
        scope.advanceTimeBy(4_000)
        assertFalse(autopilot.active)
        assertEquals(AutopilotState.Idle, autopilot.state.value)
        assertEquals(listOf<AutopilotEnd>(AutopilotEnd.MissionDone), ends)
        assertEquals(StickInput.Neutral, drone.lastSticks)
    }

    @Test
    fun `stop - neutral sticks at once, Idle, the end reported, and never an automatic stick again`() = test {
        flying()
        autopilot.startMission(Mission("Climb", listOf(MissionStep.Climb(3f))))
        scope.advanceTimeBy(300)
        assertTrue(drone.lastSticks!!.throttle > 0f)
        autopilot.stop(AutopilotEnd.PilotTookOver)
        assertFalse(autopilot.active)
        assertEquals(StickInput.Neutral, drone.lastSticks)
        val written = drone.sticks.size
        scope.advanceTimeBy(1_000)
        assertEquals(written, drone.sticks.size)
        assertEquals(AutopilotState.Idle, autopilot.state.value)
        assertEquals(listOf<AutopilotEnd>(AutopilotEnd.PilotTookOver), ends)
    }

    @Test
    fun `stop without an automatic flight does nothing`() = test {
        autopilot.stop()
        scope.runCurrent()
        assertTrue(drone.sticks.isEmpty())
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `a new flight replaces the running one, which ends as stopped`() = test {
        flying()
        autopilot.startMission(hover)
        scope.advanceTimeBy(100)
        autopilot.startMission(Mission("Second", listOf(MissionStep.Hover(30f))))
        scope.advanceTimeBy(100)
        assertEquals(listOf<AutopilotEnd>(AutopilotEnd.Stopped), ends)
        assertEquals("Second", (autopilot.state.value as AutopilotState.RunningMission).name)
    }

    private fun Rig.abortReason(): MissionAbort {
        scope.advanceTimeBy(3_000)
        assertFalse(autopilot.active)
        assertEquals(AutopilotState.Idle, autopilot.state.value)
        assertEquals(StickInput.Neutral, drone.lastSticks)
        assertFalse(autopilot.nearFence.value)
        return (ends.single() as AutopilotEnd.MissionAborted).reason
    }

    @Test
    fun `link lost - aborts`() = test {
        flying()
        autopilot.startMission(hover)
        scope.advanceTimeBy(500)
        drone.linkFlow.value = LinkState.RECONNECTING
        assertEquals(MissionAbort.LINK_LOST, abortReason())
    }

    @Test
    fun `controller gone - aborts as a lost link`() = test {
        flying()
        autopilot.startMission(hover)
        scope.advanceTimeBy(500)
        controller.value = null
        assertEquals(MissionAbort.LINK_LOST, abortReason())
    }

    @Test
    fun `frozen telemetry (no new flight data for 700 ms) - aborts as a lost link`() = test {
        flying()
        autopilot.startMission(hover)
        scope.advanceTimeBy(500)
        drone.running = false // the link stays CONNECTED but nothing new arrives
        scope.advanceTimeBy(600)
        assertTrue(autopilot.active)
        assertEquals(MissionAbort.LINK_LOST, abortReason())
    }

    @Test
    fun `not flying - aborts`() = test {
        drone.flight(flightData(flying = false))
        autopilot.startMission(hover)
        assertEquals(MissionAbort.NOT_FLYING, abortReason())
    }

    @Test
    fun `critical battery - aborts`() = test {
        flying()
        autopilot.startMission(hover)
        scope.advanceTimeBy(500)
        drone.battery = { it.copy(batteryCritical = true) }
        assertEquals(MissionAbort.LOW_BATTERY, abortReason())
    }

    @Test
    fun `a mission planned beyond the safety bubble - aborts`() = test(PilotSettings(geofenceRadius = 3f)) {
        flying()
        autopilot.startMission(Mission("Far", listOf(MissionStep.Move(MoveDirection.FORWARD, 4f))))
        assertEquals(MissionAbort.GEOFENCE, abortReason())
    }

    @Test
    fun `leaving the safety bubble in flight - aborts, near the edge is reported first`() = test(PilotSettings(geofenceRadius = 3f)) {
        flying()
        autopilot.startMission(hover)
        // Drifting away at 1 m/s (wind): near the edge, then out.
        var near = false
        for (i in 0..50) {
            drone.telemetry { it.copy(odometry = odometry(i * 0.1f, 0f)) }
            scope.advanceTimeBy(100)
            near = near || autopilot.nearFence.value
            if (!autopilot.active) break
        }
        assertTrue(near)
        assertEquals(MissionAbort.GEOFENCE, abortReason())
    }

    @Test
    fun `a move without positioning - aborts`() = test(PilotSettings(missionNoseFirst = false)) {
        flying()
        autopilot.startMission(Mission("Move", listOf(MissionStep.Move(MoveDirection.LEFT, 1f))))
        scope.advanceTimeBy(500)
        assertTrue(autopilot.active) // hovers while waiting for a position
        assertEquals(StickInput.Neutral, drone.lastSticks)
        assertEquals(MissionAbort.NO_POSITIONING, abortReason())
    }

    @Test
    fun `an unexpected error ends the flight as failed, sticks neutral`() = test {
        flying()
        every { drone.controller.flightSamples } throws IllegalStateException("boom")
        autopilot.startMission(hover)
        scope.advanceTimeBy(100)
        assertFalse(autopilot.active)
        assertEquals(listOf<AutopilotEnd>(AutopilotEnd.Failed), ends)
        assertEquals(StickInput.Neutral, drone.lastSticks)
        assertEquals(AutopilotState.Idle, autopilot.state.value)
    }

    @Test
    fun `tracking that cannot start its detector ends as failed`() = test {
        flying()
        autopilot.startTracking(TrackingMode.FOLLOW, TrackingTarget.PERSON, orbitDirection = 1)
        assertTrue(autopilot.active)
        scope.advanceTimeBy(100)
        assertFalse(autopilot.active)
        assertEquals(listOf<AutopilotEnd>(AutopilotEnd.Failed), ends)
        assertEquals(AutopilotState.Idle, autopilot.state.value)
    }
}
