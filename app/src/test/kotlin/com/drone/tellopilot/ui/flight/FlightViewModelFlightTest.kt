package com.drone.tellopilot.ui.flight

import com.drone.tello.control.LinkState
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionAbort
import com.drone.tello.control.MissionStep
import com.drone.tello.control.MoveDirection
import com.drone.tello.control.PadAction
import com.drone.tello.control.PilotSettings
import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.testutil.flightData
import com.drone.tellopilot.testutil.imu
import com.drone.tellopilot.testutil.odometry
import com.drone.tellopilot.tracking.AutopilotEnd
import com.drone.tellopilot.tracking.AutopilotState
import com.drone.tellopilot.video.RecordingState
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flight sequences through the view model: takeoff height, safety checks (shock, bubble),
 * landing priority, battery, flight track, automatic recording and user messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightViewModelFlightTest {

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun test(
        pilot: PilotSettings = FlightViewModelRig.linearPilot(),
        block: suspend FlightViewModelRig.() -> Unit,
    ) = runTest(main) { withRig(pilot, block) }

    private val height2m = FlightViewModelRig.linearPilot { copy(takeoffHeight = 2f) }

    private fun FlightViewModelRig.onGround() {
        drone.flight(flightData(flying = false))
        scope.runCurrent()
    }

    /** Takeoff command, then the drone reports flying at 1 m (10 Hz) [afterMs] later. */
    private fun FlightViewModelRig.takeOff(afterMs: Long = 1_000, throwTakeoff: Boolean = false) {
        onGround()
        vm.onAction(if (throwTakeoff) FlightAction.ThrowTakeoff else FlightAction.Takeoff)
        scope.advanceTimeBy(afterMs)
        drone.simulate(scope)
        scope.advanceTimeBy(150)
    }

    // ------------------------------------------------------------ takeoff height

    @Test
    fun `takeoff height - the app takeoff arms a climb to the chosen height, run after 2 s`() = test(height2m) {
        takeOff()
        coVerify { drone.controller.takeoff() }
        verify { haptics.confirm() } // the takeoff command left
        // Settling: nothing yet.
        scope.advanceTimeBy(1_500)
        assertFalse(autopilot.active)
        assertTrue(drone.sticks.isEmpty())
        // After 2 s: climb with the mission runner.
        scope.advanceTimeBy(600)
        assertTrue(autopilot.active)
        val running = ui.autopilot as AutopilotState.RunningMission
        assertEquals("Hauteur de décollage", running.name)
        assertTrue(drone.lastSticks!!.throttle > 0f)
        // The drone climbs, the mission ends at about 2 m, silently (a light buzz only).
        scope.advanceTimeBy(8_000)
        assertFalse(autopilot.active)
        val height = drone.telemetryFlow.value.flight!!.heightMeters
        assertEquals(2f, height, 0.2f)
        assertTrue(messages.none { it is UserMessage.AutopilotEnded })
        verify(atLeast = 2) { haptics.confirm() }
    }

    @Test
    fun `takeoff height - a stick moved during the wait cancels the climb`() = test(height2m) {
        takeOff()
        vm.onRightStick(0.1f, 0f)
        vm.onRightStick(0f, 0f)
        scope.advanceTimeBy(3_000)
        assertFalse(autopilot.active)
        assertEquals(AutopilotState.Idle, ui.autopilot)
    }

    @Test
    fun `takeoff height - landing during the wait cancels the climb`() = test(height2m) {
        takeOff()
        vm.onAction(FlightAction.Land)
        scope.advanceTimeBy(3_000)
        coVerify { drone.controller.land() }
        assertFalse(autopilot.active)
    }

    @Test
    fun `takeoff height - the pilot takes over the climb with the sticks`() = test(height2m) {
        takeOff()
        scope.advanceTimeBy(2_100)
        assertTrue(autopilot.active)
        vm.onRightStick(0f, 0.5f)
        assertFalse(autopilot.active)
        scope.runCurrent()
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.PilotTookOver), messages.last())
    }

    @Test
    fun `takeoff height - nothing without the app own takeoff, after a throw, or when off`() {
        // App restarted in flight: a flying edge with no takeoff command.
        test(height2m) {
            drone.simulate(scope)
            scope.advanceTimeBy(3_000)
            assertFalse(autopilot.active)
        }
        // Throw takeoff: the height comes from the hand.
        test(height2m) {
            takeOff(throwTakeoff = true)
            scope.advanceTimeBy(3_000)
            assertFalse(autopilot.active)
        }
        // Flying edge too long after the command.
        test(height2m) {
            takeOff(afterMs = 11_000)
            scope.advanceTimeBy(3_000)
            assertFalse(autopilot.active)
        }
        // Setting off.
        test {
            takeOff()
            scope.advanceTimeBy(3_000)
            assertFalse(autopilot.active)
        }
    }

    @Test
    fun `takeoff height - not with a low battery or a lost link at the end of the wait`() {
        test(height2m) {
            drone.battery = { it.copy(batteryLow = true) }
            takeOff()
            scope.advanceTimeBy(3_000)
            assertFalse(autopilot.active)
        }
        test(height2m) {
            takeOff()
            drone.linkFlow.value = LinkState.RECONNECTING
            scope.advanceTimeBy(3_000)
            assertFalse(autopilot.active)
        }
    }

    @Test
    fun `takeoff height - a landing reported by the drone cancels the pending climb`() = test(height2m) {
        takeOff()
        drone.running = false
        scope.advanceTimeBy(150)
        drone.flight(flightData(flying = false))
        scope.advanceTimeBy(3_000)
        assertFalse(autopilot.active)
    }

    // ------------------------------------------------------------- safety checks

    /** Flying for a while (past the takeoff quiet time of the shock detector). */
    private fun FlightViewModelRig.cruising() {
        drone.simulate(scope)
        scope.advanceTimeBy(5_000)
    }

    /** Two tumbling IMU samples 100 ms apart: a shock. */
    private fun FlightViewModelRig.tumble() {
        drone.telemetry { it.copy(imu = imu(rollDeg = 80f)) }
        scope.advanceTimeBy(100)
        drone.telemetry { it.copy(imu = imu(rollDeg = 85f)) }
        scope.runCurrent()
    }

    @Test
    fun `a shock during a mission stops the autopilot and shows an alert for 5 s`() = test {
        cruising()
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        tumble()
        assertFalse(autopilot.active)
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.MissionAborted(MissionAbort.IMPACT)), messages.last())
        assertEquals(StickInput.Neutral, drone.lastSticks)
        assertTrue(Warning.IMPACT in ui.warnings)
        verify { haptics.critical() }
        scope.advanceTimeBy(5_100)
        assertFalse(Warning.IMPACT in ui.warnings)
    }

    @Test
    fun `a shock in manual flight only alerts, the sticks stay with the pilot`() = test {
        cruising()
        vm.onRightStick(0f, 0.4f)
        tumble()
        assertTrue(Warning.IMPACT in ui.warnings)
        assertTrue(messages.none { it is UserMessage.AutopilotEnded })
        vm.onRightStick(0f, 0.5f)
        assertEquals(StickInput(pitch = 0.5f), drone.lastSticks)
    }

    @Test
    fun `no shock alert when the detection is off or right after a flip`() {
        test(FlightViewModelRig.linearPilot { copy(impactDetection = false) }) {
            cruising()
            tumble()
            assertFalse(Warning.IMPACT in ui.warnings)
        }
        test {
            cruising()
            vm.onAction(FlightAction.Flip(com.drone.tello.protocol.FlipDirection.FORWARD))
            scope.runCurrent() // the flip left (accepted by the controller)
            tumble()
            assertFalse(Warning.IMPACT in ui.warnings)
        }
    }

    @Test
    fun `the safety bubble aborts a mission that would leave it`() = test(FlightViewModelRig.linearPilot { copy(geofenceRadius = 2f) }) {
        cruising()
        vm.onAction(FlightAction.StartMission(Mission("Loin", listOf(MissionStep.Move(MoveDirection.FORWARD, 5f)))))
        scope.advanceTimeBy(100)
        assertFalse(autopilot.active)
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.MissionAborted(MissionAbort.GEOFENCE)), messages.last())
        verify { haptics.warning() }
    }

    @Test
    fun `manual flight beyond the bubble - an alert only, the sticks still fly`() = test(
        FlightViewModelRig.linearPilot { copy(geofenceManualAlert = true, geofenceRadius = 2f) },
    ) {
        cruising()
        // Centre of the bubble, then 1 m/s outward.
        for (i in 0..40) {
            drone.telemetry { it.copy(odometry = odometry(x = i * 0.1f, y = 0f)) }
            scope.advanceTimeBy(100)
        }
        assertTrue(Warning.GEOFENCE_OUT in ui.warnings)
        vm.onRightStick(0f, 0.8f)
        assertEquals(StickInput(pitch = 0.8f), drone.lastSticks)
        // Back inside: the alert goes away.
        drone.telemetry { it.copy(odometry = odometry(x = 3.5f, y = 0f)) }
        scope.advanceTimeBy(100)
        for (i in 0..30) {
            drone.telemetry { it.copy(odometry = odometry(x = 3.5f - i * 0.1f, y = 0f)) }
            scope.advanceTimeBy(100)
        }
        assertFalse(Warning.GEOFENCE_OUT in ui.warnings)
    }

    // ----------------------------------------------------------------- landing

    @Test
    fun `gamepad combo - land in flight, take off only from a stable ground state`() = test {
        // Unknown state (no flight data yet): land.
        vm.onPadAction(PadAction.TakeoffOrLand)
        scope.runCurrent()
        coVerify(exactly = 1) { drone.controller.land() }
        coVerify(exactly = 0) { drone.controller.takeoff() }
        // On the ground, motors off: takeoff, but not within 2 s of the previous combo.
        drone.landing = false
        onGround()
        vm.onPadAction(PadAction.TakeoffOrLand)
        scope.runCurrent()
        coVerify(exactly = 0) { drone.controller.takeoff() }
        scope.advanceTimeBy(2_000)
        vm.onPadAction(PadAction.TakeoffOrLand)
        scope.runCurrent()
        coVerify(exactly = 1) { drone.controller.takeoff() }
        // Flying: land.
        drone.flight(flightData(flying = true))
        scope.advanceTimeBy(2_000)
        vm.onPadAction(PadAction.TakeoffOrLand)
        scope.runCurrent()
        coVerify(exactly = 2) { drone.controller.land() }
    }

    @Test
    fun `gamepad combo - a landing in progress, motors on or a recent flight all mean land`() = test {
        drone.flight(flightData(flying = false, motorsOn = true))
        scope.runCurrent()
        vm.onPadAction(PadAction.TakeoffOrLand)
        scope.runCurrent()
        coVerify(exactly = 1) { drone.controller.land() }
        // Flying reported, then the link lost the data: the last known state says flying.
        drone.landing = false
        drone.flight(flightData(flying = true))
        scope.runCurrent()
        controller.value = null
        drone.telemetryFlow.value = com.drone.tello.control.Telemetry()
        controller.value = drone.controller
        scope.advanceTimeBy(2_000)
        vm.onPadAction(PadAction.TakeoffOrLand)
        scope.runCurrent()
        coVerify(exactly = 2) { drone.controller.land() }
        coVerify(exactly = 0) { drone.controller.takeoff() }
    }

    @Test
    fun `landing ends an automatic flight before the land command`() = test {
        cruising()
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        vm.onAction(FlightAction.Land)
        assertFalse(autopilot.active)
        scope.runCurrent()
        coVerify { drone.controller.land() }
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.Stopped), messages.last())
        // Stopped by the pilot: no warning vibration for it.
        verify(exactly = 0) { haptics.warning() }
    }

    @Test
    fun `the flying state survives a link loss - Land stays offered`() = test {
        cruising()
        assertTrue(ui.flying)
        drone.running = false
        drone.linkFlow.value = LinkState.RECONNECTING
        scope.advanceTimeBy(200)
        assertTrue(ui.flying)
        // Link lost in flight: critical vibration.
        verify { haptics.critical() }
    }

    // ----------------------------------------------------------------- battery

    @Test
    fun `low battery - alert and one vibration, critical battery - critical vibration`() = test {
        drone.flight(flightData(flying = true, low = true))
        scope.runCurrent()
        assertTrue(Warning.BATTERY_LOW in ui.warnings)
        verify(exactly = 1) { haptics.warning() }
        // The same alert again: no new vibration.
        drone.flight(flightData(flying = true, low = true, battery = 19))
        scope.runCurrent()
        verify(exactly = 1) { haptics.warning() }
        drone.flight(flightData(flying = true, low = true, critical = true, battery = 5))
        scope.runCurrent()
        assertTrue(Warning.BATTERY_CRITICAL in ui.warnings)
        assertFalse(Warning.BATTERY_LOW in ui.warnings)
        verify(exactly = 1) { haptics.critical() }
    }

    @Test
    fun `critical battery aborts a mission`() = test {
        cruising()
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        drone.battery = { it.copy(batteryLow = true, batteryCritical = true) }
        scope.advanceTimeBy(300)
        assertFalse(autopilot.active)
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.MissionAborted(MissionAbort.LOW_BATTERY)), messages.last())
        assertEquals(StickInput.Neutral, drone.lastSticks)
    }

    // ------------------------------------------------------------------- track

    @Test
    fun `the track keeps a point every 10 cm, restarts at takeoff, ignores invalid positions`() = test {
        drone.flight(flightData(flying = true))
        for (x in listOf(0f, 0.05f, 0.1f, 0.15f, 0.25f)) {
            drone.telemetry { it.copy(odometry = odometry(x, 0f)) }
            scope.runCurrent()
        }
        assertEquals(listOf(0f, 0.1f, 0.25f), ui.track.map { it.x })
        drone.telemetry { it.copy(odometry = odometry(5f, 0f, valid = false)) }
        drone.telemetry { it.copy(odometry = odometry(Float.NaN, 0f)) }
        scope.runCurrent()
        assertEquals(3, ui.track.size)
        // Landing keeps the track on screen; the next takeoff starts a new one.
        drone.flight(flightData(flying = false))
        drone.telemetry { it.copy(odometry = odometry(1f, 1f)) }
        scope.runCurrent()
        assertEquals(3, ui.track.size)
        drone.flight(flightData(flying = true))
        scope.runCurrent()
        assertEquals(listOf(androidx.compose.ui.geometry.Offset(1f, 1f)), ui.track)
    }

    @Test
    fun `the track is bounded to 2000 points, the oldest dropped`() = test {
        drone.flight(flightData(flying = true))
        repeat(2_010) { i ->
            drone.telemetry { it.copy(odometry = odometry(i * 0.2f, 0f)) }
            scope.runCurrent()
        }
        assertEquals(2_000, ui.track.size)
        assertEquals(10 * 0.2f, ui.track.first().x, 1e-3f)
        assertEquals(2_009 * 0.2f, ui.track.last().x, 1e-2f)
    }

    // ------------------------------------------------------- recording / messages

    @Test
    fun `automatic recording starts at takeoff and stops at landing, a manual one is kept`() = test(
        FlightViewModelRig.linearPilot { copy(autoRecord = true) },
    ) {
        onGround()
        drone.flight(flightData(flying = true))
        scope.runCurrent()
        verify(exactly = 1) { recorder.start() }
        coVerify { drone.controller.requestKeyFrame() }
        drone.flight(flightData(flying = false))
        scope.runCurrent()
        verify(exactly = 1) { recorder.stop() }
        // Started by hand: landing does not stop it.
        vm.onAction(FlightAction.ToggleRecording)
        drone.flight(flightData(flying = true))
        scope.runCurrent()
        drone.flight(flightData(flying = false))
        scope.runCurrent()
        verify(exactly = 1) { recorder.stop() }
        assertTrue(recording)
    }

    @Test
    fun `post-flight re-encoding is paused while flying`() = test {
        drone.flight(flightData(flying = true))
        scope.runCurrent()
        verify { transcode.setFlying(vm, true) }
        drone.flight(flightData(flying = false))
        scope.runCurrent()
        verify { transcode.setFlying(vm, false) }
    }

    @Test
    fun `messages - video saved or failed, photo failure, drone refusal, calibration once each`() = test {
        recorderState.value = RecordingState.Saved(io.mockk.mockk(relaxed = true))
        scope.runCurrent()
        recorderState.value = RecordingState.Failed("plein")
        scope.runCurrent()
        photoSaved.tryEmit(Result.failure(RuntimeException()))
        photoSaved.tryEmit(Result.success(Unit))
        scope.runCurrent()
        verify { shutter.play() }
        events.tryEmit(com.drone.tello.control.DroneEvent.ManeuverRefused(com.drone.tello.control.FlightMode.Flip))
        scope.runCurrent()
        repeat(3) { drone.flight(flightData(flying = false, gravityCalibration = 1)); scope.runCurrent() }
        drone.flight(flightData(flying = false, gravityCalibration = 2))
        scope.runCurrent()
        drone.flight(flightData(flying = false, gravityCalibration = 2, battery = 79))
        scope.runCurrent()
        assertEquals(
            listOf(
                UserMessage.VideoSaved, UserMessage.VideoFailed, UserMessage.PhotoFailed, UserMessage.DroneRefused,
                UserMessage.GravityCalibrating, UserMessage.GravityCalibrated,
            ),
            messages,
        )
    }

    @Test
    fun `leaving the screen releases the sticks, stops recording and the autopilot`() = test {
        cruising()
        vm.setGamepadActive(true)
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        val clear = FlightViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        clear.invoke(vm)
        assertEquals(null, attached)
        verify { drone.controller.releaseSticks() }
        verify { recorder.stop() }
        verify { transcode.setFlying(vm, false) }
        assertFalse(autopilot.active)
    }
}
