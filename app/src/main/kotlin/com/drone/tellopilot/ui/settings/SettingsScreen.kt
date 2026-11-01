package com.drone.tellopilot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.drone.tello.control.DroneSettings
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickMode
import com.drone.tello.control.Units
import com.drone.tello.control.VideoBitrate
import com.drone.tellopilot.R
import com.drone.tellopilot.data.AppSettings
import com.drone.tellopilot.data.DroneSession
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.drone.tellopilot.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    session: DroneSession,
) : ViewModel() {
    /** The drone took off (e.g. an armed throw-takeoff): settings must give way to the flight screen. */
    val flying: StateFlow<Boolean> = session.telemetry.map { it.flight?.flying == true }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updatePilot(transform: (PilotSettings) -> PilotSettings) {
        viewModelScope.launch { repository.updatePilot(transform) }
    }

    fun updateDrone(transform: (DroneSettings) -> DroneSettings) {
        viewModelScope.launch { repository.updateDrone(transform) }
    }
}

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenImuCalibration: () -> Unit,
    onOpenGamepad: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onBack() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        settings?.let {
            SettingsScreen(
                it, onBack, viewModel::updatePilot, viewModel::updateDrone, onOpenGamepad,
                videos = { pilot -> VideoSection(pilot, viewModel::updatePilot) },
            ) {
                MaintenanceSection(onOpenImuCalibration)
            }
        }
    }
}

/** Settings categories, one pane each. */
enum class SettingsCategory(val title: Int, val summary: Int) {
    CONTROL(R.string.settings_section_control, R.string.settings_summary_control),
    GAMEPAD(R.string.settings_section_gamepad, R.string.settings_summary_gamepad),
    DISPLAY(R.string.settings_section_display, R.string.settings_summary_display),
    VIDEOS(R.string.settings_section_videos, R.string.settings_summary_videos),
    AUTOPILOT(R.string.settings_section_autopilot, R.string.settings_summary_autopilot),
    DRONE(R.string.settings_section_drone, R.string.settings_summary_drone),
    MAINTENANCE(R.string.settings_section_maintenance, R.string.settings_summary_maintenance),
}

