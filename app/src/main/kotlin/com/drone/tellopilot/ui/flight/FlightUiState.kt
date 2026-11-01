package com.drone.tellopilot.ui.flight

import com.drone.tello.control.LinkState
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Telemetry
import com.drone.tello.control.Units
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.FlipDirection

enum class ConnectionStatus { WAITING_WIFI, CONNECTING, CONNECTED, RECONNECTING }

/** Alerts, declared from the most to the least severe (the HUD shows the first ones). */
enum class Warning(val critical: Boolean) {
    BATTERY_CRITICAL(true),
    OVERHEATING(true),
    MOTOR_ERROR(true),
    IMU_ERROR(true),
    BATTERY_ERROR(true),
    BATTERY_LOW(false),
    WIND(false),
    NO_POSITIONING(false),
    VISION_SENSOR(false),
    TOO_DARK(false),
    CAMERA_FAULT(false),
    IMU_PREHEATING(false),
    CALIBRATION_NEEDED(false),
    ;

    companion object {
        /** Motor/ESC code 206 only means "not activated yet": handled by the activation, not an alarm. */
        private const val MOTOR_NOT_ACTIVATED = 206

        fun from(flight: FlightData, telemetry: Telemetry): List<Warning> = buildList {
            if (flight.batteryCritical) add(BATTERY_CRITICAL)
            if (flight.overheating) add(OVERHEATING)
            if (flight.motorErrorCode != 0 && flight.motorErrorCode != MOTOR_NOT_ACTIVATED) add(MOTOR_ERROR)
            if (flight.imuError || flight.imuCalibrationState == 2) add(IMU_ERROR)
            if (flight.batteryError) add(BATTERY_ERROR)
            if (flight.batteryLow && !flight.batteryCritical) add(BATTERY_LOW)
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
    val flyTimeSeconds: Int? = null,
    val wifiStrength: Int? = null,
    val flying: Boolean = false,
    val fastMode: Boolean = false,
    val videoActive: Boolean = false,
    val warnings: List<Warning> = emptyList(),
    val motorErrorCode: Int = 0,
    val recording: RecordingUi = RecordingUi.Idle,
    /** 0..1 while a photo is being taken / downloaded. */
    val photoProgress: Float? = null,
    val pilot: PilotSettings = PilotSettings(),
    val firmware: String? = null,
) {
    val controlsEnabled: Boolean get() = status == ConnectionStatus.CONNECTED
    val warning: Warning? get() = warnings.firstOrNull()
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
                flyTimeSeconds = flight?.flyTimeSeconds?.toInt(),
                wifiStrength = telemetry.wifi?.strength?.takeIf { live },
                // Last known state, kept while the link is lost: Land must stay reachable.
                flying = flight?.flying ?: knownFlying,
                fastMode = fastMode,
                videoActive = videoActive && live,
                warnings = flight?.let { Warning.from(it, telemetry) }.orEmpty(),
                motorErrorCode = flight?.motorErrorCode ?: 0,
                recording = recording,
                photoProgress = photoProgress.takeIf { live },
                pilot = pilot,
                firmware = telemetry.firmware,
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
    data object ToggleFastMode : FlightAction
    data object TakePhoto : FlightAction
    data object ToggleRecording : FlightAction
    /** Fingers lifted / app paused: sticks back to neutral. */
    data object ReleaseSticks : FlightAction
}
