package com.drone.tellopilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import java.io.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.drone.tello.control.AxisTuning
import com.drone.tello.control.DroneSettings
import com.drone.tello.control.FastModeStartup
import com.drone.tello.control.FlightTuning
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.HorizonAnchor
import com.drone.tello.control.HorizonPlacement
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickAxis
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val pilot: PilotSettings = PilotSettings(),
    val drone: DroneSettings = DroneSettings(),
)

// A corrupt file falls back to defaults instead of making every read throw.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Persists [AppSettings] with Jetpack DataStore (asynchronous, transactional, no main-thread I/O). */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val store = context.dataStore

    /** Never fails: an unreadable store yields the default settings. */
    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { AppSettings(SettingsCodec.readPilot(it), SettingsCodec.readDrone(it)) }
        .distinctUntilChanged()

    suspend fun updatePilot(transform: (PilotSettings) -> PilotSettings) {
        store.edit { p -> SettingsCodec.writePilot(p, transform(SettingsCodec.readPilot(p))) }
    }

    suspend fun updateDrone(transform: (DroneSettings) -> DroneSettings) {
        store.edit { p -> SettingsCodec.writeDrone(p, transform(SettingsCodec.readDrone(p)).sanitized()) }
    }

    /** Fast mode to start the flight screen with ([PilotSettings.fastModeStartup]); `false` if unreadable. */
    suspend fun initialFastMode(): Boolean = SettingsCodec.initialFastMode(settingsPreferences().first())

    /** Remembers the current fast mode for the next launch (always stored, used only with REMEMBER). */
    suspend fun rememberFastMode(on: Boolean) {
        store.edit { p -> SettingsCodec.writeLastFastMode(p, on) }
    }

    private fun settingsPreferences(): Flow<Preferences> =
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
}

/** Mapping between the settings and their DataStore preferences (pure, no I/O). */
internal object SettingsCodec {

    fun readPilot(p: Preferences): PilotSettings {
        val d = PilotSettings()
        return PilotSettings(
            stickMode = p[STICK_MODE].toEnum(d.stickMode),
            deadZone = p[DEAD_ZONE] ?: d.deadZone,
            tripodScale = (p[TRIPOD_SCALE] ?: d.tripodScale).coerceIn(PilotSettings.TRIPOD_RANGE),
            takeoffHeight = (p[TAKEOFF_HEIGHT] ?: d.takeoffHeight).let {
                if (it <= 0f) 0f else it.coerceIn(PilotSettings.TAKEOFF_HEIGHT_RANGE)
            },
            axisNormal = readAxes(p, fast = false),
            axisFast = readAxes(p, fast = true),
            joystickSizeDp = p[JOYSTICK_SIZE] ?: d.joystickSizeDp,
            units = p[UNITS].toEnum(d.units),
            confirmTakeoff = p[CONFIRM_TAKEOFF] ?: d.confirmTakeoff,
            autoRecord = p[AUTO_RECORD] ?: d.autoRecord,
            padBindings = GamepadBindings.decode(p[PAD_BINDINGS], p[PAD_SHORTCUTS]),
            videoSharpen = p[VIDEO_SHARPEN] ?: d.videoSharpen,
            videoFit = p[VIDEO_FIT].toEnum(d.videoFit),
            hiddenHud = p[HIDDEN_HUD].orEmpty()
                .mapNotNullTo(mutableSetOf()) { name -> com.drone.tello.control.HudItem.entries.firstOrNull { it.name == name } },
            horizon = readHorizon(p),
            hideSticksWithPad = p[HIDE_STICKS_WITH_PAD] ?: d.hideSticksWithPad,
            autoTranscode = p[AUTO_TRANSCODE] ?: d.autoTranscode,
            transcodeCodec = p[TRANSCODE_CODEC].toEnum(d.transcodeCodec),
            keepOriginal = p[KEEP_ORIGINAL] ?: d.keepOriginal,
            overlayOnTranscode = p[OVERLAY_ON_TRANSCODE] ?: d.overlayOnTranscode,
            overlayStyle = p[OVERLAY_STYLE].toEnum(d.overlayStyle),
            customMissions = p[CUSTOM_MISSIONS].orEmpty().lines().mapNotNull { com.drone.tello.control.Mission.decode(it) },
            trackingTarget = p[TRACKING_TARGET].toEnum(d.trackingTarget),
            geofence = p[GEOFENCE] ?: d.geofence,
            geofenceRadius = (p[GEOFENCE_RADIUS] ?: d.geofenceRadius).let {
                if (it.isFinite()) it.coerceIn(PilotSettings.GEOFENCE_RADIUS_RANGE) else d.geofenceRadius
            },
            geofenceManualAlert = p[GEOFENCE_MANUAL_ALERT] ?: d.geofenceManualAlert,
            impactDetection = p[IMPACT_DETECTION] ?: d.impactDetection,
            missionNoseFirst = p[MISSION_NOSE_FIRST] ?: d.missionNoseFirst,
            missionSpeedLimit = p[MISSION_SPEED_LIMIT] ?: d.missionSpeedLimit,
            missionMaxSpeed = (p[MISSION_MAX_SPEED] ?: d.missionMaxSpeed).let {
                if (it.isFinite()) it.coerceIn(PilotSettings.MISSION_SPEED_RANGE) else d.missionMaxSpeed
            },
            batteryVoiceAlert = p[BATTERY_VOICE_ALERT] ?: d.batteryVoiceAlert,
            fastModeStartup = p[FAST_MODE_STARTUP].toEnum(d.fastModeStartup),
        )
    }

