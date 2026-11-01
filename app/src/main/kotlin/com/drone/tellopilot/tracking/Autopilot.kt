package com.drone.tellopilot.tracking

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.drone.tello.control.LinkState
import com.drone.tello.control.Mission
import com.drone.tello.control.MissionAbort
import com.drone.tello.control.MissionRunner
import com.drone.tello.control.MissionTick
import com.drone.tello.control.TargetObservation
import com.drone.tello.control.TargetTracker
import com.drone.tello.control.TelloController
import com.drone.tello.control.TrackerTick
import com.drone.tello.control.TrackingMode
import com.drone.tello.control.TrackingTarget
import com.drone.tello.protocol.StickInput
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.data.RealtimeThreads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AutopilotState {
    data object Idle : AutopilotState
    data class RunningMission(val name: String, val step: Int, val total: Int) : AutopilotState

    /** [target]: box of the followed target (normalised), null while searching or hidden. */
    data class Tracking(val mode: TrackingMode, val target: RectF?) : AutopilotState
}

/** Why an automatic flight ended (the drone hovers in every case). */
sealed interface AutopilotEnd {
    data object MissionDone : AutopilotEnd
    data class MissionAborted(val reason: MissionAbort) : AutopilotEnd
    data object PilotTookOver : AutopilotEnd
    data object TargetLost : AutopilotEnd
    data object Stopped : AutopilotEnd
    data object Failed : AutopilotEnd
}

/**
 * Runs automatic flights (missions, target tracking) by writing the drone's sticks at 50 Hz.
 * Safety model: the pilot is never locked out — any stick movement ends the automatic flight
 * (checked by the flight screen before its own sticks are sent), and every end leaves the
 * drone hovering with neutral sticks. Each flight owns a token: once stopped, it can never
 * write a stick again (no automatic stick after the pilot's own). Controllers are pure (core
 * module); this class only feeds them telemetry / detections and applies their output.
 */
