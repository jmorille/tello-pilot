package com.drone.tellopilot.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.GamepadMapper
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadCommand
import com.drone.tello.control.PadSticks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class GamepadStatus {
    NONE,
    /** Connected, but its sticks have not been seen centred yet: ignored until they are. */
    CENTER_STICKS,
    READY,
}

/**
 * Standard Android gamepads (Bluetooth or USB: Xbox, PlayStation, 8BitDo…), no vendor SDK.
 *
 * The activity forwards key / motion events here (main thread); sticks go straight to the
 * [Listener] without any flow in between, like the on-screen joysticks (lowest latency).
 *
 * Safety:
 * - events are only consumed while a listener is attached (the flight screen is resumed) or the
 *   configuration screen monitors them; only the listener reaches the drone;
 * - a pad is ignored until all its stick axes have been seen centred (a trigger mapped as a
 *   stick, or a stick held at connection, must not fly the drone);
 * - one pad owns the sticks; its disconnection, a window focus loss or detaching the listener
 *   re-centres them immediately.
 */
@Singleton
class GamepadInput @Inject constructor(@ApplicationContext context: Context) {

    interface Listener {
        fun onPadSticks(sticks: PadSticks)
        fun onPadAction(action: PadAction)
    }

    /**
     * Gamepad configuration screen: sees sticks, buttons and the resulting actions while the
     * flight screen is not listening (nothing reaches the drone from it).
     */
    interface Monitor {
        fun onPadSticks(sticks: PadSticks)
        /** [action]: what this press (or, for a flip, this D-pad release) triggers with the current layout. */
        fun onPadButton(button: PadButton, down: Boolean, action: PadAction?)
        /** Every button forgotten (focus loss, disconnection): no button is held any more. */
        fun onPadReleased()
    }

    private var listener: Listener? = null
    private var monitor: Monitor? = null

    private val mapper = GamepadMapper()
    private val inputManager = context.getSystemService(InputManager::class.java)

    private val _status = MutableStateFlow(GamepadStatus.NONE)
    val status: StateFlow<GamepadStatus> = _status.asStateFlow()

    private val _padName = MutableStateFlow<String?>(null)
    /** Name of the (first) connected pad, for display. */
    val padName: StateFlow<String?> = _padName.asStateFlow()

    /** Per-device axis layout and centring state. */
    private class Pad(val rightX: Int, val rightY: Int, val flat: Float) {
        var armed = false
        /**
         * Stick axes found to follow a trigger (see [detectCoupling]): read as centred from then
         * on. Value: the coupling is the centred form `2t - 1` (rests at -1), else direct (rests at 0).
         */
        val coupled = HashMap<Int, Boolean>()
        /** Per stick axis: consecutive events where it followed a trigger, that trigger value and the form. */
        val couplingHits = HashMap<Int, Int>()
        val couplingLast = HashMap<Int, Float>()
        val couplingForm = HashMap<Int, Boolean>()
        /**
         * Right-stick axes seen once equal to a trigger pressed part-way: until every trigger is
         * back at rest, a value equal to a pressed trigger (full travel included) is read as
         * centred, so a trigger pushed on to its stop never leaks before the proof.
         */
        val suspect = HashSet<Int>()
    }

    /**
     * The flip button was held: the pad sticks drive nothing until seen centred again (a
     * trigger read as a stick, or a thumb resting on a stick through the combo, never reaches
     * the drone when the button is released).
     */
    private var sticksNeedRecentre = false
    /**
     * Devices holding the flip button, per source (the merged button state hides a second pad
     * pressing it): only a pad that drives (or no pad driving) locks the sticks.
     */
    private val flipKeyHolders = HashSet<Int>()
    private val flipAxisHolders = HashSet<Int>()

    /** The flip button is held by the pad that drives the sticks, or by any pad when none drives. */
    private fun flipLocksSticks(): Boolean {
        val holders = flipKeyHolders + flipAxisHolders
        return holders.isNotEmpty() && (owner == -1 || owner in holders)
    }

    private val _coupledAxis = MutableStateFlow(false)
    /** A stick axis of a connected pad follows a trigger and is ignored (shown on the pad screen). */
    val coupledAxis: StateFlow<Boolean> = _coupledAxis.asStateFlow()

