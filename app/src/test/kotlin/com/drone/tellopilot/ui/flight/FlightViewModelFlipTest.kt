package com.drone.tellopilot.ui.flight

import com.drone.tello.control.GamepadMapper
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.ModeCheck
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadSticks
import com.drone.tello.control.PilotSettings
import com.drone.tello.protocol.FlipDirection
import com.drone.tellopilot.testutil.imu
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Flips through the view model (incident of 14 Nov. 2026): the pad sequence as the mapper
 * delivers it, the first-flip notice, what runs is never ended by a flip, and shock detection
 * suspended only for a flip actually sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightViewModelFlipTest {

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun test(
        pilot: PilotSettings = FlightViewModelRig.linearPilot(),
        block: suspend FlightViewModelRig.() -> Unit,
    ) = runTest(main) { withRig(pilot, block) }

    private fun FlightViewModelRig.cruising() {
        drone.simulate(scope, startHeight = 2.5f)
        scope.advanceTimeBy(5_000)
    }

    /** Pad buttons through the real mapper, its actions to the view model (as GamepadInput does). */
    private fun FlightViewModelRig.pad(mapper: GamepadMapper, button: PadButton, down: Boolean) {
        val action = if (down) mapper.onButtonDown(button) else mapper.onButtonUp(button)
        action?.let(vm::onPadAction)
        scope.runCurrent()
    }

    @Test
    fun `L2 + D-pad up - one flip, sent on the release, sticks untouched by the combo`() = test {
        cruising()
        val mapper = GamepadMapper()
        val sticksBefore = drone.sticks.size
        pad(mapper, PadButton.L2, down = true)
        pad(mapper, PadButton.DPAD_UP, down = true)
        coVerify(exactly = 0) { drone.controller.flip(any(), any()) }
        pad(mapper, PadButton.DPAD_UP, down = false)
        coVerify(exactly = 1) { drone.controller.flip(FlipDirection.FORWARD, false) }
        // Rolled press up → right: nothing more.
        pad(mapper, PadButton.DPAD_UP, down = true)
        pad(mapper, PadButton.DPAD_RIGHT, down = true)
        pad(mapper, PadButton.DPAD_UP, down = false)
        pad(mapper, PadButton.DPAD_RIGHT, down = false)
        pad(mapper, PadButton.L2, down = false)
        coVerify(exactly = 1) { drone.controller.flip(any(), any()) }
        assertEquals("no stick written by buttons", sticksBefore, drone.sticks.size)
    }

    @Test
    fun `a cancelled pad flip is told to the pilot, with a buzz, and logged`() = test {
        cruising()
        val mapper = GamepadMapper()
        pad(mapper, PadButton.L2, down = true)
        pad(mapper, PadButton.DPAD_UP, down = true)
        pad(mapper, PadButton.DPAD_LEFT, down = true) // GameSir G4 Pro: "left" 150 ms after "up" (1 press in 4)
        pad(mapper, PadButton.DPAD_LEFT, down = false)
        pad(mapper, PadButton.DPAD_UP, down = false)
        coVerify(exactly = 0) { drone.controller.flip(any(), any()) }
        assertEquals(UserMessage.FlipCancelledDiagonal, messages.last())
        io.mockk.verify { haptics.warning() }
        io.mockk.verify { drone.controller.recordEvent("flip_cancelled:diagonal") }
    }

    @Test
    fun `a refused flip does not suspend the shock detection`() = test {
        cruising()
        coEvery { drone.controller.flip(any(), any()) } returns ModeCheck.TOO_LOW
        vm.onPadAction(PadAction.Flip(FlipDirection.FORWARD))
        scope.runCurrent()
        assertEquals(UserMessage.ModeRefused(ModeCheck.TOO_LOW), messages.last())
        drone.telemetry { it.copy(imu = imu(rollDeg = 80f)) }
        scope.advanceTimeBy(100)
        drone.telemetry { it.copy(imu = imu(rollDeg = 85f)) }
        scope.runCurrent()
        assertTrue(Warning.IMPACT in ui.warnings)
    }

    @Test
    fun `an automatic flight makes the flip busy, and keeps flying`() = test {
        cruising()
        vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        coEvery { drone.controller.flip(any(), true) } returns ModeCheck.BUSY
        vm.onPadAction(PadAction.Flip(FlipDirection.FORWARD))
        scope.runCurrent()
        coVerify { drone.controller.flip(FlipDirection.FORWARD, true) }
        assertTrue("a refused flip never ends the automatic flight", autopilot.active)
        assertEquals(UserMessage.ModeRefused(ModeCheck.BUSY), messages.last())
    }

    @Test
    fun `first flip - a safety notice, nothing flown, confirmed once`() =
        test(FlightViewModelRig.linearPilot { copy(flipWarningAccepted = false) }) {
            cruising()
            vm.onPadAction(PadAction.Flip(FlipDirection.FORWARD))
            scope.runCurrent()
            assertTrue(vm.flipWarning.value)
            coVerify(exactly = 0) { drone.controller.flip(any(), any()) }
            vm.acceptFlipWarning()
            scope.runCurrent()
            assertFalse(vm.flipWarning.value)
            coVerify { settingsRepo.updatePilot(any()) }
            // The store answers with the accepted setting: the next flip goes.
            settings.value = settings.value.copy(pilot = settings.value.pilot.copy(flipWarningAccepted = true))
            scope.runCurrent()
            vm.onAction(FlightAction.Flip(FlipDirection.BACK))
            scope.runCurrent()
            coVerify(exactly = 1) { drone.controller.flip(FlipDirection.BACK, false) }
        }

    @Test
    fun `first flip - dismissing the notice remembers nothing`() =
        test(FlightViewModelRig.linearPilot { copy(flipWarningAccepted = false) }) {
            vm.onAction(FlightAction.Flip(FlipDirection.FORWARD))
            vm.dismissFlipWarning()
            scope.runCurrent()
            assertFalse(vm.flipWarning.value)
            coVerify(exactly = 0) { settingsRepo.updatePilot(any()) }
            vm.onAction(FlightAction.Flip(FlipDirection.FORWARD))
            assertTrue(vm.flipWarning.value)
        }

    @Test
    fun `pad sticks held during the flip still reach the controller, which neutralises them`() = test {
        // The view model never filters: the controller is the single place that holds the
        // sticks neutral around a flip (tested in TelloControllerFlipTest).
        cruising()
        vm.onPadAction(PadAction.Flip(FlipDirection.FORWARD))
        vm.onPadSticks(PadSticks(0f, 0f, 1f, 0f))
        scope.runCurrent()
        coVerify { drone.controller.flip(FlipDirection.FORWARD, false) }
        assertEquals(1f, drone.lastSticks!!.roll, 1e-6f)
    }
}
