package com.drone.tellopilot.video

import com.drone.tello.control.Units
import com.drone.tellopilot.flightlog.FlightLog
import com.drone.tellopilot.flightlog.FlightSample
import com.drone.tellopilot.flightlog.SummaryAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class OverlayTelemetryTest {

    private val zone: ZoneId = ZoneOffset.ofHours(2)
    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private fun values(height: Float, speed: Float = 0f, climb: Float = 0f, battery: Int = 80) =
        OverlayValues(height, speed, climb, battery)

    // --- video start time ---

    @Test
    fun `start time is read from the recorder file name, with or without a suffix`() {
        val expected = epoch(2026, 10, 31, 14, 5, 9)
        assertEquals(expected, VideoStartTime.fromName("tello_20261031_140509.mp4", zone))
        assertEquals(expected, VideoStartTime.fromName("tello_20261031_140509-hevc.mp4", zone))
        assertNull(VideoStartTime.fromName("VID_20261031.mp4", zone))
        assertNull(VideoStartTime.fromName("tello_20261399_999999.mp4", zone))
    }

    @Test
    fun `exact date taken is trusted only when consistent with the name`() {
        val name = epoch(2026, 10, 31, 14, 5, 9)
        // Exact first frame 1.2 s after pressing record.
        assertEquals(name + 1_200, VideoStartTime.resolve(name, name + 1_200))
        // Scanner replaced it with the end of the recording: the name wins.
        assertEquals(name + VideoStartTime.NAME_ROUNDING_MS, VideoStartTime.resolve(name, name + 60_000))
        // First frame queued just before the file was created.
        assertEquals(name - 800, VideoStartTime.resolve(name, name - 800))
        assertEquals(name + VideoStartTime.NAME_ROUNDING_MS, VideoStartTime.resolve(name, name - 2_000))
        // Time zone changed since the recording: exact DATE_TAKEN still recognised.
        assertEquals(name - 3_600_000 + 1_200, VideoStartTime.resolve(name, name - 3_600_000 + 1_200, 60_000))
        assertEquals(name + 5_400_000 + 300, VideoStartTime.resolve(name, name + 5_400_000 + 300, 60_000))
        // Shifted match that is the end of a 15 min recording: the name wins.
        assertEquals(name + VideoStartTime.NAME_ROUNDING_MS, VideoStartTime.resolve(name, name + 900_000 + 500, 900_000))
        // Not a whole zone offset away: the name wins.
        assertEquals(name + VideoStartTime.NAME_ROUNDING_MS, VideoStartTime.resolve(name, name - 3_600_000 + 400_000))
        assertEquals(name + VideoStartTime.NAME_ROUNDING_MS, VideoStartTime.resolve(name, null))
        // Not a recorder file: DATE_TAKEN alone, or nothing.
        assertEquals(1234L, VideoStartTime.resolve(null, 1234L))
        assertNull(VideoStartTime.resolve(null, null))
        assertNull(VideoStartTime.resolve(null, 0L))
    }

    // --- flight log selection ---

    @Test
    fun `only flights overlapping the video are selected`() {
        val flights = listOf(
            FlightLogMatch.Range("before", 0, 60_000),
            FlightLogMatch.Range("during", 1_000_000, 300_000),
            FlightLogMatch.Range("tail", 1_400_000 + FlightLogMatch.SLACK_MS - 1, 10_000),
            FlightLogMatch.Range("after", 5_000_000, 60_000),
        )
        assertEquals(listOf("during", "tail"), FlightLogMatch.overlapping(flights, 1_200_000, 1_400_000))
        assertEquals(emptyList<String>(), FlightLogMatch.overlapping(flights, 3_000_000, 3_100_000))
    }

    // --- sample lookup ---

    @Test
    fun `values are interpolated between close samples`() {
        val track = OverlayTrack(
            listOf(
                TimedSample(1_200, values(2f, speed = 2f, climb = -1f, battery = 79)),
                TimedSample(1_000, values(1f, speed = 1f, climb = 1f, battery = 80)),
            ),
        )
        val mid = track.at(1_100)!!
        assertEquals(1.5f, mid.heightM, 1e-4f)
        assertEquals(1.5f, mid.speedMps, 1e-4f)
        assertEquals(0f, mid.climbMps, 1e-4f)
        assertEquals(79, mid.batteryPct) // nearest reading, never an average
        assertEquals(80, track.at(1_040)!!.batteryPct)
        assertEquals(1f, track.at(1_000)!!.heightM, 1e-4f)
        assertEquals(2f, track.at(1_200)!!.heightM, 1e-4f)
    }

    @Test
    fun `outside the flight or across a gap only the nearest close sample is used`() {
        val track = OverlayTrack(listOf(TimedSample(10_000, values(1f)), TimedSample(20_000, values(5f))))
        assertNull(track.at(8_000))
        assertEquals(1f, track.at(9_500)!!.heightM, 1e-4f)
        assertEquals(1f, track.at(10_800)!!.heightM, 1e-4f)
        assertNull(track.at(15_000)) // link lost: never bridged
        assertEquals(5f, track.at(19_200)!!.heightM, 1e-4f)
        assertNull(track.at(21_500))
        assertNull(OverlayTrack(emptyList()).at(0))
    }

    @Test
    fun `flight logs are placed on the wall clock`() {
        fun sample(t: Long, h: Float) = FlightSample(t, h, 3f, 4f, 0.5f, 77, true)
        val samples = listOf(sample(0, 1f), sample(200, 2f))
        val log = FlightLog(1_000_000, samples, SummaryAccumulator.of(samples), false)
        val track = OverlayTrack.of(listOf(log))
        val v = track.at(1_000_100)
        assertNotNull(v)
        assertEquals(1.5f, v!!.heightM, 1e-4f)
        assertEquals(5f, v.speedMps, 1e-4f) // hypot(3, 4)
        assertNull(track.at(500_000))
    }

    // --- text ---

    @Test
    fun `time only without telemetry`() {
        val t = epoch(2026, 10, 31, 9, 7, 3)
        assertEquals(listOf("09:07:03"), OverlayText.lines(t, null, Units.METRIC, zone, Locale.ROOT))
    }

    @Test
    fun `metric lines with climb arrow and battery`() {
        val t = epoch(2026, 10, 31, 14, 5, 9)
        val lines = OverlayText.lines(t, values(12.34f, speed = 3.45f, climb = 0.52f, battery = 76), Units.METRIC, zone, Locale.ROOT)
        assertEquals(listOf("14:05:09", "H 12.3 m   V 3.5 m/s", "↑ 0.5 m/s   Bat. 76 %"), lines)
        val down = OverlayText.lines(t, values(1f, climb = -1.26f), Units.METRIC, zone, Locale.ROOT)
        assertTrue(down[2].startsWith("↓ 1.3 m/s"))
        val level = OverlayText.lines(t, values(1f, climb = 0.05f), Units.METRIC, zone, Locale.ROOT)
        assertTrue(level[2].startsWith("↕ 0.1 m/s"))
    }

    @Test
    fun `imperial units and locale decimal separator`() {
        val t = epoch(2026, 10, 31, 14, 5, 9)
        val lines = OverlayText.lines(t, values(10f, speed = 2f, climb = -1f, battery = 50), Units.IMPERIAL, zone, Locale.FRANCE)
        assertEquals(listOf("14:05:09", "H 33 ft   V 4,5 mph", "↓ 2,2 mph   Bat. 50 %"), lines)
    }

    // --- pilot view values ---

    @Test
    fun `attitude is interpolated, the heading through north`() {
        val a = values(1f).copy(rollDeg = 0f, pitchDeg = -10f, yawDeg = 358f, flyTimeS = 10f)
        val b = values(1f).copy(rollDeg = 20f, pitchDeg = 10f, yawDeg = 4f, flyTimeS = 10.2f)
        val track = OverlayTrack(listOf(TimedSample(0, a), TimedSample(100, b)))
        val q = track.at(25)!!
        assertEquals(5f, q.rollDeg!!, 1e-4f)
        assertEquals(-5f, q.pitchDeg!!, 1e-4f)
        assertEquals(359.5f, q.yawDeg!!, 1e-4f) // not 239.5 (the long way round)
        assertEquals(2.5f, track.at(75)!!.yawDeg!!, 1e-4f)
        assertEquals(10.05f, q.flyTimeS!!, 1e-4f)
    }

    @Test
    fun `a value known on one side only is taken from the nearest sample`() {
        val a = values(1f).copy(rollDeg = null, wifiPct = null)
        val b = values(1f).copy(rollDeg = 4f, wifiPct = 70)
        val track = OverlayTrack(listOf(TimedSample(0, a), TimedSample(100, b)))
        assertNull(track.at(40)!!.rollDeg)
        assertNull(track.at(40)!!.wifiPct)
        assertEquals(4f, track.at(60)!!.rollDeg!!, 1e-4f)
        assertEquals(70, track.at(60)!!.wifiPct)
    }

    @Test
    fun `log samples map to overlay values, yaw as a heading`() {
        val s = FlightSample(
            tMs = 0, heightM = 1f, northMps = 3f, eastMps = 4f, climbMps = 0.5f, batteryPct = 50, flying = true,
            rollDeg = 1f, pitchDeg = Float.NaN, yawDeg = -90f, imuTempC = 40f, wifiPct = 60, flyTimeS = 12f,
        )
        val v = OverlayTrack.values(s)
        assertEquals(5f, v.speedMps, 1e-4f)
        assertEquals(270f, v.yawDeg)
        assertNull(v.pitchDeg) // non-finite reading dropped
        assertEquals(12f, v.flyTimeS)
        assertEquals(60, v.wifiPct)
    }

    @Test
    fun `alerts come from the recorded ones, or the battery and heat flags of older files`() {
        val base = FlightSample(tMs = 0, heightM = 1f, northMps = 0f, eastMps = 0f, climbMps = 0f, batteryPct = 50, flying = true)
        val v1 = base.copy(batteryLow = true, overheating = true)
        assertEquals(listOf(com.drone.tellopilot.ui.flight.Warning.OVERHEATING, com.drone.tellopilot.ui.flight.Warning.BATTERY_LOW) to 0, OverlayWarnings.of(v1))
        val critical = base.copy(batteryLow = true, batteryCritical = true)
        assertEquals(listOf(com.drone.tellopilot.ui.flight.Warning.BATTERY_CRITICAL) to 0, OverlayWarnings.of(critical))
        // Recorded alerts win (the flags are part of them), unknown names are ignored.
        val v2 = base.copy(batteryLow = true, alerts = listOf("WIND", "FUTURE_ALERT", "MOTOR_ERROR:231"))
        assertEquals(
            listOf(com.drone.tellopilot.ui.flight.Warning.MOTOR_ERROR, com.drone.tellopilot.ui.flight.Warning.WIND) to 231,
            OverlayWarnings.of(v2),
        )
        assertEquals(emptyList<com.drone.tellopilot.ui.flight.Warning>() to 0, OverlayWarnings.of(base.copy(alerts = emptyList())))
    }
}
