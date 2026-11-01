package com.drone.tello.control

/** What the battery alert says (the app adds the tone, the voice and the vibration). */
sealed interface BatteryAnnouncement {
    val percent: Int

    /** Low threshold crossed, or a further [BatteryAnnouncer.REMINDER_STEP] % drop below it. */
    data class Low(override val percent: Int) : BatteryAnnouncement

    /** Critical level: land now. Repeated every [BatteryAnnouncer.CRITICAL_REPEAT_MS] while flying. */
    data class Critical(override val percent: Int) : BatteryAnnouncement
}

/** Battery state of one telemetry sample. */
data class BatteryReading(
    val percent: Int,
    /** The drone's own low battery flag (it uses the threshold the app sent it). */
    val lowFlag: Boolean = false,
    val criticalFlag: Boolean = false,
    /** Flying or motors running: announcements are only made then. */
    val airborne: Boolean,
)

/**
 * Decides when to announce a low / critical battery (pure, no Android, one instance per app
 * process so that its memory survives the reconnections).
 *
 * - Low: announced once when the level reaches the pilot's threshold (or the drone raises its
 *   low flag), then again at each new [REMINDER_STEP] % drop since the last announcement.
 * - Critical (drone flag or [criticalPercent] or less): announced at once, then every
 *   [CRITICAL_REPEAT_MS] while airborne.
 * - Nothing is said on the ground; a low battery found on the ground is announced at takeoff.
 * - Hysteresis: the alert state is only cleared once the level is back [HYSTERESIS] % above the
 *   threshold (new battery), so a value oscillating around it is announced once.
 * - Missing telemetry (link lost, reconnection) changes nothing: the same level seen again after
 *   a reconnection is not announced again.
 */
class BatteryAnnouncer(private val criticalPercent: Int = CRITICAL_PERCENT_DEFAULT) {

    /** Level at the last announcement of the current low phase, `null` = battery not low. */
    private var lastAnnouncedPercent: Int? = null
    private var criticalPhase = false
    private var lastCriticalAtMs: Long? = null

    /**
     * @param reading `null` when no telemetry is available (not connected).
     * @param nowMs monotonic clock (e.g. `SystemClock.elapsedRealtime()`).
     * @param lowPercent the pilot's low battery threshold.
     */
    fun update(reading: BatteryReading?, nowMs: Long, lowPercent: Int): BatteryAnnouncement? {
        if (reading == null || reading.percent !in 0..100) return null
        val percent = reading.percent
        val critical = reading.criticalFlag || percent <= criticalPercent
        val low = critical || reading.lowFlag || percent <= lowPercent

        // Recovery (battery swapped or charged): only well above the threshold (or above the
        // level announced on the drone's flag, if higher), flags down.
        if (!low && percent >= maxOf(lowPercent, lastAnnouncedPercent ?: 0) + HYSTERESIS) {
            lastAnnouncedPercent = null
            criticalPhase = false
            lastCriticalAtMs = null
            return null
        }
        if (criticalPhase && !critical && percent >= criticalPercent + HYSTERESIS) criticalPhase = false

        if (!reading.airborne) return null

        if (critical || criticalPhase) {
            criticalPhase = true
            val last = lastCriticalAtMs
            if (last != null && nowMs - last < CRITICAL_REPEAT_MS) return null
            lastCriticalAtMs = nowMs
            lastAnnouncedPercent = minOf(lastAnnouncedPercent ?: percent, percent)
            return BatteryAnnouncement.Critical(percent)
        }

        val last = lastAnnouncedPercent
        return when {
            last == null -> if (low) announceLow(percent) else null
            percent <= last - REMINDER_STEP -> announceLow(percent)
            else -> null
        }
    }

    private fun announceLow(percent: Int): BatteryAnnouncement {
        lastAnnouncedPercent = percent
        return BatteryAnnouncement.Low(percent)
    }

    companion object {
        const val CRITICAL_PERCENT_DEFAULT = 10
        const val REMINDER_STEP = 5
        const val HYSTERESIS = 3
        const val CRITICAL_REPEAT_MS = 30_000L
    }
}
