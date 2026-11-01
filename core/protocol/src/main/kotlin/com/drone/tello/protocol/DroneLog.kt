package com.drone.tello.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Records of the drone's internal log stream (message 4177), decoded like the TelloMe app
 * (`M2/f.java`) and the open-source Tello libraries:
 *
 * payload = `[00]` then records, possibly split across packets:
 * `55 | len u16 (whole record) | crc8 | type u16 | key | 3 bytes | data… | crc16`,
 * where everything after the 4 bytes following the type is XORed with `key`.
 *
 * Field offsets are relative to the start of `data` (record offset 10). Offsets marked
 * "unverified" come from third-party libraries, not from the decompiled apps.
 */
sealed interface LogRecord {
    /** 0x001D: visual odometry (downward camera). Position in metres from the takeoff point. */
    data class VisualOdometry(
        val x: Float,
        val y: Float,
        /** Unverified (third-party): read but not used by TelloMe. */
        val z: Float,
        /** Both bits 0x30 of data[76] set: the position can be trusted (TelloMe). */
        val positionValid: Boolean,
    ) : LogRecord

    /** 0x0800: inertial measurement unit. */
    data class Imu(
        val qw: Float,
        val qx: Float,
        val qy: Float,
        val qz: Float,
        /** Velocities north / east / down, m/s (TelloMe: horizontal speed = hypot(vn, ve)). */
        val velocityNorth: Float,
        val velocityEast: Float,
        val velocityDown: Float,
        /** Unverified offset (third-party): °C, `null` when the record is too short. */
        val temperatureC: Float?,
    ) : LogRecord {
        /** Attitude in degrees from the quaternion (standard aerospace Z-Y-X convention). */
        val attitude: Attitude get() = Attitude.fromQuaternion(qw, qx, qy, qz)
    }
}

/** Roll (right wing down > 0), pitch (nose up > 0), yaw / heading (clockwise > 0), degrees. */
data class Attitude(val rollDeg: Float, val pitchDeg: Float, val yawDeg: Float) {
    companion object {
        private const val DEG = (180.0 / Math.PI)

        fun fromQuaternion(w: Float, x: Float, y: Float, z: Float): Attitude {
            val sinr = 2.0 * (w * x + y * z)
            val cosr = 1.0 - 2.0 * (x * x + y * y)
            val sinp = (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0)
            val siny = 2.0 * (w * z + x * y)
            val cosy = 1.0 - 2.0 * (y * y + z * z)
            return Attitude(
                rollDeg = (atan2(sinr, cosr) * DEG).toFloat(),
                pitchDeg = (asin(sinp) * DEG).toFloat(),
                yawDeg = (atan2(siny, cosy) * DEG).toFloat(),
            )
        }
    }
}

/**
 * Stateful parser of 4177 payloads: keeps an incomplete record until the next packet.
 * Not thread-safe (one instance per receive loop).
 */
class DroneLogParser(private val maxPending: Int = 4096) {
    private var pending: ByteArray = ByteArray(0)

    /** Records decoded from one 4177 payload (unknown record types are skipped). */
    fun feed(payload: ByteArray): List<LogRecord> {
        if (payload.isEmpty() || payload[0].toInt() != 0) return emptyList()
        var data = if (pending.isEmpty()) payload.copyOfRange(1, payload.size) else pending + payload.copyOfRange(1, payload.size)
        pending = ByteArray(0)
        val out = mutableListOf<LogRecord>()
        var i = 0
        while (i < data.size) {
            if (data[i] != MAGIC) {
                i++ // resynchronise on the next magic byte
                continue
            }
            if (data.size - i < HEADER) {
                pending = data.copyOfRange(i, data.size)
                break
            }
            val length = PacketCodec.getShortLe(data, i + 1)
            if (length < HEADER + CRC16 || length > MAX_RECORD) {
                i++ // not a real record start
                continue
            }
            if (data.size - i < length) {
                pending = data.copyOfRange(i, data.size)
                break
            }
            decode(data, i, length)?.let(out::add)
            i += length
        }
        if (pending.size > maxPending) pending = ByteArray(0)
        data = ByteArray(0)
        return out
    }

    private fun decode(src: ByteArray, start: Int, length: Int): LogRecord? {
        val type = PacketCodec.getShortLe(src, start + 4)
        if (type != TYPE_MVO && type != TYPE_IMU) return null
        val key = src[start + 6]
        // Data = record minus header (10) and trailing CRC16, un-XORed.
        val data = ByteArray(length - HEADER - CRC16) { (src[start + HEADER + it].toInt() xor key.toInt()).toByte() }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            TYPE_MVO -> if (data.size < MVO_SIZE) null else LogRecord.VisualOdometry(
                x = b.getFloat(8),
                y = b.getFloat(12),
                z = b.getFloat(16),
                positionValid = (data[76].toInt() and 0x30) == 0x30,
            )
            else -> if (data.size < IMU_SIZE) null else LogRecord.Imu(
                qw = b.getFloat(48),
                qx = b.getFloat(52),
                qy = b.getFloat(56),
                qz = b.getFloat(60),
                velocityNorth = b.getFloat(76),
                velocityEast = b.getFloat(80),
                velocityDown = b.getFloat(84),
                temperatureC = if (data.size >= TEMPERATURE_OFFSET + 2) b.getShort(TEMPERATURE_OFFSET) / 100f else null,
            )
        }
    }

    companion object {
        private const val MAGIC: Byte = 0x55
        /** 55, len (2), crc8, type (2), key, 3 bytes. */
        const val HEADER = 10
        const val CRC16 = 2
        private const val MAX_RECORD = 1024
        const val TYPE_MVO = 0x001D
        const val TYPE_IMU = 0x0800
        private const val MVO_SIZE = 77
        private const val IMU_SIZE = 88
        private const val TEMPERATURE_OFFSET = 106
    }
}
