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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.platform.LocalResources
import com.drone.tello.control.Units
import com.drone.tello.control.HudItem
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tellopilot.R
import com.drone.tellopilot.video.FrameSink

@Composable
fun FlightRoute(onOpenSettings: () -> Unit, onOpenGallery: () -> Unit = {}, viewModel: FlightViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is UserMessage.AutopilotEnded -> autopilotEndText(message.end)
                UserMessage.AutopilotUnavailable -> R.string.autopilot_unavailable
                UserMessage.SmartVideoInterrupted -> R.string.message_smart_video_interrupted
                UserMessage.GravityCalibrating -> R.string.message_gravity_calibrating
                UserMessage.GravityCalibrated -> R.string.message_gravity_calibrated
                UserMessage.PhotoFailed -> R.string.message_photo_failed
                UserMessage.VideoSaved -> R.string.message_video_saved
                UserMessage.VideoFailed -> R.string.message_video_failed
                UserMessage.DroneRefused -> R.string.message_drone_refused
                is UserMessage.ModeRefused -> modeCheckText(message.reason)
            }
            snackbar.showSnackbar(resources.getString(text))
        }
    }

    // Safety: never keep a stick deflected when the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.setGamepadActive(false)
        viewModel.onAction(FlightAction.ReleaseSticks)
        viewModel.onBackground()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.setGamepadActive(true)
        viewModel.onForeground()
    }

    // A back gesture would close the flight screen and cut the link: ignored while flying.
    BackHandler(enabled = state.flying) { }

    // Deliver touch events as soon as they arrive instead of batching them per vsync.
    val view = LocalView.current
    LaunchedEffect(view) { view.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER or InputDevice.SOURCE_CLASS_JOYSTICK) }

    FlightScreen(
        state = state,
        onAction = viewModel::onAction,
        onLeftStick = viewModel::onLeftStick,
        onRightStick = viewModel::onRightStick,
        onRevealSticks = viewModel::revealSticks,
        attachVideo = viewModel::attachVideo,
        detachVideo = viewModel::detachVideo,
        onOpenSettings = onOpenSettings,
        onOpenGallery = onOpenGallery,
        onConnectDrone = viewModel::connectToDrone,
        snackbar = snackbar,
    )
}

