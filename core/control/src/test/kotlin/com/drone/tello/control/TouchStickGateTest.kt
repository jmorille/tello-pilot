package com.drone.tello.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TouchStickGateTest {
    private fun gate(x: Float, y: Float) = TouchStickGate.apply(x, y)

    @Test
    fun nearlyVerticalPushLocksTheCrossAxisInEveryQuadrant() {
        for (sx in listOf(1f, -1f)) for (sy in listOf(1f, -1f)) {
            val (x, y) = gate(sx * 0.087f, sy * 0.996f) // 5° off vertical
            assertEquals(0f, x, 0f)
            assertEquals(sy * 0.996f, y, 1e-6f)
        }
    }

    @Test
    fun nearlyHorizontalPushLocksTheCrossAxis() {
        val (x, y) = gate(-0.9f, 0.05f)
        assertEquals(-0.9f, x, 1e-6f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun angleIsTakenFromTheRawFingerBeforeClamping() {
        // Finger far above the stick, 5° off vertical: clamped it would be ~15°, still locked.
        val (x, y) = gate(0.26f, 3f)
        assertEquals(0f, x, 0f)
        assertEquals(1f, y, 0f)
    }

    @Test
    fun justOutsideTheLockBothAxesStay() {
        // 12° off vertical.
        val (x, y) = gate(0.208f, 0.978f)
        assertEquals(0.208f, x, 1e-6f)
        assertEquals(0.978f, y, 1e-6f)
    }

    @Test
    fun squareGateGivesFullDeflectionOnBothAxesInTheCorner() {
        val (x, y) = gate(1.5f, -1.5f)
        assertEquals(1f, x, 0f)
        assertEquals(-1f, y, 0f)
    }

    @Test
    fun centreAndNonFiniteInputsGiveZero() {
        assertEquals(0f to 0f, gate(0f, 0f))
        assertEquals(0f to 0f, gate(Float.NaN, 0.5f))
        assertEquals(0f to 0f, gate(0.5f, Float.POSITIVE_INFINITY))
    }
}
