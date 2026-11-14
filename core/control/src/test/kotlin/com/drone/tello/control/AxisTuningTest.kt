package com.drone.tello.control

import com.drone.tello.protocol.StickInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs

class AxisTuningTest {

    /** -1..1 every 0.001, plus the exact ends and centre. */
    private val grid = (-1000..1000).map { it / 1000f }

    private val wild = listOf(
        AxisTuning(gain = 0.5f, amplitude = 0.2f, expo = 0f),
        AxisTuning(gain = 2f, amplitude = 1f, expo = 0.8f),
        AxisTuning(gain = 1.4f, amplitude = 0.7f, expo = 0.15f),
        AxisTuning(gain = 1f, amplitude = 1f, expo = 0.3f),
    )

    // ------------------------------------------------------------- curve

    @Test
    fun `the curve is monotone, odd and within the amplitude`() {
        for (t in wild) for (dz in listOf(0f, 0.05f, 0.2f)) {
            var previous = -2f
            for (x in grid) {
                val y = t.apply(x, dz)
                assertTrue(y >= previous, "$t dz=$dz not monotone at $x")
                assertTrue(abs(y) <= t.amplitude + 1e-6f, "$t dz=$dz beyond the amplitude at $x: $y")
                assertEquals(-y, t.apply(-x, dz), 0f, "$t dz=$dz not symmetric at $x")
                previous = y
            }
            assertEquals(0f, t.apply(0f, dz), 0f)
            // Full stick: the amplitude, unless a gain under 100 % keeps the stick short of it.
            val g = minOf(1f, t.gain)
            assertEquals(t.amplitude * ((1f - t.expo) * g + t.expo * g * g * g), t.apply(1f, dz), 1e-6f)
        }
    }

    @Test
    fun `a gain above 100 percent saturates at 1 over the gain of the travel`() {
        val t = AxisTuning(gain = 1.4f, amplitude = 1f, expo = 0.15f)
        val at = 1f / 1.4f
        assertEquals(1f, t.apply(at + 1e-4f, 0f), 1e-5f)
        assertEquals(1f, t.apply(0.9f, 0f), 0f)
        assertTrue(t.apply(at - 0.05f, 0f) < 1f)
        // Gives more command for the same stick travel than the default.
        for (x in listOf(0.1f, 0.3f, 0.5f, 0.7f)) assertTrue(t.apply(x, 0.05f) > AxisTuning.Default.apply(x, 0.05f), "at $x")
        // With a dead zone, the stick saturates at dz + (1 - dz) / gain.
        assertEquals(1f, t.apply(0.05f + 0.95f / 1.4f + 1e-4f, 0.05f), 1e-5f)
    }

    @Test
    fun `the amplitude caps the output and keeps the curve shape`() {
        val full = AxisTuning(gain = 1f, amplitude = 1f, expo = 0.4f)
        val half = full.copy(amplitude = 0.5f)
        for (x in grid) assertEquals(full.apply(x, 0.05f) * 0.5f, half.apply(x, 0.05f), 1e-6f)
    }