/**
 * Landscape two-pane settings: the categories on the left, the selected one on the right
 * (each pane scrolls on its own, nothing is ever more than one tap away).
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    updatePilot: ((PilotSettings) -> PilotSettings) -> Unit,
    updateDrone: ((DroneSettings) -> DroneSettings) -> Unit,
    onOpenGamepad: () -> Unit = {},
    videos: @Composable (PilotSettings) -> Unit = {},
    maintenance: @Composable () -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf(SettingsCategory.CONTROL) }
    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().selectableGroup().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            }
            SettingsCategory.entries.forEach { c -> CategoryItem(c, selected = c == category) { category = c } }
        }
        VerticalDivider()
        // key: each category gets its own scroll position, starting at the top.
        key(category) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(category.title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                when (category) {
                    SettingsCategory.CONTROL -> ControlSettings(settings.pilot, updatePilot)
                    SettingsCategory.GAMEPAD -> GamepadSettings(settings.pilot, updatePilot, onOpenGamepad)
                    SettingsCategory.DISPLAY -> DisplaySettings(settings.pilot, updatePilot)
                    SettingsCategory.VIDEOS -> {
                        SwitchSetting(R.string.settings_auto_record, settings.pilot.autoRecord) { v ->
                            updatePilot { it.copy(autoRecord = v) }
                        }
                        videos(settings.pilot)
                    }
                    SettingsCategory.AUTOPILOT -> AutopilotSettings(settings.pilot, updatePilot)
                    SettingsCategory.DRONE -> DroneSettingsPane(settings.drone, updateDrone, settings.pilot, updatePilot)
                    SettingsCategory.MAINTENANCE -> maintenance()
                }
            }
        }
    }
}

@Composable
private fun ControlSettings(pilot: PilotSettings, updatePilot: ((PilotSettings) -> PilotSettings) -> Unit) {
    Label(R.string.settings_stick_mode)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StickMode.entries.forEach { mode ->
            FilterChip(
                selected = pilot.stickMode == mode,
                onClick = { updatePilot { it.copy(stickMode = mode) } },
                label = { Text(stringResource(R.string.settings_mode_n, mode.ordinal + 1)) },
            )
        }
    }
    Text(stringResource(stickModeHelp(pilot.stickMode)), style = MaterialTheme.typography.bodySmall)
    SliderSetting(
        label = { stringResource(R.string.settings_dead_zone, (it * 100).roundToInt()) },
        value = pilot.deadZone, range = PilotSettings.DEAD_ZONE_RANGE,
    ) { v -> updatePilot { it.copy(deadZone = v) } }
    SliderSetting(
        label = { stringResource(R.string.settings_expo, (it * 100).roundToInt()) },
        value = pilot.expo, range = PilotSettings.EXPO_RANGE,
    ) { v -> updatePilot { it.copy(expo = v) } }
    SliderSetting(
        label = { stringResource(R.string.settings_tripod_scale, (it * 100).roundToInt()) },
        value = pilot.tripodScale, range = PilotSettings.TRIPOD_RANGE,
    ) { v -> updatePilot { it.copy(tripodScale = v) } }
    Text(stringResource(R.string.settings_tripod_help), style = MaterialTheme.typography.bodySmall)
    SliderSetting(
        label = { stringResource(R.string.settings_joystick_size, it.roundToInt()) },
        value = pilot.joystickSizeDp.toFloat(),
        range = PilotSettings.JOYSTICK_SIZE_RANGE.first.toFloat()..PilotSettings.JOYSTICK_SIZE_RANGE.last.toFloat(),
    ) { v -> updatePilot { it.copy(joystickSizeDp = v.roundToInt()) } }
    SwitchSetting(R.string.settings_confirm_takeoff, pilot.confirmTakeoff) { v -> updatePilot { it.copy(confirmTakeoff = v) } }
    SwitchSetting(R.string.settings_takeoff_height_on, pilot.takeoffHeight > 0f) { v ->
        updatePilot { it.copy(takeoffHeight = if (v) PilotSettings.TAKEOFF_HEIGHT_DEFAULT else 0f) }
    }
    if (pilot.takeoffHeight > 0f) {
        SliderSetting(
            label = { stringResource(R.string.settings_takeoff_height, it) },
            value = pilot.takeoffHeight, range = PilotSettings.TAKEOFF_HEIGHT_RANGE, steps = 24,
        ) { v -> updatePilot { it.copy(takeoffHeight = (v * 10).roundToInt() / 10f) } }
    }
    Text(stringResource(R.string.settings_takeoff_height_help), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun GamepadSettings(
    pilot: PilotSettings,
    updatePilot: ((PilotSettings) -> PilotSettings) -> Unit,
    onOpenGamepad: () -> Unit,
) {
    SwitchSetting(R.string.settings_hide_sticks, pilot.hideSticksWithPad) { v -> updatePilot { it.copy(hideSticksWithPad = v) } }
    Text(stringResource(R.string.settings_hide_sticks_help), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onOpenGamepad) { Text(stringResource(R.string.settings_gamepad)) }
}

@Composable
private fun DisplaySettings(pilot: PilotSettings, updatePilot: ((PilotSettings) -> PilotSettings) -> Unit) {
    Label(R.string.settings_units)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Units.entries.forEach { u ->
            FilterChip(
                selected = pilot.units == u,
                onClick = { updatePilot { it.copy(units = u) } },
                label = { Text(stringResource(if (u == Units.METRIC) R.string.settings_metric else R.string.settings_imperial)) },
            )
        }
    }
    Label(R.string.settings_hud_items)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.drone.tello.control.HudItem.entries.forEach { item ->
            FilterChip(
                selected = pilot.shows(item),
                onClick = {
                    updatePilot {
                        it.copy(hiddenHud = if (item in it.hiddenHud) it.hiddenHud - item else it.hiddenHud + item)
                    }
                },
                label = { Text(stringResource(hudItemLabel(item))) },
            )
        }
    }
    Text(stringResource(R.string.settings_hud_items_help), style = MaterialTheme.typography.bodySmall)
    Label(R.string.settings_video_fit)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.drone.tello.control.VideoFit.entries.forEach { f ->
            FilterChip(
                selected = pilot.videoFit == f,
                onClick = { updatePilot { it.copy(videoFit = f) } },
                label = {
                    Text(stringResource(when (f) {
                        com.drone.tello.control.VideoFit.FILL -> R.string.settings_video_fit_fill
                        com.drone.tello.control.VideoFit.WIDE -> R.string.settings_video_fit_wide
                        com.drone.tello.control.VideoFit.FIT -> R.string.settings_video_fit_fit
                        com.drone.tello.control.VideoFit.STRETCH_WIDE -> R.string.settings_video_fit_stretch_wide
                        com.drone.tello.control.VideoFit.STRETCH_FULL -> R.string.settings_video_fit_stretch_full
                    }))
                },
            )
        }
    }
    Text(stringResource(R.string.settings_video_fit_help), style = MaterialTheme.typography.bodySmall)
    SwitchSetting(R.string.settings_video_sharpen, pilot.videoSharpen) { v -> updatePilot { it.copy(videoSharpen = v) } }
    Text(stringResource(R.string.settings_video_sharpen_help), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DroneSettingsPane(
    drone: DroneSettings,
    updateDrone: ((DroneSettings) -> DroneSettings) -> Unit,
    pilot: PilotSettings,
    updatePilot: ((PilotSettings) -> PilotSettings) -> Unit,
) {
    Text(stringResource(R.string.settings_drone_hint), style = MaterialTheme.typography.bodySmall)
    SliderSetting(
        label = { stringResource(R.string.settings_max_altitude, it.roundToInt()) },
        value = drone.maxAltitudeMeters.toFloat(),
        range = DroneSettings.MAX_ALTITUDE_RANGE.first.toFloat()..DroneSettings.MAX_ALTITUDE_RANGE.last.toFloat(),
        steps = DroneSettings.MAX_ALTITUDE_RANGE.last - DroneSettings.MAX_ALTITUDE_RANGE.first - 1,
    ) { v -> updateDrone { it.copy(maxAltitudeMeters = v.roundToInt()) } }
    SliderSetting(
        label = { stringResource(R.string.settings_low_battery, it.roundToInt()) },
        value = drone.lowBatteryPercent.toFloat(),
        range = DroneSettings.LOW_BATTERY_RANGE.first.toFloat()..DroneSettings.LOW_BATTERY_RANGE.last.toFloat(),
        steps = DroneSettings.LOW_BATTERY_RANGE.last - DroneSettings.LOW_BATTERY_RANGE.first - 1,
    ) { v -> updateDrone { it.copy(lowBatteryPercent = v.roundToInt()) } }
    SwitchSetting(R.string.settings_battery_voice, pilot.batteryVoiceAlert) { v -> updatePilot { it.copy(batteryVoiceAlert = v) } }
    Text(stringResource(R.string.settings_battery_voice_help), style = MaterialTheme.typography.bodySmall)
    SliderSetting(
        label = { stringResource(R.string.settings_exposure, it.roundToInt() / 3f) },
        value = drone.exposureSteps.toFloat(),
        range = DroneSettings.EXPOSURE_RANGE.first.toFloat()..DroneSettings.EXPOSURE_RANGE.last.toFloat(),
        steps = DroneSettings.EXPOSURE_RANGE.last - DroneSettings.EXPOSURE_RANGE.first - 1,
    ) { v -> updateDrone { it.copy(exposureSteps = v.roundToInt()) } }
    Label(R.string.settings_bitrate)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoBitrate.entries.forEach { b ->
            FilterChip(
                selected = drone.bitrate == b,
                onClick = { updateDrone { it.copy(bitrate = b) } },
                label = { Text(b.label) },
            )
        }
    }
}

private fun stickModeHelp(mode: StickMode) = when (mode) {
    StickMode.MODE_1 -> R.string.settings_mode1_help
    StickMode.MODE_2 -> R.string.settings_mode2_help
    StickMode.MODE_3 -> R.string.settings_mode3_help
    StickMode.MODE_4 -> R.string.settings_mode4_help
}

/** Compact menu entry (two lines) so that every category fits on a landscape phone. */
@Composable
private fun CategoryItem(category: SettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(category.title), style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )
        Text(
            stringResource(category.summary), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}


