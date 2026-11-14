package com.drone.tellopilot.ui.settings

import android.content.res.Resources
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.drone.tello.control.Mission
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadBinding
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadCommand
import com.drone.tello.control.PadFunction
import com.drone.tello.control.PadShortcut
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
    /** [forShortcut]: the refused edit was a shortcut's. */
    data class Refused(
        val problem: BindingProblem,
        val other: PadCommand?,
        val otherShortcut: PadShortcut? = null,
        val forShortcut: Boolean = false,
    ) : PadMessage
    data class ShortcutSaved(val shortcut: PadShortcut) : PadMessage
    /** Default fixed layout restored; [dropped] shortcuts no longer fitted and were removed. */
    data class CommandsReset(val dropped: Int) : PadMessage
    /** Several buttons held while capturing: ambiguous combo. */
    data object ReleaseOthers : PadMessage
}

/** What the next button press is for. */
sealed interface CaptureTarget {
    data class Command(val command: PadCommand) : CaptureTarget
    /** [original]: the shortcut being edited (found again by value when saving), null for a new one. */
    data class Shortcut(val original: PadShortcut?, val function: PadFunction) : CaptureTarget
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

    private val _capture = MutableStateFlow<CaptureTarget?>(null)
    /** Command or shortcut waiting for a button press, if any. */
    val capture: StateFlow<CaptureTarget?> = _capture.asStateFlow()

    private val _messages = Channel<PadMessage>(Channel.BUFFERED)
    val messages: Flow<PadMessage> = _messages.receiveAsFlow()

    /** An assignment is being written: further presses wait for it (no lost update). */
    private var saving = false

    /**
     * Shortcut capture: a button pressed with nothing held may be a single button (taken when it
     * is released alone) or the modifier of a combo (a second press comes first).
     */
    private var pendingSingle: PadButton? = null

    /** Monitoring only while the screen is resumed (main thread). */
    fun setActive(active: Boolean) {
        if (active) {
            gamepad.attachMonitor(this)
        } else {
            setCapture(null)
            if (gamepad.isMonitoring(this)) gamepad.attachMonitor(null)
            _live.value = PadLive()
        }
    }

    private fun setCapture(target: CaptureTarget?) {
        pendingSingle = null
        _capture.value = target
    }

    fun startCapture(command: PadCommand) = setCapture(CaptureTarget.Command(command))

    /** New buttons for [original] (null: a new shortcut for [function]). */
    fun startShortcutCapture(original: PadShortcut?, function: PadFunction) = setCapture(CaptureTarget.Shortcut(original, function))

    fun cancelCapture() = setCapture(null)

    /** Default fixed layout; the shortcuts that still fit are kept. */
    fun resetDefaults() {
        setCapture(null)
        viewModelScope.launch {
            var dropped = 0
            repository.updatePilot {
                val reset = it.padBindings.withDefaultCommands()
                dropped = it.padBindings.shortcuts.size - reset.shortcuts.size
                it.copy(padBindings = reset)
            }
            _messages.trySend(PadMessage.CommandsReset(dropped))
        }
    }

    // Shortcuts are found by value inside the write: a stale row index (double tap, concurrent
    // edit) never touches another shortcut (two shortcuts never share their buttons).

    fun setShortcutFunction(original: PadShortcut, function: PadFunction) {
        setCapture(null)
        val updated = original.copy(function = function)
        write(forShortcut = true, change = { b ->
            val index = b.shortcuts.indexOf(original).takeIf { it >= 0 } ?: return@write null
            b.replaceShortcut(index, updated)
        }) { PadMessage.ShortcutSaved(updated) }
    }

    fun removeShortcut(shortcut: PadShortcut) {
        setCapture(null)
        viewModelScope.launch {
            repository.updatePilot { pilot ->
                val index = pilot.padBindings.shortcuts.indexOf(shortcut)
                if (index < 0) pilot else pilot.copy(padBindings = pilot.padBindings.removeShortcut(index))
            }
        }
    }

    fun clearShortcuts() {
        setCapture(null)
        viewModelScope.launch { repository.updatePilot { it.copy(padBindings = it.padBindings.withoutShortcuts()) } }
    }

