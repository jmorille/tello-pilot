package com.drone.tellopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BatteryTonesTest {

    private fun ms(pcm: ShortArray) = pcm.size * 1000L / BatteryTones.SAMPLE_RATE

    @Test
    fun `tone lengths match the delays used before speaking`() {
        assertEquals(BatteryTones.LOW_MS, ms(BatteryTones.low()))
        assertEquals(BatteryTones.CRITICAL_MS, ms(BatteryTones.critical()))
    }

    @Test
    fun `tones are loud, never clip and end silent`() {
        listOf(BatteryTones.low(), BatteryTones.critical()).forEach { pcm ->
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("peak $peak", peak in 20_000..32_767)
            assertTrue(abs(pcm.last().toInt()) < 200)
        }
    }

    @Test
    fun `critical is made of five separate beeps`() {
        val pcm = BatteryTones.critical()
        val perMs = BatteryTones.SAMPLE_RATE / 1000
        // Inside each beep (30 ms after its start) there is sound, in each gap (95–115 ms) silence.
        (0 until 5).forEach { k ->
            val inBeep = (0 until perMs * 10).maxOf { abs(pcm[(k * 120 + 30) * perMs + it].toInt()) }
            assertTrue("beep $k", inBeep > 10_000)
        }
        (0 until 4).forEach { k ->
            val inGap = ((k * 120 + 80) * perMs until (k * 120 + 115) * perMs).maxOf { abs(pcm[it].toInt()) }
            assertEquals("gap $k", 0, inGap)
        }
    }
}
