package com.drone.tellopilot.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Pure model of the re-encoding queue as the user follows it (gallery, notification): the
 * video in progress with its step, the waiting ones, the finished and failed ones. No Android:
 * unit tested on the JVM. Videos are identified by their MediaStore Uri as a string. Times are
 * monotonic milliseconds (`SystemClock.elapsedRealtime()`).
 */

/** Step of the video in progress. */
enum class TranscodeStage {
    /** Telemetry overlay: waiting for the flight log to be closed (landing tail). */
    WAITING_FLIGHT_LOG,
    EXPORTING,
    /** Checking the output (format, duration). */
    VERIFYING,
    /** Copy into the gallery, original deleted. */
    PUBLISHING,
}

/** What became of the telemetry overlay of one video. */
enum class OverlayStatus {
    /** Not asked for (setting off or unticked in the gallery). */
    OFF,
    /** Asked for, being prepared. */
    PENDING,
    /** Burnt in with flight data. */
    ACTIVE,
    /** Burnt in, no flight log overlaps the video: time of day only. */
    TIME_ONLY,
    /** Asked for but impossible: video start time unknown. */
    NO_START_TIME,
    /** Asked for but the overlay could not be built (error). */
    UNAVAILABLE,
    /** The export with the overlay failed or stalled: the copy was redone without it. */
    DROPPED,
}

/** Why a video was not converted. */
enum class FailReason {
    /** The video was deleted (or became unreadable) before or during the re-encoding. */
    SOURCE_MISSING,
    /** No progress for too long: the export was stopped. */
    STALLED,
    EXPORT_ERROR,
    /** The output is not in the requested format or does not last as long as the original. */
    CHECK_FAILED,
    /** The copy could not be written to the gallery. */
    PUBLISH_ERROR,
    /** Abandoned: the phone kept overheating. */
    OVERHEAT,
    /** Cancelled by the user. */
    CANCELLED,
}

data class QueuedVideo(val id: String, val name: String, val overlay: Boolean?)

data class ActiveVideo(
    val id: String,
    val name: String,
    /** Original size, bytes. */
    val sizeBytes: Long,
    val overlay: OverlayStatus,
    val stage: TranscodeStage,
    /** 0..1, export progress. */
    val progress: Float,
    val startedAtMs: Long,
    /** Start of the current export (a retry without overlay restarts it), for the estimate. */
    val exportStartedAtMs: Long?,
    /** Last time the export progressed (or started). */
    val lastProgressAtMs: Long,
    /** No progress for a while: shown as "stalled" before being stopped. */
    val stalled: Boolean,
    /** Waiting for the landing or for the phone to cool down. */
    val paused: Boolean,
)

sealed interface FinishedOutcome {
    data class Converted(val afterBytes: Long, val originalDeleted: Boolean) : FinishedOutcome
    /** Not smaller than the original: the original stays, nothing published. */
    data class Kept(val afterBytes: Long) : FinishedOutcome
    data class Failed(val reason: FailReason, val detail: String?) : FinishedOutcome
}

data class FinishedVideo(
    val id: String,
    val name: String,
    val beforeBytes: Long,
    val overlay: OverlayStatus,
    /** Overlay choice of the request (to retry the same way). */
    val overlayChoice: Boolean?,
    val outcome: FinishedOutcome,
    val finishedAtMs: Long,
)