@Composable
fun FlightScreen(
    state: FlightUiState,
    onAction: (FlightAction) -> Unit,
    onLeftStick: (Float, Float) -> Unit,
    onRightStick: (Float, Float) -> Unit,
    onRevealSticks: () -> Unit = {},
    attachVideo: (FrameSink) -> Unit,
    detachVideo: (FrameSink) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onConnectDrone: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val stickSize = state.pilot.joystickSizeDp.dp
    // Read in the layer only: fading never recomposes the screen.
    val sticksAlphaState = androidx.compose.animation.core.animateFloatAsState(
        // A faint ghost stays visible: the pilot always knows where the sticks are.
        targetValue = if (state.sticksHidden) HIDDEN_STICK_ALPHA else 1f,
        animationSpec = androidx.compose.animation.core.tween(if (state.sticksHidden) 600 else 150),
        label = "sticksAlpha",
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val videoSize = videoSize(state.pilot.videoFit, maxWidth, maxHeight)
            // requiredSize: in the cropped modes the 4:3 surface is taller than the screen and
            // overflows it evenly at the top and bottom (cut by the display itself).
            val videoModifier = Modifier.align(Alignment.Center).requiredSize(videoSize.first, videoSize.second)
            VideoView(attachVideo, detachVideo, videoModifier, sharpen = state.pilot.videoSharpen)
            (state.autopilot as? com.drone.tellopilot.tracking.AutopilotState.Tracking)?.let {
                TrackingOverlay(it, videoModifier)
            }
        }
        if (state.pilot.shows(HudItem.HORIZON)) state.attitude?.let {
            AttitudeIndicator(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
        if (state.pilot.shows(HudItem.MAP) && state.flying && state.track.size >= 2) {
            TrackMap(state.track, Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 12.dp, top = 64.dp))
        }
        if (state.throwCountdown > 0) {
            // Throw takeoff armed: seconds left to throw the drone (official countdown).
            Text(
                stringResource(R.string.hud_throw_countdown, state.throwCountdown),
                Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                color = Color(0xFFFFCA28),
                style = MaterialTheme.typography.displayMedium,
            )
        }
        if (state.autopilotActive) {
            AutopilotBanner(
                state.autopilot,
                onStop = { onAction(FlightAction.StopAutopilot) },
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp).widthIn(max = 460.dp),
            )
        }

        if (!state.videoActive) {
            Column(
                // Upper third: the action panel occupies the bottom centre.
                Modifier.align(Alignment.Center).offset(y = (-80).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        if (state.otherWifi) R.string.status_other_wifi
                        else statusText(state.status, video = state.status == ConnectionStatus.CONNECTED),
                    ),
                    color = Color(0xFFFFD54F),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (state.status == ConnectionStatus.WAITING_WIFI) {
                    androidx.compose.material3.Button(onClick = onConnectDrone, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.action_connect_drone))
                    }
                }
            }
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            // Discreet top strip: compact HUD on the left, icon actions on the right. The video
            // stays clear; only takeoff / land (safety) remain large, at the bottom.
            Row(verticalAlignment = Alignment.Top) {
                // The HUD takes what the icons leave: the actions (stop recording…) never get squeezed out.
                Box(Modifier.weight(1f)) { TopBar(state) }
                TopActions(state, onAction, onOpenSettings, onOpenGallery)
            }
            // The two most severe alerts.
            state.shownWarnings.take(2).forEach {
                // Left side, under the HUD: the centre of the video stays clear for the pilot.
                WarningBanner(it, state.motorErrorCode, Modifier.align(Alignment.Start).padding(top = 6.dp).widthIn(max = 320.dp))
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
                    modifier = Modifier.align(Alignment.BottomStart).size(stickSize).systemGestureExclusion()
                        .graphicsLayer { alpha = sticksAlphaState.value },
                )
                if (state.sticksHidden) RevealZone(onRevealSticks, Modifier.align(Alignment.BottomStart).size(stickSize))
                ActionPanel(state, onAction, Modifier.align(Alignment.BottomCenter))
                Joystick(
                    onMove = onRightStick,
                    onRelease = { onRightStick(0f, 0f) },
                    enabled = state.controlsEnabled,
                    modifier = Modifier.align(Alignment.BottomEnd).size(stickSize).systemGestureExclusion()
                        .graphicsLayer { alpha = sticksAlphaState.value },
                )
                if (state.sticksHidden) RevealZone(onRevealSticks, Modifier.align(Alignment.BottomEnd).size(stickSize))
            }
        }
        // Right side, under the icons: messages never cover the centre of the video.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 60.dp, end = 12.dp).widthIn(max = 360.dp),
        )
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
    Surface(color = Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(10.dp)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColor = when (state.status) {
                ConnectionStatus.CONNECTED -> Color(0xFF66BB6A)
                ConnectionStatus.RECONNECTING -> Color(0xFFEF5350)
                else -> Color(0xFFFFCA28)
            }
            Box(Modifier.size(10.dp).background(statusColor, RoundedCornerShape(5.dp)))
            val show = state.pilot::shows
            if (show(HudItem.BATTERY)) {
                HudText(state.batteryPercent?.let { stringResource(R.string.hud_battery, it) } ?: "--",
                    color = if ((state.batteryPercent ?: 100) <= 20) Color(0xFFEF5350) else Color.White)
            }
            val imperial = state.units == Units.IMPERIAL
            if (show(HudItem.HEIGHT)) {
                HudText(state.heightMeters?.let {
                    if (imperial) stringResource(R.string.hud_height_ft, it * FEET_PER_METER) else stringResource(R.string.hud_height, it)
                } ?: "H --")
            }
            if (show(HudItem.SPEED)) {
                HudText(state.horizontalSpeed?.let {
                    if (imperial) stringResource(R.string.hud_speed_mph, it * MPH_PER_MS) else stringResource(R.string.hud_speed, it)
                } ?: "V --")
            }
            if (show(HudItem.VARIO)) state.climbRate?.let { rate ->
                // Vario: arrow by direction, hidden noise under 0.1 m/s.
                val arrow = when {
                    rate >= VARIO_DEADBAND -> "↑"
                    rate <= -VARIO_DEADBAND -> "↓"
                    else -> "↕"
                }
                val value = kotlin.math.abs(rate)
                HudText(
                    if (imperial) stringResource(R.string.hud_vario_mph, arrow, value * MPH_PER_MS)
                    else stringResource(R.string.hud_vario, arrow, value),
                )
            }
            if (show(HudItem.FLIGHT_TIME)) {
                HudText(state.flyTimeSeconds?.let { stringResource(R.string.hud_time, it / 60, it % 60) } ?: "--:--")
            }
            if (show(HudItem.TEMPERATURE)) state.imuTemperatureC?.let {
                HudText(stringResource(R.string.hud_temperature, it), color = if (it >= HOT_DRONE_C) Color(0xFFFFCA28) else Color.White)
            }
            if (show(HudItem.WIFI)) {
                HudText(state.wifiStrength?.let { stringResource(R.string.hud_wifi, it) } ?: "Wi-Fi --")
            }
            // "Centre the sticks" stays visible: the pad does not fly until it is done.
            when (state.gamepad) {
                com.drone.tellopilot.input.GamepadStatus.READY ->
                    if (show(HudItem.GAMEPAD)) HudText(stringResource(R.string.hud_gamepad))
                com.drone.tellopilot.input.GamepadStatus.CENTER_STICKS ->
                    HudText(stringResource(R.string.hud_gamepad_center), color = Color(0xFFFFCA28))
                com.drone.tellopilot.input.GamepadStatus.NONE -> Unit
            }
            if (show(HudItem.RECORDING)) when (state.recording) {
                is RecordingUi.Recording -> HudText(stringResource(R.string.hud_rec), color = Color(0xFFEF5350))
                RecordingUi.Starting -> HudText(stringResource(R.string.hud_rec_starting), color = Color(0xFFFFCA28))
                RecordingUi.Idle -> Unit
            }
        }
    }
}