    fun writePilot(p: MutablePreferences, s: PilotSettings) {
        p[STICK_MODE] = s.stickMode.name
        p[DEAD_ZONE] = s.deadZone
        p[TRIPOD_SCALE] = s.tripodScale
        p[TAKEOFF_HEIGHT] = s.takeoffHeight
        writeAxes(p, fast = false, s.axisNormal)
        writeAxes(p, fast = true, s.axisFast)
        p[JOYSTICK_SIZE] = s.joystickSizeDp
        p[UNITS] = s.units.name
        p[CONFIRM_TAKEOFF] = s.confirmTakeoff
        p[AUTO_RECORD] = s.autoRecord
        p[PAD_BINDINGS] = s.padBindings.encode()
        p[PAD_SHORTCUTS] = s.padBindings.encodeShortcuts()
        p[VIDEO_SHARPEN] = s.videoSharpen
        p[VIDEO_FIT] = s.videoFit.name
        p[HIDDEN_HUD] = s.hiddenHud.mapTo(mutableSetOf()) { it.name }
        writeHorizon(p, s.horizon)
        p[HIDE_STICKS_WITH_PAD] = s.hideSticksWithPad
        p[AUTO_TRANSCODE] = s.autoTranscode
        p[TRANSCODE_CODEC] = s.transcodeCodec.name
        p[KEEP_ORIGINAL] = s.keepOriginal
        p[OVERLAY_ON_TRANSCODE] = s.overlayOnTranscode
        p[OVERLAY_STYLE] = s.overlayStyle.name
        p[CUSTOM_MISSIONS] = s.customMissions.joinToString("\n") { it.encode() }
        p[TRACKING_TARGET] = s.trackingTarget.name
        p[GEOFENCE] = s.geofence
        p[GEOFENCE_RADIUS] = s.geofenceRadius
        p[GEOFENCE_MANUAL_ALERT] = s.geofenceManualAlert
        p[IMPACT_DETECTION] = s.impactDetection
        p[MISSION_NOSE_FIRST] = s.missionNoseFirst
        p[MISSION_SPEED_LIMIT] = s.missionSpeedLimit
        p[MISSION_MAX_SPEED] = s.missionMaxSpeed
        p[BATTERY_VOICE_ALERT] = s.batteryVoiceAlert
        p[FAST_MODE_STARTUP] = s.fastModeStartup.name
    }

