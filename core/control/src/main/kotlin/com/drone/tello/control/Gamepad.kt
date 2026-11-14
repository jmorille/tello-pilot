package com.drone.tello.control

import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.SmartVideoMode
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
    /**
     * A flip being prepared was cancelled: a second D-pad direction went down before the
     * release (diagonal / rolled press, e.g. a GameSir cross that reports "up" then "left"
     * 30-90 ms later). Nothing is flown; the pilot is told.
     */
    data object FlipCancelled : PadAction
    data object ToggleFastMode : PadAction
    /**
     * Stop what runs: smart video, automatic flight, bounce (a manoeuvre must always be
     * stoppable from the pad).
     */
    data object StopSmartVideo : PadAction
    /** A pilot shortcut ([PadShortcut]) was pressed. */
    data class Shortcut(val function: PadFunction) : PadAction
}

/**
 * Advanced function a pilot shortcut triggers. [moves]: the drone moves by itself (firmware
 * manoeuvre, automatic flight, palm landing). Such a shortcut is always a two-button combo, goes
 * through the same checks as the screen buttons and is refused while a manoeuvre runs.
 */
sealed interface PadFunction {
    val moves: Boolean
    fun encode(): String

    data class SmartVideo(val mode: SmartVideoMode) : PadFunction {
        override val moves get() = true
        override fun encode() = "SMART:${mode.name}"
    }

    data class Flip(val direction: FlipDirection) : PadFunction {
        override val moves get() = true
        override fun encode() = "FLIP:${direction.name}"
    }

    /** Bounce on / off (switching it off is a stop: always allowed). */
    data object Bounce : PadFunction {
        override val moves get() = true
        override fun encode() = "BOUNCE"
    }

    /**
     * Mission by [name] (presets and the pilot's own, looked up when pressed): a deleted mission
     * leaves the shortcut inert, the pilot is told.
     */
    data class RunMission(val name: String) : PadFunction {
        override val moves get() = true
        override fun encode() = "MISSION:" + Mission.cleanName(name)
    }

    /** [orbitDirection]: +1 = to the right, -1 = to the left (orbit only, +1 otherwise). */
    data class Tracking(val mode: TrackingMode, val orbitDirection: Int = 1) : PadFunction {
        override val moves get() = true
        override fun encode() = "TRACK:${mode.name}" + if (mode == TrackingMode.ORBIT && orbitDirection < 0) ":L" else ""
    }

    data object PalmLand : PadFunction {
        override val moves get() = true
        override fun encode() = "PALM_LAND"
    }

    data object Tripod : PadFunction {
        override val moves get() = false
        override fun encode() = "TRIPOD"
    }

    data object FastMode : PadFunction {
        override val moves get() = false
        override fun encode() = "FAST"
    }

    data object Photo : PadFunction {
        override val moves get() = false
        override fun encode() = "PHOTO"
    }

    data object Record : PadFunction {
        override val moves get() = false
        override fun encode() = "RECORD"
    }

    /** Stops the smart video, the automatic flight and the bounce. */
    data object Stop : PadFunction {
        override val moves get() = false
        override fun encode() = "STOP"
    }

    companion object {
        /** Every function but the missions (the pilot's list), in menu order. */
        val Catalog: List<PadFunction> = buildList {
            SmartVideoMode.entries.forEach { add(SmartVideo(it)) }
            FlipDirection.entries.forEach { add(Flip(it)) }
            add(Bounce)
            add(Tracking(TrackingMode.FOLLOW))
            add(Tracking(TrackingMode.SPOTLIGHT))
            add(Tracking(TrackingMode.ORBIT, -1))
            add(Tracking(TrackingMode.ORBIT, 1))
            add(PalmLand)
            add(Tripod)
            add(FastMode)
            add(Photo)
            add(Record)
            add(Stop)
        }

        fun decode(text: String): PadFunction? {
            val kind = text.substringBefore(':')
            val arg = text.substringAfter(':', missingDelimiterValue = "")
            return when (kind) {
                "SMART" -> SmartVideoMode.entries.firstOrNull { it.name == arg }?.let(::SmartVideo)
                "FLIP" -> FlipDirection.entries.firstOrNull { it.name == arg }?.let(::Flip)
                "BOUNCE" -> Bounce
                "MISSION" -> Mission.cleanName(arg).takeIf { it.isNotEmpty() }?.let(::RunMission)
                "TRACK" -> {
                    val mode = TrackingMode.entries.firstOrNull { it.name == arg.substringBefore(':') } ?: return null
                    Tracking(mode, if (mode == TrackingMode.ORBIT && arg.endsWith(":L")) -1 else 1)
                }
                "PALM_LAND" -> PalmLand
                "TRIPOD" -> Tripod
                "FAST" -> FastMode
                "PHOTO" -> Photo
                "RECORD" -> Record
                "STOP" -> Stop
                else -> null
            }
        }
    }
}

