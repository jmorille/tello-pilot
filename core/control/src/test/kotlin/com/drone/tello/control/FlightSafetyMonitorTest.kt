package com.drone.tello.control

import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.LogRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class FlightSafetyMonitorTest {

    private fun flight(flying: Boolean, flyMode: Int = 6) = FlightData(
        heightMeters = 1f, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 10f,
        batteryPercent = 80, flying = flying, onGround = !flying, hovering = true,
        batteryLow = false, batteryCritical = false, flyMode = flyMode,
    )

    /** IMU record rolled by [rollDeg]. */
    private fun imu(rollDeg: Float = 0f, vn: Float = 0f): LogRecord.Imu {
        val half = Math.toRadians(rollDeg / 2.0)
        return LogRecord.Imu(cos(half).toFloat(), sin(half).toFloat(), 0f, 0f, vn, 0f, 0f, null)
    }

    private class Run(val pilot: PilotSettings) {
        val monitor = FlightSafetyMonitor()
        var now = 0L
        var telemetry = Telemetry()
        val reports = mutableListOf<FlightSafetyMonitor.Report>()

        fun feed(t: Telemetry, maneuver: Boolean = false): FlightSafetyMonitor.Report {
            now += 100
            telemetry = t
            return monitor.onTelemetry(t, pilot, maneuver, now).also { reports += it }
        }
    }

    /** On the ground, takeoff, then [flyMs] of calm flight. */
    private fun Run.takeOff(flyMs: Long = 6_000) {
        repeat(5) { feed(Telemetry(flight = flight(false), imu = imu())) }
        repeat((flyMs / 100).toInt()) { feed(Telemetry(flight = flight(true), imu = imu())) }
    }

    @Test
    fun `a tumble in flight is reported`() {
        val r = Run(PilotSettings())
        r.takeOff()
        assertNull(r.feed(Telemetry(flight = flight(true), imu = imu(70f))).impact)
        assertEquals(ImpactKind.TILT, r.feed(Telemetry(flight = flight(true), imu = imu(75f))).impact)
    }

    @Test
    fun `impact detection off reports nothing`() {
        val r = Run(PilotSettings(impactDetection = false))
        r.takeOff()
        repeat(5) { r.feed(Telemetry(flight = flight(true), imu = imu(80f))) }
        assertTrue(r.reports.none { it.impact != null })
    }

    @Test
    fun `the same IMU record is used once`() {
        val r = Run(PilotSettings())
        r.takeOff()
        val record = imu(80f)
        r.feed(Telemetry(flight = flight(true), imu = record))
        // Flight data updates re-emit the telemetry with the same IMU record: not a second sample.
        repeat(3) { assertNull(r.feed(Telemetry(flight = flight(true), imu = record)).impact) }
    }

    @Test
    fun `nothing right after takeoff nor while landing`() {
        val r = Run(PilotSettings())
        r.takeOff(flyMs = 2_000)
        repeat(5) { r.feed(Telemetry(flight = flight(true), imu = imu(80f))) }
        r.takeOff(flyMs = 6_000)
        repeat(5) { r.feed(Telemetry(flight = flight(true, flyMode = FlightData.FLY_MODE_LANDING), imu = imu(80f))) }
        repeat(5) { r.feed(Telemetry(flight = flight(true), imu = imu(80f)), maneuver = true) }
        assertTrue(r.reports.none { it.impact != null })
    }

    @Test
    fun `the takeoff quiet time holds even when no IMU record came on the ground`() {
        // The log stream may only start once airborne: the detector never saw a ground sample,
        // the monitor's own flying edge must start the quiet time.
        val r = Run(PilotSettings())
        repeat(5) { r.feed(Telemetry(flight = flight(false))) }
        repeat(30) { r.feed(Telemetry(flight = flight(true), imu = imu(80f))) } // 3 s of a wild takeoff
        assertTrue(r.reports.none { it.impact != null })
        repeat(15) { r.feed(Telemetry(flight = flight(true), imu = imu())) }
        r.feed(Telemetry(flight = flight(true), imu = imu(80f)))
        assertEquals(ImpactKind.TILT, r.feed(Telemetry(flight = flight(true), imu = imu(80f))).impact)
    }

    @Test
    fun `turning the detection on in flight starts with a quiet second`() {
        val monitor = FlightSafetyMonitor()
        var now = 0L
        fun feed(pilot: PilotSettings, rollDeg: Float) =
            monitor.onTelemetry(Telemetry(flight = flight(now > 500), imu = imu(rollDeg)), pilot, false, now.also { now += 100 }).impact
        repeat(80) { feed(PilotSettings(impactDetection = false), 0f) } // ground, then 7.5 s of flight
        val on = PilotSettings(impactDetection = true)
        repeat(9) { assertNull(feed(on, 80f), "at ${now} ms") }
        assertNull(feed(on, 80f))
        assertEquals(ImpactKind.TILT, feed(on, 80f))
    }

    private fun odo(x: Float, y: Float, valid: Boolean = true) = LogRecord.VisualOdometry(x, y, 0f, valid)

    @Test
    fun `manual bubble alert, off by default`() {
        val off = Run(PilotSettings())
        off.takeOff()
        assertFalse(off.feed(Telemetry(flight = flight(true), odometry = odo(0f, 0f))).outsideFence)
        assertFalse(off.feed(Telemetry(flight = flight(true), odometry = odo(0f, 6f))).outsideFence)

        val on = Run(PilotSettings(geofenceManualAlert = true, geofenceRadius = 5f))
        on.takeOff()
        var y = 10f
        while (y < 14.9f) {
            assertFalse(on.feed(Telemetry(flight = flight(true), odometry = odo(10f, y))).outsideFence, "at $y")
            y += 0.5f
        }
        assertTrue(on.feed(Telemetry(flight = flight(true), odometry = odo(10f, 15.2f))).outsideFence)
        // Landed: the next flight starts a new bubble.
        on.feed(Telemetry(flight = flight(false), odometry = odo(10f, 15.2f)))
        assertFalse(on.feed(Telemetry(flight = flight(true), odometry = odo(10f, 15.3f))).outsideFence)
    }
}