    private fun publishCoupling() {
        _coupledAxis.value = pads.values.any { it.coupled.isNotEmpty() }
    }

    private fun Pad.restOf(axis: Int) = if (coupled[axis] == true) -1f else 0f

    private val pads = HashMap<Int, Pad>()
    /** Device currently driving the sticks (-1 = none). */
    private var owner = -1
    private var lastSticks = PadSticks.Neutral

    // A button can be reported both as a key and as an axis (GameSir: BTN_TL2 and BRAKE, D-pad
    // keys and HAT): it is down while either source is down, and only edges reach the mapper.
    private val keyDown = BooleanArray(PadButton.entries.size)
    private val axisDown = BooleanArray(PadButton.entries.size)
    private val effective = BooleanArray(PadButton.entries.size)
    /** Held through a [release]: ignored until seen released (no action without a new press). */
    private val blocked = BooleanArray(PadButton.entries.size)

    init {
        inputManager.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = refresh()
                override fun onInputDeviceChanged(deviceId: Int) = refresh()
                override fun onInputDeviceRemoved(deviceId: Int) {
                    pads.remove(deviceId)
                    flipKeyHolders -= deviceId
                    flipAxisHolders -= deviceId
                    // A button held when a pad drops (its key-up never comes) must not stay
                    // "held" and complete a combo or a shortcut at the next press; the sticks of
                    // another pad that drives are left alone.
                    if (deviceId == owner) {
                        release()
                    } else {
                        releaseButtons()
                        monitor?.onPadReleased()
                    }
                    refresh()
                }
            },
            null, // main thread
        )
        refresh()
    }

    /** Attaches the flight screen (resumed) or detaches it (`null`): detaching re-centres. */
    fun attach(newListener: Listener?) {
        if (newListener == null) release()
        listener = newListener
    }

    fun isAttached(l: Listener) = listener === l

    /** Attaches / detaches the configuration screen; detaching re-centres. */
    fun attachMonitor(newMonitor: Monitor?) {
        if (newMonitor == null) release()
        monitor = newMonitor
    }

    fun isMonitoring(m: Monitor) = monitor === m

    /** Button assignment from the pilot settings (main thread). */
    fun setBindings(bindings: GamepadBindings) {
        if (mapper.bindings != bindings) {
            mapper.bindings = bindings
            mapper.reset() // a half-pressed combo must not complete with the new layout
            // The flip button may have changed: who holds it is recomputed from the next events.
            flipKeyHolders.clear()
            flipAxisHolders.clear()
        }
    }

    private fun refresh() {
        val present = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).filter(::isGamepad).toList()
        present.forEach { pads.getOrPut(it.id) { layoutOf(it) } }
        pads.keys.retainAll(present.map { it.id }.toSet())
        publishCoupling()
        _padName.value = present.firstOrNull()?.name?.trim()?.removeSuffix("Keyboard")?.trim()
        _status.value = when {
            pads.isEmpty() -> GamepadStatus.NONE
            pads.values.any { it.armed } -> GamepadStatus.READY
            else -> GamepadStatus.CENTER_STICKS
        }
    }

    /** Neutral sticks, no held buttons (focus loss, disconnection, screen left). */
    fun release() {
        releaseButtons()
        flipAxisHolders.clear() // a trigger still held is seen again in its next event
        owner = -1
        if (lastSticks != PadSticks.Neutral) {
            lastSticks = PadSticks.Neutral
            listener?.onPadSticks(PadSticks.Neutral)
            monitor?.onPadSticks(PadSticks.Neutral)
        }
        monitor?.onPadReleased()
    }

    /** No held button any more; a trigger still pressed must be released before it counts again. */
    private fun releaseButtons() {
        mapper.reset()
        // Key-ups are lost meanwhile; a trigger still held keeps being reported as such.
        flipKeyHolders.clear()
        // Key-ups are lost while the window has no focus: forget the keys. Axes (triggers, hat)
        // are reported continuously: one still held must be released before it counts again,
        // otherwise the next motion event would re-press it (e.g. L2 + D-pad = flip).
        keyDown.fill(false)
        for (i in effective.indices) {
            blocked[i] = axisDown[i]
            effective[i] = false
        }
    }

    /** Returns `true` if the event was consumed (only while the flight or configuration screen listens). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (listener == null && monitor == null) return false
        if (!event.isFromGamepad()) return false
        val button = buttonOf(event.keyCode) ?: return false
        if (pads[event.deviceId]?.armed == false) return true // not centred yet: swallow
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) update(button, key = true, down = true, device = event.deviceId)
            KeyEvent.ACTION_UP -> update(button, key = true, down = false, device = event.deviceId)
        }
        return true
    }

    /** Merges the key and axis sources of [button]; only a change of the combined state counts. */
    private fun update(button: PadButton, key: Boolean, down: Boolean, device: Int = -1) {
        val i = button.ordinal
        if (key) keyDown[i] = down else axisDown[i] = down
        // Key sources per pad here; trigger axes per pad in onMotionEvent (axisDown is shared
        // by the pads, so its edges cannot tell which pad let go).
        if (key && button == mapper.bindings[PadCommand.FLIP].button && device != -1) {
            if (down) flipKeyHolders += device else flipKeyHolders -= device
        }
        val now = keyDown[i] || axisDown[i]
        if (!now) blocked[i] = false
        if (blocked[i] || now == effective[i]) return
        effective[i] = now
        press(button, now, device)
    }

    private fun press(button: PadButton, down: Boolean, device: Int) {
        // Flip button pressed: sticks neutral at once, whatever is held (see onMotionEvent).
        if (down && button == mapper.bindings[PadCommand.FLIP].button && flipLocksSticks()) {
            sticksNeedRecentre = true
            sendSticks(PadSticks.Neutral)
        }
        // A flip fires on the D-pad release (see GamepadMapper), the other actions on a press.
        val action = if (down) mapper.onButtonDown(button) else mapper.onButtonUp(button)
        monitor?.onPadButton(button, down, action)
        action?.let { listener?.onPadAction(it) }
    }

    /** Returns `true` if the event was consumed (only while the flight or configuration screen listens). */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (listener == null && monitor == null) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) {
            return false
        }
        val pad = pads[event.deviceId] ?: event.device?.takeIf(::isGamepad)?.let { layoutOf(it).also { p -> pads[it.id] = p } }
            ?: return false
        detectCoupling(pad, event)
        // Until a coupling is proven, a suspect right-stick axis (seen once equal to a trigger
        // pressed part-way, see [Pad.suspect]) equal to a pressed trigger is read as centred:
        // the first presses of L2 never leak into a flight axis, even pushed on to the stop.
        // Nothing suspect: a stick at its stop with a trigger fully down is real.
        val pressed = TRIGGER_AXES.map(event::getAxisValue).filter { it >= TRIGGER_NOISE }
        fun stick(axis: Int): Float {
            if (axis in pad.coupled) return 0f
            val v = event.getAxisValue(axis)
            return if (axis in pad.suspect && pressed.any { followsTrigger(v, it) }) 0f else v
        }
        val lx = stick(MotionEvent.AXIS_X)
        val ly = stick(MotionEvent.AXIS_Y)
        val rx = stick(pad.rightX)
        val ry = stick(pad.rightY)
        if (!pad.armed) {
            if (maxOf(abs(lx), abs(ly), abs(rx), abs(ry)) < ARM_THRESHOLD) {
                pad.armed = true
                _status.value = GamepadStatus.READY
            }
            return true
        }
        // Buttons first (analog triggers, many pads report L2/R2 only as axes, and the D-pad as
        // a hat): an event that presses the flip button must not carry its sticks to the drone.
        // They are buttons only: no trigger or hat axis is ever read as a stick on purpose.
        val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        trigger(PadButton.L2, l2, event.deviceId)
        trigger(PadButton.R2, r2, event.deviceId)
        // Who holds the flip button by its trigger axis: from this pad's own value, same hysteresis.
        when (mapper.bindings[PadCommand.FLIP].button) {
            PadButton.L2 -> flipAxisHolder(event.deviceId, l2)
            PadButton.R2 -> flipAxisHolder(event.deviceId, r2)
            else -> flipAxisHolders.clear()
        }
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        // Releases first: a rolled press (up → up+right → right) is seen in order by the mapper.
        val hat = listOf(PadButton.DPAD_LEFT to -hx, PadButton.DPAD_RIGHT to hx, PadButton.DPAD_UP to -hy, PadButton.DPAD_DOWN to hy)
        hat.forEach { (button, v) -> if (axisDown[button.ordinal] && !hatDown(true, v)) update(button, key = false, down = false, device = event.deviceId) }
        hat.forEach { (button, v) -> if (!axisDown[button.ordinal] && hatDown(false, v)) update(button, key = false, down = true, device = event.deviceId) }

        var sticks = GamepadMapper.sticks(lx, ly, rx, ry, pad.flat)
        // Flip button held (the combo, or a shortcut modifier): the sticks fly nothing, and
        // once it is released they wait to be seen centred, like a pad at connection.
        // The flip button of a pad that does not drive locks nothing.
        if (flipLocksSticks()) {
            sticksNeedRecentre = true
            sticks = PadSticks.Neutral
        } else if (sticksNeedRecentre) {
            if (maxOf(abs(lx), abs(ly), abs(rx), abs(ry)) < ARM_THRESHOLD) sticksNeedRecentre = false else sticks = PadSticks.Neutral
        }
        // One pad drives at a time: another pad only takes over once the owner is back to neutral.
        if (owner != -1 && owner != event.deviceId && lastSticks != PadSticks.Neutral) return true
        if (sticks != PadSticks.Neutral) owner = event.deviceId
        sendSticks(sticks)
        if (sticks == PadSticks.Neutral) owner = -1
        return true
    }

    private fun sendSticks(sticks: PadSticks) {
        if (sticks == lastSticks) return
        lastSticks = sticks
        listener?.onPadSticks(sticks)
        monitor?.onPadSticks(sticks)
    }

    /**
     * A stick axis that follows a trigger (some HID / DInput pads report L2 / R2 both on
     * LTRIGGER / BRAKE and on Z / RZ, which is also where the right stick is expected): seen
     * equal to a trigger value, or to its centred form `2t - 1`, on [COUPLING_EVENTS]
     * consecutive events with clearly different, intermediate trigger values, within one press
     * (the evidence is dropped as soon as the trigger leaves that range, or a reading repeats).
     * It is then read as centred for this pad. A pilot cannot track a moving trigger that
     * precisely by chance; and an ignored axis comes back as soon as it moves clearly with
     * every trigger at rest (it is a real stick after all).
     */
    private fun detectCoupling(pad: Pad, event: MotionEvent) {
        val axes = listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, pad.rightX, pad.rightY)
        val all = TRIGGER_AXES.map(event::getAxisValue)
        if (all.all { it < TRIGGER_NOISE }) {
            pad.suspect.clear()
            // Triggers at rest: a coupled axis that moves away from its rest (0, or -1 for the
            // centred form) on its own is a real stick.
            val released = axes.filter { it in pad.coupled && abs(event.getAxisValue(it) - pad.restOf(it)) > UNCOUPLE_TRAVEL }
            if (released.isNotEmpty()) {
                released.forEach { pad.coupled.remove(it) }
                android.util.Log.i("GamepadInput", "axes $released move on their own: sticks again")
                publishCoupling()
            }
        }
        val triggers = all.filter { it in COUPLING_RANGE }
        if (triggers.isEmpty()) {
            pad.couplingHits.clear()
            pad.couplingLast.clear()
            pad.couplingForm.clear()
            return
        }
        for (axis in axes) {
            if (axis in pad.coupled) continue
            val v = event.getAxisValue(axis)
            val t = triggers.firstOrNull { followsTrigger(v, it) }
            if (t == null) {
                pad.couplingHits.remove(axis)
                pad.couplingLast.remove(axis)
                pad.couplingForm.remove(axis)
                continue
            }
            val centred = abs(v - t) >= COUPLING_TOLERANCE // matched only by 2t - 1
            // A centred stick matches 2t - 1 whenever a trigger is half pressed: only an axis
            // away from its centre is suspect.
            if ((axis == pad.rightX || axis == pad.rightY) && (!centred || abs(v) > SUSPECT_MIN)) pad.suspect += axis
            val last = pad.couplingLast[axis]
            if ((last != null && abs(t - last) < COUPLING_STEP) || pad.couplingForm[axis].let { it != null && it != centred }) {
                // The same reading again (another axis moved), or the other form: start over from it.
                pad.couplingHits[axis] = 1
                pad.couplingLast[axis] = t
                pad.couplingForm[axis] = centred
                continue
            }
            pad.couplingLast[axis] = t
            pad.couplingForm[axis] = centred
            val hits = (pad.couplingHits[axis] ?: 0) + 1
            pad.couplingHits[axis] = hits
            if (hits >= COUPLING_EVENTS) {
                pad.coupled[axis] = centred
                pad.couplingHits.remove(axis)
                pad.couplingLast.remove(axis)
                pad.couplingForm.remove(axis)
                android.util.Log.w("GamepadInput", "axis $axis follows a trigger (centred form: $centred): ignored as a stick")
                publishCoupling()
            }
        }
    }

    /** [v] equals trigger value [t] directly, or in its centred form `2t - 1`. */
    private fun followsTrigger(v: Float, t: Float) =
        abs(v - t) < COUPLING_TOLERANCE || abs(v - (2 * t - 1)) < COUPLING_TOLERANCE

    private fun flipAxisHolder(device: Int, value: Float) {
        when {
            value > GamepadMapper.TRIGGER_THRESHOLD -> flipAxisHolders += device
            !(value > TRIGGER_RELEASE) -> flipAxisHolders -= device
        }
    }

    /** Hysteresis on a hat direction ([v] > 0 towards it): a worn or bouncing D-pad never double-presses. */
    private fun hatDown(wasDown: Boolean, v: Float) = if (wasDown) v > HAT_RELEASE else v > HAT_PRESS

    /** Hysteresis: a trigger resting near the threshold must not press / release repeatedly. */
    private fun trigger(button: PadButton, value: Float, device: Int) {
        val wasDown = axisDown[button.ordinal]
        val down = if (wasDown) value > TRIGGER_RELEASE else value > GamepadMapper.TRIGGER_THRESHOLD
        if (down != wasDown) update(button, key = false, down = down, device = device)
    }

    private fun isGamepad(d: InputDevice) =
        !d.isVirtual && d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD

    /**
     * Right stick axes: Z/RZ on Xbox / PlayStation layouts (which report the triggers on
     * LTRIGGER/BRAKE), RX/RY on generic HID pads whose Z/RZ are the triggers.
     */
    private fun layoutOf(d: InputDevice): Pad {
        fun has(axis: Int) = d.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
        val dedicatedTriggers = has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE)
        val (x, y) = if (!dedicatedTriggers && has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)) {
            MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        } else {
            MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        }
        val flat = maxOf(
            d.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK)?.flat ?: 0f,
            d.getMotionRange(x, InputDevice.SOURCE_JOYSTICK)?.flat ?: 0f,
            MIN_FLAT,
        )
        return Pad(x, y, flat)
    }

    private fun KeyEvent.isFromGamepad() =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun buttonOf(keyCode: Int): PadButton? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> PadButton.A
        KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
        KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
        KeyEvent.KEYCODE_DPAD_UP -> PadButton.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.DPAD_RIGHT
        KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
        else -> null
    }

    private companion object {
        /** Worn sticks often report a flat of 0 while drifting a few percent. */
        const val MIN_FLAT = 0.08f
        /** All stick axes must be seen below this once before the pad is used. */
        const val ARM_THRESHOLD = 0.2f
        /** A pressed trigger counts as released below this travel (press: TRIGGER_THRESHOLD). */
        const val TRIGGER_RELEASE = 0.3f
        /** A hat direction counts as pressed beyond this… */
        const val HAT_PRESS = 0.6f
        /** …and as released only below this (no chatter around one threshold). */
        const val HAT_RELEASE = 0.4f
        val TRIGGER_AXES = listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        /** Only intermediate trigger travel is evidence (rest and full travel are too common). */
        val COUPLING_RANGE = 0.15f..0.85f
        const val COUPLING_TOLERANCE = 0.02f
        /** Two pieces of evidence must differ by this much trigger travel. */
        const val COUPLING_STEP = 0.05f
        const val COUPLING_EVENTS = 3
        /** Trigger travel below this is rest noise. */
        const val TRIGGER_NOISE = 0.05f
        /** A coupled axis moving this far from its rest with every trigger at rest is a real stick again. */
        const val UNCOUPLE_TRAVEL = 0.3f
        /** A 2t - 1 match only makes an axis suspect beyond this deflection. */
        const val SUSPECT_MIN = 0.1f
    }
}
