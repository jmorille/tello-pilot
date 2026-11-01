package com.drone.tellopilot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
fun SettingsRoute(onBack: () -> Unit, onOpenImuCalibration: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onBack() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        settings?.let {
            SettingsScreen(it, onBack, viewModel::updatePilot, viewModel::updateDrone) {
                MaintenanceSection(onOpenImuCalibration)
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    updatePilot: ((PilotSettings) -> PilotSettings) -> Unit,
    updateDrone: ((DroneSettings) -> DroneSettings) -> Unit,
    maintenance: @Composable () -> Unit = {},
) {
    val pilot = settings.pilot
    val drone = settings.drone
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }

        Section(R.string.settings_section_control)
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
            label = { stringResource(R.string.settings_joystick_size, it.roundToInt()) },
            value = pilot.joystickSizeDp.toFloat(),
            range = PilotSettings.JOYSTICK_SIZE_RANGE.first.toFloat()..PilotSettings.JOYSTICK_SIZE_RANGE.last.toFloat(),
        ) { v -> updatePilot { it.copy(joystickSizeDp = v.roundToInt()) } }
        SwitchSetting(R.string.settings_confirm_takeoff, pilot.confirmTakeoff) { v -> updatePilot { it.copy(confirmTakeoff = v) } }

        Section(R.string.settings_section_display)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Units.entries.forEach { u ->
                FilterChip(
                    selected = pilot.units == u,
                    onClick = { updatePilot { it.copy(units = u) } },
                    label = { Text(stringResource(if (u == Units.METRIC) R.string.settings_metric else R.string.settings_imperial)) },
                )
            }
        }
        SwitchSetting(R.string.settings_auto_record, pilot.autoRecord) { v -> updatePilot { it.copy(autoRecord = v) } }

        Section(R.string.settings_section_drone)
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
        Section(R.string.settings_section_maintenance)
        maintenance()
        Spacer(Modifier.width(1.dp))
    }
}

private fun stickModeHelp(mode: StickMode) = when (mode) {
    StickMode.MODE_1 -> R.string.settings_mode1_help
    StickMode.MODE_2 -> R.string.settings_mode2_help
    StickMode.MODE_3 -> R.string.settings_mode3_help
    StickMode.MODE_4 -> R.string.settings_mode4_help
}

@Composable
private fun Section(title: Int) {
    HorizontalDivider()
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Label(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)

/** Slider that only persists on release (no DataStore write per drag event). */
@Composable
private fun SliderSetting(
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
private fun SwitchSetting(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
