package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DroneLogTest {

    /** Builds a record as the drone sends it: header, data XORed with [key], CRC16 placeholder. */
    private fun record(type: Int, key: Int, data: ByteArray): ByteArray {
        val length = DroneLogParser.HEADER + data.size + DroneLogParser.CRC16
        val r = ByteArray(length)
        r[0] = 0x55
        PacketCodec.putShortLe(r, 1, length)
        r[3] = 0x11 // crc8, not checked
        PacketCodec.putShortLe(r, 4, type)
        r[6] = key.toByte()
        data.forEachIndexed { i, b -> r[DroneLogParser.HEADER + i] = (b.toInt() xor key).toByte() }
        return r
    }

    private fun imuData(w: Float, x: Float, y: Float, z: Float, temperatureCenti: Int? = 4250): ByteArray {
        val b = ByteBuffer.allocate(if (temperatureCenti != null) 120 else 88).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(48, w).putFloat(52, x).putFloat(56, y).putFloat(60, z)
        b.putFloat(76, 1.5f).putFloat(80, -0.5f).putFloat(84, 0.25f)
        if (temperatureCenti != null) b.putShort(106, temperatureCenti.toShort())
        return b.array()
    }

    private fun mvoData(x: Float, y: Float, z: Float, valid: Boolean): ByteArray {
        val b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(8, x).putFloat(12, y).putFloat(16, z)
        b.put(76, if (valid) 0x30 else 0x10)
        return b.array()
    }

    @Test
    fun `imu record is un-XORed and decoded`() {
        val payload = byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 0x5A, imuData(1f, 0f, 0f, 0f))
        val imu = assertInstanceOf(LogRecord.Imu::class.java, DroneLogParser().feed(payload).single())
        assertEquals(1f, imu.qw)
        assertEquals(1.5f, imu.velocityNorth)
        assertEquals(0.25f, imu.velocityDown)
        assertEquals(42.5f, imu.temperatureC!!, 0.001f)
        val level = imu.attitude
        assertEquals(0f, level.rollDeg, 0.01f)
        assertEquals(0f, level.pitchDeg, 0.01f)
        assertEquals(0f, level.yawDeg, 0.01f)
    }

    @Test
    fun `short imu record has no temperature`() {
        val payload = byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 3, imuData(1f, 0f, 0f, 0f, null))
        assertNull((DroneLogParser().feed(payload).single() as LogRecord.Imu).temperatureC)
    }

    @Test
    fun `quaternion to attitude`() {
        val h = Math.sqrt(0.5).toFloat()
        // 90° about Z: heading 90.
        assertEquals(90f, Attitude.fromQuaternion(h, 0f, 0f, h).yawDeg, 0.01f)
        // 90° about X: roll 90.
        assertEquals(90f, Attitude.fromQuaternion(h, h, 0f, 0f).rollDeg, 0.01f)
        // 30° about Y: pitch 30.
        val s = Math.sin(Math.toRadians(15.0)).toFloat()
        val c = Math.cos(Math.toRadians(15.0)).toFloat()
        assertEquals(30f, Attitude.fromQuaternion(c, 0f, s, 0f).pitchDeg, 0.01f)
    }

    @Test
    fun `visual odometry record`() {
        val payload = byteArrayOf(0) + record(DroneLogParser.TYPE_MVO, 0x7F, mvoData(1.25f, -2f, 0.8f, true))
        val mvo = assertInstanceOf(LogRecord.VisualOdometry::class.java, DroneLogParser().feed(payload).single())
        assertEquals(1.25f, mvo.x)
        assertEquals(-2f, mvo.y)
        assertTrue(mvo.positionValid)
        val invalid = DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_MVO, 1, mvoData(0f, 0f, 0f, false)))
        assertFalse((invalid.single() as LogRecord.VisualOdometry).positionValid)
    }

    @Test
    fun `record split across packets, unknown types and garbage are handled`() {
        val other = record(0x0123, 9, ByteArray(20))
        val imu = record(DroneLogParser.TYPE_IMU, 0x22, imuData(1f, 0f, 0f, 0f))
        val stream = byteArrayOf(0x01, 0x02) + other + imu
        val parser = DroneLogParser()
        val cut = stream.size - 30
        assertTrue(parser.feed(byteArrayOf(0) + stream.copyOfRange(0, cut)).isEmpty())
        val records = parser.feed(byteArrayOf(0) + stream.copyOfRange(cut, stream.size))
        assertInstanceOf(LogRecord.Imu::class.java, records.single())
    }

    @Test
    fun `payload not starting with 0 is ignored`() {
        assertTrue(DroneLogParser().feed(byteArrayOf(1) + record(DroneLogParser.TYPE_IMU, 0, imuData(1f, 0f, 0f, 0f))).isEmpty())
        assertTrue(DroneLogParser().feed(ByteArray(0)).isEmpty())
    }
}