@Composable
private fun HudText(text: String, color: Color = Color.White) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        maxLines = 1, softWrap = false,
    )
}

/** Icon actions, top right: small translucent circles that leave the video visible. */
@Composable
private fun TopActions(
    state: FlightUiState,
    onAction: (FlightAction) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val enabled = state.controlsEnabled
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.flying) {
            HudIcon(R.drawable.ic_palm, R.string.action_palm_land, enabled) { onAction(FlightAction.PalmLand) }
            if (state.smartVideoRunning) {
                HudIcon(R.drawable.ic_stop, R.string.action_stop_smart_video, true, tint = Color(0xFFFFA726)) {
                    onAction(FlightAction.StopSmartVideo)
                }
            } else {
                ModesMenu(state, enabled, onAction)
            }
            if (state.autopilotActive) {
                // Always reachable, even while reconnecting.
                HudIcon(R.drawable.ic_stop, R.string.autopilot_stop, true, tint = Color(0xFF4FC3F7), selected = true) {
                    onAction(FlightAction.StopAutopilot)
                }
            } else {
                AutopilotMenu(state, enabled && !state.smartVideoRunning, onAction)
            }
        } else {
            HudIcon(R.drawable.ic_throw, R.string.action_throw, enabled) { onAction(FlightAction.ThrowTakeoff) }
        }
        HudIcon(
            R.drawable.ic_bolt, R.string.action_fast, true,
            tint = if (state.fastMode) Color(0xFFFFCA28) else Color.White,
            selected = state.fastMode,
        ) { onAction(FlightAction.ToggleFastMode) }
        HudIcon(
            R.drawable.ic_tripod, R.string.action_tripod, true,
            tint = if (state.tripod) Color(0xFF4FC3F7) else Color.White,
            selected = state.tripod,
        ) { onAction(FlightAction.ToggleTripod) }
        HudIcon(R.drawable.ic_photo, R.string.action_photo, enabled && state.photoProgress == null) {
            onAction(FlightAction.TakePhoto)
        }
        val recording = state.recording != RecordingUi.Idle
        HudIcon(
            if (recording) R.drawable.ic_stop else R.drawable.ic_videocam,
            if (recording) R.string.action_stop_recording else R.string.action_record,
            enabled || recording, // stopping is always possible
            tint = if (recording) Color(0xFFEF5350) else Color.White,
            selected = recording,
        ) { onAction(FlightAction.ToggleRecording) }
        // On the ground only, like the settings: nothing must hide the flight screen in the air.
        if (!state.flying) HudIcon(R.drawable.ic_gallery, R.string.action_gallery, true, onClick = onOpenGallery)
        HudIcon(R.drawable.ic_settings, R.string.action_settings, state.settingsAvailable, onClick = onOpenSettings)
    }
}

