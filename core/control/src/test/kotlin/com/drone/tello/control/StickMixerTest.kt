package com.drone.tello.control

import com.drone.tello.protocol.StickInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class StickMixerTest {
    private val linear = StickShaping(deadZone = 0f, expo = 0f)

    @Test
    fun `mode 2 maps left to yaw-throttle and right to roll-pitch`() {
        val mixer = StickMixer(StickMode.MODE_2, linear)
        mixer.setLeft(0.1f, 0.2f, fastMode = false)
        val input = mixer.setRight(0.3f, 0.4f, fastMode = true)
        assertEquals(StickInput(roll = 0.3f, pitch = 0.4f, throttle = 0.2f, yaw = 0.1f, fastMode = true), input)
    }

    @Test
    fun `mode 1 maps left to yaw-pitch and right to roll-throttle`() {
        val mixer = StickMixer(StickMode.MODE_1, linear)
        mixer.setLeft(0.1f, 0.2f, false)
        assertEquals(StickInput(roll = 0.3f, pitch = 0.2f, throttle = 0.4f, yaw = 0.1f), mixer.setRight(0.3f, 0.4f, false))
    }

    @Test
    fun `mode 3 and 4 swap the hands`() {
        val m3 = StickMixer(StickMode.MODE_3, linear).apply { setLeft(0.1f, 0.2f, false) }
        assertEquals(StickInput(roll = 0.1f, pitch = 0.2f, throttle = 0.4f, yaw = 0.3f), m3.setRight(0.3f, 0.4f, false))
        val m4 = StickMixer(StickMode.MODE_4, linear).apply { setLeft(0.1f, 0.2f, false) }
        assertEquals(StickInput(roll = 0.1f, pitch = 0.4f, throttle = 0.2f, yaw = 0.3f), m4.setRight(0.3f, 0.4f, false))
    }

    @ParameterizedTest
    @EnumSource(StickMode::class)
    fun `every mode uses each axis exactly once`(mode: StickMode) {
        val mixer = StickMixer(mode, linear)
        mixer.setLeft(0.1f, 0.2f, false)
        val i = mixer.setRight(0.3f, 0.4f, false)
        assertEquals(setOf(0.1f, 0.2f, 0.3f, 0.4f), setOf(i.roll, i.pitch, i.throttle, i.yaw))
    }

    @Test
    fun `reset centers everything`() {
        val mixer = StickMixer()
        mixer.setLeft(1f, 1f, false)
        mixer.setRight(-1f, -1f, false)
        mixer.reset()
        assertEquals(StickInput.Neutral, mixer.mix(false))
    }

    @Test
    fun `shaping is applied, dead zone removes drift`() {
        val mixer = StickMixer(StickMode.MODE_2, StickShaping(deadZone = 0.1f, expo = 0f))
        val input = mixer.setLeft(0.05f, 1f, false)
        assertEquals(0f, input.yaw)
        assertEquals(1f, input.throttle, 1e-6f)
    }

    @Test
    fun `tripod scale reduces every axis after shaping`() {
        val mixer = StickMixer(StickMode.MODE_2, StickShaping(deadZone = 0f, expo = 0f))
        mixer.scale = 0.3f
        mixer.setLeft(1f, -1f, false)
        val input = mixer.setRight(0.5f, 1f, false)
        assertEquals(0.3f, input.yaw, 1e-6f)
        assertEquals(-0.3f, input.throttle, 1e-6f)
        assertEquals(0.15f, input.roll, 1e-6f)
        assertEquals(0.3f, input.pitch, 1e-6f)
        mixer.scale = 0f // clamped: never a dead stick
        assertEquals(0.05f, mixer.mix(false).pitch, 1e-6f)
    }

    @ParameterizedTest
    @EnumSource(StickMode::class)
    fun `tripod scales every axis of every mode and keeps the fast flag`(mode: StickMode) {
        val shaping = StickShaping() // default dead zone + expo
        val full = StickMixer(mode, shaping).apply { setLeft(0.7f, -0.4f, true) }.setRight(-0.9f, 0.6f, true)
        val tripod = StickMixer(mode, shaping).apply { scale = 0.3f; setLeft(0.7f, -0.4f, true) }.setRight(-0.9f, 0.6f, true)
        assertEquals(full.roll * 0.3f, tripod.roll, 1e-6f)
        assertEquals(full.pitch * 0.3f, tripod.pitch, 1e-6f)
        assertEquals(full.throttle * 0.3f, tripod.throttle, 1e-6f)
        assertEquals(full.yaw * 0.3f, tripod.yaw, 1e-6f)
        assertEquals(true, tripod.fastMode)
        // The flag follows each call (fast mode toggled while the sticks are held).
        assertEquals(false, StickMixer(mode, shaping).apply { setLeft(1f, 1f, true) }.mix(false).fastMode)
    }

    @ParameterizedTest
    @EnumSource(StickMode::class)
    fun `output stays within -1 to 1 whatever the input`(mode: StickMode) {
        val mixer = StickMixer(mode, linear)
        val inputs = listOf(5f, -7f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1f, -1f)
        for (a in inputs) for (b in inputs) {
            mixer.setLeft(a, b, false)
            val i = mixer.setRight(b, a, false)
            listOf(i.roll, i.pitch, i.throttle, i.yaw).forEach {
                assertTrue(!it.isNaN() && it in -1f..1f, "$mode ($a, $b) -> $i")
            }
        }
    }

    @Test
    fun `one stick update keeps the other stick where it is`() {
        val mixer = StickMixer(StickMode.MODE_2, linear)
        mixer.setRight(0.5f, 0.5f, false)
        val input = mixer.setLeft(0f, 0.8f, false)
        assertEquals(0.5f, input.roll)
        assertEquals(0.5f, input.pitch)
        assertEquals(0.8f, input.throttle)
    }

    @Test
    fun `scale above one is clamped to full range`() {
        val mixer = StickMixer(StickMode.MODE_2, linear)
        mixer.scale = 3f
        assertEquals(1f, mixer.setLeft(0f, 1f, false).throttle, 1e-6f)
    }
}
