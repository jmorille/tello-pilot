package com.drone.tello.control

import kotlin.math.abs
import kotlin.math.sign

/**
 * Converts raw touch deflection into a stick command.
 *
 * - dead zone: small deflections around the centre give an exact 0 (no drift in hover);
 * - expo: finer control near the centre, full range kept at the edges.
 */
data class StickShaping(val deadZone: Float = 0.05f, val expo: Float = 0.3f) {
    init {
        require(deadZone in 0f..0.5f) { "deadZone must be in 0..0.5" }
        require(expo in 0f..1f) { "expo must be in 0..1" }
    }

    fun apply(raw: Float): Float {
        if (raw.isNaN()) return 0f
        val v = raw.coerceIn(-1f, 1f)
        val magnitude = abs(v)
        if (magnitude <= deadZone) return 0f
        val scaled = (magnitude - deadZone) / (1f - deadZone)
        val curved = (1f - expo) * scaled + expo * scaled * scaled * scaled
        return sign(v) * curved
    }
}
