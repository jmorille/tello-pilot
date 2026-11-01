package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.ImuCalibrationStatus
import com.drone.tello.protocol.ImuFace
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.SmartVideoStatus

/** Firmware-executed manoeuvres (one command each). */
sealed interface FlightMode {
    data object Flip : FlightMode
    data object Bounce : FlightMode
    data class SmartVideo(val mode: SmartVideoMode) : FlightMode
}

/** Why a manoeuvre was refused before sending anything to the drone. */
enum class ModeCheck {
    OK,
    NOT_CONNECTED,
    NOT_FLYING,
    /** Flips need >= 50 %, the other modes the low-battery threshold. */
    LOW_BATTERY,
    /** Flips and bounce are limited to 4 m. */
    TOO_HIGH,
    WIND,
    PHOTO_IN_PROGRESS,
    /** Calibrations need the drone on the ground, motors stopped. */
    MUST_BE_ON_GROUND,
    BUSY,
}

/**
 * Preconditions of the official app (`FlightModeActivity`): in flight, no strong wind; flips
 * with battery >= 50 % and height <= 4 m; bounce with battery >= threshold and height <= 4 m;
 * smart videos with battery >= threshold and no photo in progress.
 */
object FlightModeRules {
    const val FLIP_MIN_BATTERY = 50
    const val MAX_HEIGHT_METERS = 4f

    fun check(mode: FlightMode, flight: FlightData?, lowBatteryThreshold: Int, photoInProgress: Boolean): ModeCheck {
        if (flight == null) return ModeCheck.NOT_CONNECTED
        if (!flight.flying) return ModeCheck.NOT_FLYING
        if (flight.windWarning) return ModeCheck.WIND
        return when (mode) {
            FlightMode.Flip -> when {
                flight.batteryPercent < FLIP_MIN_BATTERY -> ModeCheck.LOW_BATTERY
                flight.heightMeters > MAX_HEIGHT_METERS -> ModeCheck.TOO_HIGH
                else -> ModeCheck.OK
            }
            FlightMode.Bounce -> when {
                flight.batteryPercent < lowBatteryThreshold -> ModeCheck.LOW_BATTERY
                flight.heightMeters > MAX_HEIGHT_METERS -> ModeCheck.TOO_HIGH
                else -> ModeCheck.OK
            }
            is FlightMode.SmartVideo -> when {
                flight.batteryPercent < lowBatteryThreshold -> ModeCheck.LOW_BATTERY
                photoInProgress -> ModeCheck.PHOTO_IN_PROGRESS
                else -> ModeCheck.OK
            }
        }
    }

    /** Calibrations: on the ground with motors stopped. */
    fun checkCalibration(flight: FlightData?): ModeCheck = when {
        flight == null -> ModeCheck.NOT_CONNECTED
        flight.flying || flight.motorsOn -> ModeCheck.MUST_BE_ON_GROUND
        else -> ModeCheck.OK
    }
}

/** State of the firmware manoeuvres, as known by the app. */
data class ManeuverState(
    val bounce: Boolean = false,
    val smartVideo: SmartVideoStatus? = null,
    /** Smart video requested by the app and not yet known to be finished. */
    val requestedSmartVideo: SmartVideoMode? = null,
) {
    val smartVideoRunning: Boolean get() = smartVideo?.running == true

    /** The pilot must be offered "Stop" (requested, or reported running). */
    val smartVideoActive: Boolean get() = smartVideoRunning || requestedSmartVideo != null
}

/** IMU calibration as seen by the UI. */
sealed interface ImuCalibrationState {
    data object Idle : ImuCalibrationState
    /** Command 90 sent, waiting for the drone to accept it. */
    data object Starting : ImuCalibrationState
    data class Running(val doneFaces: Set<ImuFace>, val progress: Int) : ImuCalibrationState {
        /** Face the pilot must place the drone on next (official order). */
        val nextFace: ImuFace? get() = ImuFace.entries.firstOrNull { it !in doneFaces }
    }
    data object Succeeded : ImuCalibrationState
    data class Failed(val reason: Reason) : ImuCalibrationState
    enum class Reason { REFUSED, TIMEOUT, LINK_LOST }
}

/**
 * IMU calibration flow (official `IMUCalibrationActivity`): 90 starts it, then 91 is polled every
 * 100 ms (never retried) until the six faces are reported done. Pure: the controller drives it.
 */
