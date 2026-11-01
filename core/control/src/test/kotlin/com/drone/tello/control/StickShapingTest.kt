package com.drone.tello.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StickShapingTest {
    private val shaping = StickShaping(deadZone = 0.05f, expo = 0.3f)

    @Test
    fun `dead zone gives an exact zero`() {
        listOf(0f, 0.03f, -0.05f, Float.NaN).forEach { assertEquals(0f, shaping.apply(it)) }
    }

    @Test
    fun `full deflection keeps full range and is clamped`() {
        assertEquals(1f, shaping.apply(1f), 1e-6f)
        assertEquals(-1f, shaping.apply(-1f), 1e-6f)
        assertEquals(1f, shaping.apply(3f), 1e-6f)
    }

    @Test
    fun `curve is symmetric, monotonic and continuous out of the dead zone`() {
        var previous = 0f
        for (i in 6..100) {
            val x = i / 100f
            val y = shaping.apply(x)
            assertEquals(-y, shaping.apply(-x), 1e-6f)
            assertTrue(y > previous, "not monotonic at $x")
            assertTrue(y - previous < 0.05f, "jump at $x")
            previous = y
        }
    }

    @Test
    fun `expo softens the centre`() {
        assertTrue(shaping.apply(0.3f) < StickShaping(0.05f, 0f).apply(0.3f))
    }

    @Test
    fun `invalid settings are rejected`() {
        assertThrows<IllegalArgumentException> { StickShaping(deadZone = 0.9f) }
        assertThrows<IllegalArgumentException> { StickShaping(expo = 2f) }
    }
}
