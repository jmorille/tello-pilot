package com.drone.tellopilot.ui.flight

import android.view.InputDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.drone.tello.control.Units
import com.drone.tello.protocol.FlipDirection
import com.drone.tellopilot.R
import com.drone.tellopilot.video.FrameSink

@Composable
fun FlightRoute(onOpenSettings: () -> Unit, viewModel: FlightViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                UserMessage.PHOTO_SAVED -> R.string.message_photo_saved
                UserMessage.PHOTO_FAILED -> R.string.message_photo_failed
                UserMessage.VIDEO_SAVED -> R.string.message_video_saved
                UserMessage.VIDEO_FAILED -> R.string.message_video_failed
            }
            snackbar.showSnackbar(context.getString(text))
        }
    }

    // Safety: never keep a stick deflected when the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onAction(FlightAction.ReleaseSticks) }

    // A back gesture would close the flight screen and cut the link: ignored while flying.
    BackHandler(enabled = state.flying) { }

    // Deliver touch events as soon as they arrive instead of batching them per vsync.
    val view = LocalView.current
    LaunchedEffect(view) { view.requestUnbufferedDispatch(InputDevice.SOURCE_TOUCHSCREEN) }

    FlightScreen(
        state = state,
        onAction = viewModel::onAction,
        onLeftStick = viewModel::onLeftStick,
        onRightStick = viewModel::onRightStick,
        attachVideo = viewModel::attachVideo,
        detachVideo = viewModel::detachVideo,
        onOpenSettings = onOpenSettings,
        snackbar = snackbar,
    )
}

@Composable
fun FlightScreen(
    state: FlightUiState,
    onAction: (FlightAction) -> Unit,
    onLeftStick: (Float, Float) -> Unit,
    onRightStick: (Float, Float) -> Unit,
    attachVideo: (FrameSink) -> Unit,
    detachVideo: (FrameSink) -> Unit,
    onOpenSettings: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val stickSize = state.pilot.joystickSizeDp.dp
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoView(attachVideo, detachVideo, Modifier.align(Alignment.Center))

        if (!state.videoActive) {
            Text(
                text = stringResource(statusText(state.status, video = state.status == ConnectionStatus.CONNECTED)),
                color = Color(0xFFFFD54F),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(state)
                Box(Modifier.weight(1f))
                TextButton(onClick = onOpenSettings, enabled = state.settingsAvailable) {
                    Text(stringResource(R.string.action_settings))
                }
            }
            // The two most severe alerts.
            state.warnings.take(2).forEach {
                WarningBanner(it, state.motorErrorCode, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
            }
            state.photoProgress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).size(width = 160.dp, height = 4.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Each stick only re-centres itself: lifting one finger must not cut the other axes.
                Joystick(
                    onMove = onLeftStick,
                    onRelease = { onLeftStick(0f, 0f) },
                    enabled = state.controlsEnabled,
                    modifier = Modifier.align(Alignment.BottomStart).size(stickSize).systemGestureExclusion(),
                )
                ActionPanel(state, onAction, Modifier.align(Alignment.BottomCenter))
                Joystick(
                    onMove = onRightStick,
                    onRelease = { onRightStick(0f, 0f) },
                    enabled = state.controlsEnabled,
                    modifier = Modifier.align(Alignment.BottomEnd).size(stickSize).systemGestureExclusion(),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp))
    }
}

private fun statusText(status: ConnectionStatus, video: Boolean) = when (status) {
    ConnectionStatus.WAITING_WIFI -> R.string.status_waiting_wifi
    ConnectionStatus.CONNECTING -> R.string.status_connecting
    ConnectionStatus.RECONNECTING -> R.string.status_reconnecting
    ConnectionStatus.CONNECTED -> if (video) R.string.video_waiting else R.string.status_connected
}

@Composable
private fun TopBar(state: FlightUiState) {
    Surface(color = Color.Black.copy(alpha = 0.45f), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColor = when (state.status) {
                ConnectionStatus.CONNECTED -> Color(0xFF66BB6A)
                ConnectionStatus.RECONNECTING -> Color(0xFFEF5350)
                else -> Color(0xFFFFCA28)
            }
            Box(Modifier.size(10.dp).background(statusColor, RoundedCornerShape(5.dp)))
            HudText(state.batteryPercent?.let { stringResource(R.string.hud_battery, it) } ?: "--",
                color = if ((state.batteryPercent ?: 100) <= 20) Color(0xFFEF5350) else Color.White)
            val imperial = state.units == Units.IMPERIAL
            HudText(state.heightMeters?.let {
                if (imperial) stringResource(R.string.hud_height_ft, it * FEET_PER_METER) else stringResource(R.string.hud_height, it)
            } ?: "H --")
            HudText(state.horizontalSpeed?.let {
                if (imperial) stringResource(R.string.hud_speed_mph, it * MPH_PER_MS) else stringResource(R.string.hud_speed, it)
            } ?: "V --")
            HudText(state.flyTimeSeconds?.let { stringResource(R.string.hud_time, it / 60, it % 60) } ?: "--:--")
            HudText(state.wifiStrength?.let { stringResource(R.string.hud_wifi, it) } ?: "Wi-Fi --")
            when (state.recording) {
                is RecordingUi.Recording -> HudText(stringResource(R.string.hud_rec), color = Color(0xFFEF5350))
                RecordingUi.Starting -> HudText(stringResource(R.string.hud_rec_starting), color = Color(0xFFFFCA28))
                RecordingUi.Idle -> Unit
            }
        }
    }
}