/**
 * A pilot shortcut: [binding] triggers [function]. Unlike the fixed commands, a shortcut combo
 * is ordered: its modifier is held first, then its button is pressed (a "shift" key), so the
 * button may also be a single-button command of its own.
 */
data class PadShortcut(val binding: PadBinding, val function: PadFunction) {
    fun encode(): String = "${binding.encode()}=${function.encode()}"

    companion object {
        fun decode(text: String): PadShortcut? {
            val binding = PadBinding.decode(text.substringBefore('=', missingDelimiterValue = "").trim()) ?: return null
            val function = PadFunction.decode(text.substringAfter('=').trim()) ?: return null
            return PadShortcut(binding, function)
        }
    }
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
    /**
     * Same buttons as [BindingResult.Refused.other] / [BindingResult.Refused.otherShortcut], or
     * a button it holds for a combo.
     */
    CONFLICT,
    /** A shortcut that makes the drone move must be a two-button combo (no accidental trigger). */
    SHORTCUT_COMBO_REQUIRED,
    /** The buttons of the stop command belong to it alone: stopping always works, held button or not. */
    STOP_RESERVED,
    /** [GamepadBindings.MAX_SHORTCUTS] reached. */
    TOO_MANY,
}

sealed interface BindingResult {
    /** Applied; [swappedWith] took the previous binding of the edited command. */
    data class Ok(val bindings: GamepadBindings, val swappedWith: PadCommand? = null) : BindingResult
    /** [other] / [otherShortcut]: the command or shortcut in the way, when there is one. */
    data class Refused(
        val problem: BindingProblem,
        val other: PadCommand? = null,
        val otherShortcut: PadShortcut? = null,
    ) : BindingResult
}

/**
 * Button assignment of every [PadCommand]. Always valid: only built from [Default], [assign] or
 * [decode], which all reject unsafe or ambiguous layouts.
 *
 * Rules: the D-pad is reserved for the flip directions; takeoff / land is a combo whose buttons
 * are used by nothing else; a button held for a combo or for flips (a "hold" button) is never
 * also a single-button command, otherwise pressing the first button of the combo would fire it.
 *
 * [shortcuts] come on top of the fixed commands, with their own rules (see [shortcutProblem]):
 * a shortcut that makes the drone move is a combo; a shortcut never uses a takeoff / land or a
 * stop button, never the flip button as its pressed button, and its modifier is never a
 * single-button command. Their combos are ordered (modifier held first), which lets a modifier
 * such as the flip button shift single-button commands to shortcuts.
 */
