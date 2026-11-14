package com.drone.tellopilot.ui.flight

import androidx.lifecycle.viewModelScope
import com.drone.tello.control.DroneEvent
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TelloController
import com.drone.tellopilot.data.AppSettings
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.Haptics
import com.drone.tellopilot.data.MonotonicClock
import com.drone.tellopilot.data.PhoneHeat
import com.drone.tellopilot.data.PhoneThermal
import com.drone.tellopilot.data.SettingsRepository
import com.drone.tellopilot.data.ShutterSound
import com.drone.tellopilot.data.WifiState
import com.drone.tellopilot.input.GamepadInput
import com.drone.tellopilot.input.GamepadStatus
import com.drone.tellopilot.testutil.FakeDrone
import com.drone.tellopilot.tracking.Autopilot
import com.drone.tellopilot.video.RecordingState
import com.drone.tellopilot.video.TranscodeQueue
import com.drone.tellopilot.video.VideoRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

/**
 * A [FlightViewModel] wired to test doubles: a [FakeDrone] behind a mocked [DroneSession],
 * a real [Autopilot] on the test dispatcher, and virtual clocks. Everything runs on the test
 * scheduler (`Dispatchers.Main` must be set to the test dispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FlightViewModelRig(
    val scope: TestScope,
    pilot: PilotSettings = linearPilot(),
    /** What the settings store answers for the start mode ([SettingsRepository.initialFastMode]). */
    initialFastMode: suspend () -> Boolean = { false },
) {
    val drone = FakeDrone()
    val controller = MutableStateFlow<TelloController?>(drone.controller)
    val wifi = MutableStateFlow<WifiState>(WifiState.Drone(mockk(relaxed = true)))
    val settings = MutableStateFlow(AppSettings(pilot = pilot))
    val padStatus = MutableStateFlow(GamepadStatus.NONE)
    val recorderState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val events = MutableSharedFlow<DroneEvent>(extraBufferCapacity = 8)
    val photoSaved = MutableSharedFlow<Result<Unit>>(extraBufferCapacity = 4)
    var recording = false
    var attached: GamepadInput.Listener? = null

    val haptics: Haptics = mockk(relaxed = true)
    val shutter: ShutterSound = mockk(relaxed = true)
    val transcode: TranscodeQueue = mockk(relaxed = true)
    val gamepad: GamepadInput = mockk(relaxed = true)
    val recorder: VideoRecorder = mockk(relaxed = true)
    val session: DroneSession = mockk(relaxed = true)
    val settingsRepo: SettingsRepository = mockk(relaxed = true)
    private val thermal: PhoneThermal = mockk(relaxed = true)
    private val context: android.content.Context = mockk(relaxed = true)

    init {
        every { settingsRepo.settings } returns settings
        coEvery { settingsRepo.initialFastMode() } coAnswers { initialFastMode() }
        every { thermal.heat } returns flowOf(PhoneHeat.NORMAL)
        every { context.getString(any()) } returns "Hauteur de décollage"
        every { gamepad.status } returns padStatus
        every { gamepad.attach(any()) } answers { attached = firstArg() }
        every { gamepad.isAttached(any()) } answers { attached === firstArg<Any>() }
        every { recorder.state } returns recorderState
        every { recorder.isActive } answers { recording }
        every { recorder.start() } answers { recording = true }
        every { recorder.stop() } answers { recording = false }
        every { session.controller } returns controller
        every { session.wifiState } returns wifi
        every { session.link } returns drone.linkFlow
        every { session.telemetry } returns drone.telemetryFlow
        every { session.maneuvers } returns drone.maneuversFlow
        every { session.videoActive } returns MutableStateFlow(true)
        every { session.photoProgress } returns flowOf(null)
        every { session.photoSaved } returns photoSaved
        every { session.events } returns events
        every { session.lastFlight } answers { controller.value?.telemetry?.value?.flight }
        coEvery { session.run() } coAnswers { awaitCancellation() }
    }

    val autopilot = Autopilot(session, settingsRepo, StandardDispatcherOf(scope)) { scope.testScheduler.currentTime }

    private val clock = object : MonotonicClock {
        override fun elapsedRealtime() = BOOT_MS + scope.testScheduler.currentTime
        override fun uptimeMillis() = BOOT_MS + scope.testScheduler.currentTime
    }

    val vm = FlightViewModel(
        session, recorder, gamepad, haptics, shutter, transcode, autopilot, thermal, settingsRepo, context,
        clock, StandardDispatcherOf(scope),
    )

    val messages = mutableListOf<UserMessage>()

    init {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.messages.toList(messages) }
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.uiState.collect {} }
        scope.runCurrent()
    }

    val ui: FlightUiState get() = vm.uiState.value

    /** The view model and the autopilot stop with the test (the ground-heat loop never ends). */
    fun close() {
        autopilot.stop()
        vm.viewModelScope.cancel()
    }

    companion object {
        /**
         * Linear sticks (no dead zone, no expo): the stick value is the drone's. The first-flip
         * notice is already accepted (tested on its own with `flipWarningAccepted = false`).
         */
        fun linearPilot(build: PilotSettings.() -> PilotSettings = { this }) =
            PilotSettings(
                flipWarningAccepted = true,
                deadZone = 0f,
                axisNormal = com.drone.tello.control.FlightTuning.uniform(expo = 0f),
                axisFast = com.drone.tello.control.FlightTuning.uniform(expo = 0f),
            ).build()

        const val BOOT_MS = 3_600_000L
    }
}

/** A dispatcher on the scope's scheduler (queued, like the real threads: run with runCurrent / advanceTimeBy). */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun StandardDispatcherOf(scope: TestScope) = kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler)

/** Runs [block] with a rig, closing it afterwards. */
internal suspend fun TestScope.withRig(
    pilot: PilotSettings = FlightViewModelRig.linearPilot(),
    block: suspend FlightViewModelRig.() -> Unit,
) {
    val rig = FlightViewModelRig(this, pilot)
    try {
        rig.block()
    } finally {
        rig.close()
        runCurrent()
    }
}
