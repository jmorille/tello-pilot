package com.drone.tello.control

import com.drone.tello.protocol.FlipDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GamepadMapperTest {

    @Test
    fun `R2 plus Y fires takeoff-or-land once, in either order`() {
        val m = GamepadMapper()
        assertNull(m.onButtonDown(PadButton.Y), "Y alone does nothing")
        assertEquals(PadAction.TakeoffOrLand, m.onButtonDown(PadButton.R2))
        assertNull(m.onButtonDown(PadButton.R2), "auto-repeat does not fire again")
        m.onButtonUp(PadButton.R2); m.onButtonUp(PadButton.Y)
        assertNull(m.onButtonDown(PadButton.R2))
        assertEquals(PadAction.TakeoffOrLand, m.onButtonDown(PadButton.Y))
    }

    @Test
    fun `flips need L2 held`() {
        val m = GamepadMapper()
        assertNull(m.onButtonDown(PadButton.DPAD_LEFT))
        m.onButtonUp(PadButton.DPAD_LEFT)
        m.onButtonDown(PadButton.L2)
        assertEquals(PadAction.Flip(FlipDirection.LEFT), m.onButtonDown(PadButton.DPAD_LEFT))
        assertEquals(PadAction.Flip(FlipDirection.FORWARD), m.onButtonDown(PadButton.DPAD_UP))
    }

    @Test
    fun `reset forgets held buttons`() {
        val m = GamepadMapper()
        m.onButtonDown(PadButton.Y)
        m.reset()
        assertNull(m.onButtonDown(PadButton.R2), "Y no longer held after a disconnection")
    }

    @Test
    fun `single buttons`() {
        val m = GamepadMapper()
        assertEquals(PadAction.Photo, m.onButtonDown(PadButton.R1))
        assertEquals(PadAction.ToggleRecording, m.onButtonDown(PadButton.L1))
        assertEquals(PadAction.ToggleFastMode, m.onButtonDown(PadButton.X))
        assertEquals(PadAction.StopSmartVideo, m.onButtonDown(PadButton.B))
        assertNull(m.onButtonDown(PadButton.A))
    }

    @Test
    fun `axes invert y, remove the flat zone and still reach full scale`() {
        val s = GamepadMapper.sticks(lx = 1f, ly = -1f, rx = 0.05f, ry = 0.55f, flat = 0.1f)
        assertEquals(1f, s.leftX, 1e-6f)
        assertEquals(1f, s.leftY, 1e-6f, "stick pushed up (-1 on Android) = up-positive")
        assertEquals(0f, s.rightX, "inside the flat zone")
        assertEquals(-0.5f, s.rightY, 1e-6f)
        assertEquals(0f, GamepadMapper.axis(Float.NaN, 0.1f))
        assertEquals(-1f, GamepadMapper.axis(-3f, 0.1f), "clamped")
    }
}
