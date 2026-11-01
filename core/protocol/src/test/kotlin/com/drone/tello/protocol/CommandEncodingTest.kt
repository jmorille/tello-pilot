package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDateTime

/** Byte-level encoding of the commands the app sends, and the checksums. */
class CommandEncodingTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** Decodes [bytes] and checks every header field of the wire format. */
    private fun assertFrame(bytes: ByteArray, id: Int, type: Int, sequence: Int, payload: ByteArray) {
        assertEquals(0xCC, bytes[0].toInt() and 0xFF)
        assertEquals(bytes.size shl 3, (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8))
        assertEquals(Crc.crc8(bytes, 0, 3), bytes[3].toInt() and 0xFF)
        val p = PacketCodec.decode(bytes)
        assertNotNull(p)
        p!!
        assertEquals(id, p.messageId)
        assertEquals(type, p.packetType)
        assertEquals(sequence, p.sequence)
        assertArrayEquals(payload, p.payload)
    }

    @Test
    fun `takeoff, land, palm land and throw takeoff`() {
        assertFrame(PacketCodec.encode(Command.TAKEOFF, 300), 84, 0x68, 300, ByteArray(0))
        assertFrame(PacketCodec.encode(Command.LAND, 301, byteArrayOf(0)), 85, 0x68, 301, byteArrayOf(0))
        assertFrame(PacketCodec.encode(Command.PALM_LAND, 302), 94, 0x48, 302, ByteArray(0))
        assertFrame(PacketCodec.encode(Command.THROW_TAKEOFF, 303), 93, 0x48, 303, ByteArray(0))
    }

    @Test
    fun `video mode, recording flag and photo`() {
        // Photo mode (0) is what the controller sends on connect; 1 = video.
        assertFrame(PacketCodec.encode(Command.SET_VIDEO_MODE, 9, byteArrayOf(0)), 49, 0x68, 9, byteArrayOf(0))
        assertFrame(PacketCodec.encode(Command.SET_VIDEO_MODE, 10, byteArrayOf(1)), 49, 0x68, 10, byteArrayOf(1))
        assertFrame(PacketCodec.encode(Command.SET_RECORDING, 11, byteArrayOf(1)), 50, 0x68, 11, byteArrayOf(1))
        assertFrame(PacketCodec.encode(Command.TAKE_PICTURE, 12), 48, 0x68, 12, ByteArray(0))
        assertFrame(PacketCodec.encode(Command.SET_EIS, 13, byteArrayOf(0)), 36, 0x68, 13, byteArrayOf(0))
    }

    @Test
    fun `key frame request and sticks are unsequenced`() {
        assertFrame(PacketCodec.encode(Command.REQUEST_KEY_FRAME, 77), 37, 0x60, 0, ByteArray(0))
        val stick = Payloads.stick(StickInput(roll = 0.5f), LocalDateTime.of(2026, 3, 4, 5, 6, 7, 0))
        assertFrame(PacketCodec.encode(Command.STICK, 78, stick), 80, 0x60, 0, stick)
    }

    @Test
    fun `stick axes are linear around the centre and the time suffix is little-endian`() {
        val payload = Payloads.stick(
            StickInput(roll = 0.5f, pitch = -0.5f, throttle = 0.25f, yaw = -0.25f),
            LocalDateTime.of(2026, 1, 1, 23, 59, 58, 999_000_000),
        )
        var packed = 0L
        for (i in 0 until 6) packed = packed or ((payload[i].toLong() and 0xFF) shl (8 * i))
        assertEquals(listOf(1354L, 694L, 1189L, 859L), List(4) { (packed ushr (11 * it)) and 0x7FF })
        assertEquals(0L, packed ushr 44)
        assertArrayEquals(hex("17 3b 3a e7 03"), payload.copyOfRange(6, 11)) // 23:59:58.999
        assertEquals(1354, Payloads.axisValue(0.5f))
    }

    @Test
    fun `settings payloads are clamped to the firmware ranges`() {
        assertArrayEquals(byteArrayOf(2, 0), Payloads.maxAltitude(0))
        assertArrayEquals(byteArrayOf(30, 0), Payloads.maxAltitude(500))
        assertArrayEquals(byteArrayOf(10, 0), Payloads.lowBatteryThreshold(1))
        assertArrayEquals(byteArrayOf(50, 0), Payloads.lowBatteryThreshold(90))
        assertArrayEquals(byteArrayOf(-9), Payloads.exposure(-20))
        assertArrayEquals(byteArrayOf(9), Payloads.exposure(20))
    }

    @Test
    fun `activation timestamp and file transfer acks`() {
        val stamp = Payloads.activationTimestamp(LocalDateTime.of(2026, 11, 1, 13, 14, 15, 16_000_000))
        assertArrayEquals(hex("ea 07 0b 00 01 00 0d 00 0e 00 0f 00 10 00"), stamp) // full year, month 1-12
        assertArrayEquals(hex("01 34 12 05 00 00 00"), Payloads.filePieceAck(complete = true, fileId = 0x1234, piece = 5))
        assertArrayEquals(hex("34 12 00 00 01 00"), Payloads.fileDone(0x1234, 65_536))
        assertArrayEquals(hex("46 52"), Payloads.region("FR"))
    }

    @ParameterizedTest
    @EnumSource(Command::class)
    fun `every command round-trips through the codec`(command: Command) {
        val payload = byteArrayOf(1, 2, 3)
        val encoded = PacketCodec.encode(command, 0x1234, payload)
        assertEquals(PacketCodec.OVERHEAD + payload.size, encoded.size)
        assertFrame(encoded, command.id, command.packetType, if (command.sequenced) 0x1234 else 0, payload)
    }

    // --- Checksums ------------------------------------------------------------------------

    @Test
    fun `crc16 matches captured frames`() {
        val takeoff = hex("cc 58 00 7c 68 54 00 e4 01 c2 16")
        assertEquals(0x16C2, Crc.crc16(takeoff, 0, 9))
        val land = hex("cc 60 00 27 68 55 00 e5 01 00 ba c7")
        assertEquals(0xC7BA, Crc.crc16(land, 0, 10))
    }

    @Test
    fun `checksums have a zero residue over data followed by the checksum`() {
        // Reflected CRCs without final XOR: running them over data + CRC (little-endian) gives 0.
        for (n in listOf(0, 1, 3, 9, 64, 300)) {
            val data = ByteArray(n) { (it * 37 + 11).toByte() }
            val c16 = Crc.crc16(data)
            assertEquals(0, Crc.crc16(data + byteArrayOf(c16.toByte(), (c16 ushr 8).toByte())), "crc16 n=$n")
            val c8 = Crc.crc8(data)
            assertEquals(0, Crc.crc8(data + byteArrayOf(c8.toByte())), "crc8 n=$n")
        }
    }

    @Test
    fun `checksums of an empty range are the seeds and offsets are honoured`() {
        assertEquals(0x77, Crc.crc8(ByteArray(0)))
        assertEquals(0x3692, Crc.crc16(ByteArray(0)))
        val framed = byteArrayOf(9, 9) + hex("cc 58 00") + byteArrayOf(9)
        assertEquals(0x7C, Crc.crc8(framed, 2, 3))
        assertEquals(Crc.crc16(hex("cc 58 00")), Crc.crc16(framed, 2, 3))
    }

    @Test
    fun `every single-bit corruption is rejected`() {
        val good = PacketCodec.encode(Command.LAND, 0x0101, byteArrayOf(0))
        for (i in good.indices) for (bit in 0 until 8) {
            val bad = good.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }
            assertNull(PacketCodec.decode(bad), "byte $i bit $bit")
        }
    }
}
