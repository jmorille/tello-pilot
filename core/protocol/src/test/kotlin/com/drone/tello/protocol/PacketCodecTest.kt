package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PacketCodecTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `crc8 of a header matches a captured frame`() {
        assertEquals(0x7C, Crc.crc8(hex("cc 58 00")))
    }

    @Test
    fun `takeoff packet matches a captured frame`() {
        val packet = PacketCodec.encode(Command.TAKEOFF, sequence = 0x01E4)
        assertArrayEquals(hex("cc 58 00 7c 68 54 00 e4 01 c2 16"), packet)
    }

    @Test
    fun `land packet matches a captured frame`() {
        val packet = PacketCodec.encode(Command.LAND, sequence = 0x01E5, payload = byteArrayOf(0))
        assertArrayEquals(hex("cc 60 00 27 68 55 00 e5 01 00 ba c7"), packet)
    }

    @Test
    fun `palm land and throw takeoff are 11-byte packets of type 0x48`() {
        for (c in listOf(Command.PALM_LAND, Command.THROW_TAKEOFF)) {
            val p = PacketCodec.encode(c, 1)
            assertEquals(11, p.size)
            assertEquals(0x48, p[4].toInt() and 0xFF)
        }
    }

    @Test
    fun `stick packet is 22 bytes of type 0x60 with sequence 0 and the time suffix`() {
        val payload = Payloads.stick(StickInput.Neutral, LocalDateTime.of(2026, 1, 1, 9, 8, 7, 513_000_000))
        val p = PacketCodec.encode(Command.STICK, 99, payload)
        assertEquals(22, p.size)
        assertArrayEquals(hex("cc b0 00"), p.copyOfRange(0, 3))
        assertArrayEquals(hex("60 50 00 00 00"), p.copyOfRange(4, 9))
        assertArrayEquals(hex("09 08 07 01 02"), p.copyOfRange(15, 20)) // h m s, 513 ms LE
    }

    @Test
    fun `stick axes are exact at the extremes and bits 45-47 stay clear`() {
        val payload = Payloads.stick(StickInput(roll = -1f, pitch = 1f, throttle = -1f, yaw = 1f, fastMode = true))
        var packed = 0L
        for (i in 0 until 6) packed = packed or ((payload[i].toLong() and 0xFF) shl (8 * i))
        assertEquals(listOf(364L, 1684L, 364L, 1684L), List(4) { (packed ushr (11 * it)) and 0x7FF })
        assertEquals(1L, packed ushr 44, "only the fast-mode bit above bit 43")
    }

    @Test
    fun `timestamp reply layout`() {
        val p = Payloads.timestamp(LocalDateTime.of(2026, 11, 1, 2, 3, 4, 5_000_000))
        assertEquals(15, p.size)
        assertEquals(0, p[0].toInt())
        assertEquals(126, PacketCodec.getShortLe(p, 1)) // year - 1900
        assertEquals(10, PacketCodec.getShortLe(p, 3))  // 0-based month
        assertEquals(5, PacketCodec.getShortLe(p, 13))  // millis
    }

    @Test
    fun `NaN axis is treated as centre`() {
        assertEquals(1024L, Payloads.axis(Float.NaN))
    }

    @Test
    fun `unsequenced commands always use sequence zero`() {
        val packet = PacketCodec.encode(Command.REQUEST_KEY_FRAME, sequence = 42)
        assertEquals(0, PacketCodec.decode(packet)!!.sequence)
    }

    @Test
    fun `encode then decode round-trips`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val decoded = PacketCodec.decode(PacketCodec.encode(Command.SET_BITRATE, 7, payload))
        assertNotNull(decoded)
        decoded!!
        assertEquals(Command.SET_BITRATE.id, decoded.messageId)
        assertEquals(PacketType.SET, decoded.packetType)
        assertEquals(7, decoded.sequence)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `corrupted packets are rejected`() {
        val packet = PacketCodec.encode(Command.TAKEOFF, 1)
        packet[6] = 0x7F
        assertNull(PacketCodec.decode(packet))
        assertNull(PacketCodec.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `centered sticks encode every axis at 1024`() {
        val payload = Payloads.stick(StickInput.Neutral, LocalDateTime.of(2026, 1, 1, 12, 34, 56))
        var packed = 0L
        for (i in 0 until 6) packed = packed or ((payload[i].toLong() and 0xFF) shl (8 * i))
        for (axis in 0 until 4) assertEquals(1024L, (packed ushr (11 * axis)) and 0x7FF)
        assertEquals(0L, packed ushr 44)
        assertEquals(12, payload[6].toInt())
        assertEquals(34, payload[7].toInt())
        assertEquals(56, payload[8].toInt())
    }

    @Test
    fun `stick extremes and fast mode`() {
        val payload = Payloads.stick(StickInput(roll = 1f, pitch = -1f, throttle = 2f, yaw = 0f, fastMode = true))
        var packed = 0L
        for (i in 0 until 6) packed = packed or ((payload[i].toLong() and 0xFF) shl (8 * i))
        assertEquals(1684L, packed and 0x7FF)
        assertEquals(364L, (packed ushr 11) and 0x7FF)
        assertEquals(1684L, (packed ushr 22) and 0x7FF) // clamped
        assertEquals(1L, (packed ushr 44) and 1)
    }

    @Test
    fun `handshake`() {
        assertArrayEquals("conn_req:".toByteArray() + byteArrayOf(0x61, 0x1E), Handshake.connectRequest(7777))
        assertTrue(Handshake.isConnectAck("conn_ack:a\u001e".toByteArray()))
        assertFalse(Handshake.isConnectAck("conn_req".toByteArray()))
    }
}
