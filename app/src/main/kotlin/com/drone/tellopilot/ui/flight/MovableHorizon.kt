package com.drone.tellopilot.ui.flight

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.drone.tello.control.HorizonAnchor
import com.drone.tello.control.HorizonPlacement
import com.drone.tello.control.VideoFit
import com.drone.tello.protocol.Attitude
import com.drone.tellopilot.R
import com.drone.tellopilot.hud.HorizonGeometry
import com.drone.tellopilot.hud.HorizonLayout
import com.drone.tellopilot.hud.HudPoint
import com.drone.tellopilot.hud.HudRect
import kotlin.math.roundToInt

/**
 * The artificial horizon, placed where the pilot put it ([placement], see [HorizonLayout]).
 * A long press then a drag moves it (vibration when it lifts, a tick on each snap place, guides
 * while dragging); a plain touch does nothing. It is composed *under* the sticks and the action
 * panel: a finger on a stick is never delivered to it, and on release it is pushed off them.
 *
 * [obstacles] are in the coordinates of this full-screen layer (pixels); empty until measured,
 * the instrument then waits one frame instead of jumping.
 */
@Composable
internal fun MovableHorizon(
    attitude: Attitude,
    placement: HorizonPlacement,
    videoFit: VideoFit,
    obstacles: List<Rect>,
    onPlaced: (HorizonPlacement) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        var measured by remember { mutableStateOf(IntSize.Zero) }
        // Pending drop: shown at once, until the stored settings catch up (no flicker back).
        var pending by remember { mutableStateOf<HorizonPlacement?>(null) }
        LaunchedEffect(placement) { pending = null }
        var dragRaw by remember { mutableStateOf<Offset?>(null) }

        val geometry = with(density) {
            val margin = EDGE_MARGIN.toPx()
            val (videoW, videoH) = videoSize(videoFit, maxWidth, maxHeight)
            val vw = videoW.toPx()
            val vh = videoH.toPx()
            HorizonGeometry(
                screenWidth = screenW,
                screenHeight = screenH,
                bounds = HudRect(
                    insets.getLeft(density, direction) + margin,
                    insets.getTop(density) + margin,
                    screenW - insets.getRight(density, direction) - margin,
                    screenH - insets.getBottom(density) - margin,
                ),
                video = HudRect((screenW - vw) / 2f, (screenH - vh) / 2f, (screenW + vw) / 2f, (screenH + vh) / 2f),
                width = if (measured.width > 0) measured.width.toFloat() else FALLBACK_WIDTH.toPx(),
                height = if (measured.height > 0) measured.height.toFloat() else FALLBACK_HEIGHT.toPx(),
                obstacles = obstacles.map { HudRect(it.left, it.top, it.right, it.bottom) },
                gap = OBSTACLE_GAP.toPx(),
                centerBottomGap = CENTER_BOTTOM_GAP.toPx(),
            )
        }
        val snapRadius = with(density) { SNAP_RADIUS.toPx() }
        val resolved = HorizonLayout.center(pending ?: placement, geometry)
        val dragged = dragRaw?.let { HorizonLayout.clamp(HudPoint(it.x, it.y), geometry) }
        val target = dragged ?: resolved
        // Follows the finger exactly; glides to its place on release or when the layout changes.
        val shown by animateOffsetAsState(
            Offset(target.x, target.y),
            animationSpec = if (dragged != null) snap() else spring(stiffness = Spring.StiffnessMediumLow),
            label = "horizonPosition",
        )
        val nearSnap = dragged?.let { HorizonLayout.nearestSnap(it, geometry, snapRadius) }

        val haptic = LocalHapticFeedback.current
        // A tick on entering a snap place (not for the one it is lifted from).
        var ticked by remember { mutableStateOf<HorizonAnchor?>(null) }
        LaunchedEffect(nearSnap) {
            if (nearSnap != null && nearSnap != ticked) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ticked = nearSnap
        }

        if (dragged != null) SnapGuides(geometry, nearSnap)

        val currentGeometry by rememberUpdatedState(geometry)
        val currentResolved by rememberUpdatedState(resolved)
        val currentOnPlaced by rememberUpdatedState(onPlaced)
        val ready = obstacles.isNotEmpty() && screenW > 0f && screenH > 0f
        val description = stringResource(R.string.hud_horizon_move_hint)
        Box(
            Modifier
                .offset {
                    IntOffset((shown.x - measured.width / 2f).roundToInt(), (shown.y - measured.height / 2f).roundToInt())
                }
                .onSizeChanged { measured = it }
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    // Long press first: a touch or a brush during the flight never moves it.
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            ticked = HorizonLayout.nearestSnap(currentResolved, currentGeometry, snapRadius)
                            dragRaw = Offset(currentResolved.x, currentResolved.y)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragRaw = (dragRaw ?: Offset(currentResolved.x, currentResolved.y)) + amount
                        },
                        onDragEnd = {
                            val g = currentGeometry
                            dragRaw?.let { raw ->
                                val drop = HorizonLayout.clamp(HudPoint(raw.x, raw.y), g)
                                val placed = HorizonLayout.drop(drop, g, snapRadius)
                                pending = placed
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                currentOnPlaced(placed)
                            }
                            dragRaw = null
                        },
                        onDragCancel = { dragRaw = null },
                    )
                },
        ) {
            if (ready) AttitudeIndicator(attitude)
        }
    }
}

/** Discreet guides while dragging: the snap places (the one in reach highlighted) and the no-go zones. */
@Composable
private fun SnapGuides(g: HorizonGeometry, near: HorizonAnchor?) {
    val targets = HorizonLayout.snapTargets(g)
    Canvas(Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        val corner = CornerRadius(12.dp.toPx())
        g.obstacles.forEach { o ->
            drawRoundRect(NoGo, Offset(o.left, o.top), Size(o.width, o.height), CornerRadius(8.dp.toPx()))
        }
        targets.forEach { (anchor, p) ->
            val topLeft = Offset(p.x - g.width / 2f, p.y - g.height / 2f)
            val size = Size(g.width, g.height)
            if (anchor == near) {
                drawRoundRect(SnapFill, topLeft, size, corner)
                drawRoundRect(SnapActive, topLeft, size, corner, style = Stroke(2.dp.toPx()))
            } else {
                drawRoundRect(SnapIdle, topLeft, size, corner, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
            }
        }
    }
}

private val SnapIdle = Color.White.copy(alpha = 0.35f)
private val SnapActive = Color(0xFFFFCA28)
private val SnapFill = Color(0x33FFCA28)
private val NoGo = Color(0x1FEF5350)

/** Kept from the screen edges (inside the system bars / cutout). */
private val EDGE_MARGIN = 8.dp
/** Kept from the sticks, the takeoff / land button and the top bar. */
private val OBSTACLE_GAP = 8.dp
/** Historical bottom-centre place: 96 dp above the bottom of the screen. */
private val CENTER_BOTTOM_GAP = 96.dp
/** A drop closer than this to a snap place snaps to it. */
private val SNAP_RADIUS = 56.dp
/** Disc and heading label, until measured. */
private val FALLBACK_WIDTH = 96.dp
private val FALLBACK_HEIGHT = 118.dp
