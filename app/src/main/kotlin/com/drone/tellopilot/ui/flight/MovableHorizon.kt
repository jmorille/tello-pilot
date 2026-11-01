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
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import kotlinx.coroutines.delay
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
    // Physical left / top everywhere (insets, measured bounds, drag deltas): never mirrored in RTL.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        var measured by remember { mutableStateOf(IntSize.Zero) }
        // Widest size seen: the heading label may exceed the disc with a large font, and a
        // width changing with the heading would make the instrument twitch near an edge.
        var widest by remember { mutableIntStateOf(0) }
        // Pending drop: shown at once, until the stored settings catch up (no flicker back).
        var pending by remember { mutableStateOf<HorizonPlacement?>(null) }
        LaunchedEffect(placement) { if (placement == pending) pending = null }
        LaunchedEffect(pending) {
            // A lost write never pins the instrument: the stored place wins after a while.
            if (pending != null) {
                delay(PENDING_TIMEOUT_MS)
                pending = null
            }
        }
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
                width = if (widest > 0) widest.toFloat() else FALLBACK_WIDTH.toPx(),
                height = if (measured.height > 0) measured.height.toFloat() else FALLBACK_HEIGHT.toPx(),
                obstacles = obstacles.map { HudRect(it.left, it.top, it.right, it.bottom) },
                gap = OBSTACLE_GAP.toPx(),
                centerBottomGap = CENTER_BOTTOM_GAP.toPx(),
            )
        }
        val snapRadius = with(density) { SNAP_RADIUS.toPx() }
        val ready = obstacles.isNotEmpty() && screenW > 0f && screenH > 0f
        // No glide while the layout and the stored settings arrive: it appears in place.
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(ready) {
            if (ready) {
                delay(SETTLE_MS)
                settled = true
            }
        }
        val resolved = HorizonLayout.center(pending ?: placement, geometry)
        val dragged = dragRaw?.let { HorizonLayout.clamp(HudPoint(it.x, it.y), geometry) }
        val target = dragged ?: resolved
        // Follows the finger exactly; glides to its place on release or when the layout changes.
        val shownState = animateOffsetAsState(
            Offset(target.x, target.y),
            animationSpec = if (dragged != null || !settled) snap() else spring(stiffness = Spring.StiffnessMediumLow),
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
        val currentOnPlaced by rememberUpdatedState(onPlaced)
        val currentSnapRadius by rememberUpdatedState(snapRadius)
        val description = stringResource(R.string.hud_horizon_move_hint)
        val anchorLabels = HorizonAnchor.entries.associateWith { stringResource(anchorLabel(it)) }
        var moved by remember { mutableStateOf(false) }
        Box(
            Modifier
                .absoluteOffset {
                    val shown = shownState.value
                    IntOffset((shown.x - measured.width / 2f).roundToInt(), (shown.y - measured.height / 2f).roundToInt())
                }
                .onSizeChanged {
                    measured = it
                    if (it.width > widest) widest = it.width
                }
                .semantics {
                    contentDescription = description
                    // Screen readers: the three snap places as actions.
                    customActions = HorizonAnchor.entries.map { anchor ->
                        CustomAccessibilityAction(anchorLabels.getValue(anchor)) {
                            currentOnPlaced(HorizonPlacement.snapped(anchor))
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Long press first: a touch or a brush during the flight never moves it.
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // From where it is drawn (it may still be gliding), not from its target.
                            val start = shownState.value
                            ticked = HorizonLayout.nearestSnap(HudPoint(start.x, start.y), currentGeometry, currentSnapRadius)
                            moved = false
                            dragRaw = start
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (amount != Offset.Zero) moved = true
                            dragRaw = (dragRaw ?: shownState.value) + amount
                        },
                        onDragEnd = {
                            val g = currentGeometry
                            // A long press without a move changes nothing (no silent re-snap).
                            dragRaw?.takeIf { moved }?.let { raw ->
                                val drop = HorizonLayout.clamp(HudPoint(raw.x, raw.y), g)
                                val placed = HorizonLayout.drop(drop, g, currentSnapRadius)
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

private fun anchorLabel(anchor: HorizonAnchor) = when (anchor) {
    HorizonAnchor.LEFT -> R.string.hud_horizon_action_left
    HorizonAnchor.CENTER -> R.string.hud_horizon_action_center
    HorizonAnchor.RIGHT -> R.string.hud_horizon_action_right
}

/** A dropped place not confirmed by the stored settings within this delay is dropped. */
private const val PENDING_TIMEOUT_MS = 2_000L
/** Layout and stored settings arriving after the first frame: placed without a glide. */
private const val SETTLE_MS = 500L

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
