package com.drone.tellopilot.data

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.drone.tello.control.AxisTuning
import com.drone.tello.control.DroneSettings
import com.drone.tello.control.FlightTuning
import com.drone.tello.control.HudItem
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickAxis
import com.drone.tello.control.StickMode
import com.drone.tello.control.TrackingTarget
import com.drone.tello.control.TranscodeCodec
import com.drone.tello.control.Units
import com.drone.tello.control.VideoBitrate
import com.drone.tello.control.VideoFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-field codec behaviour not covered by [SettingsCodecTest]: bounds and missing values. */
class SettingsBoundsTest {

    private val customPilot = PilotSettings(
        stickMode = StickMode.MODE_4, deadZone = 0.1f,
        axisNormal = FlightTuning(pitch = AxisTuning(1.4f, 0.9f, 0.15f), roll = AxisTuning(0.6f, 0.5f, 0.8f)),
        axisFast = FlightTuning.uniform(0.5f).copy(yaw = AxisTuning(2f, 0.2f, 0f)), tripodScale = 0.45f, joystickSizeDp = 260,
        units = Units.IMPERIAL, confirmTakeoff = false, takeoffHeight = 2.5f, autoRecord = true,
        hideSticksWithPad = false, videoSharpen = false, videoFit = VideoFit.STRETCH_WIDE, hiddenHud = setOf(HudItem.MAP),
        autoTranscode = false, transcodeCodec = TranscodeCodec.AV1, keepOriginal = true, overlayOnTranscode = true,
        trackingTarget = TrackingTarget.FACE,
    )
    private val customDrone = DroneSettings(maxAltitudeMeters = 3, lowBatteryPercent = 45, exposureSteps = 9, bitrate = VideoBitrate.MBPS_4)

    private fun pilotWith(vararg pairs: androidx.datastore.preferences.core.Preferences.Pair<*>) =
        SettingsCodec.readPilot(mutablePreferencesOf(*pairs))

    @Test
    fun `tripod scale is clamped to its range`() {
        assertEquals(PilotSettings.TRIPOD_RANGE.start, pilotWith(floatPreferencesKey("tripod_scale") to 0.01f).tripodScale)
        assertEquals(PilotSettings.TRIPOD_RANGE.endInclusive, pilotWith(floatPreferencesKey("tripod_scale") to 2f).tripodScale)
        assertEquals(0.3f, pilotWith(floatPreferencesKey("tripod_scale") to 0.3f).tripodScale)
    }

    @Test
    fun `takeoff height is off at or below zero, else clamped to its range`() {
        fun read(v: Float) = pilotWith(floatPreferencesKey("takeoff_height_m") to v).takeoffHeight
        assertEquals(0f, read(-2f))
        assertEquals(0f, read(0f))
        assertEquals(PilotSettings.TAKEOFF_HEIGHT_RANGE.start, read(0.1f)) // never a takeoff to the ground
        assertEquals(PilotSettings.TAKEOFF_HEIGHT_RANGE.endInclusive, read(50f))
        assertEquals(1.5f, read(1.5f))
    }

    @Test
    fun `out of range shaping values still give a valid stick tuning`() {
        val wild = pilotWith(floatPreferencesKey("dead_zone") to 3f, floatPreferencesKey("expo") to -1f).stickTuning
        assertEquals(0.5f, wild.deadZone)
        assertEquals(FlightTuning.uniform(0f), wild.normal) // former global expo, clamped
        assertEquals(FlightTuning.uniform(0f), wild.fast)
        val negative = pilotWith(floatPreferencesKey("dead_zone") to -0.5f, floatPreferencesKey("expo") to 9f).stickTuning
        assertEquals(0f, negative.deadZone)
        assertEquals(FlightTuning.uniform(AxisTuning.EXPO_RANGE.endInclusive), negative.normal)
        assertEquals(0.05f, pilotWith(floatPreferencesKey("dead_zone") to Float.NaN).stickTuning.deadZone)
    }

    @Test
    fun `an older store seeds every axis of both modes with its global expo, nothing else changes`() {
        val old = pilotWith(floatPreferencesKey("expo") to 0.55f, floatPreferencesKey("dead_zone") to 0.08f)
        assertEquals(FlightTuning.uniform(0.55f), old.axisNormal)
        assertEquals(FlightTuning.uniform(0.55f), old.axisFast)
        // Same commands as the former shaping (dead zone + that expo) on every axis.
        val former = com.drone.tello.control.StickShaping(0.08f, 0.55f)
        for (i in -100..100) {
            val x = i / 100f
            StickAxis.entries.forEach { a ->
                assertEquals(former.apply(x), old.stickTuning.normal[a].apply(x, old.stickTuning.deadZone), 0f)
                assertEquals(former.apply(x), old.stickTuning.fast[a].apply(x, old.stickTuning.deadZone), 0f)
            }
        }
        // No stored expo at all: the former default (0.3).
        assertEquals(FlightTuning.Default, pilotWith().axisNormal)
        assertEquals(FlightTuning.uniform(0.3f), pilotWith(floatPreferencesKey("expo") to Float.NaN).axisFast)
    }

