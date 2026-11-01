package com.drone.tellopilot.data

import android.util.Log
import com.drone.tello.control.DroneEvent
import com.drone.tello.control.ImuCalibrationState
import com.drone.tello.control.LinkState
import com.drone.tello.control.ManeuverState
import com.drone.tello.control.WifiChangeState
import kotlinx.coroutines.flow.emptyFlow
import com.drone.tello.control.Telemetry
import com.drone.tello.control.TelloController
import com.drone.tello.control.VideoFrameGate
import com.drone.tello.protocol.VideoFrameAssembler
import com.drone.tellopilot.video.FrameSink
import com.drone.tellopilot.video.VideoRecorder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires Wi-Fi → sockets → [TelloController] and the video path.
 *
 * Video path (single thread, zero hand-off): socket.receive → assembler → gate → decoder input.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DroneSession internal constructor(
    private val wifi: WifiNetworkMonitor,
    private val settings: SettingsRepository,
    private val recorder: VideoRecorder,
    private val photoStore: PhotoStore,
    private val hints: PerformanceHints,
    private val flightRecorder: com.drone.tellopilot.flightlog.FlightRecorder,
    /** Control loop and commands: the realtime control thread (a test dispatcher in the JVM tests). */
    private val controlDispatcher: CoroutineDispatcher,
    /** Opens the UDP sockets on the drone's network ([DroneSockets.open]). */
    private val openSockets: (android.net.Network) -> DroneSockets,
) {
    @Inject constructor(
        wifi: WifiNetworkMonitor,
        settings: SettingsRepository,
        recorder: VideoRecorder,
        photoStore: PhotoStore,
        hints: PerformanceHints,
        flightRecorder: com.drone.tellopilot.flightlog.FlightRecorder,
    ) : this(wifi, settings, recorder, photoStore, hints, flightRecorder, RealtimeThreads.control, { DroneSockets.open(it) })

    private val _controller = MutableStateFlow<TelloController?>(null)
    val controller: StateFlow<TelloController?> = _controller.asStateFlow()

    private val _wifiState = MutableStateFlow<WifiState>(WifiState.Off)
    /** Drone network, another Wi-Fi (Android switched back home) or no Wi-Fi. */
    val wifiState: StateFlow<WifiState> = _wifiState.asStateFlow()

    /** A drone network request (system dialog) is pending or held. */
    val droneWifiRequested: StateFlow<Boolean> get() = wifi.requesting

    /** Asks Android for the drone's Wi-Fi (`TELLO-…`), see [WifiNetworkMonitor.requestDroneNetwork]. */
    fun connectToDrone() = wifi.requestDroneNetwork()

    /** The drone's Wi-Fi was lost during this session (and not found again yet). */
    @Volatile private var droneWifiLost = false

    /** The foreground request for the current loss was already made (no dialog loop). */
    @Volatile private var foregroundRetried = false

    /** The flight screen is visible (a request made now shows Android's dialog). */
    @Volatile private var inForeground = false

    fun onBackground() {
        inForeground = false
    }

    /**
     * The app comes back to the foreground: Android only grants a drone network request to a
     * foreground app, so the one made while in the background was refused — ask again once.
     */
    fun onForeground() {
        inForeground = true
        // Once per loss: Android's dialog itself pauses and resumes the activity, and a
        // cancelled or failed request must not reopen it (the "connect" button stays there).
        // A request stuck for a long time (no answer from Android) does not block it.
        if (droneWifiLost && !foregroundRetried && (!wifi.requesting.value || wifi.requestStale()) &&
            _wifiState.value !is WifiState.Drone
        ) {
            foregroundRetried = true
            Log.i(TAG, "back in the foreground without the drone Wi-Fi: requesting it")
            wifi.requestDroneNetwork()
        }
    }

    /** The drone's Wi-Fi name / password were changed from the app. */
    fun rememberDroneWifi(ssid: String, password: String) = wifi.rememberDroneNetwork(ssid, password)

    val link: Flow<LinkState> = _controller.flatMapLatest { it?.link ?: flowOf(LinkState.IDLE) }
    val telemetry: Flow<Telemetry> = _controller.flatMapLatest { it?.telemetry ?: flowOf(Telemetry()) }

    /** Latest flight data of the current controller (null when unknown). */
    val lastFlight: com.drone.tello.protocol.FlightData? get() = _controller.value?.telemetry?.value?.flight

    val maneuvers: Flow<ManeuverState> = _controller.flatMapLatest { it?.maneuvers ?: flowOf(ManeuverState()) }
    val imuCalibration: Flow<ImuCalibrationState> =
        _controller.flatMapLatest { it?.imuCalibration ?: flowOf(ImuCalibrationState.Idle) }
    val wifiChange: Flow<WifiChangeState> = _controller.flatMapLatest { it?.wifiChangeState ?: flowOf(WifiChangeState.Idle) }
    val events: Flow<DroneEvent> = _controller.flatMapLatest { it?.events ?: emptyFlow() }

    /** Photo transfer progress (0..1) or `null` when no photo is in progress. */
    val photoProgress: Flow<Float?> = _controller.flatMapLatest { it?.photoProgress ?: flowOf(null) }

    private val _photoSaved = MutableSharedFlow<Result<Unit>>(extraBufferCapacity = 4)
    /** One event per photo received from the drone (saved or failed). */
    val photoSaved: SharedFlow<Result<Unit>> = _photoSaved.asSharedFlow()

    private val _videoActive = MutableStateFlow(false)
    val videoActive: StateFlow<Boolean> = _videoActive.asStateFlow()

    private val restart = MutableStateFlow(0)

    /** Reopens the sockets (e.g. once the local network permission is granted). */
    fun reconnect() {
        restart.value++
    }
    private val sink = AtomicReference<FrameSink?>(null)

    /** Second consumer of the stream, for target tracking (never slows the live view). */
    private val analysis = AtomicReference<com.drone.tellopilot.tracking.AnalysisDecoder?>(null)

    fun attachAnalysis(decoder: com.drone.tellopilot.tracking.AnalysisDecoder) = analysis.set(decoder)

    fun detachAnalysis(decoder: com.drone.tellopilot.tracking.AnalysisDecoder) {
        analysis.compareAndSet(decoder, null)
    }
    private val resync = AtomicBoolean(true)

    /** Attaches the decoder (when the video surface exists). The stream restarts on a key frame. */
    fun attachVideo(frameSink: FrameSink) {
        frameSink.setLatencyListener(hints::logDecodeLatency)
        hints.addThreads(*frameSink.threadIds)
        sink.set(frameSink)
        resync.set(true)
    }

    fun detachVideo(frameSink: FrameSink) {
        frameSink.setLatencyListener(null)
        hints.removeThreads(*frameSink.threadIds)
        sink.compareAndSet(frameSink, null)
        _videoActive.value = false
    }

    /** Runs until cancelled; follows Wi-Fi changes and reconnects automatically. */
    suspend fun run() {
        val instance = runs.incrementAndGet()
        Log.i(TAG, "session run #$instance start (concurrent runs: ${active.incrementAndGet()})")
        try {
            coroutineScope {
                // Black box: polls the live telemetry on an I/O thread (never the realtime ones).
                launch {
                    flightRecorder.record {
                        _controller.value?.takeIf { it.isConnected }?.telemetry?.value
                    }
                }
                runSession()
            }
        } finally {
            Log.i(TAG, "session run #$instance end (concurrent runs: ${active.decrementAndGet()})")
        }
    }

    private val runs = java.util.concurrent.atomic.AtomicInteger()
    private val active = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun runSession() {
        val wifiLock = wifi.createLowLatencyLock()
        try {
            wifiLock.acquire()
            var onDrone = false
            combine(wifi.wifiState, restart) { state, _ -> state }.collectLatest { state ->
                _wifiState.value = state
                Log.i(TAG, "Wi-Fi: $state")
                val network = (state as? WifiState.Drone)?.network
                if (network == null) {
                    // Android left the drone's Wi-Fi during the session (app in background,
                    // "better" network): ask for it back once per loss instead of waiting
                    // forever (no dialog loop if the pilot cancels: the button stays there).
                    if (onDrone) {
                        onDrone = false
                        droneWifiLost = true
                        // Lost in the foreground: the request below already shows the dialog,
                        // coming back from it must not open a second one.
                        foregroundRetried = inForeground
                        Log.i(TAG, "drone Wi-Fi lost, requesting it back")
                        wifi.requestDroneNetwork()
                    }
                    return@collectLatest
                }
                onDrone = true
                droneWifiLost = false
                val sockets = try {
                    withContext(controlDispatcher) { openSockets(network) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cannot open sockets", e)
                    delay(RESTART_DELAY_MS)
                    restart.value++
                    return@collectLatest
                }
                Log.i(TAG, "sockets open: ${sockets.stats}")
                try {
                    val controller = TelloController(sockets)
                    controller.diagnostics = { Log.i(TAG, it) }
                    _controller.value = controller
                    coroutineScope {
                        // Diagnostics: link state changes; traffic counters every 5 s with `setprop log.tag.DroneSession DEBUG`.
                        launch { controller.link.collect { Log.i(TAG, "link $it (${sockets.stats})") } }
                        if (Log.isLoggable(TAG, Log.DEBUG)) launch {
                            while (true) {
                                delay(5_000)
                                Log.d(TAG, "traffic: ${sockets.stats}, link ${controller.link.value}")
                            }
                        }
                        launch(controlDispatcher) { controller.run() }
                        launch(RealtimeThreads.video) { receiveVideo(sockets, controller) }
                        // Drone settings: stored in the controller, sent on connect and on change.
                        launch(controlDispatcher) {
                            try {
                                settings.settings.map { it.drone }.distinctUntilChanged()
                                    .collect { controller.applyDroneSettings(it) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Settings must never take the flight session down.
                                Log.w(TAG, "drone settings unavailable", e)
                            }
                        }
                        launch {
                            controller.photos.collect { jpeg ->
                                val result = try {
                                    photoStore.save(jpeg)
                                    Result.success(Unit)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                                result.exceptionOrNull()?.let { Log.w(TAG, "cannot save photo", it) }
                                _photoSaved.tryEmit(result)
                            }
                        }
                        // On cancellation, close the sockets at once: unblocks the pending
                        // receive() calls instead of waiting for their 250 ms timeout.
                        launch {
                            try {
                                awaitCancellation()
                            } finally {
                                sockets.close()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never crash the app because of the network: log and restart the session.
                    Log.w(TAG, "session failed, restarting", e)
                } finally {
                    _controller.value = null
                    _videoActive.value = false
                    sockets.close()
                }
                delay(RESTART_DELAY_MS)
                restart.value++
            }
        } finally {
            if (wifiLock.isHeld) wifiLock.release()
            wifi.releaseDroneNetwork()
            _wifiState.value = WifiState.Off
        }
    }

    private suspend fun receiveVideo(sockets: DroneSockets, controller: TelloController) = coroutineScope {
        val tid = android.os.Process.myTid()
        hints.addThreads(tid)
        try {
            receiveVideoLoop(sockets, controller)
        } finally {
            hints.removeThreads(tid)
        }
    }

    private suspend fun receiveVideoLoop(sockets: DroneSockets, controller: TelloController) = coroutineScope {
        val assembler = VideoFrameAssembler()
        val gate = VideoFrameGate()
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        /** CPU time spent on the datagrams of the frame being assembled (performance hints). */
        var frameWork = 0L
        while (isActive) {
            try {
                packet.setLength(buffer.size)
                sockets.video.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (sockets.video.isClosed) break
                delay(DroneSockets.RECEIVE_ERROR_BACKOFF_MS)
                continue
            }
            val workStart = System.nanoTime()
            if (resync.getAndSet(false)) gate.reset()

            var decision = gate.onResult(assembler.push(buffer, packet.length))
            val frame = decision.frame
            if (frame != null) {
                recorder.onFrame(frame) // never blocks (bounded queue, dedicated writer thread)
                analysis.get()?.offer(frame) // never blocks either
                val target = sink.get()
                when {
                    // No surface: the decoder will need a key frame — unless recording, which
                    // must keep the full stream (settings open / app in background).
                    target == null -> if (!recorder.isActive && analysis.get() == null) gate.reset()
                    target.decode(frame) -> _videoActive.value = true
                    else -> decision = gate.onDecoderBacklog()
                }
                hints.reportFrameWork(frameWork + (System.nanoTime() - workStart))
                frameWork = 0
            } else {
                frameWork += System.nanoTime() - workStart
            }
            if (decision.requestKeyFrame && (sink.get() != null || recorder.isActive || analysis.get() != null)) {
                launch(controlDispatcher) { controller.requestKeyFrame() }
            }
        }
    }

    private companion object {
        const val TAG = "DroneSession"
        const val RESTART_DELAY_MS = 1_000L
    }
}
