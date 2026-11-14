package com.drone.tellopilot.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drone.tello.control.AxisTuning
import com.drone.tello.control.FlightTuning
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.StickAxis
import com.drone.tello.control.TuningPreset
import com.drone.tellopilot.R
import kotlin.math.roundToInt

/**
 * Per-axis response (gain, amplitude, expo) of the normal and fast flight modes, with a live
 * response curve per axis. The sliders edit a local draft (the curve follows the finger) and
 * persist on release, like [SliderSetting]. [fastExtras] is shown in the fast tab only (settings
 * that only exist in fast mode, such as the max tilt angle).
 */
@Composable
internal fun AxisTuningSection(
    pilot: PilotSettings,
    updatePilot: ((PilotSettings) -> PilotSettings) -> Unit,
    fastExtras: @Composable () -> Unit = {},
) {
    Text(stringResource(R.string.axis_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.axis_help), style = MaterialTheme.typography.bodySmall)

    var fast by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = !fast, onClick = { fast = false }, label = { Text(stringResource(R.string.axis_mode_normal)) })
        FilterChip(selected = fast, onClick = { fast = true }, label = { Text(stringResource(R.string.axis_mode_fast)) })
    }
    Text(
        stringResource(if (fast) R.string.axis_mode_help_fast else R.string.axis_mode_help_normal),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (fast) fastExtras()

    val persisted = pilot.axisTuning(fast).sanitized()
    var draft by remember(persisted, fast) { mutableStateOf(persisted) }
    val save = { tuning: FlightTuning ->
        val mode = fast
        updatePilot { it.withAxisTuning(mode, tuning) }
    }

    Text(stringResource(R.string.axis_presets), style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TuningPreset.entries.forEach { preset ->
            val tuning = preset.tuning(fast)
            FilterChip(
                selected = draft == tuning,
                onClick = { draft = tuning; save(tuning) },
                label = { Text(stringResource(presetLabel(preset))) },
            )
        }
    }
    TextButton(onClick = { draft = FlightTuning.Default; save(FlightTuning.Default) }) {
        Text(stringResource(R.string.axis_reset_mode))
    }

    val deadZone = pilot.stickTuning.deadZone
    listOf(StickAxis.PITCH, StickAxis.ROLL, StickAxis.YAW).forEach { axis ->
        AxisCard(axis, draft[axis], deadZone, onDrag = { draft = draft.with(axis, it) }, onDone = { save(draft) })
    }
    // Vertical axis: not asked for as often, kept folded.
    var showThrottle by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showThrottle = !showThrottle }) {
        Text(stringResource(if (showThrottle) R.string.axis_hide_throttle else R.string.axis_show_throttle))
    }
    if (showThrottle) {
        AxisCard(StickAxis.THROTTLE, draft.throttle, deadZone, onDrag = { draft = draft.with(StickAxis.THROTTLE, it) }, onDone = { save(draft) })
    }
}

@Composable
private fun AxisCard(
    axis: StickAxis,
    tuning: AxisTuning,
    deadZone: Float,
    onDrag: (AxisTuning) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(axisLabel(axis)), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(CURVE_SIZE), horizontalAlignment = Alignment.CenterHorizontally) {
                    ResponseCurve(tuning, deadZone)
                    val hint = when {
                        tuning.gain > 1f -> stringResource(R.string.axis_saturation, ((deadZone + (1f - deadZone) / tuning.gain) * 100).roundToInt())
                        else -> stringResource(R.string.axis_max_output, (tuning.apply(1f, deadZone) * 100).roundToInt())
                    }
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    TuningSlider(R.string.axis_gain, tuning.gain, AxisTuning.GAIN_RANGE, steps = 29, onDone) { onDrag(tuning.copy(gain = it)) }
                    TuningSlider(R.string.axis_amplitude, tuning.amplitude, AxisTuning.AMPLITUDE_RANGE, steps = 15, onDone) {
                        onDrag(tuning.copy(amplitude = it))
                    }
                    TuningSlider(R.string.axis_expo, tuning.expo, AxisTuning.EXPO_RANGE, steps = 15, onDone) { onDrag(tuning.copy(expo = it)) }
                }
            }
        }
    }
}

/** Slider in percent, 5 % steps; the value is rounded to 0.01 so that presets compare equal. */
@Composable
private fun TuningSlider(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDone: () -> Unit,
    onDrag: (Float) -> Unit,
) {
    Text(stringResource(label, (value * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value.coerceIn(range),
        onValueChange = { onDrag(((it * 100).roundToInt() / 100f).coerceIn(range)) },
        onValueChangeFinished = onDone,
        valueRange = range,
        steps = steps,
    )
}

/**
 * Stick deflection (0..1, left to right) to command sent (0..1, bottom to top): the linear
 * response dashed for reference, the axis response in the primary colour.
 */
@Composable
private fun ResponseCurve(tuning: AxisTuning, deadZone: Float) {
    val description = stringResource(R.string.axis_curve_description)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val reference = MaterialTheme.colorScheme.onSurfaceVariant
    val curve = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(CURVE_SIZE).semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        drawRect(grid, style = Stroke(width = 1.dp.toPx()))
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 0.5.dp.toPx())
        drawLine(grid, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 0.5.dp.toPx())
        drawLine(
            reference, Offset(0f, h), Offset(w, 0f), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        val path = Path()
        for (i in 0..CURVE_POINTS) {
            val x = i / CURVE_POINTS.toFloat()
            val y = tuning.apply(x, deadZone)
            val px = x * w
            val py = h - y * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, curve, style = Stroke(width = 2.5.dp.toPx()))
    }
}

private val CURVE_SIZE = 96.dp
private const val CURVE_POINTS = 64

private fun axisLabel(axis: StickAxis) = when (axis) {
    StickAxis.PITCH -> R.string.axis_pitch
    StickAxis.ROLL -> R.string.axis_roll
    StickAxis.YAW -> R.string.axis_yaw
    StickAxis.THROTTLE -> R.string.axis_throttle
}

private fun presetLabel(preset: TuningPreset) = when (preset) {
    TuningPreset.CINEMA -> R.string.axis_preset_cinema
    TuningPreset.STANDARD -> R.string.axis_preset_standard
    TuningPreset.OFFICIAL -> R.string.axis_preset_official
    TuningPreset.PITCH_BOOST -> R.string.axis_preset_pitch_boost
    TuningPreset.SPORT -> R.string.axis_preset_sport
}
