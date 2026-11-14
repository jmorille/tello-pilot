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

    /**
     * A combined attitude (Z-Y-X: yaw 40°, then pitch -25°, then roll 15°): the single-axis
     * cases above leave every cross term (y·z, z·x, x·y) at zero, so a sign error there went
     * unnoticed. The tilt detection reads roll and pitch from here.
     */
    @Test
    fun `combined rotation gives back its roll, pitch and yaw`() {
        fun quaternion(rollDeg: Double, pitchDeg: Double, yawDeg: Double): FloatArray {
            val (cr, sr) = Math.cos(Math.toRadians(rollDeg / 2)) to Math.sin(Math.toRadians(rollDeg / 2))
            val (cp, sp) = Math.cos(Math.toRadians(pitchDeg / 2)) to Math.sin(Math.toRadians(pitchDeg / 2))
            val (cy, sy) = Math.cos(Math.toRadians(yawDeg / 2)) to Math.sin(Math.toRadians(yawDeg / 2))
            return floatArrayOf(
                (cr * cp * cy + sr * sp * sy).toFloat(),
                (sr * cp * cy - cr * sp * sy).toFloat(),
                (cr * sp * cy + sr * cp * sy).toFloat(),
                (cr * cp * sy - sr * sp * cy).toFloat(),
            )
        }
        for ((roll, pitch, yaw) in listOf(Triple(15.0, -25.0, 40.0), Triple(-60.0, 35.0, -150.0))) {
            val (w, x, y, z) = quaternion(roll, pitch, yaw)
            val a = Attitude.fromQuaternion(w, x, y, z)
            assertEquals(roll.toFloat(), a.rollDeg, 0.01f, "roll of $roll/$pitch/$yaw")
            assertEquals(pitch.toFloat(), a.pitchDeg, 0.01f, "pitch of $roll/$pitch/$yaw")
            assertEquals(yaw.toFloat(), a.yawDeg, 0.01f, "yaw of $roll/$pitch/$yaw")
        }
    }

    /**
     * Hybrid records (a lost packet in the middle of a split record) are rejected rather than
     * turned into a false attitude (tilt alarm) or a false position (geofence).
     */
    @Test
    fun `implausible imu and odometry records are rejected`() {
        fun imu(data: ByteArray) = DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 0x31, data))
        fun withSpeed(offset: Int, value: Float) = imuData(1f, 0f, 0f, 0f).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
        }
        // |q| = 1 ± 10 %: |q|² from 0.81 to 1.21.
        assertEquals(1, imu(imuData(0.91f, 0f, 0f, 0f)).size, "|q| 0.91")
        assertEquals(1, imu(imuData(1.09f, 0f, 0f, 0f)).size, "|q| 1.09")
        assertTrue(imu(imuData(0.89f, 0f, 0f, 0f)).isEmpty(), "|q| 0.89")
        assertTrue(imu(imuData(1.11f, 0f, 0f, 0f)).isEmpty(), "|q| 1.11")
        assertTrue(imu(imuData(0f, 0f, 0f, 0f)).isEmpty(), "null quaternion")
        assertTrue(imu(imuData(Float.NaN, 0f, 0f, 0f)).isEmpty(), "NaN quaternion")
        // Speeds up to 30 m/s, on each axis.
        for (offset in listOf(76, 80, 84)) {
            assertEquals(1, imu(withSpeed(offset, -30f)).size, "30 m/s at $offset")
            assertTrue(imu(withSpeed(offset, 30.5f)).isEmpty(), "30.5 m/s at $offset")
            assertTrue(imu(withSpeed(offset, Float.POSITIVE_INFINITY)).isEmpty(), "infinite speed at $offset")
        }
        // Positions within 500 m, on each axis.
        fun mvo(x: Float, y: Float, z: Float) =
            DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_MVO, 0x44, mvoData(x, y, z, true)))
        assertEquals(1, mvo(500f, -500f, 500f).size)
        assertTrue(mvo(500.5f, 0f, 0f).isEmpty())
        assertTrue(mvo(0f, -500.5f, 0f).isEmpty())
        assertTrue(mvo(0f, 0f, Float.NaN).isEmpty())
    }

    @Test
    fun `record sizes - shortest imu and odometry records, length field bounds`() {
        // 88 bytes is the shortest IMU record, 77 the shortest odometry one.
        assertEquals(1, DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 2, imuData(1f, 0f, 0f, 0f, null))).size)
        assertTrue(DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 2, imuData(1f, 0f, 0f, 0f, null).copyOf(87))).isEmpty())
        assertEquals(1, DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_MVO, 2, mvoData(1f, 1f, 0f, true).copyOf(77))).size)
        assertTrue(DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_MVO, 2, mvoData(1f, 1f, 0f, true).copyOf(76))).isEmpty())
        // The temperature needs its two bytes at offset 106.
        val temperature = imuData(1f, 0f, 0f, 0f, 2000)
        assertEquals(20f, (DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 2, temperature.copyOf(108))).single() as LogRecord.Imu).temperatureC)
        assertNull((DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 2, temperature.copyOf(107))).single() as LogRecord.Imu).temperatureC)
        // A 1024-byte record (the largest) decodes; a length field of 1025 is not a record start.
        val largest = imuData(1f, 0f, 0f, 0f, null).copyOf(1024 - DroneLogParser.HEADER - DroneLogParser.CRC16)
        assertEquals(1, DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 2, largest)).size)
        val tooLong = imuData(1f, 0f, 0f, 0f, null).copyOf(1025 - DroneLogParser.HEADER - DroneLogParser.CRC16)
        assertTrue(DroneLogParser().feed(byteArrayOf(0) + record(DroneLogParser.TYPE_IMU, 2, tooLong)).isEmpty())
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
