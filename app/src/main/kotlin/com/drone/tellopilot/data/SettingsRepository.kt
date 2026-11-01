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
import com.drone.tello.control.DroneSettings
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.PilotSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
            expo = p[EXPO] ?: d.expo,
            joystickSizeDp = p[JOYSTICK_SIZE] ?: d.joystickSizeDp,
            units = p[UNITS].toEnum(d.units),
            confirmTakeoff = p[CONFIRM_TAKEOFF] ?: d.confirmTakeoff,
            autoRecord = p[AUTO_RECORD] ?: d.autoRecord,
            padBindings = GamepadBindings.decode(p[PAD_BINDINGS]),
            videoSharpen = p[VIDEO_SHARPEN] ?: d.videoSharpen,
            videoFit = p[VIDEO_FIT].toEnum(d.videoFit),
            hiddenHud = p[HIDDEN_HUD].orEmpty()
                .mapNotNullTo(mutableSetOf()) { name -> com.drone.tello.control.HudItem.entries.firstOrNull { it.name == name } },
            hideSticksWithPad = p[HIDE_STICKS_WITH_PAD] ?: d.hideSticksWithPad,
            autoTranscode = p[AUTO_TRANSCODE] ?: d.autoTranscode,
            transcodeCodec = p[TRANSCODE_CODEC].toEnum(d.transcodeCodec),
            keepOriginal = p[KEEP_ORIGINAL] ?: d.keepOriginal,
            overlayOnTranscode = p[OVERLAY_ON_TRANSCODE] ?: d.overlayOnTranscode,
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
        )
    }

    fun writePilot(p: MutablePreferences, s: PilotSettings) {
        p[STICK_MODE] = s.stickMode.name
        p[DEAD_ZONE] = s.deadZone
        p[TRIPOD_SCALE] = s.tripodScale
        p[TAKEOFF_HEIGHT] = s.takeoffHeight
        p[EXPO] = s.expo
        p[JOYSTICK_SIZE] = s.joystickSizeDp
        p[UNITS] = s.units.name
        p[CONFIRM_TAKEOFF] = s.confirmTakeoff
        p[AUTO_RECORD] = s.autoRecord
        p[PAD_BINDINGS] = s.padBindings.encode()
        p[VIDEO_SHARPEN] = s.videoSharpen
        p[VIDEO_FIT] = s.videoFit.name
        p[HIDDEN_HUD] = s.hiddenHud.mapTo(mutableSetOf()) { it.name }
        p[HIDE_STICKS_WITH_PAD] = s.hideSticksWithPad
        p[AUTO_TRANSCODE] = s.autoTranscode
        p[TRANSCODE_CODEC] = s.transcodeCodec.name
        p[KEEP_ORIGINAL] = s.keepOriginal
        p[OVERLAY_ON_TRANSCODE] = s.overlayOnTranscode
        p[CUSTOM_MISSIONS] = s.customMissions.joinToString("\n") { it.encode() }
        p[TRACKING_TARGET] = s.trackingTarget.name
        p[GEOFENCE] = s.geofence
        p[GEOFENCE_RADIUS] = s.geofenceRadius
        p[GEOFENCE_MANUAL_ALERT] = s.geofenceManualAlert
        p[IMPACT_DETECTION] = s.impactDetection
        p[MISSION_NOSE_FIRST] = s.missionNoseFirst
        p[MISSION_SPEED_LIMIT] = s.missionSpeedLimit
        p[MISSION_MAX_SPEED] = s.missionMaxSpeed
    }

    fun readDrone(p: Preferences): DroneSettings {
        val d = DroneSettings()
        return DroneSettings(
            maxAltitudeMeters = p[MAX_ALTITUDE] ?: d.maxAltitudeMeters,
            lowBatteryPercent = p[LOW_BATTERY] ?: d.lowBatteryPercent,
            exposureSteps = p[EXPOSURE] ?: d.exposureSteps,
            bitrate = p[BITRATE].toEnum(d.bitrate),
        ).sanitized()
    }

    fun writeDrone(p: MutablePreferences, s: DroneSettings) {
        p[MAX_ALTITUDE] = s.maxAltitudeMeters
        p[LOW_BATTERY] = s.lowBatteryPercent
        p[EXPOSURE] = s.exposureSteps
        p[BITRATE] = s.bitrate.name
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private val STICK_MODE = stringPreferencesKey("stick_mode")
    private val DEAD_ZONE = floatPreferencesKey("dead_zone")
    private val TRIPOD_SCALE = floatPreferencesKey("tripod_scale")
    private val TAKEOFF_HEIGHT = floatPreferencesKey("takeoff_height_m")
    private val EXPO = floatPreferencesKey("expo")
    private val JOYSTICK_SIZE = intPreferencesKey("joystick_size_dp")
    private val UNITS = stringPreferencesKey("units")
    private val CONFIRM_TAKEOFF = booleanPreferencesKey("confirm_takeoff")
    private val AUTO_RECORD = booleanPreferencesKey("auto_record")
    private val PAD_BINDINGS = stringPreferencesKey("pad_bindings")
    private val VIDEO_SHARPEN = booleanPreferencesKey("video_sharpen")
    private val VIDEO_FIT = stringPreferencesKey("video_fit")
    private val HIDDEN_HUD = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_hud")
    private val HIDE_STICKS_WITH_PAD = booleanPreferencesKey("hide_sticks_with_pad")
    private val AUTO_TRANSCODE = booleanPreferencesKey("auto_transcode")
    private val TRANSCODE_CODEC = stringPreferencesKey("transcode_codec")
    private val KEEP_ORIGINAL = booleanPreferencesKey("keep_original")
    private val OVERLAY_ON_TRANSCODE = booleanPreferencesKey("overlay_on_transcode")
    private val CUSTOM_MISSIONS = stringPreferencesKey("custom_missions")
    private val TRACKING_TARGET = stringPreferencesKey("tracking_target")
    private val GEOFENCE = booleanPreferencesKey("geofence")
    private val GEOFENCE_RADIUS = floatPreferencesKey("geofence_radius_m")
    private val GEOFENCE_MANUAL_ALERT = booleanPreferencesKey("geofence_manual_alert")
    private val IMPACT_DETECTION = booleanPreferencesKey("impact_detection")
    private val MISSION_NOSE_FIRST = booleanPreferencesKey("mission_nose_first")
    private val MISSION_SPEED_LIMIT = booleanPreferencesKey("mission_speed_limit")
    private val MISSION_MAX_SPEED = floatPreferencesKey("mission_max_speed_mps")
    private val MAX_ALTITUDE = intPreferencesKey("max_altitude_m")
    private val LOW_BATTERY = intPreferencesKey("low_battery_percent")
    private val EXPOSURE = intPreferencesKey("exposure_steps")
    private val BITRATE = stringPreferencesKey("bitrate")
}
