package com.drone.tello.control

import com.drone.tello.protocol.FlipDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `flips need L2 held, and fire on the D-pad release`() {
        val m = GamepadMapper()
        assertNull(m.onButtonDown(PadButton.DPAD_LEFT))
        assertNull(m.onButtonUp(PadButton.DPAD_LEFT))
        m.onButtonDown(PadButton.L2)
        assertNull(m.onButtonDown(PadButton.DPAD_LEFT), "nothing on the press")
        assertEquals(PadAction.Flip(FlipDirection.LEFT), m.onButtonUp(PadButton.DPAD_LEFT))
        assertNull(m.onButtonDown(PadButton.DPAD_UP))
        assertEquals(PadAction.Flip(FlipDirection.FORWARD), m.onButtonUp(PadButton.DPAD_UP))
    }

    @Test
    fun `one press, one flip - bounce, rolled press, early L2 release, reset`() {
        val m = GamepadMapper()
        m.onButtonDown(PadButton.L2)
        // Rolled press: up, then right while up is still down → nothing, even after the releases.
        assertNull(m.onButtonDown(PadButton.DPAD_UP))
        assertEquals(PadAction.FlipCancelled, m.onButtonDown(PadButton.DPAD_RIGHT), "the pilot is told")
        assertNull(m.onButtonUp(PadButton.DPAD_UP))
        assertNull(m.onButtonUp(PadButton.DPAD_RIGHT))
        // Auto-repeat of the D-pad press does not re-arm anything; one release, one flip.
        assertNull(m.onButtonDown(PadButton.DPAD_UP))
        assertNull(m.onButtonDown(PadButton.DPAD_UP))
        assertEquals(PadAction.Flip(FlipDirection.FORWARD), m.onButtonUp(PadButton.DPAD_UP))
        assertNull(m.onButtonUp(PadButton.DPAD_UP), "a duplicate release fires nothing")
        // L2 released before the D-pad: cancelled.
        m.onButtonDown(PadButton.DPAD_DOWN)
        m.onButtonUp(PadButton.L2)
        assertNull(m.onButtonUp(PadButton.DPAD_DOWN))
        // Disconnection in the middle: forgotten.
        m.onButtonDown(PadButton.L2)
        m.onButtonDown(PadButton.DPAD_LEFT)
        m.reset()
        m.onButtonDown(PadButton.L2)
        assertNull(m.onButtonUp(PadButton.DPAD_LEFT))
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

    @Test
    fun `custom bindings drive the mapper`() {
        val custom = (GamepadBindings.Default.assign(PadCommand.PHOTO, PadBinding(PadButton.A)) as BindingResult.Ok).bindings
        val m = GamepadMapper(custom)
        assertEquals(PadAction.Photo, m.onButtonDown(PadButton.A))
        assertNull(m.onButtonDown(PadButton.R1), "R1 is free now")
    }

    @Test
    fun `assigning a used button swaps the two commands`() {
        val r = GamepadBindings.Default.assign(PadCommand.PHOTO, PadBinding(PadButton.X))
        r as BindingResult.Ok
        assertEquals(PadCommand.FAST_MODE, r.swappedWith)
        assertEquals(PadBinding(PadButton.X), r.bindings[PadCommand.PHOTO])
        assertEquals(PadBinding(PadButton.R1), r.bindings[PadCommand.FAST_MODE])
    }

    @Test
    fun `unsafe bindings are refused`() {
        val d = GamepadBindings.Default
        assertEquals(BindingResult.Refused(BindingProblem.COMBO_REQUIRED), d.assign(PadCommand.TAKEOFF_LAND, PadBinding(PadButton.A)))
        assertEquals(BindingResult.Refused(BindingProblem.DPAD_RESERVED), d.assign(PadCommand.PHOTO, PadBinding(PadButton.DPAD_UP)))
        assertEquals(
            BindingResult.Refused(BindingProblem.FLIP_SINGLE_BUTTON),
            d.assign(PadCommand.FLIP, PadBinding(PadButton.A, PadButton.L1)),
        )
        assertEquals(
            BindingResult.Refused(BindingProblem.SAME_BUTTON_TWICE),
            d.assign(PadCommand.TAKEOFF_LAND, PadBinding(PadButton.A, PadButton.A)),
        )
        // Y is held for the R2+Y combo: as a single button it would fire on the first half of the combo.
        val r = d.assign(PadCommand.PHOTO, PadBinding(PadButton.Y))
        assertTrue(r is BindingResult.Refused && r.other == PadCommand.TAKEOFF_LAND)
    }

    @Test
    fun `bindings survive storage and corrupt text falls back to the defaults`() {
        val custom = (GamepadBindings.Default.assign(PadCommand.TAKEOFF_LAND, PadBinding(PadButton.START, PadButton.SELECT)) as BindingResult.Ok).bindings
        assertEquals(custom, GamepadBindings.decode(custom.encode()))
        assertEquals(GamepadBindings.Default, GamepadBindings.decode(null))
        assertEquals(GamepadBindings.Default, GamepadBindings.decode("garbage;;="))
        assertEquals(GamepadBindings.Default, GamepadBindings.decode("TAKEOFF_LAND=A"), "a single-button takeoff is never loaded")
        assertEquals(GamepadBindings.Default, GamepadBindings.decode("PHOTO=Y"), "conflicting layout rejected")
    }

    @Test
    fun `a custom combo fires in either order, and flips follow the flip button`() {
        val b = (GamepadBindings.Default.assign(PadCommand.FLIP, PadBinding(PadButton.L1)) as BindingResult.Ok).bindings
        val m = GamepadMapper(b)
        m.onButtonDown(PadButton.L1)
        assertNull(m.onButtonDown(PadButton.DPAD_RIGHT))
        assertEquals(PadAction.Flip(FlipDirection.RIGHT), m.onButtonUp(PadButton.DPAD_RIGHT))
        m.reset()
        assertNull(m.onButtonDown(PadButton.Y))
        assertEquals(PadAction.TakeoffOrLand, m.onButtonDown(PadButton.R2))
    }

    @Test
    fun `takeoff buttons are used by nothing else`() {
        val d = GamepadBindings.Default
        val photoCombo = d.assign(PadCommand.PHOTO, PadBinding(PadButton.A, PadButton.Y))
        assertTrue(photoCombo is BindingResult.Refused && photoCombo.other == PadCommand.TAKEOFF_LAND, "Y+A would half-press R2+Y")
        val flip = d.assign(PadCommand.FLIP, PadBinding(PadButton.R2))
        assertTrue(flip is BindingResult.Refused && flip.other == PadCommand.TAKEOFF_LAND)
    }

    @Test
    fun `centred sticks are exactly Neutral (no negative zero on the inverted axes)`() {
        assertEquals(PadSticks.Neutral, GamepadMapper.sticks(0f, 0f, 0f, 0f, flat = 0.1f))
        assertEquals(PadSticks.Neutral, GamepadMapper.sticks(0.05f, -0.05f, -0.02f, 0.08f, flat = 0.1f))
        assertEquals(PadSticks(0f, 1f, 0f, -1f), GamepadMapper.sticks(0f, -1f, 0f, 1f, flat = 0.1f))
    }
}
