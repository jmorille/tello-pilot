package com.drone.tellopilot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.drone.tello.control.DroneEvent
import com.drone.tello.control.FlightModeRules
import com.drone.tello.control.ImuCalibrationState
import com.drone.tello.control.LinkState
import com.drone.tello.control.ModeCheck
import com.drone.tello.control.TelloController
import com.drone.tello.control.WifiChangeState
import com.drone.tello.protocol.ImuFace
import com.drone.tellopilot.R
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.RealtimeThreads
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MaintenanceState(
    val connected: Boolean = false,
    val flying: Boolean = false,
    /** Calibrations and Wi-Fi changes need the drone on the ground, motors stopped. */
    val onGround: Boolean = false,
    val ssid: String? = null,
    val firmware: String? = null,
    val wifiChange: WifiChangeState = WifiChangeState.Idle,
    val imu: ImuCalibrationState = ImuCalibrationState.Idle,
)

@HiltViewModel
class MaintenanceViewModel @Inject constructor(private val session: DroneSession) : ViewModel() {

    val state: StateFlow<MaintenanceState> = combine(
        session.telemetry,
        session.link,
        session.wifiChange,
        session.imuCalibration,
    ) { telemetry, link, wifi, imu ->
        val connected = link == LinkState.CONNECTED && telemetry.flight != null
        MaintenanceState(
            connected = connected,
            flying = telemetry.flight?.flying == true,
            onGround = connected && FlightModeRules.checkCalibration(telemetry.flight) == ModeCheck.OK,
            ssid = telemetry.ssid,
            firmware = telemetry.firmware,
            wifiChange = wifi,
            imu = imu,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceState())

    /**
     * Last centre-of-gravity calibration result, kept in the ViewModel: it must survive a switch
     * to another settings pane while the drone answers.
     */
    private val _centerOfGravityResult = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val centerOfGravityResult: StateFlow<Boolean?> = _centerOfGravityResult

    init {
        viewModelScope.launch {
            session.events.filterIsInstance<DroneEvent.CenterOfGravityCalibrated>()
                .collect { _centerOfGravityResult.value = it.success }
        }
    }

    fun refreshWifiName() = run { queryWifiName() }
    fun changeWifi(ssid: String, password: String) = run { changeWifi(ssid, password) }
    fun resetWifiChange() = run { resetWifiChange() }
    fun calibrateCenterOfGravity() {
        _centerOfGravityResult.value = null
        run { calibrateCenterOfGravity() }
    }
    fun startImuCalibration() = run { startImuCalibration() }
    fun resetImuCalibration() = run { resetImuCalibration() }

    /** Reset then start, in this order, on the control thread. */
    fun retryImuCalibration() = run {
        resetImuCalibration()
        startImuCalibration()
    }

    /** Controller state is confined to the control thread: every call goes through it. */
    private fun run(block: suspend TelloController.() -> Any) {
        val controller = session.controller.value ?: return
        viewModelScope.launch(RealtimeThreads.control) { controller.block() }
    }
}

/** Drone maintenance block of the settings screen: info, Wi-Fi, calibrations. */
@Composable
fun MaintenanceSection(onOpenImuCalibration: () -> Unit, viewModel: MaintenanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cgResult by viewModel.centerOfGravityResult.collectAsStateWithLifecycle()
    var editWifi by remember { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (state.connected) viewModel.refreshWifiName() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.connected) {
            Text(stringResource(R.string.maintenance_not_connected), style = MaterialTheme.typography.bodySmall)
        } else if (!state.onGround) {
            Text(stringResource(R.string.maintenance_on_ground), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.maintenance_firmware, state.firmware ?: "--"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.maintenance_ssid, state.ssid ?: "--"), Modifier.weight(1f))
            FilledTonalButton(onClick = { editWifi = true }, enabled = state.onGround) {
                Text(stringResource(R.string.maintenance_wifi_edit))
            }
        }
        when (val wifi = state.wifiChange) {
            WifiChangeState.Sending -> Text(stringResource(R.string.maintenance_wifi_sending))
            is WifiChangeState.Applied -> Text(
                stringResource(R.string.maintenance_wifi_applied, wifi.ssid),
                color = MaterialTheme.colorScheme.primary,
            )
            WifiChangeState.Failed -> Text(stringResource(R.string.maintenance_wifi_failed), color = MaterialTheme.colorScheme.error)
            WifiChangeState.Idle -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = viewModel::calibrateCenterOfGravity, enabled = state.onGround) {
                Text(stringResource(R.string.maintenance_cg))
            }
            FilledTonalButton(onClick = onOpenImuCalibration, enabled = state.onGround) {
                Text(stringResource(R.string.maintenance_imu))
            }
        }
        cgResult?.let {
            Text(stringResource(if (it) R.string.maintenance_cg_ok else R.string.maintenance_cg_failed))
        }
    }

    if (editWifi) {
        WifiDialog(
            current = state.ssid.orEmpty(),
            onDismiss = { editWifi = false },
            onConfirm = { ssid, password ->
                editWifi = false
                viewModel.resetWifiChange()
                viewModel.changeWifi(ssid, password)
            },
        )
    }
}

