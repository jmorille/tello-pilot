package com.drone.tellopilot.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadBinding
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadCommand
import com.drone.tello.control.PadSticks
import com.drone.tello.protocol.FlipDirection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gamepad events as Android delivers them (KeyEvent / MotionEvent / InputDevice test doubles):
 * axis mapping, arming, buttons and combos, disconnection and several pads.
 */
class GamepadInputTest {

    private val devices = LinkedHashMap<Int, InputDevice>()
    private val deviceListener = slot<InputManager.InputDeviceListener>()
    private val inputManager: InputManager = mockk(relaxed = true) {
        every { registerInputDeviceListener(capture(deviceListener), any()) } returns Unit
    }

    init {
        mockkStatic(InputDevice::class)
        every { InputDevice.getDeviceIds() } answers { devices.keys.toIntArray() }
        every { InputDevice.getDevice(any()) } answers { devices[firstArg()] }
    }

    @After fun tearDown() = unmockkAll()

    private val context: Context = mockk { every { getSystemService(InputManager::class.java) } returns inputManager }

    private class Recorder : GamepadInput.Listener {
        val sticks = mutableListOf<PadSticks>()
        val actions = mutableListOf<PadAction>()
        override fun onPadSticks(sticks: PadSticks) { this.sticks += sticks }
        override fun onPadAction(action: PadAction) { actions += action }
        val last get() = sticks.last()
    }

    private val listener = Recorder()

    /** Xbox / PlayStation layout (dedicated triggers, right stick on Z/RZ) or a generic HID pad (RX/RY). */
    private fun connect(
        id: Int,
        name: String = "Xbox Wireless Controller",
        generic: Boolean = false,
        flat: Float = 0f,
        notify: Boolean = true,
    ): InputDevice {
        val axes = if (generic) {
            setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        } else {
            setOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER)
        }
        val device: InputDevice = mockk {
            every { this@mockk.id } returns id
            every { this@mockk.name } returns name
            every { isVirtual } returns false
            every { sources } returns (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            every { getMotionRange(any(), any()) } answers {
                if (firstArg<Int>() in axes) mockk<InputDevice.MotionRange> { every { this@mockk.flat } returns flat } else null
            }
        }
        devices[id] = device
        if (notify && deviceListener.isCaptured) deviceListener.captured.onInputDeviceAdded(id)
        return device
    }

    private fun disconnect(id: Int) {
        devices.remove(id)
        deviceListener.captured.onInputDeviceRemoved(id)
    }

    private fun motion(id: Int, vararg axes: Pair<Int, Float>): MotionEvent {
        val values = axes.toMap()
        return mockk {
            every { source } returns InputDevice.SOURCE_JOYSTICK
            every { action } returns MotionEvent.ACTION_MOVE
            every { deviceId } returns id
            every { device } answers { devices[id] }
            every { getAxisValue(any<Int>()) } answers { values[firstArg()] ?: 0f }
        }
    }

    private fun key(id: Int, code: Int, down: Boolean, repeat: Int = 0): KeyEvent = mockk {
        every { source } returns InputDevice.SOURCE_GAMEPAD
        every { keyCode } returns code
        every { action } returns if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        every { repeatCount } returns repeat
        every { deviceId } returns id
    }

    private fun GamepadInput.sticks(id: Int, lx: Float = 0f, ly: Float = 0f, rx: Float = 0f, ry: Float = 0f, generic: Boolean = false) =
        onMotionEvent(
            motion(
                id, MotionEvent.AXIS_X to lx, MotionEvent.AXIS_Y to ly,
                (if (generic) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z) to rx,
                (if (generic) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ) to ry,
            ),
        )

    private fun GamepadInput.press(id: Int, code: Int) {
        onKeyEvent(key(id, code, down = true)); onKeyEvent(key(id, code, down = false))
    }

    /** A connected, centred (armed) pad with the flight screen listening. */
    private fun ready(vararg ids: Int = intArrayOf(1)): GamepadInput {
        ids.forEach { connect(it) }
        return GamepadInput(context).apply {
            attach(listener)
            ids.forEach { sticks(it) }
        }
    }

