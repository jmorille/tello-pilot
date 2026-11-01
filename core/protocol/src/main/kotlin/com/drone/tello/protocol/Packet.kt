package com.drone.tello.protocol

/** A decoded packet of the binary protocol (header and checksums stripped). */
class Packet(
    val messageId: Int,
    val packetType: Int,
    val sequence: Int,
    val payload: ByteArray,
) {
    override fun toString() =
        "Packet(id=$messageId, type=0x${packetType.toString(16)}, seq=$sequence, payload=${payload.size}B)"
}

/**
 * Wire format (little-endian):
 * ```
 * 0      : 0xCC
 * 1..2   : total size << 3
 * 3      : CRC-8 of bytes 0..2
 * 4      : packet type
 * 5..6   : message id
 * 7..8   : sequence number
 * 9..n-3 : payload
 * n-2..n : CRC-16 of bytes 0..n-3
 * ```
 */
object PacketCodec {
    const val MAGIC = 0xCC
    const val OVERHEAD = 11

    fun encode(command: Command, sequence: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val size = payload.size + OVERHEAD
        val out = ByteArray(size)
        out[0] = MAGIC.toByte()
        putShortLe(out, 1, size shl 3)
        out[3] = Crc.crc8(out, 0, 3).toByte()
        out[4] = command.packetType.toByte()
        putShortLe(out, 5, command.id)
        putShortLe(out, 7, if (command.sequenced) sequence else 0)
        payload.copyInto(out, 9)
        putShortLe(out, size - 2, Crc.crc16(out, 0, size - 2))
        return out
    }

    /** Returns the decoded packet, or `null` when [data] is not a valid protocol packet. */
    fun decode(data: ByteArray, length: Int = data.size): Packet? {
        if (length < OVERHEAD || (data[0].toInt() and 0xFF) != MAGIC) return null
        val size = getShortLe(data, 1) ushr 3
        if (size != length) return null
        if (Crc.crc8(data, 0, 3) != (data[3].toInt() and 0xFF)) return null
        if (Crc.crc16(data, 0, size - 2) != getShortLe(data, size - 2)) return null
        return Packet(
            messageId = getShortLe(data, 5),
            packetType = data[4].toInt() and 0xFF,
            sequence = getShortLe(data, 7),
            payload = data.copyOfRange(9, size - 2),
        )
    }

    internal fun putShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    internal fun getShortLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}