@Composable
private fun Label(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)

/** Slider that only persists on release (no DataStore write per drag event). */
@Composable
internal fun SliderSetting(
    label: @Composable (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    var dragging by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Text(label(dragging), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onChange(dragging) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
internal fun SwitchSetting(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun hudItemLabel(item: com.drone.tello.control.HudItem): Int = when (item) {
    com.drone.tello.control.HudItem.BATTERY -> R.string.hud_item_battery
    com.drone.tello.control.HudItem.HEIGHT -> R.string.hud_item_height
    com.drone.tello.control.HudItem.SPEED -> R.string.hud_item_speed
    com.drone.tello.control.HudItem.VARIO -> R.string.hud_item_vario
    com.drone.tello.control.HudItem.FLIGHT_TIME -> R.string.hud_item_flight_time
    com.drone.tello.control.HudItem.WIFI -> R.string.hud_item_wifi
    com.drone.tello.control.HudItem.GAMEPAD -> R.string.hud_item_gamepad
    com.drone.tello.control.HudItem.RECORDING -> R.string.hud_item_recording
    com.drone.tello.control.HudItem.HORIZON -> R.string.hud_item_horizon
    com.drone.tello.control.HudItem.MAP -> R.string.hud_item_map
    com.drone.tello.control.HudItem.TEMPERATURE -> R.string.hud_item_temperature
    com.drone.tello.control.HudItem.INFO_ALERTS -> R.string.hud_item_info_alerts
}
