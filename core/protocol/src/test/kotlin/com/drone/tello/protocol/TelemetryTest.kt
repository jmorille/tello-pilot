package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryTest {

    private fun flightPayload(): ByteArray = ByteArray(24).also { p ->
        PacketCodec.putShortLe(p, 0, 15)      // 1.5 m
        PacketCodec.putShortLe(p, 2, 30)      // north 3 m/s
        PacketCodec.putShortLe(p, 4, -40)     // east -4 m/s
        PacketCodec.putShortLe(p, 8, 123)     // 12.3 s
        p[10] = 0b1000_0101.toByte()          // IMU error, vision error, wind
        p[11] = 2                             // IMU calibration error
        p[12] = 87                            // battery
        p[17] = 0b0010_1001                   // flying + hovering + battery low
        p[18] = 12                            // landing
        p[19] = 30                            // throw timer 3 s
        p[20] = 1                             // camera error
        p[21] = 206.toByte()                  // not activated
        p[22] = 0x80.toByte()                 // overheating
    }

    @Test
    fun `flight data is parsed`() {
        val data = FlightData.parse(flightPayload())!!
        assertEquals(1.5f, data.heightMeters)
        assertEquals(-4f, data.eastSpeed)
        assertEquals(5f, data.horizontalSpeed)
        assertEquals(12.3f, data.flyTimeSeconds, 0.001f)
        assertEquals(87, data.batteryPercent)
        assertTrue(data.flying && data.hovering && data.batteryLow)
        assertTrue(data.imuError && data.visionSensorError && data.windWarning)
        assertFalse(data.batteryError || data.gravityCalibrationNeeded)
        assertEquals(2, data.imuCalibrationState)
        assertTrue(data.isLanding)
        assertEquals(30, data.throwTimer)
        assertEquals(1, data.cameraState)
        assertEquals(206, data.motorErrorCode)
        assertTrue(data.overheating)
    }

    @Test
    fun `short flight data from older firmware is accepted`() {
        val data = FlightData.parse(flightPayload().copyOf(21))!!
        assertEquals(0, data.motorErrorCode)
        assertFalse(data.overheating)
        assertEquals(null, FlightData.parse(ByteArray(20)))
    }

    @Test
    fun `firmware version keeps the first 11 characters`() {
        val payload = byteArrayOf(0) + "01.04.92.01 extra-padding-123456".toByteArray()
        assertEquals(DroneMessage.Firmware("01.04.92.01"), DroneMessage.from(Packet(MessageId.FIRMWARE, 0x48, 0, payload)))
    }

    @Test
    fun `settings payloads`() {
        assertArrayEquals(byteArrayOf(30, 0), Payloads.maxAltitude(99))
        assertArrayEquals(byteArrayOf(2, 0), Payloads.maxAltitude(0))
        assertArrayEquals(byteArrayOf(25, 0), Payloads.lowBatteryThreshold(25))
        assertArrayEquals(byteArrayOf(-9), Payloads.exposure(-20))
        assertArrayEquals("FR".toByteArray(), Payloads.region("FR"))
        val stamp = Payloads.activationTimestamp(java.time.LocalDateTime.of(2026, 11, 1, 2, 3, 4, 5_000_000))
        assertEquals(14, stamp.size)
        assertEquals(2026, PacketCodec.getShortLe(stamp, 0))
        assertEquals(11, PacketCodec.getShortLe(stamp, 2), "month is 1-based")
    }

    @Test
    fun `messages are dispatched by id`() {
        val flight = PacketCodec.decode(PacketCodec.encode(Command.STICK, 0, flightPayload()))!!
        val asFlight = Packet(MessageId.FLIGHT_DATA, flight.packetType, 0, flight.payload)
        assertInstanceOf(DroneMessage.Flight::class.java, DroneMessage.from(asFlight))
        val wifi = DroneMessage.from(Packet(MessageId.WIFI, 0x48, 0, byteArrayOf(90, 3)))
        assertEquals(DroneMessage.Wifi(WifiStatus(90, 3)), wifi)
        assertEquals(DroneMessage.TimeRequest, DroneMessage.from(Packet(MessageId.TIME_REQUEST, 0x48, 0, byteArrayOf())))
    }

    @Test
    fun `log ack echoes the log id`() {
        assertArrayEquals(byteArrayOf(0, 0x34, 0x12), Payloads.logAck(0x1234))
    }
}
