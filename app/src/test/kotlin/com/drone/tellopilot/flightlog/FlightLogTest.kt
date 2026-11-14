package com.drone.tellopilot.flightlog

import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.Attitude
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.LogRecord
import com.drone.tello.protocol.WifiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightLogTest {

    private fun flight(flying: Boolean, height: Float = 1f, battery: Int = 80, north: Float = 0f, east: Float = 0f) = FlightData(
        heightMeters = height, northSpeed = north, eastSpeed = east, verticalSpeed = -0.5f, flyTimeSeconds = 0f,
        batteryPercent = battery, flying = flying, onGround = !flying, hovering = false,
        batteryLow = false, batteryCritical = false, flyMode = 6,
    )

    private fun telemetry(flying: Boolean, height: Float = 1f, battery: Int = 80) = Telemetry(flight = flight(flying, height, battery))

    // --- Sampling -------------------------------------------------------------------------

    @Test
    fun `nothing is recorded on the ground`() {
        val sampler = FlightSampler()
        repeat(10) { assertEquals(FlightSampler.Tick(), sampler.onTick(it * 200L, telemetry(flying = false))) }
        assertEquals(FlightSampler.Tick(), sampler.onTick(5_000, null))
        assertFalse(sampler.recording)
    }

    @Test
    fun `takeoff starts a flight with a first sample at offset zero`() {
        val sampler = FlightSampler()
        val tick = sampler.onTick(1_000, telemetry(flying = true))
        assertTrue(tick.start)
        assertEquals(0L, tick.sample?.tMs)
        assertEquals(1_200L - 1_000, sampler.onTick(1_200, telemetry(flying = true)).sample?.tMs)
    }

    @Test
    fun `samples are rate limited`() {
        val sampler = FlightSampler(intervalMs = 200)
        sampler.onTick(0, telemetry(true))
        assertNull(sampler.onTick(100, telemetry(true)).sample) // too early
        assertNotNull(sampler.onTick(160, telemetry(true)).sample) // late-tick tolerance (>= 150)
        assertNull(sampler.onTick(200, telemetry(true)).sample)
    }

    @Test
    fun `landing ends the flight after the tail`() {
        val sampler = FlightSampler(tailMs = 5_000)
        sampler.onTick(0, telemetry(true))
        sampler.onTick(1_000, telemetry(false))
        assertFalse(sampler.onTick(5_800, telemetry(false)).stop)
        val end = sampler.onTick(6_000, telemetry(false))
        assertTrue(end.stop)
        assertNotNull(end.sample) // the last ground sample is kept
        assertFalse(sampler.recording)
    }

    @Test
    fun `a new takeoff within the tail continues the same flight`() {
        val sampler = FlightSampler(tailMs = 5_000)
        sampler.onTick(0, telemetry(true))
        sampler.onTick(1_000, telemetry(false))
        val again = sampler.onTick(3_000, telemetry(true))
        assertFalse(again.start)
        assertFalse(again.stop)
        assertFalse(sampler.onTick(7_000, telemetry(true)).stop)
        assertTrue(sampler.recording)
    }

    @Test
    fun `link loss keeps the flight open until the timeout`() {
        val sampler = FlightSampler(noDataTimeoutMs = 60_000)
        sampler.onTick(0, telemetry(true))
        assertEquals(FlightSampler.Tick(), sampler.onTick(30_000, null))
        // Reconnected: same flight, offsets continue.
        assertEquals(40_000L, sampler.onTick(40_000, telemetry(true)).sample?.tMs)
        assertFalse(sampler.onTick(99_000, null).stop)
        assertTrue(sampler.onTick(100_000, null).stop)
        assertTrue(sampler.onTick(100_200, telemetry(true)).start) // next one is a new flight
    }

    @Test
    fun `sample maps telemetry including the optional log stream fields`() {
        val t = Telemetry(
            flight = flight(true, height = 2.5f, battery = 64, north = 3f, east = 4f),
            wifi = WifiStatus(strength = 90, interference = 0),
            attitude = Attitude(rollDeg = 1f, pitchDeg = -2f, yawDeg = 45f),
            imuTemperatureC = 41.5f,
            odometry = LogRecord.VisualOdometry(x = 1f, y = 2f, z = -0.5f, positionValid = true),
        )
        val s = FlightSample.from(1_234, t)!!
        assertEquals(1_234L, s.tMs)
        assertEquals(2.5f, s.heightM)
        assertEquals(5f, s.horizontalMps, 1e-6f)
        assertEquals(0.5f, s.climbMps) // down-positive vertical speed inverted
        assertEquals(64, s.batteryPct)
        assertEquals(45f, s.yawDeg)
        assertEquals(true, s.odoValid)
        assertEquals(41.5f, s.imuTempC)
        assertEquals(90, s.wifiPct)
        assertEquals(emptyList<String>(), s.alerts)
        assertEquals(0f, s.flyTimeS)
        assertNull(FlightSample.from(0, Telemetry()))
        val bare = FlightSample.from(0, telemetry(true))!!
        assertNull(bare.rollDeg)
        assertNull(bare.odoX)
        assertNull(bare.imuTempC)
        assertNull(bare.wifiPct)
    }

    @Test
    fun `sample records the drone alerts, the motor error with its code`() {
        val f = flight(true).copy(batteryLow = true, motorErrorCode = 231, flyTimeSeconds = 42.5f)
        val s = FlightSample.from(0, Telemetry(flight = f))!!
        assertEquals(listOf("MOTOR_ERROR:231", "BATTERY_LOW"), s.alerts)
        assertEquals(42.5f, s.flyTimeS)
    }

    // --- CSV ------------------------------------------------------------------------------

    private val full = FlightSample(
        tMs = 400, heightM = 1.23f, northMps = -0.5f, eastMps = 0.25f, climbMps = 0.1f, batteryPct = 77, flying = true,
        rollDeg = 1.5f, pitchDeg = -3.25f, yawDeg = 179.9f, odoX = 0.12f, odoY = -1.5f, odoZ = 0.8f, odoValid = true,
        imuTempC = 40.2f, wifiPct = 88, batteryLow = true, batteryCritical = false, overheating = true,
        flyTimeS = 95.3f, alerts = listOf("OVERHEATING", "MOTOR_ERROR:231", "BATTERY_LOW"),
        cmdRoll = -0.25f, cmdPitch = 1f, cmdThrottle = 0f, cmdYaw = 0.5f, fastMode = true,
    )
    private val bare = FlightSample(tMs = 0, heightM = 0f, northMps = 0f, eastMps = 0f, climbMps = 0f, batteryPct = 80, flying = true)

    @Test
    fun `rows are compact, locale independent and leave unknown values empty`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE) // decimal comma must not leak in
            assertEquals(
                "400,1.23,-0.50,0.25,0.10,77,1,1.5,-3.3,179.9,0.12,-1.50,0.80,1,40.2,88,1,0,1,95.3,OVERHEATING;MOTOR_ERROR:231;BATTERY_LOW," +
                    "-0.250,1.000,0.000,0.500,1\n",
                FlightLogCsv.row(full),
            )
            assertEquals("0,0.00,0.00,0.00,0.00,80,1,,,,,,,,,,0,0,0,,,,,,,\n", FlightLogCsv.row(bare))
        } finally {
            java.util.Locale.setDefault(default)
        }
        assertEquals(FlightLogCsv.COLUMNS.size, FlightLogCsv.row(bare).trimEnd().split(',').size)
    }

    @Test
    fun `a complete file round-trips`() {
        val samples = listOf(bare, full, full.copy(tMs = 600, flying = false, batteryPct = 75))
        val summary = SummaryAccumulator.of(samples)
        val text = FlightLogCsv.header(1_700_000_000_000) + samples.joinToString("") { FlightLogCsv.row(it) } + FlightLogCsv.footer(summary)
        val log = FlightLogCsv.parse(text.lineSequence())!!
        assertEquals(1_700_000_000_000, log.startEpochMs)
        assertEquals(3, log.samples.size)
        assertFalse(log.interrupted)
        // The footer rounds to 2 decimals.
        assertEquals(FlightLogCsv.parseFooter(FlightLogCsv.footer(summary).trim())!!.first, log.summary)
        assertEquals(3, log.summary.samples)
        assertEquals(5, log.summary.batteryUsed)
        val back = log.samples[1]
        assertEquals(full.copy(pitchDeg = -3.3f, odoX = 0.12f), back.copy(pitchDeg = -3.3f))
        assertNull(log.samples[0].odoValid)
        assertNull(log.samples[0].wifiPct)
    }

    @Test
    fun `a v1 file without the newer columns still reads, their values unknown`() {
        val v1 = """
            # tellopilot-flight v1 start=1700000000000
            t_ms,height_m,north_mps,east_mps,climb_mps,battery_pct,flying,roll_deg,pitch_deg,yaw_deg,odo_x_m,odo_y_m,odo_z_m,odo_valid,imu_temp_c,wifi_pct,battery_low,battery_critical,overheating
            0,0.30,0.00,0.00,0.10,87,1,,,,,,,,,90,0,0,0
            200,1.20,0.50,0.00,0.40,86,1,2.0,-1.0,359.0,0.10,0.20,0.00,1,45.0,88,1,0,0
            # summary duration_ms=200 max_height_m=1.20 max_speed_mps=0.50 battery_start=87 battery_end=86 samples=2 track=1
        """.trimIndent()
        val log = FlightLogCsv.parse(v1.lineSequence())!!
        assertEquals(2, log.samples.size)
        assertFalse(log.interrupted)
        val s = log.samples[1]
        assertEquals(359f, s.yawDeg)
        assertEquals(true, s.batteryLow)
        assertNull(s.flyTimeS)
        assertNull(s.alerts) // not recorded: the overlay falls back to the battery / heat flags
    }

    @Test
    fun `an empty alerts cell is no alert, not unknown`() {
        val text = FlightLogCsv.header(5) + FlightLogCsv.row(bare.copy(alerts = emptyList()))
        assertEquals(emptyList<String>(), FlightLogCsv.parse(text.lineSequence())!!.samples.single().alerts)
    }

    @Test
    fun `columns are read by name, in any order, unknown ones ignored`() {
        val text = listOf(
            "# tellopilot-flight v3 start=5",
            "flying,t_ms,future_col,height_m,north_mps,east_mps,climb_mps,battery_pct,fly_time_s",
            "1,100,zzz,2.00,0,0,0,50,12.5",
        )
        val s = FlightLogCsv.parse(text.asSequence())!!.samples.single()
        assertEquals(100L, s.tMs)
        assertEquals(2f, s.heightM)
        assertEquals(12.5f, s.flyTimeS)
        assertNull(s.rollDeg)
        assertNull(s.alerts)
    }

    @Test
    fun `a file cut by a crash is still readable`() {
        val text = FlightLogCsv.header(5) + FlightLogCsv.row(bare) + FlightLogCsv.row(full) + FlightLogCsv.row(full).take(12)
        val log = FlightLogCsv.parse(text.lineSequence())!!
        assertEquals(2, log.samples.size) // the truncated row is skipped
        assertTrue(log.interrupted)
        assertEquals(2, log.summary.samples)
        assertEquals(1.23f, log.summary.maxHeightM)
    }

    @Test
    fun `not a flight file`() {
        assertNull(FlightLogCsv.parse(sequenceOf("hello", "1,2,3")))
        assertNull(FlightLogCsv.parseStart(null))
        assertNull(FlightLogCsv.parseFooter("# summary duration_ms=x"))
    }

    @Test
    fun `footer keeps the interrupted flag`() {
        val summary = FlightSummary(12_000, 3.5f, 2.25f, 90, 80, 60, hasTrack = true)
        val (back, interrupted) = FlightLogCsv.parseFooter(FlightLogCsv.footer(summary, interrupted = true).trim())!!
        assertEquals(summary, back)
        assertTrue(interrupted)
        val partial = summary.copy(batteryStart = null, batteryEnd = null, hasTrack = false)
        assertEquals(partial to false, FlightLogCsv.parseFooter(FlightLogCsv.footer(partial).trim()))
    }

    // --- Summary & statistics -------------------------------------------------------------

    @Test
    fun `summary counts flying time, maxima, battery and track`() {
        val samples = listOf(
            bare.copy(tMs = 0, batteryPct = 90),
            bare.copy(tMs = 1_000, heightM = 3f, northMps = 3f, eastMps = 4f, batteryPct = 88),
            bare.copy(tMs = 61_000, heightM = 2f, batteryPct = 80, odoX = 1f, odoY = 1f, odoValid = false),
            bare.copy(tMs = 66_000, heightM = 0f, flying = false, batteryPct = 81), // tail on the ground
        )
        val s = SummaryAccumulator.of(samples)
        assertEquals(61_000L, s.durationMs)
        assertEquals(3f, s.maxHeightM)
        assertEquals(5f, s.maxSpeedMps)
        assertEquals(9, s.batteryUsed)
        assertFalse(s.hasTrack) // odometry present but not valid
        assertTrue(SummaryAccumulator.of(samples + bare.copy(tMs = 70_000, odoX = 0f, odoY = 0f, odoValid = true)).hasTrack)
        assertEquals(0, FlightSummary(1, 0f, 0f, 50, 52, 1, false).batteryUsed) // reading went up at rest
    }

    @Test
    fun `statistics over flights`() {
        val a = FlightSummary(durationMs = 120_000, maxHeightM = 5f, maxSpeedMps = 3f, batteryStart = 100, batteryEnd = 90, samples = 600, hasTrack = false)
        val b = FlightSummary(durationMs = 60_000, maxHeightM = 8f, maxSpeedMps = 2f, batteryStart = 90, batteryEnd = 85, samples = 300, hasTrack = false)
        val c = FlightSummary(durationMs = 30_000, maxHeightM = 1f, maxSpeedMps = 6f, batteryStart = null, batteryEnd = null, samples = 150, hasTrack = false)
        val stats = FlightStats.of(listOf(a, b, c))
        assertEquals(3, stats.flights)
        assertEquals(210_000L, stats.totalMs)
        assertEquals(120_000L, stats.longestMs)
        assertEquals(70_000L, stats.averageMs)
        assertEquals(8f, stats.maxHeightM)
        assertEquals(6f, stats.maxSpeedMps)
        // 15 % over 3 min (c has no battery reading and is left out).
        assertEquals(5f, stats.batteryPerMinute!!, 1e-4f)
        assertNull(FlightStats.of(listOf(c, b.copy(durationMs = 30_000))).batteryPerMinute) // under a minute
        val empty = FlightStats.of(emptyList())
        assertEquals(0, empty.flights)
        assertEquals(0L, empty.averageMs)
        assertNull(empty.batteryPerMinute)
    }

    // --- Storage cap ----------------------------------------------------------------------

    @Test
    fun `retention keeps the newest flights within count and size`() {
        val files = (1..5).map { FlightRetention.Stored("flight-2026010$it-120000", 10) }.shuffled()
        assertEquals(
            setOf("flight-20260101-120000", "flight-20260102-120000"),
            FlightRetention.toDelete(files, maxFlights = 3, maxBytes = 1_000).toSet(),
        )
        assertEquals(
            setOf("flight-20260101-120000"),
            FlightRetention.toDelete(files, maxFlights = 10, maxBytes = 40).toSet(),
        )
        assertTrue(FlightRetention.toDelete(files, maxFlights = 10, maxBytes = 1_000).isEmpty())
    }

    @Test
    fun `retention never deletes the flight being recorded`() {
        val files = listOf(
            FlightRetention.Stored("flight-20260101-120000", 100),
            FlightRetention.Stored("flight-20260102-120000", 10),
        )
        assertEquals(
            listOf("flight-20260102-120000"),
            FlightRetention.toDelete(files, keep = "flight-20260101-120000", maxFlights = 1, maxBytes = 1_000),
        )
    }
}
