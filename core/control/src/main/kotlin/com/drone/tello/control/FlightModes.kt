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
    /** Flips need >= [FlightModeRules.FLIP_MIN_BATTERY] %, the other modes the low-battery threshold. */
    LOW_BATTERY,
    /** Flips and bounce are limited to 4 m. */
    TOO_HIGH,
    WIND,
    PHOTO_IN_PROGRESS,
    /** Calibrations need the drone on the ground, motors stopped. */
    MUST_BE_ON_GROUND,
    BUSY,
    /** Flips: below [FlightModeRules.FLIP_MIN_HEIGHT_METERS]. */
    TOO_LOW,
    /** Flips: the downward camera (VPS) does not hold the position. */
    NO_POSITIONING,
    /** Flips: less than [FlightModeRules.FLIP_MIN_FLYING_MS] since the takeoff. */
    NOT_STABILISED,
    /** Flips: another flip was sent less than [FlightModeRules.FLIP_LOCK_MS] ago. */
    FLIP_LOCKED,
    /** Flips: no flight data for more than [FlightModeRules.FLIP_MAX_TELEMETRY_AGE_MS] (the state is not known). */
    TELEMETRY_STALE,
}

/**
 * What the flip rules need beyond the flight data (kept by the controller and the app).
 *
 * [flyingForMs]: time since the drone was first seen flying (`null`: not flying / unknown);
 * [sinceLastFlipMs]: time since the last flip sent (`null`: none this session); [busy]: a
 * landing, a firmware manoeuvre, an automatic flight or the takeoff height adjustment runs.
 */
data class FlipContext(
    val flyingForMs: Long?,
    val sinceLastFlipMs: Long?,
    val busy: Boolean,
    /** Age of the flight data the decision is based on (`null`: none received, refused as stale). */
    val telemetryAgeMs: Long?,
)

/**
 * Flip state that outlives a [TelloController] (the session creates a new one on every
 * reconnection): last flip sent, stick hold and re-centring. Thread-safe.
 */
class FlipGuard {
    /** Time of the last flip sent (`null`: none). */
    val lastFlipAt = java.util.concurrent.atomic.AtomicReference<Long?>(null)
    /** Neutral sticks are sent until this time (flip in progress). */
    val stickHoldUntil = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE / 2)
    /** After a flip, the pilot's sticks wait to be seen centred before driving again. */
    val sticksNeedRecentre = java.util.concurrent.atomic.AtomicBoolean(false)
}

/**
 * Preconditions of the official app (`FlightModeActivity`): in flight, no strong wind; flips
 * with battery >= 50 % and height <= 4 m; bounce with battery >= threshold and height <= 4 m;
 * smart videos with battery >= threshold and no photo in progress.
 *
 * Flips have stricter rules of our own on top (see [FlipContext]): nothing else running, one
 * flip at a time ([FLIP_LOCK_MS]), the takeoff over ([FLIP_MIN_FLYING_MS]), the position held by
 * the downward camera, and at least [FLIP_MIN_HEIGHT_METERS] of height.
 */
object FlightModeRules {
    /**
     * Official app: 50 %. The app asks for a margin on top: near 50 % the battery sags under
     * the flip's full-power burst and the drone may lack the thrust to catch itself.
     */
    const val FLIP_MIN_BATTERY = 55
    const val MAX_HEIGHT_METERS = 4f

    /**
     * A Tello flip loses about 0.5 to 1 m before the drone recovers (it drops while upside
     * down, and the downward sensors are blind during the rotation), and the height reading
     * itself is noisy over grass or low texture. 2 m keeps at least a metre of margin above
     * the worst loss. The official app has no minimum; this is the app's own rule, after a
     * flip that ended on the ground during a series of flights at 0.6-0.7 m (14 Nov. 2026).
     * An implausible height (negative, NaN) counts as too low.
     */
    const val FLIP_MIN_HEIGHT_METERS = 2f

    /** The takeoff (climb to ~1 m, then settling) must be over before a flip. */
    const val FLIP_MIN_FLYING_MS = 3_000L

    /**
     * A flip and the recovery take about 2 s: a second flip command in the meantime (contact
     * bounce, rolled D-pad press, double tap) would hit a drone that is not level. Any flip,
     * touch or pad, is refused for this long after the last one sent.
     */
    const val FLIP_LOCK_MS = 4_000L

    /** The flight data is sent at 10 Hz: older than this, "flying" and the height are not trusted. */
    const val FLIP_MAX_TELEMETRY_AGE_MS = 500L

    /**
     * From the flip command on, the controller sends neutral sticks (without the fast flag)
     * for this long, whatever the pilot holds: the firmware flies the rotation and the
     * recovery, and a stick command mixed into it (a thumb resting on a pad stick, the fast
     * flag) can tip the drone over as it passes the vertical.
     */
    const val FLIP_STICK_HOLD_MS = 1_500L

    /**
     * After [FLIP_STICK_HOLD_MS], the pilot's sticks only drive again once seen back within
     * this deflection on every axis (re-armed like a pad at connection): a stick held through
     * the flip never jumps into the drone at the end of the hold.
     */
    const val FLIP_RECENTRE_THRESHOLD = 0.05f

    fun check(
        mode: FlightMode,
        flight: FlightData?,
        lowBatteryThreshold: Int,
        photoInProgress: Boolean,
        /** Required for [FlightMode.Flip]: without it a flip is refused ([ModeCheck.TELEMETRY_STALE]). */
        flip: FlipContext? = null,
    ): ModeCheck {
        if (flight == null) return ModeCheck.NOT_CONNECTED
        if (!flight.flying) return ModeCheck.NOT_FLYING
        if (flight.windWarning) return ModeCheck.WIND
        return when (mode) {
            FlightMode.Flip -> checkFlip(flight, flip)
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

    /**
     * Flip rules (flying / wind are checked before): what runs first, then what the pilot must
     * fix (battery, height, ground), the takeoff wait last.
     */
    private fun checkFlip(flight: FlightData, flip: FlipContext?): ModeCheck {
        val since = flip?.sinceLastFlipMs
        val flyingFor = flip?.flyingForMs
        val age = flip?.telemetryAgeMs
        return when {
            age == null || age > FLIP_MAX_TELEMETRY_AGE_MS -> ModeCheck.TELEMETRY_STALE
            flip?.busy == true || flight.isLanding -> ModeCheck.BUSY
            since != null && since < FLIP_LOCK_MS -> ModeCheck.FLIP_LOCKED
            flight.batteryPercent < FLIP_MIN_BATTERY -> ModeCheck.LOW_BATTERY
            // NaN / negative (blind or inverted sensor) fails this comparison too: refused.
            !flight.heightMeters.isFinite() || !(flight.heightMeters >= FLIP_MIN_HEIGHT_METERS) -> ModeCheck.TOO_LOW
            flight.heightMeters > MAX_HEIGHT_METERS -> ModeCheck.TOO_HIGH
            flight.positioningUnavailable || flight.visionSensorError -> ModeCheck.NO_POSITIONING
            flyingFor == null || flyingFor < FLIP_MIN_FLYING_MS -> ModeCheck.NOT_STABILISED
            else -> ModeCheck.OK
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