    /** Same stick positions (a centred axis may be reported as -0.0). */
    private fun assertSticks(expected: PadSticks, actual: PadSticks) {
        val e = listOf(expected.leftX, expected.leftY, expected.rightX, expected.rightY)
        val a = listOf(actual.leftX, actual.leftY, actual.rightX, actual.rightY)
        assertTrue("expected $expected but was $actual", e.zip(a).all { (x, y) -> kotlin.math.abs(x - y) < 1e-5f })
    }

    // ------------------------------------------------------------- connection

    @Test
    fun `status follows the connected pads and their centring`() {
        val input = GamepadInput(context)
        assertEquals(GamepadStatus.NONE, input.status.value)
        connect(1, name = " 8BitDo Pro 2 Keyboard ")
        assertEquals(GamepadStatus.CENTER_STICKS, input.status.value)
        assertEquals("8BitDo Pro 2", input.padName.value)
        input.attach(listener)
        input.sticks(1)
        assertEquals(GamepadStatus.READY, input.status.value)
        disconnect(1)
        assertEquals(GamepadStatus.NONE, input.status.value)
        assertEquals(null, input.padName.value)
    }

    @Test
    fun `a pad is ignored until its sticks have been seen centred`() {
        connect(1)
        val input = GamepadInput(context)
        input.attach(listener)
        // Stick held at connection (or a trigger mapped as a stick): nothing flies.
        assertTrue(input.sticks(1, ly = -1f))
        assertTrue(input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R1, down = true))) // swallowed
        assertTrue(listener.sticks.isEmpty())
        assertTrue(listener.actions.isEmpty())
        assertEquals(GamepadStatus.CENTER_STICKS, input.status.value)
        input.sticks(1, lx = 0.1f) // below the arming threshold
        assertEquals(GamepadStatus.READY, input.status.value)
        assertTrue(listener.sticks.isEmpty())
        input.sticks(1, ly = -1f)
        assertSticks(PadSticks(0f, 1f, 0f, 0f), listener.last)
    }

    @Test
    fun `events are not consumed while nobody listens`() {
        connect(1)
        val input = GamepadInput(context)
        assertFalse(input.sticks(1, lx = 0.5f))
        assertFalse(input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_A, down = true)))
        input.attach(listener)
        // Not a joystick move, or not a pad button: left to the system.
        val touch = motion(1).also { every { it.source } returns InputDevice.SOURCE_TOUCHSCREEN }
        assertFalse(input.onMotionEvent(touch))
        assertFalse(input.onKeyEvent(key(1, KeyEvent.KEYCODE_VOLUME_UP, down = true)))
    }

    // ------------------------------------------------------------------- axes

    @Test
    fun `axes - signs (up is positive), flat zone removed and rescaled`() {
        val input = ready()
        input.sticks(1, lx = 0.5f, ly = -1f, rx = -1f, ry = 0.54f)
        val s = listener.last
        assertEquals((0.5f - 0.08f) / 0.92f, s.leftX, 1e-5f) // minimum flat: 0.08
        assertEquals(1f, s.leftY, 1e-6f)
        assertEquals(-1f, s.rightX, 1e-6f)
        assertEquals(-0.5f, s.rightY, 1e-5f)
        // Inside the flat zone: neutral.
        input.sticks(1, lx = 0.05f, ly = -0.07f, rx = 0.08f)
        assertSticks(PadSticks.Neutral, listener.last)
    }

    @Test
    fun `the device flat is used when it is larger`() {
        connect(1, flat = 0.2f)
        val input = GamepadInput(context).apply { attach(listener); sticks(1) }
        input.sticks(1, lx = 0.15f)
        assertTrue(listener.sticks.all { it.leftX == 0f && it.leftY == 0f && it.rightX == 0f && it.rightY == 0f })
        input.sticks(1, lx = 0.6f)
        assertEquals(0.5f, listener.last.leftX, 1e-5f)
    }

    @Test
    fun `right stick on RX-RY for generic pads whose Z-RZ are the triggers`() {
        connect(1, generic = true)
        val input = GamepadInput(context).apply { attach(listener); sticks(1, generic = true) }
        input.onMotionEvent(motion(1, MotionEvent.AXIS_RX to 1f, MotionEvent.AXIS_Z to 1f))
        assertSticks(PadSticks(0f, 0f, 1f, 0f), listener.last)
    }

    @Test
    fun `an unchanged position is not sent again`() {
        val input = ready()
        input.sticks(1, lx = 1f)
        input.sticks(1, lx = 1f)
        assertEquals(1, listener.sticks.size)
    }

    // --------------------------------------------------------- buttons, combos

    @Test
    fun `takeoff or land is the R2 + Y combo, in either order, once per press`() {
        val input = ready()
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true))
        assertTrue(listener.actions.isEmpty())
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = true))
        assertEquals(listOf<PadAction>(PadAction.TakeoffOrLand), listener.actions)
        // Auto-repeat and held buttons never fire again.
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = true, repeat = 1))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true, repeat = 3))
        assertEquals(1, listener.actions.size)
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = false))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true))
        assertEquals(2, listener.actions.size)
        // Y alone does nothing.
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = false))
        input.press(1, KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(2, listener.actions.size)
    }

    @Test
    fun `emergency landing works with an analog R2 trigger too`() {
        val input = ready()
        input.onMotionEvent(motion(1, MotionEvent.AXIS_RTRIGGER to 0.9f))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true))
        assertEquals(listOf<PadAction>(PadAction.TakeoffOrLand), listener.actions)
    }

    @Test
    fun `single buttons - photo, video, fast mode, stop smart video`() {
        val input = ready()
        input.press(1, KeyEvent.KEYCODE_BUTTON_R1)
        input.press(1, KeyEvent.KEYCODE_BUTTON_L1)
        input.press(1, KeyEvent.KEYCODE_BUTTON_X)
        input.press(1, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(
            listOf(PadAction.Photo, PadAction.ToggleRecording, PadAction.ToggleFastMode, PadAction.StopSmartVideo),
            listener.actions,
        )
    }

    @Test
    fun `flip - L2 held (analog) plus the D-pad hat, fired on the D-pad release`() {
        val input = ready()
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0.8f))
        // The trigger eases off but stays above the release travel: still held.
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0.4f, MotionEvent.AXIS_HAT_X to -1f))
        assertTrue("nothing on the press", listener.actions.isEmpty())
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0.4f))
        assertEquals(listOf<PadAction>(PadAction.Flip(FlipDirection.LEFT)), listener.actions)
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0.4f, MotionEvent.AXIS_HAT_Y to 1f))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0.4f))
        assertEquals(PadAction.Flip(FlipDirection.BACK), listener.actions.last())
        // Released: the D-pad alone does nothing.
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0.2f))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_HAT_Y to -1f))
        input.onMotionEvent(motion(1))
        assertEquals(2, listener.actions.size)
    }

    @Test
    fun `a button reported both as key and axis counts once`() {
        val input = ready()
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_L2, down = true))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_BRAKE to 1f))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_DPAD_UP, down = true))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_BRAKE to 1f, MotionEvent.AXIS_HAT_Y to -1f))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_DPAD_UP, down = false))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_BRAKE to 1f))
        assertEquals(listOf<PadAction>(PadAction.Flip(FlipDirection.FORWARD)), listener.actions)
    }

    @Test
    fun `new bindings apply and reset a half-pressed combo`() {
        val input = ready()
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = true))
        val swapped = (GamepadBindings.Default.assign(PadCommand.PHOTO, PadBinding(PadButton.A)) as com.drone.tello.control.BindingResult.Ok).bindings
        input.setBindings(swapped)
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true))
        assertTrue(listener.actions.isEmpty())
        input.press(1, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf<PadAction>(PadAction.Photo), listener.actions)
        // Same bindings again: a held combo button is kept.
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = false))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = false))
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = true))
        input.setBindings(swapped)
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true))
        assertEquals(PadAction.TakeoffOrLand, listener.actions.last())
    }

    @Test
    fun `a shortcut fires with an analog trigger as modifier, the button alone keeps its command`() {
        val input = ready()
        val rotate = com.drone.tello.control.PadFunction.SmartVideo(com.drone.tello.protocol.SmartVideoMode.ROTATE_360)
        val bindings = (
            GamepadBindings.Default.addShortcut(com.drone.tello.control.PadShortcut(PadBinding(PadButton.X, PadButton.L2), rotate))
                as com.drone.tello.control.BindingResult.Ok
            ).bindings
        input.setBindings(bindings)
        input.press(1, KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(listOf<PadAction>(PadAction.ToggleFastMode), listener.actions)
        // L2 reported as an axis only (many pads), then X.
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 1f))
        input.press(1, KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(PadAction.Shortcut(rotate), listener.actions.last())
        // Trigger released: X is the fast mode again.
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0f))
        input.press(1, KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(PadAction.ToggleFastMode, listener.actions.last())
        assertEquals(3, listener.actions.size)
    }

    @Test
    fun `focus loss with the modifier held - no shortcut from the stale modifier`() {
        val input = ready()
        val rotate = com.drone.tello.control.PadFunction.SmartVideo(com.drone.tello.protocol.SmartVideoMode.ROTATE_360)
        input.setBindings(
            (
                GamepadBindings.Default.addShortcut(com.drone.tello.control.PadShortcut(PadBinding(PadButton.A, PadButton.SELECT), rotate))
                    as com.drone.tello.control.BindingResult.Ok
                ).bindings,
        )
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_SELECT, down = true))
        input.release() // window focus lost: the key-up of SELECT will never come
        input.press(1, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `a modifier held when the pad drops (sticks centred) is forgotten`() {
        val input = ready()
        val flip = com.drone.tello.control.PadFunction.Flip(FlipDirection.FORWARD)
        input.setBindings(
            (
                GamepadBindings.Default.addShortcut(com.drone.tello.control.PadShortcut(PadBinding(PadButton.A, PadButton.L2), flip))
                    as com.drone.tello.control.BindingResult.Ok
                ).bindings,
        )
        // L2 held as a key; the pad drops before its key-up and comes back under a new id.
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_L2, down = true))
        disconnect(1)
        connect(2)
        input.sticks(2)
        input.press(2, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(listener.actions.isEmpty())
    }

    // ------------------------------------------------ release, disconnection

    @Test
    fun `disconnecting the pad in flight re-centres the sticks at once`() {
        val input = ready()
        input.sticks(1, ly = -1f, rx = 0.7f)
        disconnect(1)
        assertSticks(PadSticks.Neutral, listener.last)
        assertEquals(GamepadStatus.NONE, input.status.value)
    }

    @Test
    fun `detaching the flight screen re-centres and forgets held buttons`() {
        val input = ready()
        input.sticks(1, lx = 0.9f)
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_R2, down = true))
        input.attach(null)
        assertSticks(PadSticks.Neutral, listener.last)
        input.attach(listener)
        // R2 was forgotten (its key-up was lost): Y alone is not a takeoff.
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_Y, down = true))
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `a trigger held through a release must be released before it counts again`() {
        val input = ready()
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 1f))
        input.release()
        // Still held: the next motion event must not press it again (L2 + D-pad = flip).
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 1f, MotionEvent.AXIS_HAT_X to 1f))
        assertTrue(listener.actions.isEmpty())
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 0f))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 1f))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 1f, MotionEvent.AXIS_HAT_X to 1f))
        input.onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to 1f))
        assertEquals(listOf<PadAction>(PadAction.Flip(FlipDirection.RIGHT)), listener.actions)
    }

    // ------------------------------------------------- flip safety (incident 14 Nov. 2026)

    private fun GamepadInput.l2Hat(l2: Float = 1f, hx: Float = 0f, hy: Float = 0f, vararg more: Pair<Int, Float>) =
        onMotionEvent(motion(1, MotionEvent.AXIS_LTRIGGER to l2, MotionEvent.AXIS_HAT_X to hx, MotionEvent.AXIS_HAT_Y to hy, *more))

    @Test
    fun `flip safety - a bouncing hat around the threshold gives one flip, on the release`() {
        val input = ready()
        input.l2Hat()
        input.l2Hat(hy = -1f)
        input.l2Hat(hy = -0.45f) // contact bounce: above the 0.4 release, still held
        input.l2Hat(hy = -1f)
        input.l2Hat(hy = -0.55f) // below the 0.6 press: no new press
        assertTrue(listener.actions.isEmpty())
        input.l2Hat(hy = 0f)
        assertEquals(listOf<PadAction>(PadAction.Flip(FlipDirection.FORWARD)), listener.actions)
        assertTrue("the combo never moved a stick", listener.sticks.none { it != PadSticks.Neutral })
    }

    @Test
    fun `flip safety - a rolled press up then right gives no flip at all`() {
        val input = ready()
        input.l2Hat()
        input.l2Hat(hy = -1f)
        input.l2Hat(hx = 1f, hy = -1f)
        input.l2Hat(hx = 1f)
        input.l2Hat()
        assertTrue(listener.actions.isEmpty())
        // The next clean press works again.
        input.l2Hat(hy = -1f)
        input.l2Hat()
        assertEquals(listOf<PadAction>(PadAction.Flip(FlipDirection.FORWARD)), listener.actions)
    }

    @Test
    fun `flip safety - a diagonal hat in one event gives no flip`() {
        val input = ready()
        input.l2Hat()
        input.l2Hat(hx = 0.7f, hy = -0.7f)
        input.l2Hat()
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `flip safety - L2 released before the D-pad cancels the flip`() {
        val input = ready()
        input.l2Hat()
        input.l2Hat(hy = -1f)
        input.l2Hat(l2 = 0f, hy = -1f)
        input.l2Hat(l2 = 0f)
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `flip safety - L2 also reported on AXIS_Z (right stick X) never reaches a flight axis`() {
        // Pad declaring LTRIGGER (so the right stick is read on Z / RZ) whose L2 also moves Z.
        val input = ready()
        for (t in listOf(0.1f, 0.3f, 0.5f, 0.7f, 1f)) input.l2Hat(l2 = t, more = arrayOf(MotionEvent.AXIS_Z to t))
        input.l2Hat(l2 = 1f, hy = -1f, more = arrayOf(MotionEvent.AXIS_Z to 1f))
        input.l2Hat(l2 = 1f, more = arrayOf(MotionEvent.AXIS_Z to 1f))
        for (t in listOf(0.8f, 0.6f, 0.4f, 0.2f, 0f)) input.l2Hat(l2 = t, more = arrayOf(MotionEvent.AXIS_Z to t))
        assertEquals(listOf<PadAction>(PadAction.Flip(FlipDirection.FORWARD)), listener.actions)
        assertTrue("sticks sent: ${listener.sticks}", listener.sticks.none { it != PadSticks.Neutral })
        // Proven coupled: Z is now ignored even with L2 alone at full travel, the left stick still flies.
        input.l2Hat(l2 = 1f, more = arrayOf(MotionEvent.AXIS_Z to 1f))
        input.l2Hat(l2 = 0f)
        input.sticks(1, ly = -1f)
        assertSticks(PadSticks(0f, 1f, 0f, 0f), listener.last)
    }

    @Test
    fun `flip safety - R2 also reported on AXIS_RZ never moves the right stick Y`() {
        val input = ready()
        for (t in listOf(0.2f, 0.4f, 0.6f, 0.4f, 0f)) {
            input.onMotionEvent(motion(1, MotionEvent.AXIS_RTRIGGER to t, MotionEvent.AXIS_RZ to t))
        }
        assertTrue("sticks sent: ${listener.sticks}", listener.sticks.none { it != PadSticks.Neutral })
    }

    @Test
    fun `flip safety - a real right stick still flies while R2 is pressed`() {
        val input = ready()
        input.onMotionEvent(motion(1, MotionEvent.AXIS_RTRIGGER to 0.3f, MotionEvent.AXIS_Z to 0.8f))
        assertEquals((0.8f - 0.08f) / 0.92f, listener.last.rightX, 1e-5f)
    }

    @Test
    fun `flip safety - sticks fly nothing while L2 is held, then wait to be centred`() {
        val input = ready()
        input.sticks(1, rx = 1f) // thumb on the right stick
        assertEquals(1f, listener.last.rightX, 1e-6f)
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_L2, down = true))
        assertSticks(PadSticks.Neutral, listener.last)
        input.sticks(1, rx = 1f, ly = -1f)
        assertSticks(PadSticks.Neutral, listener.last)
        input.onKeyEvent(key(1, KeyEvent.KEYCODE_BUTTON_L2, down = false))
        input.sticks(1, rx = 1f) // still deflected after the combo: not re-armed
        assertSticks(PadSticks.Neutral, listener.last)
        input.sticks(1) // centred: re-armed
        input.sticks(1, rx = 0.5f)
        assertEquals((0.5f - 0.08f) / 0.92f, listener.last.rightX, 1e-5f)
    }

    @Test
    fun `release with neutral sticks sends nothing`() {
        val input = ready()
        input.release()
        assertTrue(listener.sticks.isEmpty())
    }

    // ------------------------------------------------------------ several pads

    @Test
    fun `one pad drives at a time - another is ignored while the owner is deflected`() {
        val input = ready(1, 2)
        input.sticks(1, lx = 1f)
        input.sticks(2, ly = -1f)
        assertSticks(PadSticks(1f, 0f, 0f, 0f), listener.last)
        assertEquals(1, listener.sticks.size)
    }

    @Test
    fun `disconnecting a pad that does not drive leaves the owner alone`() {
        val input = ready(1, 2)
        input.sticks(1, lx = 1f)
        disconnect(2)
        assertSticks(PadSticks(1f, 0f, 0f, 0f), listener.last)
        assertEquals(1, listener.sticks.size)
        assertEquals(GamepadStatus.READY, input.status.value)
    }

    @Test
    fun `a pad first seen in a motion event is adopted`() {
        val input = GamepadInput(context).apply { attach(listener) }
        connect(3, notify = false) // no device callback before the event
        input.sticks(3)
        input.sticks(3, rx = 1f)
        assertSticks(PadSticks(0f, 0f, 1f, 0f), listener.last)
    }

    // --------------------------------------------------------------- monitor

    @Test
    fun `the configuration screen sees sticks, buttons and actions without flying`() {
        connect(1)
        val input = GamepadInput(context)
        val seen = mutableListOf<String>()
        val monitor = object : GamepadInput.Monitor {
            override fun onPadSticks(sticks: PadSticks) { seen += "sticks ${sticks.leftX}" }
            override fun onPadButton(button: PadButton, down: Boolean, action: PadAction?) { seen += "$button $down $action" }
            override fun onPadReleased() { seen += "released" }
        }
        input.attachMonitor(monitor)
        assertTrue(input.isMonitoring(monitor))
        input.sticks(1)
        input.sticks(1, lx = 1f)
        input.press(1, KeyEvent.KEYCODE_BUTTON_R1)
        input.attachMonitor(null)
        assertEquals(
            listOf("sticks 1.0", "R1 true Photo", "R1 false null", "sticks 0.0", "released"),
            seen,
        )
        assertTrue(listener.actions.isEmpty())
    }

    // ------------------------------- centred = Neutral (was -0.0 on the Y axes)

    @Test
    fun `another pad takes over once the owner is back to neutral`() {
        val input = ready(1, 2)
        input.sticks(1, lx = 1f)
        input.sticks(1)
        assertEquals(PadSticks.Neutral, listener.last)
        input.sticks(2, ly = -1f)
        assertSticks(PadSticks(0f, 1f, 0f, 0f), listener.last)
    }

    @Test
    fun `a centred pad sends nothing`() {
        val input = ready()
        input.sticks(1, lx = 0.05f)
        assertTrue(listener.sticks.isEmpty())
    }
}
