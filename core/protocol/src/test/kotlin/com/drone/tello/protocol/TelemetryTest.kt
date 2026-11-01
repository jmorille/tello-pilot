package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryTest {

    private fun flightPayload(): ByteArray = ByteArray(24).also { p ->
        PacketCodec.putShortLe(p, 0, 15)      // 1.5 m
        PacketCodec.putShortLe(p, 2, 30)      // north 3 m/s
        PacketCodec.putShortLe(p, 4, -40)     // east -4 m/s
        PacketCodec.putShortLe(p, 8, 123)     // 12.3 s
        p[10] = 0b0000_0001                   // IMU ok
        p[12] = 87                            // battery
        p[17] = 0b0010_1001                   // flying + hovering + battery low
        p[18] = 6
    }

    @Test
    fun `flight data is parsed`() {
        val data = FlightData.parse(flightPayload())!!
        assertEquals(1.5f, data.heightMeters)
        assertEquals(-4f, data.eastSpeed)
        assertEquals(5f, data.horizontalSpeed)
        assertEquals(12.3f, data.flyTimeSeconds, 0.001f)
        assertEquals(87, data.batteryPercent)
        assertTrue(data.flying && data.hovering && data.batteryLow && data.imuOk)
        assertEquals(6, data.flyMode)
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
