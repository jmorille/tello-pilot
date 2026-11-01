package com.drone.tellopilot.ui.flight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drone.tello.control.DroneEvent
import com.drone.tello.control.ModeCheck
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadSticks
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickMixer
import com.drone.tello.control.TelloController
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.Haptics
import com.drone.tellopilot.data.PhoneThermal
import com.drone.tellopilot.data.RealtimeThreads
import com.drone.tellopilot.data.SettingsRepository
import com.drone.tellopilot.input.GamepadInput
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One-shot messages shown in a snackbar. */
sealed interface UserMessage {
    data object PhotoSaved : UserMessage
    data object PhotoFailed : UserMessage
    data object VideoSaved : UserMessage
    data object VideoFailed : UserMessage
    /** Refused by the app before sending (official preconditions). */
    data class ModeRefused(val reason: ModeCheck) : UserMessage
    /** Refused by the drone itself. */
    data object DroneRefused : UserMessage
}

@HiltViewModel
class FlightViewModel @Inject constructor(
    private val session: DroneSession,
    private val recorder: VideoRecorder,
    private val gamepad: GamepadInput,
    private val haptics: Haptics,
    private val transcodeQueue: com.drone.tellopilot.video.TranscodeQueue,
    phoneThermal: PhoneThermal,
    settings: SettingsRepository,
) : ViewModel(), GamepadInput.Listener {

    private val fastMode = MutableStateFlow(false)

    /** The gamepad drove last (sticks or buttons); a touch on a screen stick hands back to touch. */
    private val padDriving = MutableStateFlow(false)
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
        combine(recordingUi, session.photoProgress, ::Pair),
        combine(session.maneuvers, gamepad.status, phoneThermal.heat, padDriving, ::Extras),
        pilot,
    ) { (wifi, link, telemetry), (video, fast, flying), (recording, photo), extras, pilot ->
        FlightUiState.from(
            wifi, link, telemetry, video, fast, flying, recording, photo, pilot,
            extras.maneuvers, extras.pad, extras.heat, extras.padDriving,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FlightUiState())

    private data class Extras(
        val maneuvers: com.drone.tello.control.ManeuverState,
        val pad: com.drone.tellopilot.input.GamepadStatus,
        val heat: com.drone.tellopilot.data.PhoneHeat,
        val padDriving: Boolean,
    )

    init {
        // The drone session lives as long as the flight screen.
        viewModelScope.launch { session.run() }
        viewModelScope.launch {
            session.telemetry.collect { t -> t.flight?.let { onFlyingChanged(it.flying) } }
        }
        // Stick mapping / shaping are read on the main thread by the touch / gamepad handlers.
        viewModelScope.launch {
            pilot.collect {
                mixer.mode = it.stickMode
                mixer.shaping = it.shaping
                gamepad.setBindings(it.padBindings)
            }
        }
        viewModelScope.launch {
            session.photoSaved.collect { _messages.trySend(if (it.isSuccess) UserMessage.PhotoSaved else UserMessage.PhotoFailed) }
        }
        viewModelScope.launch {
            // drop(1): the recorder is a singleton, its current state is not a new event.
            recorder.state.drop(1).collect {
                when (it) {
                    is RecordingState.Saved -> _messages.trySend(UserMessage.VideoSaved)
                    is RecordingState.Failed -> _messages.trySend(UserMessage.VideoFailed)
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            session.events.collect { if (it is DroneEvent.ManeuverRefused) _messages.trySend(UserMessage.DroneRefused) }
        }
        viewModelScope.launch { vibrateOnAlerts() }
        // Post-flight re-encoding never competes with the live view in flight.
        viewModelScope.launch { knownFlying.collect { transcodeQueue.setFlying(this@FlightViewModel, it) } }
        // Pad gone or not centred: the screen sticks come back, and stay until the pad drives again.
        viewModelScope.launch {
            gamepad.status.collect { if (it != com.drone.tellopilot.input.GamepadStatus.READY) padDriving.value = false }
        }
    }

    /** Felt without looking: link lost, and every new alert (once per appearance). */
    private suspend fun vibrateOnAlerts() {
        var previous = FlightUiState()
        val lastVibration = HashMap<Warning, Long>()
        uiState.collect { state ->
            // Out of range the phone often drops the Wi-Fi first (WAITING_WIFI), or the session restarts (CONNECTING).
            val linkLost = previous.status == ConnectionStatus.CONNECTED && state.status != ConnectionStatus.CONNECTED
            val now = android.os.SystemClock.elapsedRealtime()
            // A flapping alert (wind, battery around the threshold) vibrates at most every 30 s.
            val fresh = (state.warnings - previous.warnings.toSet())
                .filter { now - (lastVibration[it] ?: (Long.MIN_VALUE / 2)) > ALERT_REPEAT_MS }
            fresh.forEach { lastVibration[it] = now }
            when {
                (linkLost && state.flying) || fresh.any { it.critical } -> haptics.critical()
                linkLost || fresh.isNotEmpty() -> haptics.warning()
            }
            previous = state
        }
    }

    /** The gamepad flies only while the flight screen is resumed (not from settings / calibration). */
    fun setGamepadActive(active: Boolean) {
        if (active) {
            gamepad.attach(this)
        } else {
            padDriving.value = false
            if (gamepad.isAttached(this)) gamepad.attach(null) // re-centres the pad sticks through onPadSticks
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

    // Touch and gamepad have separate slots, merged per axis (largest deflection wins): a pad
    // event never cancels a held touch stick and lifting a finger never cancels a held pad stick.
    // Everything runs on the main thread and ends in an atomic in the controller: no state flow,
    // no recomposition on the hot path.
    private val touch = FloatArray(4) // left x, left y, right x, right y
    private var pad = PadSticks.Neutral

    fun onLeftStick(x: Float, y: Float) {
        touch[0] = x; touch[1] = y
        if (x != 0f || y != 0f) padDriving.value = false
        pushSticks()
    }

    fun onRightStick(x: Float, y: Float) {
        touch[2] = x; touch[3] = y
        if (x != 0f || y != 0f) padDriving.value = false
        pushSticks()
    }

    override fun onPadSticks(sticks: PadSticks) {
        pad = sticks
        // Only a deliberate pad movement takes over: stick drift or noise must never hide the
        // screen sticks again right after the pilot switched back to the phone.
        if (touchIdle() && sticks.maxDeflection() >= PAD_TAKEOVER) padDriving.value = true
        pushSticks()
    }

    private fun pushSticks() {
        val fast = fastMode.value
        mixer.setLeft(merge(touch[0], pad.leftX), merge(touch[1], pad.leftY), fast)
        session.controller.value?.setSticks(mixer.setRight(merge(touch[2], pad.rightX), merge(touch[3], pad.rightY), fast))
    }

    private fun touchIdle() = touch.all { it == 0f }

    private fun PadSticks.maxDeflection() =
        maxOf(kotlin.math.abs(leftX), kotlin.math.abs(leftY), kotlin.math.abs(rightX), kotlin.math.abs(rightY))

    /** A touch on a hidden screen stick: show them again (that touch sends nothing). */
    fun revealSticks() {
        padDriving.value = false
    }

    private fun merge(a: Float, b: Float) = if (kotlin.math.abs(a) >= kotlin.math.abs(b)) a else b

    /** Last takeoff/land from the pad combo (anti double-press). */
    private var lastPadCombo = Long.MIN_VALUE / 2

    override fun onPadAction(action: PadAction) {
        if (touchIdle()) padDriving.value = true
        when (action) {
            // The R2+Y combo is the gamepad's takeoff guard (slide-to-confirm is touch only).
            PadAction.TakeoffOrLand -> padTakeoffOrLand()
            PadAction.StopSmartVideo -> onAction(FlightAction.StopSmartVideo)
            PadAction.Photo -> onAction(FlightAction.TakePhoto)
            PadAction.ToggleRecording -> onAction(FlightAction.ToggleRecording)
            PadAction.ToggleFastMode -> onAction(FlightAction.ToggleFastMode)
            is PadAction.Flip -> onAction(FlightAction.Flip(action.direction))
        }
    }

    /**
     * Land unless the drone is *certainly* on the ground: a landing in progress, motors on,
     * unknown state or a recent flying report all mean Land. Takeoff only from a stable
     * on-ground state, and never within 2 s of the previous combo.
     */
    private fun padTakeoffOrLand() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPadCombo < PAD_COMBO_COOLDOWN_MS) return
        lastPadCombo = now
        val controller = session.controller.value
        val flight = session.lastFlight
        val onGround = flight != null && !flight.flying && !flight.motorsOn && !knownFlying.value &&
            controller?.isLanding != true
        onAction(if (onGround) FlightAction.Takeoff else FlightAction.Land)
    }

    fun onAction(action: FlightAction) {
        when (action) {
            FlightAction.Takeoff -> confirmed { takeoff() }
            FlightAction.Land -> confirmed { land() }
            FlightAction.ThrowTakeoff -> confirmed { throwTakeoff() }
            FlightAction.PalmLand -> confirmed { palmLand() }
            is FlightAction.Flip -> modeCommand { flip(action.direction) }
            is FlightAction.SetBounce -> modeCommand { setBounce(action.enabled) }
            is FlightAction.StartSmartVideo -> modeCommand { startSmartVideo(action.mode) }
            FlightAction.StopSmartVideo -> command { stopSmartVideo() }
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
                padDriving.value = false
                touch.fill(0f)
                pad = PadSticks.Neutral
                mixer.reset()
                gamepad.release()
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

    /** Takeoff / landing: a vibration confirms the command left (touch or gamepad). */
    private fun confirmed(block: suspend TelloController.() -> Boolean) {
        val controller = session.controller.value ?: return
        viewModelScope.launch {
            if (withContext(RealtimeThreads.control) { controller.block() }) haptics.confirm()
        }
    }

    /** Network I/O never runs on the main thread. */
    private fun command(block: suspend TelloController.() -> Boolean) {
        val controller = session.controller.value ?: return
        viewModelScope.launch(RealtimeThreads.control) { controller.block() }
    }

    /** Manoeuvre with preconditions: a refusal is explained to the pilot. */
    private fun modeCommand(block: suspend TelloController.() -> ModeCheck) {
        val controller = session.controller.value ?: run {
            _messages.trySend(UserMessage.ModeRefused(ModeCheck.NOT_CONNECTED))
            return
        }
        viewModelScope.launch {
            val result = withContext(RealtimeThreads.control) { controller.block() }
            if (result != ModeCheck.OK) _messages.trySend(UserMessage.ModeRefused(result))
        }
    }

    override fun onCleared() {
        if (gamepad.isAttached(this)) gamepad.attach(null)
        session.controller.value?.releaseSticks()
        recorder.stop()
        transcodeQueue.setFlying(this, false)
    }
}

private const val PAD_COMBO_COOLDOWN_MS = 2_000L
private const val ALERT_REPEAT_MS = 30_000L
/** Pad deflection that counts as "the pilot flies with the pad" (hides the screen sticks). */
private const val PAD_TAKEOVER = 0.35f

/** Convenience used by the UI. */
val SmartVideoMode.label: Int
    get() = when (this) {
        SmartVideoMode.ROTATE_360 -> com.drone.tellopilot.R.string.mode_360
        SmartVideoMode.CIRCLE -> com.drone.tellopilot.R.string.mode_circle
        SmartVideoMode.UP_AND_AWAY -> com.drone.tellopilot.R.string.mode_up_and_away
    }

internal val FlipDirection.arrow: String
    get() = when (this) {
        FlipDirection.FORWARD -> "↑"
        FlipDirection.BACK -> "↓"
        FlipDirection.LEFT -> "←"
        FlipDirection.RIGHT -> "→"
        FlipDirection.FORWARD_LEFT -> "↖"
        FlipDirection.FORWARD_RIGHT -> "↗"
        FlipDirection.BACK_LEFT -> "↙"
        FlipDirection.BACK_RIGHT -> "↘"
    }