@Composable
private fun HudText(text: String, color: Color = Color.White) {
    Text(text, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WarningBanner(warning: Warning, motorErrorCode: Int, modifier: Modifier = Modifier) {
    val text = when (warning) {
        Warning.BATTERY_CRITICAL -> stringResource(R.string.warning_battery_critical)
        Warning.OVERHEATING -> stringResource(R.string.warning_overheating)
        Warning.MOTOR_ERROR -> stringResource(R.string.warning_motor_error, motorErrorCode)
        Warning.IMU_ERROR -> stringResource(R.string.warning_imu_error)
        Warning.BATTERY_ERROR -> stringResource(R.string.warning_battery_error)
        Warning.BATTERY_LOW -> stringResource(R.string.warning_battery_low)
        Warning.WIND -> stringResource(R.string.warning_wind)
        Warning.NO_POSITIONING -> stringResource(R.string.warning_no_positioning)
        Warning.VISION_SENSOR -> stringResource(R.string.warning_vision_sensor)
        Warning.TOO_DARK -> stringResource(R.string.warning_too_dark)
        Warning.CAMERA_FAULT -> stringResource(R.string.warning_camera_fault)
        Warning.IMU_PREHEATING -> stringResource(R.string.warning_imu_preheating)
        Warning.CALIBRATION_NEEDED -> stringResource(R.string.warning_calibration)
    }
    Surface(
        modifier = modifier,
        color = if (warning.critical) Color(0xCCD32F2F) else Color(0xCCF57C00),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

private const val FEET_PER_METER = 3.28084f
private const val MPH_PER_MS = 2.23694f

@Composable
private fun ActionPanel(state: FlightUiState, onAction: (FlightAction) -> Unit, modifier: Modifier = Modifier) {
    val enabled = state.controlsEnabled
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.flying) {
                FilledTonalButton(onClick = { onAction(FlightAction.PalmLand) }, enabled = enabled) {
                    Text(stringResource(R.string.action_palm_land))
                }
                FlipMenu(enabled) { onAction(FlightAction.Flip(it)) }
            } else {
                FilledTonalButton(onClick = { onAction(FlightAction.ThrowTakeoff) }, enabled = enabled) {
                    Text(stringResource(R.string.action_throw))
                }
            }
            FilterChip(
                selected = state.fastMode,
                onClick = { onAction(FlightAction.ToggleFastMode) },
                label = { Text(stringResource(R.string.action_fast)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onAction(FlightAction.TakePhoto) }, enabled = enabled && state.photoProgress == null) {
                Text(stringResource(R.string.action_photo))
            }
            val recording = state.recording != RecordingUi.Idle
            FilledTonalButton(
                onClick = { onAction(FlightAction.ToggleRecording) },
                enabled = enabled || recording, // stopping is always possible
                colors = if (recording) {
                    ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFD32F2F))
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Text(stringResource(if (recording) R.string.action_stop_recording else R.string.action_record))
            }
        }
        // Land is always reachable while flying (last known state), even if the link is lost:
        // the controller sends it best-effort and repeats it until the drone reports landed.
        if (state.flying) {
            Button(
                onClick = { onAction(FlightAction.Land) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier.size(width = 180.dp, height = 56.dp),
            ) { Text(stringResource(R.string.action_land), style = MaterialTheme.typography.titleMedium) }
        } else if (state.pilot.confirmTakeoff) {
            SlideToConfirm(stringResource(R.string.action_slide_takeoff), enabled, onConfirm = { onAction(FlightAction.Takeoff) })
        } else {
            Button(
                onClick = { onAction(FlightAction.Takeoff) },
                enabled = enabled,
                modifier = Modifier.size(width = 180.dp, height = 56.dp),
            ) { Text(stringResource(R.string.action_takeoff), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun FlipMenu(enabled: Boolean, onFlip: (FlipDirection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled) { Text(stringResource(R.string.action_flip)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                FlipDirection.FORWARD to R.string.flip_forward,
                FlipDirection.BACK to R.string.flip_back,
                FlipDirection.LEFT to R.string.flip_left,
                FlipDirection.RIGHT to R.string.flip_right,
            ).forEach { (direction, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; onFlip(direction) })
            }
        }
    }
}

@Preview(widthDp = 860, heightDp = 400)
@Composable
private fun FlightScreenPreview() {
    MaterialTheme {
        FlightScreen(
            state = FlightUiState(
                status = ConnectionStatus.CONNECTED, batteryPercent = 76, heightMeters = 1.8f,
                horizontalSpeed = 0.4f, flyTimeSeconds = 83, wifiStrength = 90, flying = true,
            ),
            onAction = {}, onLeftStick = { _, _ -> }, onRightStick = { _, _ -> },
            attachVideo = {}, detachVideo = {},
        )
    }
}
