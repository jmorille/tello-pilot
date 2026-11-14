package com.drone.tellopilot.flightlog

import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.Payloads
import com.drone.tello.protocol.StickInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** v3 columns: stick command actually sent (cmd_*) and fast_mode; older files still read. */
class FlightLogCommandColumnsTest {

    private val flight = FlightData(
        heightMeters = 1f, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 0f,
        batteryPercent = 80, flying = true, onGround = false, hovering = false,
        batteryLow = false, batteryCritical = false, flyMode = 6,
    )
    private val telemetry = Telemetry(flight = flight)
    private val bare = FlightSample(tMs = 0, heightM = 0f, northMps = 0f, eastMps = 0f, climbMps = 0f, batteryPct = 80, flying = true)

    private fun roundTrip(s: FlightSample) =
        FlightLogCsv.parse((FlightLogCsv.header(5) + FlightLogCsv.row(s)).lineSequence())!!.samples.single()

    @Test
    fun `the header is v3 with the command columns at the end`() {
        assertEquals(3, FlightLogCsv.VERSION)
        assertTrue(FlightLogCsv.header(5).startsWith("# tellopilot-flight v3 start=5\n"))
        assertEquals(listOf("cmd_roll", "cmd_pitch", "cmd_throttle", "cmd_yaw", "fast_mode"), FlightLogCsv.COLUMNS.takeLast(5))
        // Older columns keep their place: positional readers of v2 files see the same prefix.
        assertEquals("alerts", FlightLogCsv.COLUMNS[20])
    }

    @Test
    fun `a sample carries the sticks being sent, clamped like the encoder`() {
        val s = FlightSample.from(0, telemetry, StickInput(roll = -0.5f, pitch = 2f, throttle = Float.NaN, yaw = 0.25f, fastMode = true))!!
        assertEquals(-0.5f, s.cmdRoll)
        assertEquals(1f, s.cmdPitch)
        assertEquals(0f, s.cmdThrottle)
        assertEquals(0.25f, s.cmdYaw)
        assertEquals(true, s.fastMode)
    }

    @Test
    fun `without a known command the columns stay empty`() {
        val s = FlightSample.from(0, telemetry)!!
        assertNull(s.cmdRoll)
        assertNull(s.fastMode)
        val back = roundTrip(s)
        assertNull(back.cmdPitch)
        assertNull(back.fastMode)
    }

    @Test
    fun `command and fast mode round-trip`() {
        val back = roundTrip(bare.copy(cmdRoll = -1f, cmdPitch = 0.75f, cmdThrottle = 0f, cmdYaw = 1f, fastMode = false))
        assertEquals(-1f, back.cmdRoll)
        assertEquals(0.75f, back.cmdPitch)
        assertEquals(0f, back.cmdThrottle)
        assertEquals(1f, back.cmdYaw)
        assertEquals(false, back.fastMode)
        assertEquals(true, roundTrip(bare.copy(fastMode = true)).fastMode)
    }

    @Test
    fun `three decimals recover every 364-1684 wire value exactly`() {
        for (raw in 364..1684) {
            val v = (raw - 1024) / 660f
            val back = roundTrip(bare.copy(cmdPitch = v)).cmdPitch!!
            assertEquals("raw $raw", raw, (1024 + back * 660).roundToInt())
            assertEquals("raw $raw", raw, Payloads.axisValue(back))
        }
    }

    @Test
    fun `the sampler records the sticks it is given`() {
        val sampler = FlightSampler()
        val start = sampler.onTick(0, telemetry, StickInput(pitch = 0.5f))
        assertEquals(0.5f, start.sample!!.cmdPitch)
        assertEquals(false, start.sample!!.fastMode)
        val next = sampler.onTick(FlightSampler.INTERVAL_MS, telemetry, StickInput(yaw = -1f, fastMode = true))
        assertEquals(-1f, next.sample!!.cmdYaw)
        assertEquals(true, next.sample!!.fastMode)
    }

    @Test
    fun `a v2 file reads, without command values`() {
        val v2 = """
            # tellopilot-flight v2 start=1700000000000
            t_ms,height_m,north_mps,east_mps,climb_mps,battery_pct,flying,roll_deg,pitch_deg,yaw_deg,odo_x_m,odo_y_m,odo_z_m,odo_valid,imu_temp_c,wifi_pct,battery_low,battery_critical,overheating,fly_time_s,alerts
            0,0.30,0.00,0.00,0.10,87,1,,,,,,,,,90,0,0,0,1.2,
            100,1.20,0.50,0.00,0.40,86,1,2.0,-1.0,359.0,0.10,0.20,0.00,1,45.0,88,1,0,0,1.3,BATTERY_LOW
            # summary duration_ms=100 max_height_m=1.20 max_speed_mps=0.50 battery_start=87 battery_end=86 samples=2 track=1
        """.trimIndent()
        val log = FlightLogCsv.parse(v2.lineSequence())!!
        assertEquals(2, log.samples.size)
        assertFalse(log.interrupted)
        val s = log.samples[1]
        assertEquals(listOf("BATTERY_LOW"), s.alerts)
        assertEquals(359f, s.yawDeg)
        assertNull(s.cmdRoll)
        assertNull(s.cmdPitch)
        assertNull(s.cmdThrottle)
        assertNull(s.cmdYaw)
        assertNull(s.fastMode)
    }

    @Test
    fun `a v3 row cut inside the new columns is skipped`() {
        val full = bare.copy(cmdRoll = 0.1f, cmdPitch = 0.2f, cmdThrottle = 0.3f, cmdYaw = 0.4f, fastMode = true)
        val row = FlightLogCsv.row(full)
        val cut = row.substring(0, row.lastIndexOf(',', row.lastIndexOf(',') - 1))
        val log = FlightLogCsv.parse((FlightLogCsv.header(5) + row + cut).lineSequence())!!
        assertEquals(1, log.samples.size)
    }
}
