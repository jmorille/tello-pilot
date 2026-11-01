package com.drone.tello.control

import kotlin.math.abs
import kotlin.math.atan2

/**
 * On-screen stick gate, as in the official Tello app (touch sticks only, never the gamepad):
 *
 * - axis lock: a finger within [AXIS_LOCK_DEG] of a pure axis gives no cross-axis component
 *   (a straight forward push flies straight). The angle is taken from the raw finger position,
 *   before clamping, like the official app;
 * - square gate: each axis is clamped to -1..1 on its own, so a diagonal gives full deflection
 *   on both axes (not 71 % of each as with a round gate).
 *
 * Input: finger offset from the stick centre divided by the stick travel (may exceed ±1).
 */
object TouchStickGate {
    /** Official app: |angle| strictly below 10° of an axis. */
    const val AXIS_LOCK_DEG = 10.0

    fun apply(rawX: Float, rawY: Float): Pair<Float, Float> {
        if (!rawX.isFinite() || !rawY.isFinite()) return 0f to 0f
        var x = rawX
        var y = rawY
        if (x != 0f || y != 0f) {
            val angle = Math.toDegrees(atan2(abs(y), abs(x)).toDouble())
            if (angle > 90.0 - AXIS_LOCK_DEG) x = 0f else if (angle < AXIS_LOCK_DEG) y = 0f
        }
        return x.coerceIn(-1f, 1f) to y.coerceIn(-1f, 1f)
    }
}
