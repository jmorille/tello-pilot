package com.drone.tellopilot.video

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TranscodeCodec
import com.drone.tellopilot.data.PhoneHeat
import com.drone.tellopilot.data.PhoneThermal
import com.drone.tellopilot.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TranscodeState {
    data object Idle : TranscodeState

    /** [paused]: waiting for the landing (or the phone to cool down). */
    data class Running(
        val name: String,
        val index: Int,
        val total: Int,
        val progress: Float,
        val paused: Boolean,
    ) : TranscodeState

    /** [kept]: re-encoding brought no gain, the original H.264 stays. */
    data class Done(val converted: Int, val kept: Int, val failed: Int, val savedBytes: Long) : TranscodeState
}

/**
 * Post-flight re-encoding of the recorded videos, one at a time. Never runs in flight or with
 * a hot phone: the current video is abandoned at takeoff (CPU, GPU and heat belong to the live
 * view) and redone from the start once landed. The original is only deleted once the new
 * file is published and checked. [board] details the queue for the user (gallery, notification).
 */
@Singleton
class TranscodeQueue internal constructor(
    private val context: Context,
    private val library: VideoLibrary,
    private val transcoder: VideoTranscoder,
    settings: SettingsRepository,
    recorder: VideoRecorder,
    private val thermal: PhoneThermal,
    private val overlays: TelemetryOverlayFactory,
    /** Where the queue works ([Dispatchers.Default]; a test dispatcher in the JVM tests). */
    dispatcher: CoroutineDispatcher,
    /** Monotonic clock for the board's times (elapsedRealtime; a fake in the JVM tests). */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        library: VideoLibrary,
        transcoder: VideoTranscoder,
        settings: SettingsRepository,
        recorder: VideoRecorder,
        thermal: PhoneThermal,
        overlays: TelemetryOverlayFactory,
    ) : this(context, library, transcoder, settings, recorder, thermal, overlays, Dispatchers.Default)

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.e(TAG, "transcode queue", e) },
    )
    /** Video -> telemetry overlay choice (`null`: the setting at processing time). */
    private val pending = LinkedHashMap<Uri, Boolean?>()
    /** Board id (Uri string) -> Uri of the videos queued in this process (for a retry). */
    private val known = HashMap<String, Uri>()
    private var worker: Job? = null

    /** Flight screens currently reporting a flying drone (a closing screen cannot unpause another one). */
    private val flyingOwners = MutableStateFlow<Set<Any>>(emptySet())

    /** Id of the active video the user asked to cancel. */
    private val cancelRequest = MutableStateFlow<String?>(null)

    private val pilot: StateFlow<PilotSettings?> = settings.settings.map { it.pilot }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow<TranscodeState>(TranscodeState.Idle)
    val state: StateFlow<TranscodeState> = _state.asStateFlow()

    private val _board = MutableStateFlow(TranscodeBoard())
    /** The queue in detail: video in progress, waiting, finished and failed ones. */
    val board: StateFlow<TranscodeBoard> = _board.asStateFlow()

    /** Work queued or in progress (the foreground service lives as long as this is true). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        // Recordings left pending by a process that died mid-flight (crash, update): published
        // if readable (then re-encoded like any recording), deleted otherwise.
        scope.launch {
            val recovered = library.recoverOrphans { recorder.isActive }
            if (recovered.isNotEmpty() && pilot.first { it != null }?.autoTranscode != false) enqueue(recovered)
        }
        // drop(1): the recorder's current state is not a new recording.
        scope.launch {
            recorder.state.drop(1).collect {
                if (it is RecordingState.Saved && pilot.value?.autoTranscode != false) enqueue(listOf(it.uri))
            }
        }
    }

    /**
     * Paused in flight, and from HOT until the phone is back to NORMAL (hysteresis: resuming at
     * WARM would heat it up again straight away). Cold: only watched while the queue works.
     */
    private fun pauseFlow(): Flow<Boolean> {
        // drop(1): the fold's seed is not a reading (a hot phone must not look cool for an instant).
        val thermalHold = thermal.heat.runningFold(false) { hold, heat -> thermalHold(hold, heat) }.drop(1)
        return combine(flyingOwners, thermalHold) { owners, hold -> owners.isNotEmpty() || hold }.distinctUntilChanged()
    }

    fun setFlying(owner: Any, value: Boolean) {
        flyingOwners.update { if (value) it + owner else it - owner }
    }

    fun isSupported(codec: TranscodeCodec) = transcoder.isSupported(codec)

    fun isHardware(codec: TranscodeCodec) = transcoder.isHardware(codec)

    /** Queues every H.264 flight video that has no re-encoded copy yet. */
    fun enqueueExisting() {
        scope.launch {
            val todo = untranscoded(library.list(), DroneVideo::isH264, DroneVideo::baseName)
            if (todo.isEmpty()) _state.value = TranscodeState.Done(0, 0, 0, 0) else enqueue(todo.map { it.uri })
        }
    }

    /**
     * Re-encodes one video now (gallery), whatever the automatic setting; [overlay]: burn the
     * telemetry into the copy (`null`: as the setting says).
     */
    fun enqueueVideo(uri: Uri, overlay: Boolean? = null) = enqueue(listOf(uri), overlay)

    /** Takes a waiting video out of the queue. */
    fun remove(id: String) {
        synchronized(pending) {
            pending.keys.firstOrNull { it.toString() == id }?.let { pending.remove(it) }
            _board.update { it.removed(id) }
        }
    }

    /** Stops the video in progress (it is reported as cancelled, the queue goes on). */
    fun cancelCurrent() {
        val active = _board.value.active ?: return
        // Publishing: the copy is being written and the original deleted, too late to cancel.
        if (active.stage == TranscodeStage.PUBLISHING) return
        val id = active.id
        Log.i(TAG, "cancel requested for $id")
        cancelRequest.value = id
    }

    /** Queues a failed (or kept) video again, with the same overlay choice. */
    fun retry(id: String) {
        val done = _board.value.finished.firstOrNull { it.id == id } ?: return
        val uri = synchronized(pending) { known[id] } ?: Uri.parse(id)
        enqueue(listOf(uri), done.overlayChoice)
    }

    fun clearFinished() = _board.update { it.clearedFinished() }

    private fun enqueue(uris: List<Uri>, overlay: Boolean? = null) {
        synchronized(pending) {
            // A choice made in the gallery is never reset to "as the setting says" by a bulk enqueue.
            uris.forEach { if (overlay != null || it !in pending) pending[it] = overlay }
            uris.forEach { known[it.toString()] = it }
            // A cancel left over for a video queued again (retry) must not cancel the new run.
            cancelRequest.value?.let { c ->
                if (c != _board.value.active?.id && uris.any { it.toString() == c }) cancelRequest.value = null
            }
            _board.update { b -> b.enqueued(uris.map { QueuedVideo(it.toString(), it.lastPathSegment.orEmpty(), overlay) }) }
            scope.launch { uris.forEach { uri -> library.get(uri)?.let { v -> _board.update { it.named(uri.toString(), v.name) } } } }
            if (worker?.isActive == true) return
            _busy.value = true
            worker = scope.launch { drain() }
        }
        TranscodeService.start(context)
    }

    private suspend fun drain() {
        var completed = false
        try {
            // withStateOf stops the pause watcher when the loop is over: a bare stateIn(this)
            // inside coroutineScope kept the worker alive forever after its last video (queue
            // stuck, service and notification never gone, later videos never processed).
            withStateOf(pauseFlow()) { paused ->
                launch {
                    paused.collect { p ->
                        _state.update { if (it is TranscodeState.Running) it.copy(paused = p) else it }
                        _board.update { it.paused(p) }
                    }
                }
                drainLoop(paused)
            }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "queue stopped", e)
            synchronized(pending) { pending.clear() }
            _board.update { TranscodeBoard(finished = it.finished) }
            _state.value = TranscodeState.Done(0, 0, 1, 0)
        } finally {
            val restarted = synchronized(pending) {
                // A video queued during the last instants: keep going with a new worker.
                worker = if (completed && pending.isNotEmpty()) scope.launch { drain() } else null
                _busy.value = worker != null
                worker != null
            }
            if (restarted) TranscodeService.start(context)
        }
    }

    private suspend fun drainLoop(paused: StateFlow<Boolean>) {
        // Leftovers of a process killed mid-export.
        File(context.cacheDir, TMP_DIR).listFiles()?.forEach { it.delete() }
        var converted = 0
        var kept = 0
        var failed = 0
        var saved = 0L
        var index = 0
        val thermalAborts = HashMap<Uri, Int>()
        while (true) {
            val (uri, overlayChoice) = synchronized(pending) { pending.entries.firstOrNull()?.toPair() } ?: break
            val id = uri.toString()
            val total = index + synchronized(pending) { pending.size }
            val video = library.get(uri)
            if (video == null || !video.isH264) {
                synchronized(pending) {
                    pending.remove(uri)
                    _board.update {
                        if (video == null) {
                            Log.w(TAG, "$id: video gone before its turn")
                            it.failedBeforeStart(id, it.queued.firstOrNull { q -> q.id == id }?.name ?: id, FailReason.SOURCE_MISSING, overlayChoice, now())
                        } else {
                            it.removed(id)
                        }
                    }
                }
                continue
            }
            // Removed by the user while MediaStore was being asked.
            if (synchronized(pending) { uri !in pending }) continue
            _state.value = TranscodeState.Running(video.name, index + 1, total, 0f, paused.value)
            val wantOverlay = overlayChoice ?: (pilot.value ?: PilotSettings()).overlayOnTranscode
            _board.update {
                it.started(id, video.name, video.sizeBytes, if (wantOverlay) OverlayStatus.PENDING else OverlayStatus.OFF, now(), paused.value)
            }
            if (cancelRequest.value != id) cancelRequest.value = null
            // Waits for the landing / cooling down, unless the user cancels meanwhile.
            combine(paused, cancelRequest) { p, c -> !p || c == id }.first { it }
            var outcome = if (cancelRequest.value == id) {
                FinishedOutcome.Failed(FailReason.CANCELLED, null)
            } else {
                runInterruptible(video, overlayChoice, paused)
            }
            // A cancel asked for this run is consumed (a later retry must not inherit it).
            cancelRequest.compareAndSet(id, null)
            if (outcome == null) {
                if (flyingOwners.value.isNotEmpty()) {
                    _board.update { it.requeued(overlayChoice) }
                    continue // takeoff: same video again once landed
                }
                // Heat: give up on a video that heats the phone again and again.
                val aborts = (thermalAborts[uri] ?: 0) + 1
                thermalAborts[uri] = aborts
                if (aborts < MAX_THERMAL_ABORTS) {
                    _board.update { it.requeued(overlayChoice) }
                    continue
                }
                Log.w(TAG, "${video.name}: abandoned, the phone overheats")
                outcome = FinishedOutcome.Failed(FailReason.OVERHEAT, null)
            }
            synchronized(pending) {
                pending.remove(uri)
                _board.update { it.finished(outcome, overlayChoice, now()) }
            }
            index++
            when (outcome) {
                is FinishedOutcome.Converted -> {
                    converted++
                    if (outcome.originalDeleted) saved += video.sizeBytes - outcome.afterBytes
                }
                is FinishedOutcome.Kept -> kept++
                is FinishedOutcome.Failed -> failed++
            }
        }
        _state.value = TranscodeState.Done(converted, kept, failed, saved)
    }

    /**
     * Null if abandoned because the drone took off or the phone got hot. A cancel asked by the
     * user ends as [FailReason.CANCELLED].
     */
    private suspend fun runInterruptible(video: DroneVideo, overlay: Boolean?, paused: StateFlow<Boolean>): FinishedOutcome? =
        coroutineScope {
            val id = video.uri.toString()
            // Set once the copy is published: a cancel arriving meanwhile must not lose it (the
            // video would be redone or reported cancelled although converted).
            val published = AtomicReference<FinishedOutcome?>(null)
            val work = async { process(video, overlay, published) }
            val byUser = AtomicBoolean(false)
            val watcher = launch {
                // true: cancelled by the user; false: takeoff or heat.
                byUser.set(merge(paused.filter { it }.map { false }, cancelRequest.filter { it == id }.map { true }).first())
                work.cancel()
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                ensureActive() // our own cancellation propagates
                published.get() ?: if (byUser.get()) FinishedOutcome.Failed(FailReason.CANCELLED, null) else null
            } finally {
                watcher.cancel()
            }
        }

    private fun now() = clock()

    private fun stage(stage: TranscodeStage) {
        Log.i(TAG, "stage $stage")
        _board.update { it.stage(stage, now()) }
    }

    @OptIn(UnstableApi::class)
    private suspend fun process(video: DroneVideo, overlayChoice: Boolean?, published: AtomicReference<FinishedOutcome?>): FinishedOutcome {
        val tmp = File(File(context.cacheDir, TMP_DIR).apply { mkdirs() }, "${video.baseName}.mp4")
        try {
            val pilot = pilot.value ?: PilotSettings()
            val codec = pilot.transcodeCodec.takeIf { transcoder.isSupported(it) } ?: TranscodeCodec.HEVC
            val bitrate = video.bitrate.takeIf { it > 0 } ?: DEFAULT_SOURCE_BITRATE
            val onProgress: (Float) -> Unit = { p ->
                _state.update { if (it is TranscodeState.Running) it.copy(progress = p) else it }
                _board.update { it.progress(p, now()) }
            }
            val onStall: (Boolean) -> Unit = { s -> _board.update { it.stalled(s) } }
            val wantOverlay = overlayChoice ?: pilot.overlayOnTranscode
            Log.i(
                TAG,
                "${video.name}: start, $codec, overlay asked=$wantOverlay (" +
                    (if (overlayChoice != null) "gallery choice" else "setting overlayOnTranscode=${pilot.overlayOnTranscode}") + ")",
            )
            val built = if (wantOverlay) buildOverlay(video, pilot) else null
            built?.let { b -> _board.update { it.overlay(b.status) } }
            val overlays = built?.overlays.orEmpty()
            stage(TranscodeStage.EXPORTING)
            // The overlay must never cost the re-encoding: on a failed, stalled export or output
            // check with it, try again without it.
            val overlayOk = overlays.isNotEmpty() && try {
                transcoder.transcode(video.uri, tmp, codec, bitrate, overlays, onStall, onProgress)
                stage(TranscodeStage.VERIFYING)
                transcoder.verify(tmp, codec, video.durationMs).also {
                    if (!it) Log.w(TAG, "${video.name}: output check failed with overlay, retrying without it")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sourceExists(video)) return FinishedOutcome.Failed(FailReason.SOURCE_MISSING, null)
                Log.w(TAG, "${video.name}: transcoding with overlay failed, retrying without it", e)
                false
            }
            if (overlays.isNotEmpty() && !overlayOk) {
                tmp.delete()
                _board.update { it.overlay(OverlayStatus.DROPPED) }
                _state.update { if (it is TranscodeState.Running) it.copy(progress = 0f) else it }
                Log.i(TAG, "${video.name}: overlay DROPPED, plain re-encoding")
                stage(TranscodeStage.EXPORTING)
            }
            if (!overlayOk) {
                try {
                    transcoder.transcode(video.uri, tmp, codec, bitrate, emptyList(), onStall, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TranscodeStalledException) {
                    return FinishedOutcome.Failed(FailReason.STALLED, e.message)
                } catch (e: Exception) {
                    Log.w(TAG, "${video.name}: transcoding failed", e)
                    return if (!sourceExists(video)) {
                        FinishedOutcome.Failed(FailReason.SOURCE_MISSING, null)
                    } else {
                        FinishedOutcome.Failed(FailReason.EXPORT_ERROR, e.message)
                    }
                }
                stage(TranscodeStage.VERIFYING)
                if (!transcoder.verify(tmp, codec, video.durationMs)) {
                    Log.w(TAG, "${video.name}: output check failed")
                    return FinishedOutcome.Failed(FailReason.CHECK_FAILED, null)
                }
            }
            val after = tmp.length()
            if (after >= video.sizeBytes) {
                Log.i(TAG, "${video.name}: $after >= ${video.sizeBytes}, original kept")
                return FinishedOutcome.Kept(after)
            }
            // Deleted by the user meanwhile: publishing its copy would bring it back.
            if (!sourceExists(video)) {
                Log.w(TAG, "${video.name}: original deleted during the re-encoding, copy dropped")
                return FinishedOutcome.Failed(FailReason.SOURCE_MISSING, null)
            }
            stage(TranscodeStage.PUBLISHING)
            // Checked copy: publishing and deleting the original must not be split by a takeoff
            // (a published copy then redone would duplicate it).
            return withContext(NonCancellable) {
                val result = try {
                    library.publish(tmp, video, codec.suffix)
                    val deleted = !pilot.keepOriginal && library.delete(video.uri)
                    Log.i(TAG, "${video.name}: ${video.sizeBytes} -> $after bytes ($codec), original deleted=$deleted")
                    FinishedOutcome.Converted(after, deleted)
                } catch (e: Exception) {
                    Log.w(TAG, "${video.name}: publication failed", e)
                    FinishedOutcome.Failed(FailReason.PUBLISH_ERROR, e.message)
                }
                // Kept even if this coroutine was cancelled meanwhile (see runInterruptible).
                published.set(result)
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${video.name}: transcoding failed", e)
            return FinishedOutcome.Failed(FailReason.EXPORT_ERROR, e.message)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun sourceExists(video: DroneVideo): Boolean = library.get(video.uri) != null

    /** Telemetry overlay of [video], `null` overlay (plain re-encoding) if it cannot be built. */
    @OptIn(UnstableApi::class)
    private suspend fun buildOverlay(video: DroneVideo, pilot: PilotSettings): TelemetryOverlayFactory.Built = try {
        overlays.build(video, pilot) { stage(TranscodeStage.WAITING_FLIGHT_LOG) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${video.name}: overlay UNAVAILABLE, plain re-encoding", e)
        TelemetryOverlayFactory.Built(emptyList(), OverlayStatus.UNAVAILABLE)
    }

    internal companion object {
        /**
         * Thermal pause with hysteresis: held from HOT until the phone is back to NORMAL
         * (resuming at WARM would heat it up again straight away).
         */
        fun thermalHold(hold: Boolean, heat: PhoneHeat): Boolean = when (heat) {
            PhoneHeat.HOT -> true
            PhoneHeat.NORMAL -> false
            PhoneHeat.WARM -> hold
        }

        /** The H.264 [videos] that have no re-encoded copy yet (`<base>-hevc`, `<base>-av1`). */
        fun <V> untranscoded(videos: List<V>, isH264: (V) -> Boolean, baseName: (V) -> String): List<V> {
            val converted = videos.filter { !isH264(it) }.map(baseName).toSet()
            return videos.filter { v ->
                isH264(v) && TranscodeCodec.entries.none { "${baseName(v)}-${it.suffix}" in converted }
            }
        }

        private const val TAG = "Transcode"
        private const val TMP_DIR = "transcode"
        private const val MAX_THERMAL_ABORTS = 3
        /** The drone's "auto" bitrate is about this much. */
        private const val DEFAULT_SOURCE_BITRATE = 2_500_000
    }
}