    override fun onPadSticks(sticks: PadSticks) {
        _live.update { it.copy(sticks = sticks) }
    }

    override fun onPadButton(button: PadButton, down: Boolean, action: PadAction?) {
        val before = _live.value.held
        _live.update { it.copy(held = if (down) it.held + button else it.held - button) }
        val target = _capture.value
        if (target != null) {
            // The press is consumed by the capture: its action under the old layout is not shown.
            if (down && button !in before) {
                capture(target, button, before)
            } else if (!down && button == pendingSingle) {
                pendingSingle = null
                // Not dropped while a previous write runs: the change is applied inside the store transaction.
                if (target is CaptureTarget.Shortcut) saveShortcut(target, PadBinding(button), evenWhileSaving = true)
            }
        } else if (action != null) {
            _live.update { it.copy(lastAction = action, actionCount = it.actionCount + 1) }
        }
    }

    override fun onPadReleased() {
        pendingSingle = null
        _live.update { it.copy(held = emptySet()) }
    }

    private fun capture(target: CaptureTarget, button: PadButton, alreadyHeld: Set<PadButton>) {
        if (saving) return
        val others = alreadyHeld.filter { it != button && it !in DPAD }
        if (others.size > 1) {
            pendingSingle = null
            _messages.trySend(PadMessage.ReleaseOthers)
            return
        }
        val modifier = others.firstOrNull()
        when (target) {
            is CaptureTarget.Command -> {
                val command = target.command
                // Takeoff / land is a combo: the first button is only being held, wait for the second.
                if (command == PadCommand.TAKEOFF_LAND && modifier == null && button !in DPAD) return
                val binding = if (command == PadCommand.FLIP) PadBinding(button) else PadBinding(button, modifier)
                write(forShortcut = false, change = { it.assign(command, binding) }) { r ->
                    PadMessage.Assigned(command, binding, r.swappedWith)
                }
            }
            is CaptureTarget.Shortcut -> when {
                modifier != null -> {
                    pendingSingle = null
                    saveShortcut(target, PadBinding(button, modifier))
                }
                // A button alone: decided when it is released (or a second button makes a combo).
                // A moving function is a combo anyway, unless the press is a refused D-pad.
                button in DPAD -> saveShortcut(target, PadBinding(button))
                target.function.moves -> Unit
                else -> pendingSingle = button
            }
        }
    }

    private fun saveShortcut(target: CaptureTarget.Shortcut, binding: PadBinding, evenWhileSaving: Boolean = false) {
        if (saving && !evenWhileSaving) return
        val shortcut = PadShortcut(binding, target.function)
        write(forShortcut = true, change = { b ->
            val original = target.original
            if (original == null) {
                b.addShortcut(shortcut)
            } else {
                // Deleted meanwhile: nothing to edit.
                val index = b.shortcuts.indexOf(original).takeIf { it >= 0 } ?: return@write null
                b.replaceShortcut(index, shortcut)
            }
        }) { PadMessage.ShortcutSaved(shortcut) }
    }

