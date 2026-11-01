package com.drone.tellopilot.data

import android.util.Log
import com.drone.tello.control.LinkState
import com.drone.tello.control.Telemetry
import com.drone.tello.control.TelloController
import com.drone.tello.control.VideoFrameGate
import com.drone.tello.protocol.VideoFrameAssembler
import com.drone.tellopilot.video.FrameSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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
class DroneSession @Inject constructor(
    private val wifi: WifiNetworkMonitor,
) {
    private val _controller = MutableStateFlow<TelloController?>(null)
    val controller: StateFlow<TelloController?> = _controller.asStateFlow()

    private val _wifiAvailable = MutableStateFlow(false)
    val wifiAvailable: StateFlow<Boolean> = _wifiAvailable.asStateFlow()

    val link: Flow<LinkState> = _controller.flatMapLatest { it?.link ?: flowOf(LinkState.IDLE) }
    val telemetry: Flow<Telemetry> = _controller.flatMapLatest { it?.telemetry ?: flowOf(Telemetry()) }

    private val _videoActive = MutableStateFlow(false)
    val videoActive: StateFlow<Boolean> = _videoActive.asStateFlow()

    private val restart = MutableStateFlow(0)
    private val sink = AtomicReference<FrameSink?>(null)
    private val resync = AtomicBoolean(true)

    /** Attaches the decoder (when the video surface exists). The stream restarts on a key frame. */
    fun attachVideo(frameSink: FrameSink) {
        sink.set(frameSink)
        resync.set(true)
    }

    fun detachVideo(frameSink: FrameSink) {
        sink.compareAndSet(frameSink, null)
        _videoActive.value = false
    }

    /** Runs until cancelled; follows Wi-Fi changes and reconnects automatically. */
    suspend fun run() {
        val wifiLock = wifi.createLowLatencyLock()
        try {
            wifiLock.acquire()
            combine(wifi.wifiNetwork, restart) { network, _ -> network }.collectLatest { network ->
                _wifiAvailable.value = network != null
                if (network == null) return@collectLatest
                val sockets = try {
                    withContext(RealtimeThreads.control) { DroneSockets.open(network) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cannot open sockets", e)
                    delay(RESTART_DELAY_MS)
                    restart.value++
                    return@collectLatest
                }
                try {
                    val controller = TelloController(sockets)
                    _controller.value = controller
                    coroutineScope {
                        launch(RealtimeThreads.control) { controller.run() }
                        launch(RealtimeThreads.video) { receiveVideo(sockets, controller) }
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
            _wifiAvailable.value = false
        }
    }

    private suspend fun receiveVideo(sockets: DroneSockets, controller: TelloController) = coroutineScope {
        val assembler = VideoFrameAssembler()
        val gate = VideoFrameGate()
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
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
            if (resync.getAndSet(false)) gate.reset()

            var decision = gate.onResult(assembler.push(buffer, packet.length))
            val frame = decision.frame
            if (frame != null) {
                val target = sink.get()
                when {
                    target == null -> gate.reset() // no surface: decoder will need a key frame
                    target.decode(frame) -> _videoActive.value = true
                    else -> decision = gate.onDecoderBacklog()
                }
            }
            if (decision.requestKeyFrame && sink.get() != null) {
                launch(RealtimeThreads.control) { controller.requestKeyFrame() }
            }
        }
    }

    private companion object {
        const val TAG = "DroneSession"
        const val RESTART_DELAY_MS = 1_000L
    }
}