data class TranscodeBoard(
    val active: ActiveVideo? = null,
    val queued: List<QueuedVideo> = emptyList(),
    /** Most recent first, at most [MAX_FINISHED]. */
    val finished: List<FinishedVideo> = emptyList(),
) {
    val isIdle: Boolean get() = active == null && queued.isEmpty()

    /** Position of the active video (1-based) and total, as in "2/5". */
    val position: Int get() = if (active == null) 0 else 1
    val total: Int get() = (if (active == null) 0 else 1) + queued.size

    fun enqueued(videos: List<QueuedVideo>): TranscodeBoard {
        var q = queued
        for (v in videos) {
            if (v.id == active?.id) continue
            val i = q.indexOfFirst { it.id == v.id }
            // A gallery choice (non-null) replaces "as the setting says"; the reverse never.
            q = if (i < 0) q + v else if (v.overlay != null) q.toMutableList().also { it[i] = v } else q
        }
        // A retried video leaves the finished list.
        val ids = videos.map { it.id }.toSet()
        return copy(queued = q, finished = finished.filterNot { it.id in ids })
    }

    fun removed(id: String): TranscodeBoard = copy(queued = queued.filterNot { it.id == id })

    /** Display name of a queued video, known once MediaStore answered. */
    fun named(id: String, name: String): TranscodeBoard =
        copy(queued = queued.map { if (it.id == id && it.name != name) it.copy(name = name) else it })

    /** [id] leaves the queue and becomes the active video. */
    fun started(id: String, name: String, sizeBytes: Long, overlay: OverlayStatus, nowMs: Long, paused: Boolean) = copy(
        active = ActiveVideo(id, name, sizeBytes, overlay, TranscodeStage.EXPORTING, 0f, nowMs, null, nowMs, false, paused),
        queued = queued.filterNot { it.id == id },
    )

    fun stage(stage: TranscodeStage, nowMs: Long): TranscodeBoard = updateActive {
        it.copy(
            stage = stage,
            stalled = false,
            lastProgressAtMs = nowMs,
            exportStartedAtMs = if (stage == TranscodeStage.EXPORTING) nowMs else it.exportStartedAtMs,
            progress = if (stage == TranscodeStage.EXPORTING) 0f else it.progress,
        )
    }

    fun overlay(status: OverlayStatus): TranscodeBoard = updateActive { it.copy(overlay = status) }

    fun paused(paused: Boolean): TranscodeBoard = updateActive { it.copy(paused = paused) }

    /** Export progress [progress] (0..1) seen at [nowMs]; a rise resets the stall timer. */
    fun progress(progress: Float, nowMs: Long): TranscodeBoard = updateActive {
        val rose = progress > it.progress
        it.copy(
            progress = maxOf(it.progress, progress.coerceIn(0f, 1f)),
            lastProgressAtMs = if (rose) nowMs else it.lastProgressAtMs,
            stalled = if (rose) false else it.stalled,
        )
    }

    fun stalled(stalled: Boolean): TranscodeBoard = updateActive { it.copy(stalled = stalled) }

    /** Back to the queue head (takeoff, heat: redone from the start once possible). */
    fun requeued(overlayChoice: Boolean?): TranscodeBoard {
        val a = active ?: return this
        return copy(active = null, queued = listOf(QueuedVideo(a.id, a.name, overlayChoice)) + queued.filterNot { it.id == a.id })
    }

    fun finished(outcome: FinishedOutcome, overlayChoice: Boolean?, nowMs: Long): TranscodeBoard {
        val a = active ?: return this
        val done = FinishedVideo(a.id, a.name, a.sizeBytes, a.overlay, overlayChoice, outcome, nowMs)
        return copy(active = null, finished = (listOf(done) + finished.filterNot { it.id == a.id }).take(MAX_FINISHED))
    }

    /** A video that failed before becoming active (deleted while waiting...). */
    fun failedBeforeStart(id: String, name: String, reason: FailReason, overlayChoice: Boolean?, nowMs: Long): TranscodeBoard {
        val done = FinishedVideo(id, name, 0, OverlayStatus.OFF, overlayChoice, FinishedOutcome.Failed(reason, null), nowMs)
        return copy(queued = queued.filterNot { it.id == id }, finished = (listOf(done) + finished.filterNot { it.id == id }).take(MAX_FINISHED))
    }

    fun clearedFinished(): TranscodeBoard = copy(finished = emptyList())

    private inline fun updateActive(f: (ActiveVideo) -> ActiveVideo) = active?.let { copy(active = f(it)) } ?: this

    companion object {
        const val MAX_FINISHED = 20
    }
}

/** Export time estimates. */
object TranscodeEta {
    /** Below this progress the estimate is noise. */
    const val MIN_PROGRESS = 0.03f

    /** Remaining export time, or null while too early to say. */
    fun remainingMs(elapsedMs: Long, progress: Float): Long? {
        if (progress < MIN_PROGRESS || progress >= 1f || elapsedMs <= 0) return null
        return (elapsedMs * (1f - progress) / progress).toLong()
    }
}

/**
 * Watchdog of an export that may hang without any error (no callback ever comes): stalled
 * when the progress has not risen for [stallMs]; [warnMs] earlier, shown as such.
 */
class StallWatchdog(private val warnMs: Long, private val stallMs: Long, startMs: Long) {
    private var lastRiseMs = startMs
    private var best = -1f

    enum class State { OK, WARN, STALLED }

    /** [progress]: null when the exporter cannot tell (counts as no progress). */
    fun update(nowMs: Long, progress: Float?): State {
        if (progress != null && progress > best) {
            best = progress
            lastRiseMs = nowMs
        }
        val idle = nowMs - lastRiseMs
        return when {
            idle >= stallMs -> State.STALLED
            idle >= warnMs -> State.WARN
            else -> State.OK
        }
    }
}

/**
 * Runs [block] with [flow] as a StateFlow, then stops the sharing. `stateIn(scope)` launches a
 * collector that lives as long as its scope: inside a `coroutineScope` it must be cancelled
 * explicitly, otherwise the scope never returns (this hung the queue after its last video).
 */
suspend fun <T, R> withStateOf(flow: Flow<T>, block: suspend CoroutineScope.(StateFlow<T>) -> R): R = coroutineScope {
    val state = flow.stateIn(this)
    try {
        block(state)
    } finally {
        coroutineContext.cancelChildren()
    }
}
