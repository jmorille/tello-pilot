package com.drone.tellopilot.tracking

import com.drone.tello.protocol.StickInput
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingHeightLimitTest {

    private val down = StickInput(roll = 0.1f, pitch = 0.2f, throttle = -0.5f, yaw = 0.3f)
    private val up = down.copy(throttle = 0.5f)

    @Test
    fun `unknown height freezes the vertical axis only`() {
        assertEquals(down.copy(throttle = 0f), limitTrackingHeight(down, null))
        assertEquals(up.copy(throttle = 0f), limitTrackingHeight(up, null))
    }

    @Test
    fun `free movement between the floor and the ceiling`() {
        assertEquals(down, limitTrackingHeight(down, 2f))
        assertEquals(up, limitTrackingHeight(up, 2f))
    }

    @Test
    fun `no descent at or below the floor, climbing still allowed`() {
        assertEquals(0f, limitTrackingHeight(down, 1.0f).throttle, 0f)
        assertEquals(0f, limitTrackingHeight(down, 0.3f).throttle, 0f)
        assertEquals(up, limitTrackingHeight(up, 0.3f))
    }

    @Test
    fun `no climb at or above the ceiling, descent still allowed`() {
        assertEquals(0f, limitTrackingHeight(up, 5.0f).throttle, 0f)
        assertEquals(0f, limitTrackingHeight(up, 8f).throttle, 0f)
        assertEquals(down, limitTrackingHeight(down, 8f))
    }

    @Test
    fun `other axes are never touched`() {
        val limited = limitTrackingHeight(up, 9f)
        assertEquals(up.roll, limited.roll, 0f)
        assertEquals(up.pitch, limited.pitch, 0f)
        assertEquals(up.yaw, limited.yaw, 0f)
    }
}
