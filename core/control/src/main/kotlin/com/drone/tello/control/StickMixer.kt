package com.drone.tello.control

import com.drone.tello.protocol.StickInput

/** Radio-control stick layouts. */
enum class StickMode {
    /** Left: yaw / pitch — Right: roll / throttle. */
    MODE_1,
    /** Left: yaw / throttle — Right: roll / pitch (default, like the official app). */
    MODE_2,
    /** Left: roll / pitch — Right: yaw / throttle. */
    MODE_3,
    /** Left: roll / throttle — Right: yaw / pitch. */
    MODE_4,
}

/**
 * Combines the two on-screen sticks (x right-positive, y up-positive, each in -1..1)
 * into a [StickInput] according to the [mode], applying [shaping] to every axis.
 */
class StickMixer(
    var mode: StickMode = StickMode.MODE_2,
    var shaping: StickShaping = StickShaping(),
) {
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f

    fun setLeft(x: Float, y: Float, fastMode: Boolean): StickInput {
        leftX = x; leftY = y
        return mix(fastMode)
    }

    fun setRight(x: Float, y: Float, fastMode: Boolean): StickInput {
        rightX = x; rightY = y
        return mix(fastMode)
    }

    fun reset() {
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
    }

    fun mix(fastMode: Boolean): StickInput {
        val s = shaping::apply
        return when (mode) {
            StickMode.MODE_1 -> StickInput(roll = s(rightX), pitch = s(leftY), throttle = s(rightY), yaw = s(leftX), fastMode = fastMode)
            StickMode.MODE_2 -> StickInput(roll = s(rightX), pitch = s(rightY), throttle = s(leftY), yaw = s(leftX), fastMode = fastMode)
            StickMode.MODE_3 -> StickInput(roll = s(leftX), pitch = s(leftY), throttle = s(rightY), yaw = s(rightX), fastMode = fastMode)
            StickMode.MODE_4 -> StickInput(roll = s(leftX), pitch = s(rightY), throttle = s(leftY), yaw = s(rightX), fastMode = fastMode)
        }
    }
}
