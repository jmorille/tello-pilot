package com.drone.tellopilot.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Virtual stick. Latency notes:
 * - the knob position is only read in the draw phase → moving it redraws, never recomposes;
 * - [onMove] is called synchronously from the pointer event, before the next frame.
 * Values: x right-positive, y up-positive, both in -1..1 (each axis clamped: square gate).
 */
@Composable
fun Joystick(
    onMove: (x: Float, y: Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val isEnabled by rememberUpdatedState(enabled)

    Canvas(
        modifier.pointerInput(Unit) {
            fun handle(position: Offset) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = min(size.width, size.height) / 2f * TRAVEL
                // Square gate, like the official app: each axis reaches full deflection on its
                // own (a diagonal gives full pitch AND full roll, not 71 % of each).
                val raw = (position - center) / radius
                val v = Offset(raw.x.coerceIn(-1f, 1f), raw.y.coerceIn(-1f, 1f))
                knob = v
                if (isEnabled) move(v.x, -v.y)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                try {
                    down.consume()
                    handle(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        handle(change.position)
                    }
                } finally {
                    // Also runs if the gesture is cancelled (node removed): never leave a stick deflected.
                    knob = Offset.Zero
                    release()
                }
            }
        },
    ) {
        val radius = min(size.width, size.height) / 2f
        val alpha = if (isEnabled) 1f else 0.4f
        drawCircle(Color.Black.copy(alpha = 0.25f * alpha), radius)
        drawCircle(Color.White.copy(alpha = 0.6f * alpha), radius, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.25f * alpha), radius * 0.08f)
        val k = center + knob * (radius * TRAVEL)
        drawCircle(Color.White.copy(alpha = 0.85f * alpha), radius * 0.28f, k)
    }
}

private const val TRAVEL = 0.72f