@Composable
private fun HudIcon(
    icon: Int,
    label: Int,
    enabled: Boolean,
    tint: Color = Color.White,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = if (selected) 0.55f else 0.3f),
            contentColor = tint,
            disabledContainerColor = Color.Black.copy(alpha = 0.2f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun WarningBanner(warning: Warning, motorErrorCode: Int, modifier: Modifier = Modifier) {
    val text = when (warning) {
        Warning.BATTERY_CRITICAL -> stringResource(R.string.warning_battery_critical)
        Warning.OVERHEATING -> stringResource(R.string.warning_overheating)
        Warning.SENSOR_ERROR -> stringResource(R.string.warning_sensor_error)
        Warning.WIFI_INTERFERENCE -> stringResource(R.string.warning_wifi_interference)
        Warning.GROUND_HEAT -> stringResource(R.string.warning_ground_heat)
        Warning.BAROMETER -> stringResource(R.string.warning_barometer)
        Warning.MOTOR_ERROR -> stringResource(R.string.warning_motor_error, motorErrorCode)
        Warning.IMU_ERROR -> stringResource(R.string.warning_imu_error)
        Warning.BATTERY_ERROR -> stringResource(R.string.warning_battery_error)
        Warning.PHONE_OVERHEATING -> stringResource(R.string.warning_phone_overheating)
        Warning.PHONE_HOT -> stringResource(R.string.warning_phone_hot)
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
private const val HIDDEN_STICK_ALPHA = 0.12f

/**
 * Over a hidden stick: swallows the whole gesture (an invisible stick never commands the drone,
 * e.g. a thumb resting on a corner) and shows the sticks again; the next touch pilots.
 */
@Composable
private fun RevealZone(onReveal: () -> Unit, modifier: Modifier) {
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                onReveal()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        },
    )
}
private const val MPH_PER_MS = 2.23694f

@Composable
private fun ActionPanel(state: FlightUiState, onAction: (FlightAction) -> Unit, modifier: Modifier = Modifier) {
    val enabled = state.controlsEnabled
    // Only the safety-critical takeoff / land stay at the bottom; the rest is in TopActions.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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

/** Firmware manoeuvres: 8-direction flips, bounce, smart videos (official "flight modes"). */
@Composable
private fun ModesMenu(state: FlightUiState, enabled: Boolean, onAction: (FlightAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var flipPad by remember { mutableStateOf(false) }
    Box {
        HudIcon(R.drawable.ic_modes, R.string.action_modes, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = MenuTowardCentre, properties = NonFocusablePopup) {
            DropdownMenuItem(text = { Text(stringResource(R.string.mode_flip)) }, onClick = { open = false; flipPad = true })
            DropdownMenuItem(
                text = { Text(stringResource(if (state.bounce) R.string.mode_bounce_off else R.string.mode_bounce_on)) },
                onClick = { open = false; onAction(FlightAction.SetBounce(!state.bounce)) },
            )
            SmartVideoMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.label)) },
                    onClick = { open = false; onAction(FlightAction.StartSmartVideo(mode)) },
                )
            }
        }
        if (flipPad) {
            FlipPad(onFlip = { flipPad = false; onAction(FlightAction.Flip(it)) }, onDismiss = { flipPad = false })
        }
    }
}

