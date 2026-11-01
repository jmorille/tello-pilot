package com.drone.tello.control

import com.drone.tello.protocol.FlipDirection
import kotlin.math.abs
import kotlin.math.sign

/** Logical gamepad buttons (standard Android layout: Xbox / PlayStation / 8BitDo…). */
enum class PadButton { A, B, X, Y, L1, R1, L2, R2, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, START, SELECT }

/** What a button combination asks for. */
sealed interface PadAction {
    /** R2 + Y (official combo): takeoff on the ground, land in flight. */
    data object TakeoffOrLand : PadAction
    data object Photo : PadAction
    data object ToggleRecording : PadAction
    data class Flip(val direction: FlipDirection) : PadAction
    data object ToggleFastMode : PadAction
    /** B: stop the running smart video (a manoeuvre must always be stoppable from the pad). */
    data object StopSmartVideo : PadAction
}

/** Stick positions in screen convention: x right-positive, y up-positive, both -1..1. */
data class PadSticks(val leftX: Float, val leftY: Float, val rightX: Float, val rightY: Float) {
    companion object {
        val Neutral = PadSticks(0f, 0f, 0f, 0f)
    }
}

/**
 * Gamepad mapping, independent of Android (the app feeds it from `KeyEvent` / `MotionEvent`).
 *
 * Mapping (official GameSir layout): sticks like the on-screen joysticks, R2+Y take off / land,
 * L2 + D-pad flip, R1 photo, L1 video, X fast mode, B stop smart video. Combos fire once, when the second button
 * goes down, so holding a button never repeats an action.
 */
class GamepadMapper {
    private val held = HashSet<PadButton>()

    /** Returns the action triggered by pressing [button], if any. */
    fun onButtonDown(button: PadButton): PadAction? {
        if (!held.add(button)) return null // auto-repeat
        return when (button) {
            PadButton.Y -> if (PadButton.R2 in held) PadAction.TakeoffOrLand else null
            PadButton.R2 -> if (PadButton.Y in held) PadAction.TakeoffOrLand else null
            PadButton.R1 -> PadAction.Photo
            PadButton.L1 -> PadAction.ToggleRecording
            PadButton.X -> PadAction.ToggleFastMode
            PadButton.B -> PadAction.StopSmartVideo
            PadButton.DPAD_UP -> flipIfArmed(FlipDirection.FORWARD)
            PadButton.DPAD_DOWN -> flipIfArmed(FlipDirection.BACK)
            PadButton.DPAD_LEFT -> flipIfArmed(FlipDirection.LEFT)
            PadButton.DPAD_RIGHT -> flipIfArmed(FlipDirection.RIGHT)
            else -> null
        }
    }

    fun onButtonUp(button: PadButton) {
        held.remove(button)
    }

    /** Controller disconnected / app paused: forget the held buttons. */
    fun reset() = held.clear()

    private fun flipIfArmed(direction: FlipDirection): PadAction? =
        if (PadButton.L2 in held) PadAction.Flip(direction) else null

    companion object {
        /**
         * Converts raw Android axes (y down-positive) to [PadSticks], removing the device's flat
         * (centre noise) region and rescaling so full deflection still reaches 1.
         */
        fun sticks(lx: Float, ly: Float, rx: Float, ry: Float, flat: Float): PadSticks {
            val f = flat.coerceIn(0f, 0.5f)
            return PadSticks(axis(lx, f), -axis(ly, f), axis(rx, f), -axis(ry, f))
        }

        internal fun axis(value: Float, flat: Float): Float {
            if (value.isNaN()) return 0f
            val v = value.coerceIn(-1f, 1f)
            val m = abs(v)
            return if (m <= flat) 0f else sign(v) * (m - flat) / (1f - flat)
        }

        /** Analog triggers count as pressed beyond this travel. */
        const val TRIGGER_THRESHOLD = 0.5f
    }
}
