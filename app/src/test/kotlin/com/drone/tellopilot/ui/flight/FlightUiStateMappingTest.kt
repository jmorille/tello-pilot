package com.drone.tellopilot.ui.flight

import com.drone.tello.control.HudItem
import com.drone.tello.control.LinkState
import com.drone.tello.control.ManeuverState
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Telemetry
import com.drone.tello.control.Units
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.SmartVideoStatus
import com.drone.tello.protocol.WifiStatus
import com.drone.tellopilot.data.PhoneHeat
import com.drone.tellopilot.input.GamepadStatus
import com.drone.tellopilot.tracking.AutopilotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FlightUiState.from] mapping details not covered by [FlightUiStateTest]. */
class FlightUiStateMappingTest {

    private val air = FlightData(
        heightMeters = 2.5f, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = -0.8f, flyTimeSeconds = 12.9f,
        batteryPercent = 60, flying = true, onGround = false, hovering = false,
        batteryLow = false, batteryCritical = false, flyMode = 6, throwTimer = 25,
    )
    private val ground = air.copy(flying = false, onGround = true, verticalSpeed = 0f)

    private fun state(
        flight: FlightData? = air,
        wifiAvailable: Boolean = true,
        link: LinkState = LinkState.CONNECTED,
        telemetry: Telemetry = Telemetry(flight = flight),
        videoActive: Boolean = true,
        knownFlying: Boolean = false,
        pilot: PilotSettings = PilotSettings(),
        maneuvers: ManeuverState = ManeuverState(),
        gamepad: GamepadStatus = GamepadStatus.NONE,
        phoneHeat: PhoneHeat = PhoneHeat.NORMAL,
        padDriving: Boolean = false,
        autopilot: AutopilotState = AutopilotState.Idle,
        otherWifi: Boolean = false,
        groundTooLong: Boolean = false,
    ) = FlightUiState.from(
        wifiAvailable = wifiAvailable, link = link, telemetry = telemetry, videoActive = videoActive,
        fastMode = true, knownFlying = knownFlying, pilot = pilot, maneuvers = maneuvers, gamepad = gamepad,
        phoneHeat = phoneHeat, padDriving = padDriving, autopilot = autopilot, otherWifi = otherWifi,
        groundTooLong = groundTooLong,
    )

    @Test
    fun `no wifi wins over any link state`() {
        LinkState.entries.forEach { assertEquals(ConnectionStatus.WAITING_WIFI, state(wifiAvailable = false, link = it).status) }
        assertEquals(ConnectionStatus.CONNECTING, state(link = LinkState.IDLE).status)
    }

    @Test
    fun `climb rate is positive upwards and height is shown`() {
        val s = state()
        assertEquals(0.8f, s.climbRate!!, 1e-6f)
        assertEquals(2.5f, s.heightMeters!!, 0f)
        assertEquals(12, s.flyTimeSeconds)
        assertTrue(s.fastMode)
    }

    @Test
    fun `throw countdown is shown on the ground only, rounded up to the second`() {
        assertEquals(3, state(flight = ground).throwCountdown)
        assertEquals(0, state(flight = ground.copy(throwTimer = 0)).throwCountdown)
        assertEquals(1, state(flight = ground.copy(throwTimer = 1)).throwCountdown)
        assertEquals(0, state(flight = air).throwCountdown)
        assertEquals(0, state(flight = ground, link = LinkState.RECONNECTING).throwCountdown)
    }

    @Test
    fun `link dependent values are hidden while not connected`() {
        val telemetry = Telemetry(flight = air, wifi = WifiStatus(strength = 70, interference = 0), firmware = "01.04")
        val live = state(telemetry = telemetry, maneuvers = ManeuverState(bounce = true))
        assertEquals(70, live.wifiStrength)
        assertTrue(live.videoActive)
        assertTrue(live.bounce)

        val lost = state(telemetry = telemetry, link = LinkState.RECONNECTING, maneuvers = ManeuverState(bounce = true))
        assertNull(lost.wifiStrength)
        assertNull(lost.heightMeters)
        assertNull(lost.climbRate)
        assertNull(lost.flyTimeSeconds)
        assertFalse(lost.videoActive)
        assertFalse(lost.bounce)
        assertEquals(0, lost.motorErrorCode)
        assertEquals("firmware is kept", "01.04", lost.firmware)
    }

    @Test
    fun `video is inactive when the stream is off even if connected`() {
        assertFalse(state(videoActive = false).videoActive)
    }

    @Test
    fun `critical warnings come first, phone heat included`() {
        val s = state(flight = air.copy(windWarning = true, batteryLow = true), phoneHeat = PhoneHeat.HOT)
        assertEquals(listOf(Warning.PHONE_OVERHEATING, Warning.BATTERY_LOW, Warning.WIND), s.warnings)
        assertEquals(Warning.PHONE_OVERHEATING, s.warning)
    }

