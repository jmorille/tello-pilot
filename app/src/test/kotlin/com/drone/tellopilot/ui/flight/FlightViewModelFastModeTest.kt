package com.drone.tellopilot.ui.flight

import com.drone.tello.control.ManeuverState
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.testutil.FakeDrone
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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

/** Fast mode kept between launches ("Mode rapide au démarrage"), and given to every new controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightViewModelFastModeTest {

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun test(initialFastMode: Boolean = false, block: suspend FlightViewModelRig.() -> Unit) =
        testWith({ initialFastMode }, block)

    /** [answer]: the store's answer, e.g. suspended on a [CompletableDeferred] to test races. */
    private fun testWith(answer: suspend () -> Boolean, block: suspend FlightViewModelRig.() -> Unit) = runTest(main) {
        val rig = FlightViewModelRig(this, initialFastMode = answer)
        try {
            rig.block()
        } finally {
            rig.close()
            runCurrent()
        }
    }

    @Test
    fun `starts in normal mode when the store says so`() = test(initialFastMode = false) {
        assertFalse(ui.fastMode)
        verify(exactly = 0) { drone.controller.setFastMode(true) }
    }

    @Test
    fun `a remembered fast mode is restored at launch and sent to the drone`() = test(initialFastMode = true) {
        assertTrue(ui.fastMode)
        verify { drone.controller.setFastMode(true) }
        assertEquals(StickInput(fastMode = true), drone.lastSticks)
        // The next stick packet carries the fast flag too.
        vm.onRightStick(0f, 0.5f)
        assertEquals(StickInput(pitch = 0.5f, fastMode = true), drone.lastSticks)
    }

    @Test
    fun `every toggle is remembered for the next launch`() = test {
        vm.onAction(FlightAction.ToggleFastMode)
        scope.runCurrent()
        vm.onAction(FlightAction.ToggleFastMode)
        scope.runCurrent()
        coVerifyOrder {
            settingsRepo.rememberFastMode(true)
            settingsRepo.rememberFastMode(false)
        }
    }

    @Test
    fun `the tripod turns the fast mode off, and that is remembered`() = test(initialFastMode = true) {
        vm.onAction(FlightAction.ToggleTripod)
        scope.runCurrent()
        assertFalse(ui.fastMode)
        assertTrue(ui.tripod)
        coVerify { settingsRepo.rememberFastMode(false) }
        // Tripod off again: the fast mode stays off (and nothing more is stored).
        vm.onAction(FlightAction.ToggleTripod)
        scope.runCurrent()
        assertFalse(ui.fastMode)
        coVerify(exactly = 1) { settingsRepo.rememberFastMode(any()) }
    }

    @Test
    fun `the tripod alone does not store anything`() = test {
        vm.onAction(FlightAction.ToggleTripod)
        scope.runCurrent()
        coVerify(exactly = 0) { settingsRepo.rememberFastMode(any()) }
    }

    @Test
    fun `a new controller gets the current mode`() = test(initialFastMode = true) {
        val next = FakeDrone()
        controller.value = next.controller
        scope.runCurrent()
        verify { next.controller.setFastMode(true) }

        vm.onAction(FlightAction.ToggleFastMode)
        scope.runCurrent()
        val third = FakeDrone()
        controller.value = third.controller
        scope.runCurrent()
        verify { third.controller.setFastMode(false) }
        verify(exactly = 0) { third.controller.setFastMode(true) }
    }

    // ------------------------------------------- the store answers late (startup race)

    @Test
    fun `a fast mode toggle while the store answers wins`() {
        val store = CompletableDeferred<Boolean>()
        testWith({ store.await() }) {
            vm.onAction(FlightAction.ToggleFastMode) // on
            vm.onAction(FlightAction.ToggleFastMode) // off again
            scope.runCurrent()
            verify(exactly = 1) { drone.controller.setFastMode(true) } // the pilot's own toggle only
            store.complete(true)
            scope.runCurrent()
            assertFalse(ui.fastMode)
            verify(exactly = 1) { drone.controller.setFastMode(true) }
        }
    }

    @Test
    fun `a tripod toggle while the store answers prevents the restore`() {
        val store = CompletableDeferred<Boolean>()
        testWith({ store.await() }) {
            vm.onAction(FlightAction.ToggleTripod)
            scope.runCurrent()
            store.complete(true)
            scope.runCurrent()
            assertFalse(ui.fastMode)
            assertTrue(ui.tripod)
            verify(exactly = 0) { drone.controller.setFastMode(true) }
        }
    }

    @Test
    fun `no restore while a smart video flies`() {
        val store = CompletableDeferred<Boolean>()
        testWith({ store.await() }) {
            drone.maneuversFlow.value = ManeuverState(requestedSmartVideo = SmartVideoMode.CIRCLE)
            scope.runCurrent()
            store.complete(true)
            scope.runCurrent()
            assertFalse(ui.fastMode)
            verify(exactly = 0) { drone.controller.setFastMode(true) }
        }
    }

    @Test
    fun `no restore during an automatic flight`() {
        val store = CompletableDeferred<Boolean>()
        testWith({ store.await() }) {
            drone.simulate(scope)
            scope.advanceTimeBy(150)
            vm.onAction(FlightAction.StartMission(Mission("Test", listOf(MissionStep.Hover(30f)))))
            scope.advanceTimeBy(100)
            assertTrue(autopilot.active)
            store.complete(true)
            scope.runCurrent()
            assertFalse(ui.fastMode)
            verify(exactly = 0) { drone.controller.setFastMode(true) }
        }
    }
}