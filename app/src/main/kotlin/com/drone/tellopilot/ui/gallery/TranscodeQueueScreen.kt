package com.drone.tellopilot.ui.gallery

import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drone.tellopilot.R
import com.drone.tellopilot.video.ActiveVideo
import com.drone.tellopilot.video.FailReason
import com.drone.tellopilot.video.FinishedOutcome
import com.drone.tellopilot.video.FinishedVideo
import com.drone.tellopilot.video.OverlayStatus
import com.drone.tellopilot.video.QueuedVideo
import com.drone.tellopilot.video.TranscodeBoard
import com.drone.tellopilot.video.TranscodeEta
import com.drone.tellopilot.video.TranscodeQueue
import com.drone.tellopilot.video.TranscodeStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class TranscodeQueueViewModel @Inject constructor(private val queue: TranscodeQueue) : ViewModel() {
    val board = queue.board

    fun cancel() = queue.cancelCurrent()
    fun remove(id: String) = queue.remove(id)
    fun retry(id: String) = queue.retry(id)
    fun clear() = queue.clearFinished()
}

/** Re-encoding follow-up: video in progress, waiting ones, finished and failed ones. */
@Composable
fun TranscodeQueueRoute(onBack: () -> Unit, viewModel: TranscodeQueueViewModel = hiltViewModel()) {
    val board by viewModel.board.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (board.finished.isNotEmpty()) TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.queue_clear)) }
            }
            if (board.isIdle && board.finished.isEmpty()) {
                Text(stringResource(R.string.queue_empty), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                board.active?.let { active ->
                    item { SectionTitle(R.string.queue_current) }
                    item { ActiveCard(active, onCancel = viewModel::cancel) }
                }
                if (board.queued.isNotEmpty()) {
                    item { SectionTitle(R.string.queue_waiting) }
                    items(board.queued, key = { "q" + it.id }) { QueuedCard(it, onRemove = { viewModel.remove(it.id) }) }
                }
                if (board.finished.isNotEmpty()) {
                    item { SectionTitle(R.string.queue_finished) }
                    items(board.finished, key = { "f" + it.id }) { FinishedCard(it, onRetry = { viewModel.retry(it.id) }) }
                }
            }
        }
    }
}

