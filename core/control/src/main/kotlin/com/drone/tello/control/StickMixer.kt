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
 * Combines the two pilot sticks (x right-positive, y up-positive, each in -1..1, touch and
 * gamepad alike) into a [StickInput] according to the [mode], applying to each axis the
 * [AxisTuning] of the flight mode given at each mix (normal or fast profile of [tuning]): a
 * fast mode toggle switches the profile on the very next mix.
 *
 * Only the pilot's sticks go through here: the automatic flights send their own commands to
 * the controller, untouched by this tuning.
 */
class StickMixer(
    var mode: StickMode = StickMode.MODE_2,
    var tuning: StickTuning = StickTuning(),
) {
    /** Output scale (0..1] after the axis tuning: < 1 for the tripod mode (slow, smooth shots). */
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

    fun mix(fastMode: Boolean): StickInput {
        val k = scale
        val t = tuning
        val profile = t.forMode(fastMode)
        val (roll, pitch, throttle, yaw) = when (mode) {
            StickMode.MODE_1 -> Raw(roll = rightX, pitch = leftY, throttle = rightY, yaw = leftX)
            StickMode.MODE_2 -> Raw(roll = rightX, pitch = rightY, throttle = leftY, yaw = leftX)
            StickMode.MODE_3 -> Raw(roll = leftX, pitch = leftY, throttle = rightY, yaw = rightX)
            StickMode.MODE_4 -> Raw(roll = leftX, pitch = rightY, throttle = leftY, yaw = rightX)
        }
        return StickInput(
            roll = profile.roll.apply(roll, t.deadZone) * k,
            pitch = profile.pitch.apply(pitch, t.deadZone) * k,
            throttle = profile.throttle.apply(throttle, t.deadZone) * k,
            yaw = profile.yaw.apply(yaw, t.deadZone) * k,
            fastMode = fastMode,
        )
    }
}

private data class Raw(val roll: Float, val pitch: Float, val throttle: Float, val yaw: Float)
