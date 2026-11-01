package com.drone.tellopilot.ui.flight

import com.drone.tello.control.LinkState
import com.drone.tello.control.ManeuverState
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Telemetry
import com.drone.tello.control.Units
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.FlipDirection

enum class ConnectionStatus { WAITING_WIFI, CONNECTING, CONNECTED, RECONNECTING }

/** Alerts, declared from the most to the least severe (the HUD shows the first ones). */
enum class Warning(val critical: Boolean) {
    BATTERY_CRITICAL(true),
    /** Flight controller sensor error: land as soon as possible (official "Fcerror"). */
    SENSOR_ERROR(true),
    OVERHEATING(true),
    MOTOR_ERROR(true),
    IMU_ERROR(true),
    BATTERY_ERROR(true),
    /** The phone throttles: video and controls may lag. */
    PHONE_OVERHEATING(true),
    BATTERY_LOW(false),
    /** Strong Wi-Fi interference in flight: the link may drop. */
    WIFI_INTERFERENCE(false),
    /** On the ground, motors off, for a long time: the drone heats up without propeller airflow. */
    GROUND_HEAT(false),
    BAROMETER(false),
    /** The phone is close to throttling. */
    PHONE_HOT(false),
    WIND(false),
    NO_POSITIONING(false),
    VISION_SENSOR(false),
    TOO_DARK(false),
    CAMERA_FAULT(false),
    IMU_PREHEATING(false),
    CALIBRATION_NEEDED(false),
    ;

    /** Shown even when the pilot hides information alerts. */
    val alwaysShown: Boolean
        get() = critical || this == BATTERY_LOW || this == NO_POSITIONING || this == WIFI_INTERFERENCE

    companion object {
        /** Motor/ESC code 206 only means "not activated yet": handled by the activation, not an alarm. */
        private const val MOTOR_NOT_ACTIVATED = 206

        /** Interference level the official app warns about (in flight). */
        private const val STRONG_INTERFERENCE = 100

        fun from(flight: FlightData, telemetry: Telemetry, groundTooLong: Boolean = false): List<Warning> = buildList {
            if (flight.batteryCritical) add(BATTERY_CRITICAL)
            // In flight only (the official app shows it once): a bit left set on the ground
            // must not raise a permanent critical alarm.
            if (flight.flying && flight.flightControllerError) add(SENSOR_ERROR)
            if (flight.overheating) add(OVERHEATING)
            if (flight.motorErrorCode != 0 && flight.motorErrorCode != MOTOR_NOT_ACTIVATED) add(MOTOR_ERROR)
            if (flight.imuError || flight.imuCalibrationState == 2) add(IMU_ERROR)
            if (flight.batteryError) add(BATTERY_ERROR)
            if (flight.batteryLow && !flight.batteryCritical) add(BATTERY_LOW)
            if (flight.flying && (telemetry.wifi?.interference ?: 0) >= STRONG_INTERFERENCE) add(WIFI_INTERFERENCE)
            if (groundTooLong && !flight.flying) add(GROUND_HEAT)
            // In flight only: the meaning of this bit on the ground is not documented.
            if (flight.flying && flight.barometerError) add(BAROMETER)
            if (flight.windWarning) add(WIND)
            if (flight.flying && flight.positioningUnavailable) add(NO_POSITIONING)
            if (flight.visionSensorError) add(VISION_SENSOR)
            if (telemetry.tooDark) add(TOO_DARK)
            if (telemetry.cameraFault) add(CAMERA_FAULT)
            if (flight.imuCalibrationState == 1) add(IMU_PREHEATING)
            if (flight.gravityCalibrationNeeded && !flight.flying) add(CALIBRATION_NEEDED)
        }.sortedBy { it.ordinal }
    }
}

sealed interface RecordingUi {
    data object Idle : RecordingUi
    data object Starting : RecordingUi
    data class Recording(val startedAtMs: Long) : RecordingUi
}