    /**
     * Applies [change] on the stored layout, inside the write (never built on a stale copy).
     * Done: capture over, [ok] told. Refused: the capture stays, the pilot simply tries again.
     */
    private fun write(
        forShortcut: Boolean,
        change: (GamepadBindings) -> BindingResult?,
        ok: (BindingResult.Ok) -> PadMessage,
    ) {
        saving = true
        val target = _capture.value
        viewModelScope.launch {
            try {
                var result: BindingResult? = null
                repository.updatePilot { pilot ->
                    val r = change(pilot.padBindings)
                    result = r
                    if (r is BindingResult.Ok) pilot.copy(padBindings = r.bindings) else pilot
                }
                when (val r = result) {
                    is BindingResult.Ok -> {
                        if (target != null && _capture.value == target) setCapture(null)
                        _messages.trySend(ok(r))
                    }
                    is BindingResult.Refused -> _messages.trySend(PadMessage.Refused(r.problem, r.other, r.otherShortcut, forShortcut))
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
        viewModel.messages.collectLatest { message -> snackbar.showSnackbar(messageText(message, resources)) }
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
                            capture = (capture as? CaptureTarget.Command)?.command,
                            onEdit = viewModel::startCapture,
                            onCancel = viewModel::cancelCapture,
                            onReset = viewModel::resetDefaults,
                        )
                        ShortcutsView(
                            bindings = pilot.padBindings,
                            missions = availableMissions(pilot.customMissions),
                            capture = capture as? CaptureTarget.Shortcut,
                            onCapture = viewModel::startShortcutCapture,
                            onFunction = viewModel::setShortcutFunction,
                            onRemove = viewModel::removeShortcut,
                            onClear = viewModel::clearShortcuts,
                            onCancel = viewModel::cancelCapture,
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        }
    }
}

private fun messageText(message: PadMessage, res: Resources): String = when (message) {
    PadMessage.ReleaseOthers -> res.getString(R.string.pad_release_others)
    is PadMessage.Assigned -> {
        val assigned = res.getString(R.string.pad_assigned, res.getString(message.command.label), message.binding.label())
        message.swappedWith?.let { assigned + " " + res.getString(R.string.pad_swapped, res.getString(it.label)) } ?: assigned
    }
    is PadMessage.ShortcutSaved -> res.getString(R.string.pad_shortcut_saved, message.shortcut.text(res))
    is PadMessage.CommandsReset ->
        if (message.dropped == 0) res.getString(R.string.pad_reset_done)
        else res.getQuantityString(R.plurals.pad_reset_dropped, message.dropped, message.dropped)
    is PadMessage.Refused -> when (message.problem) {
        BindingProblem.DPAD_RESERVED -> res.getString(R.string.pad_refused_dpad)
        BindingProblem.COMBO_REQUIRED -> res.getString(R.string.pad_refused_combo)
        BindingProblem.FLIP_SINGLE_BUTTON -> res.getString(R.string.pad_refused_flip)
        BindingProblem.SAME_BUTTON_TWICE -> res.getString(R.string.pad_refused_same)
        BindingProblem.SHORTCUT_COMBO_REQUIRED -> res.getString(R.string.pad_refused_shortcut_combo)
        BindingProblem.STOP_RESERVED -> res.getString(R.string.pad_refused_stop, res.getString(PadCommand.STOP_SMART_VIDEO.label))
        BindingProblem.TOO_MANY -> res.getString(R.string.pad_refused_too_many, GamepadBindings.MAX_SHORTCUTS)
        BindingProblem.CONFLICT -> when {
            message.otherShortcut != null -> res.getString(R.string.pad_refused_conflict_shortcut, message.otherShortcut.text(res))
            message.forShortcut -> res.getString(
                R.string.pad_refused_conflict_command,
                message.other?.let { res.getString(it.label) } ?: "?",
            )
            else -> res.getString(R.string.pad_refused_conflict, message.other?.let { res.getString(it.label) } ?: "?")
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
    val resources = LocalResources.current
    Text(
        text = live.lastAction?.let { stringResource(R.string.pad_last_action, it.text(resources), live.actionCount) }
            ?: stringResource(R.string.pad_no_action),
        style = MaterialTheme.typography.bodyLarge,
    )

    SectionTitle(R.string.pad_section_transmission)
    // Same mixing as the flight screen (stick mode, dead zone, axis response of the normal mode).
    val input = remember(live.sticks, pilot.stickMode, pilot.stickTuning) {
        val mixer = StickMixer(pilot.stickMode, pilot.stickTuning)
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
                BindingChip(commandBindingLabel(command, binding))
                TextButton(onClick = { onEdit(command) }) { Text(stringResource(R.string.pad_edit)) }
            }
        }
    }
    Text(stringResource(R.string.pad_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onReset) { Text(stringResource(R.string.pad_reset)) }
}

@Composable
private fun BindingChip(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Pilot shortcuts: add, change the function or the buttons, delete. */
@Composable
private fun ShortcutsView(
    bindings: GamepadBindings,
    missions: List<Mission>,
    capture: CaptureTarget.Shortcut?,
    onCapture: (PadShortcut?, PadFunction) -> Unit,
    onFunction: (PadShortcut, PadFunction) -> Unit,
    onRemove: (PadShortcut) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    val resources = LocalResources.current
    // Function picker: for a new shortcut or an existing one.
    var picking by remember { mutableStateOf<PickFor?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    SectionTitle(R.string.pad_section_shortcuts)
    if (bindings.shortcuts.isEmpty() && capture == null) {
        Text(stringResource(R.string.pad_shortcuts_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    bindings.shortcuts.forEach { shortcut ->
        val capturing = capture != null && capture.original == shortcut
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(shortcut.function.text(resources), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (capturing) {
                    CaptureHint(shortcut.function)
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.pad_cancel)) }
                } else {
                    BindingChip(shortcut.binding.label())
                }
            }
            if (shortcut.missionMissing(missions)) {
                Text(
                    stringResource(R.string.pad_shortcut_invalid_mission),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!capturing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onCapture(shortcut, shortcut.function) }) { Text(stringResource(R.string.pad_shortcut_buttons)) }
                    TextButton(onClick = { picking = PickFor(shortcut) }) { Text(stringResource(R.string.pad_shortcut_function)) }
                    TextButton(onClick = { onRemove(shortcut) }) { Text(stringResource(R.string.pad_shortcut_delete)) }
                }
            }
        }
    }
    if (capture != null && capture.original == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pad_shortcut_new, capture.function.text(resources)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.pad_cancel)) }
        }
        CaptureHint(capture.function)
    }
    Text(stringResource(R.string.pad_shortcuts_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { picking = PickFor(null) },
            enabled = bindings.shortcuts.size < GamepadBindings.MAX_SHORTCUTS,
        ) { Text(stringResource(R.string.pad_shortcut_add)) }
        if (bindings.shortcuts.isNotEmpty()) {
            OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.pad_shortcuts_clear)) }
        }
    }

    picking?.let { pick ->
        FunctionPicker(
            missions = missions,
            onPick = { function ->
                picking = null
                if (pick.shortcut == null) onCapture(null, function) else onFunction(pick.shortcut, function)
            },
            onDismiss = { picking = null },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.pad_shortcuts_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.pad_shortcut_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.pad_cancel)) } },
        )
    }
}

