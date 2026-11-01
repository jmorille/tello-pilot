package com.drone.tellopilot.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.drone.tello.control.DroneSettings
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.HudItem
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.MoveDirection
import com.drone.tello.control.PadBinding
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadCommand
import com.drone.tello.control.BindingResult
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickMode
import com.drone.tello.control.TrackingTarget
import com.drone.tello.control.TranscodeCodec
import com.drone.tello.control.Units
import com.drone.tello.control.VideoBitrate
import com.drone.tello.control.VideoFit
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `empty store gives the defaults`() {
        assertEquals(PilotSettings(), SettingsCodec.readPilot(emptyPreferences()))
        assertEquals(DroneSettings(), SettingsCodec.readDrone(emptyPreferences()))
    }

    @Test
    fun `pilot settings survive a write and read back`() {
        val bindings = (GamepadBindings.Default.assign(PadCommand.PHOTO, PadBinding(PadButton.A)) as BindingResult.Ok).bindings
        val custom = PilotSettings(
            stickMode = StickMode.MODE_1,
            deadZone = 0.12f,
            expo = 0.6f,
            joystickSizeDp = 240,
            units = Units.IMPERIAL,
            confirmTakeoff = false,
            autoRecord = true,
            hideSticksWithPad = false,
            videoSharpen = false,
            videoFit = VideoFit.WIDE,
            hiddenHud = setOf(HudItem.SPEED, HudItem.INFO_ALERTS),
            autoTranscode = false,
            transcodeCodec = TranscodeCodec.AV1,
            keepOriginal = true,
            overlayOnTranscode = true,
            customMissions = listOf(
                Mission("Un", listOf(MissionStep.Climb(1.5f), MissionStep.Move(MoveDirection.LEFT, 2f))),
                Mission("Deux", listOf(MissionStep.Rotate(-90f), MissionStep.Hover(3f))),
            ),
            trackingTarget = TrackingTarget.FACE,
            padBindings = bindings,
        )
        val prefs = mutablePreferencesOf()
        SettingsCodec.writePilot(prefs, custom)
        assertEquals(custom, SettingsCodec.readPilot(prefs))
    }

    @Test
    fun `drone settings survive a write and read back`() {
        val custom = DroneSettings(maxAltitudeMeters = 25, lowBatteryPercent = 30, exposureSteps = -3, bitrate = VideoBitrate.MBPS_2)
        val prefs = mutablePreferencesOf()
        SettingsCodec.writeDrone(prefs, custom)
        assertEquals(custom, SettingsCodec.readDrone(prefs))
    }

    @Test
    fun `out of range drone values are sanitised on read`() {
        val prefs = mutablePreferencesOf(
            intPreferencesKey("max_altitude_m") to 500,
            intPreferencesKey("low_battery_percent") to 5,
            intPreferencesKey("exposure_steps") to 42,
        )
        val read = SettingsCodec.readDrone(prefs)
        assertEquals(DroneSettings.MAX_ALTITUDE_RANGE.last, read.maxAltitudeMeters)
        assertEquals(DroneSettings.LOW_BATTERY_RANGE.first, read.lowBatteryPercent)
        assertEquals(DroneSettings.EXPOSURE_RANGE.last, read.exposureSteps)
    }

    @Test
    fun `unknown or stale stored values fall back to the defaults`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("stick_mode") to "MODE_9",
            stringPreferencesKey("units") to "",
            stringPreferencesKey("bitrate") to "TURBO",
            stringSetPreferencesKey("hidden_hud") to setOf("BATTERY", "REMOVED_ITEM"),
            stringPreferencesKey("custom_missions") to "Ok|H2;C1\ngarbage\n\nBad|X1\nTooFar|MF99",
            stringPreferencesKey("pad_bindings") to "not a binding",
        )
        val pilot = SettingsCodec.readPilot(prefs)
        assertEquals(StickMode.MODE_2, pilot.stickMode)
        assertEquals(Units.METRIC, pilot.units)
        assertEquals(setOf(HudItem.BATTERY), pilot.hiddenHud)
        assertEquals(GamepadBindings.Default, pilot.padBindings)
        assertEquals(VideoBitrate.AUTO, SettingsCodec.readDrone(prefs).bitrate)
        assertEquals(listOf(Mission("Ok", listOf(MissionStep.Hover(2f), MissionStep.Climb(1f)))), pilot.customMissions)
    }

    @Test
    fun `safety options default on, manual bubble alert off`() {
        val d = SettingsCodec.readPilot(emptyPreferences())
        assertEquals(true, d.geofence)
        assertEquals(5f, d.geofenceRadius)
        assertEquals(false, d.geofenceManualAlert)
        assertEquals(true, d.impactDetection)
        assertEquals(true, d.missionNoseFirst)
        assertEquals(true, d.missionSpeedLimit)
        assertEquals(0.5f, d.missionMaxSpeed)
    }

    @Test
    fun `each safety option survives a write and read back independently`() {
        val variants = listOf(
            PilotSettings(geofence = false),
            PilotSettings(geofenceRadius = 12.5f),
            PilotSettings(geofenceManualAlert = true),
            PilotSettings(impactDetection = false),
            PilotSettings(missionNoseFirst = false),
            PilotSettings(missionSpeedLimit = false),
            PilotSettings(missionMaxSpeed = 0.8f),
        )
        variants.forEach { custom ->
            val prefs = mutablePreferencesOf()
            SettingsCodec.writePilot(prefs, custom)
            assertEquals(custom, SettingsCodec.readPilot(prefs))
        }
    }

    @Test
    fun `out of range safety values are clamped on read`() {
        val prefs = mutablePreferencesOf(
            floatPreferencesKey("geofence_radius_m") to 500f,
            floatPreferencesKey("mission_max_speed_mps") to 0f,
        )
        val read = SettingsCodec.readPilot(prefs)
        assertEquals(PilotSettings.GEOFENCE_RADIUS_RANGE.endInclusive, read.geofenceRadius)
        assertEquals(PilotSettings.MISSION_SPEED_RANGE.start, read.missionMaxSpeed)
        val nan = mutablePreferencesOf(floatPreferencesKey("geofence_radius_m") to Float.NaN)
        assertEquals(PilotSettings.GEOFENCE_RADIUS_DEFAULT, SettingsCodec.readPilot(nan).geofenceRadius)
    }

    @Test
    fun `no custom mission is stored as an empty list`() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.writePilot(prefs, PilotSettings(customMissions = emptyList()))
        assertEquals(emptyList<Mission>(), SettingsCodec.readPilot(prefs).customMissions)
    }
}
