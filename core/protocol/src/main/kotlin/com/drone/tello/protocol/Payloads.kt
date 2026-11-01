package com.drone.tello.protocol

import java.time.LocalDateTime
import kotlin.math.roundToLong

/** Plain-text handshake exchanged on the control port before the binary protocol starts. */
object Handshake {
    private val REQUEST = "conn_req:".toByteArray(Charsets.US_ASCII)
    private val ACK = "conn_ack".toByteArray(Charsets.US_ASCII)

    /** Asks the drone to stream video to [videoPort] on the phone. */
    fun connectRequest(videoPort: Int): ByteArray =
        REQUEST + byteArrayOf(videoPort.toByte(), (videoPort ushr 8).toByte())

    fun isConnectAck(data: ByteArray, length: Int = data.size): Boolean =
        length >= ACK.size && ACK.indices.all { data[it] == ACK[it] }
}

/**
 * Stick positions, each in `-1f..1f` (Mode 2 layout).
 * @property roll right stick X, @property pitch right stick Y,
 * @property throttle left stick Y, @property yaw left stick X.
 */
data class StickInput(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val throttle: Float = 0f,
    val yaw: Float = 0f,
    val fastMode: Boolean = false,
) {
    companion object {
        val Neutral = StickInput()
    }
}

object Payloads {
    private const val AXIS_CENTER = 1024
    private const val AXIS_RANGE = 660

    /** 11-bit axis value as expected by the firmware (364..1684, centre 1024). */
    internal fun axis(value: Float): Long {
        val v = if (value.isNaN()) 0f else value.coerceIn(-1f, 1f)
        return (AXIS_CENTER + v * AXIS_RANGE).roundToLong() and 0x7FF
    }

    /** 4 x 11-bit axes + fast-mode flag packed in 6 bytes, followed by a 5-byte time stamp. */
    fun stick(input: StickInput, now: LocalDateTime = LocalDateTime.now()): ByteArray {
        val packed = axis(input.roll) or
            (axis(input.pitch) shl 11) or
            (axis(input.throttle) shl 22) or
            (axis(input.yaw) shl 33) or
            (if (input.fastMode) 1L shl 44 else 0L)
        val millis = now.nano / 1_000_000
        return ByteArray(11).also { out ->
            for (i in 0 until 6) out[i] = (packed ushr (8 * i)).toByte()
            out[6] = now.hour.toByte()
            out[7] = now.minute.toByte()
            out[8] = now.second.toByte()
            out[9] = millis.toByte()
            out[10] = (millis ushr 8).toByte()
        }
    }

    /**
     * Answer to [MessageId.TIME_REQUEST]: status byte + 7 little-endian shorts.
     * Layout follows TelloMe (year-1900, 0-based month, millis); the official app sends the
     * full year / 1-based month. The drone only uses it to time-stamp its logs.
     */
    fun timestamp(now: LocalDateTime = LocalDateTime.now()): ByteArray {
        val fields = intArrayOf(
            now.year - 1900, now.monthValue - 1, now.dayOfMonth,
            now.hour, now.minute, now.second, now.nano / 1_000_000,
        )
        val out = ByteArray(1 + fields.size * 2)
        fields.forEachIndexed { i, v -> PacketCodec.putShortLe(out, 1 + i * 2, v) }
        return out
    }

    /** Acknowledges a log header so the drone keeps streaming telemetry. */
    fun logAck(logId: Int): ByteArray =
        ByteArray(3).also { PacketCodec.putShortLe(it, 1, logId) }

    fun flip(direction: FlipDirection): ByteArray = byteArrayOf(direction.code.toByte())

    fun singleByte(value: Int): ByteArray = byteArrayOf(value.toByte())
}