@Singleton
class Autopilot @Inject constructor(private val session: DroneSession) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loop = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(
        SupervisorJob() + loop + CoroutineExceptionHandler { _, e -> Log.e(TAG, "automatic flight", e) },
    )
    private var job: Job? = null

    /** Identity of the current automatic flight; null when none. */
    private val current = AtomicReference<Any?>(null)

    private val _state = MutableStateFlow<AutopilotState>(AutopilotState.Idle)
    val state: StateFlow<AutopilotState> = _state.asStateFlow()

    private val _ends = Channel<AutopilotEnd>(Channel.BUFFERED)
    val ends: Flow<AutopilotEnd> = _ends.receiveAsFlow()

    /** Checked from the touch / gamepad handlers: cheap, no locking. */
    @Volatile var active: Boolean = false
        private set

    fun startMission(mission: Mission) = start { token -> runMission(token, mission) }

    fun startTracking(mode: TrackingMode, target: TrackingTarget, orbitDirection: Int) =
        start { token -> runTracking(token, mode, target, orbitDirection) }

    /** Ends the automatic flight (pilot took over, landing, screen left…); the drone hovers. */
    @Synchronized
    fun stop(end: AutopilotEnd = AutopilotEnd.Stopped) {
        if (current.getAndSet(null) == null) return
        active = false
        job?.cancel()
        // Neutral now, and only now: the pilot's own sticks, sent right after by the caller,
        // must never be overwritten by a late automatic write.
        controller()?.setSticks(StickInput.Neutral)
        _state.value = AutopilotState.Idle
        _ends.trySend(end)
    }

    @Synchronized
    private fun start(block: suspend CoroutineScope.(token: Any) -> AutopilotEnd) {
        stop()
        val token = Any()
        current.set(token)
        active = true
        job = scope.launch {
            val end = try {
                block(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "automatic flight failed", e)
                AutopilotEnd.Failed
            }
            finish(token, end)
        }
    }

    /** Natural end of [token]'s flight (not a [stop]): hover and report. */
    @Synchronized
    private fun finish(token: Any, end: AutopilotEnd) {
        if (!current.compareAndSet(token, null)) return
        active = false
        controller()?.setSticks(StickInput.Neutral)
        _state.value = AutopilotState.Idle
        _ends.trySend(end)
    }

    /** Writes automatic sticks only while [token] is the running flight (same lock as [stop]). */
    @Synchronized
    private fun apply(token: Any, sticks: StickInput) {
        if (current.get() === token) controller()?.setSticks(sticks)
    }

    @Synchronized
    private fun publish(token: Any, state: AutopilotState) {
        if (current.get() === token) _state.value = state
    }

    private fun controller(): TelloController? = session.controller.value

    /**
     * Shared safety checks: live link, fresh flight data, flying, battery. Fresh = a new
     * flight data packet within [STALE_MS]: a frozen StateFlow value (it never re-emits an
     * equal value) must not pass for live telemetry.
     */
    private class Freshness {
        private var lastSamples = -1L
        private var lastSampleAt = 0L
        private var fedSamples = -1L

        fun check(controller: TelloController?, now: Long): MissionAbort? {
            if (controller == null || controller.link.value != LinkState.CONNECTED) return MissionAbort.LINK_LOST
            val samples = controller.flightSamples
            if (samples != lastSamples) {
                lastSamples = samples
                lastSampleAt = now
            }
            if (now - lastSampleAt > STALE_MS) return MissionAbort.LINK_LOST
            val flight = controller.telemetry.value.flight ?: return MissionAbort.LINK_LOST
            return when {
                !flight.flying -> MissionAbort.NOT_FLYING
                flight.batteryCritical -> MissionAbort.LOW_BATTERY
                else -> null
            }
        }

        /** True once per new flight data packet. */
        fun isNew(controller: TelloController): Boolean {
            val samples = controller.flightSamples
            val fresh = samples != fedSamples
            fedSamples = samples
            return fresh
        }
    }

    private suspend fun CoroutineScope.runMission(token: Any, mission: Mission): AutopilotEnd {
        val runner = MissionRunner(mission)
        val total = mission.steps.size
        val freshness = Freshness()
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val controller = controller()
            freshness.check(controller, now)?.let { return AutopilotEnd.MissionAborted(it) }
            // Only a new flight data packet counts as telemetry for the runner.
            if (controller != null && freshness.isNew(controller)) {
                controller.telemetry.value.flight?.let { runner.onTelemetry(it, now) }
            }
            when (val tick = runner.tick(now)) {
                is MissionTick.Running -> {
                    publish(token, AutopilotState.RunningMission(mission.name, tick.stepIndex + 1, total))
                    apply(token, tick.sticks)
                }
                MissionTick.Finished -> return AutopilotEnd.MissionDone
                is MissionTick.Aborted -> return AutopilotEnd.MissionAborted(tick.reason)
            }
            delay(TICK_MS)
        }
        return AutopilotEnd.Stopped
    }

    private suspend fun CoroutineScope.runTracking(
        token: Any,
        mode: TrackingMode,
        target: TrackingTarget,
        orbitDirection: Int,
    ): AutopilotEnd {
        val tracker = TargetTracker(mode, orbitDirection = orbitDirection)
        val latest = AtomicReference<Pair<List<Detection>, Long>?>(null)
        val detector = TargetDetector(target)
        var lastKeyFrameRequest = 0L
        val decoder = try {
            AnalysisDecoder(
                onImage = { image, release ->
                    val at = SystemClock.elapsedRealtime()
                    detector.process(image) { detections ->
                        release()
                        latest.set(detections to at)
                    }
                },
                requestKeyFrame = {
                    // The analysis decoder lost its place: ask the drone for a key frame (1/s at most).
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastKeyFrameRequest >= KEY_FRAME_INTERVAL_MS) {
                        lastKeyFrameRequest = now
                        controller()?.let { c -> scope.launch(RealtimeThreads.control) { c.requestKeyFrame() } }
                    }
                },
            )
        } catch (e: Exception) {
            detector.close()
            throw e
        }
        session.attachAnalysis(decoder)
        controller()?.let { c -> launch(RealtimeThreads.control) { c.requestKeyFrame() } }
        val freshness = Freshness()
        var lockedId: Int? = null
        var lastConsumed = -1L
        var box: RectF? = null
        try {
            publish(token, AutopilotState.Tracking(mode, null))
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val controller = controller()
                freshness.check(controller, now)?.let { return AutopilotEnd.MissionAborted(it) }
                val sample = latest.get()
                if (sample != null && sample.second != lastConsumed) {
                    lastConsumed = sample.second
                    val chosen = choose(sample.first, lockedId)
                    if (chosen != null) {
                        lockedId = chosen.trackingId ?: lockedId
                        box = chosen.box
                        tracker.observe(
                            TargetObservation(chosen.box.centerX(), chosen.box.centerY(), chosen.box.height()),
                            sample.second,
                        )
                    }
                }
                when (val tick = tracker.tick(now)) {
                    TrackerTick.Lost -> return AutopilotEnd.TargetLost
                    is TrackerTick.Tracking -> {
                        if (tick.target == null) box = null
                        publish(token, AutopilotState.Tracking(mode, box))
                        apply(token, limitTrackingHeight(tick.sticks, controller?.telemetry?.value?.flight?.heightMeters))
                    }
                }
                delay(TICK_MS)
            }
            return AutopilotEnd.Stopped
        } finally {
            session.detachAnalysis(decoder)
            // Detector first (its pending analysis fails and releases the image), then the
            // decoder; closing waits a little, so it runs off the control loop.
            Thread({ detector.close(); decoder.close() }, "tello-analysis-close").start()
        }
    }

    /**
     * The locked target only: if it is out of sight, nobody else is taken instead (the drone
     * would fly toward a stranger); the tracker hovers, then reports the target lost. Before
     * any lock, the biggest detection (closest to the camera) is chosen.
     */
    private fun choose(detections: List<Detection>, lockedId: Int?): Detection? {
        if (detections.isEmpty()) return null
        if (lockedId != null) return detections.firstOrNull { it.trackingId == lockedId }
        return detections.maxByOrNull { it.box.width() * it.box.height() }
    }

    private companion object {
        const val TAG = "Autopilot"
        const val TICK_MS = 20L
        /** Flight data arrives at ~10 Hz: 700 ms without any is a frozen link. */
        const val STALE_MS = 700L
        const val KEY_FRAME_INTERVAL_MS = 1_000L
    }
}

private const val MIN_TRACKING_HEIGHT = 1.0f
private const val MAX_TRACKING_HEIGHT = 5.0f

/**
 * The tracker centres the target vertically: a person sitting down or a child must not
 * bring the drone down to the ground, a target up high must not take it up forever.
 * Unknown height: no vertical movement at all.
 */
internal fun limitTrackingHeight(sticks: StickInput, height: Float?): StickInput {
    if (height == null) return sticks.copy(throttle = 0f)
    val throttle = when {
        sticks.throttle < 0 && height <= MIN_TRACKING_HEIGHT -> 0f
        sticks.throttle > 0 && height >= MAX_TRACKING_HEIGHT -> 0f
        else -> sticks.throttle
    }
    return sticks.copy(throttle = throttle)
}
