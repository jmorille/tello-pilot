package com.drone.tellopilot.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Takeoff guard: the thumb must be slid to the end of the track (a tap does nothing),
 * which prevents an accidental takeoff with the phone in a pocket or a mis-touch.
 *
 * It confirms while the finger is still dragging, when the thumb reaches the end — never from
 * the drag-stop callback, which also runs when the gesture is cancelled (link lost, system).
 */
@Composable
fun SlideToConfirm(text: String, enabled: Boolean, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val width = 250.dp
    val thumb = 52.dp
    val maxPx = with(LocalDensity.current) { (width - thumb - 8.dp).toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    val isEnabled by rememberUpdatedState(enabled)
    val confirm by rememberUpdatedState(onConfirm)
    val state = rememberDraggableState { delta ->
        offset = (offset + delta).coerceIn(0f, maxPx)
        if (!fired && isEnabled && offset >= maxPx * CONFIRM_FRACTION) {
            fired = true
            confirm()
        }
    }
    val track = if (enabled) MaterialTheme.colorScheme.primaryContainer else Color.DarkGray
    Box(
        modifier
            .size(width, 60.dp)
            .background(track.copy(alpha = 0.85f), RoundedCornerShape(30.dp))
            .semantics {
                contentDescription = text
                // Explicit, labelled action for accessibility services (no plain "click").
                if (enabled) customActions = listOf(CustomAccessibilityAction(text) { onConfirm(); true })
            },
    ) {
        Text(
            text,
            Modifier.align(Alignment.Center).padding(start = thumb),
            color = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.LightGray,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(offset.roundToInt() + 4.dp.roundToPx(), 0) }
                .size(thumb)
                .background(if (enabled) MaterialTheme.colorScheme.primary else Color.Gray, RoundedCornerShape(26.dp))
                .draggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = {
                        offset = 0f
                        fired = false
                    },
                ),
        ) {
            Text("»", Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private const val CONFIRM_FRACTION = 0.95f
