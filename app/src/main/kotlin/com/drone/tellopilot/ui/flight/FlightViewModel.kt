package com.drone.tellopilot.ui.flight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickMixer
import com.drone.tello.control.TelloController
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.RealtimeThreads
import com.drone.tellopilot.data.SettingsRepository
import com.drone.tellopilot.video.FrameSink
import com.drone.tellopilot.video.RecordingState
import com.drone.tellopilot.video.VideoRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot messages shown in a snackbar. */
enum class UserMessage { PHOTO_SAVED, PHOTO_FAILED, VIDEO_SAVED, VIDEO_FAILED }

@HiltViewModel
class FlightViewModel @Inject constructor(
    private val session: DroneSession,
    private val recorder: VideoRecorder,
    settings: SettingsRepository,
) : ViewModel() {

    private val fastMode = MutableStateFlow(false)
    private val mixer = StickMixer()

    /** Last flying state reported by the drone; survives link losses and session restarts. */
    private val knownFlying = MutableStateFlow(false)

    private val pilot: StateFlow<PilotSettings> = settings.settings.map { it.pilot }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PilotSettings())

    private val recordingUi: Flow<RecordingUi> = recorder.state.map {
        when (it) {
            RecordingState.Starting -> RecordingUi.Starting
            is RecordingState.Recording -> RecordingUi.Recording(it.startedAtMs)
            else -> RecordingUi.Idle
        }
    }.distinctUntilChanged()

    private val _messages = Channel<UserMessage>(Channel.BUFFERED)
    val messages: Flow<UserMessage> = _messages.receiveAsFlow()

    /** Recording started automatically at takeoff: stopped automatically at landing. */
    private var autoRecording = false

    val uiState: StateFlow<FlightUiState> = combine(
        combine(session.wifiAvailable, session.link, session.telemetry, ::Triple),
        combine(session.videoActive, fastMode, knownFlying, ::Triple),
        recordingUi,
        session.photoProgress,
        pilot,
    ) { (wifi, link, telemetry), (video, fast, flying), recording, photo, pilot ->
        FlightUiState.from(wifi, link, telemetry, video, fast, flying, recording, photo, pilot)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FlightUiState())

    init {
        // The drone session lives as long as the flight screen.
        viewModelScope.launch { session.run() }
        viewModelScope.launch {
            session.telemetry.collect { t -> t.flight?.let { onFlyingChanged(it.flying) } }
        }
        // Stick mapping / shaping are read on the main thread by the touch handler.
        viewModelScope.launch {
            pilot.collect {
                mixer.mode = it.stickMode
                mixer.shaping = it.shaping
            }
        }
        viewModelScope.launch {
            session.photoSaved.collect { _messages.trySend(if (it.isSuccess) UserMessage.PHOTO_SAVED else UserMessage.PHOTO_FAILED) }
        }
        viewModelScope.launch {
            // drop(1): the recorder is a singleton, its current state is not a new event.
            recorder.state.drop(1).collect {
                when (it) {
                    is RecordingState.Saved -> _messages.trySend(UserMessage.VIDEO_SAVED)
                    is RecordingState.Failed -> _messages.trySend(UserMessage.VIDEO_FAILED)
                    else -> Unit
                }
            }
        }
    }

    private fun onFlyingChanged(flying: Boolean) {
        val wasFlying = knownFlying.value
        knownFlying.value = flying
        if (flying && !wasFlying && pilot.value.autoRecord && !recorder.isActive) {
            autoRecording = true
            startRecording()
        } else if (!flying && wasFlying && autoRecording) {
            autoRecording = false
            stopRecording()
        }
    }

    // Stick events come straight from the touch handler (main thread) and go to an atomic
    // in the controller: no state flow, no recomposition on the hot path.
    fun onLeftStick(x: Float, y: Float) {
        session.controller.value?.setSticks(mixer.setLeft(x, y, fastMode.value))
    }

    fun onRightStick(x: Float, y: Float) {
        session.controller.value?.setSticks(mixer.setRight(x, y, fastMode.value))
    }

    fun onAction(action: FlightAction) {
        when (action) {
            FlightAction.Takeoff -> command { takeoff() }
            FlightAction.Land -> command { land() }
            FlightAction.ThrowTakeoff -> command { throwTakeoff() }
            FlightAction.PalmLand -> command { palmLand() }
            is FlightAction.Flip -> command { flip(action.direction) }
            FlightAction.TakePhoto -> command { takePicture() }
            FlightAction.ToggleRecording -> {
                autoRecording = false
                if (recorder.isActive) stopRecording() else startRecording()
            }
            FlightAction.ToggleFastMode -> {
                fastMode.value = !fastMode.value
                session.controller.value?.setFastMode(fastMode.value)
            }
            FlightAction.ReleaseSticks -> {
                mixer.reset()
                session.controller.value?.releaseSticks()
            }
        }
    }

    private fun startRecording() {
        recorder.start()
        // The file can only start on a key frame (SPS/PPS): ask for one now.
        command { requestKeyFrame(); setOnboardRecording(true) }
    }

    private fun stopRecording() {
        recorder.stop()
        command { setOnboardRecording(false) }
    }

    fun attachVideo(sink: FrameSink) = session.attachVideo(sink)
    fun detachVideo(sink: FrameSink) = session.detachVideo(sink)

    /** Network I/O never runs on the main thread. */
    private fun command(block: suspend TelloController.() -> Boolean) {
        val controller = session.controller.value ?: return
        viewModelScope.launch(RealtimeThreads.control) { controller.block() }
    }

    override fun onCleared() {
        session.controller.value?.releaseSticks()
        recorder.stop()
    }
}