/** One-line queue state for the gallery header; tap to open the follow-up screen. */
@Composable
fun TranscodeQueueSummary(board: TranscodeBoard, onOpen: () -> Unit) {
    val active = board.active
    val failed = board.finished.count { it.outcome is FinishedOutcome.Failed && (it.outcome as FinishedOutcome.Failed).reason != FailReason.CANCELLED }
    if (active == null && board.queued.isEmpty() && board.finished.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val line = when {
                active != null && active.stalled -> stringResource(R.string.queue_summary_stalled, active.name)
                active != null -> stringResource(R.string.queue_summary_active, active.name, stageLabel(active), (active.progress * 100).toInt())
                board.queued.isNotEmpty() -> stringResource(R.string.queue_summary_waiting, board.queued.size)
                else -> stringResource(R.string.queue_summary_done, board.finished.size)
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            val extra = listOfNotNull(
                board.queued.size.takeIf { active != null && it > 0 }?.let { stringResource(R.string.queue_summary_waiting, it) },
                failed.takeIf { it > 0 }?.let { stringResource(R.string.queue_summary_failed, it) },
            )
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (active != null && active.stage == TranscodeStage.EXPORTING && !active.paused) {
                LinearProgressIndicator(progress = { active.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        TextButton(onClick = onOpen) { Text(stringResource(R.string.queue_open)) }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun ActiveCard(active: ActiveVideo, onCancel: () -> Unit) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(active.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (active.stage != TranscodeStage.PUBLISHING) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.queue_cancel)) }
            }
        }
        Text(overlayLabel(active.overlay), style = MaterialTheme.typography.bodySmall)
        val stage = stageLabel(active)
        Text(
            if (active.stage == TranscodeStage.EXPORTING) "$stage · ${(active.progress * 100).toInt()} %" else stage,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (active.stage == TranscodeStage.EXPORTING) {
            LinearProgressIndicator(progress = { active.progress }, modifier = Modifier.fillMaxWidth())
        }
        val elapsed = stringResource(R.string.queue_elapsed, DateUtils.formatElapsedTime((now - active.startedAtMs).coerceAtLeast(0) / 1000))
        val remaining = active.exportStartedAtMs
            ?.takeIf { active.stage == TranscodeStage.EXPORTING && !active.paused && !active.stalled }
            ?.let { TranscodeEta.remainingMs(now - it, active.progress) }
            ?.let { stringResource(R.string.queue_remaining, DateUtils.formatElapsedTime(it / 1000)) }
        Text(listOfNotNull(elapsed, remaining).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        when {
            active.paused -> Text(stringResource(R.string.queue_paused), style = MaterialTheme.typography.bodySmall)
            active.stalled -> Text(
                stringResource(R.string.queue_stalled, DateUtils.formatElapsedTime((now - active.lastProgressAtMs).coerceAtLeast(0) / 1000)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun QueuedCard(video: QueuedVideo, onRemove: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(
                        when (video.overlay) {
                            true -> R.string.queue_overlay_choice_on
                            false -> R.string.queue_overlay_choice_off
                            null -> R.string.queue_overlay_choice_setting
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.queue_remove)) }
        }
    }
}

@Composable
private fun FinishedCard(video: FinishedVideo, onRetry: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.name, style = MaterialTheme.typography.bodyLarge)
                when (val o = video.outcome) {
                    is FinishedOutcome.Converted -> {
                        Text(stringResource(R.string.queue_converted, size(video.beforeBytes), size(o.afterBytes)), style = MaterialTheme.typography.bodyMedium)
                        if (!o.originalDeleted) Text(stringResource(R.string.queue_original_kept), style = MaterialTheme.typography.bodySmall)
                    }
                    is FinishedOutcome.Kept ->
                        Text(stringResource(R.string.queue_kept, size(video.beforeBytes), size(o.afterBytes)), style = MaterialTheme.typography.bodyMedium)
                    is FinishedOutcome.Failed -> {
                        Text(failLabel(o.reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        o.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                }
                if (video.outcome !is FinishedOutcome.Failed || video.overlay != OverlayStatus.OFF) {
                    Text(overlayLabel(video.overlay), style = MaterialTheme.typography.bodySmall)
                }
            }
            val failed = video.outcome as? FinishedOutcome.Failed
            if (failed != null && failed.reason != FailReason.SOURCE_MISSING) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.queue_retry)) }
            }
        }
    }
}

@Composable
private fun stageLabel(active: ActiveVideo): String = stringResource(
    when (active.stage) {
        TranscodeStage.WAITING_FLIGHT_LOG -> R.string.queue_stage_waiting_log
        TranscodeStage.EXPORTING -> R.string.queue_stage_exporting
        TranscodeStage.VERIFYING -> R.string.queue_stage_verifying
        TranscodeStage.PUBLISHING -> R.string.queue_stage_publishing
    },
)

@Composable
private fun overlayLabel(status: OverlayStatus): String = stringResource(
    when (status) {
        OverlayStatus.OFF -> R.string.queue_overlay_off
        OverlayStatus.PENDING -> R.string.queue_overlay_pending
        OverlayStatus.ACTIVE -> R.string.queue_overlay_active
        OverlayStatus.TIME_ONLY -> R.string.queue_overlay_time_only
        OverlayStatus.NO_START_TIME -> R.string.queue_overlay_no_start
        OverlayStatus.UNAVAILABLE -> R.string.queue_overlay_unavailable
        OverlayStatus.DROPPED -> R.string.queue_overlay_dropped
    },
)

@Composable
private fun failLabel(reason: FailReason): String = stringResource(
    when (reason) {
        FailReason.SOURCE_MISSING -> R.string.queue_failed_source
        FailReason.STALLED -> R.string.queue_failed_stalled
        FailReason.EXPORT_ERROR -> R.string.queue_failed_export
        FailReason.CHECK_FAILED -> R.string.queue_failed_check
        FailReason.PUBLISH_ERROR -> R.string.queue_failed_publish
        FailReason.OVERHEAT -> R.string.queue_failed_overheat
        FailReason.CANCELLED -> R.string.queue_failed_cancelled
    },
)
