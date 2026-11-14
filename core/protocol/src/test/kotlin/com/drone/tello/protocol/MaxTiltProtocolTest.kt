package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Max tilt angle of the fast mode: 4184 (set) / 4185 (query), as in the official app. */
class MaxTiltProtocolTest {

    private fun reply(vararg payload: Byte) = DroneMessage.from(Packet(MessageId.MAX_TILT_QUERY, 0x48 or PacketType.REPLY_BIT, 3, payload))

    private fun le(value: Float): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(4) { (bits ushr (8 * it)).toByte() }
    }

    @Test
    fun `ids and packet types match the official app`() {
        assertEquals(4184 to 0x68, Command.SET_MAX_TILT.id to Command.SET_MAX_TILT.packetType)
        assertEquals(4185 to 0x48, Command.QUERY_MAX_TILT.id to Command.QUERY_MAX_TILT.packetType)
        assertEquals(Command.SET_MAX_TILT.id, MessageId.MAX_TILT)
        assertEquals(Command.QUERY_MAX_TILT.id, MessageId.MAX_TILT_QUERY)
    }

    @Test
    fun `set payload is a little-endian float32 in degrees`() {
        assertArrayEquals(byteArrayOf(0, 0, 0xC8.toByte(), 0x41), Payloads.maxTilt(25f)) // 0x41C80000
        assertArrayEquals(byteArrayOf(0, 0, 0x70, 0x41), Payloads.maxTilt(15f)) // 0x41700000
        assertArrayEquals(byteArrayOf(0, 0, 0xA0.toByte(), 0x41), Payloads.maxTilt(20f)) // 0x41A00000
        assertArrayEquals(le(17.5f), Payloads.maxTilt(17.5f))
    }

    @Test
    fun `set payload is clamped to 15-25 and NaN gives the official default`() {
        assertArrayEquals(le(15f), Payloads.maxTilt(3f))
        assertArrayEquals(le(25f), Payloads.maxTilt(40f))
        assertArrayEquals(le(25f), Payloads.maxTilt(Float.NaN))
        assertArrayEquals(le(25f), Payloads.maxTilt(Float.POSITIVE_INFINITY))
        assertArrayEquals(le(15f), Payloads.maxTilt(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `set packet round-trips through the codec`() {
        val packet = PacketCodec.decode(PacketCodec.encode(Command.SET_MAX_TILT, 42, Payloads.maxTilt(22f)))!!
        assertEquals(4184, packet.messageId)
        assertEquals(0x68, packet.packetType)
        assertEquals(42, packet.sequence)
        assertArrayEquals(le(22f), packet.payload)
    }

    @Test
    fun `query has no payload`() {
        val packet = PacketCodec.decode(PacketCodec.encode(Command.QUERY_MAX_TILT, 9))!!
        assertEquals(4185, packet.messageId)
        assertEquals(0x48, packet.packetType)
        assertEquals(0, packet.payload.size)
    }

    @Test
    fun `query reply is status then float32`() {
        assertEquals(DroneMessage.MaxTilt(25f), reply(0, *le(25f)))
        assertEquals(DroneMessage.MaxTilt(18f), reply(0, *le(18f)))
        // Extra trailing bytes are ignored.
        assertEquals(DroneMessage.MaxTilt(20f), reply(0, *le(20f), 7, 7))
    }

    @Test
    fun `failed, short or implausible replies give no value`() {
        assertEquals(DroneMessage.MaxTilt(null), reply(1, *le(25f)), "status != 0")
        assertEquals(DroneMessage.MaxTilt(null), reply(0, 0, 0, 0xC8.toByte()), "short")
        assertEquals(DroneMessage.MaxTilt(null), reply(0, *le(Float.NaN)))
        assertEquals(DroneMessage.MaxTilt(null), reply(0, *le(Float.POSITIVE_INFINITY)))
        assertEquals(DroneMessage.MaxTilt(null), reply(0, *le(0f)))
        assertEquals(DroneMessage.MaxTilt(null), reply(0, *le(90f)))
        assertEquals(DroneMessage.MaxTilt(null), reply(0, *le(-20f)))
        assertNull(Payloads.parseMaxTilt(ByteArray(0)))
        // Empty payload: not decoded at all.
        assertInstanceOf(DroneMessage.Other::class.java, reply())
    }

    @Test
    fun `set reply is a status result`() {
        val ok = DroneMessage.from(Packet(MessageId.MAX_TILT, 0x68 or PacketType.REPLY_BIT, 3, byteArrayOf(0)))
        assertEquals(DroneMessage.CommandResult(MessageId.MAX_TILT, success = true), ok)
        val ko = DroneMessage.from(Packet(MessageId.MAX_TILT, 0x68 or PacketType.REPLY_BIT, 3, byteArrayOf(1)))
        assertEquals(DroneMessage.CommandResult(MessageId.MAX_TILT, success = false), ko)
    }
}