data class FlightUiState(
    val status: ConnectionStatus = ConnectionStatus.WAITING_WIFI,
    val batteryPercent: Int? = null,
    val heightMeters: Float? = null,
    val horizontalSpeed: Float? = null,
    /** m/s, positive upwards. */
    val climbRate: Float? = null,
    /** Throw takeoff countdown (s), shown big while > 0. */
    val throwCountdown: Int = 0,
    val flyTimeSeconds: Int? = null,
    val wifiStrength: Int? = null,
    val flying: Boolean = false,
    val fastMode: Boolean = false,
    val tripod: Boolean = false,
    /** From the drone's log stream: attitude, IMU temperature, visual-odometry track. */
    val attitude: com.drone.tello.protocol.Attitude? = null,
    val imuTemperatureC: Float? = null,
    val track: List<androidx.compose.ui.geometry.Offset> = emptyList(),
    val videoActive: Boolean = false,
    val warnings: List<Warning> = emptyList(),
    val motorErrorCode: Int = 0,
    val recording: RecordingUi = RecordingUi.Idle,
    /** 0..1 while a photo is being taken / downloaded. */
    val photoProgress: Float? = null,
    val pilot: PilotSettings = PilotSettings(),
    val firmware: String? = null,
    val bounce: Boolean = false,
    /** Smart video currently executed by the drone, if any. */
    val smartVideo: SmartVideoMode? = null,
    val smartVideoRunning: Boolean = false,
    val gamepad: com.drone.tellopilot.input.GamepadStatus = com.drone.tellopilot.input.GamepadStatus.NONE,
    /** The gamepad is flying: screen sticks invisible (but still touchable). */
    val sticksHidden: Boolean = false,
    val autopilot: com.drone.tellopilot.tracking.AutopilotState = com.drone.tellopilot.tracking.AutopilotState.Idle,
    /** Wi-Fi is up but not on the drone (Android went back to another network). */
    val otherWifi: Boolean = false,
) {
    val autopilotActive: Boolean get() = autopilot != com.drone.tellopilot.tracking.AutopilotState.Idle

    /** Missions offered in flight: the presets, then the pilot's own. */
    val missions: List<com.drone.tello.control.Mission>
        get() = com.drone.tello.control.Mission.Presets + pilot.customMissions

    val controlsEnabled: Boolean get() = status == ConnectionStatus.CONNECTED
    val warning: Warning? get() = shownWarnings.firstOrNull()

    /**
     * Warnings on screen: information alerts can be hidden by the pilot, but never the
     * critical ones nor those that matter in flight. Vibrations always use [warnings].
     */
    val shownWarnings: List<Warning>
        get() = if (pilot.shows(com.drone.tello.control.HudItem.INFO_ALERTS)) warnings
        else warnings.filter { it.alwaysShown }
    val units: Units get() = pilot.units

    /** Settings can be changed on the ground only (stick mode changes mid-flight are dangerous). */
    val settingsAvailable: Boolean get() = !flying

    companion object {
        fun from(
            wifiAvailable: Boolean,
            link: LinkState,
            telemetry: Telemetry,
            videoActive: Boolean,
            fastMode: Boolean,
            knownFlying: Boolean,
            recording: RecordingUi = RecordingUi.Idle,
            photoProgress: Float? = null,
            pilot: PilotSettings = PilotSettings(),
            maneuvers: ManeuverState = ManeuverState(),
            gamepad: com.drone.tellopilot.input.GamepadStatus = com.drone.tellopilot.input.GamepadStatus.NONE,
            phoneHeat: com.drone.tellopilot.data.PhoneHeat = com.drone.tellopilot.data.PhoneHeat.NORMAL,
            padDriving: Boolean = false,
            autopilot: com.drone.tellopilot.tracking.AutopilotState = com.drone.tellopilot.tracking.AutopilotState.Idle,
            otherWifi: Boolean = false,
            groundTooLong: Boolean = false,
            tripod: Boolean = false,
            track: List<androidx.compose.ui.geometry.Offset> = emptyList(),
        ): FlightUiState {
            val status = when {
                !wifiAvailable -> ConnectionStatus.WAITING_WIFI
                link == LinkState.CONNECTED -> ConnectionStatus.CONNECTED
                link == LinkState.RECONNECTING -> ConnectionStatus.RECONNECTING
                else -> ConnectionStatus.CONNECTING
            }
            val live = status == ConnectionStatus.CONNECTED
            val flight = telemetry.flight.takeIf { live }
            return FlightUiState(
                status = status,
                batteryPercent = flight?.batteryPercent,
                heightMeters = flight?.heightMeters,
                horizontalSpeed = flight?.horizontalSpeed,
                climbRate = flight?.climbRate,
                throwCountdown = flight?.takeIf { !it.flying }?.throwCountdown ?: 0,
                flyTimeSeconds = flight?.flyTimeSeconds?.toInt(),
                wifiStrength = telemetry.wifi?.strength?.takeIf { live },
                // Last known state, kept while the link is lost: Land must stay reachable.
                flying = flight?.flying ?: knownFlying,
                fastMode = fastMode,
                tripod = tripod,
                attitude = telemetry.attitude.takeIf { live },
                // The offset of this field is not confirmed: implausible values are dropped.
                imuTemperatureC = telemetry.imuTemperatureC?.takeIf { live && it in -10f..100f },
                track = track,
                videoActive = videoActive && live,
                // Phone warnings do not depend on the link; critical ones first (two are shown).
                warnings = (
                    flight?.let { Warning.from(it, telemetry, groundTooLong) }.orEmpty() + when (phoneHeat) {
                        com.drone.tellopilot.data.PhoneHeat.HOT -> listOf(Warning.PHONE_OVERHEATING)
                        com.drone.tellopilot.data.PhoneHeat.WARM -> listOf(Warning.PHONE_HOT)
                        com.drone.tellopilot.data.PhoneHeat.NORMAL -> emptyList()
                    }
                    ).sortedBy { !it.critical },
                motorErrorCode = flight?.motorErrorCode ?: 0,
                recording = recording,
                photoProgress = photoProgress.takeIf { live },
                pilot = pilot,
                firmware = telemetry.firmware,
                bounce = maneuvers.bounce && live,
                smartVideo = maneuvers.smartVideo?.mode ?: maneuvers.requestedSmartVideo,
                // Not gated on the link: Stop must stay reachable while reconnecting.
                smartVideoRunning = maneuvers.smartVideoActive,
                gamepad = gamepad,
                // Flying with the gamepad: the screen sticks fade out (still active where they
                // are), and come back as soon as the pilot touches one or the pad goes away.
                sticksHidden = pilot.hideSticksWithPad && padDriving &&
                    gamepad == com.drone.tellopilot.input.GamepadStatus.READY,
                autopilot = autopilot,
                otherWifi = otherWifi && status == ConnectionStatus.WAITING_WIFI,
            )
        }
    }
}

sealed interface FlightAction {
    data object Takeoff : FlightAction
    data object Land : FlightAction
    data object ThrowTakeoff : FlightAction
    data object PalmLand : FlightAction
    data class Flip(val direction: FlipDirection) : FlightAction
    data class SetBounce(val enabled: Boolean) : FlightAction
    data class StartSmartVideo(val mode: SmartVideoMode) : FlightAction
    data object StopSmartVideo : FlightAction
    data object ToggleFastMode : FlightAction
    /** Slow, smooth sticks for filming (exclusive with the fast mode). */
    data object ToggleTripod : FlightAction
    data object TakePhoto : FlightAction
    data object ToggleRecording : FlightAction
    /** Fingers lifted / app paused: sticks back to neutral. */
    data object ReleaseSticks : FlightAction
    data class StartMission(val mission: com.drone.tello.control.Mission) : FlightAction
    /** [orbitDirection]: +1 = to the right, -1 = to the left (orbit only). */
    data class StartTracking(val mode: com.drone.tello.control.TrackingMode, val orbitDirection: Int = 1) : FlightAction
    data object StopAutopilot : FlightAction
}
