package com.drone.tellopilot.video

import com.drone.tello.control.HudItem
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Units
import com.drone.tellopilot.flightlog.FlightLog
import com.drone.tellopilot.flightlog.FlightSample
import com.drone.tellopilot.flightlog.SummaryAccumulator
import com.drone.tellopilot.ui.flight.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PilotHudTest {

    /** Same templates as the app's French resources. */
    private val texts = HudTexts(
        battery = "Batterie %1\$d %%",
        height = "H %1\$.1f m",
        heightFt = "H %1\$.0f ft",
        speed = "V %1\$.1f m/s",
        speedMph = "V %1\$.1f mph",
        vario = "%1\$s %2\$.1f m/s",
        varioMph = "%1\$s %2\$.1f mph",
        time = "%1\$d:%2\$02d",
        temperature = "%1\$.0f °C",
        wifi = "Wi-Fi %1\$d %%",
        heading = "Cap %1\$d° %2\$s",
        homeDistance = "Départ : %1\$.1f m",
        warning = { w, code -> if (w == Warning.MOTOR_ERROR) "${w.name} $code" else w.name },
        locale = Locale.FRANCE,
    )

    private val full = OverlayValues(
        heightM = 2.46f, speedMps = 1.25f, climbMps = -0.4f, batteryPct = 18, flying = true,
        flyTimeS = 135.7f, imuTempC = 61f, wifiPct = 80, rollDeg = 10.2f, pitchDeg = -4.8f, yawDeg = 359.7f,
        warnings = listOf(Warning.BATTERY_LOW, Warning.WIND),
    )

    private fun frame(values: OverlayValues?, pilot: PilotSettings = PilotSettings(), map: OverlayMapTrack = OverlayMapTrack.EMPTY) =
        PilotHud.frame(values, map, map.at(Long.MAX_VALUE), pilot, texts)

    @Test
    fun `panel shows the flight screen items in its order, formatted like it`() {
        val panel = frame(full)!!.panel
        assertEquals(PilotHud.PANEL_ORDER, panel.map { it.item })
        assertEquals(
            listOf("Batterie 18 %", "H 2,5 m", "V 1,3 m/s", "↓ 0,4 m/s", "2:15", "61 °C", "Wi-Fi 80 %"),
            panel.map { it.text },
        )
        assertEquals(HudAccent.ALERT, panel[0].accent) // battery <= 20 %: red
        assertEquals(HudAccent.WARM, panel[5].accent) // drone >= 60 °C: amber
        assertEquals(HudAccent.NORMAL, panel[1].accent)
    }

    @Test
    fun `items hidden on the flight screen are hidden in the video`() {
        val pilot = PilotSettings(hiddenHud = setOf(HudItem.SPEED, HudItem.WIFI, HudItem.HORIZON, HudItem.MAP))
        val f = frame(full, pilot)!!
        assertEquals(listOf(HudItem.BATTERY, HudItem.HEIGHT, HudItem.VARIO, HudItem.FLIGHT_TIME, HudItem.TEMPERATURE), f.panel.map { it.item })
        assertNull(f.horizon)
        assertNull(f.map)
    }

    @Test
    fun `values missing from the log are not drawn`() {
        val old = OverlayValues(heightM = 1f, speedMps = 0f, climbMps = 0f, batteryPct = 80) // v1 file, no log stream
        val f = frame(old)!!
        assertEquals(listOf(HudItem.BATTERY, HudItem.HEIGHT, HudItem.SPEED, HudItem.VARIO), f.panel.map { it.item })
        assertNull(f.horizon)
        assertEquals("↕ 0,0 m/s", f.panel[3].text)
        // Implausible temperature (field offset unconfirmed): dropped as on screen.
        assertEquals(null, frame(full.copy(imuTempC = 150f))!!.panel.firstOrNull { it.item == HudItem.TEMPERATURE })
        assertNull(frame(full.copy(yawDeg = null))!!.horizon)
    }

    @Test
    fun `no telemetry, nothing drawn`() {
        assertNull(frame(null))
    }

    @Test
    fun `imperial units as on screen`() {
        val panel = frame(full, PilotSettings(units = Units.IMPERIAL))!!.panel
        assertEquals("H 8 ft", panel[1].text)
        assertEquals("V 2,8 mph", panel[2].text)
        assertEquals("↓ 0,9 mph", panel[3].text)
    }

    @Test
    fun `horizon is quantized to half a degree, heading written as on screen`() {
        val h = frame(full)!!.horizon!!
        assertEquals(10f, h.rollDeg)
        assertEquals(-5f, h.pitchDeg)
        assertEquals("Cap 0° N", h.label)
        // Tiny moves do not change the frame (no redraw).
        assertEquals(h, frame(full.copy(rollDeg = 10.1f, pitchDeg = -4.9f, yawDeg = 359.9f))!!.horizon)
        // Beyond the pitch limit the horizon no longer moves.
        assertEquals(40f, frame(full.copy(pitchDeg = 70f))!!.horizon!!.pitchDeg)
    }

    @Test
    fun `alerts follow the flight screen rule`() {
        assertEquals(listOf(Warning.BATTERY_LOW, Warning.WIND), frame(full)!!.warnings.map { it.warning })
        // Information alerts hidden: the battery one stays (always shown).
        val hidden = frame(full, PilotSettings(hiddenHud = setOf(HudItem.INFO_ALERTS)))!!
        assertEquals(listOf(Warning.BATTERY_LOW), hidden.warnings.map { it.warning })
        // Two at most, most severe first.
        val many = full.copy(warnings = listOf(Warning.BATTERY_CRITICAL, Warning.MOTOR_ERROR, Warning.WIND), motorErrorCode = 231)
        assertEquals(listOf("BATTERY_CRITICAL", "MOTOR_ERROR 231"), frame(many)!!.warnings.map { it.text })
    }

    private fun sample(t: Long, x: Float?, y: Float?, flying: Boolean = true, valid: Boolean = true) = FlightSample(
        tMs = t, heightM = 1f, northMps = 0f, eastMps = 0f, climbMps = 0f, batteryPct = 80, flying = flying,
        odoX = x, odoY = y, odoValid = valid,
    )

    private fun log(start: Long, vararg samples: FlightSample) = FlightLog(start, samples.toList(), SummaryAccumulator.of(samples.toList()), false)

    @Test
    fun `map track is the screen's one, up to the instant of the video`() {
        val track = OverlayMapTrack.of(
            listOf(
                log(
                    1_000,
                    sample(0, 0f, 0f),
                    sample(100, 0.05f, 0f), // under 10 cm: skipped
                    sample(200, 0.5f, 0f),
                    sample(300, 1f, 1f, valid = false), // invalid position: skipped
                    sample(400, 1f, 0f),
                    sample(500, 9f, 9f, flying = false), // landed: skipped
                    sample(600, 2f, 2f), // new takeoff: the track restarts
                    sample(700, 2f, 3f),
                ),
            ),
        )
        assertEquals(5, track.size)
        assertNull(track.at(999))
        assertEquals(OverlayMapTrack.Range(0, 0), track.at(1_000))
        assertEquals(OverlayMapTrack.Range(0, 1), track.at(1_250))
        assertEquals(OverlayMapTrack.Range(0, 2), track.at(1_599))
        assertEquals(OverlayMapTrack.Range(3, 3), track.at(1_600))
        assertEquals(OverlayMapTrack.Range(3, 4), track.at(5_000))
        // From two points on, with the distance back to the takeoff point.
        val values = OverlayValues(1f, 0f, 0f, 80)
        assertNull(PilotHud.frame(values, track, track.at(1_000), PilotSettings(), texts)!!.map)
        val map = PilotHud.frame(values, track, track.at(1_500), PilotSettings(), texts)!!.map!!
        assertEquals("Départ : 1,0 m", map.label)
        // Hidden while landed, as on screen.
        assertNull(PilotHud.frame(values.copy(flying = false), track, track.at(1_500), PilotSettings(), texts)!!.map)
    }

    @Test
    fun `map track is bounded like the screen's`() {
        val samples = (0 until 2_100).map { sample(it * 100L, it * 0.2f, 0f) }.toTypedArray()
        val track = OverlayMapTrack.of(listOf(log(0, *samples)))
        val range = track.at(Long.MAX_VALUE)!!
        assertEquals(2_000, range.count)
        assertEquals(2_099, range.to)
        assertNotNull(PilotHud.frame(OverlayValues(1f, 0f, 0f, 80), track, range, PilotSettings(), texts)!!.map)
    }
}
