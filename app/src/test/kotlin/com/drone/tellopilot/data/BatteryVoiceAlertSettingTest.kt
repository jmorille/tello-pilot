package com.drone.tellopilot.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.drone.tello.control.PilotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryVoiceAlertSettingTest {

    @Test
    fun `battery voice alert is on by default`() {
        assertTrue(PilotSettings().batteryVoiceAlert)
        assertTrue(SettingsCodec.readPilot(emptyPreferences()).batteryVoiceAlert)
    }

    @Test
    fun `battery voice alert survives a write and read back`() {
        listOf(false, true).forEach { enabled ->
            val custom = PilotSettings(batteryVoiceAlert = enabled)
            val prefs = mutablePreferencesOf()
            SettingsCodec.writePilot(prefs, custom)
            assertEquals(custom, SettingsCodec.readPilot(prefs))
        }
    }

    @Test
    fun `battery voice alert is stored under its own key`() {
        val prefs = mutablePreferencesOf(booleanPreferencesKey("battery_voice_alert") to false)
        assertEquals(false, SettingsCodec.readPilot(prefs).batteryVoiceAlert)
        val written = mutablePreferencesOf()
        SettingsCodec.writePilot(written, PilotSettings(batteryVoiceAlert = false))
        assertEquals(false, written[booleanPreferencesKey("battery_voice_alert")])
    }
}
