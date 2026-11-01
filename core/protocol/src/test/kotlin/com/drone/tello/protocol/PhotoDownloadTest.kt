package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PhotoDownloadTest {

    private fun le32(v: Int) = ByteArray(4) { (v ushr (8 * it)).toByte() }
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun fileSize(size: Int, fileId: Int, type: Int = 0x48) =
        Packet(MessageId.FILE_SIZE, type, 0, byteArrayOf(1) + le32(size) + le16(fileId))

    private fun chunk(fileId: Int, chunk: Int, data: ByteArray) =
        Packet(MessageId.FILE_DATA, 0x48, 0, le16(fileId) + le32(chunk / 8) + le32(chunk) + le16(data.size) + data)

    private val photo = Random(42).nextBytes(10 * 1024 + 300) // 11 chunks → pieces 0 (8) and 1 (3)

    private fun chunksOf(bytes: ByteArray) = bytes.toList().chunked(1024).map { it.toByteArray() }

    @Test
    fun `file size is acknowledged with 98 payload 00`() {
        val d = PhotoDownload()
        val e = d.onFileSize(fileSize(photo.size, 7)) as PhotoDownload.Event.Reply
        assertEquals(Command.ACK_FILE_SIZE, e.commands.single().first)
        assertArrayEquals(byteArrayOf(0), e.commands.single().second)
        assertTrue(d.inProgress)
    }

    @Test
    fun `a 98 reply (firmware upload path) is ignored`() {
        val d = PhotoDownload()
        assertEquals(PhotoDownload.Event.None, d.onFileSize(fileSize(100, 1, type = 0x58)))
        assertFalse(d.inProgress)
    }

    @Test
    fun `pieces are acked when complete, then the file, then FILE_DONE`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(photo.size, 7))
        val chunks = chunksOf(photo)
        val events = chunks.mapIndexed { i, c -> d.onFileData(chunk(7, i, c)) }

        // piece 0 completes at chunk 7
        assertEquals(PhotoDownload.Event.None, events[6])
        val piece0 = events[7] as PhotoDownload.Event.Reply
        assertArrayEquals(Payloads.filePieceAck(false, 7, 0), piece0.commands.single().second)

        val done = assertInstanceOf(PhotoDownload.Event.Completed::class.java, events.last())
        assertArrayEquals(photo, done.jpeg)
        assertEquals(
            listOf(Command.ACK_FILE_PIECE, Command.ACK_FILE_PIECE, Command.FILE_DONE),
            done.commands.map { it.first },
        )
        assertArrayEquals(Payloads.filePieceAck(false, 7, 1), done.commands[0].second)
        assertArrayEquals(Payloads.filePieceAck(true, 7, 1), done.commands[1].second)
        assertArrayEquals(Payloads.fileDone(7, photo.size), done.commands[2].second)
        assertFalse(d.inProgress)
    }

    @Test
    fun `chunks out of order and duplicates are handled`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(photo.size, 3))
        val chunks = chunksOf(photo)
        // reverse order, every chunk received twice
        val events = chunks.indices.reversed().flatMap { i ->
            listOf(d.onFileData(chunk(3, i, chunks[i])), d.onFileData(chunk(3, i, chunks[i])))
        }
        val completed = events.filterIsInstance<PhotoDownload.Event.Completed>()
        assertEquals(1, completed.size, "completed exactly once")
        assertArrayEquals(photo, completed.single().jpeg)
    }

    @Test
    fun `a duplicated chunk of an acked piece re-sends the ack, otherwise nothing`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(8 * 1024 * 2, 1))
        val data = ByteArray(1024)
        d.onFileData(chunk(1, 8, data))
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(1, 8, data)), "piece 1 incomplete: no ack")
        repeat(8) { d.onFileData(chunk(1, it, data)) }
        val again = d.onFileData(chunk(1, 7, data)) as PhotoDownload.Event.Reply
        assertArrayEquals(Payloads.filePieceAck(false, 1, 0), again.commands.single().second)
        assertEquals(1024f * 9 / (16 * 1024), d.progress!!, 1e-6f, "duplicates are not counted")
    }

    @Test
    fun `short or empty chunks are rejected so the photo can never have a hole`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(2048 + 10, 1))
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(1, 0, ByteArray(0))))
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(1, 0, ByteArray(1000))))
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(1, 2, ByteArray(9))), "last chunk must be exact too")
        d.onFileData(chunk(1, 0, ByteArray(1024)))
        d.onFileData(chunk(1, 1, ByteArray(1024)))
        assertInstanceOf(PhotoDownload.Event.Completed::class.java, d.onFileData(chunk(1, 2, ByteArray(10))))
    }

    @Test
    fun `a full last piece with a short last chunk completes`() {
        val bytes = Random(1).nextBytes(7 * 1024 + 1) // exactly 8 chunks = one piece
        val d = PhotoDownload()
        d.onFileSize(fileSize(bytes.size, 2))
        val events = chunksOf(bytes).mapIndexed { i, c -> d.onFileData(chunk(2, i, c)) }
        assertArrayEquals(bytes, (events.last() as PhotoDownload.Event.Completed).jpeg)
    }

    @Test
    fun `chunks with another file id are ignored`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(1024, 4))
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(3, 0, ByteArray(1024))))
        assertEquals(0f, d.progress)
    }

    @Test
    fun `a repeated 98 for the same file is re-acked without losing data`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(4096, 1))
        d.onFileData(chunk(1, 0, ByteArray(1024)))
        val e = d.onFileSize(fileSize(4096, 1)) as PhotoDownload.Event.Reply
        assertEquals(Command.ACK_FILE_SIZE, e.commands.single().first)
        assertEquals(0.25f, d.progress)
    }

    @Test
    fun `invalid chunks are rejected`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(2048, 1))
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(1, 5, ByteArray(1024))), "out of range")
        assertEquals(PhotoDownload.Event.None, d.onFileData(chunk(1, 0, ByteArray(1500))), "too long")
        assertEquals(0f, d.progress)
    }

    @Test
    fun `a new 98 restarts the transfer`() {
        val d = PhotoDownload()
        d.onFileSize(fileSize(4096, 1))
        d.onFileData(chunk(1, 0, ByteArray(1024)))
        d.onFileSize(fileSize(2048, 2))
        assertEquals(0f, d.progress)
    }
}
