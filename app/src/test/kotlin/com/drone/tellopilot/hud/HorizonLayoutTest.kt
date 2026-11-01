package com.drone.tellopilot.hud

import com.drone.tello.control.HorizonAnchor
import com.drone.tello.control.HorizonPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonLayoutTest {

    /**
     * Pixel 7 Pro in landscape, in dp (891 x 411), "whole picture" framing: 4:3 video of
     * 548 dp in the middle, black bands of 171.5 dp; 200 dp sticks, land button, top bar.
     */
    private fun pixel(
        width: Float = 891f,
        height: Float = 411f,
        videoWidth: Float = height * 4f / 3f,
        stick: Float = 200f,
    ): HorizonGeometry {
        val margin = 8f
        val pad = 12f
        return HorizonGeometry(
            screenWidth = width,
            screenHeight = height,
            bounds = HudRect(margin, margin, width - margin, height - margin),
            video = HudRect((width - videoWidth) / 2f, 0f, (width + videoWidth) / 2f, height),
            width = 96f,
            height = 118f,
            obstacles = listOf(
                HudRect(pad, pad, width - pad, 56f), // top bar
                HudRect(pad, height - pad - stick, pad + stick, height - pad), // left stick
                HudRect(width - pad - stick, height - pad - stick, width - pad, height - pad), // right stick
                HudRect(width / 2f - 90f, height - pad - 56f, width / 2f + 90f, height - pad), // land button
            ),
            gap = 8f,
            centerBottomGap = 96f,
        )
    }

    private fun assertPlaced(p: HudPoint, g: HorizonGeometry) {
        assertTrue("$p must be valid", HorizonLayout.isValid(p, g))
        val r = HudRect.centered(p.x, p.y, g.width, g.height)
        assertTrue("$r inside ${g.bounds}", r.left >= g.bounds.left - 0.01f && r.right <= g.bounds.right + 0.01f)
        assertTrue("$r inside ${g.bounds}", r.top >= g.bounds.top - 0.01f && r.bottom <= g.bounds.bottom + 0.01f)
        g.obstacles.forEach { assertFalse("$r covers $it", r.overlaps(it)) }
    }

    @Test
    fun `snap places are the bottom centre and the middle of each black band`() {
        val g = pixel()
        val t = HorizonLayout.snapTargets(g)
        // Historical place: 96 dp above the bottom, centred.
        assertEquals(HudPoint(445.5f, 411f - 96f - 59f), t.getValue(HorizonAnchor.CENTER))
        // Middle of the band (8..171.5), between the top bar (64 with the gap) and the stick (191).
        assertEquals(HudPoint(89.75f, 127.5f), t.getValue(HorizonAnchor.LEFT))
        assertEquals(HudPoint(801.25f, 127.5f), t.getValue(HorizonAnchor.RIGHT))
        t.values.forEach { assertPlaced(it, g) }
        assertTrue(t.getValue(HorizonAnchor.LEFT).x + g.width / 2f <= g.video.left)
        assertTrue(t.getValue(HorizonAnchor.RIGHT).x - g.width / 2f >= g.video.right)
    }

    @Test
    fun `the default placement is the bottom centre snap`() {
        val g = pixel()
        assertEquals(HorizonLayout.snapTargets(g).getValue(HorizonAnchor.CENTER), HorizonLayout.center(HorizonPlacement.Default, g))
    }

    @Test
    fun `a free place over a stick is pushed off it, staying on screen`() {
        val g = pixel()
        val placed = HorizonLayout.center(HorizonPlacement(null, 100f / 891f, 300f / 411f), g)
        assertPlaced(placed, g)
    }

    @Test
    fun `a free place over the land button is pushed off it`() {
        val g = pixel()
        val p = HorizonLayout.constrain(HudPoint(445.5f, 380f), g)
        assertPlaced(p, g)
        // Straight up is the shortest way out.
        assertEquals(445.5f, p.x, 0.01f)
    }

    @Test
    fun `a free place off screen comes back entirely inside`() {
        val g = pixel()
        listOf(HudPoint(-500f, -500f), HudPoint(5000f, 5000f), HudPoint(891f, 0f), HudPoint(Float.NaN, Float.POSITIVE_INFINITY)).forEach {
            assertPlaced(HorizonLayout.constrain(it, g), g)
        }
    }

    @Test
    fun `a free valid place is kept as is`() {
        val g = pixel()
        val p = HudPoint(300f, 150f)
        assertEquals(p, HorizonLayout.constrain(p, g))
    }

    @Test
    fun `dropping near a snap place snaps to it`() {
        val g = pixel()
        val left = HorizonLayout.snapTargets(g).getValue(HorizonAnchor.LEFT)
        assertEquals(HorizonPlacement.snapped(HorizonAnchor.LEFT), HorizonLayout.drop(HudPoint(left.x + 20f, left.y - 25f), g, 56f))
        assertEquals(HorizonAnchor.LEFT, HorizonLayout.nearestSnap(HudPoint(left.x + 20f, left.y - 25f), g, 56f))
        assertNull(HorizonLayout.nearestSnap(HudPoint(left.x + 80f, left.y), g, 56f))
    }

    @Test
    fun `dropping elsewhere stores screen fractions that resolve to the same spot`() {
        val g = pixel()
        val placed = HorizonLayout.drop(HudPoint(300f, 150f), g, 56f)
        assertNull(placed.anchor)
        assertEquals(300f / 891f, placed.x, 1e-5f)
        assertEquals(150f / 411f, placed.y, 1e-5f)
        val back = HorizonLayout.center(placed, g)
        assertEquals(300f, back.x, 0.01f)
        assertEquals(150f, back.y, 0.01f)
    }

    @Test
    fun `dropping on a stick stores a spot off the stick`() {
        val g = pixel()
        val placed = HorizonLayout.drop(HudPoint(780f, 330f), g, 56f)
        assertNull(placed.anchor)
        assertPlaced(HorizonLayout.center(placed, g), g)
    }

    @Test
    fun `snapped places follow another screen and another framing`() {
        // Narrower phone, 16:9 crop (narrow bands), larger sticks.
        listOf(
            pixel(width = 800f, height = 360f),
            pixel(videoWidth = 411f * 16f / 9f),
            pixel(stick = 160f),
            pixel(width = 1280f, height = 800f), // tablet
        ).forEach { g ->
            HorizonLayout.snapTargets(g).values.forEach { assertPlaced(it, g) }
        }
        val tablet = pixel(width = 1280f, height = 800f)
        assertTrue(HorizonLayout.snapTargets(tablet).getValue(HorizonAnchor.LEFT).x < tablet.video.left)
    }

    @Test
    fun `with the mini-map shown the left snap sits beside it, never under it`() {
        val base = pixel()
        val map = HudRect(12f, 64f, 122f, 196f)
        val g = base.copy(obstacles = base.obstacles + map)
        val left = HorizonLayout.snapTargets(g).getValue(HorizonAnchor.LEFT)
        assertPlaced(left, g)
        assertFalse(HudRect.centered(left.x, left.y, g.width, g.height).overlaps(map))
    }

    @Test
    fun `without black bands the side places go against the screen sides`() {
        // Cropped to the full width: the video overflows the screen, no band.
        val g = pixel(videoWidth = 891f)
        val t = HorizonLayout.snapTargets(g)
        assertEquals(g.bounds.left + g.width / 2f, t.getValue(HorizonAnchor.LEFT).x, 0.01f)
        assertEquals(g.bounds.right - g.width / 2f, t.getValue(HorizonAnchor.RIGHT).x, 0.01f)
        t.values.forEach { assertPlaced(it, g) }
    }

    @Test
    fun `free fractions follow a rotation or another screen and stay valid`() {
        val placed = HorizonPlacement(null, 0.1f, 0.3f)
        listOf(pixel(), pixel(width = 411f, height = 891f, videoWidth = 411f), pixel(width = 640f, height = 360f)).forEach { g ->
            assertPlaced(HorizonLayout.center(placed, g), g)
        }
    }

    @Test
    fun `when no spot is free it only stays inside the screen`() {
        val g = pixel().copy(obstacles = listOf(HudRect(0f, 0f, 891f, 411f)))
        val p = HorizonLayout.constrain(HudPoint(-100f, 900f), g)
        assertEquals(HorizonLayout.clamp(HudPoint(-100f, 900f), g), p)
        assertTrue(p.x - g.width / 2f >= g.bounds.left && p.y + g.height / 2f <= g.bounds.bottom)
    }

    @Test
    fun `an instrument larger than the bounds is centred`() {
        val g = pixel().copy(bounds = HudRect(0f, 0f, 50f, 50f), obstacles = emptyList())
        assertEquals(HudPoint(25f, 25f), HorizonLayout.clamp(HudPoint(400f, -3f), g))
    }

    @Test
    fun `the free column middle splits around the obstacles`() {
        val g = pixel()
        assertEquals(127.5f, HorizonLayout.freeColumnMiddle(89.75f, g), 0.01f)
        val empty = g.copy(obstacles = emptyList())
        assertEquals(205.5f, HorizonLayout.freeColumnMiddle(89.75f, empty), 0.01f)
    }

    @Test
    fun `rectangles that only touch do not overlap`() {
        val a = HudRect(0f, 0f, 10f, 10f)
        assertFalse(a.overlaps(HudRect(10f, 0f, 20f, 10f)))
        assertTrue(a.overlaps(HudRect(9f, 9f, 20f, 20f)))
    }

    @Test
    fun `video slot follows the snap or the third of the screen`() {
        assertEquals(HorizonAnchor.LEFT, HorizonLayout.videoSlot(HorizonPlacement.snapped(HorizonAnchor.LEFT)))
        assertEquals(HorizonAnchor.CENTER, HorizonLayout.videoSlot(HorizonPlacement.Default))
        assertEquals(HorizonAnchor.RIGHT, HorizonLayout.videoSlot(HorizonPlacement.snapped(HorizonAnchor.RIGHT)))
        assertEquals(HorizonAnchor.LEFT, HorizonLayout.videoSlot(HorizonPlacement(null, 0.1f, 0.2f)))
        assertEquals(HorizonAnchor.CENTER, HorizonLayout.videoSlot(HorizonPlacement(null, 0.5f, 0.1f)))
        assertEquals(HorizonAnchor.RIGHT, HorizonLayout.videoSlot(HorizonPlacement(null, 0.9f, 0.9f)))
        assertEquals(HorizonAnchor.CENTER, HorizonLayout.videoSlot(HorizonPlacement(null, Float.NaN, 0.5f)))
    }

    @Test
    fun `video horizon box stays inside the frame`() {
        assertEquals(21f, HorizonLayout.videoLeft(HorizonAnchor.LEFT, 960f, 245f, 21f), 0.01f)
        assertEquals(357.5f, HorizonLayout.videoLeft(HorizonAnchor.CENTER, 960f, 245f, 21f), 0.01f)
        assertEquals(960f - 245f - 21f, HorizonLayout.videoLeft(HorizonAnchor.RIGHT, 960f, 245f, 21f), 0.01f)
        // Margin too large for a small frame: clamped inside.
        assertEquals(0f, HorizonLayout.videoLeft(HorizonAnchor.RIGHT, 260f, 245f, 21f), 0.01f)
        assertEquals(15f, HorizonLayout.videoLeft(HorizonAnchor.LEFT, 260f, 245f, 21f), 0.01f)
    }
}