    /** Fast mode at launch: the last one only when the pilot chose to remember it. */
    fun initialFastMode(p: Preferences): Boolean =
        p[FAST_MODE_STARTUP].toEnum(PilotSettings().fastModeStartup) == FastModeStartup.REMEMBER && p[LAST_FAST_MODE] == true

    fun writeLastFastMode(p: MutablePreferences, on: Boolean) {
        p[LAST_FAST_MODE] = on
    }

    /**
     * Axis profile of a flight mode, each value bounded (NaN gives the default). Migration: a
     * missing expo takes the former global expo ([EXPO], no longer written) so that an update
     * changes nothing; gain and amplitude default to 100 %, i.e. the historical response.
     */
    private fun readAxes(p: Preferences, fast: Boolean): FlightTuning {
        // Rounded to the 0.05 step of the per-axis sliders (the former slider had no steps).
        val legacyExpo = p[EXPO]?.takeUnless { it.isNaN() }?.coerceIn(AxisTuning.EXPO_RANGE)
            ?.let { (it * 20f).roundToInt() / 20f } ?: AxisTuning.DEFAULT_EXPO
        return StickAxis.entries.fold(FlightTuning.Default) { tuning, axis ->
            val keys = axisKeys(fast, axis)
            tuning.with(
                axis,
                AxisTuning(
                    gain = p[keys.gain] ?: 1f,
                    amplitude = p[keys.amplitude] ?: 1f,
                    expo = p[keys.expo] ?: legacyExpo,
                ).sanitized(defaultExpo = legacyExpo),
            )
        }
    }

    private fun writeAxes(p: MutablePreferences, fast: Boolean, tuning: FlightTuning) {
        StickAxis.entries.forEach { axis ->
            val keys = axisKeys(fast, axis)
            val t = tuning[axis].sanitized()
            p[keys.gain] = t.gain
            p[keys.amplitude] = t.amplitude
            p[keys.expo] = t.expo
        }
    }

    private class AxisKeys(val gain: Preferences.Key<Float>, val amplitude: Preferences.Key<Float>, val expo: Preferences.Key<Float>)

    /** `axis_<normal|fast>_<pitch|roll|yaw|throttle>_<gain|amplitude|expo>`. */
    private fun axisKeys(fast: Boolean, axis: StickAxis): AxisKeys {
        val prefix = "axis_${if (fast) "fast" else "normal"}_${axis.name.lowercase()}_"
        return AxisKeys(floatPreferencesKey(prefix + "gain"), floatPreferencesKey(prefix + "amplitude"), floatPreferencesKey(prefix + "expo"))
    }

    /** Snapped place by name, [HORIZON_FREE] for a free one (then its fractions). Unknown or corrupt: the default. */
    private fun readHorizon(p: Preferences): HorizonPlacement {
        val d = HorizonPlacement.Default
        val anchor = when (val name = p[HORIZON_ANCHOR]) {
            null -> return d
            HORIZON_FREE -> null
            else -> HorizonAnchor.entries.firstOrNull { it.name == name } ?: return d
        }
        return HorizonPlacement(anchor, p[HORIZON_X] ?: d.x, p[HORIZON_Y] ?: d.y).sanitized()
    }

    private fun writeHorizon(p: MutablePreferences, h: HorizonPlacement) {
        val s = h.sanitized()
        p[HORIZON_ANCHOR] = s.anchor?.name ?: HORIZON_FREE
        p[HORIZON_X] = s.x
        p[HORIZON_Y] = s.y
    }

    fun readDrone(p: Preferences): DroneSettings {
        val d = DroneSettings()
        return DroneSettings(
            maxAltitudeMeters = p[MAX_ALTITUDE] ?: d.maxAltitudeMeters,
            lowBatteryPercent = p[LOW_BATTERY] ?: d.lowBatteryPercent,
            exposureSteps = p[EXPOSURE] ?: d.exposureSteps,
            bitrate = p[BITRATE].toEnum(d.bitrate),
            maxTiltDeg = p[MAX_TILT] ?: d.maxTiltDeg,
        ).sanitized()
    }

