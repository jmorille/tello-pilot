package com.drone.tellopilot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.MoveDirection
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TrackingTarget
import com.drone.tellopilot.R
import kotlin.math.abs

/** Settings pane of the automatic flights: tracking target and the pilot's own missions. */
@Composable
fun AutopilotSettings(pilot: PilotSettings, updatePilot: ((PilotSettings) -> PilotSettings) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) } // index in customMissions, -1 = new
    Text(stringResource(R.string.settings_tracking_target), style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackingTarget.entries.forEach { t ->
            FilterChip(
                selected = pilot.trackingTarget == t,
                onClick = { updatePilot { it.copy(trackingTarget = t) } },
                label = {
                    Text(stringResource(if (t == TrackingTarget.PERSON) R.string.settings_target_person else R.string.settings_target_face))
                },
            )
        }
    }
    Text(stringResource(R.string.settings_autopilot_help), style = MaterialTheme.typography.bodySmall)

    Text(stringResource(R.string.settings_missions_presets), style = MaterialTheme.typography.bodyLarge)
    Mission.Presets.forEach { m ->
        Text("• ${m.name} : ${m.steps.map { stepText(it) }.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.settings_missions_custom), style = MaterialTheme.typography.bodyLarge)
    if (pilot.customMissions.isEmpty()) {
        Text(stringResource(R.string.settings_missions_none), style = MaterialTheme.typography.bodySmall)
    }
    pilot.customMissions.forEachIndexed { i, m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.bodyMedium)
                Text(m.steps.map { stepText(it) }.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            TextButton(onClick = { editing = i }) { Text(stringResource(R.string.mission_edit)) }
            TextButton(onClick = {
                updatePilot { p -> p.copy(customMissions = p.customMissions.filterIndexed { j, _ -> j != i }) }
            }) { Text(stringResource(R.string.mission_delete)) }
        }
    }
    OutlinedButton(onClick = { editing = -1 }) { Text(stringResource(R.string.mission_new)) }

    editing?.let { index ->
        MissionEditor(
            initial = pilot.customMissions.getOrNull(index),
            onDismiss = { editing = null },
            onSave = { mission ->
                editing = null
                updatePilot { p ->
                    val list = p.customMissions.toMutableList()
                    if (index in list.indices) list[index] = mission else list += mission
                    p.copy(customMissions = list)
                }
            },
        )
    }
}

/** Step kinds offered in the editor, with the unit of their value. */
private enum class StepKind(val label: Int, val unit: Int, val default: Float) {
    UP(R.string.step_up, R.string.unit_m, 1f),
    DOWN(R.string.step_down, R.string.unit_m, 1f),
    FORWARD(R.string.step_forward, R.string.unit_m, 2f),
    BACK(R.string.step_back, R.string.unit_m, 2f),
    LEFT(R.string.step_left, R.string.unit_m, 2f),
    RIGHT(R.string.step_right, R.string.unit_m, 2f),
    TURN_RIGHT(R.string.step_turn_right, R.string.unit_deg, 90f),
    TURN_LEFT(R.string.step_turn_left, R.string.unit_deg, 90f),
    HOVER(R.string.step_hover, R.string.unit_s, 3f),
    ;

    fun build(v: Float): MissionStep = when (this) {
        UP -> MissionStep.Climb(v)
        DOWN -> MissionStep.Climb(-v)
        FORWARD -> MissionStep.Move(MoveDirection.FORWARD, v)
        BACK -> MissionStep.Move(MoveDirection.BACK, v)
        LEFT -> MissionStep.Move(MoveDirection.LEFT, v)
        RIGHT -> MissionStep.Move(MoveDirection.RIGHT, v)
        TURN_RIGHT -> MissionStep.Rotate(v)
        TURN_LEFT -> MissionStep.Rotate(-v)
        HOVER -> MissionStep.Hover(v)
    }
}

@Composable
private fun stepText(step: MissionStep): String {
    fun f(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
    return when (step) {
        is MissionStep.Climb -> stringResource(if (step.meters > 0) R.string.step_up else R.string.step_down) + " ${f(abs(step.meters))} m"
        is MissionStep.Move -> stringResource(
            when (step.direction) {
                MoveDirection.FORWARD -> R.string.step_forward
                MoveDirection.BACK -> R.string.step_back
                MoveDirection.LEFT -> R.string.step_left
                MoveDirection.RIGHT -> R.string.step_right
            },
        ) + " ${f(step.meters)} m"
        is MissionStep.Rotate -> stringResource(if (step.degrees > 0) R.string.step_turn_right else R.string.step_turn_left) + " ${f(abs(step.degrees))}°"
        is MissionStep.Hover -> stringResource(R.string.step_hover) + " ${f(step.seconds)} s"
    }
}

@Composable
private fun MissionEditor(initial: Mission?, onDismiss: () -> Unit, onSave: (Mission) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    val steps = remember { mutableStateListOf<MissionStep>().apply { initial?.let { addAll(it.steps) } } }
    var kind by remember { mutableStateOf(StepKind.FORWARD) }
    var value by remember { mutableStateOf(StepKind.FORWARD.default.toString()) }
    var kindMenu by remember { mutableStateOf(false) }
    val candidate = value.replace(',', '.').toFloatOrNull()?.let { kind.build(it) }
    val canAdd = candidate?.isValid() == true && steps.size < MAX_STEPS
    val cleanName = Mission.cleanName(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.mission_new else R.string.mission_edit)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.mission_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${stepText(step)}", Modifier.weight(1f))
                        TextButton(onClick = { steps.removeAt(i) }) { Text(stringResource(R.string.mission_delete)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { kindMenu = true }) { Text(stringResource(kind.label)) }
                        DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                            StepKind.entries.forEach { k ->
                                DropdownMenuItem(text = { Text(stringResource(k.label)) }, onClick = {
                                    kind = k
                                    value = k.default.toString()
                                    kindMenu = false
                                })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.take(6) },
                        suffix = { Text(stringResource(kind.unit)) },
                        singleLine = true,
                        isError = !canAdd && steps.size < MAX_STEPS,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp),
                    )
                    TextButton(onClick = { candidate?.let { steps += it } }, enabled = canAdd) {
                        Text(stringResource(R.string.mission_add_step))
                    }
                }
                Text(stringResource(R.string.mission_limits), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Mission(cleanName, steps.toList())) },
                enabled = cleanName.isNotEmpty() && steps.isNotEmpty(),
            ) { Text(stringResource(R.string.mission_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mission_cancel)) } },
    )
}

private const val MAX_STEPS = 20
