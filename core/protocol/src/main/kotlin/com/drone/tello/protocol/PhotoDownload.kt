package com.drone.tello.protocol

/**
 * Receives a photo pushed by the drone after [Command.TAKE_PICTURE], following the official
 * app's download protocol (`analysis/PROTOCOLE_PHASE2.md` §1.2):
 *
 * ```
 * drone → 98 [u8 type][u32 size][u16 fileId]           app → 98 ack [00]
 * drone → 99 [u16 fileId][u32 piece][u32 chunk][u16 len][data]  (chunks ≤ 1024 B, 8 per piece)
 *                                                      app → 99 ack [00][fileId][piece] per complete piece
 * last chunk received                                  app → 99 ack [01][fileId][piece], then 100 [fileId][size]
 * ```
 * A piece is only acknowledged once complete; a duplicated chunk of an already acknowledged
 * piece re-sends its ack (the first one may have been lost). Every chunk except the last must
 * be exactly 1024 bytes, and the photo completes only when every byte has been received.
 * Pure state machine: it returns the packets to send.
 */
class PhotoDownload(private val maxSize: Int = 8 * 1024 * 1024) {

    sealed interface Event {
        /** Send these acks / commands to the drone (in order). */
        data class Reply(val commands: List<Pair<Command, ByteArray>>) : Event
        /** Photo complete: JPEG bytes (plus the final acks to send). */
        class Completed(val jpeg: ByteArray, val commands: List<Pair<Command, ByteArray>>) : Event
        data object None : Event
    }

    private var buffer: ByteArray? = null
    private var size = 0
    private var fileId = 0
    private var chunkCount = 0
    private var received = BooleanArray(0)
    private var receivedChunks = 0
    private var receivedBytes = 0
    private var ackedPieces = HashSet<Int>()

    val inProgress: Boolean get() = buffer != null

    /** Progress in 0..1, or null when idle. */
    val progress: Float? get() = buffer?.let { if (size == 0) 0f else receivedBytes.toFloat() / size }

    /** 98 FILE_SIZE from the drone (only when it is not a reply, i.e. type & 0x10 == 0). */
    fun onFileSize(packet: Packet): Event {
        if (packet.packetType and PacketType.REPLY_BIT != 0) return Event.None // firmware-upload path
        val p = packet.payload
        if (p.size < 7) return Event.None
        val newSize = getIntLe(p, 1)
        if (newSize <= 0 || newSize > maxSize) return Event.None
        val newId = PacketCodec.getShortLe(p, 5)
        val ack = Event.Reply(listOf(Command.ACK_FILE_SIZE to byteArrayOf(0)))
        // The same announcement repeated mid-transfer (our ack was lost): re-ack, keep the data.
        if (buffer != null && newId == fileId && newSize == size) return ack
        // Any other 98 restarts the transfer (as the official app does).
        size = newSize
        fileId = newId
        chunkCount = (size + CHUNK - 1) / CHUNK
        buffer = ByteArray(size)
        received = BooleanArray(chunkCount)
        receivedChunks = 0
        receivedBytes = 0
        ackedPieces = HashSet()
        return ack
    }

    /** 99 FILE_DATA chunk. */
    fun onFileData(packet: Packet): Event {
        val buf = buffer ?: return Event.None
        val p = packet.payload
        if (p.size < 12) return Event.None
        if (PacketCodec.getShortLe(p, 0) != fileId) return Event.None // late chunk of an older transfer
        val piece = getIntLe(p, 2)
        val chunk = getIntLe(p, 6)
        val len = PacketCodec.getShortLe(p, 10)
        if (chunk !in 0 until chunkCount || 12 + len > p.size) return Event.None
        val offset = chunk * CHUNK
        // Exact length only: a short chunk would otherwise leave a silent hole in the JPEG.
        if (len != minOf(CHUNK, size - offset)) return Event.None
        if (received[chunk]) {
            // Duplicate: the drone resends a piece whose ack it did not get → ack it again.
            val local = chunk / CHUNKS_PER_PIECE
            return if (local in ackedPieces) {
                Event.Reply(listOf(Command.ACK_FILE_PIECE to Payloads.filePieceAck(false, fileId, piece)))
            } else {
                Event.None
            }
        }
        p.copyInto(buf, offset, 12, 12 + len)
        received[chunk] = true
        receivedChunks++
        receivedBytes += len

        // Completeness is computed from the global chunk index (8 chunks per piece); the ack
        // echoes the piece number sent by the drone, as the official app does.
        val localPiece = chunk / CHUNKS_PER_PIECE
        val replies = mutableListOf<Pair<Command, ByteArray>>()
        if (localPiece !in ackedPieces && pieceComplete(localPiece)) {
            ackedPieces += localPiece
            replies += Command.ACK_FILE_PIECE to Payloads.filePieceAck(false, fileId, piece)
        }
        if (receivedChunks == chunkCount && receivedBytes == size) {
            replies += Command.ACK_FILE_PIECE to Payloads.filePieceAck(true, fileId, piece)
            replies += Command.FILE_DONE to Payloads.fileDone(fileId, size)
            buffer = null
            return Event.Completed(buf, replies)
        }
        return if (replies.isEmpty()) Event.None else Event.Reply(replies)
    }

    /** Drops an unfinished transfer (timeout / disconnection). */
    fun reset() {
        buffer = null
    }

    private fun pieceComplete(piece: Int): Boolean {
        val first = piece * CHUNKS_PER_PIECE
        val last = minOf(first + CHUNKS_PER_PIECE, chunkCount)
        return (first until last).all { received[it] }
    }

    private fun getIntLe(p: ByteArray, o: Int): Int =
        (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8) or
            ((p[o + 2].toInt() and 0xFF) shl 16) or ((p[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val CHUNK = 1024
        const val CHUNKS_PER_PIECE = 8
    }
}
