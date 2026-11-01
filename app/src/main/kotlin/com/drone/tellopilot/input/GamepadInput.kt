package com.drone.tellopilot.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
 * - events are only consumed while a listener is attached (the flight screen is resumed);
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

    private var listener: Listener? = null

    private val mapper = GamepadMapper()
    private val inputManager = context.getSystemService(InputManager::class.java)

    private val _status = MutableStateFlow(GamepadStatus.NONE)
    val status: StateFlow<GamepadStatus> = _status.asStateFlow()

    /** Per-device axis layout and centring state. */
    private class Pad(val rightX: Int, val rightY: Int, val flat: Float) {
        var armed = false
    }

    private val pads = HashMap<Int, Pad>()
    /** Device currently driving the sticks (-1 = none). */
    private var owner = -1
    private var lastSticks = PadSticks.Neutral
    private var l2Down = false
    private var r2Down = false
    private val hatState = BooleanArray(PadButton.entries.size)

    init {
        inputManager.registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = refresh()
                override fun onInputDeviceChanged(deviceId: Int) = refresh()
                override fun onInputDeviceRemoved(deviceId: Int) {
                    pads.remove(deviceId)
                    if (deviceId == owner) release()
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

    private fun refresh() {
        val present = InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).filter(::isGamepad).toList()
        present.forEach { pads.getOrPut(it.id) { layoutOf(it) } }
        pads.keys.retainAll(present.map { it.id }.toSet())
        _status.value = when {
            pads.isEmpty() -> GamepadStatus.NONE
            pads.values.any { it.armed } -> GamepadStatus.READY
            else -> GamepadStatus.CENTER_STICKS
        }
    }

    /** Neutral sticks, no held buttons (focus loss, disconnection, screen left). */
    fun release() {
        mapper.reset()
        l2Down = false
        r2Down = false
        hatState.fill(false)
        owner = -1
        if (lastSticks != PadSticks.Neutral) {
            lastSticks = PadSticks.Neutral
            listener?.onPadSticks(PadSticks.Neutral)
        }
    }

    /** Returns `true` if the event was consumed (only while the flight screen listens). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val l = listener ?: return false
        if (!event.isFromGamepad()) return false
        val button = buttonOf(event.keyCode) ?: return false
        if (pads[event.deviceId]?.armed == false) return true // not centred yet: swallow
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) mapper.onButtonDown(button)?.let(l::onPadAction)
            KeyEvent.ACTION_UP -> mapper.onButtonUp(button)
        }
        return true
    }

    /** Returns `true` if the event was consumed (only while the flight screen listens). */
    fun onMotionEvent(event: MotionEvent): Boolean {
        val l = listener ?: return false
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
            l.onPadSticks(sticks)
        }
        if (sticks == PadSticks.Neutral) owner = -1

        // Analog triggers (many pads report L2/R2 only as axes) and the D-pad as a hat.
        val l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        l2Down = trigger(l, PadButton.L2, l2, l2Down)
        r2Down = trigger(l, PadButton.R2, r2, r2Down)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        setHeld(l, PadButton.DPAD_LEFT, hx < -0.5f)
        setHeld(l, PadButton.DPAD_RIGHT, hx > 0.5f)
        setHeld(l, PadButton.DPAD_UP, hy < -0.5f)
        setHeld(l, PadButton.DPAD_DOWN, hy > 0.5f)
        return true
    }

    private fun setHeld(l: Listener, button: PadButton, down: Boolean) {
        if (hatState[button.ordinal] == down) return
        hatState[button.ordinal] = down
        if (down) mapper.onButtonDown(button)?.let(l::onPadAction) else mapper.onButtonUp(button)
    }

    private fun trigger(l: Listener, button: PadButton, value: Float, wasDown: Boolean): Boolean {
        val down = value > GamepadMapper.TRIGGER_THRESHOLD
        if (down && !wasDown) mapper.onButtonDown(button)?.let(l::onPadAction)
        if (!down && wasDown) mapper.onButtonUp(button)
        return down
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
    }
}
