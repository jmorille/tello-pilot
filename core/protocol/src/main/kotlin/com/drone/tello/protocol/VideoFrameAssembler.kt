package com.drone.tello.protocol

/**
 * Re-assembles H.264 access units from the video datagrams.
 *
 * Each datagram starts with a 2-byte header:
 * - byte 0: frame sequence number (wraps 255 → 0)
 * - byte 1: bit 7 = last fragment of the frame, bits 0..6 = fragment index
 *
 * The remaining bytes are an Annex-B byte stream; fragment 0 starts with `00 00 00 01`.
 *
 * The fragment index has 7 bits: frames above 128 fragments (~185 KB) cannot be represented
 * and are reported as lost (not expected at the Tello's bitrates; the loss path then simply
 * resynchronises on the next key frame).
 *
 * Losses are detected both inside a frame (fragment gap) and between frames (sequence gap,
 * e.g. a whole single-datagram P-frame lost) — any loss means the following frames reference
 * missing data, so the caller must resynchronise on a key frame.
 */
class VideoFrameAssembler(maxFrameSize: Int = 512 * 1024) {

    sealed interface Result {
        /** A complete frame. [afterLoss] = some data was lost since the previous complete frame. */
        class Frame(val data: ByteArray, val afterLoss: Boolean = false) : Result
        /** Fragment stored, frame not complete yet. */
        data object Pending : Result
        /** Data was lost: a key frame should be requested. */
        data object Lost : Result
    }

    private val buffer = ByteArray(maxFrameSize)
    private var size = 0
    private var frameSeq = -1
    private var lastFragment = -1
    private var assembling = false
    private var lastCompletedSeq = -1
    private var lossPending = false

    fun push(datagram: ByteArray, length: Int = datagram.size): Result {
        if (length <= HEADER) return Result.Pending
        val seq = datagram[0].toInt() and 0xFF
        val flags = datagram[1].toInt() and 0xFF
        val fragment = flags and 0x7F
        val last = flags and 0x80 != 0

        if (fragment == 0) {
            // Duplicates (Wi-Fi retransmissions) of the current or of the last completed frame.
            if (assembling && seq == frameSeq && lastFragment == 0) return Result.Pending
            if (!assembling && seq == lastCompletedSeq) return Result.Pending
            val wasAssembling = assembling
            val expected = if (lastCompletedSeq < 0) seq else (lastCompletedSeq + 1) and 0xFF
            if (wasAssembling || seq != expected) lossPending = true
            if (!hasStartCode(datagram, length)) {
                assembling = false
                lossPending = true
                return Result.Lost
            }
            size = 0
            frameSeq = seq
            assembling = true
        } else if (!assembling || seq != frameSeq || fragment != lastFragment + 1) {
            if (fragment == lastFragment && seq == frameSeq && assembling) return Result.Pending // duplicate
            val wasAssembling = assembling
            assembling = false
            if (wasAssembling) {
                lossPending = true
                return Result.Lost
            }
            return Result.Pending
        }

        val chunk = length - HEADER
        if (size + chunk > buffer.size) {
            assembling = false
            lossPending = true
            return Result.Lost
        }
        datagram.copyInto(buffer, size, HEADER, length)
        size += chunk
        lastFragment = fragment

        if (last) {
            assembling = false
            lastCompletedSeq = frameSeq
            val afterLoss = lossPending
            lossPending = false
            return Result.Frame(buffer.copyOf(size), afterLoss)
        }
        return if (lossPending && fragment == 0) Result.Lost else Result.Pending
    }

    private fun hasStartCode(d: ByteArray, length: Int) =
        length >= HEADER + 4 && d[2].toInt() == 0 && d[3].toInt() == 0 && d[4].toInt() == 0 && d[5].toInt() == 1

    private companion object {
        const val HEADER = 2
    }
}

/** Helpers on Annex-B H.264 byte streams. */
object H264 {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** Types of the NAL units contained in [frame]. */
    fun nalTypes(frame: ByteArray): List<Int> = buildList {
        var i = 0
        while (i + 3 < frame.size) {
            if (frame[i].toInt() == 0 && frame[i + 1].toInt() == 0) {
                val startLen = when {
                    frame[i + 2].toInt() == 1 -> 3
                    frame[i + 2].toInt() == 0 && frame[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
                if (startLen > 0 && i + startLen < frame.size) {
                    add(frame[i + startLen].toInt() and 0x1F)
                    i += startLen
                    continue
                }
            }
            i++
        }
    }

    fun containsConfig(frame: ByteArray) = NAL_SPS in nalTypes(frame)
    fun isKeyFrame(frame: ByteArray) = nalTypes(frame).let { NAL_IDR in it || NAL_SPS in it }
}
