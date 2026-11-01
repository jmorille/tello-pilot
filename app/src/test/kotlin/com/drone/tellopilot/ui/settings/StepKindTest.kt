package com.drone.tellopilot.ui.settings

import com.drone.tello.control.MissionStep
import com.drone.tello.control.MoveDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepKindTest {

    @Test
    fun `each kind builds its step with the right sign`() {
        assertEquals(MissionStep.Climb(1.5f), StepKind.UP.build(1.5f))
        assertEquals(MissionStep.Climb(-1.5f), StepKind.DOWN.build(1.5f))
        assertEquals(MissionStep.Move(MoveDirection.FORWARD, 2f), StepKind.FORWARD.build(2f))
        assertEquals(MissionStep.Move(MoveDirection.BACK, 2f), StepKind.BACK.build(2f))
        assertEquals(MissionStep.Move(MoveDirection.LEFT, 2f), StepKind.LEFT.build(2f))
        assertEquals(MissionStep.Move(MoveDirection.RIGHT, 2f), StepKind.RIGHT.build(2f))
        assertEquals(MissionStep.Rotate(90f), StepKind.TURN_RIGHT.build(90f))
        assertEquals(MissionStep.Rotate(-90f), StepKind.TURN_LEFT.build(90f))
        assertEquals(MissionStep.Hover(3f), StepKind.HOVER.build(3f))
    }

    @Test
    fun `every default value is a valid step`() {
        StepKind.entries.forEach { assertTrue(it.name, it.build(it.default).isValid()) }
    }

    @Test
    fun `typed values accept a decimal comma`() {
        assertEquals(MissionStep.Climb(1.5f), StepKind.UP.parse("1,5"))
        assertEquals(MissionStep.Hover(2.5f), StepKind.HOVER.parse("2.5"))
        assertEquals(MissionStep.Rotate(-45f), StepKind.TURN_LEFT.parse("45"))
    }

    @Test
    fun `non numbers give no step`() {
        assertNull(StepKind.FORWARD.parse(""))
        assertNull(StepKind.FORWARD.parse("abc"))
        assertNull(StepKind.FORWARD.parse("1,2,3"))
    }
}
