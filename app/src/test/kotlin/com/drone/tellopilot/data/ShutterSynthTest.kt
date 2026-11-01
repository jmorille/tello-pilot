package com.drone.tellopilot.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShutterSynthTest {

    private val rate = ShutterSynth.SAMPLE_RATE
    private val pcm = ShutterSynth.synthesize(rate)

    /** Mean absolute level between two instants (ms). */
    private fun level(fromMs: Int, toMs: Int): Double {
        val a = rate * fromMs / 1000
        val b = rate * toMs / 1000
        return (a until b).sumOf { abs(pcm[it].toInt()).toDouble() } / (b - a)
    }

    @Test
    fun `short, between 150 and 250 ms`() {
        val ms = pcm.size * 1000 / rate
        assertTrue("$ms ms", ms in 150..250)
    }

    @Test
    fun `normalised without clipping`() {
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertEquals(ShutterSynth.PEAK * Short.MAX_VALUE, peak.toDouble(), 2.0)
    }

    @Test
    fun `three distinct clicks separated by quieter gaps`() {
        val open = level(0, 6)
        val close = level(48, 54)
        val recock = level(150, 156)
        val gap1 = level(32, 46)
        val gap2 = level(90, 110)
        assertTrue("open $open > gap $gap1", open > 3 * gap1)
        assertTrue("close $close > gap $gap1", close > 3 * gap1)
        assertTrue("recock $recock > gap $gap2", recock > 3 * gap2)
        assertTrue("the re-cock click is softer", recock < open)
    }

    @Test
    fun `ends in silence`() {
        assertTrue(abs(pcm.last().toInt()) < 50)
    }

    @Test
    fun `deterministic`() {
        assertArrayEquals(pcm, ShutterSynth.synthesize(rate))
    }
}
