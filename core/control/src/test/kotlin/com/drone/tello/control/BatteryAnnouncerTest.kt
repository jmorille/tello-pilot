package com.drone.tello.control

import com.drone.tello.control.BatteryAnnouncement.Critical
import com.drone.tello.control.BatteryAnnouncement.Low
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BatteryAnnouncerTest {

    private val announcer = BatteryAnnouncer()
    private var now = 0L

    /** One sample, 1 s after the previous one; threshold 20 % unless given. */
    private fun feed(
        percent: Int,
        airborne: Boolean = true,
        lowFlag: Boolean = false,
        criticalFlag: Boolean = false,
        threshold: Int = 20,
        stepMs: Long = 1_000,
    ): BatteryAnnouncement? {
        now += stepMs
        return announcer.update(BatteryReading(percent, lowFlag, criticalFlag, airborne), now, threshold)
    }

    /** Announcements made over a series of levels. */
    private fun run(vararg levels: Int): List<BatteryAnnouncement> = levels.toList().mapNotNull { feed(it) }

    @Test
    fun `nothing above the threshold`() {
        assertEquals(emptyList<BatteryAnnouncement>(), run(80, 50, 30, 21))
    }

    @Test
    fun `crossing the threshold is announced once with the current level`() {
        assertEquals(listOf(Low(20)), run(25, 21, 20, 20, 19, 18))
    }

    @Test
    fun `a level jumping over the threshold is announced at the level seen`() {
        assertEquals(listOf(Low(18)), run(22, 18, 17))
    }

    @Test
    fun `a reminder at each new 5 percent drop`() {
        assertEquals(listOf(Low(20), Low(15)), run(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11))
    }

    @Test
    fun `reminders follow the last announced level, not the threshold`() {
        // Announced at 18 (first sample under 20): next reminder at 13.
        assertEquals(listOf(Low(18), Low(13)), run(21, 18, 15, 14, 13, 12))
    }

    @Test
    fun `a level oscillating around the threshold is announced once`() {
        assertEquals(listOf(Low(20)), run(21, 20, 21, 20, 22, 20, 21, 20, 22, 19))
    }

    @Test
    fun `a value oscillating around a reminder level is announced once`() {
        assertEquals(listOf(Low(20), Low(15)), run(20, 16, 15, 16, 15, 16, 15, 14))
    }

    @Test
    fun `the drone low flag announces even above the pilot threshold`() {
        assertEquals(Low(23), feed(23, lowFlag = true))
        assertNull(feed(23, lowFlag = false))
        assertNull(feed(23, lowFlag = true))
        assertEquals(Low(18), feed(18))
    }

    @Test
    fun `a new battery clears the state only well above the threshold`() {
        assertEquals(listOf(Low(20)), run(20))
        assertNull(feed(22)) // inside the hysteresis band: still low
        assertNull(feed(20))
        assertNull(feed(23)) // threshold + 3: cleared
        assertEquals(Low(20), feed(20))
    }

    @Test
    fun `fresh battery after landing is a new cycle`() {
        assertEquals(listOf(Low(20), Low(15)), run(20, 15))
        assertNull(feed(100, airborne = false))
        assertEquals(listOf(Low(20)), run(60, 40, 20))
    }

    @Test
    fun `nothing is said on the ground`() {
        assertNull(feed(18, airborne = false))
        assertNull(feed(8, airborne = false))
        assertNull(feed(5, airborne = false, criticalFlag = true))
    }

    @Test
    fun `a low battery found on the ground is announced at takeoff`() {
        assertNull(feed(18, airborne = false))
        assertEquals(Low(18), feed(18))
        assertNull(feed(18))
    }

    @Test
    fun `landing and taking off again at the same level is not announced again`() {
        assertEquals(Low(19), feed(19))
        assertNull(feed(19, airborne = false))
        assertNull(feed(18, airborne = false))
        assertNull(feed(18))
        assertEquals(Low(14), feed(14))
    }

    @Test
    fun `a drop that happened on the ground is announced at takeoff`() {
        assertEquals(Low(20), feed(20))
        assertNull(feed(14, airborne = false))
        assertEquals(Low(14), feed(14))
    }

    @Test
    fun `missing telemetry changes nothing and reconnection at the same level is silent`() {
        assertEquals(Low(19), feed(19))
        now += 5_000
        assertNull(announcer.update(null, now, 20))
        assertNull(announcer.update(null, now + 1_000, 20))
        assertNull(feed(19))
        assertNull(feed(17))
    }

    @Test
    fun `reconnection after a 5 percent drop gives the reminder`() {
        assertEquals(Low(19), feed(19))
        assertNull(announcer.update(null, now + 1_000, 20))
        assertEquals(Low(14), feed(14, stepMs = 10_000))
    }

    @Test
    fun `out of range levels are ignored`() {
        assertNull(feed(-1))
        assertNull(feed(255))
        assertEquals(Low(20), feed(20))
    }

    @Test
    fun `critical at 10 percent or less, repeated every 30 s while flying`() {
        assertEquals(listOf(Low(20), Low(15), Critical(10)), run(20, 15, 10))
        // Reminders every 30 s, whatever the level does.
        assertNull(feed(10, stepMs = 10_000))
        assertNull(feed(9, stepMs = 10_000))
        assertNull(feed(9, stepMs = 9_000))
        assertEquals(Critical(9), feed(9, stepMs = 1_000))
        assertNull(feed(5, stepMs = 29_000))
        assertEquals(Critical(5), feed(5, stepMs = 1_000))
    }

    @Test
    fun `the critical flag is critical whatever the level`() {
        assertEquals(Critical(14), feed(14, criticalFlag = true))
        assertNull(feed(14, criticalFlag = true))
        assertEquals(Critical(13), feed(13, criticalFlag = true, stepMs = 30_000))
    }

    @Test
    fun `a critical level reached directly skips the low announcement`() {
        assertEquals(listOf(Critical(9)), run(30, 9, 9, 8))
    }

    @Test
    fun `a flickering critical flag does not repeat before 30 s`() {
        assertEquals(Critical(16), feed(16, criticalFlag = true))
        assertNull(feed(16))
        assertNull(feed(16, criticalFlag = true))
        assertNull(feed(16))
        assertNull(feed(16, criticalFlag = true))
    }

    @Test
    fun `critical stays critical in the hysteresis band and keeps reminding`() {
        assertEquals(Critical(10), feed(10))
        assertNull(feed(11))
        assertNull(feed(12))
        assertEquals(Critical(12), feed(12, stepMs = 30_000))
    }

    @Test
    fun `leaving the critical band back to low does not announce low again`() {
        assertEquals(Critical(10), feed(10))
        assertNull(feed(13)) // critical cleared (10 + 3), still low
        assertNull(feed(13, stepMs = 30_000))
        assertNull(feed(11)) // neither critical nor 5 below the last announcement
        assertEquals(Critical(10), feed(10))
    }

    @Test
    fun `a flickering drone low flag above the threshold is announced once`() {
        assertEquals(Low(24), feed(24, lowFlag = true))
        assertNull(feed(25))
        assertNull(feed(24, lowFlag = true))
        assertNull(feed(26))
        assertNull(feed(24, lowFlag = true))
        assertNull(feed(27)) // 24 + 3: cleared
        assertEquals(Low(24), feed(24, lowFlag = true))
    }

    @Test
    fun `no critical reminder on the ground, one at takeoff`() {
        assertEquals(Critical(9), feed(9))
        assertNull(feed(9, airborne = false, stepMs = 60_000))
        assertNull(feed(8, airborne = false, stepMs = 60_000))
        assertEquals(Critical(8), feed(8))
    }

    @Test
    fun `critical reminders keep their pace through a reconnection`() {
        assertEquals(Critical(9), feed(9))
        assertNull(announcer.update(null, now + 5_000, 20))
        assertNull(feed(9, stepMs = 10_000))
        assertEquals(Critical(9), feed(9, stepMs = 20_000))
    }

    @Test
    fun `the threshold follows the pilot setting`() {
        assertNull(feed(35, threshold = 30))
        assertEquals(Low(30), feed(30, threshold = 30))
        assertEquals(Low(25), feed(25, threshold = 30))
        assertNull(feed(25, threshold = 15))
    }

    @Test
    fun `a custom critical level is honoured`() {
        val a = BatteryAnnouncer(criticalPercent = 15)
        assertEquals(Critical(15), a.update(BatteryReading(15, airborne = true), 1_000, 20))
    }
}
