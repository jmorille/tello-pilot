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
        .map { AppSettings(readPilot(it), readDrone(it)) }
        .distinctUntilChanged()

    suspend fun updatePilot(transform: (PilotSettings) -> PilotSettings) {
        store.edit { p -> writePilot(p, transform(readPilot(p))) }
    }

    suspend fun updateDrone(transform: (DroneSettings) -> DroneSettings) {
        store.edit { p -> writeDrone(p, transform(readDrone(p)).sanitized()) }
    }

    private fun readPilot(p: Preferences): PilotSettings {
        val d = PilotSettings()
        return PilotSettings(
            stickMode = p[STICK_MODE].toEnum(d.stickMode),
            deadZone = p[DEAD_ZONE] ?: d.deadZone,
            expo = p[EXPO] ?: d.expo,
            joystickSizeDp = p[JOYSTICK_SIZE] ?: d.joystickSizeDp,
            units = p[UNITS].toEnum(d.units),
            confirmTakeoff = p[CONFIRM_TAKEOFF] ?: d.confirmTakeoff,
            autoRecord = p[AUTO_RECORD] ?: d.autoRecord,
            padBindings = GamepadBindings.decode(p[PAD_BINDINGS]),
            videoSharpen = p[VIDEO_SHARPEN] ?: d.videoSharpen,
            hideSticksWithPad = p[HIDE_STICKS_WITH_PAD] ?: d.hideSticksWithPad,
        )
    }

    private fun writePilot(p: MutablePreferences, s: PilotSettings) {
        p[STICK_MODE] = s.stickMode.name
        p[DEAD_ZONE] = s.deadZone
        p[EXPO] = s.expo
        p[JOYSTICK_SIZE] = s.joystickSizeDp
        p[UNITS] = s.units.name
        p[CONFIRM_TAKEOFF] = s.confirmTakeoff
        p[AUTO_RECORD] = s.autoRecord
        p[PAD_BINDINGS] = s.padBindings.encode()
        p[VIDEO_SHARPEN] = s.videoSharpen
        p[HIDE_STICKS_WITH_PAD] = s.hideSticksWithPad
    }

    private fun readDrone(p: Preferences): DroneSettings {
        val d = DroneSettings()
        return DroneSettings(
            maxAltitudeMeters = p[MAX_ALTITUDE] ?: d.maxAltitudeMeters,
            lowBatteryPercent = p[LOW_BATTERY] ?: d.lowBatteryPercent,
            exposureSteps = p[EXPOSURE] ?: d.exposureSteps,
            bitrate = p[BITRATE].toEnum(d.bitrate),
        ).sanitized()
    }

    private fun writeDrone(p: MutablePreferences, s: DroneSettings) {
        p[MAX_ALTITUDE] = s.maxAltitudeMeters
        p[LOW_BATTERY] = s.lowBatteryPercent
        p[EXPOSURE] = s.exposureSteps
        p[BITRATE] = s.bitrate.name
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val STICK_MODE = stringPreferencesKey("stick_mode")
        val DEAD_ZONE = floatPreferencesKey("dead_zone")
        val EXPO = floatPreferencesKey("expo")
        val JOYSTICK_SIZE = intPreferencesKey("joystick_size_dp")
        val UNITS = stringPreferencesKey("units")
        val CONFIRM_TAKEOFF = booleanPreferencesKey("confirm_takeoff")
        val AUTO_RECORD = booleanPreferencesKey("auto_record")
        val PAD_BINDINGS = stringPreferencesKey("pad_bindings")
        val VIDEO_SHARPEN = booleanPreferencesKey("video_sharpen")
        val HIDE_STICKS_WITH_PAD = booleanPreferencesKey("hide_sticks_with_pad")
        val MAX_ALTITUDE = intPreferencesKey("max_altitude_m")
        val LOW_BATTERY = intPreferencesKey("low_battery_percent")
        val EXPOSURE = intPreferencesKey("exposure_steps")
        val BITRATE = stringPreferencesKey("bitrate")
    }
}
