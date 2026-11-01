package com.drone.tellopilot.ui.flight

import com.drone.tello.control.MissionStep
import com.drone.tello.protocol.FlightData
import kotlin.math.abs

/*
 * Pure flight-safety decisions of [FlightViewModel] (no Android, no coroutine): stick
 * arbitration, autopilot takeover, gamepad takeoff/land combo and the automatic takeoff
 * height. Unit tested on the JVM.
 */

/** Stick deflection (touch or pad) that ends an automatic flight. */
internal const val AUTOPILOT_OVERRIDE = 0.25f

/** Touch and pad merged per axis: the largest deflection wins (touch on a tie). */
internal fun mergeAxis(touch: Float, pad: Float) = if (abs(touch) >= abs(pad)) touch else pad

/** A clear stick movement takes control back from an automatic flight / smart video. */
internal fun overridesAutopilot(pilotDeflection: Float) = pilotDeflection >= AUTOPILOT_OVERRIDE

/** Sticks quiet enough for the autopilot to keep flying (not the negation of [overridesAutopilot] for NaN). */
internal fun belowAutopilotOverride(pilotDeflection: Float) = pilotDeflection < AUTOPILOT_OVERRIDE

/** Manual commands that end an automatic flight first. */
internal fun FlightAction.endsAutopilot() = when (this) {
    FlightAction.Takeoff, FlightAction.Land, FlightAction.ThrowTakeoff, FlightAction.PalmLand,
    is FlightAction.Flip, is FlightAction.SetBounce, is FlightAction.StartSmartVideo,
    FlightAction.StopAutopilot, FlightAction.ReleaseSticks,
    is FlightAction.StartMission, is FlightAction.StartTracking,
    // Speed changes are the pilot's call: they end the (always slow) automatic flight.
    FlightAction.ToggleFastMode, FlightAction.ToggleTripod,
    -> true
    else -> false
}

/**
 * Gamepad takeoff/land combo: Land unless the drone is *certainly* on the ground. A landing in
 * progress ([landing]), motors on, unknown state ([flight] null) or a recent flying report
 * ([knownFlying]) all mean Land; Takeoff only from a stable on-ground state.
 */
internal fun padComboAction(flight: FlightData?, knownFlying: Boolean, landing: Boolean): FlightAction {
    val onGround = flight != null && !flight.flying && !flight.motorsOn && !knownFlying && !landing
    return if (onGround) FlightAction.Takeoff else FlightAction.Land
}

/**
 * Automatic climb / descent to the pilot's height right after the app's own takeoff (the Tello
 * always takes off to about 1 m).
 */
internal object TakeoffHeight {
    /** Wait for the takeoff to be over (stable for a moment) before adjusting. */
    const val SETTLE_MS = 2_000L
    const val TOLERANCE_M = 0.15f
    /** The "flying" edge must follow the takeoff command within this delay. */
    const val ARM_MS = 10_000L
    /** Any stick moved by the pilot after takeoff: no automatic adjustment. */
    const val STICK_IDLE = 0.05f

    /**
     * Whether a "flying" edge at [nowMs] arms the adjustment: the setting is on ([target] > 0)
     * and it follows the app's own takeoff command ([takeoffRequestedAtMs]) closely — never
     * after an app restart in flight, a throw takeoff or a bounce.
     */
    fun armed(target: Float, nowMs: Long, takeoffRequestedAtMs: Long): Boolean {
        if (target <= 0f) return false
        return nowMs - takeoffRequestedAtMs <= ARM_MS
    }

    /** A moved stick cancels a pending adjustment (the pilot flies). */
    fun cancelledBySticks(pilotDeflection: Float) = pilotDeflection > STICK_IDLE

    /**
     * The climb (negative: descent) to run once settled, or `null` when nothing must happen:
     * not flying, landing, low battery, link not connected, another automatic flight or
     * manoeuvre running, sticks moved, or already within [TOLERANCE_M] of [target].
     */
    fun step(
        target: Float,
        flight: FlightData,
        landingRequested: Boolean,
        connected: Boolean,
        autopilotActive: Boolean,
        smartVideoRunning: Boolean,
        bounce: Boolean,
        pilotDeflection: Float,
    ): MissionStep.Climb? {
        if (!flight.flying || flight.isLanding || landingRequested ||
            flight.batteryLow || flight.batteryCritical || !connected ||
            autopilotActive || smartVideoRunning || bounce || cancelledBySticks(pilotDeflection)
        ) {
            return null
        }
        val delta = target - flight.heightMeters
        if (abs(delta) < TOLERANCE_M) return null
        val step = MissionStep.Climb(delta.coerceIn(MissionStep.CLIMB_RANGE))
        return step.takeIf { it.isValid() }
    }
}
