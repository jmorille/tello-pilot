package com.drone.tellopilot.ui.flight

import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.testutil.FakeDrone
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private fun test(initialFastMode: Boolean = false, block: suspend FlightViewModelRig.() -> Unit) = runTest(main) {
        val rig = FlightViewModelRig(this, initialFastMode = initialFastMode)
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
}
