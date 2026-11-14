package com.drone.tello.control

import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.SmartVideoMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GamepadShortcutsTest {

    private val d = GamepadBindings.Default
    private val rotate = PadFunction.SmartVideo(SmartVideoMode.ROTATE_360)

    private fun combo(modifier: PadButton, button: PadButton) = PadBinding(button, modifier)

    private fun GamepadBindings.with(vararg shortcuts: PadShortcut): GamepadBindings =
        shortcuts.fold(this) { b, s -> (b.addShortcut(s) as BindingResult.Ok).bindings }

    // ------------------------------------------------------------------ model and validation

    @Test
    fun `a shortcut that makes the drone move must be a combo`() {
        PadFunction.Catalog.filter { it.moves }.forEach { f ->
            assertEquals(
                BindingResult.Refused(BindingProblem.SHORTCUT_COMBO_REQUIRED),
                d.addShortcut(PadShortcut(PadBinding(PadButton.A), f)),
                "$f",
            )
        }
        assertEquals(
            BindingResult.Refused(BindingProblem.SHORTCUT_COMBO_REQUIRED),
            d.addShortcut(PadShortcut(PadBinding(PadButton.A), PadFunction.RunMission("Carré 2 m"))),
        )
        // Photo, video, speed modes and stop may be a single button.
        listOf(PadFunction.Photo, PadFunction.Record, PadFunction.Tripod, PadFunction.FastMode, PadFunction.Stop).forEach {
            assertTrue(d.addShortcut(PadShortcut(PadBinding(PadButton.A), it)) is BindingResult.Ok, "$it")
        }
    }

    @Test
    fun `the moving functions are the manoeuvres, automatic flights and palm landing`() {
        val moving = PadFunction.Catalog.filter { it.moves }.toSet()
        assertTrue(PadFunction.Bounce in moving && PadFunction.PalmLand in moving)
        assertTrue(moving.containsAll(SmartVideoMode.entries.map { PadFunction.SmartVideo(it) }))
        assertTrue(moving.containsAll(FlipDirection.entries.map { PadFunction.Flip(it) }))
        assertTrue(PadFunction.RunMission("x").moves && PadFunction.Tracking(TrackingMode.FOLLOW).moves)
        assertEquals(
            setOf(PadFunction.Tripod, PadFunction.FastMode, PadFunction.Photo, PadFunction.Record, PadFunction.Stop),
            PadFunction.Catalog.filterNot { it.moves }.toSet(),
        )
    }

    @Test
    fun `fixed commands keep their buttons`() {
        fun refused(s: PadShortcut) = d.addShortcut(s) as BindingResult.Refused
        assertEquals(BindingProblem.DPAD_RESERVED, refused(PadShortcut(combo(PadButton.L2, PadButton.DPAD_UP), rotate)).problem)
        assertEquals(BindingProblem.SAME_BUTTON_TWICE, refused(PadShortcut(combo(PadButton.A, PadButton.A), rotate)).problem)
        // Takeoff / land buttons belong to it alone.
        assertEquals(PadCommand.TAKEOFF_LAND, refused(PadShortcut(combo(PadButton.R2, PadButton.A), rotate)).other)
        assertEquals(PadCommand.TAKEOFF_LAND, refused(PadShortcut(combo(PadButton.L2, PadButton.Y), rotate)).other)
        // The stop button too: stopping always works.
        val stop = refused(PadShortcut(combo(PadButton.L2, PadButton.B), rotate))
        assertEquals(BindingProblem.STOP_RESERVED, stop.problem)
        assertEquals(PadCommand.STOP_SMART_VIDEO, stop.other)
        assertEquals(BindingProblem.STOP_RESERVED, refused(PadShortcut(PadBinding(PadButton.B), PadFunction.Photo)).problem)
        // A single-button command as modifier would fire when held.
        assertEquals(PadCommand.FAST_MODE, refused(PadShortcut(combo(PadButton.X, PadButton.A), rotate)).other)
        // The flip button is held with the D-pad: it can be a modifier, never the pressed button.
        assertEquals(PadCommand.FLIP, refused(PadShortcut(combo(PadButton.A, PadButton.L2), rotate)).other)
        assertEquals(PadCommand.FLIP, refused(PadShortcut(PadBinding(PadButton.L2), PadFunction.Photo)).other)
        // Same single button as a fixed command.
        assertEquals(PadCommand.PHOTO, refused(PadShortcut(PadBinding(PadButton.R1), PadFunction.Tripod)).other)
    }

    @Test
    fun `shortcuts never clash with each other`() {
        val first = PadShortcut(combo(PadButton.L2, PadButton.A), rotate)
        val b = d.with(first)
        fun otherOf(s: PadShortcut) = (b.addShortcut(s) as BindingResult.Refused).also {
            assertEquals(BindingProblem.CONFLICT, it.problem)
        }.otherShortcut
        assertEquals(first, otherOf(PadShortcut(combo(PadButton.L2, PadButton.A), PadFunction.Photo)), "same combo")
        // A held as a modifier would chain into the first shortcut, or fire a single A.
        assertEquals(first, otherOf(PadShortcut(combo(PadButton.A, PadButton.START), PadFunction.Flip(FlipDirection.BACK))))
        // Same button with another modifier, or alone (the modifier shifts it): allowed.
        assertTrue(b.addShortcut(PadShortcut(combo(PadButton.SELECT, PadButton.A), PadFunction.Bounce)) is BindingResult.Ok)
        assertTrue(b.addShortcut(PadShortcut(PadBinding(PadButton.A), PadFunction.Photo)) is BindingResult.Ok)
        val withSingle = b.with(PadShortcut(PadBinding(PadButton.START), PadFunction.Photo))
        assertEquals(
            PadShortcut(PadBinding(PadButton.START), PadFunction.Photo),
            (withSingle.addShortcut(PadShortcut(combo(PadButton.START, PadButton.A), PadFunction.Bounce)) as BindingResult.Refused).otherShortcut,
            "START held as a modifier would take a photo",
        )
    }

    @Test
    fun `editing a shortcut is checked against the others only, deleting frees its buttons`() {
        val a = PadShortcut(combo(PadButton.L2, PadButton.A), rotate)
        val x = PadShortcut(combo(PadButton.L2, PadButton.X), PadFunction.Flip(FlipDirection.FORWARD))
        val b = d.with(a, x)
        val edited = b.replaceShortcut(0, a.copy(function = PadFunction.Tracking(TrackingMode.ORBIT, -1))) as BindingResult.Ok
        assertEquals(PadFunction.Tracking(TrackingMode.ORBIT, -1), edited.bindings.shortcuts[0].function)
        assertEquals(x, edited.bindings.shortcuts[1])
        val clash = b.replaceShortcut(0, a.copy(binding = x.binding)) as BindingResult.Refused
        assertEquals(x, clash.otherShortcut)
        // A moving function cannot be put on a single-button shortcut.
        val single = d.with(PadShortcut(PadBinding(PadButton.A), PadFunction.Photo))
        assertEquals(
            BindingResult.Refused(BindingProblem.SHORTCUT_COMBO_REQUIRED),
            single.replaceShortcut(0, PadShortcut(PadBinding(PadButton.A), rotate)),
        )
        val removed = b.removeShortcut(0)
        assertEquals(listOf(x), removed.shortcuts)
        assertTrue(removed.addShortcut(a.copy(function = PadFunction.Photo)) is BindingResult.Ok)
        assertEquals(d, b.withoutShortcuts())
    }

    @Test
    fun `a fixed command cannot take a shortcut's buttons`() {
        val s = PadShortcut(combo(PadButton.L2, PadButton.A), rotate)
        val b = d.with(s)
        val flip = b.assign(PadCommand.FLIP, PadBinding(PadButton.A)) as BindingResult.Refused
        assertEquals(BindingProblem.CONFLICT, flip.problem)
        assertEquals(s, flip.otherShortcut)
        val stop = b.assign(PadCommand.STOP_SMART_VIDEO, PadBinding(PadButton.A)) as BindingResult.Refused
        assertEquals(s, stop.otherShortcut)
        // A single command on the shortcut's pressed button is fine: L2 shifts it.
        val photo = b.assign(PadCommand.PHOTO, PadBinding(PadButton.A)) as BindingResult.Ok
        assertEquals(listOf(s), photo.bindings.shortcuts)
    }

    @Test
    fun `default commands keep the shortcuts that still fit`() {
        val moved = (d.assign(PadCommand.PHOTO, PadBinding(PadButton.A)) as BindingResult.Ok).bindings
        val keep = PadShortcut(combo(PadButton.L2, PadButton.START), rotate)
        val drop = PadShortcut(PadBinding(PadButton.R1), PadFunction.Tripod) // R1 is free while photo is on A
        val b = moved.with(keep, drop)
        val reset = b.withDefaultCommands()
        assertEquals(PadBinding(PadButton.R1), reset[PadCommand.PHOTO])
        assertEquals(listOf(keep), reset.shortcuts)
    }

    // ------------------------------------------------------------------ mapping

    @Test
    fun `modifier then button fires the shortcut, the button alone keeps its command`() {
        val b = d.with(
            PadShortcut(combo(PadButton.L2, PadButton.X), rotate),
            PadShortcut(combo(PadButton.L2, PadButton.A), PadFunction.RunMission("Carré 2 m")),
            PadShortcut(PadBinding(PadButton.START), PadFunction.Tripod),
        )
        val m = GamepadMapper(b)
        assertEquals(PadAction.ToggleFastMode, m.onButtonDown(PadButton.X), "X alone")
        m.onButtonUp(PadButton.X)
        assertNull(m.onButtonDown(PadButton.L2), "the modifier alone fires nothing")
        assertEquals(PadAction.Shortcut(rotate), m.onButtonDown(PadButton.X))
        assertNull(m.onButtonDown(PadButton.X), "auto-repeat")
        m.onButtonUp(PadButton.X)
        assertEquals(PadAction.Shortcut(PadFunction.RunMission("Carré 2 m")), m.onButtonDown(PadButton.A))
        assertNull(m.onButtonDown(PadButton.DPAD_LEFT))
        assertEquals(PadAction.Flip(FlipDirection.LEFT), m.onButtonUp(PadButton.DPAD_LEFT), "flips still work with L2")
        m.reset()
        assertEquals(PadAction.Shortcut(PadFunction.Tripod), m.onButtonDown(PadButton.START))
    }

    @Test
    fun `a shortcut combo is ordered - button first then modifier fires nothing extra`() {
        val m = GamepadMapper(d.with(PadShortcut(combo(PadButton.L2, PadButton.A), rotate)))
        assertNull(m.onButtonDown(PadButton.A))
        assertNull(m.onButtonDown(PadButton.L2), "no 360 when A was pressed first")
    }

    @Test
    fun `two modifiers held - ambiguous, nothing fires`() {
        val b = d.with(
            PadShortcut(combo(PadButton.L2, PadButton.A), rotate),
            PadShortcut(combo(PadButton.SELECT, PadButton.A), PadFunction.Bounce),
            PadShortcut(PadBinding(PadButton.A), PadFunction.Photo),
        )
        val m = GamepadMapper(b)
        m.onButtonDown(PadButton.L2)
        m.onButtonDown(PadButton.SELECT)
        assertNull(m.onButtonDown(PadButton.A), "neither shortcut, nor the single A")
        m.onButtonUp(PadButton.A); m.onButtonUp(PadButton.L2)
        assertEquals(PadAction.Shortcut(PadFunction.Bounce), m.onButtonDown(PadButton.A))
    }

    @Test
    fun `stop and takeoff still fire with shortcuts around`() {
        val m = GamepadMapper(d.with(PadShortcut(combo(PadButton.L2, PadButton.A), rotate)))
        m.onButtonDown(PadButton.L2)
        assertEquals(PadAction.StopSmartVideo, m.onButtonDown(PadButton.B), "stop works with the modifier held")
        m.reset()
        m.onButtonDown(PadButton.R2)
        assertEquals(PadAction.TakeoffOrLand, m.onButtonDown(PadButton.Y))
    }

    // ------------------------------------------------------------------ storage

    @Test
    fun `every function survives storage`() {
        val all = PadFunction.Catalog + PadFunction.RunMission("Révélation : recul et montée") + PadFunction.RunMission("a=b+c")
        all.forEach { assertEquals(it, PadFunction.decode(it.encode()), "$it") }
        assertNull(PadFunction.decode("WARP:9"))
        assertNull(PadFunction.decode("SMART:NOPE"))
        assertNull(PadFunction.decode("MISSION:"))
    }

    @Test
    fun `shortcuts survive storage next to the fixed commands`() {
        val b = d.with(
            PadShortcut(combo(PadButton.L2, PadButton.X), rotate),
            PadShortcut(combo(PadButton.L2, PadButton.A), PadFunction.RunMission("Aller = retour")),
            PadShortcut(combo(PadButton.L2, PadButton.START), PadFunction.Tracking(TrackingMode.ORBIT, -1)),
            PadShortcut(PadBinding(PadButton.SELECT), PadFunction.Stop),
        )
        val decoded = GamepadBindings.decode(b.encode(), b.encodeShortcuts())
        assertEquals(b, decoded)
        assertEquals(b.shortcuts, decoded.shortcuts)
        // The fixed text is unchanged: an older version still reads it.
        assertEquals(d.encode(), b.encode())
    }

    @Test
    fun `old stores load without shortcuts, broken shortcut lines are dropped one by one`() {
        val old = (d.assign(PadCommand.PHOTO, PadBinding(PadButton.A)) as BindingResult.Ok).bindings
        val loaded = GamepadBindings.decode(old.encode())
        assertEquals(old, loaded)
        assertTrue(loaded.shortcuts.isEmpty())
        assertEquals(old, GamepadBindings.decode(old.encode(), ""))
        val text = listOf(
            "garbage",
            "L2+X=SMART:CIRCLE",
            "L2+X=PHOTO", // same combo: dropped
            "R1=TRIPOD", // fine: R1 is free (photo on A)
            "A=SMART:ROTATE_360", // single-button manoeuvre: never loaded
            "L2+B=PHOTO", // stop button
            "L2+START=WARP:9",
            "",
        ).joinToString("\n")
        val decoded = GamepadBindings.decode(old.encode(), text)
        assertEquals(
            listOf(
                PadShortcut(combo(PadButton.L2, PadButton.X), PadFunction.SmartVideo(SmartVideoMode.CIRCLE)),
                PadShortcut(PadBinding(PadButton.R1), PadFunction.Tripod),
            ),
            decoded.shortcuts,
        )
        assertEquals(PadBinding(PadButton.A), decoded[PadCommand.PHOTO])
        // Unsafe fixed text still gives the default layout; the shortcuts are checked against it.
        val fallback = GamepadBindings.decode("TAKEOFF_LAND=A", "R1=TRIPOD\nL2+X=SMART:CIRCLE")
        assertEquals(d[PadCommand.PHOTO], fallback[PadCommand.PHOTO])
        assertEquals(listOf(PadShortcut(combo(PadButton.L2, PadButton.X), PadFunction.SmartVideo(SmartVideoMode.CIRCLE))), fallback.shortcuts)
    }
}
