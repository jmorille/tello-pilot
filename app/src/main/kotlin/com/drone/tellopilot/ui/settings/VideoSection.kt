package com.drone.tellopilot.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TranscodeCodec
import com.drone.tellopilot.R
import com.drone.tellopilot.video.TranscodeQueue
import com.drone.tellopilot.video.TranscodeState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class VideoSectionViewModel @Inject constructor(private val queue: TranscodeQueue) : ViewModel() {
    val state = queue.state

    /** Codecs this phone can encode, with "hardware" flag (AV1 is software-only on a Pixel 7). */
    val codecs: List<Pair<TranscodeCodec, Boolean>> =
        TranscodeCodec.entries.filter { queue.isSupported(it) }.map { it to queue.isHardware(it) }

    fun reencodeExisting() = queue.enqueueExisting()
}

@Composable
fun VideoSection(
    pilot: PilotSettings,
    updatePilot: ((PilotSettings) -> PilotSettings) -> Unit,
    viewModel: VideoSectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SwitchSetting(R.string.settings_auto_transcode, pilot.autoTranscode) { v -> updatePilot { it.copy(autoTranscode = v) } }
    Text(stringResource(R.string.settings_transcode_codec), style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        viewModel.codecs.forEach { (codec, hardware) ->
            val name = stringResource(if (codec == TranscodeCodec.HEVC) R.string.settings_codec_hevc else R.string.settings_codec_av1)
            val kind = stringResource(if (hardware) R.string.settings_codec_hardware else R.string.settings_codec_software)
            FilterChip(
                selected = pilot.transcodeCodec == codec,
                onClick = { updatePilot { it.copy(transcodeCodec = codec) } },
                label = { Text("$name · $kind") },
            )
        }
    }
    SwitchSetting(R.string.settings_keep_original, pilot.keepOriginal) { v -> updatePilot { it.copy(keepOriginal = v) } }
    Text(stringResource(R.string.settings_transcode_help), style = MaterialTheme.typography.bodySmall)
    val running = state is TranscodeState.Running
    OutlinedButton(onClick = viewModel::reencodeExisting, enabled = !running) {
        Text(stringResource(R.string.settings_transcode_existing))
    }
    TranscodeStatus(state)
    NotificationsHint()
}

/** Shown when notifications are off: the re-encoding progress is then invisible outside the app. */
@Composable
private fun NotificationsHint() {
    val context = LocalContext.current
    var enabled by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    if (enabled) return
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        enabled = granted
        if (!granted) {
            // Refused for good: only the system settings can turn them back on.
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
    Text(stringResource(R.string.settings_notifications_off), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }) { Text(stringResource(R.string.settings_notifications_allow)) }
}

@Composable
private fun TranscodeStatus(state: TranscodeState) {
    val context = LocalContext.current
    when (state) {
        TranscodeState.Idle -> Unit
        is TranscodeState.Running -> {
            Text(
                if (state.paused) {
                    stringResource(R.string.transcode_paused, state.index, state.total, state.name)
                } else {
                    stringResource(R.string.transcode_running, state.index, state.total, state.name, (state.progress * 100).toInt())
                },
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        is TranscodeState.Done -> {
            val saved = Formatter.formatShortFileSize(context, state.savedBytes)
            Text(
                when {
                    state.converted == 0 && state.failed == 0 && state.kept == 0 -> stringResource(R.string.transcode_nothing)
                    state.failed > 0 -> stringResource(R.string.transcode_done_failed, state.converted, state.failed, saved)
                    else -> stringResource(R.string.transcode_done, state.converted, saved)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.kept > 0) {
                Text(stringResource(R.string.transcode_kept, state.kept), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