@Composable
private fun WifiDialog(current: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var ssid by remember { mutableStateOf(current) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val ssidOk = ssid.length in 1..32 && ssid.all { it.code in 0x20..0x7E }
    val passwordOk = (password.isEmpty() || password.length in 8..63) && password.all { it.code in 0x20..0x7E }
    // A typo would lock the pilot out until a hardware Wi-Fi reset: type it twice.
    val confirmOk = confirm == password
    val mask = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maintenance_wifi_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it }, singleLine = true,
                    label = { Text(stringResource(R.string.maintenance_wifi_ssid)) }, isError = !ssidOk,
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(stringResource(R.string.maintenance_wifi_password)) }, isError = !passwordOk,
                    visualTransformation = mask,
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it }, singleLine = true,
                    label = { Text(stringResource(R.string.maintenance_wifi_confirm)) }, isError = !confirmOk,
                    visualTransformation = mask,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = show, onCheckedChange = { show = it })
                    Text(stringResource(R.string.maintenance_wifi_show))
                }
                Text(
                    stringResource(if (password.isEmpty()) R.string.maintenance_wifi_open_warning else R.string.maintenance_wifi_restart_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(ssid, password) }, enabled = ssidOk && passwordOk && confirmOk) {
                Text(stringResource(R.string.maintenance_wifi_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Guided 6-face IMU calibration (official `IMUCalibrationActivity`). */
@Composable
fun ImuCalibrationRoute(onBack: () -> Unit, viewModel: MaintenanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Never stay on a maintenance screen while the drone flies: back to the flight screen.
    LaunchedEffect(state.flying) { if (state.flying) onBack() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.resetImuCalibration(); onBack() }) { Text(stringResource(R.string.settings_back)) }
                Text(stringResource(R.string.imu_title), style = MaterialTheme.typography.headlineSmall)
            }
            val imu = state.imu
            val done = (imu as? ImuCalibrationState.Running)?.doneFaces
                ?: if (imu == ImuCalibrationState.Succeeded) ImuFace.entries.toSet() else emptySet()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImuFace.entries.forEach { face ->
                    val next = (imu as? ImuCalibrationState.Running)?.nextFace == face
                    Box(
                        Modifier.size(width = 84.dp, height = 48.dp).background(
                            when {
                                face in done -> Color(0xFF2E7D32)
                                next -> Color(0xFFF9A825)
                                else -> Color.DarkGray
                            },
                            RoundedCornerShape(8.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(face.label), color = Color.White) }
                }
            }
            when (imu) {
                ImuCalibrationState.Idle -> {
                    Text(stringResource(R.string.imu_intro))
                    Button(onClick = viewModel::startImuCalibration, enabled = state.onGround) { Text(stringResource(R.string.imu_start)) }
                }
                ImuCalibrationState.Starting -> Text(stringResource(R.string.imu_starting))
                is ImuCalibrationState.Running -> {
                    Text(stringResource(R.string.imu_place, stringResource(imu.nextFace?.label ?: R.string.imu_face_bottom)))
                    LinearProgressIndicator(
                        progress = { (done.size * 10 + imu.progress * 0.4f) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.imu_count, done.size))
                }
                ImuCalibrationState.Succeeded -> Text(stringResource(R.string.imu_success), color = MaterialTheme.colorScheme.primary)
                is ImuCalibrationState.Failed -> {
                    Text(stringResource(R.string.imu_failed), color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = viewModel::retryImuCalibration,
                        enabled = state.onGround,
                    ) { Text(stringResource(R.string.imu_retry)) }
                }
            }
        }
    }
}

private val ImuFace.label: Int
    get() = when (this) {
        ImuFace.BOTTOM -> R.string.imu_face_bottom
        ImuFace.RIGHT -> R.string.imu_face_right
        ImuFace.LEFT -> R.string.imu_face_left
        ImuFace.FRONT -> R.string.imu_face_front
        ImuFace.BACK -> R.string.imu_face_back
        ImuFace.TOP -> R.string.imu_face_top
    }