class GamepadBindings private constructor(
    private val map: Map<PadCommand, PadBinding>,
    val shortcuts: List<PadShortcut> = emptyList(),
) {

    operator fun get(command: PadCommand): PadBinding = map.getValue(command)

    val entries: List<Pair<PadCommand, PadBinding>> = PadCommand.entries.map { it to map.getValue(it) }

    /**
     * Assigns [binding] to [command]; a conflicting command swaps with it when that is valid. A
     * clash with a shortcut is refused (the pilot edits or deletes the shortcut first).
     */
    fun assign(command: PadCommand, binding: PadBinding): BindingResult {
        intrinsicProblem(command, binding)?.let { return BindingResult.Refused(it) }
        if (get(command) == binding) return BindingResult.Ok(this)
        val candidate = map + (command to binding)
        val conflict = conflictOf(candidate)
        var swappedWith: PadCommand? = null
        val result = if (conflict == null) {
            candidate
        } else {
            val other = conflict.firstOrNull { it != command } ?: return BindingResult.Refused(BindingProblem.CONFLICT)
            val swapped = candidate + (other to get(command))
            if (intrinsicProblem(other, get(command)) != null || conflictOf(swapped) != null) {
                return BindingResult.Refused(BindingProblem.CONFLICT, other)
            }
            swappedWith = other
            swapped
        }
        shortcuts.firstOrNull { commandClash(it.binding, result) != null }?.let {
            return BindingResult.Refused(BindingProblem.CONFLICT, otherShortcut = it)
        }
        return BindingResult.Ok(GamepadBindings(result, shortcuts), swappedWith)
    }

    /** Adds [shortcut] at the end of the list, if it fits with everything else. */
    fun addShortcut(shortcut: PadShortcut): BindingResult {
        if (shortcuts.size >= MAX_SHORTCUTS) return BindingResult.Refused(BindingProblem.TOO_MANY)
        shortcutProblem(map, shortcuts, shortcut)?.let { return it }
        return BindingResult.Ok(GamepadBindings(map, shortcuts + shortcut))
    }

    /** Replaces the shortcut at [index] (new buttons or new function). */
    fun replaceShortcut(index: Int, shortcut: PadShortcut): BindingResult {
        if (index !in shortcuts.indices) return addShortcut(shortcut)
        shortcutProblem(map, shortcuts.filterIndexed { i, _ -> i != index }, shortcut)?.let { return it }
        return BindingResult.Ok(GamepadBindings(map, shortcuts.toMutableList().also { it[index] = shortcut }))
    }

    fun removeShortcut(index: Int): GamepadBindings =
        if (index !in shortcuts.indices) this else GamepadBindings(map, shortcuts.filterIndexed { i, _ -> i != index })

    fun withoutShortcuts(): GamepadBindings = GamepadBindings(map)

    /** The default fixed layout, keeping the shortcuts that still fit with it. */
    fun withDefaultCommands(): GamepadBindings = fromParts(Default.map, shortcuts)

    /** Fixed commands only (stored as before the shortcuts existed). */
    fun encode(): String = entries.joinToString(";") { (c, b) -> "${c.name}=${b.encode()}" }

    /** One shortcut per line. */
    fun encodeShortcuts(): String = shortcuts.joinToString("\n") { it.encode() }

    override fun equals(other: Any?) = other is GamepadBindings && other.map == map && other.shortcuts == shortcuts
    override fun hashCode() = 31 * map.hashCode() + shortcuts.hashCode()
    override fun toString() = encode() + if (shortcuts.isEmpty()) "" else " | " + shortcuts.joinToString(" ; ") { it.encode() }

    companion object {
        /** Enough for every function and a few missions; keeps the list readable. */
        const val MAX_SHORTCUTS = 24

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

        /**
         * Stored text of the fixed commands ([text]): anything missing is the default, anything
         * unsafe gives the whole [Default]. Then the [shortcuts] (absent before they existed):
         * each one that does not fit (unknown function, conflict) is dropped on its own.
         */
        fun decode(text: String?, shortcuts: String? = null): GamepadBindings {
            val list = shortcuts.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { PadShortcut.decode(it) }
            return fromParts(decodeCommands(text), list)
        }

        private fun decodeCommands(text: String?): Map<PadCommand, PadBinding> {
            if (text.isNullOrBlank()) return Default.map
            val parsed = text.split(';').mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val command = PadCommand.entries.firstOrNull { it.name == parts[0].trim() } ?: return@mapNotNull null
                PadBinding.decode(parts[1])?.let { command to it }
            }.toMap()
            val map = PadCommand.entries.associateWith { parsed[it] ?: Default[it] }
            val valid = map.all { (c, b) -> intrinsicProblem(c, b) == null } && conflictOf(map) == null
            return if (valid) map else Default.map
        }

        /** [map] plus the shortcuts of [list] that fit, in order. */
        private fun fromParts(map: Map<PadCommand, PadBinding>, list: List<PadShortcut>): GamepadBindings {
            val kept = ArrayList<PadShortcut>()
            list.forEach { if (kept.size < MAX_SHORTCUTS && shortcutProblem(map, kept, it) == null) kept += it }
            return GamepadBindings(map, kept)
        }

        /** Why [shortcut] cannot join the fixed commands [map] and the [others], if it cannot. */
        internal fun shortcutProblem(
            map: Map<PadCommand, PadBinding>,
            others: List<PadShortcut>,
            shortcut: PadShortcut,
        ): BindingResult.Refused? {
            val b = shortcut.binding
            when {
                b.buttons.any { it in DPAD } -> return BindingResult.Refused(BindingProblem.DPAD_RESERVED)
                b.modifier == b.button -> return BindingResult.Refused(BindingProblem.SAME_BUTTON_TWICE)
                shortcut.function.moves && !b.isCombo -> return BindingResult.Refused(BindingProblem.SHORTCUT_COMBO_REQUIRED)
            }
            commandClash(b, map)?.let { return it }
            others.firstOrNull { shortcutsClash(b, it.binding) }?.let {
                return BindingResult.Refused(BindingProblem.CONFLICT, otherShortcut = it)
            }
            return null
        }

        /**
         * A shortcut on [b] against the fixed commands: never a takeoff / land or a stop button;
         * its pressed button is never held for flips or for a fixed combo (pressing it would
         * complete or half-press that command); its modifier is never a single-button command
         * (holding it would fire that command). The modifier may be the flip button or a
         * button of another fixed combo: holding it then fires nothing by itself.
         */
        private fun commandClash(b: PadBinding, map: Map<PadCommand, PadBinding>): BindingResult.Refused? {
            for ((c, fixed) in map) {
                val shared = b.buttons.any { it in fixed.buttons }
                val clash = when {
                    c == PadCommand.TAKEOFF_LAND -> shared
                    c == PadCommand.STOP_SMART_VIDEO -> if (shared) return BindingResult.Refused(BindingProblem.STOP_RESERVED, c) else false
                    c == PadCommand.FLIP -> b.button == fixed.button
                    fixed.isCombo -> b.button in fixed.buttons
                    else -> (!b.isCombo && b.button == fixed.button) || b.modifier == fixed.button
                }
                if (clash) return BindingResult.Refused(BindingProblem.CONFLICT, c)
            }
            return null
        }

        /**
         * Two shortcuts: same buttons (in any order), or one's button is the other's modifier
         * (holding the modifier would fire the single one, or a held shortcut button would
         * chain into another shortcut).
         */
        private fun shortcutsClash(a: PadBinding, b: PadBinding): Boolean =
            a.buttons == b.buttons ||
                (b.modifier != null && a.button == b.modifier) ||
                (a.modifier != null && b.button == a.modifier)

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
 *
 * Flips fire on the *release* of the D-pad, like the official app: the flip button must be held
 * when the D-pad goes down and still held when it comes back up, and no other D-pad direction
 * may be pressed in between (a rolled press, up then right, is ambiguous: no flip at all until
 * the whole D-pad is released). One press, one flip at most.
 */
class GamepadMapper(var bindings: GamepadBindings = GamepadBindings.Default) {
    private val held = LinkedHashSet<PadButton>()

    /** D-pad direction waiting for its release to flip, if any. */
    private var pendingFlip: PadButton? = null
    /** Two D-pad directions were held together: no flip until the D-pad is fully released. */
    private var flipCancelled = false

    /** Buttons currently down (copy). */
    val heldButtons: Set<PadButton> get() = held.toSet()

    /** Returns the action triggered by pressing [button], if any (never a flip: see [onButtonUp]). */
    fun onButtonDown(button: PadButton): PadAction? {
        if (!held.add(button)) return null // auto-repeat
        if (flipDirection(button) != null) {
            val flipHeld = bindings[PadCommand.FLIP].button in held
            when {
                // Another direction already down (rolled press, diagonal): ambiguous, nothing.
                pendingFlip != null || held.count { flipDirection(it) != null } > 1 -> {
                    val wasPending = pendingFlip != null
                    pendingFlip = null
                    flipCancelled = true
                    // Told once, when a flip the pilot was preparing is dropped.
                    if (wasPending) return PadAction.FlipCancelled
                }
                flipHeld && !flipCancelled -> pendingFlip = button
            }
            return null
        }
        // The flip button released / pressed again in the middle: that press no longer flips.
        if (button == bindings[PadCommand.FLIP].button) pendingFlip = null
        // A combo completed by this button wins over a single-button command.
        val combo = bindings.entries.firstOrNull { (c, b) ->
            val modifier = b.modifier
            c != PadCommand.FLIP && modifier != null &&
                ((button == b.button && modifier in held) || (button == modifier && b.button in held))
        }
        if (combo != null) return actionOf(combo.first)
        // Shortcut combos: modifier held first, then this button. Two shortcuts completed at
        // once (two modifiers held) are ambiguous: nothing fires, not even a single command.
        val shortcutCombos = bindings.shortcuts.filter { s ->
            val modifier = s.binding.modifier
            modifier != null && s.binding.button == button && modifier in held
        }
        if (shortcutCombos.isNotEmpty()) return shortcutCombos.singleOrNull()?.let { PadAction.Shortcut(it.function) }
        bindings.entries.firstOrNull { (c, b) -> isSingle(c, b) && b.button == button }?.let { return actionOf(it.first) }
        return bindings.shortcuts.firstOrNull { !it.binding.isCombo && it.binding.button == button }?.let { PadAction.Shortcut(it.function) }
    }

    /** Returns the flip completed by releasing [button], if any. */
    fun onButtonUp(button: PadButton): PadAction? {
        if (!held.remove(button)) return null
        if (button == bindings[PadCommand.FLIP].button) {
            pendingFlip = null // released before the D-pad: no flip
            return null
        }
        val direction = flipDirection(button) ?: return null
        val fire = pendingFlip == button && !flipCancelled && bindings[PadCommand.FLIP].button in held
        if (pendingFlip == button) pendingFlip = null
        if (held.none { flipDirection(it) != null }) flipCancelled = false
        return if (fire) PadAction.Flip(direction) else null
    }

    /** Controller disconnected / app paused: forget the held buttons (and a pending flip). */
    fun reset() {
        held.clear()
        pendingFlip = null
        flipCancelled = false
    }

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
            // 0f - v, not -v: a centred axis must be +0.0 (PadSticks equality tells -0.0 from 0.0).
            return PadSticks(axis(lx, f), 0f - axis(ly, f), axis(rx, f), 0f - axis(ry, f))
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
