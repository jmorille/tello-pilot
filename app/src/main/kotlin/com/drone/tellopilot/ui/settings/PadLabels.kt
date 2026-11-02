package com.drone.tellopilot.ui.settings

import android.content.res.Resources
import com.drone.tello.control.Mission
import com.drone.tello.control.PadAction
import com.drone.tello.control.PadBinding
import com.drone.tello.control.PadButton
import com.drone.tello.control.PadCommand
import com.drone.tello.control.PadFunction
import com.drone.tello.control.PadShortcut
import com.drone.tello.control.TrackingMode
import com.drone.tellopilot.R
import com.drone.tellopilot.ui.flight.arrow
import com.drone.tellopilot.ui.flight.label

/* Texts of the gamepad layout, shared by the configuration screen and the flight screen's reminder. */

internal fun PadButton.label(): String = when (this) {
    PadButton.DPAD_UP -> "✚↑"
    PadButton.DPAD_DOWN -> "✚↓"
    PadButton.DPAD_LEFT -> "✚←"
    PadButton.DPAD_RIGHT -> "✚→"
    else -> name
}

internal fun PadBinding.label(): String = modifier?.let { "${it.label()} + ${button.label()}" } ?: button.label()

/** [binding] of [command] as the pilot uses it (the flip button is held with the D-pad). */
internal fun commandBindingLabel(command: PadCommand, binding: PadBinding): String =
    binding.label() + if (command == PadCommand.FLIP) " + ✚" else ""

internal val PadCommand.label: Int
    get() = when (this) {
        PadCommand.TAKEOFF_LAND -> R.string.pad_cmd_takeoff_land
        PadCommand.FLIP -> R.string.pad_cmd_flip
        PadCommand.PHOTO -> R.string.pad_cmd_photo
        PadCommand.RECORD -> R.string.pad_cmd_record
        PadCommand.FAST_MODE -> R.string.pad_cmd_fast
        PadCommand.STOP_SMART_VIDEO -> R.string.pad_cmd_stop_smart
    }

internal fun PadFunction.text(res: Resources): String = when (this) {
    is PadFunction.SmartVideo -> res.getString(R.string.pad_fn_smart, res.getString(mode.label))
    is PadFunction.Flip -> res.getString(R.string.pad_action_flip, direction.arrow)
    PadFunction.Bounce -> res.getString(R.string.pad_fn_bounce)
    is PadFunction.RunMission -> res.getString(R.string.pad_fn_mission, name)
    is PadFunction.Tracking -> res.getString(
        R.string.pad_fn_tracking,
        res.getString(
            when (mode) {
                TrackingMode.FOLLOW -> R.string.tracking_follow
                TrackingMode.SPOTLIGHT -> R.string.tracking_spotlight
                TrackingMode.ORBIT -> if (orbitDirection < 0) R.string.tracking_orbit_left else R.string.tracking_orbit_right
            },
        ),
    )
    PadFunction.PalmLand -> res.getString(R.string.pad_fn_palm_land)
    PadFunction.Tripod -> res.getString(R.string.action_tripod)
    PadFunction.FastMode -> res.getString(R.string.pad_cmd_fast)
    PadFunction.Photo -> res.getString(R.string.pad_cmd_photo)
    PadFunction.Record -> res.getString(R.string.pad_cmd_record)
    PadFunction.Stop -> res.getString(R.string.pad_cmd_stop_smart)
}

internal fun PadShortcut.text(res: Resources): String = res.getString(R.string.pad_shortcut_label, function.text(res), binding.label())

internal fun PadAction.text(res: Resources): String = when (this) {
    PadAction.TakeoffOrLand -> res.getString(R.string.pad_cmd_takeoff_land)
    PadAction.Photo -> res.getString(R.string.pad_cmd_photo)
    PadAction.ToggleRecording -> res.getString(R.string.pad_cmd_record)
    PadAction.ToggleFastMode -> res.getString(R.string.pad_cmd_fast)
    PadAction.StopSmartVideo -> res.getString(R.string.pad_cmd_stop_smart)
    is PadAction.Flip -> res.getString(R.string.pad_action_flip, direction.arrow)
    is PadAction.Shortcut -> function.text(res)
}

/** Missions a shortcut can name: the presets, then the pilot's own. */
internal fun availableMissions(custom: List<Mission>): List<Mission> = Mission.Presets + custom

/** A mission shortcut whose mission was deleted or renamed: it does nothing when pressed. */
internal fun PadShortcut.missionMissing(missions: List<Mission>): Boolean {
    val f = function as? PadFunction.RunMission ?: return false
    val wanted = Mission.cleanName(f.name)
    return missions.none { Mission.cleanName(it.name) == wanted }
}
