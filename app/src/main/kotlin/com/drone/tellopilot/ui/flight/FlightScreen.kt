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
import com.drone.tello.protocol.FlipDirection
import com.drone.tellopilot.R
import com.drone.tellopilot.video.FrameSink

@Composable
fun FlightRoute(viewModel: FlightViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
) {
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
            TopBar(state)
            state.warning?.let { WarningBanner(it, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Each stick only re-centres itself: lifting one finger must not cut the other axes.
                Joystick(
                    onMove = onLeftStick,
                    onRelease = { onLeftStick(0f, 0f) },
                    enabled = state.controlsEnabled,
                    modifier = Modifier.align(Alignment.BottomStart).size(200.dp).systemGestureExclusion(),
                )
                ActionPanel(state, onAction, Modifier.align(Alignment.BottomCenter))
                Joystick(
                    onMove = onRightStick,
                    onRelease = { onRightStick(0f, 0f) },
                    enabled = state.controlsEnabled,
                    modifier = Modifier.align(Alignment.BottomEnd).size(200.dp).systemGestureExclusion(),
                )
            }
        }
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
            HudText(state.heightMeters?.let { stringResource(R.string.hud_height, it) } ?: "H --")
            HudText(state.horizontalSpeed?.let { stringResource(R.string.hud_speed, it) } ?: "V --")
            HudText(state.flyTimeSeconds?.let { stringResource(R.string.hud_time, it / 60, it % 60) } ?: "--:--")
            HudText(state.wifiStrength?.let { stringResource(R.string.hud_wifi, it) } ?: "Wi-Fi --")
        }
    }
}

@Composable
private fun HudText(text: String, color: Color = Color.White) {
    Text(text, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WarningBanner(warning: Warning, modifier: Modifier = Modifier) {
    val text = when (warning) {
        Warning.BATTERY_CRITICAL -> R.string.warning_battery_critical
        Warning.BATTERY_LOW -> R.string.warning_battery_low
        Warning.TOO_DARK -> R.string.warning_too_dark
    }
    Surface(
        modifier = modifier,
        color = if (warning == Warning.TOO_DARK) Color(0xCCF57C00) else Color(0xCCD32F2F),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(stringResource(text), color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

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
        // Land is always reachable while flying (last known state), even if the link is lost:
        // the controller sends it best-effort and repeats it until the drone reports landed.
        if (state.flying) {
            Button(
                onClick = { onAction(FlightAction.Land) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier.size(width = 180.dp, height = 56.dp),
            ) { Text(stringResource(R.string.action_land), style = MaterialTheme.typography.titleMedium) }
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
