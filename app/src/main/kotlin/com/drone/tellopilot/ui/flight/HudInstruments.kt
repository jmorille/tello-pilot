package com.drone.tellopilot.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drone.tello.protocol.Attitude
import com.drone.tellopilot.R
import com.drone.tellopilot.hud.HudColors
import com.drone.tellopilot.hud.HudMath
import com.drone.tellopilot.hud.TrackMapMath

private val Sky = Color(HudColors.SKY)
private val Ground = Color(HudColors.GROUND)
private val Marker = Color(HudColors.MARKER)

/**
 * Artificial horizon: the horizon line tilts with the roll and moves with the pitch (the
 * aircraft symbol stays fixed), the heading is written underneath.
 */
@Composable
fun AttitudeIndicator(attitude: Attitude, modifier: Modifier = Modifier) {
    // Never draw (nor round) a non-finite value: roundToInt() throws on NaN.
    if (!attitude.rollDeg.isFinite() || !attitude.pitchDeg.isFinite() || !attitude.yawDeg.isFinite()) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(96.dp).clip(CircleShape)) {
            val r = size.minDimension / 2
            // 1° of pitch = 1.5 % of the radius, clamped so the horizon stays visible.
            val pitchShift = HudMath.horizonShift(attitude.pitchDeg, r)
            rotate(HudMath.horizonRotation(attitude.rollDeg), center) {
                translate(top = pitchShift) {
                    drawRect(Sky, topLeft = Offset(-r, -r * 2), size = androidx.compose.ui.geometry.Size(r * 4, r * 3))
                    drawRect(Ground, topLeft = Offset(-r, r), size = androidx.compose.ui.geometry.Size(r * 4, r * 3))
                    drawLine(Color.White, Offset(-r, r), Offset(r * 3, r), strokeWidth = 2.dp.toPx())
                }
            }
            // Fixed aircraft symbol.
            val w = 3.dp.toPx()
            drawLine(Marker, Offset(r * 0.35f, r), Offset(r * 0.8f, r), strokeWidth = w)
            drawLine(Marker, Offset(r * 1.2f, r), Offset(r * 1.65f, r), strokeWidth = w)
            drawCircle(Marker, radius = w, center = center)
            drawCircle(Color(HudColors.RING), radius = r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        HudLabel(stringResource(R.string.hud_heading, HudMath.headingDegrees(attitude.yawDeg), HudMath.cardinal(attitude.yawDeg)))
    }
}

/**
 * Mini-map of the visual-odometry track since takeoff: the path, the drone (dot) and an
 * arrow + distance back to the takeoff point. Axes as reported by the drone (unverified).
 */
@Composable
fun TrackMap(track: List<Offset>, modifier: Modifier = Modifier) {
    val last = track.lastOrNull() ?: return
    val home = track.first()
    val distance = TrackMapMath.distance(home.x, home.y, last.x, last.y)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(110.dp).background(Color(HudColors.MAP_BG), RoundedCornerShape(8.dp)).padding(8.dp),
        ) {
            // Scale: the whole track fits, at least ±2 m around the takeoff point.
            val span = TrackMapMath.span(track.size, home.x, home.y, { track[it].x }, { track[it].y })
            val k = TrackMapMath.scale(size.minDimension / 2, span)
            fun map(p: Offset) = Offset(
                TrackMapMath.screenX(p.x, home.x, center.x, k),
                TrackMapMath.screenY(p.y, home.y, center.y, k),
            )
            val path = Path()
            track.forEachIndexed { i, p ->
                val m = map(p)
                if (i == 0) path.moveTo(m.x, m.y) else path.lineTo(m.x, m.y)
            }
            drawPath(path, Color(HudColors.TRACK), style = Stroke(2.dp.toPx()))
            drawCircle(Color(HudColors.HOME), radius = 4.dp.toPx(), center = map(home)) // takeoff point
            val d = map(last)
            drawCircle(Marker, radius = 4.dp.toPx(), center = d)
            if (TrackMapMath.showsArrow(distance)) {
                // Arrow from the drone towards home.
                val angle = TrackMapMath.arrowAngle(d.x, d.y, map(home).x, map(home).y)
                val len = 14.dp.toPx()
                val tip = Offset(d.x + kotlin.math.cos(angle) * len, d.y + kotlin.math.sin(angle) * len)
                drawLine(Color.White, d, tip, strokeWidth = 2.dp.toPx())
            }
        }
        HudLabel(stringResource(R.string.hud_home_distance, distance))
    }
}

@Composable
private fun HudLabel(text: String) {
    Box(Modifier.padding(top = 2.dp).background(Color(HudColors.LABEL_BG), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}
