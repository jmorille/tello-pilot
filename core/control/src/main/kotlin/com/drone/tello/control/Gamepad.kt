package com.drone.tello.control

import com.drone.tello.protocol.FlipDirection
import kotlin.math.abs
import kotlin.math.sign

/** Logical gamepad buttons (standard Android layout: Xbox / PlayStation / 8BitDo…). */
enum class PadButton { A, B, X, Y, L1, R1, L2, R2, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, START, SELECT }

/** What a button combination asks for. */
sealed interface PadAction {
    /** Takeoff on the ground, land in flight (a combo, R2 + Y by default). */
    data object TakeoffOrLand : PadAction
    data object Photo : PadAction
    data object ToggleRecording : PadAction
    data class Flip(val direction: FlipDirection) : PadAction
    data object ToggleFastMode : PadAction
    /** Stop the running smart video (a manoeuvre must always be stoppable from the pad). */
    data object StopSmartVideo : PadAction
}

/** Stick positions in screen convention: x right-positive, y up-positive, both -1..1. */
data class PadSticks(val leftX: Float, val leftY: Float, val rightX: Float, val rightY: Float) {
    companion object {
        val Neutral = PadSticks(0f, 0f, 0f, 0f)
    }
}

/** Commands the pilot can assign to pad buttons. */
enum class PadCommand { TAKEOFF_LAND, FLIP, PHOTO, RECORD, FAST_MODE, STOP_SMART_VIDEO }

/**
 * [button] pressed alone, or together with [modifier] (a combo fires in either order).
 * For [PadCommand.FLIP], [button] is the button held while the D-pad gives the direction.
 */
data class PadBinding(val button: PadButton, val modifier: PadButton? = null) {
    val buttons: Set<PadButton> get() = setOfNotNull(button, modifier)
    val isCombo: Boolean get() = modifier != null

    fun encode(): String = if (modifier == null) button.name else "${modifier.name}+${button.name}"

    companion object {
        fun decode(text: String): PadBinding? {
            val buttons = text.split('+').map { part ->
                PadButton.entries.firstOrNull { it.name == part.trim() } ?: return null
            }
            return when (buttons.size) {
                1 -> PadBinding(buttons[0])
                2 -> PadBinding(buttons[1], buttons[0])
                else -> null
            }
        }
    }
}

/** Why a binding was refused. */
enum class BindingProblem {
    /** The D-pad gives the flip directions. */
    DPAD_RESERVED,
    /** Takeoff / land must stay a two-button combo (no accidental takeoff). */
    COMBO_REQUIRED,
    /** The flip button is held alone with the D-pad. */
    FLIP_SINGLE_BUTTON,
    SAME_BUTTON_TWICE,
    /** Same buttons as [BindingResult.Refused.other], or a button it holds for a combo. */
    CONFLICT,
}

sealed interface BindingResult {
    /** Applied; [swappedWith] took the previous binding of the edited command. */
    data class Ok(val bindings: GamepadBindings, val swappedWith: PadCommand? = null) : BindingResult
    data class Refused(val problem: BindingProblem, val other: PadCommand? = null) : BindingResult
}

/**
 * Button assignment of every [PadCommand]. Always valid: only built from [Default], [assign] or
 * [decode], which all reject unsafe or ambiguous layouts.
 *
 * Rules: the D-pad is reserved for the flip directions; takeoff / land is a combo whose buttons
 * are used by nothing else; a button held for a combo or for flips (a "hold" button) is never
 * also a single-button command, otherwise pressing the first button of the combo would fire it.
 */
class GamepadBindings private constructor(private val map: Map<PadCommand, PadBinding>) {

    operator fun get(command: PadCommand): PadBinding = map.getValue(command)

    val entries: List<Pair<PadCommand, PadBinding>> = PadCommand.entries.map { it to map.getValue(it) }

    /** Assigns [binding] to [command]; a conflicting command swaps with it when that is valid. */
    fun assign(command: PadCommand, binding: PadBinding): BindingResult {
        intrinsicProblem(command, binding)?.let { return BindingResult.Refused(it) }
        if (get(command) == binding) return BindingResult.Ok(this)
        val candidate = map + (command to binding)
        val conflict = conflictOf(candidate) ?: return BindingResult.Ok(GamepadBindings(candidate))
        val other = conflict.firstOrNull { it != command } ?: return BindingResult.Refused(BindingProblem.CONFLICT)
        val swapped = candidate + (other to get(command))
        if (intrinsicProblem(other, get(command)) == null && conflictOf(swapped) == null) {
            return BindingResult.Ok(GamepadBindings(swapped), swappedWith = other)
        }
        return BindingResult.Refused(BindingProblem.CONFLICT, other)
    }

    fun encode(): String = entries.joinToString(";") { (c, b) -> "${c.name}=${b.encode()}" }

    override fun equals(other: Any?) = other is GamepadBindings && other.map == map
    override fun hashCode() = map.hashCode()
    override fun toString() = encode()