@Composable
private fun AutopilotMenu(state: FlightUiState, enabled: Boolean, onAction: (FlightAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HudIcon(R.drawable.ic_autopilot, R.string.action_autopilot, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = MenuTowardCentre, properties = NonFocusablePopup) {
            MenuHeader(R.string.autopilot_tracking)
            listOf(
                R.string.tracking_follow to FlightAction.StartTracking(com.drone.tello.control.TrackingMode.FOLLOW),
                R.string.tracking_spotlight to FlightAction.StartTracking(com.drone.tello.control.TrackingMode.SPOTLIGHT),
                R.string.tracking_orbit_left to FlightAction.StartTracking(com.drone.tello.control.TrackingMode.ORBIT, -1),
                R.string.tracking_orbit_right to FlightAction.StartTracking(com.drone.tello.control.TrackingMode.ORBIT, 1),
            ).forEach { (label, action) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; onAction(action) })
            }
            MenuHeader(R.string.autopilot_missions)
            state.missions.forEach { mission ->
                DropdownMenuItem(
                    text = { Text(mission.name) },
                    onClick = { open = false; onAction(FlightAction.StartMission(mission)) },
                )
            }
        }
    }
}

@Composable
private fun MenuHeader(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** What the automatic flight is doing, and how to take control back. */
@Composable
private fun AutopilotBanner(
    autopilot: com.drone.tellopilot.tracking.AutopilotState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (autopilot) {
        is com.drone.tellopilot.tracking.AutopilotState.RunningMission ->
            stringResource(R.string.autopilot_mission_running, autopilot.name, autopilot.step, autopilot.total)
        is com.drone.tellopilot.tracking.AutopilotState.Tracking -> stringResource(
            if (autopilot.target == null) R.string.autopilot_tracking_search else R.string.autopilot_tracking_running,
            stringResource(trackingModeLabel(autopilot.mode)),
        )
        com.drone.tellopilot.tracking.AutopilotState.Idle -> return
    }
    Row(
        modifier.background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                stringResource(R.string.autopilot_takeover_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        androidx.compose.material3.TextButton(onClick = onStop) { Text(stringResource(R.string.autopilot_stop)) }
    }
}

private fun trackingModeLabel(mode: com.drone.tello.control.TrackingMode) = when (mode) {
    com.drone.tello.control.TrackingMode.FOLLOW -> R.string.tracking_follow
    com.drone.tello.control.TrackingMode.SPOTLIGHT -> R.string.tracking_spotlight
    com.drone.tello.control.TrackingMode.ORBIT -> R.string.tracking_orbit
}

/**
 * Size of the 4:3 video for [fit]: the visible window is the largest rectangle of the chosen
 * aspect inside the screen, and the video takes its width (its height may exceed the screen).
 */
private fun videoSize(
    fit: com.drone.tello.control.VideoFit,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val screenAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else VIDEO_ASPECT
    val visibleAspect = when (fit) {
        com.drone.tello.control.VideoFit.FIT -> VIDEO_ASPECT
        com.drone.tello.control.VideoFit.WIDE -> 16f / 9f
        com.drone.tello.control.VideoFit.FILL -> screenAspect
    }.coerceAtLeast(VIDEO_ASPECT) // never narrower than the full picture
    val width = minOf(maxWidth.value, maxHeight.value * visibleAspect)
    return width.dp to (width / VIDEO_ASPECT).dp
}

private const val VIDEO_ASPECT = 4f / 3f
private const val VARIO_DEADBAND = 0.1f
/** Unverified threshold: shown in amber above it. */
private const val HOT_DRONE_C = 60f

/** Frame around the followed target, drawn over the 4:3 video (same size and place). */
@Composable
private fun TrackingOverlay(tracking: com.drone.tellopilot.tracking.AutopilotState.Tracking, modifier: Modifier) {
    val box = tracking.target ?: return
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(
            color = Color(0xFF4FC3F7),
            topLeft = androidx.compose.ui.geometry.Offset(box.left * size.width, box.top * size.height),
            size = androidx.compose.ui.geometry.Size(box.width() * size.width, box.height() * size.height),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
        )
    }
}

private fun autopilotEndText(end: com.drone.tellopilot.tracking.AutopilotEnd) = when (end) {
    com.drone.tellopilot.tracking.AutopilotEnd.MissionDone -> R.string.autopilot_end_done
    com.drone.tellopilot.tracking.AutopilotEnd.PilotTookOver -> R.string.autopilot_end_pilot
    com.drone.tellopilot.tracking.AutopilotEnd.TargetLost -> R.string.autopilot_end_lost
    com.drone.tellopilot.tracking.AutopilotEnd.Stopped -> R.string.autopilot_end_stopped
    com.drone.tellopilot.tracking.AutopilotEnd.Failed -> R.string.autopilot_end_failed
    is com.drone.tellopilot.tracking.AutopilotEnd.MissionAborted -> when (end.reason) {
        com.drone.tello.control.MissionAbort.NOT_FLYING -> R.string.autopilot_abort_not_flying
        com.drone.tello.control.MissionAbort.LINK_LOST -> R.string.autopilot_abort_link
        com.drone.tello.control.MissionAbort.LOW_BATTERY -> R.string.autopilot_abort_battery
        com.drone.tello.control.MissionAbort.NO_POSITIONING -> R.string.autopilot_abort_positioning
        com.drone.tello.control.MissionAbort.STEP_TIMEOUT -> R.string.autopilot_abort_timeout
    }
}

/**
 * In-flight menus must not take the window focus: a focused popup would receive the gamepad
 * events instead of the activity (sticks frozen while the menu is open).
 */
private val NonFocusablePopup = androidx.compose.ui.window.PopupProperties(focusable = false)

/** Menus anchored on the top-right icons open toward the centre, not over the right stick. */
private val MenuTowardCentre = androidx.compose.ui.unit.DpOffset(x = (-240).dp, y = 0.dp)

/** 3 x 3 pad: the eight flip directions around a cancel button. */
@Composable
private fun FlipPad(onFlip: (FlipDirection) -> Unit, onDismiss: () -> Unit) {
    val grid = listOf(
        listOf(FlipDirection.FORWARD_LEFT, FlipDirection.FORWARD, FlipDirection.FORWARD_RIGHT),
        listOf(FlipDirection.LEFT, null, FlipDirection.RIGHT),
        listOf(FlipDirection.BACK_LEFT, FlipDirection.BACK, FlipDirection.BACK_RIGHT),
    )
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = MenuTowardCentre, properties = NonFocusablePopup) {
        Text(
            stringResource(R.string.mode_flip_hint),
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        grid.forEach { row ->
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { direction ->
                    if (direction == null) {
                        TextButton(onClick = onDismiss, modifier = Modifier.size(56.dp)) { Text("✕") }
                    } else {
                        FilledTonalButton(onClick = { onFlip(direction) }, modifier = Modifier.size(56.dp)) {
                            Text(direction.arrow, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}

private fun modeCheckText(reason: com.drone.tello.control.ModeCheck) = when (reason) {
    com.drone.tello.control.ModeCheck.OK -> R.string.message_drone_refused // never shown
    com.drone.tello.control.ModeCheck.NOT_CONNECTED -> R.string.mode_refused_not_connected
    com.drone.tello.control.ModeCheck.NOT_FLYING -> R.string.mode_refused_not_flying
    com.drone.tello.control.ModeCheck.LOW_BATTERY -> R.string.mode_refused_battery
    com.drone.tello.control.ModeCheck.TOO_HIGH -> R.string.mode_refused_height
    com.drone.tello.control.ModeCheck.WIND -> R.string.mode_refused_wind
    com.drone.tello.control.ModeCheck.PHOTO_IN_PROGRESS -> R.string.mode_refused_photo
    com.drone.tello.control.ModeCheck.MUST_BE_ON_GROUND -> R.string.mode_refused_ground
    com.drone.tello.control.ModeCheck.BUSY -> R.string.mode_refused_busy
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
