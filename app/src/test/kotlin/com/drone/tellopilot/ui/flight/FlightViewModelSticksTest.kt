package com.drone.tellopilot.ui.flight

import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.ManeuverState
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadSticks
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.input.GamepadStatus
import com.drone.tellopilot.testutil.flightData
import com.drone.tellopilot.tracking.AutopilotEnd
import com.drone.tellopilot.tracking.AutopilotState
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

/** Touch and gamepad sticks down to the drone, takeover of automatic flights, tripod / fast mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightViewModelSticksTest {

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun test(
        pilot: com.drone.tello.control.PilotSettings = FlightViewModelRig.linearPilot(),
        block: suspend FlightViewModelRig.() -> Unit,
    ) = runTest(main) { withRig(pilot, block) }

    private fun FlightViewModelRig.flyingConnected() {
        drone.flight(flightData(flying = true))
        scope.runCurrent()
    }

    // ------------------------------------------------------------------ sticks

    @Test
    fun `touch sticks reach the drone with the mode 2 layout`() = test {
        vm.onLeftStick(0.5f, 0.25f) // yaw, throttle
        vm.onRightStick(-0.75f, 1f) // roll, pitch
        assertEquals(StickInput(roll = -0.75f, pitch = 1f, throttle = 0.25f, yaw = 0.5f), drone.lastSticks)
    }

    @Test
    fun `stick mode and shaping follow the settings`() = test(FlightViewModelRig.linearPilot { copy(stickMode = com.drone.tello.control.StickMode.MODE_1) }) {
        vm.onLeftStick(0f, 0.5f) // mode 1: left y = pitch
        assertEquals(0.5f, drone.lastSticks!!.pitch, 1e-6f)
        settings.value = settings.value.copy(pilot = settings.value.pilot.copy(deadZone = 0.2f))
        scope.runCurrent()
        vm.onLeftStick(0f, 0.1f) // inside the new dead zone
        assertEquals(0f, drone.lastSticks!!.pitch, 0f)
    }

    @Test
    fun `gamepad sticks reach the drone and merge per axis with touch`() = test {
        vm.onPadSticks(PadSticks(leftX = 0f, leftY = 0.4f, rightX = 0.2f, rightY = 0f))
        assertEquals(StickInput(roll = 0.2f, throttle = 0.4f), drone.lastSticks)
        // Touch holds the right stick further on roll; the pad keeps the throttle.
        vm.onRightStick(-0.6f, 0f)
        assertEquals(StickInput(roll = -0.6f, throttle = 0.4f), drone.lastSticks)
        // Lifting the finger never cancels the held pad stick.
        vm.onRightStick(0f, 0f)
        assertEquals(StickInput(roll = 0.2f, throttle = 0.4f), drone.lastSticks)
        // Pad back to neutral: nothing held anywhere.
        vm.onPadSticks(PadSticks.Neutral)
        assertEquals(StickInput.Neutral, drone.lastSticks)
    }

    @Test
    fun `a deliberate pad movement hides the screen sticks, drift does not, a touch shows them again`() = test {
        padStatus.value = GamepadStatus.READY
        scope.runCurrent()
        vm.onPadSticks(PadSticks(0.2f, 0f, 0f, 0f)) // drift / noise
        scope.runCurrent()
        assertFalse(ui.sticksHidden)
        vm.onPadSticks(PadSticks(0.5f, 0f, 0f, 0f))
        scope.runCurrent()
        assertTrue(ui.sticksHidden)
        vm.onLeftStick(0.1f, 0f)
        scope.runCurrent()
        assertFalse(ui.sticksHidden)
        // A pad that is not ready any more gives the screen sticks back.
        vm.onRightStick(0f, 0f); vm.onLeftStick(0f, 0f)
        vm.onPadSticks(PadSticks(0.9f, 0f, 0f, 0f))
        scope.runCurrent()
        assertTrue(ui.sticksHidden)
        padStatus.value = GamepadStatus.NONE
        scope.runCurrent()
        assertFalse(ui.sticksHidden)
    }

    @Test
    fun `a pad held while a touch stick is used does not hide the screen sticks`() = test {
        padStatus.value = GamepadStatus.READY
        vm.onLeftStick(0.3f, 0f)
        vm.onPadSticks(PadSticks(0f, 0f, 0.9f, 0f))
        scope.runCurrent()
        assertFalse(ui.sticksHidden)
    }

    @Test
    fun `releasing the sticks centres touch, pad and the drone`() = test {
        vm.onLeftStick(0.5f, 0.5f)
        vm.onPadSticks(PadSticks(0f, 0f, 0.7f, 0f))
        vm.onAction(FlightAction.ReleaseSticks)
        verify { drone.controller.releaseSticks() }
        verify { gamepad.release() }
        // The next event starts from neutral: nothing held is replayed.
        vm.onRightStick(0f, 0.1f)
        assertEquals(StickInput(pitch = 0.1f), drone.lastSticks)
    }

    @Test
    fun `no drone connected, sticks and commands are dropped without error`() = test {
        controller.value = null
        vm.onLeftStick(1f, 1f)
        vm.onAction(FlightAction.Takeoff)
        vm.onAction(FlightAction.TakePhoto)
        scope.runCurrent()
        assertTrue(drone.sticks.isEmpty())
        coVerify(exactly = 0) { drone.controller.takeoff() }
        // A manoeuvre is refused with a reason.
        vm.onAction(FlightAction.Flip(com.drone.tello.protocol.FlipDirection.LEFT))
        scope.runCurrent()
        assertEquals(UserMessage.ModeRefused(com.drone.tello.control.ModeCheck.NOT_CONNECTED), messages.last())
    }

    @Test
    fun `the gamepad flies only while the flight screen is active and is re-centred when it leaves`() = test {
        vm.setGamepadActive(true)
        assertTrue(attached === vm)
        vm.setGamepadActive(false)
        assertEquals(null, attached)
        verify { gamepad.attach(null) }
        // Already detached (another screen took the pad): not detached again.
        vm.setGamepadActive(false)
        verify(exactly = 1) { gamepad.attach(null) }
    }

    // ------------------------------------------------------- tripod / fast mode

    @Test
    fun `tripod scales the held stick at once, without a new stick event`() = test(FlightViewModelRig.linearPilot { copy(tripodScale = 0.3f) }) {
        vm.onRightStick(0f, 1f)
        assertEquals(1f, drone.lastSticks!!.pitch, 1e-6f)
        vm.onAction(FlightAction.ToggleTripod)
        assertEquals(0.3f, drone.lastSticks!!.pitch, 1e-6f)
        scope.runCurrent()
        assertTrue(ui.tripod)
        vm.onAction(FlightAction.ToggleTripod)
        assertEquals(1f, drone.lastSticks!!.pitch, 1e-6f)
    }

    @Test
    fun `fast mode and tripod are exclusive`() = test(FlightViewModelRig.linearPilot { copy(tripodScale = 0.5f) }) {
        vm.onAction(FlightAction.ToggleTripod)
        vm.onRightStick(0f, 1f)
        assertEquals(0.5f, drone.lastSticks!!.pitch, 1e-6f)
        vm.onAction(FlightAction.ToggleFastMode)
        scope.runCurrent()
        verify { drone.controller.setFastMode(true) }
        assertTrue(ui.fastMode)
        assertFalse(ui.tripod)
        // Tripod off: full scale again, applied at once.
        assertEquals(StickInput(pitch = 1f, fastMode = true), drone.lastSticks)
        // The next stick packet carries the fast flag.
        vm.onRightStick(0f, 0.5f)
        assertEquals(StickInput(pitch = 0.5f, fastMode = true), drone.lastSticks)
        // Tripod on again: fast mode off.
        vm.onAction(FlightAction.ToggleTripod)
        scope.runCurrent()
        verify { drone.controller.setFastMode(false) }
        assertFalse(ui.fastMode)
        assertTrue(ui.tripod)
    }

    @Test
    fun `the tripod scale setting applies while tripod is on`() = test(FlightViewModelRig.linearPilot { copy(tripodScale = 0.5f) }) {
        vm.onAction(FlightAction.ToggleTripod)
        settings.value = settings.value.copy(pilot = settings.value.pilot.copy(tripodScale = 0.2f))
        scope.runCurrent()
        vm.onRightStick(0f, 1f)
        assertEquals(0.2f, drone.lastSticks!!.pitch, 1e-6f)
    }

    // ------------------------------------------------------------ axis tuning

    private val slowPitch = com.drone.tello.control.FlightTuning.uniform(0f)
        .copy(pitch = com.drone.tello.control.AxisTuning(gain = 1f, amplitude = 0.5f, expo = 0f))
    private val boostedPitch = com.drone.tello.control.FlightTuning.uniform(0f)
        .copy(pitch = com.drone.tello.control.AxisTuning(gain = 1.5f, amplitude = 1f, expo = 0f))

    @Test
    fun `each flight mode has its own axis profile, switched at once with the stick held`() =
        test(FlightViewModelRig.linearPilot { copy(axisNormal = slowPitch, axisFast = boostedPitch) }) {
            vm.onRightStick(0f, 0.5f) // pitch
            assertEquals(StickInput(pitch = 0.25f), drone.lastSticks)
            vm.onAction(FlightAction.ToggleFastMode)
            assertEquals(StickInput(pitch = 0.75f, fastMode = true), drone.lastSticks)
            vm.onAction(FlightAction.ToggleFastMode)
            assertEquals(StickInput(pitch = 0.25f), drone.lastSticks)
            // Tripod on from fast mode: normal profile, then the tripod scale.
            vm.onAction(FlightAction.ToggleFastMode)
            vm.onAction(FlightAction.ToggleTripod)
            assertEquals(0.25f * settings.value.pilot.tripodScale, drone.lastSticks!!.pitch, 1e-6f)
            assertFalse(drone.lastSticks!!.fastMode)
        }

    @Test
    fun `the gamepad goes through the same axis profile`() =
        test(FlightViewModelRig.linearPilot { copy(axisNormal = boostedPitch) }) {
            vm.onPadSticks(PadSticks(leftX = 0f, leftY = 0f, rightX = 0f, rightY = 0.4f))
            assertEquals(0.6f, drone.lastSticks!!.pitch, 1e-6f)
        }

    private val weakest = com.drone.tello.control.StickAxis.entries.fold(com.drone.tello.control.FlightTuning.uniform(0f)) { t, a ->
        t.with(a, com.drone.tello.control.AxisTuning(gain = 0.5f, amplitude = 0.2f, expo = 0.8f))
    }

    @Test
    fun `a weak axis profile never prevents taking an automatic flight back`() =
        test(FlightViewModelRig.linearPilot { copy(deadZone = 0.2f, axisNormal = weakest, axisFast = weakest) }) {
            startMission()
            // The raw stick decides, not the (tiny) command it would give.
            vm.onRightStick(0f, 0.6f)
            assertFalse(autopilot.active)
            scope.advanceTimeBy(200)
            assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.PilotTookOver), messages.last())
            startMission()
            vm.onPadSticks(PadSticks(0f, 0f, -0.8f, 0f))
            assertFalse(autopilot.active)
        }

    @Test
    fun `automatic flights send their own sticks, untouched by the axis profiles`() {
        fun flown(pilot: com.drone.tello.control.PilotSettings): List<StickInput> {
            var sticks = emptyList<StickInput>()
            runTest(main) {
                withRig(pilot) {
                    drone.simulate(scope)
                    scope.advanceTimeBy(150)
                    vm.onAction(
                        FlightAction.StartMission(
                            Mission("Test", listOf(MissionStep.Rotate(90f), MissionStep.Climb(0.5f), MissionStep.Move(com.drone.tello.control.MoveDirection.FORWARD, 1f))),
                        ),
                    )
                    scope.advanceTimeBy(4_000)
                    sticks = drone.sticks.toList()
                }
            }
            return sticks
        }
        val linear = flown(FlightViewModelRig.linearPilot())
        val tuned = flown(FlightViewModelRig.linearPilot { copy(deadZone = 0.2f, axisNormal = weakest, axisFast = boostedPitch) })
        assertTrue("the mission moved the drone", linear.any { it != StickInput.Neutral })
        assertEquals(linear, tuned)
    }

    // --------------------------------------------------- automatic flight takeover

    private fun FlightViewModelRig.startMission() {
        drone.simulate(scope)
        scope.advanceTimeBy(150)
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
    }

    @Test
    fun `small stick movements are ignored during an automatic flight`() = test {
        startMission()
        vm.onLeftStick(0.1f, 0f)
        vm.onPadSticks(PadSticks(0f, 0f, 0f, -0.2f))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        // The autopilot keeps flying its own (hover) sticks.
        assertEquals(StickInput.Neutral, drone.lastSticks)
    }

    @Test
    fun `a clear stick movement takes control back at once and is applied`() = test {
        startMission()
        vm.onRightStick(0f, 0.6f)
        assertFalse(autopilot.active)
        // Neutral from the autopilot's stop, then the pilot's own stick, never the other way round.
        val last = drone.sticks.takeLast(2)
        assertEquals(StickInput.Neutral, last[0])
        assertEquals(StickInput(pitch = 0.6f), last[1])
        scope.advanceTimeBy(200)
        assertEquals(StickInput(pitch = 0.6f), drone.lastSticks)
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.PilotTookOver), messages.last())
        assertEquals(AutopilotState.Idle, ui.autopilot)
    }

    @Test
    fun `the gamepad takes control back too`() = test {
        startMission()
        vm.onPadSticks(PadSticks(0f, 0f, -0.8f, 0f))
        assertFalse(autopilot.active)
        assertEquals(StickInput(roll = -0.8f), drone.lastSticks)
    }

    @Test
    fun `sticks held when a mission starts are not applied meanwhile`() = test {
        drone.simulate(scope)
        scope.advanceTimeBy(150)
        vm.onRightStick(0f, 0.1f)
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        assertEquals(StickInput.Neutral, drone.lastSticks)
        verify { haptics.confirm() }
    }

    @Test
    fun `automatic flights start only in flight with a live link (and the video for tracking)`() = test {
        // On the ground.
        drone.flight(flightData(flying = false))
        scope.runCurrent()
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(3f)))))
        assertEquals(UserMessage.AutopilotUnavailable, messages.last())
        assertFalse(autopilot.active)
        // Flying but the link is lost.
        drone.flight(flightData(flying = true))
        drone.linkFlow.value = com.drone.tello.control.LinkState.RECONNECTING
        scope.runCurrent()
        messages.clear()
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(3f)))))
        assertEquals(listOf<UserMessage>(UserMessage.AutopilotUnavailable), messages)
        assertFalse(autopilot.active)
    }

    @Test
    fun `manual commands end the automatic flight first`() = test {
        for (action in listOf(FlightAction.Land, FlightAction.ToggleFastMode, FlightAction.ToggleTripod, FlightAction.StopAutopilot)) {
            startMission()
            vm.onAction(action)
            assertFalse("$action", autopilot.active)
            drone.running = false
            scope.advanceTimeBy(200)
            drone.running = true
        }
    }

    // ----------------------------------------------------------- smart videos

    @Test
    fun `a stick movement cancels a running smart video, once per second, retried while it runs`() = test {
        flyingConnected()
        drone.maneuversFlow.value = ManeuverState(requestedSmartVideo = SmartVideoMode.CIRCLE)
        vm.onAction(FlightAction.StartSmartVideo(SmartVideoMode.CIRCLE))
        scope.runCurrent()
        vm.onRightStick(0.5f, 0f)
        scope.runCurrent()
        coVerify(exactly = 1) { drone.controller.stopSmartVideo() }
        assertEquals(UserMessage.SmartVideoInterrupted, messages.last())
        verify { haptics.warning() }
        // Another movement within the second: no second request from the stick…
        vm.onRightStick(0.6f, 0f)
        scope.runCurrent()
        coVerify(exactly = 1) { drone.controller.stopSmartVideo() }
        // …but the drone still runs it: asked again every second (4 times at most).
        scope.advanceTimeBy(1_001)
        coVerify(exactly = 2) { drone.controller.stopSmartVideo() }
        drone.maneuversFlow.value = ManeuverState()
        scope.advanceTimeBy(5_000)
        coVerify(exactly = 2) { drone.controller.stopSmartVideo() }
    }

    @Test
    fun `a stick already held when the smart video starts does not cancel it until centred`() = test {
        flyingConnected()
        vm.onRightStick(0.5f, 0f)
        drone.maneuversFlow.value = ManeuverState(requestedSmartVideo = SmartVideoMode.ROTATE_360)
        vm.onAction(FlightAction.StartSmartVideo(SmartVideoMode.ROTATE_360))
        scope.runCurrent()
        vm.onRightStick(0.6f, 0f)
        scope.runCurrent()
        coVerify(exactly = 0) { drone.controller.stopSmartVideo() }
        vm.onRightStick(0f, 0f) // centred: armed
        vm.onRightStick(0.6f, 0f)
        scope.runCurrent()
        coVerify(exactly = 1) { drone.controller.stopSmartVideo() }
    }

    @Test
    fun `a refused manoeuvre is explained`() = test {
        io.mockk.coEvery { drone.controller.startSmartVideo(any()) } returns com.drone.tello.control.ModeCheck.TOO_HIGH
        vm.onAction(FlightAction.StartSmartVideo(SmartVideoMode.UP_AND_AWAY))
        scope.runCurrent()
        assertEquals(UserMessage.ModeRefused(com.drone.tello.control.ModeCheck.TOO_HIGH), messages.last())
    }

    // ------------------------------------------------------------------ gamepad

    @Test
    fun `gamepad buttons trigger the flight actions`() = test {
        vm.onPadAction(PadAction.Photo)
        vm.onPadAction(PadAction.ToggleFastMode)
        vm.onPadAction(PadAction.Flip(com.drone.tello.protocol.FlipDirection.BACK))
        vm.onPadAction(PadAction.ToggleRecording)
        vm.onPadAction(PadAction.StopSmartVideo)
        scope.runCurrent()
        coVerify { drone.controller.takePicture() }
        verify { drone.controller.setFastMode(true) }
        coVerify { drone.controller.flip(com.drone.tello.protocol.FlipDirection.BACK) }
        verify { recorder.start() }
        coVerify { drone.controller.stopSmartVideo() }
    }
}
