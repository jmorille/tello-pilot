package com.drone.tellopilot.ui.flight

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.drone.tello.control.GamepadBindings
import com.drone.tello.control.Mission
import com.drone.tellopilot.R
import com.drone.tellopilot.ui.settings.commandBindingLabel
import com.drone.tellopilot.ui.settings.label
import com.drone.tellopilot.ui.settings.missionMissing
import com.drone.tellopilot.ui.settings.text

/**
 * Gamepad indicator of the HUD: a long press shows the fixed commands and the pilot's shortcuts.
 * The list never takes the window focus (the pad keeps flying while it is open) and the label
 * is not focusable (D-pad presses never move to it).
 */
@Composable
internal fun PadHudLabel(
    text: String,
    color: Color,
    bindings: GamepadBindings,
    missions: List<Mission>,
) {
    var open by remember { mutableStateOf(false) }
    val openLabel = stringResource(R.string.pad_sheet_open)
    Box {
        Text(
            text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false,
            modifier = Modifier
                .pointerInput(Unit) { detectTapGestures(onLongPress = { open = true }) }
                .semantics { onLongClick(openLabel) { open = true; true } },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = false),
        ) {
            PadCheatSheet(bindings, missions)
        }
    }
}

@Composable
private fun PadCheatSheet(bindings: GamepadBindings, missions: List<Mission>) {
    val resources = LocalResources.current
    SheetHeader(R.string.pad_sheet_commands)
    bindings.entries.forEach { (command, binding) ->
        SheetRow(stringResource(command.label), commandBindingLabel(command, binding))
    }
    SheetHeader(R.string.pad_sheet_shortcuts)
    if (bindings.shortcuts.isEmpty()) {
        Text(
            stringResource(R.string.pad_sheet_empty),
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    bindings.shortcuts.forEach { shortcut ->
        val missing = shortcut.missionMissing(missions)
        SheetRow(
            shortcut.function.text(resources) + if (missing) " — " + stringResource(R.string.pad_sheet_missing) else "",
            shortcut.binding.label(),
            error = missing,
        )
    }
}

@Composable
private fun SheetHeader(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SheetRow(name: String, buttons: String, error: Boolean = false) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp).widthIn(min = 240.dp, max = 420.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(buttons, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
