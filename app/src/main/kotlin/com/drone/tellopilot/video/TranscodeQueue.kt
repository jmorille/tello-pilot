package com.drone.tellopilot.video

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextureOverlay
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
import kotlinx.coroutines.cancelChildren
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

    private fun enqueue(uris: List<Uri>, overlay: Boolean? = null) {
        synchronized(pending) {
            // A choice made in the gallery is never reset to "as the setting says" by a bulk enqueue.
            uris.forEach { if (overlay != null || it !in pending) pending[it] = overlay }
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
                launch {
                    paused.collect { p -> _state.update { if (it is TranscodeState.Running) it.copy(paused = p) else it } }
                }
                drainLoop(paused)
                // The UI collector and the stateIn sharing coroutine never end by themselves.
                coroutineContext.cancelChildren()
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
            val (uri, overlayChoice) = synchronized(pending) { pending.entries.firstOrNull()?.toPair() } ?: break
            val total = index + synchronized(pending) { pending.size }
            val video = library.get(uri)
            if (video == null || !video.isH264) {
                synchronized(pending) { pending.remove(uri) }
                continue
            }
            _state.value = TranscodeState.Running(video.name, index + 1, total, 0f, paused.value)
            paused.first { !it }
            var outcome = runInterruptible(video, overlayChoice, paused)
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
    private suspend fun runInterruptible(video: DroneVideo, overlay: Boolean?, paused: StateFlow<Boolean>): Outcome? = coroutineScope {
        val work = async { process(video, overlay) }
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

    @OptIn(UnstableApi::class)
    private suspend fun process(video: DroneVideo, overlayChoice: Boolean?): Outcome {
        val tmp = File(File(context.cacheDir, TMP_DIR).apply { mkdirs() }, "${video.baseName}.mp4")
        try {
            val pilot = pilot.value ?: PilotSettings()
            val codec = pilot.transcodeCodec.takeIf { transcoder.isSupported(it) } ?: TranscodeCodec.HEVC
            val bitrate = video.bitrate.takeIf { it > 0 } ?: DEFAULT_SOURCE_BITRATE
            val onProgress: (Float) -> Unit = { p -> _state.update { if (it is TranscodeState.Running) it.copy(progress = p) else it } }
            val overlay = if (overlayChoice ?: pilot.overlayOnTranscode) buildOverlay(video, pilot) else null
            // The overlay must never cost the re-encoding: on a failed export or output check
            // with it, try again without it.
            val overlayOk = overlay != null && try {
                transcoder.transcode(video.uri, tmp, codec, bitrate, overlay, onProgress)
                transcoder.verify(tmp, codec, video.durationMs).also {
                    if (!it) Log.w(TAG, "${video.name}: output check failed with overlay, retrying without it")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${video.name}: transcoding with overlay failed, retrying without it", e)
                false
            }
            if (!overlayOk) {
                if (overlay != null) {
                    tmp.delete()
                    onProgress(0f)
                }
                transcoder.transcode(video.uri, tmp, codec, bitrate, null, onProgress)
            }
            if (!overlayOk && !transcoder.verify(tmp, codec, video.durationMs)) {
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

    /** Telemetry overlay of [video], `null` (plain re-encoding) if it cannot be built. */
    @OptIn(UnstableApi::class)
    private suspend fun buildOverlay(video: DroneVideo, pilot: PilotSettings): TextureOverlay? = try {
        overlays.build(video, pilot.units)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${video.name}: overlay unavailable, plain re-encoding", e)
        null
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