    @Test
    fun `per axis values win over the former expo and survive the codec`() {
        val p = mutablePreferencesOf(floatPreferencesKey("expo") to 0.55f)
        SettingsCodec.writePilot(p, PilotSettings(axisNormal = FlightTuning(pitch = AxisTuning(1.4f, 1f, 0.15f))))
        val read = SettingsCodec.readPilot(p)
        assertEquals(AxisTuning(1.4f, 1f, 0.15f), read.axisNormal.pitch)
        assertEquals(AxisTuning.Default, read.axisNormal.yaw)
        assertEquals(FlightTuning.Default, read.axisFast)
        assertEquals(1.4f, p[floatPreferencesKey("axis_normal_pitch_gain")])
        // Only the missing expo of one axis falls back to the former global expo.
        p.remove(floatPreferencesKey("axis_fast_yaw_expo"))
        assertEquals(AxisTuning(expo = 0.55f), SettingsCodec.readPilot(p).axisFast.yaw)
        assertEquals(AxisTuning.Default, SettingsCodec.readPilot(p).axisFast.roll)
    }

    @Test
    fun `corrupt or out of range axis values are bounded`() {
        val read = pilotWith(
            floatPreferencesKey("expo") to 0.4f,
            floatPreferencesKey("axis_normal_pitch_gain") to 50f,
            floatPreferencesKey("axis_normal_pitch_amplitude") to 0f,
            floatPreferencesKey("axis_normal_pitch_expo") to -2f,
            floatPreferencesKey("axis_fast_roll_gain") to Float.NaN,
            floatPreferencesKey("axis_fast_roll_amplitude") to Float.NaN,
            floatPreferencesKey("axis_fast_roll_expo") to Float.NaN,
            floatPreferencesKey("axis_fast_yaw_gain") to Float.NEGATIVE_INFINITY,
            floatPreferencesKey("axis_fast_yaw_amplitude") to Float.POSITIVE_INFINITY,
            floatPreferencesKey("axis_fast_yaw_expo") to 3f,
        )
        assertEquals(AxisTuning(gain = 2f, amplitude = 0.2f, expo = 0f), read.axisNormal.pitch)
        assertEquals(AxisTuning(gain = 1f, amplitude = 1f, expo = 0.4f), read.axisFast.roll)
        assertEquals(AxisTuning(gain = 0.5f, amplitude = 1f, expo = 0.8f), read.axisFast.yaw)
        // Even values never written by the codec give a valid tuning (sanitized on use).
        val hand = PilotSettings(axisFast = FlightTuning(pitch = AxisTuning(Float.NaN, 9f, -1f))).stickTuning.fast.pitch
        assertEquals(AxisTuning(1f, 1f, 0f), hand)
    }

    @Test
    fun `every drone setting is clamped on both sides`() {
        fun read(vararg pairs: androidx.datastore.preferences.core.Preferences.Pair<*>) = SettingsCodec.readDrone(mutablePreferencesOf(*pairs))
        assertEquals(2, read(intPreferencesKey("max_altitude_m") to 0).maxAltitudeMeters)
        assertEquals(30, read(intPreferencesKey("max_altitude_m") to 31).maxAltitudeMeters)
        // The firmware refuses a threshold under 15 %.
        assertEquals(15, read(intPreferencesKey("low_battery_percent") to 10).lowBatteryPercent)
        assertEquals(50, read(intPreferencesKey("low_battery_percent") to 99).lowBatteryPercent)
        assertEquals(-9, read(intPreferencesKey("exposure_steps") to -100).exposureSteps)
        assertEquals(9, read(intPreferencesKey("exposure_steps") to 10).exposureSteps)
    }

    @Test
    fun `every bitrate and stick mode survives the codec`() {
        VideoBitrate.entries.forEach { b ->
            val p = mutablePreferencesOf()
            SettingsCodec.writeDrone(p, DroneSettings(bitrate = b))
            assertEquals(b, SettingsCodec.readDrone(p).bitrate)
        }
        StickMode.entries.forEach { m ->
            assertEquals(m, pilotWith(stringPreferencesKey("stick_mode") to m.name).stickMode)
        }
    }

    @Test
    fun `a missing pilot value falls back to its default, the others are kept`() {
        val full = mutablePreferencesOf().also { SettingsCodec.writePilot(it, customPilot) }
        val defaults = mutablePreferencesOf().also { SettingsCodec.writePilot(it, PilotSettings()) }.asMap()
        assertTrue(full.asMap().size >= 19)
        full.asMap().keys.forEach { key ->
            val partial = full.toMutablePreferences().apply { remove(key) }
            val written = mutablePreferencesOf().also { SettingsCodec.writePilot(it, SettingsCodec.readPilot(partial)) }.asMap()
            full.asMap().keys.forEach { k ->
                val expected = if (k == key) defaults[k] else full.asMap()[k]
                assertEquals("without ${key.name}: ${k.name}", expected, written[k])
            }
        }
    }

    @Test
    fun `a missing drone value falls back to its default, the others are kept`() {
        val full = mutablePreferencesOf().also { SettingsCodec.writeDrone(it, customDrone) }
        assertEquals(DroneSettings(maxAltitudeMeters = 10, lowBatteryPercent = 45, exposureSteps = 9, bitrate = VideoBitrate.MBPS_4),
            SettingsCodec.readDrone(full.toMutablePreferences().apply { remove(intPreferencesKey("max_altitude_m")) }))
        assertEquals(customDrone.copy(lowBatteryPercent = 20),
            SettingsCodec.readDrone(full.toMutablePreferences().apply { remove(intPreferencesKey("low_battery_percent")) }))
        assertEquals(customDrone.copy(exposureSteps = 0),
            SettingsCodec.readDrone(full.toMutablePreferences().apply { remove(intPreferencesKey("exposure_steps")) }))
        assertEquals(customDrone.copy(bitrate = VideoBitrate.AUTO),
            SettingsCodec.readDrone(full.toMutablePreferences().apply { remove(stringPreferencesKey("bitrate")) }))
    }
}
