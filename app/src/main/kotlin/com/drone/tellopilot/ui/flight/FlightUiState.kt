package com.drone.tellopilot.ui.flight

import com.drone.tello.control.LinkState
import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.FlipDirection

enum class ConnectionStatus { WAITING_WIFI, CONNECTING, CONNECTED, RECONNECTING }

enum class Warning { BATTERY_CRITICAL, BATTERY_LOW, TOO_DARK }

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
    val warning: Warning? = null,
) {
    val controlsEnabled: Boolean get() = status == ConnectionStatus.CONNECTED

    companion object {
        fun from(
            wifiAvailable: Boolean,
            link: LinkState,
            telemetry: Telemetry,
            videoActive: Boolean,
            fastMode: Boolean,
            knownFlying: Boolean,
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
                warning = when {
                    flight?.batteryCritical == true -> Warning.BATTERY_CRITICAL
                    flight?.batteryLow == true -> Warning.BATTERY_LOW
                    live && telemetry.tooDark -> Warning.TOO_DARK
                    else -> null
                },
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
    /** Fingers lifted / app paused: sticks back to neutral. */
    data object ReleaseSticks : FlightAction
}
