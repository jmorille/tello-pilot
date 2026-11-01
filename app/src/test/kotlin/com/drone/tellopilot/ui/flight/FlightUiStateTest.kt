package com.drone.tellopilot.ui.flight

import com.drone.tello.control.LinkState
import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.FlightData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightUiStateTest {

    private fun flight(flying: Boolean = true, battery: Int = 80, low: Boolean = false, critical: Boolean = false) = FlightData(
        heightMeters = 1.2f, northSpeed = 3f, eastSpeed = 4f, verticalSpeed = 0f, flyTimeSeconds = 65.4f,
        batteryPercent = battery, flying = flying, onGround = !flying, hovering = false,
        batteryLow = low, batteryCritical = critical, flyMode = 6,
    )

    private fun state(
        wifi: Boolean = true,
        link: LinkState = LinkState.CONNECTED,
        telemetry: Telemetry = Telemetry(flight = flight()),
        knownFlying: Boolean = false,
    ) = FlightUiState.from(wifi, link, telemetry, videoActive = true, fastMode = false, knownFlying = knownFlying)

    @Test
    fun `status follows wifi and link`() {
        assertEquals(ConnectionStatus.WAITING_WIFI, state(wifi = false).status)
        assertEquals(ConnectionStatus.CONNECTING, state(link = LinkState.CONNECTING).status)
        assertEquals(ConnectionStatus.RECONNECTING, state(link = LinkState.RECONNECTING).status)
        assertEquals(ConnectionStatus.CONNECTED, state().status)
    }

    @Test
    fun `controls are enabled only when connected`() {
        assertTrue(state().controlsEnabled)
        assertFalse(state(link = LinkState.RECONNECTING).controlsEnabled)
    }

    @Test
    fun `telemetry is shown when connected`() {
        val s = state()
        assertEquals(80, s.batteryPercent)
        assertEquals(5f, s.horizontalSpeed!!, 1e-4f)
        assertEquals(65, s.flyTimeSeconds)
        assertTrue(s.flying)
    }

    @Test
    fun `land stays reachable when the link is lost in flight`() {
        val s = state(link = LinkState.RECONNECTING, knownFlying = true)
        assertTrue("last known flying state is kept", s.flying)
        assertNull("stale telemetry is hidden", s.batteryPercent)
        assertTrue(state(wifi = false, telemetry = Telemetry(), knownFlying = true).flying)
    }

    @Test
    fun `live telemetry wins over the remembered state`() {
        assertFalse(state(telemetry = Telemetry(flight = flight(flying = false)), knownFlying = true).flying)
    }

    @Test
    fun `battery warnings are prioritised`() {
        assertEquals(Warning.BATTERY_CRITICAL, state(telemetry = Telemetry(flight = flight(low = true, critical = true), tooDark = true)).warning)
        assertEquals(Warning.BATTERY_LOW, state(telemetry = Telemetry(flight = flight(low = true))).warning)
        assertEquals(Warning.TOO_DARK, state(telemetry = Telemetry(flight = flight(), tooDark = true)).warning)
        assertNull(state(link = LinkState.RECONNECTING, telemetry = Telemetry(flight = flight(critical = true))).warning)
    }

    @Test
    fun `alerts are sorted by severity and low battery is hidden by critical`() {
        val f = flight(low = true, critical = true).copy(windWarning = true, overheating = true, imuCalibrationState = 2)
        assertEquals(
            listOf(Warning.BATTERY_CRITICAL, Warning.OVERHEATING, Warning.IMU_ERROR, Warning.WIND),
            state(telemetry = Telemetry(flight = f)).warnings,
        )
    }

    @Test
    fun `motor code 206 (not activated) is not an alarm, other codes are`() {
        assertTrue(state(telemetry = Telemetry(flight = flight().copy(motorErrorCode = 206))).warnings.isEmpty())
        val s = state(telemetry = Telemetry(flight = flight().copy(motorErrorCode = 205)))
        assertEquals(Warning.MOTOR_ERROR, s.warning)
        assertEquals(205, s.motorErrorCode)
    }

    @Test
    fun `no positioning is only an alert in flight, calibration only on the ground`() {
        val noVps = flight().copy(flyMode = 1)
        assertEquals(Warning.NO_POSITIONING, state(telemetry = Telemetry(flight = noVps)).warning)
        assertNull(state(telemetry = Telemetry(flight = noVps.copy(flying = false))).warning)
        val calib = flight(flying = false).copy(gravityCalibrationNeeded = true)
        assertEquals(Warning.CALIBRATION_NEEDED, state(telemetry = Telemetry(flight = calib)).warning)
        assertNull(state(telemetry = Telemetry(flight = calib.copy(flying = true))).warning)
    }

    @Test
    fun `camera fault and photo progress are shown only when connected`() {
        val live = FlightUiState.from(true, LinkState.CONNECTED, Telemetry(flight = flight(), cameraFault = true), true, false, false, photoProgress = 0.5f)
        assertEquals(Warning.CAMERA_FAULT, live.warning)
        assertEquals(0.5f, live.photoProgress!!, 0f)
        val lost = FlightUiState.from(true, LinkState.RECONNECTING, Telemetry(), true, false, true, photoProgress = 0.5f)
        assertNull(lost.photoProgress)
    }

    @Test
    fun `settings are locked in flight`() {
        assertFalse(state().settingsAvailable)
        assertTrue(state(telemetry = Telemetry(flight = flight(flying = false))).settingsAvailable)
    }
}
