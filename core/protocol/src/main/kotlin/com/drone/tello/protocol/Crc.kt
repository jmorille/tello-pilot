package com.drone.tello.protocol

/**
 * Checksums used by the Tello binary protocol.
 *
 * - CRC-8: reflected polynomial 0x31 (0x8C), seed 0x77, over the 3 header bytes.
 * - CRC-16: reflected CCITT polynomial 0x1021 (0x8408), seed 0x3692, over the whole packet.
 */
object Crc {
    private const val CRC8_SEED = 0x77
    private const val CRC16_SEED = 0x3692

    private val crc8Table = IntArray(256) { index ->
        var crc = index
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        crc
    }

    private val crc16Table = IntArray(256) { index ->
        var crc = index
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        crc
    }

    fun crc8(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = CRC8_SEED
        for (i in offset until offset + length) {
            crc = crc8Table[(crc xor data[i].toInt()) and 0xFF]
        }
        return crc
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = CRC16_SEED
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor crc16Table[(crc xor data[i].toInt()) and 0xFF]
        }
        return crc and 0xFFFF
    }
}
