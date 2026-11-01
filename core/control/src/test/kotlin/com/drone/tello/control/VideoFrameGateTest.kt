package com.drone.tello.control

import com.drone.tello.protocol.VideoFrameAssembler.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoFrameGateTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 9)

    @Test
    fun `frames before the first key frame are dropped and a key frame is requested`() {
        val gate = VideoFrameGate()
        val d = gate.onResult(Result.Frame(pFrame))
        assertNull(d.frame)
        assertTrue(d.requestKeyFrame)
    }

    @Test
    fun `key frame unlocks the stream`() {
        val gate = VideoFrameGate()
        assertNotNull(gate.onResult(Result.Frame(sps)).frame)
        val d = gate.onResult(Result.Frame(pFrame))
        assertNotNull(d.frame)
        assertFalse(d.requestKeyFrame)
    }

    @Test
    fun `loss drops P-frames until the next key frame`() {
        val gate = VideoFrameGate()
        gate.onResult(Result.Frame(sps))
        assertTrue(gate.onResult(Result.Lost).requestKeyFrame)
        assertNull(gate.onResult(Result.Frame(pFrame)).frame)
        assertNotNull(gate.onResult(Result.Frame(sps)).frame)
    }

    @Test
    fun `decoder backlog triggers a resync`() {
        val gate = VideoFrameGate()
        gate.onResult(Result.Frame(sps))
        assertTrue(gate.onDecoderBacklog().requestKeyFrame)
        assertTrue(gate.waitingForKeyFrame)
    }

    @Test
    fun `a P-frame completed right after a loss is dropped`() {
        val gate = VideoFrameGate()
        gate.onResult(Result.Frame(sps))
        val d = gate.onResult(Result.Frame(pFrame, afterLoss = true))
        assertNull(d.frame)
        assertTrue(d.requestKeyFrame)
        assertNotNull(gate.onResult(Result.Frame(sps, afterLoss = true)).frame, "key frame after loss is fine")
    }

    @Test
    fun `pending fragments do nothing`() {
        assertEquals(VideoFrameGate.Decision(null, false), VideoFrameGate().onResult(Result.Pending))
    }
}
