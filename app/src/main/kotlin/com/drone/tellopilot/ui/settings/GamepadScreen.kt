package com.drone.tellopilot.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drone.tello.control.BindingProblem
import com.drone.tello.control.BindingResult
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadBinding
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadCommand
import com.drone.tello.control.PadSticks
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickMixer
import com.drone.tello.protocol.Payloads
import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.R
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.SettingsRepository
import com.drone.tellopilot.input.GamepadInput
import com.drone.tellopilot.input.GamepadStatus
import com.drone.tellopilot.ui.flight.arrow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** What the pad does right now, as seen by the configuration screen. */
data class PadLive(
    val sticks: PadSticks = PadSticks.Neutral,
    val held: Set<PadButton> = emptySet(),
    val lastAction: PadAction? = null,
    /** Incremented on every action, so that two identical actions in a row are visible. */
    val actionCount: Int = 0,
)

sealed interface PadMessage {
    data class Assigned(val command: PadCommand, val binding: PadBinding, val swappedWith: PadCommand?) : PadMessage
    data class Refused(val problem: BindingProblem, val other: PadCommand?) : PadMessage
    /** Several buttons held while capturing: ambiguous combo. */
    data object ReleaseOthers : PadMessage
}

/**
 * Gamepad configuration: live view of sticks / buttons / resulting command, and button
 * assignment. The flight screen is paused meanwhile, so nothing reaches the drone from here.
 */
@HiltViewModel
class GamepadViewModel @Inject constructor(
    private val gamepad: GamepadInput,
    private val repository: SettingsRepository,
    session: DroneSession,
) : ViewModel(), GamepadInput.Monitor {

    val flying: StateFlow<Boolean> = session.telemetry.map { it.flight?.flying == true }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val pilot: StateFlow<PilotSettings> = repository.settings.map { it.pilot }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PilotSettings())

    val status: StateFlow<GamepadStatus> = gamepad.status
    val padName: StateFlow<String?> = gamepad.padName

    private val _live = MutableStateFlow(PadLive())
    val live: StateFlow<PadLive> = _live.asStateFlow()

    private val _capture = MutableStateFlow<PadCommand?>(null)
    /** Command waiting for a button press, if any. */
    val capture: StateFlow<PadCommand?> = _capture.asStateFlow()

    private val _messages = Channel<PadMessage>(Channel.BUFFERED)
    val messages: Flow<PadMessage> = _messages.receiveAsFlow()

    /** An assignment is being written: further presses wait for it (no lost update). */
    private var saving = false

    /** Monitoring only while the screen is resumed (main thread). */
    fun setActive(active: Boolean) {
        if (active) {
            gamepad.attachMonitor(this)
        } else {
            _capture.value = null
            if (gamepad.isMonitoring(this)) gamepad.attachMonitor(null)
            _live.value = PadLive()
        }
    }

    fun startCapture(command: PadCommand) {
        _capture.value = command
    }

    fun cancelCapture() {
        _capture.value = null
    }

    fun resetDefaults() {
        _capture.value = null
        viewModelScope.launch { repository.updatePilot { it.copy(padBindings = GamepadBindings.Default) } }
    }

    override fun onPadSticks(sticks: PadSticks) {
        _live.update { it.copy(sticks = sticks) }
    }

    override fun onPadButton(button: PadButton, down: Boolean, action: PadAction?) {
        val before = _live.value.held
        _live.update { it.copy(held = if (down) it.held + button else it.held - button) }
        val command = _capture.value
        if (command != null) {
            // The press is consumed by the capture: its action under the old layout is not shown.
            if (down && button !in before) capture(command, button, before)
        } else if (action != null) {
            _live.update { it.copy(lastAction = action, actionCount = it.actionCount + 1) }
        }
    }

    override fun onPadReleased() {
        _live.update { it.copy(held = emptySet()) }
    }

    private fun capture(command: PadCommand, button: PadButton, alreadyHeld: Set<PadButton>) {
        if (saving) return
        val others = alreadyHeld.filter { it != button && it !in DPAD }
        if (others.size > 1) {
            _messages.trySend(PadMessage.ReleaseOthers)
            return
        }
        val modifier = others.firstOrNull()
        // Takeoff / land is a combo: the first button is only being held, wait for the second.
        if (command == PadCommand.TAKEOFF_LAND && modifier == null && button !in DPAD) return
        val binding = if (command == PadCommand.FLIP) PadBinding(button) else PadBinding(button, modifier)
        saving = true
        viewModelScope.launch {
            try {
                // Assigned on the stored layout, inside the write: never built on a stale copy.
                var result: BindingResult? = null
                repository.updatePilot { pilot ->
                    val r = pilot.padBindings.assign(command, binding)
                    result = r
                    if (r is BindingResult.Ok) pilot.copy(padBindings = r.bindings) else pilot
                }
                when (val r = result) {
                    is BindingResult.Ok -> {
                        if (_capture.value == command) _capture.value = null
                        _messages.trySend(PadMessage.Assigned(command, binding, r.swappedWith))
                    }
                    // Stay in capture mode: the pilot simply tries another button.
                    is BindingResult.Refused -> _messages.trySend(PadMessage.Refused(r.problem, r.other))
                    null -> Unit
                }
            } finally {
                saving = false
            }
        }
    }

    override fun onCleared() {
        if (gamepad.isMonitoring(this)) gamepad.attachMonitor(null)
    }

    private companion object {
        val DPAD = setOf(PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT)
    }
}