    @Test
    fun `NaN and out of range inputs give a valid command`() {
        for (t in wild + AxisTuning(Float.NaN, Float.NaN, Float.NaN) + AxisTuning(99f, -3f, 5f)) {
            assertEquals(0f, t.apply(Float.NaN, 0.05f), 0f)
            for (x in listOf(5f, -7f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val y = t.apply(x, 0.05f)
                assertTrue(!y.isNaN() && y in -1f..1f, "$t $x -> $y")
            }
            val y = t.apply(0.5f, Float.NaN)
            assertTrue(!y.isNaN() && y in -1f..1f)
        }
        // Corrupt values act like their default / bound.
        assertEquals(AxisTuning.Default.apply(0.6f, 0.05f), AxisTuning(Float.NaN, Float.NaN, Float.NaN).apply(0.6f, 0.05f), 0f)
        assertEquals(AxisTuning(2f, 0.2f, 0.8f).apply(0.4f, 0f), AxisTuning(99f, -3f, 5f).apply(0.4f, 0f), 0f)
    }

    @Test
    fun `sanitized bounds every value, NaN gives the default`() {
        assertEquals(AxisTuning(2f, 0.2f, 0.8f), AxisTuning(99f, -3f, 5f).sanitized())
        assertEquals(AxisTuning(0.5f, 1f, 0f), AxisTuning(0f, 7f, -1f).sanitized())
        assertEquals(AxisTuning(1f, 1f, 0.3f), AxisTuning(Float.NaN, Float.NaN, Float.NaN).sanitized())
        assertEquals(0.6f, AxisTuning(expo = Float.NaN).sanitized(defaultExpo = 0.6f).expo)
    }

    // --------------------------------------------------- non-regression

    @Test
    fun `defaults reproduce the former shaping bit for bit`() {
        for (dz in listOf(0f, 0.05f, 0.1f, 0.2f)) for (expo in listOf(0f, 0.3f, 0.5f, 0.8f)) {
            val old = StickShaping(dz, expo)
            val new = AxisTuning(expo = expo)
            for (x in grid + listOf(Float.NaN, 3f, -3f)) assertEquals(old.apply(x), new.apply(x, dz), 0f, "dz=$dz expo=$expo x=$x")
        }
    }

    @ParameterizedTest
    @EnumSource(StickMode::class)
    fun `the mixer with default settings sends what it sent before, tripod included`(mode: StickMode) {
        val shaping = StickShaping(0.05f, 0.3f)
        for (scale in listOf(1f, 0.3f)) for (fast in listOf(false, true)) {
            val mixer = StickMixer(mode, PilotSettings().stickTuning).apply { this.scale = scale }
            for (a in grid.filterIndexed { i, _ -> i % 50 == 0 }) for (b in listOf(-1f, -0.4f, 0f, 0.07f, 0.66f, 1f)) {
                mixer.setLeft(a, b, fast)
                val i = mixer.setRight(b, a, fast)
                val s = { v: Float -> shaping.apply(v) * scale } // the former mixer
                val expected = when (mode) {
                    StickMode.MODE_1 -> StickInput(roll = s(b), pitch = s(b), throttle = s(a), yaw = s(a), fastMode = fast)
                    StickMode.MODE_2 -> StickInput(roll = s(b), pitch = s(a), throttle = s(b), yaw = s(a), fastMode = fast)
                    StickMode.MODE_3 -> StickInput(roll = s(a), pitch = s(b), throttle = s(a), yaw = s(b), fastMode = fast)
                    StickMode.MODE_4 -> StickInput(roll = s(a), pitch = s(a), throttle = s(b), yaw = s(b), fastMode = fast)
                }
                assertEquals(expected, i, "$mode scale=$scale fast=$fast ($a, $b)")
            }
        }
    }

    // ---------------------------------------------------------- mixer

    private val normal = FlightTuning(
        pitch = AxisTuning(gain = 1.4f, amplitude = 1f, expo = 0f),
        roll = AxisTuning(gain = 1f, amplitude = 0.5f, expo = 0f),
        yaw = AxisTuning(gain = 1f, amplitude = 0.4f, expo = 0f),
        throttle = AxisTuning(gain = 2f, amplitude = 1f, expo = 0f),
    )
    private val fast = FlightTuning.uniform(0f).copy(pitch = AxisTuning(gain = 1f, amplitude = 0.8f, expo = 0f))
    private val tuning = StickTuning(deadZone = 0f, normal = normal, fast = fast)

    @Test
    fun `each axis gets its own profile, chosen by the fast mode at each mix`() {
        val mixer = StickMixer(StickMode.MODE_2, tuning)
        mixer.setLeft(0.5f, 0.25f, false) // yaw, throttle
        val slow = mixer.setRight(0.5f, 0.5f, false) // roll, pitch
        assertEquals(0.7f, slow.pitch, 1e-6f)
        assertEquals(0.25f, slow.roll, 1e-6f)
        assertEquals(0.2f, slow.yaw, 1e-6f)
        assertEquals(0.5f, slow.throttle, 1e-6f)
        // Same sticks, fast mode toggled: the fast profile at once.
        val quick = mixer.mix(true)
        assertEquals(StickInput(roll = 0.5f, pitch = 0.4f, throttle = 0.25f, yaw = 0.5f, fastMode = true), quick)
        assertEquals(slow, mixer.mix(false))
    }

    @ParameterizedTest
    @EnumSource(StickMode::class)
    fun `the profile follows the drone axis whatever the stick layout`(mode: StickMode) {
        val mixer = StickMixer(mode, StickTuning(0f, FlightTuning.uniform(0f).copy(yaw = AxisTuning(1f, 0.4f, 0f)), FlightTuning.uniform(0f)))
        mixer.setLeft(1f, 1f, false)
        val i = mixer.setRight(1f, 1f, false)
        assertEquals(StickInput(roll = 1f, pitch = 1f, throttle = 1f, yaw = 0.4f), i)
    }

    @Test
    fun `the tripod scale applies after the axis profile`() {
        val mixer = StickMixer(StickMode.MODE_2, tuning).apply { scale = 0.3f }
        mixer.setLeft(1f, 0f, false)
        val i = mixer.setRight(1f, 1f, false)
        assertEquals(0.3f, i.pitch, 1e-6f) // gain saturates at 1, then the tripod
        assertEquals(0.15f, i.roll, 1e-6f) // amplitude 0.5 then the tripod
        assertEquals(0.12f, i.yaw, 1e-6f)
    }

    @ParameterizedTest
    @EnumSource(StickMode::class)
    fun `the output stays within -1 to 1 with the strongest settings`(mode: StickMode) {
        val strong = FlightTuning.uniform(0f).let { f -> StickAxis.entries.fold(f) { t, a -> t.with(a, AxisTuning(2f, 1f, 0f)) } }
        val mixer = StickMixer(mode, StickTuning(0f, strong, strong))
        val inputs = listOf(5f, -7f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1f, -1f, 0.9f)
        for (a in inputs) for (b in inputs) for (f in listOf(false, true)) {
            mixer.setLeft(a, b, f)
            val i = mixer.setRight(b, a, f)
            listOf(i.roll, i.pitch, i.throttle, i.yaw).forEach { assertTrue(!it.isNaN() && it in -1f..1f, "$mode ($a, $b) -> $i") }
        }
    }

    // --------------------------------------------------------- presets

    @Test
    fun `presets stay within the slider ranges and standard is the default`() {
        for (preset in TuningPreset.entries) for (f in listOf(false, true)) {
            val t = preset.tuning(f)
            assertEquals(t, t.sanitized(), "$preset fast=$f")
        }
        assertEquals(FlightTuning.Default, TuningPreset.STANDARD.tuning(false))
        assertEquals(FlightTuning.Default, TuningPreset.STANDARD.tuning(true))
    }

    @Test
    fun `the official preset follows the squared curve of the official app`() {
        val t = TuningPreset.OFFICIAL.tuning(false).pitch
        for (x in grid.filter { it >= 0f }) assertEquals(x * x, t.apply(x, 0f), 0.04f, "at $x")
    }

    @Test
    fun `the pitch boost preset gives more pitch for the same stick in normal mode`() {
        val boost = TuningPreset.PITCH_BOOST.tuning(false)
        for (x in listOf(0.2f, 0.4f, 0.6f)) assertTrue(boost.pitch.apply(x, 0.05f) > AxisTuning.Default.apply(x, 0.05f))
        assertEquals(AxisTuning.Default, boost.roll)
        assertEquals(AxisTuning.Default, boost.yaw)
    }
}
