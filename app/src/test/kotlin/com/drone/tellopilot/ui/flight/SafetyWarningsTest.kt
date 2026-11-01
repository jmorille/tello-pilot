package com.drone.tellopilot.ui.flight

import com.drone.tello.control.HudItem
import com.drone.tello.control.LinkState
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.FlightData
import com.drone.tellopilot.data.PhoneHeat
import org.junit.Assert.assertEquals
import org.junit.Test

/** Alerts of the app's own safety checks (shock, bubble) in [FlightUiState]. */
class SafetyWarningsTest {

    private val air = FlightData(
        heightMeters = 2f, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 12f,
        batteryPercent = 60, flying = true, onGround = false, hovering = true,
        batteryLow = true, batteryCritical = false, flyMode = 6, windWarning = true,
    )

    private fun state(safety: Set<Warning>, pilot: PilotSettings = PilotSettings(), heat: PhoneHeat = PhoneHeat.NORMAL) =
        FlightUiState.from(
            wifiAvailable = true, link = LinkState.CONNECTED, telemetry = Telemetry(flight = air),
            videoActive = true, fastMode = false, knownFlying = true, pilot = pilot, phoneHeat = heat,
            safetyWarnings = safety,
        )

    @Test
    fun `no safety alert keeps the drone warnings unchanged`() {
        assertEquals(listOf(Warning.BATTERY_LOW, Warning.WIND), state(emptySet()).warnings)
    }

    @Test
    fun `a shock comes first, bubble alerts in their declared place`() {
        val s = state(setOf(Warning.GEOFENCE_OUT, Warning.IMPACT), heat = PhoneHeat.WARM)
        assertEquals(
            listOf(Warning.IMPACT, Warning.BATTERY_LOW, Warning.GEOFENCE_OUT, Warning.WIND, Warning.PHONE_HOT),
            s.warnings,
        )
        assertEquals(Warning.IMPACT, s.warning)
    }

    @Test
    fun `bubble alerts stay visible when information alerts are hidden`() {
        val s = state(setOf(Warning.GEOFENCE_NEAR), pilot = PilotSettings(hiddenHud = setOf(HudItem.INFO_ALERTS)))
        assertEquals(listOf(Warning.BATTERY_LOW, Warning.GEOFENCE_NEAR), s.shownWarnings)
    }
}
