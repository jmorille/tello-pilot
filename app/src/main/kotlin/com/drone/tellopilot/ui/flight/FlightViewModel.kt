package com.drone.tellopilot.ui.flight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drone.tello.control.StickMixer
import com.drone.tello.control.TelloController
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.RealtimeThreads
import com.drone.tellopilot.video.FrameSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlightViewModel @Inject constructor(
    private val session: DroneSession,
) : ViewModel() {

    private val fastMode = MutableStateFlow(false)
    private val mixer = StickMixer()

    /** Last flying state reported by the drone; survives link losses and session restarts. */
    private val knownFlying = MutableStateFlow(false)

    val uiState: StateFlow<FlightUiState> = combine(
        combine(session.wifiAvailable, session.link, ::Pair),
        session.telemetry,
        session.videoActive,
        fastMode,
        knownFlying,
    ) { (wifi, link), telemetry, video, fast, flying ->
        FlightUiState.from(wifi, link, telemetry, video, fast, flying)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FlightUiState())

    init {
        // The drone session lives as long as the flight screen.
        viewModelScope.launch { session.run() }
        viewModelScope.launch {
            session.telemetry.collect { t -> t.flight?.let { knownFlying.value = it.flying } }
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

    fun attachVideo(sink: FrameSink) = session.attachVideo(sink)
    fun detachVideo(sink: FrameSink) = session.detachVideo(sink)

    /** Network I/O never runs on the main thread. */
    private fun command(block: suspend TelloController.() -> Boolean) {
        val controller = session.controller.value ?: return
        viewModelScope.launch(RealtimeThreads.control) { controller.block() }
    }

    override fun onCleared() {
        session.controller.value?.releaseSticks()
    }
}
