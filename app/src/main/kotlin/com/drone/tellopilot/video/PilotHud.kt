package com.drone.tellopilot.video

import com.drone.tello.control.HudItem
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.Units
import com.drone.tellopilot.hud.HudMath
import com.drone.tellopilot.hud.TrackMapMath
import com.drone.tellopilot.ui.flight.Warning
import java.util.Locale
import kotlin.math.abs

/**
 * Pure part of the "pilot view" overlay (no Android): what the flight screen HUD showed at one
 * instant of the video, already formatted. Equal frames draw the same pixels, so the bitmaps
 * are redrawn only when a frame differs from the previous one. Unit tested on the JVM.
 */

/** Text colour of a panel item, as on the flight screen. */
enum class HudAccent { NORMAL, ALERT, WARM }

/** One item of the top panel. */
data class PanelItem(val item: HudItem, val text: String, val accent: HudAccent = HudAccent.NORMAL)

/** Artificial horizon, angles quantized to [PilotHud.ATTITUDE_STEP_DEG]. */
data class HorizonFrame(val rollDeg: Float, val pitchDeg: Float, val label: String)

/** Mini-map: the part of the [OverlayMapTrack] known at that instant. */
data class MapFrame(val range: OverlayMapTrack.Range, val label: String)

data class WarningFrame(val warning: Warning, val text: String)

/** Everything the pilot view draws at one instant; `null` parts are not drawn. */
data class PilotHudFrame(
    val panel: List<PanelItem>,
    val warnings: List<WarningFrame>,
    val horizon: HorizonFrame?,
    val map: MapFrame?,
)

/**
 * Format strings of the HUD, read from the app resources (the same as the flight screen's);
 * [warning] gives an alert's text (the motor error takes its code).
 */
class HudTexts(
    val battery: String,
    val height: String,
    val heightFt: String,
    val speed: String,
    val speedMph: String,
    val vario: String,
    val varioMph: String,
    val time: String,
    val temperature: String,
    val wifi: String,
    val heading: String,
    val homeDistance: String,
    val warning: (Warning, motorErrorCode: Int) -> String,
    val locale: Locale = Locale.getDefault(),
) {
    fun format(template: String, vararg args: Any): String = String.format(locale, template, *args)
}

object PilotHud {
    /**
     * The horizon is redrawn when roll or pitch move by this much: under a pixel of the disc
     * edge at 960x720, and far fewer redraws than one per frame while hovering.
     */
    const val ATTITUDE_STEP_DEG = 0.5f

    /** Flight screen panel order. */
    val PANEL_ORDER = listOf(
        HudItem.BATTERY, HudItem.HEIGHT, HudItem.SPEED, HudItem.VARIO, HudItem.FLIGHT_TIME, HudItem.TEMPERATURE, HudItem.WIFI,
    )

    /**
     * Frame of one instant. [values] `null` (no telemetry: before the takeoff, link lost):
     * nothing is drawn. Items hidden by the pilot ([PilotSettings.hiddenHud]) stay hidden, the
     * alerts follow the same rule as on screen ([Warning.shown]).
     */
    fun frame(
        values: OverlayValues?,
        map: OverlayMapTrack,
        mapRange: OverlayMapTrack.Range?,
        pilot: PilotSettings,
        texts: HudTexts,
    ): PilotHudFrame? {
        values ?: return null
        val shown = Warning.shown(values.warnings, pilot).take(Warning.MAX_SHOWN)
        return PilotHudFrame(
            panel = panel(values, pilot, texts),
            warnings = shown.map { WarningFrame(it, texts.warning(it, values.motorErrorCode)) },
            horizon = if (pilot.shows(HudItem.HORIZON)) horizon(values, texts) else null,
            map = if (pilot.shows(HudItem.MAP) && values.flying) map(map, mapRange, texts) else null,
        )
    }

    fun panel(v: OverlayValues, pilot: PilotSettings, texts: HudTexts): List<PanelItem> {
        val imperial = pilot.units == Units.IMPERIAL
        return PANEL_ORDER.filter(pilot::shows).mapNotNull { item ->
            when (item) {
                HudItem.BATTERY -> PanelItem(
                    item, texts.format(texts.battery, v.batteryPct.coerceIn(0, 100)),
                    if (HudMath.batteryLow(v.batteryPct)) HudAccent.ALERT else HudAccent.NORMAL,
                )
                HudItem.HEIGHT -> PanelItem(
                    item,
                    if (imperial) texts.format(texts.heightFt, v.heightM * HudMath.FEET_PER_METER) else texts.format(texts.height, v.heightM),
                )
                HudItem.SPEED -> PanelItem(
                    item,
                    if (imperial) texts.format(texts.speedMph, v.speedMps * HudMath.MPH_PER_MS) else texts.format(texts.speed, v.speedMps),
                )
                HudItem.VARIO -> {
                    val arrow = HudMath.varioArrow(v.climbMps)
                    val rate = abs(v.climbMps)
                    PanelItem(
                        item,
                        if (imperial) texts.format(texts.varioMph, arrow, rate * HudMath.MPH_PER_MS) else texts.format(texts.vario, arrow, rate),
                    )
                }
                HudItem.FLIGHT_TIME -> v.flyTimeS?.let {
                    val s = it.toInt().coerceAtLeast(0)
                    PanelItem(item, texts.format(texts.time, s / 60, s % 60))
                }
                HudItem.TEMPERATURE -> v.imuTempC?.takeIf(HudMath::isPlausibleImuTemp)?.let {
                    PanelItem(item, texts.format(texts.temperature, it), if (HudMath.droneHot(it)) HudAccent.WARM else HudAccent.NORMAL)
                }
                HudItem.WIFI -> v.wifiPct?.let { PanelItem(item, texts.format(texts.wifi, it)) }
                else -> null
            }
        }
    }

    /** `null` without a complete attitude (log stream not received, or an old file without it). */
    fun horizon(v: OverlayValues, texts: HudTexts): HorizonFrame? {
        val roll = v.rollDeg ?: return null
        val pitch = v.pitchDeg ?: return null
        val yaw = v.yawDeg ?: return null
        return HorizonFrame(
            rollDeg = HudMath.quantize(roll, ATTITUDE_STEP_DEG),
            // Beyond the limit the horizon no longer moves: no redraw either.
            pitchDeg = HudMath.quantize(pitch.coerceIn(-HudMath.PITCH_LIMIT_DEG, HudMath.PITCH_LIMIT_DEG), ATTITUDE_STEP_DEG),
            label = texts.format(texts.heading, HudMath.headingDegrees(yaw), HudMath.cardinal(yaw)),
        )
    }

    /** Shown from two points on, as on screen. */
    fun map(map: OverlayMapTrack, range: OverlayMapTrack.Range?, texts: HudTexts): MapFrame? {
        range?.takeIf { it.count >= 2 } ?: return null
        val distance = TrackMapMath.distance(map.x(range.from), map.y(range.from), map.x(range.to), map.y(range.to))
        return MapFrame(range, texts.format(texts.homeDistance, distance))
    }
}