    @Test
    fun `phone heat is reported even without the drone`() {
        assertEquals(listOf(Warning.PHONE_HOT), state(wifiAvailable = false, phoneHeat = PhoneHeat.WARM).warnings)
        assertEquals(listOf(Warning.PHONE_OVERHEATING), state(link = LinkState.RECONNECTING, phoneHeat = PhoneHeat.HOT).warnings)
        assertTrue(state(link = LinkState.RECONNECTING, phoneHeat = PhoneHeat.NORMAL).warnings.isEmpty())
    }

    @Test
    fun `phone hot stays behind the drone critical warnings`() {
        val s = state(flight = air.copy(batteryCritical = true), phoneHeat = PhoneHeat.WARM)
        assertEquals(listOf(Warning.BATTERY_CRITICAL, Warning.PHONE_HOT), s.warnings)
    }

    @Test
    fun `ground heat is passed through`() {
        assertEquals(listOf(Warning.GROUND_HEAT), state(flight = ground, groundTooLong = true).warnings)
    }

    @Test
    fun `hidden information alerts keep the important ones on screen`() {
        val flight = air.copy(windWarning = true, batteryLow = true, flyMode = FlightData.FLY_MODE_NO_POSITIONING)
        val telemetry = Telemetry(flight = flight, tooDark = true, wifi = WifiStatus(50, 150))
        val hidden = PilotSettings(hiddenHud = setOf(HudItem.INFO_ALERTS))

        val all = state(telemetry = telemetry, phoneHeat = PhoneHeat.WARM)
        assertEquals(all.warnings, all.shownWarnings)

        val filtered = state(telemetry = telemetry, phoneHeat = PhoneHeat.WARM, pilot = hidden)
        assertEquals("vibrations still see everything", all.warnings, filtered.warnings)
        assertEquals(listOf(Warning.BATTERY_LOW, Warning.WIFI_INTERFERENCE, Warning.NO_POSITIONING), filtered.shownWarnings)
        assertEquals(Warning.BATTERY_LOW, filtered.warning)
    }

    @Test
    fun `hiding information alerts never hides a critical one`() {
        val hidden = PilotSettings(hiddenHud = setOf(HudItem.INFO_ALERTS))
        val s = state(flight = air.copy(overheating = true, windWarning = true), pilot = hidden, phoneHeat = PhoneHeat.HOT)
        assertEquals(listOf(Warning.OVERHEATING, Warning.PHONE_OVERHEATING), s.shownWarnings)
        assertNull(state(flight = air.copy(windWarning = true), pilot = hidden).warning)
    }

    @Test
    fun `other wifi is only reported while waiting for the drone wifi`() {
        assertTrue(state(wifiAvailable = false, otherWifi = true).otherWifi)
        assertFalse(state(otherWifi = true).otherWifi)
        assertFalse(state(wifiAvailable = false, otherWifi = false).otherWifi)
    }

    @Test
    fun `sticks hide only when the ready gamepad is flying and the pilot wants it`() {
        assertTrue(state(gamepad = GamepadStatus.READY, padDriving = true).sticksHidden)
        assertFalse(state(gamepad = GamepadStatus.CENTER_STICKS, padDriving = true).sticksHidden)
        assertFalse(state(gamepad = GamepadStatus.READY, padDriving = false).sticksHidden)
        assertFalse(state(gamepad = GamepadStatus.READY, padDriving = true, pilot = PilotSettings(hideSticksWithPad = false)).sticksHidden)
    }

    @Test
    fun `smart video stays stoppable while reconnecting`() {
        val requested = ManeuverState(requestedSmartVideo = SmartVideoMode.CIRCLE)
        val s = state(link = LinkState.RECONNECTING, maneuvers = requested)
        assertTrue(s.smartVideoRunning)
        assertEquals(SmartVideoMode.CIRCLE, s.smartVideo)
    }

    @Test
    fun `reported smart video mode wins over the requested one`() {
        val running = ManeuverState(
            smartVideo = SmartVideoStatus(exitReason = 0, state = 1, modeCode = SmartVideoMode.ROTATE_360.code),
            requestedSmartVideo = SmartVideoMode.CIRCLE,
        )
        assertEquals(SmartVideoMode.ROTATE_360, state(maneuvers = running).smartVideo)
        assertNull(state().smartVideo)
        assertFalse(state().smartVideoRunning)
    }

    @Test
    fun `autopilot activity and missions offered`() {
        assertFalse(state().autopilotActive)
        assertTrue(state(autopilot = AutopilotState.RunningMission("x", 1, 3)).autopilotActive)
        val own = Mission("Mine", listOf(MissionStep.Hover(2f)))
        val missions = state(pilot = PilotSettings(customMissions = listOf(own))).missions
        assertEquals(Mission.Presets + own, missions)
    }

    @Test
    fun `units follow the pilot settings`() {
        assertEquals(Units.IMPERIAL, state(pilot = PilotSettings(units = Units.IMPERIAL)).units)
    }

    @Test
    fun `default state waits for the wifi with controls disabled`() {
        val s = FlightUiState()
        assertEquals(ConnectionStatus.WAITING_WIFI, s.status)
        assertFalse(s.controlsEnabled)
        assertTrue(s.settingsAvailable)
        assertNull(s.warning)
        assertEquals(RecordingUi.Idle, s.recording)
    }
}
