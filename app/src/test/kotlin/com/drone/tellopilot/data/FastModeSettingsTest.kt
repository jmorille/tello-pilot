package com.drone.tellopilot.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.drone.tello.control.DroneSettings
import com.drone.tello.control.FastModeStartup
import com.drone.tello.control.PilotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast-mode max tilt (drone setting) and fast mode at launch (pilot setting + last state). */
class FastModeSettingsTest {

    private val maxTiltKey = floatPreferencesKey("max_tilt_deg")
    private val startupKey = stringPreferencesKey("fast_mode_startup")
    private val lastKey = booleanPreferencesKey("last_fast_mode")

    private fun tiltStored(v: Float) = SettingsCodec.readDrone(mutablePreferencesOf(maxTiltKey to v)).maxTiltDeg

    @Test
    fun `defaults follow the official app and favour safety`() {
        assertEquals(25f, SettingsCodec.readDrone(emptyPreferences()).maxTiltDeg)
        assertEquals(FastModeStartup.ALWAYS_NORMAL, SettingsCodec.readPilot(emptyPreferences()).fastModeStartup)
        assertFalse(SettingsCodec.initialFastMode(emptyPreferences()))
    }

    @Test
    fun `max tilt round-trips`() {
        val p = mutablePreferencesOf()
        SettingsCodec.writeDrone(p, DroneSettings(maxTiltDeg = 18f))
        assertEquals(18f, p[maxTiltKey])
        assertEquals(18f, SettingsCodec.readDrone(p).maxTiltDeg)
    }

    @Test
    fun `max tilt is bounded to 15-25 whole degrees, NaN and infinities give the default`() {
        assertEquals(15f, tiltStored(15f))
        assertEquals(25f, tiltStored(25f))
        assertEquals(15f, tiltStored(3f))
        assertEquals(25f, tiltStored(40f))
        assertEquals(17f, tiltStored(17.4f))
        assertEquals(18f, tiltStored(17.6f))
        assertEquals(25f, tiltStored(Float.NaN))
        assertEquals(25f, tiltStored(Float.POSITIVE_INFINITY))
        assertEquals(25f, tiltStored(Float.NEGATIVE_INFINITY))
        assertEquals(15f, tiltStored(-20f))
        assertEquals(25f, DroneSettings(maxTiltDeg = Float.NaN).sanitized().maxTiltDeg)
        assertEquals(DroneSettings.MAX_TILT_RANGE, 15f..25f)
    }

    @Test
    fun `an invalid max tilt is never written`() {
        val p = mutablePreferencesOf()
        SettingsCodec.writeDrone(p, DroneSettings(maxTiltDeg = Float.NaN))
        assertEquals(25f, p[maxTiltKey])
        SettingsCodec.writeDrone(p, DroneSettings(maxTiltDeg = 99f))
        assertEquals(25f, p[maxTiltKey])
    }

    @Test
    fun `start option round-trips, unknown values give the safe default`() {
        val p = mutablePreferencesOf()
        SettingsCodec.writePilot(p, PilotSettings(fastModeStartup = FastModeStartup.REMEMBER))
        assertEquals("REMEMBER", p[startupKey])
        assertEquals(FastModeStartup.REMEMBER, SettingsCodec.readPilot(p).fastModeStartup)
        p[startupKey] = "SOMETHING_ELSE"
        assertEquals(FastModeStartup.ALWAYS_NORMAL, SettingsCodec.readPilot(p).fastModeStartup)
    }

    @Test
    fun `the last fast mode is used only when the pilot chose to remember it`() {
        val p = mutablePreferencesOf()
        SettingsCodec.writeLastFastMode(p, true)
        assertEquals(true, p[lastKey])
        assertFalse("ALWAYS_NORMAL by default", SettingsCodec.initialFastMode(p))

        p[startupKey] = FastModeStartup.REMEMBER.name
        assertTrue(SettingsCodec.initialFastMode(p))

        SettingsCodec.writeLastFastMode(p, false)
        assertFalse(SettingsCodec.initialFastMode(p))

        p.remove(lastKey)
        assertFalse("never stored: normal", SettingsCodec.initialFastMode(p))

        SettingsCodec.writeLastFastMode(p, true)
        p[startupKey] = FastModeStartup.ALWAYS_NORMAL.name
        assertFalse(SettingsCodec.initialFastMode(p))
    }

    @Test
    fun `saving the pilot settings keeps the remembered fast mode`() {
        val p = mutablePreferencesOf()
        SettingsCodec.writeLastFastMode(p, true)
        SettingsCodec.writePilot(p, PilotSettings(fastModeStartup = FastModeStartup.REMEMBER))
        assertTrue(SettingsCodec.initialFastMode(p))
    }
}
