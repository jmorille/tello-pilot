package com.drone.tello.control

import com.drone.tello.protocol.StickInput
import org.junit.jupiter.api.Assertions.assertEquals
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
}
