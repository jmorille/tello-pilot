package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure flip rules of [FlightModeRules]. */
class FlightModeRulesFlipTest {

    private fun flight(height: Float = 2.5f, battery: Int = 80, flyMode: Int = 6, vision: Boolean = false, wind: Boolean = false) = FlightData(
        heightMeters = height, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 10f,
        batteryPercent = battery, flying = true, onGround = false, motorsOn = true, hovering = true,
        batteryLow = false, batteryCritical = false, flyMode = flyMode, visionSensorError = vision, windWarning = wind,
    )

    private val settled = FlipContext(flyingForMs = 10_000, sinceLastFlipMs = null, busy = false)

    private fun check(f: FlightData, c: FlipContext? = settled) = FlightModeRules.check(FlightMode.Flip, f, 20, false, c)

    @Test
    fun `a settled flight between 2 and 4 m with 55 percent flips`() {
        assertEquals(ModeCheck.OK, check(flight(height = 2f, battery = 55)))
        assertEquals(ModeCheck.OK, check(flight(height = 4f)))
    }

    @Test
    fun `height - below 2 m, negative, NaN or above 4 m is refused`() {
        assertEquals(ModeCheck.TOO_LOW, check(flight(height = 0.6f)), "the incident height")
        assertEquals(ModeCheck.TOO_LOW, check(flight(height = 1.9f)))
        assertEquals(ModeCheck.TOO_LOW, check(flight(height = -2.5f)))
        assertEquals(ModeCheck.TOO_LOW, check(flight(height = Float.NaN)))
        assertEquals(ModeCheck.TOO_HIGH, check(flight(height = 4.1f)))
        assertEquals(ModeCheck.TOO_LOW, check(flight(height = Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `battery margin above the official 50 percent`() {
        assertEquals(ModeCheck.LOW_BATTERY, check(flight(battery = 51)))
        assertEquals(ModeCheck.LOW_BATTERY, check(flight(battery = 54)))
    }

    @Test
    fun `positioning - no VPS fly mode or a vision sensor error`() {
        assertEquals(ModeCheck.NO_POSITIONING, check(flight(flyMode = FlightData.FLY_MODE_NO_POSITIONING)))
        assertEquals(ModeCheck.NO_POSITIONING, check(flight(vision = true)))
    }

    @Test
    fun `takeoff over, one flip at a time, nothing running`() {
        assertEquals(ModeCheck.NOT_STABILISED, check(flight(), settled.copy(flyingForMs = 2_999)))
        assertEquals(ModeCheck.NOT_STABILISED, check(flight(), settled.copy(flyingForMs = null)))
        assertEquals(ModeCheck.TELEMETRY_STALE, check(flight(), null), "no context: refused")
        assertEquals(ModeCheck.OK, check(flight(), settled.copy(flyingForMs = 3_000)))
        assertEquals(ModeCheck.FLIP_LOCKED, check(flight(), settled.copy(sinceLastFlipMs = 3_999)))
        assertEquals(ModeCheck.OK, check(flight(), settled.copy(sinceLastFlipMs = 4_000)))
        assertEquals(ModeCheck.BUSY, check(flight(), settled.copy(busy = true)))
        assertEquals(ModeCheck.BUSY, check(flight(flyMode = FlightData.FLY_MODE_LANDING)))
        assertEquals(ModeCheck.WIND, check(flight(wind = true)))
    }

    @Test
    fun `stale or missing flight data is never trusted`() {
        assertEquals(ModeCheck.OK, check(flight(), settled.copy(telemetryAgeMs = 500)))
        assertEquals(ModeCheck.TELEMETRY_STALE, check(flight(), settled.copy(telemetryAgeMs = 501)))
        assertEquals(ModeCheck.TELEMETRY_STALE, check(flight(), settled.copy(telemetryAgeMs = null)))
    }

    @Test
    fun `bounce and smart videos keep the official rules`() {
        assertEquals(ModeCheck.OK, FlightModeRules.check(FlightMode.Bounce, flight(height = 0.6f, battery = 30), 20, false))
    }
}
