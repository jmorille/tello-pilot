package com.drone.tellopilot.video

import android.content.Context
import android.net.Uri
import android.util.Log
import com.drone.tello.control.PilotSettings
import com.drone.tello.control.TranscodeCodec
import com.drone.tellopilot.data.PhoneHeat
import com.drone.tellopilot.data.PhoneThermal
import com.drone.tellopilot.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
 * file is published and checked.
 */
@Singleton
class TranscodeQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: VideoLibrary,
    private val transcoder: VideoTranscoder,
    settings: SettingsRepository,
    recorder: VideoRecorder,
    private val thermal: PhoneThermal,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "transcode queue", e) },
    )
    private val pending = LinkedHashSet<Uri>()
    private var worker: Job? = null

    /** Flight screens currently reporting a flying drone (a closing screen cannot unpause another one). */
    private val flyingOwners = MutableStateFlow<Set<Any>>(emptySet())

    private val pilot: StateFlow<PilotSettings?> = settings.settings.map { it.pilot }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow<TranscodeState>(TranscodeState.Idle)
    val state: StateFlow<TranscodeState> = _state.asStateFlow()

    /** Work queued or in progress (the foreground service lives as long as this is true). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
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
        val thermalHold = thermal.heat.runningFold(false) { hold, heat ->
            when (heat) {
                PhoneHeat.HOT -> true
                PhoneHeat.NORMAL -> false
                PhoneHeat.WARM -> hold
            }
        }
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
            val videos = library.list()
            val converted = videos.filter { !it.isH264 }.map { it.baseName }.toSet()
            val todo = videos.filter { v ->
                v.isH264 && TranscodeCodec.entries.none { "${v.baseName}-${it.suffix}" in converted }
            }
            if (todo.isEmpty()) _state.value = TranscodeState.Done(0, 0, 0, 0) else enqueue(todo.map { it.uri })
        }
    }

    /** Re-encodes one video now (gallery), whatever the automatic setting. */
    fun enqueueVideo(uri: Uri) = enqueue(listOf(uri))

    private fun enqueue(uris: List<Uri>) {
        synchronized(pending) {
            pending.addAll(uris)
            if (worker?.isActive == true) return
            _busy.value = true
            worker = scope.launch { drain() }
        }
        TranscodeService.start(context)
    }

    private suspend fun drain() {
        var completed = false
        try {
            coroutineScope {
                val paused = pauseFlow().stateIn(this)
                val pauseUi = launch {
                    paused.collect { p -> _state.update { if (it is TranscodeState.Running) it.copy(paused = p) else it } }
                }
                drainLoop(paused)
                pauseUi.cancel()
            }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "queue stopped", e)
            synchronized(pending) { pending.clear() }
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
            val uri = synchronized(pending) { pending.firstOrNull() } ?: break
            val total = index + synchronized(pending) { pending.size }
            val video = library.get(uri)
            if (video == null || !video.isH264) {
                synchronized(pending) { pending.remove(uri) }
                continue
            }
            _state.value = TranscodeState.Running(video.name, index + 1, total, 0f, paused.value)
            paused.first { !it }
            var outcome = runInterruptible(video, paused)
            if (outcome == null) {
                if (flyingOwners.value.isNotEmpty()) continue // takeoff: same video again once landed
                // Heat: give up on a video that heats the phone again and again.
                val aborts = (thermalAborts[uri] ?: 0) + 1
                thermalAborts[uri] = aborts
                if (aborts < MAX_THERMAL_ABORTS) continue
                Log.w(TAG, "${video.name}: abandoned, the phone overheats")
                outcome = Outcome.Failed
            }
            synchronized(pending) { pending.remove(uri) }
            index++
            when (outcome) {
                is Outcome.Converted -> { converted++; saved += outcome.savedBytes }
                Outcome.Kept -> kept++
                Outcome.Failed -> failed++
            }
        }
        _state.value = TranscodeState.Done(converted, kept, failed, saved)
    }

    private sealed interface Outcome {
        data class Converted(val savedBytes: Long) : Outcome
        /** Not smaller than the original: the original stays, nothing is published. */
        data object Kept : Outcome
        data object Failed : Outcome
    }

    /** Null if abandoned because the drone took off or the phone got hot. */
    private suspend fun runInterruptible(video: DroneVideo, paused: StateFlow<Boolean>): Outcome? = coroutineScope {
        val work = async { process(video) }
        val watcher = launch { paused.first { it }; work.cancel() }
        try {
            work.await()
        } catch (e: CancellationException) {
            ensureActive() // our own cancellation propagates
            null
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun process(video: DroneVideo): Outcome {
        val tmp = File(File(context.cacheDir, TMP_DIR).apply { mkdirs() }, "${video.baseName}.mp4")
        try {
            val pilot = pilot.value ?: PilotSettings()
            val codec = pilot.transcodeCodec.takeIf { transcoder.isSupported(it) } ?: TranscodeCodec.HEVC
            transcoder.transcode(video.uri, tmp, codec, video.bitrate.takeIf { it > 0 } ?: DEFAULT_SOURCE_BITRATE) { p ->
                _state.update { if (it is TranscodeState.Running) it.copy(progress = p) else it }
            }
            if (!transcoder.verify(tmp, codec, video.durationMs)) {
                Log.w(TAG, "${video.name}: output check failed")
                return Outcome.Failed
            }
            if (tmp.length() >= video.sizeBytes) {
                Log.i(TAG, "${video.name}: ${tmp.length()} >= ${video.sizeBytes}, original kept")
                return Outcome.Kept
            }
            // Checked copy: publishing and deleting the original must not be split by a takeoff
            // (a published copy then redone would duplicate it).
            return withContext(NonCancellable) {
                library.publish(tmp, video, codec.suffix)
                val deleted = !pilot.keepOriginal && library.delete(video.uri)
                Log.i(TAG, "${video.name}: ${video.sizeBytes} -> ${tmp.length()} bytes ($codec), original deleted=$deleted")
                Outcome.Converted(if (deleted) video.sizeBytes - tmp.length() else 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${video.name}: transcoding failed", e)
            return Outcome.Failed
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "Transcode"
        const val TMP_DIR = "transcode"
        const val MAX_THERMAL_ABORTS = 3
        /** The drone's "auto" bitrate is about this much. */
        const val DEFAULT_SOURCE_BITRATE = 2_500_000
    }
}
