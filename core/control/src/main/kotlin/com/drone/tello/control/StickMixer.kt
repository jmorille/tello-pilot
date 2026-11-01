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
    /** Output scale (0..1] after shaping: < 1 for the tripod mode (slow, smooth shots). */
    var scale: Float = 1f
        set(value) {
            field = value.coerceIn(0.05f, 1f)
        }

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

    /**
     * Axis lock (as in the official app): a stick pushed within [AXIS_LOCK_DEG] of a pure axis
     * gives no cross-axis component, so a straight forward push flies straight.
     */
    var axisLock: Boolean = true

    fun mix(fastMode: Boolean): StickInput {
        val k = scale
        val s = { v: Float -> shaping.apply(v) * k }
        val (leftX, leftY) = lock(leftX, leftY)
        val (rightX, rightY) = lock(rightX, rightY)
        return when (mode) {
            StickMode.MODE_1 -> StickInput(roll = s(rightX), pitch = s(leftY), throttle = s(rightY), yaw = s(leftX), fastMode = fastMode)
            StickMode.MODE_2 -> StickInput(roll = s(rightX), pitch = s(rightY), throttle = s(leftY), yaw = s(leftX), fastMode = fastMode)
            StickMode.MODE_3 -> StickInput(roll = s(leftX), pitch = s(leftY), throttle = s(rightY), yaw = s(rightX), fastMode = fastMode)
            StickMode.MODE_4 -> StickInput(roll = s(leftX), pitch = s(rightY), throttle = s(leftY), yaw = s(rightX), fastMode = fastMode)
        }
    }

    private fun lock(x: Float, y: Float): Pair<Float, Float> {
        if (!axisLock || (x == 0f && y == 0f) || x.isNaN() || y.isNaN()) return x to y
        val angle = Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(y), kotlin.math.abs(x)).toDouble())
        return when {
            angle >= 90 - AXIS_LOCK_DEG -> 0f to y
            angle <= AXIS_LOCK_DEG -> x to 0f
            else -> x to y
        }
    }

    companion object {
        const val AXIS_LOCK_DEG = 10.0
    }
}
