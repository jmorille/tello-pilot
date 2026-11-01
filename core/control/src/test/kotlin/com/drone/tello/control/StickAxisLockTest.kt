package com.drone.tello.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StickAxisLockTest {
    private val linear = StickShaping(deadZone = 0f, expo = 0f)

    @Test
    fun nearlyStraightPushHasNoCrossAxis() {
        val mixer = StickMixer(StickMode.MODE_2, linear)
        // Right stick 5° off pure forward: no roll.
        val out = mixer.setRight(0.087f, 0.996f, fastMode = false)
        assertEquals(0f, out.roll, 0f)
        assertEquals(0.996f, out.pitch, 1e-4f)
    }

    @Test
    fun nearlySidewaysPushHasNoPitch() {
        val mixer = StickMixer(StickMode.MODE_2, linear)
        val out = mixer.setRight(-0.9f, 0.05f, fastMode = false)
        assertEquals(0f, out.pitch, 0f)
        assertEquals(-0.9f, out.roll, 1e-4f)
    }

    @Test
    fun diagonalKeepsBothAxes() {
        val mixer = StickMixer(StickMode.MODE_2, linear)
        val out = mixer.setRight(1f, 1f, fastMode = false)
        assertEquals(1f, out.roll, 1e-4f)
        assertEquals(1f, out.pitch, 1e-4f)
    }

    @Test
    fun lockCanBeDisabled() {
        val mixer = StickMixer(StickMode.MODE_2, linear).apply { axisLock = false }
        val out = mixer.setRight(0.087f, 0.996f, fastMode = false)
        assertEquals(0.087f, out.roll, 1e-4f)
    }
}