@Composable
fun GamepadRoute(onBack: () -> Unit, viewModel: GamepadViewModel = hiltViewModel()) {
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onBack() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.setActive(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.setActive(false) }

    val pilot by viewModel.pilot.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val capture by viewModel.capture.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val padName by viewModel.padName.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        // collectLatest: a new message replaces the one shown instead of queueing behind it.
        viewModel.messages.collectLatest { message ->
            val text = when (message) {
                PadMessage.ReleaseOthers -> resources.getString(R.string.pad_release_others)
                is PadMessage.Assigned -> {
                    val assigned = resources.getString(R.string.pad_assigned, resources.getString(message.command.label), message.binding.label())
                    message.swappedWith?.let { assigned + " " + resources.getString(R.string.pad_swapped, resources.getString(it.label)) } ?: assigned
                }
                is PadMessage.Refused -> when (message.problem) {
                    BindingProblem.DPAD_RESERVED -> resources.getString(R.string.pad_refused_dpad)
                    BindingProblem.COMBO_REQUIRED -> resources.getString(R.string.pad_refused_combo)
                    BindingProblem.FLIP_SINGLE_BUTTON -> resources.getString(R.string.pad_refused_flip)
                    BindingProblem.SAME_BUTTON_TWICE -> resources.getString(R.string.pad_refused_same)
                    BindingProblem.CONFLICT -> resources.getString(
                        R.string.pad_refused_conflict,
                        message.other?.let { resources.getString(it.label) } ?: "?",
                    )
                }
            }
            snackbar.showSnackbar(text)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                    Text(stringResource(R.string.pad_title), style = MaterialTheme.typography.headlineSmall)
                    Box(Modifier.weight(1f))
                    Text(
                        text = when (status) {
                            GamepadStatus.NONE -> stringResource(R.string.pad_status_none)
                            GamepadStatus.CENTER_STICKS -> stringResource(R.string.pad_status_center, padName ?: "")
                            GamepadStatus.READY -> stringResource(R.string.pad_status_ready, padName ?: "")
                        },
                        color = when (status) {
                            GamepadStatus.READY -> Color(0xFF66BB6A)
                            GamepadStatus.CENTER_STICKS -> Color(0xFFFFCA28)
                            GamepadStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LiveView(live, pilot)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BindingsView(
                            bindings = pilot.padBindings,
                            capture = capture,
                            onEdit = viewModel::startCapture,
                            onCancel = viewModel::cancelCapture,
                            onReset = viewModel::resetDefaults,
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveView(live: PadLive, pilot: PilotSettings) {
    SectionTitle(R.string.pad_section_live)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        StickView(R.string.pad_left_stick, live.sticks.leftX, live.sticks.leftY)
        StickView(R.string.pad_right_stick, live.sticks.rightX, live.sticks.rightY)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PadButton.entries.forEach { button ->
            val down = button in live.held
            Text(
                text = button.label(),
                color = if (down) Color.Black else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (down) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        if (down) Color(0xFF66BB6A) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    Text(
        text = live.lastAction?.let { stringResource(R.string.pad_last_action, actionText(it), live.actionCount) }
            ?: stringResource(R.string.pad_no_action),
        style = MaterialTheme.typography.bodyLarge,
    )

    SectionTitle(R.string.pad_section_transmission)
    // Same mixing / shaping as the flight screen (stick mode, dead zone, expo).
    val input = remember(live.sticks, pilot.stickMode, pilot.shaping) {
        val mixer = StickMixer(pilot.stickMode, pilot.shaping)
        mixer.setLeft(live.sticks.leftX, live.sticks.leftY, fastMode = false)
        mixer.setRight(live.sticks.rightX, live.sticks.rightY, fastMode = false)
    }
    ChannelBar(R.string.pad_channel_roll, input.roll)
    ChannelBar(R.string.pad_channel_pitch, input.pitch)
    ChannelBar(R.string.pad_channel_throttle, input.throttle)
    ChannelBar(R.string.pad_channel_yaw, input.yaw)
    Text(
        text = stringResource(R.string.pad_packet, packetHex(input)),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(R.string.pad_preview_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StickView(label: Int, x: Float, y: Float) {
    val ring = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(96.dp)) {
            val r = size.minDimension / 2f
            drawCircle(ring, radius = r - 2f, style = Stroke(width = 3f))
            drawLine(ring.copy(alpha = 0.4f), Offset(center.x - r, center.y), Offset(center.x + r, center.y))
            drawLine(ring.copy(alpha = 0.4f), Offset(center.x, center.y - r), Offset(center.x, center.y + r))
            drawCircle(dot, radius = r * 0.16f, center = Offset(center.x + x * (r - 8f), center.y - y * (r - 8f)))
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text("%+.2f  %+.2f".format(x, y), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    }
}

/** One channel of the stick packet: centred bar, percentage and the value sent (364..1684). */
@Composable
private fun ChannelBar(label: Int, value: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.weight(1f).height(12.dp)) {
            drawRect(track)
            val mid = size.width / 2f
            val end = mid + value.coerceIn(-1f, 1f) * mid
            drawRect(fill, topLeft = Offset(minOf(mid, end), 0f), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(end - mid), size.height))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2f)
        }
        Text(
            "%+4d %%  %4d".format((value * 100).roundToInt(), Payloads.axisValue(value)),
            Modifier.width(110.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BindingsView(
    bindings: GamepadBindings,
    capture: PadCommand?,
    onEdit: (PadCommand) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    SectionTitle(R.string.pad_section_bindings)
    bindings.entries.forEach { (command, binding) ->
        val capturing = capture == command
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(command.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (capturing) {
                Text(
                    stringResource(if (command == PadCommand.TAKEOFF_LAND) R.string.pad_capture_combo else R.string.pad_capture),
                    color = Color(0xFFFFCA28),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.pad_cancel)) }
            } else {
                Text(
                    text = binding.label() + if (command == PadCommand.FLIP) " + ✚" else "",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                TextButton(onClick = { onEdit(command) }) { Text(stringResource(R.string.pad_edit)) }
            }
        }
    }
    Text(stringResource(R.string.pad_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onReset) { Text(stringResource(R.string.pad_reset)) }
}

@Composable
private fun SectionTitle(text: Int) {
    HorizontalDivider()
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun packetHex(input: StickInput): String =
    Payloads.stick(input).take(6).joinToString(" ") { "%02x".format(it) }

private fun PadButton.label(): String = when (this) {
    PadButton.DPAD_UP -> "✚↑"
    PadButton.DPAD_DOWN -> "✚↓"
    PadButton.DPAD_LEFT -> "✚←"
    PadButton.DPAD_RIGHT -> "✚→"
    else -> name
}

private fun PadBinding.label(): String = modifier?.let { "${it.label()} + ${button.label()}" } ?: button.label()

private val PadCommand.label: Int
    get() = when (this) {
        PadCommand.TAKEOFF_LAND -> R.string.pad_cmd_takeoff_land
        PadCommand.FLIP -> R.string.pad_cmd_flip
        PadCommand.PHOTO -> R.string.pad_cmd_photo
        PadCommand.RECORD -> R.string.pad_cmd_record
        PadCommand.FAST_MODE -> R.string.pad_cmd_fast
        PadCommand.STOP_SMART_VIDEO -> R.string.pad_cmd_stop_smart
    }

@Composable
private fun actionText(action: PadAction): String = when (action) {
    PadAction.TakeoffOrLand -> stringResource(R.string.pad_cmd_takeoff_land)
    PadAction.Photo -> stringResource(R.string.pad_cmd_photo)
    PadAction.ToggleRecording -> stringResource(R.string.pad_cmd_record)
    PadAction.ToggleFastMode -> stringResource(R.string.pad_cmd_fast)
    PadAction.StopSmartVideo -> stringResource(R.string.pad_cmd_stop_smart)
    is PadAction.Flip -> stringResource(R.string.pad_action_flip, action.direction.arrow)
}
