package com.drone.tellopilot.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class HudMathTest {

    @Test
    fun `heading is normalised to 0 until 360`() {
        assertEquals(0f, HudMath.heading(0f))
        assertEquals(270f, HudMath.heading(-90f))
        assertEquals(180f, HudMath.heading(-180f))
        assertEquals(10f, HudMath.heading(370f), 1e-4f)
        assertEquals(0f, HudMath.heading(-1e-6f)) // never 360
    }

    @Test
    fun `written heading and cardinal point`() {
        assertEquals(0, HudMath.headingDegrees(359.6f))
        assertEquals("N", HudMath.cardinal(359.6f))
        assertEquals("N", HudMath.cardinal(22.4f))
        assertEquals("NE", HudMath.cardinal(22.5f))
        assertEquals("E", HudMath.cardinal(90f))
        assertEquals("SO", HudMath.cardinal(-135f))
        assertEquals("O", HudMath.cardinal(-90f))
        assertEquals("NO", HudMath.cardinal(315f))
        assertEquals(270, HudMath.headingDegrees(-90f))
    }

    @Test
    fun `angles interpolate the short way round, through north`() {
        assertEquals(0f, HudMath.lerpAngle(359f, 1f, 0.5f), 1e-4f)
        assertEquals(359.5f, HudMath.lerpAngle(359f, 1f, 0.25f), 1e-4f)
        assertEquals(0.5f, HudMath.lerpAngle(359f, 1f, 0.75f), 1e-4f)
        assertEquals(359.5f, HudMath.lerpAngle(1f, 358f, 0.5f), 1e-4f)
        assertEquals(45f, HudMath.lerpAngle(0f, 90f, 0.5f), 1e-4f)
        assertEquals(90f, HudMath.lerpAngle(10f, 170f, 0.5f), 1e-4f)
        assertEquals(1f, HudMath.lerpAngle(359f, 1f, 1f), 1e-4f)
    }

    @Test
    fun `horizon shift follows the pitch, clamped`() {
        assertEquals(0f, HudMath.horizonShift(0f, 50f))
        assertEquals(15f, HudMath.horizonShift(20f, 50f), 1e-4f) // 20/40 * 50 * 0.6
        assertEquals(30f, HudMath.horizonShift(90f, 50f), 1e-4f)
        assertEquals(-30f, HudMath.horizonShift(-60f, 50f), 1e-4f)
        assertEquals(-12f, HudMath.horizonRotation(12f))
    }

    @Test
    fun `quantization limits the redraws`() {
        assertEquals(1.5f, HudMath.quantize(1.3f, 0.5f))
        assertEquals(1f, HudMath.quantize(1.2f, 0.5f))
        assertEquals(-2.5f, HudMath.quantize(-2.6f, 0.5f))
    }

    @Test
    fun `panel thresholds`() {
        assertTrue(HudMath.batteryLow(20))
        assertFalse(HudMath.batteryLow(21))
        assertTrue(HudMath.droneHot(60f))
        assertFalse(HudMath.droneHot(59.9f))
        assertFalse(HudMath.isPlausibleImuTemp(120f))
        assertFalse(HudMath.isPlausibleImuTemp(Float.NaN))
        assertTrue(HudMath.isPlausibleImuTemp(45f))
        assertEquals("↑", HudMath.varioArrow(0.1f))
        assertEquals("↓", HudMath.varioArrow(-0.5f))
        assertEquals("↕", HudMath.varioArrow(0.05f))
    }

    @Test
    fun `map span keeps the whole track and at least 2 m`() {
        val xs = floatArrayOf(0f, 1f, 3f)
        val ys = floatArrayOf(0f, -0.5f, 1f)
        assertEquals(3f, TrackMapMath.span(3, 0f, 0f, { xs[it] }, { ys[it] }))
        assertEquals(2f, TrackMapMath.span(2, 0f, 0f, { xs[it] }, { ys[it] }))
        assertEquals(10f, TrackMapMath.scale(30f, 3f))
    }

    @Test
    fun `map projection puts the takeoff point at the centre, forward up`() {
        assertEquals(50f, TrackMapMath.screenX(2f, 2f, 50f, 10f))
        assertEquals(60f, TrackMapMath.screenX(3f, 2f, 50f, 10f))
        assertEquals(40f, TrackMapMath.screenY(1f, 0f, 50f, 10f))
        assertEquals(5f, TrackMapMath.distance(0f, 0f, 3f, 4f))
        assertTrue(TrackMapMath.showsArrow(0.31f))
        assertFalse(TrackMapMath.showsArrow(0.3f))
        // Drone right of home: the arrow points left.
        assertEquals(PI.toFloat(), TrackMapMath.arrowAngle(60f, 50f, 50f, 50f), 1e-5f)
        assertTrue(TrackMapMath.isNewPoint(0f, 0f, 0.1f, 0f))
        assertFalse(TrackMapMath.isNewPoint(0f, 0f, 0.05f, 0.05f))
    }
}
