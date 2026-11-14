package com.drone.tellopilot.flightlog

import com.drone.tello.control.FlightMode
import com.drone.tello.control.FlightModeRules
import com.drone.tello.control.FlipContext
import com.drone.tello.control.ModeCheck
import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.StickInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flight log of the incident (14 Nov. 2026, 11:39, app v0.10.0, v2 file, last 8 s kept):
 * it still reads, and the telemetry it shows could never let a flip through the rules — neither
 * the old ones (battery 49 % < 50 %, then "not flying") nor the new ones.
 */
class FlightIncidentReplayTest {

    private val log = javaClass.getResourceAsStream("/flightlog/incident-20261114-113934.csv")!!
        .bufferedReader().useLines { FlightLogCsv.parse(it)!! }

    private fun FlightSample.asFlightData() = FlightData(
        heightMeters = heightM, northSpeed = northMps, eastSpeed = eastMps, verticalSpeed = -climbMps,
        flyTimeSeconds = flyTimeS ?: 0f, batteryPercent = batteryPct, flying = flying, onGround = !flying,
        hovering = flying, batteryLow = batteryLow, batteryCritical = batteryCritical, flyMode = 6,
    )

    @Test
    fun `the v2 incident file reads, without the newer columns`() {
        assertTrue(log.interrupted)
        val last = log.samples.last()
        assertEquals(listOf("MOTOR_ERROR:21"), last.alerts)
        assertNull(last.cmdRoll)
        assertNull(last.events)
        assertNull(last.telemetryAgeMs)
    }

    @Test
    fun `no row of the incident would have allowed a flip`() {
        val fresh = FlipContext(flyingForMs = 60_000, sinceLastFlipMs = null, busy = false, telemetryAgeMs = 100)
        log.samples.forEach { s ->
            val f = s.asFlightData()
            val now = FlightModeRules.check(FlightMode.Flip, f, 20, false, fresh)
            assertTrue("t=${s.tMs}: $now", now != ModeCheck.OK)
            // The official rules alone (the app before this change) refused it as well.
            val official = when {
                !f.flying -> ModeCheck.NOT_FLYING
                f.batteryPercent < 50 -> ModeCheck.LOW_BATTERY
                else -> ModeCheck.OK
            }
            assertTrue("t=${s.tMs}: official $official", official != ModeCheck.OK)
        }
    }

    @Test
    fun `the motor error and the jump are in the log, from a drone reported landed`() {
        val jump = log.samples.first { it.climbMps >= 2f }
        assertEquals(30_926L, jump.tMs)
        assertTrue(!jump.flying)
        assertEquals(listOf("MOTOR_ERROR:21"), jump.alerts)
    }

    @Test
    fun `v4 rows carry the flight data age, events and fly mode`() {
        val sampler = FlightSampler()
        val t = Telemetry(flight = log.samples.first().asFlightData().copy(flying = true, flyMode = 1, motorErrorCode = 21))
        val first = sampler.onTick(0, t, StickInput.Neutral, listOf("flip_refused:too_low"), telemetryAgeMs = 40).sample!!
        assertEquals(listOf("flip_refused:too_low"), first.events)
        assertEquals(1, first.flyMode)
        assertEquals(21, first.motorError)
        // A tick that writes no row keeps its events for the next one.
        assertNull(sampler.onTick(10, t, null, listOf("flip:forward")).sample)
        val next = sampler.onTick(100, t, null, listOf("flip_reply:ok"), telemetryAgeMs = 900).sample!!
        assertEquals(listOf("flip:forward", "flip_reply:ok"), next.events)
        assertEquals(900L, next.telemetryAgeMs)
        val back = FlightLogCsv.parse((FlightLogCsv.header(1) + FlightLogCsv.row(next)).lineSequence())!!.samples.single()
        assertEquals(next.events, back.events)
        assertEquals(900L, back.telemetryAgeMs)
    }
}
