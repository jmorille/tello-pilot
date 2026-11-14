package com.drone.tello.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeofenceTest {

    private fun fence(radius: Float = 5f, ceiling: Float = 10f) = Geofence(GeofenceConfig(radius, ceiling))

    @Test
    fun `distance is relative to the first position, whatever the axes`() {
        val f = fence()
        f.onOdometry(12f, -7f, valid = true, nowMs = 0)
        assertEquals(0f, f.distance!!, 1e-4f)
        f.onOdometry(12f + 3f, -7f - 4f, valid = true, nowMs = 1_000)
        assertEquals(5f, f.distance!!, 1e-4f)
    }

    @Test
    fun `inside, near at 80 percent, outside at the radius`() {
        val f = fence()
        f.onOdometry(0f, 0f, true, 0)
        assertEquals(FenceStatus.INSIDE, f.status(1f, 0))
        f.onOdometry(3.9f, 0f, true, 1_000)
        assertEquals(FenceStatus.INSIDE, f.status(1f, 1_000))
        f.onOdometry(4.1f, 0f, true, 1_100)
        assertEquals(FenceStatus.NEAR, f.status(1f, 1_100))
        f.onOdometry(5f, 0.5f, true, 2_000)
        assertEquals(FenceStatus.OUTSIDE, f.status(1f, 2_000))
    }

    @Test
    fun `exactly at the radius is outside, exactly at 80 percent is near`() {
        val f = fence()
        f.onOdometry(0f, 0f, true, 0)
        f.onOdometry(0.75f, 1f, true, 300)
        f.onOdometry(1.5f, 2f, true, 600)
        f.onOdometry(2.25f, 3f, true, 900)
        f.onOdometry(3f, 4f, true, 1_200) // hypot = 5 exactly
        assertEquals(5f, f.distance!!)
        assertEquals(FenceStatus.OUTSIDE, f.status(1f, 1_200))
        val g = fence()
        g.onOdometry(0f, 0f, true, 0)
        g.onOdometry(2f, 0f, true, 300)
        g.onOdometry(4f, 0f, true, 600) // 4 m = 0.8 x 5 m
        assertEquals(FenceStatus.NEAR, g.status(1f, 600))
    }

    @Test
    fun `the ceiling is exceeded only above it`() {
        val f = fence(ceiling = 3f)
        assertEquals(FenceStatus.INSIDE, f.status(3f, 0))
        assertEquals(FenceStatus.TOO_HIGH, f.status(3.01f, 100))
    }

    @Test
    fun `a gap of exactly the timeout still trusts the position, one more millisecond does not`() {
        val f = fence(radius = 30f)
        f.onOdometry(0f, 0f, true, 0)
        f.onOdometry(1f, 0f, true, 1_500)
        assertTrue(f.positionKnown)
        assertEquals(FenceStatus.INSIDE, f.status(1f, 3_000)) // 1500 ms without a fix: still known
        assertEquals(FenceStatus.NO_POSITION, f.status(1f, 3_001))
        val g = fence(radius = 30f)
        g.onOdometry(0f, 0f, true, 0)
        g.onOdometry(1f, 0f, true, 1_501)
        assertFalse(g.positionKnown)
    }

    @Test
    fun `the ceiling is checked even without a position`() {
        val f = fence(ceiling = 3f)
        assertEquals(FenceStatus.INSIDE, f.status(2.9f, 0))
        assertEquals(FenceStatus.TOO_HIGH, f.status(3.1f, 100))
        assertEquals(FenceStatus.INSIDE, f.status(null, 200)) // unknown height: not checked
    }

    @Test
    fun `invalid positions are ignored, then the position is unknown after the timeout`() {
        val f = fence()
        f.start(0)
        f.onOdometry(1f, 1f, valid = false, nowMs = 100)
        assertNull(f.distance)
        assertEquals(FenceStatus.INSIDE, f.status(1f, 1_000)) // still within the grace period
        assertEquals(FenceStatus.NO_POSITION, f.status(1f, 1_600))
        f.onOdometry(1f, 1f, valid = true, nowMs = 1_700)
        assertEquals(FenceStatus.INSIDE, f.status(1f, 1_700))
    }

    @Test
    fun `a stale position becomes unknown`() {
        val f = fence()
        f.onOdometry(0f, 0f, true, 0)
        f.onOdometry(1f, 0f, true, 500)
        assertEquals(FenceStatus.INSIDE, f.status(1f, 1_500)) // short gap: last distance kept
        assertEquals(FenceStatus.NO_POSITION, f.status(1f, 2_100))
    }

    @Test
    fun `an impossible jump makes the position untrustworthy for good`() {
        val f = fence()
        f.onOdometry(0f, 0f, true, 0)
        f.onOdometry(0.1f, 0f, true, 100)
        f.onOdometry(-20f, 0f, true, 200) // odometry reset or corrupt record
        assertFalse(f.positionKnown)
        assertEquals(FenceStatus.NO_POSITION, f.status(1f, 200))
        f.onOdometry(-20f, 0.1f, true, 300)
        assertEquals(FenceStatus.NO_POSITION, f.status(1f, 300))
    }

    @Test
    fun `a believable fast move is not a jump`() {
        val f = fence(radius = 30f)
        f.onOdometry(0f, 0f, true, 0)
        f.onOdometry(1.4f, 0f, true, 200) // 7 m/s, fast mode
        assertTrue(f.positionKnown)
        f.onOdometry(1.4f, 4f, true, 1_200) // after a 1 s gap
        assertTrue(f.positionKnown)
    }

    @Test
    fun `a position coming back after a long gap is not trusted`() {
        val f = fence(radius = 30f)
        f.onOdometry(4f, 0f, true, 0)
        f.onOdometry(0f, 0f, true, 3_000) // odometry restarted from a new origin?
        assertFalse(f.positionKnown)
        assertEquals(FenceStatus.NO_POSITION, f.status(1f, 3_000))
    }

    @Test
    fun `non finite positions are ignored`() {
        val f = fence()
        f.onOdometry(Float.NaN, 0f, true, 0)
        assertNull(f.distance)
    }
}