class ImuCalibration(private val timeoutMs: Long = 60_000) {
    var state: ImuCalibrationState = ImuCalibrationState.Idle
        private set
    private var startedAt = 0L

    val polling: Boolean get() = state is ImuCalibrationState.Running

    fun start(now: Long): Boolean {
        if (state is ImuCalibrationState.Starting || state is ImuCalibrationState.Running) return false
        state = ImuCalibrationState.Starting
        startedAt = now
        return true
    }

    /** Timeout counted from the start or from the last progress (see [onStatus]). */
    fun onStartResult(success: Boolean) {
        if (state != ImuCalibrationState.Starting) return
        state = if (success) ImuCalibrationState.Running(emptySet(), 0)
        else ImuCalibrationState.Failed(ImuCalibrationState.Reason.REFUSED)
    }

    /**
     * Faces are accumulated over the run (as the official app does: a face once seen stays done)
     * and the timeout restarts on every progress, so a slow pilot is never cut off mid-way.
     */
    fun onStatus(status: ImuCalibrationStatus, now: Long) {
        val running = state as? ImuCalibrationState.Running ?: return
        val faces = running.doneFaces + status.doneFaces
        if (faces != running.doneFaces || status.progress != running.progress) startedAt = now
        state = if (faces.size == ImuFace.entries.size) ImuCalibrationState.Succeeded
        else ImuCalibrationState.Running(faces, status.progress)
    }

    fun onTick(now: Long) {
        if ((state is ImuCalibrationState.Starting || state is ImuCalibrationState.Running) && now - startedAt > timeoutMs) {
            state = ImuCalibrationState.Failed(ImuCalibrationState.Reason.TIMEOUT)
        }
    }

    fun onLinkLost() {
        if (state is ImuCalibrationState.Starting || state is ImuCalibrationState.Running) {
            state = ImuCalibrationState.Failed(ImuCalibrationState.Reason.LINK_LOST)
        }
    }

    fun reset() {
        state = ImuCalibrationState.Idle
    }
}

/** Change of the drone's Wi-Fi name / password. */
sealed interface WifiChangeState {
    data object Idle : WifiChangeState
    data object Sending : WifiChangeState
    /** Stored and Wi-Fi restarted: the phone must now join [ssid]. */
    data class Applied(val ssid: String) : WifiChangeState
    /** Refused or no answer: the drone may have stored part of it (applied at the next reboot). */
    data object Failed : WifiChangeState
}

/**
 * Official flow (`widgets/i.java`): send SSID (18) and password (20); on the first success reply,
 * restart the drone's Wi-Fi (25) once so the new settings take effect.
 */
class WifiChange {
    var state: WifiChangeState = WifiChangeState.Idle
        private set
    private var ssid = ""
    private var startedAt = 0L
    private var ssidOk: Boolean? = null
    private var passwordOk: Boolean? = null
    private var restartSent = false

    fun start(ssid: String, now: Long): Boolean {
        if (state == WifiChangeState.Sending) return false
        this.ssid = ssid
        startedAt = now
        ssidOk = null
        passwordOk = null
        restartSent = false
        state = WifiChangeState.Sending
        return true
    }

    /** Returns `true` when the Wi-Fi restart must be sent now. */
    fun onResult(ssidReply: Boolean, success: Boolean): Boolean {
        if (state != WifiChangeState.Sending) return false
        if (ssidReply) ssidOk = success else passwordOk = success
        if (ssidOk == false || passwordOk == false) {
            // Restart only when both were stored: a half-applied change would lock the pilot out.
            state = WifiChangeState.Failed
            return false
        }
        if (ssidOk == true && passwordOk == true && !restartSent) {
            restartSent = true
            state = WifiChangeState.Applied(ssid)
            return true
        }
        return false
    }

    /** Returns `true` when the change just timed out (→ [WifiChangeState.Failed]). */
    fun onTick(now: Long, timeoutMs: Long): Boolean {
        if (state != WifiChangeState.Sending || now - startedAt <= timeoutMs) return false
        state = WifiChangeState.Failed
        return true
    }

    fun reset() {
        if (state != WifiChangeState.Sending) state = WifiChangeState.Idle
    }
}
