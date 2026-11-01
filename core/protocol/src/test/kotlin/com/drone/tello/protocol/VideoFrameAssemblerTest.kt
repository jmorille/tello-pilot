package com.drone.tello.protocol

import com.drone.tello.protocol.VideoFrameAssembler.Result
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoFrameAssemblerTest {

    private fun datagram(seq: Int, fragment: Int, last: Boolean, vararg data: Int) =
        byteArrayOf(seq.toByte(), (fragment or if (last) 0x80 else 0).toByte()) +
            data.map { it.toByte() }.toByteArray()

    /** A complete single-datagram frame. */
    private fun single(seq: Int) = datagram(seq, 0, true, 0, 0, 0, 1, 0x41, seq and 0x7F)

    private fun Result.frame() = assertInstanceOf(Result.Frame::class.java, this)

    @Test
    fun `fragments are joined into a frame`() {
        val a = VideoFrameAssembler()
        assertEquals(Result.Pending, a.push(datagram(3, 0, false, 0, 0, 0, 1, 0x65)))
        val frame = a.push(datagram(3, 1, true, 0xAA, 0xBB)).frame()
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0xAA.toByte(), 0xBB.toByte()), frame.data)
        assertFalse(frame.afterLoss)
    }

    @Test
    fun `missing fragment drops the frame and flags the next one`() {
        val a = VideoFrameAssembler()
        a.push(datagram(3, 0, false, 0, 0, 0, 1, 0x41))
        assertEquals(Result.Lost, a.push(datagram(3, 2, true, 1)))
        assertTrue(a.push(single(4)).frame().afterLoss)
        assertFalse(a.push(single(5)).frame().afterLoss)
    }

    @Test
    fun `a whole lost frame is detected through the sequence gap`() {
        val a = VideoFrameAssembler()
        assertFalse(a.push(single(10)).frame().afterLoss)
        // frame 11 never arrives
        assertTrue(a.push(single(12)).frame().afterLoss)
    }

    @Test
    fun `sequence wraps from 255 to 0 without a false loss`() {
        val a = VideoFrameAssembler()
        a.push(single(254))
        assertFalse(a.push(single(255)).frame().afterLoss)
        assertFalse(a.push(single(0)).frame().afterLoss)
    }

    @Test
    fun `a new frame starting before the previous one completed is a loss`() {
        val a = VideoFrameAssembler()
        a.push(single(1))
        a.push(datagram(2, 0, false, 0, 0, 0, 1, 0x41))
        assertEquals(Result.Lost, a.push(datagram(3, 0, false, 0, 0, 0, 1, 0x41)))
        assertTrue(a.push(datagram(3, 1, true, 7)).frame().afterLoss)
    }

    @Test
    fun `fragment zero without start code is rejected`() {
        val a = VideoFrameAssembler()
        assertEquals(Result.Lost, a.push(datagram(1, 0, true, 9, 9, 9, 9, 9)))
    }

    @Test
    fun `duplicate fragment is ignored`() {
        val a = VideoFrameAssembler()
        a.push(datagram(1, 0, false, 0, 0, 0, 1, 0x41))
        a.push(datagram(1, 1, false, 5))
        assertEquals(Result.Pending, a.push(datagram(1, 1, false, 5)))
        val f = a.push(datagram(1, 2, true, 6)).frame()
        assertEquals(7, f.data.size)
        assertFalse(f.afterLoss)
    }

    @Test
    fun `duplicate fragment zero is not a loss`() {
        val a = VideoFrameAssembler()
        a.push(single(1))
        a.push(datagram(2, 0, false, 0, 0, 0, 1, 0x41))
        assertEquals(Result.Pending, a.push(datagram(2, 0, false, 0, 0, 0, 1, 0x41)))
        assertFalse(a.push(datagram(2, 1, true, 5)).frame().afterLoss)
    }

    @Test
    fun `duplicate of a completed single-fragment frame is not emitted twice`() {
        val a = VideoFrameAssembler()
        a.push(single(1))
        assertEquals(Result.Pending, a.push(single(1)))
        assertFalse(a.push(single(2)).frame().afterLoss)
    }

    @Test
    fun `oversized frame is dropped`() {
        val a = VideoFrameAssembler(maxFrameSize = 8)
        a.push(datagram(1, 0, false, 0, 0, 0, 1, 0x41, 1))
        assertEquals(Result.Lost, a.push(datagram(1, 1, true, 1, 2, 3, 4)))
    }

    @Test
    fun `nal types are detected`() {
        val frame = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9)
        assertEquals(listOf(H264.NAL_SPS, H264.NAL_PPS, H264.NAL_IDR), H264.nalTypes(frame))
        assertTrue(H264.isKeyFrame(frame))
    }
}
