package com.drone.tello.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImpactDetectorTest {

    private data class S(
        val vn: Float = 0f,
        val ve: Float = 0f,
        val vd: Float = 0f,
        val roll: Float = 0f,
        val pitch: Float = 0f,
        val flying: Boolean = true,
        val maneuver: Boolean = false,
    )

    /** A detector already flying for a while (past the takeoff quiet time). */
    private class Flight(config: ImpactConfig = ImpactConfig()) {
        val detector = ImpactDetector(config)
        var now = 0L
        val detected = mutableListOf<ImpactKind>()

        init {
            detector.onSample(now, 0f, 0f, 0f, 0f, 0f, flying = false, maneuver = false)
            hover(6_000)
            detected.clear()
        }

        fun feed(s: S, stepMs: Long = 100): ImpactKind? {
            now += stepMs
            return detector.onSample(now, s.vn, s.ve, s.vd, s.roll, s.pitch, s.flying, s.maneuver)?.also { detected += it }
        }

        fun hover(ms: Long) {
            repeat((ms / 100).toInt()) { feed(S()) }
        }
    }

    @Test
    fun `steady flight and normal stick work raise nothing`() {
        val f = Flight()
        // Accelerate to 1.5 m/s in 0.5 s, cruise with 20° pitch, brake hard with 30°.
        listOf(0.3f, 0.6f, 0.9f, 1.2f, 1.5f).forEach { f.feed(S(vn = it, pitch = -20f)) }
        repeat(20) { f.feed(S(vn = 1.5f, pitch = -15f)) }
        listOf(1.0f, 0.5f, 0.1f, 0f).forEach { f.feed(S(vn = it, pitch = 30f)) }
        f.hover(2_000)
        assertEquals(emptyList<ImpactKind>(), f.detected)
    }

    @Test
    fun `fast mode at 8 m per s braking at 5 m per s2 raises nothing`() {
        val f = Flight()
        var v = 0f
        while (v < 8f) {
            v += 0.5f
            f.feed(S(ve = v, roll = 25f))
        }
        while (v > 0f) {
            f.feed(S(ve = v, roll = -30f))
            v -= 0.5f // 5 m/s² at 10 Hz
        }
        assertEquals(emptyList<ImpactKind>(), f.detected)
    }

    @Test
    fun `wall hit at 1_5 m per s is a shock`() {
        val f = Flight()
        listOf(0.5f, 1f).forEach { f.feed(S(vn = it, pitch = -10f)) }
        repeat(10) { f.feed(S(vn = 1.5f, pitch = -10f)) }
        assertEquals(emptyList<ImpactKind>(), f.detected)
        assertNull(f.feed(S(vn = 0.2f, pitch = 5f))) // 13 m/s², to be confirmed
        assertEquals(ImpactKind.SHOCK, f.feed(S(vn = 0f, pitch = 3f)))
    }

    @Test
    fun `a single corrupt velocity sample is not a shock`() {
        val f = Flight()
        repeat(5) { f.feed(S(vn = 0.5f)) }
        f.feed(S(vn = 4f))
        f.feed(S(vn = 0.5f))
        f.hover(1_000)
        assertEquals(emptyList<ImpactKind>(), f.detected)
    }

    @Test
    fun `tumbling is detected after two samples`() {
        val f = Flight()
        assertNull(f.feed(S(roll = 60f)))
        assertEquals(ImpactKind.TILT, f.feed(S(roll = 70f)))
    }

    @Test
    fun `attitude jump confirmed by the next sample`() {
        val f = Flight()
        f.feed(S())
        assertNull(f.feed(S(pitch = 65f)))
        assertEquals(ImpactKind.ATTITUDE_JUMP, f.feed(S(pitch = 50f)))
    }

    @Test
    fun `an attitude glitch that comes back is ignored`() {
        val f = Flight()
        f.feed(S())
        f.feed(S(roll = 170f))
        f.feed(S(roll = 2f))
        f.hover(500)
        assertEquals(emptyList<ImpactKind>(), f.detected)
    }

    @Test
    fun `roll wraps around 180 degrees`() {
        val d = ImpactDetector(ImpactConfig(maxTiltDeg = 200f)) // only the jump rule
        d.onSample(0, 0f, 0f, 0f, 0f, 0f, flying = false, maneuver = false)
        listOf(179f, 179f, -179f, -178f, 180f).forEachIndexed { i, roll ->
            assertNull(d.onSample(5_000L + i * 100, 0f, 0f, 0f, roll, 0f, flying = true, maneuver = false))
        }
    }

    @Test
    fun `nothing while not flying nor during the takeoff quiet time`() {
        val d = ImpactDetector()
        assertNull(d.onSample(0, 0f, 0f, 0f, 80f, 80f, flying = false, maneuver = false))
        assertNull(d.onSample(100, 0f, 0f, 0f, 80f, 80f, flying = false, maneuver = false))
        // Takeoff / throw: wild values right after the flying edge.
        var t = 200L
        while (t < 4_000) {
            assertNull(d.onSample(t, 0f, 0f, if (t % 200 == 0L) -3f else 2f, 70f, -70f, flying = true, maneuver = false))
            t += 100
        }
        // Calm samples, then a tumble once the quiet time is over (4 s after the last ground sample).
        while (t < 4_300) {
            d.onSample(t, 0f, 0f, 0f, 0f, 0f, flying = true, maneuver = false); t += 100
        }
        assertNull(d.onSample(t, 0f, 0f, 0f, 60f, 0f, flying = true, maneuver = false))
        assertEquals(ImpactKind.TILT, d.onSample(t + 100, 0f, 0f, 0f, 60f, 0f, flying = true, maneuver = false))
    }

    @Test
    fun `flips and manoeuvres are ignored, and one second after`() {
        val f = Flight()
        // Flip announced by the caller.
        f.detector.suppress(f.now, 3_000)
        listOf(90f, 180f, -90f, 0f).forEach { f.feed(S(roll = it, vd = 2f)) }
        f.hover(2_000)
        assertEquals(emptyList<ImpactKind>(), f.detected)
        // Landing / smart video / bounce flagged as manoeuvre.
        repeat(5) { f.feed(S(vd = if (it % 2 == 0) 1.5f else -1.5f, maneuver = true)) }
        f.feed(S(roll = 80f)); f.feed(S(roll = 80f)) // within the following second
        assertEquals(emptyList<ImpactKind>(), f.detected)
        f.hover(1_000)
        f.feed(S(roll = 80f))
        assertEquals(ImpactKind.TILT, f.feed(S(roll = 80f)))
    }

    @Test
    fun `one detection per shock`() {
        val f = Flight()
        f.feed(S(roll = 80f)); f.feed(S(roll = 80f))
        repeat(10) { f.feed(S(roll = 80f)) }
        assertEquals(listOf(ImpactKind.TILT), f.detected)
    }

    @Test
    fun `samples too far apart are not differentiated`() {
        val f = Flight()
        listOf(0.5f, 1f, 1.5f, 2f).forEach { f.feed(S(vn = it)) }
        f.feed(S(vn = 0f), stepMs = 1_000) // link gap
        f.feed(S(vn = 0f))
        assertEquals(emptyList<ImpactKind>(), f.detected)
    }
}