/** Function picker opened for [shortcut] (null: a new shortcut). */
private data class PickFor(val shortcut: PadShortcut?)

@Composable
private fun CaptureHint(function: PadFunction) {
    Text(
        stringResource(if (function.moves) R.string.pad_capture_shortcut_combo else R.string.pad_capture_shortcut),
        color = Color(0xFFFFCA28),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Every function a shortcut can trigger, missions by name. */
@Composable
private fun FunctionPicker(missions: List<Mission>, onPick: (PadFunction) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val groups = listOf(
        R.string.pad_group_smart to PadFunction.Catalog.filterIsInstance<PadFunction.SmartVideo>(),
        R.string.pad_group_flips to PadFunction.Catalog.filterIsInstance<PadFunction.Flip>(),
        R.string.pad_group_maneuvers to listOf(PadFunction.Bounce, PadFunction.PalmLand),
        R.string.pad_group_tracking to PadFunction.Catalog.filterIsInstance<PadFunction.Tracking>(),
        R.string.pad_group_missions to missions.map { PadFunction.RunMission(it.name) }.distinct(),
        R.string.pad_group_other to PadFunction.Catalog.filter { !it.moves },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pad_pick_function)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { (title, functions) ->
                    if (functions.isEmpty()) return@forEach
                    Text(
                        stringResource(title),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    functions.forEach { function ->
                        TextButton(onClick = { onPick(function) }, modifier = Modifier.fillMaxWidth()) {
                            Text(function.text(resources), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pad_cancel)) } },
    )
}

@Composable
private fun SectionTitle(text: Int) {
    HorizontalDivider()
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun packetHex(input: StickInput): String =
    Payloads.stick(input).take(6).joinToString(" ") { "%02x".format(it) }
