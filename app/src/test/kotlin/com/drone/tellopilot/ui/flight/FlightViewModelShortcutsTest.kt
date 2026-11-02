package com.drone.tellopilot.ui.flight

import com.drone.tello.control.ManeuverState
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.ModeCheck
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadFunction
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TrackingMode
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tellopilot.testutil.flightData
import com.drone.tellopilot.tracking.AutopilotEnd
import com.drone.tellopilot.tracking.AutopilotState
import io.mockk.coEvery
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
 * Pad shortcuts through the view model: the same checks and messages as the screen buttons,
 * a vibration on every refusal, refusal while a manoeuvre runs, the pad's stop, and deleted
 * missions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightViewModelShortcutsTest {

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private val waiting = Mission("Attente", listOf(MissionStep.Hover(30f)))
    private val withMission = FlightViewModelRig.linearPilot { copy(customMissions = listOf(waiting)) }

    private fun test(pilot: PilotSettings = withMission, block: suspend FlightViewModelRig.() -> Unit) =
        runTest(main) { withRig(pilot, block) }

    private fun FlightViewModelRig.flying() {
        drone.simulate(scope)
        scope.advanceTimeBy(150)
    }

    private fun FlightViewModelRig.shortcut(function: PadFunction) {
        vm.onPadAction(PadAction.Shortcut(function))
        scope.runCurrent()
    }

    // ------------------------------------------------------------ same path as the screen

    @Test
    fun `a 360 shortcut in flight starts the smart video`() = test {
        flying()
        shortcut(PadFunction.SmartVideo(SmartVideoMode.ROTATE_360))
        coVerify { drone.controller.startSmartVideo(SmartVideoMode.ROTATE_360) }
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `a refusal of the drone checks shows the screen message and vibrates`() = test {
        flying()
        coEvery { drone.controller.flip(any()) } returns ModeCheck.LOW_BATTERY
        shortcut(PadFunction.Flip(FlipDirection.BACK))
        assertEquals(UserMessage.ModeRefused(ModeCheck.LOW_BATTERY), messages.last())
        verify { haptics.warning() }
    }

    @Test
    fun `a touch refusal does not vibrate (unchanged screen behaviour)`() = test {
        flying()
        coEvery { drone.controller.flip(any()) } returns ModeCheck.TOO_HIGH
        vm.onAction(FlightAction.Flip(FlipDirection.BACK))
        scope.runCurrent()
        assertEquals(UserMessage.ModeRefused(ModeCheck.TOO_HIGH), messages.last())
        verify(exactly = 0) { haptics.warning() }
    }

    @Test
    fun `missions and tracking on the ground are refused like on the screen`() = test {
        drone.flight(flightData(flying = false))
        scope.runCurrent()
        shortcut(PadFunction.RunMission("Attente"))
        assertFalse(autopilot.active)
        assertEquals(UserMessage.AutopilotUnavailable, messages.last())
        scope.advanceTimeBy(2_000) // past the anti double-press delay
        shortcut(PadFunction.Tracking(TrackingMode.ORBIT, -1))
        assertFalse(autopilot.active)
        assertEquals(2, messages.count { it == UserMessage.AutopilotUnavailable })
        verify(exactly = 2) { haptics.warning() }
        shortcut(PadFunction.PalmLand)
        coVerify(exactly = 0) { drone.controller.palmLand() }
    }

    @Test
    fun `a mission shortcut starts the named mission`() = test {
        flying()
        shortcut(PadFunction.RunMission("Attente"))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        assertEquals("Attente", (ui.autopilot as AutopilotState.RunningMission).name)
    }

    @Test
    fun `a deleted mission - nothing happens, the pilot is told`() = test {
        flying()
        shortcut(PadFunction.RunMission("Supprimée"))
        scope.advanceTimeBy(100)
        assertFalse(autopilot.active)
        assertEquals(UserMessage.ShortcutMissionMissing("Supprimée"), messages.last())
        verify { haptics.warning() }
    }

    // ------------------------------------------------------------ manoeuvre in progress

    @Test
    fun `a moving shortcut is refused while a smart video runs`() = test {
        flying()
        drone.maneuversFlow.value = ManeuverState(requestedSmartVideo = SmartVideoMode.CIRCLE)
        shortcut(PadFunction.Flip(FlipDirection.FORWARD))
        coVerify(exactly = 0) { drone.controller.flip(any()) }
        assertEquals(UserMessage.ModeRefused(ModeCheck.BUSY), messages.last())
        verify { haptics.warning() }
        // Photo, video and the stop stay available.
        shortcut(PadFunction.Photo)
        coVerify { drone.controller.takePicture() }
    }

    @Test
    fun `a moving shortcut is refused during a mission, which keeps flying`() = test {
        flying()
        shortcut(PadFunction.RunMission("Attente"))
        scope.advanceTimeBy(1_600)
        assertTrue(autopilot.active)
        shortcut(PadFunction.SmartVideo(SmartVideoMode.ROTATE_360))
        assertTrue(autopilot.active)
        coVerify(exactly = 0) { drone.controller.startSmartVideo(any()) }
        assertEquals(UserMessage.ModeRefused(ModeCheck.BUSY), messages.last())
    }

    @Test
    fun `no manoeuvre over the hand after a palm landing, nor during the bounce`() = test {
        flying()
        shortcut(PadFunction.PalmLand)
        coVerify { drone.controller.palmLand() }
        scope.advanceTimeBy(2_000) // past the anti double-press delay
        shortcut(PadFunction.Flip(FlipDirection.FORWARD))
        coVerify(exactly = 0) { drone.controller.flip(any()) }
        assertEquals(UserMessage.ModeRefused(ModeCheck.BUSY), messages.last())
        scope.advanceTimeBy(16_000)
        drone.maneuversFlow.value = ManeuverState(bounce = true)
        shortcut(PadFunction.RunMission("Attente"))
        assertFalse(autopilot.active)
        assertEquals(UserMessage.ModeRefused(ModeCheck.BUSY), messages.last())
    }

    @Test
    fun `switching the bounce off right after switching it on is never dropped`() = test {
        flying()
        shortcut(PadFunction.Bounce)
        coVerify { drone.controller.setBounce(true) }
        drone.maneuversFlow.value = ManeuverState(bounce = true)
        scope.advanceTimeBy(300)
        shortcut(PadFunction.Bounce)
        coVerify { drone.controller.setBounce(false) }
    }

    @Test
    fun `the pad stop ends the mission, the bounce and the smart video`() = test {
        flying()
        shortcut(PadFunction.RunMission("Attente"))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        drone.maneuversFlow.value = ManeuverState(bounce = true)
        vm.onPadAction(PadAction.StopSmartVideo)
        scope.runCurrent()
        assertFalse(autopilot.active)
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.Stopped), messages.last())
        coVerify { drone.controller.setBounce(false) }
        coVerify { drone.controller.stopSmartVideo() }
    }

    @Test
    fun `a stop shortcut stops as well, and switching the bounce off is always allowed`() = test {
        flying()
        drone.maneuversFlow.value = ManeuverState(bounce = true, requestedSmartVideo = SmartVideoMode.CIRCLE)
        shortcut(PadFunction.Bounce)
        coVerify { drone.controller.setBounce(false) }
        shortcut(PadFunction.Stop)
        coVerify { drone.controller.stopSmartVideo() }
    }

    @Test
    fun `the sticks still take over a manoeuvre started by a shortcut`() = test {
        flying()
        shortcut(PadFunction.RunMission("Attente"))
        scope.advanceTimeBy(100)
        assertTrue(autopilot.active)
        vm.onPadSticks(com.drone.tello.control.PadSticks(0f, 0f, 0f, 0.6f))
        assertFalse(autopilot.active)
        scope.runCurrent()
        assertEquals(UserMessage.AutopilotEnded(AutopilotEnd.PilotTookOver), messages.last())
    }

    @Test
    fun `a double press of a moving shortcut sends it once`() = test {
        flying()
        shortcut(PadFunction.Flip(FlipDirection.LEFT))
        scope.advanceTimeBy(500)
        shortcut(PadFunction.Flip(FlipDirection.LEFT))
        coVerify(exactly = 1) { drone.controller.flip(FlipDirection.LEFT) }
        scope.advanceTimeBy(1_500)
        shortcut(PadFunction.Flip(FlipDirection.LEFT))
        coVerify(exactly = 2) { drone.controller.flip(FlipDirection.LEFT) }
    }

    @Test
    fun `speed modes and recording go through the same toggles as the screen`() = test {
        flying()
        shortcut(PadFunction.FastMode)
        assertTrue(ui.fastMode)
        shortcut(PadFunction.Tripod)
        assertTrue(ui.tripod)
        assertFalse(ui.fastMode)
        shortcut(PadFunction.Record)
        assertTrue(recording)
    }
}