    fun writeDrone(p: MutablePreferences, s: DroneSettings) {
        p[MAX_ALTITUDE] = s.maxAltitudeMeters
        p[LOW_BATTERY] = s.lowBatteryPercent
        p[EXPOSURE] = s.exposureSteps
        p[BITRATE] = s.bitrate.name
        p[MAX_TILT] = DroneSettings.sanitizeMaxTilt(s.maxTiltDeg)
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private val STICK_MODE = stringPreferencesKey("stick_mode")
    private val DEAD_ZONE = floatPreferencesKey("dead_zone")
    private val TRIPOD_SCALE = floatPreferencesKey("tripod_scale")
    private val TAKEOFF_HEIGHT = floatPreferencesKey("takeoff_height_m")
    // Former global expo: only read, to seed the per-axis expo of an older store.
    private val EXPO = floatPreferencesKey("expo")
    private val JOYSTICK_SIZE = intPreferencesKey("joystick_size_dp")
    private val UNITS = stringPreferencesKey("units")
    private val CONFIRM_TAKEOFF = booleanPreferencesKey("confirm_takeoff")
    private val AUTO_RECORD = booleanPreferencesKey("auto_record")
    private val PAD_BINDINGS = stringPreferencesKey("pad_bindings")
    // Separate key: older versions keep reading pad_bindings unchanged.
    private val PAD_SHORTCUTS = stringPreferencesKey("pad_shortcuts")
    private val VIDEO_SHARPEN = booleanPreferencesKey("video_sharpen")
    private val VIDEO_FIT = stringPreferencesKey("video_fit_v2") // v2: the old key held FILL for everyone who saved any setting
    private val HIDDEN_HUD = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_hud")
    private val HORIZON_ANCHOR = stringPreferencesKey("horizon_anchor")
    private val HORIZON_X = floatPreferencesKey("horizon_x")
    private val HORIZON_Y = floatPreferencesKey("horizon_y")
    private const val HORIZON_FREE = "FREE"
    private val HIDE_STICKS_WITH_PAD = booleanPreferencesKey("hide_sticks_with_pad")
    private val AUTO_TRANSCODE = booleanPreferencesKey("auto_transcode")
    private val TRANSCODE_CODEC = stringPreferencesKey("transcode_codec")
    private val KEEP_ORIGINAL = booleanPreferencesKey("keep_original")
    private val OVERLAY_ON_TRANSCODE = booleanPreferencesKey("overlay_on_transcode")
    private val OVERLAY_STYLE = stringPreferencesKey("overlay_style")
    private val CUSTOM_MISSIONS = stringPreferencesKey("custom_missions")
    private val TRACKING_TARGET = stringPreferencesKey("tracking_target")
    private val GEOFENCE = booleanPreferencesKey("geofence")
    private val GEOFENCE_RADIUS = floatPreferencesKey("geofence_radius_m")
    private val GEOFENCE_MANUAL_ALERT = booleanPreferencesKey("geofence_manual_alert")
    private val IMPACT_DETECTION = booleanPreferencesKey("impact_detection")
    private val MISSION_NOSE_FIRST = booleanPreferencesKey("mission_nose_first")
    private val MISSION_SPEED_LIMIT = booleanPreferencesKey("mission_speed_limit")
    private val MISSION_MAX_SPEED = floatPreferencesKey("mission_max_speed_mps")
    private val BATTERY_VOICE_ALERT = booleanPreferencesKey("battery_voice_alert")
    private val MAX_ALTITUDE = intPreferencesKey("max_altitude_m")
    private val LOW_BATTERY = intPreferencesKey("low_battery_percent")
    private val EXPOSURE = intPreferencesKey("exposure_steps")
    private val BITRATE = stringPreferencesKey("bitrate")
    private val MAX_TILT = floatPreferencesKey("max_tilt_deg")
    private val FAST_MODE_STARTUP = stringPreferencesKey("fast_mode_startup")
    // Not part of PilotSettings: written on every toggle, read once at launch.
    private val LAST_FAST_MODE = booleanPreferencesKey("last_fast_mode")
}
