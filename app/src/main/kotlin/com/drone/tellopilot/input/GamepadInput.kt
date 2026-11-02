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
        /** [action]: what this press triggers with the current layout (only on a press). */
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
    }

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
        }
    }

    private fun refresh() {
        val present = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).filter(::isGamepad).toList()
        present.forEach { pads.getOrPut(it.id) { layoutOf(it) } }
        pads.keys.retainAll(present.map { it.id }.toSet())
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
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) update(button, key = true, down = true)
            KeyEvent.ACTION_UP -> update(button, key = true, down = false)
        }
        return true
    }

    /** Merges the key and axis sources of [button]; only a change of the combined state counts. */
    private fun update(button: PadButton, key: Boolean, down: Boolean) {
        val i = button.ordinal
        if (key) keyDown[i] = down else axisDown[i] = down
        val now = keyDown[i] || axisDown[i]
        if (!now) blocked[i] = false
        if (blocked[i] || now == effective[i]) return
        effective[i] = now
        press(button, now)
    }

    private fun press(button: PadButton, down: Boolean) {
        val action = if (down) mapper.onButtonDown(button) else null.also { mapper.onButtonUp(button) }
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
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(pad.rightX)
        val ry = event.getAxisValue(pad.rightY)
        if (!pad.armed) {
            if (maxOf(abs(lx), abs(ly), abs(rx), abs(ry)) < ARM_THRESHOLD) {
                pad.armed = true
                _status.value = GamepadStatus.READY
            }
            return true
        }
        val sticks = GamepadMapper.sticks(lx, ly, rx, ry, pad.flat)
        // One pad drives at a time: another pad only takes over once the owner is back to neutral.
        if (owner != -1 && owner != event.deviceId && lastSticks != PadSticks.Neutral) return true
        if (sticks != PadSticks.Neutral) owner = event.deviceId
        if (sticks != lastSticks) {
            lastSticks = sticks
            listener?.onPadSticks(sticks)
            monitor?.onPadSticks(sticks)
        }
        if (sticks == PadSticks.Neutral) owner = -1

        // Analog triggers (many pads report L2/R2 only as axes) and the D-pad as a hat.
        val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        trigger(PadButton.L2, l2)
        trigger(PadButton.R2, r2)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        update(PadButton.DPAD_LEFT, key = false, down = hx < -0.5f)
        update(PadButton.DPAD_RIGHT, key = false, down = hx > 0.5f)
        update(PadButton.DPAD_UP, key = false, down = hy < -0.5f)
        update(PadButton.DPAD_DOWN, key = false, down = hy > 0.5f)
        return true
    }

    /** Hysteresis: a trigger resting near the threshold must not press / release repeatedly. */
    private fun trigger(button: PadButton, value: Float) {
        val wasDown = axisDown[button.ordinal]
        val down = if (wasDown) value > TRIGGER_RELEASE else value > GamepadMapper.TRIGGER_THRESHOLD
        if (down != wasDown) update(button, key = false, down = down)
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
    }
}
