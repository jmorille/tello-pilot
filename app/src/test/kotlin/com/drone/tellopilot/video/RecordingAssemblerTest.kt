package com.drone.tellopilot.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAssemblerTest {

    private fun nal(header: Int, vararg body: Int) = byteArrayOf(0, 0, 0, 1, header.toByte()) + body.map { it.toByte() }.toByteArray()

    private val sps = nal(0x67, 0x42, 0x00, 0x1F)
    private val pps = nal(0x68, 0xCE, 0x3C)
    private val sps2 = nal(0x67, 0x42, 0x00, 0x28)
    private val pps2 = nal(0x68, 0xCE, 0x3D)
    private val idr = nal(0x65, 0x88, 0x80)
    private val slice = nal(0x41, 0x9A, 0x02)

    private var clockNs = 1_000_000_000L
    private fun RecordingAssembler.feed(frame: ByteArray, gap: Boolean = false, stepMs: Long = 33) =
        onFrame(frame, clockNs, gap).also { clockNs += stepMs * 1_000_000 }

    @Test
    fun `nothing is written before SPS, PPS and a key frame`() {
        val a = RecordingAssembler()
        assertNull(a.feed(slice))
        assertNull(a.feed(idr)) // key frame but no parameter sets yet
        assertNull(a.feed(sps)) // parameter sets alone are not a sample
        assertNull(a.feed(slice)) // picture after SPS only: still no PPS
        assertNull(a.feed(pps))
        assertNull(a.feed(slice)) // not a key frame (the SPS was two frames ago)
        assertFalse(a.started)
        val first = a.feed(idr)!!
        assertTrue(a.started)
        assertTrue(first.key)
        assertEquals(0L, first.ptsUs)
        assertArrayEquals(sps, first.start!!.first) // csd keeps the 4-byte start code
        assertArrayEquals(pps, first.start!!.second)
        assertArrayEquals(idr, first.sample)
    }

    @Test
    fun `the picture right after the parameter sets is the key frame (Tello layout)`() {
        val a = RecordingAssembler()
        a.feed(sps)
        a.feed(pps)
        val first = a.feed(slice)!!
        assertTrue(first.key)
        assertNotNull(first.start)
        val next = a.feed(slice)!!
        assertFalse(next.key)
        assertNull(next.start) // the track is created once
    }

    @Test
    fun `parameter sets and picture in one buffer start the file`() {
        val out = RecordingAssembler().feed(sps + pps + idr)!!
        assertTrue(out.key)
        assertArrayEquals(sps, out.start!!.first)
        assertArrayEquals(pps, out.start!!.second)
    }

    @Test
    fun `timestamps are arrival times relative to the first sample`() {
        val a = RecordingAssembler()
        a.feed(sps, stepMs = 5)
        a.feed(pps, stepMs = 5)
        assertEquals(0L, a.feed(slice, stepMs = 33)!!.ptsUs)
        assertEquals(33_000L, a.feed(slice, stepMs = 40)!!.ptsUs)
        assertEquals(73_000L, a.feed(slice)!!.ptsUs)
    }

    @Test
    fun `after a gap P-frames are skipped until the next key frame`() {
        val a = RecordingAssembler()
        a.feed(sps + pps + idr)
        assertNotNull(a.feed(slice))
        assertNull(a.feed(slice, gap = true))
        assertNull(a.feed(slice))
        val resumed = a.feed(idr)!!
        assertTrue(resumed.key)
        assertFalse(a.feed(slice)!!.key) // back to normal
    }

    @Test
    fun `a gap between the parameter sets and the picture cancels the key frame`() {
        val a = RecordingAssembler()
        a.feed(sps + pps + idr)
        a.feed(sps)
        a.feed(pps)
        // The picture that followed the parameter sets was lost: this one is a P-frame.
        assertNull(a.feed(slice, gap = true))
        a.feed(sps)
        a.feed(pps)
        assertTrue(a.feed(slice)!!.key)
    }

    @Test
    fun `repeated identical parameter sets are not written in-band`() {
        val a = RecordingAssembler()
        a.feed(sps + pps + idr)
        a.feed(sps)
        a.feed(pps)
        val key = a.feed(slice)!!
        assertTrue(key.key)
        assertFalse(key.configInBand)
        assertArrayEquals(slice, key.sample)
    }

    @Test
    fun `changed parameter sets go in-band before the next key frame, once`() {
        val a = RecordingAssembler()
        a.feed(sps + pps + idr)
        a.feed(sps2)
        a.feed(pps2)
        val key = a.feed(slice)!!
        assertTrue(key.key)
        assertTrue(key.configInBand)
        assertNull(key.start) // the track keeps its first csd
        assertArrayEquals(sps2 + pps2 + slice, key.sample)
        a.feed(sps2)
        a.feed(pps2)
        assertFalse(a.feed(slice)!!.configInBand)
    }

    @Test
    fun `changed parameter sets survive a gap until the key frame`() {
        val a = RecordingAssembler()
        a.feed(sps + pps + idr)
        a.feed(sps2)
        a.feed(pps2)
        assertNull(a.feed(slice, gap = true))
        val key = a.feed(idr)!!
        assertTrue(key.configInBand)
        assertArrayEquals(sps2 + pps2 + idr, key.sample)
    }

    @Test
    fun `garbage and truncated frames are ignored without error`() {
        val a = RecordingAssembler()
        listOf(ByteArray(0), byteArrayOf(0), byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 0, 1), byteArrayOf(1, 2, 3, 4, 5))
            .forEach { assertNull(a.feed(it)) }
        assertFalse(a.started)
        assertNotNull(a.feed(sps + pps + idr))
    }
}