    companion object {
        /** Official GameSir layout: R2+Y take off / land, L2 + D-pad flip, R1 photo, L1 video, X fast, B stop. */
        val Default = GamepadBindings(
            mapOf(
                PadCommand.TAKEOFF_LAND to PadBinding(PadButton.Y, PadButton.R2),
                PadCommand.FLIP to PadBinding(PadButton.L2),
                PadCommand.PHOTO to PadBinding(PadButton.R1),
                PadCommand.RECORD to PadBinding(PadButton.L1),
                PadCommand.FAST_MODE to PadBinding(PadButton.X),
                PadCommand.STOP_SMART_VIDEO to PadBinding(PadButton.B),
            ),
        )

        private val DPAD = setOf(PadButton.DPAD_UP, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT)

        /** Stored text; anything missing is the default, anything unsafe gives the whole [Default]. */
        fun decode(text: String?): GamepadBindings {
            if (text.isNullOrBlank()) return Default
            val parsed = text.split(';').mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val command = PadCommand.entries.firstOrNull { it.name == parts[0].trim() } ?: return@mapNotNull null
                PadBinding.decode(parts[1])?.let { command to it }
            }.toMap()
            val map = PadCommand.entries.associateWith { parsed[it] ?: Default[it] }
            val valid = map.all { (c, b) -> intrinsicProblem(c, b) == null } && conflictOf(map) == null
            return if (valid) GamepadBindings(map) else Default
        }

        internal fun intrinsicProblem(command: PadCommand, binding: PadBinding): BindingProblem? = when {
            binding.buttons.any { it in DPAD } -> BindingProblem.DPAD_RESERVED
            binding.modifier == binding.button -> BindingProblem.SAME_BUTTON_TWICE
            command == PadCommand.TAKEOFF_LAND && !binding.isCombo -> BindingProblem.COMBO_REQUIRED
            command == PadCommand.FLIP && binding.isCombo -> BindingProblem.FLIP_SINGLE_BUTTON
            else -> null
        }

        /** Buttons held for [command]: both buttons of a combo, the flip button. */
        private fun holdButtons(command: PadCommand, binding: PadBinding): Set<PadButton> =
            if (command == PadCommand.FLIP || binding.isCombo) binding.buttons else emptySet()

        private fun isSingle(command: PadCommand, binding: PadBinding) = command != PadCommand.FLIP && !binding.isCombo

        /** The first pair of commands that cannot coexist, if any. */
        internal fun conflictOf(map: Map<PadCommand, PadBinding>): Set<PadCommand>? {
            for (a in PadCommand.entries) for (b in PadCommand.entries) {
                if (a.ordinal >= b.ordinal) continue
                val ba = map.getValue(a)
                val bb = map.getValue(b)
                // Takeoff / land shares no button with anything: holding a button for another
                // combo or for flips must never be half of the takeoff combo.
                val takeoffShared = (a == PadCommand.TAKEOFF_LAND || b == PadCommand.TAKEOFF_LAND) &&
                    ba.buttons.any { it in bb.buttons }
                val clash = takeoffShared ||
                    (ba.buttons == bb.buttons && (a == PadCommand.FLIP) == (b == PadCommand.FLIP)) ||
                    (isSingle(a, ba) && ba.button in holdButtons(b, bb)) ||
                    (isSingle(b, bb) && bb.button in holdButtons(a, ba))
                if (clash) return setOf(a, b)
            }
            return null
        }
    }
}

/**
 * Gamepad mapping, independent of Android (the app feeds it from `KeyEvent` / `MotionEvent`).
 *
 * Sticks work like the on-screen joysticks; buttons follow [bindings] (default: official GameSir
 * layout). Combos fire once, when their second button goes down, so holding a button never
 * repeats an action.
 */
class GamepadMapper(var bindings: GamepadBindings = GamepadBindings.Default) {
    private val held = LinkedHashSet<PadButton>()

    /** Buttons currently down (copy). */
    val heldButtons: Set<PadButton> get() = held.toSet()

    /** Returns the action triggered by pressing [button], if any. */
    fun onButtonDown(button: PadButton): PadAction? {
        if (!held.add(button)) return null // auto-repeat
        flipDirection(button)?.let { direction ->
            return if (bindings[PadCommand.FLIP].button in held) PadAction.Flip(direction) else null
        }
        // A combo completed by this button wins over a single-button command.
        val combo = bindings.entries.firstOrNull { (c, b) ->
            val modifier = b.modifier
            c != PadCommand.FLIP && modifier != null &&
                ((button == b.button && modifier in held) || (button == modifier && b.button in held))
        }
        if (combo != null) return actionOf(combo.first)
        return bindings.entries.firstOrNull { (c, b) -> isSingle(c, b) && b.button == button }?.let { actionOf(it.first) }
    }

    fun onButtonUp(button: PadButton) {
        held.remove(button)
    }

    /** Controller disconnected / app paused: forget the held buttons. */
    fun reset() = held.clear()

    private fun isSingle(command: PadCommand, binding: PadBinding) = command != PadCommand.FLIP && !binding.isCombo

    private fun flipDirection(button: PadButton): FlipDirection? = when (button) {
        PadButton.DPAD_UP -> FlipDirection.FORWARD
        PadButton.DPAD_DOWN -> FlipDirection.BACK
        PadButton.DPAD_LEFT -> FlipDirection.LEFT
        PadButton.DPAD_RIGHT -> FlipDirection.RIGHT
        else -> null
    }

    private fun actionOf(command: PadCommand): PadAction? = when (command) {
        PadCommand.TAKEOFF_LAND -> PadAction.TakeoffOrLand
        PadCommand.PHOTO -> PadAction.Photo
        PadCommand.RECORD -> PadAction.ToggleRecording
        PadCommand.FAST_MODE -> PadAction.ToggleFastMode
        PadCommand.STOP_SMART_VIDEO -> PadAction.StopSmartVideo
        PadCommand.FLIP -> null
    }

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
