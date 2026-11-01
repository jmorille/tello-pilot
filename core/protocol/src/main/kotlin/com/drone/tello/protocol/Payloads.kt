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

    /** Axis value sent to the drone (364..1684, centre 1024): for display. */
    fun axisValue(value: Float): Int = axis(value).toInt()

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

    /**
     * Activation date for a brand-new drone, as the official app encodes it:
     * 7 x u16 = full year, month 1-12, day, hour, minute, second, millisecond.
     */
    fun activationTimestamp(now: LocalDateTime = LocalDateTime.now()): ByteArray =
        shorts(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, now.nano / 1_000_000)

    /** Wi-Fi region: 2 ASCII characters, no terminator (official app). */
    fun region(code: String): ByteArray {
        require(code.length == 2 && code.all { it.code < 128 }) { "region must be a 2-letter ASCII code" }
        return code.toByteArray(Charsets.US_ASCII)
    }

    /** Max altitude in metres as u16 (clamped to the firmware range 2..30). */
    fun maxAltitude(meters: Int): ByteArray = shorts(meters.coerceIn(2, 30))

    /** Low-battery warning threshold in percent as u16 (clamped to 10..50). */
    fun lowBatteryThreshold(percent: Int): ByteArray = shorts(percent.coerceIn(10, 50))

    /** Exposure compensation, i8 in 1/3 EV steps (clamped to -9..9). */
    fun exposure(steps: Int): ByteArray = byteArrayOf(steps.coerceIn(-9, 9).toByte())

    /** Ack of a file piece (flag 0) or of the whole file (flag 1): [flag][u16 fileId][u32 piece]. */
    fun filePieceAck(complete: Boolean, fileId: Int, piece: Int): ByteArray = ByteArray(7).also {
        it[0] = if (complete) 1 else 0
        PacketCodec.putShortLe(it, 1, fileId)
        putIntLe(it, 3, piece)
    }

    /** End of a file transfer: [u16 fileId][u32 size]. */
    fun fileDone(fileId: Int, size: Int): ByteArray = ByteArray(6).also {
        PacketCodec.putShortLe(it, 0, fileId)
        putIntLe(it, 2, size)
    }

    private fun shorts(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { out ->
        values.forEachIndexed { i, v -> PacketCodec.putShortLe(out, i * 2, v) }
    }

    private fun putIntLe(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = (value ushr (8 * i)).toByte()
    }

    /** Acknowledges a log header so the drone keeps streaming telemetry. */
    fun logAck(logId: Int): ByteArray =
        ByteArray(3).also { PacketCodec.putShortLe(it, 1, logId) }

    fun flip(direction: FlipDirection): ByteArray = byteArrayOf(direction.code.toByte())

    /** Smart video: `(start & 3) | (mode & 7) << 2` (official `d.a(int, int)`). */
    fun smartVideo(start: Boolean, mode: SmartVideoMode): ByteArray =
        byteArrayOf(((if (start) 1 else 0) or (mode.code shl 2)).toByte())

    /** Bounce: 0x30 enter, 0x31 leave (official `c(48)` / `c(49)`). */
    fun bounce(enter: Boolean): ByteArray = byteArrayOf(if (enter) 0x30 else 0x31)

    /** SSID: 1..32 printable ASCII characters (official limit), no terminator. */
    fun ssid(name: String): ByteArray {
        require(name.length in 1..32 && name.all { it.code in 0x20..0x7E }) { "SSID: 1..32 printable ASCII characters" }
        return name.toByteArray(Charsets.US_ASCII)
    }

    /** Wi-Fi password: empty (open network) or 8..63 printable ASCII characters (WPA2). */
    fun wifiPassword(password: String): ByteArray {
        require((password.isEmpty() || password.length in 8..63) && password.all { it.code in 0x20..0x7E }) {
            "password: empty or 8..63 printable ASCII characters"
        }
        return password.toByteArray(Charsets.US_ASCII)
    }

    fun singleByte(value: Int): ByteArray = byteArrayOf(value.toByte())
}
