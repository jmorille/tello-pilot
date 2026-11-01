package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.DroneMessage
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.Handshake
import com.drone.tello.protocol.PacketCodec
import com.drone.tello.protocol.PacketType
import com.drone.tello.protocol.Payloads
import com.drone.tello.protocol.StickInput
import com.drone.tello.protocol.WifiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class LinkState { IDLE, CONNECTING, CONNECTED, RECONNECTING }

data class Telemetry(
    val flight: FlightData? = null,
    val wifi: WifiStatus? = null,
    val tooDark: Boolean = false,
    val firmware: String? = null,
)

data class ControlConfig(
    /** Keep-alive period of the stick stream (the drone expects ~50 Hz). */
    val stickIntervalMs: Long = 20,
    /** Minimum spacing between two stick packets when the user moves the sticks fast. */
    val stickMinIntervalMs: Long = 10,
    /** Period of `conn_req` while waiting for the drone. */
    val handshakeIntervalMs: Long = 500,
    /** Link is considered lost when nothing has been received for this long. */
    val linkTimeoutMs: Long = 1_000,
    /** Minimum spacing between two key-frame requests. */
    val keyFrameMinIntervalMs: Long = 100,
    /** Acknowledged commands are re-sent at this period until the drone answers… */
    val retryIntervalMs: Long = 50,
    /** …at most this many extra times (TelloMe: 5 x 50 ms). */
    val maxRetries: Int = 5,
    /** A landing request is repeated at this period until telemetry reports "not flying". */
    val landRepeatMs: Long = 300,
    /** Give up repeating a landing request after this long. */
    val landRepeatTimeoutMs: Long = 30_000,
    /** Not-flying samples ignored right after a landing request (stale / transitional telemetry). */
    val landConfirmGraceMs: Long = 1_000,
    /** Consecutive not-flying samples needed to consider the drone landed. */
    val landConfirmSamples: Int = 3,
    /** Neutral stick packets sent when the session stops (spaced, bounded in time). */
    val neutralPacketsOnStop: Int = 3,
)

/**
 * Flight control session with a Tello drone.
 *
 * Latency-critical design:
 * - stick positions live in an [AtomicReference] written directly from the touch handler,
 *   no UI state / recomposition in between;
 * - a stick change wakes the sender immediately (conflated signal), the 50 Hz period is only a keep-alive.
 *
 * Safety:
 * - sticks fall back to neutral whenever the link is (re)established or lost, and the stick
 *   stream never stops while the session runs: during a reconnection the drone keeps
 *   receiving *neutral* sticks (the uplink may still work even if the downlink is gone);
 * - when the session stops, a few neutral packets are sent before going silent;
 * - acknowledged commands (takeoff, land…) are retransmitted until the drone answers;
 * - a landing request is repeated, even while reconnecting, until the drone reports it landed.
 */
class TelloController(
    private val transport: ControlTransport,
    private val config: ControlConfig = ControlConfig(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _link = MutableStateFlow(LinkState.IDLE)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val sticks = AtomicReference(StickInput.Neutral)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val sequence = AtomicInteger(0)
    private val lastReceived = AtomicLong(0)
    private val lastKeyFrameRequest = AtomicLong(Long.MIN_VALUE / 2)

    private class Pending(val bytes: ByteArray, val messageId: Int, var retriesLeft: Int, var nextAt: Long)
    private val pendingLock = Mutex()
    private val pending = HashMap<Int, Pending>()

    private val landRequested = AtomicBoolean(false)
    private val landRequestedAt = AtomicLong(0)
    private val lastLandSent = AtomicLong(Long.MIN_VALUE / 2)
    private var notFlyingSamples = 0 // control thread only

    val isConnected: Boolean get() = _link.value == LinkState.CONNECTED

    /** Current stick positions (as they will be sent). */
    val currentSticks: StickInput get() = sticks.get()

    /** `true` while a landing request is being repeated. */
    val isLanding: Boolean get() = landRequested.get()

    /** Runs the session until the calling coroutine is cancelled. */
    suspend fun run() = coroutineScope {
        _link.value = LinkState.CONNECTING
        launch {
            transport.incoming.collect(::onDatagram)
            // The receiver must never end while the session runs: let the owner restart us.
            throw IllegalStateException("control receiver ended")
        }
        try {
            var lastHandshake = Long.MIN_VALUE / 2
            var lastStick = Long.MIN_VALUE / 2
            while (isActive) {
                val now = clock()
                if (isConnected && now - lastReceived.get() > config.linkTimeoutMs) onLinkLost()

                if (!isConnected && now - lastHandshake >= config.handshakeIntervalMs) {
                    send(Handshake.connectRequest(transport.videoPort))
                    lastHandshake = now
                }

                val wait = config.stickMinIntervalMs - (now - lastStick)
                if (wait > 0) delay(wait)
                // User sticks only while connected; neutral keep-alive otherwise.
                val input = sticks.get().let { if (isConnected) it else StickInput(fastMode = it.fastMode) }
                send(PacketCodec.encode(Command.STICK, 0, Payloads.stick(input)))
                lastStick = clock()

                retransmitPending()
                repeatLandingIfNeeded()

                withTimeoutOrNull(config.stickIntervalMs) { wakeUp.receive() }
            }
        } finally {
            sticks.set(StickInput.Neutral)
            withContext(NonCancellable) {
                // Spaced so one burst loss does not drop them all; bounded so a stuck send cannot hang stop.
                withTimeoutOrNull(config.stickIntervalMs * (config.neutralPacketsOnStop + 2)) {
                    repeat(config.neutralPacketsOnStop) { i ->
                        if (i > 0) delay(config.stickIntervalMs)
                        send(PacketCodec.encode(Command.STICK, 0, Payloads.stick(StickInput.Neutral)))
                    }
                }
            }
            _link.value = LinkState.IDLE
        }
    }

    // ---------------------------------------------------------------- sticks

    /** Updates stick positions; values are clamped to `-1..1` and sent right away. */
    fun setSticks(input: StickInput) {
        sticks.set(
            input.copy(
                roll = input.roll.clampAxis(),
                pitch = input.pitch.clampAxis(),
                throttle = input.throttle.clampAxis(),
                yaw = input.yaw.clampAxis(),
            ),
        )
        wakeUp.trySend(Unit)
    }

    /** Centers all sticks (finger lifted, app paused, link lost…). Fast-mode is kept. */
    fun releaseSticks() {
        centerSticks()
        wakeUp.trySend(Unit)
    }

    fun setFastMode(enabled: Boolean) {
        sticks.updateAndGet { it.copy(fastMode = enabled) }
        wakeUp.trySend(Unit)
    }

    private fun centerSticks() {
        sticks.updateAndGet { StickInput(fastMode = it.fastMode) }
    }

    // --------------------------------------------------------------- commands

    suspend fun takeoff() = launchCommand(Command.TAKEOFF)
    suspend fun throwTakeoff() = launchCommand(Command.THROW_TAKEOFF)

    /** Takeoff variants: only when connected; they supersede a landing request and its retries. */
    private suspend fun launchCommand(command: Command): Boolean {
        if (!isConnected) return false // a refused takeoff must not cancel a landing in progress
        landRequested.set(false)
        dropPending(Command.LAND)
        return command(command)
    }

    /**
     * Requests a landing. Accepted in any running state (even while reconnecting: the uplink
     * may still reach the drone) and repeated until telemetry confirms the drone landed.
     */
    suspend fun land(): Boolean {
        if (_link.value == LinkState.IDLE) return false
        dropPending(Command.TAKEOFF, Command.THROW_TAKEOFF) // never retry a takeoff after a land
        landRequested.set(true)
        landRequestedAt.set(clock())
        notFlyingSamples = 0
        sendLand()
        return true
    }

    private suspend fun dropPending(vararg commands: Command) {
        val ids = commands.map { it.id }.toSet()
        pendingLock.withLock { pending.values.removeAll { it.messageId in ids } }
    }

    /** Cancels a pending landing request (does not stop a landing already started by the drone). */
    fun cancelLandingRequest() = landRequested.set(false)

    suspend fun palmLand() = command(Command.PALM_LAND)
    suspend fun flip(direction: FlipDirection) = command(Command.FLIP, Payloads.flip(direction))

    /** 0 = automatic, 1..5 = 1 to 4 Mbit/s. */
    suspend fun setVideoBitrate(level: Int) = command(Command.SET_BITRATE, Payloads.singleByte(level.coerceIn(0, 5)))

    /** Asks the drone for an I-frame (throttled). Returns `true` when a request was sent. */
    suspend fun requestKeyFrame(): Boolean {
        if (!isConnected) return false
        val now = clock()
        val last = lastKeyFrameRequest.get()
        if (now - last < config.keyFrameMinIntervalMs || !lastKeyFrameRequest.compareAndSet(last, now)) return false
        return send(PacketCodec.encode(Command.REQUEST_KEY_FRAME, 0))
    }

    /** Sends [command] if the link is up. Returns `false` (and sends nothing) otherwise. */
    private suspend fun command(command: Command, payload: ByteArray = ByteArray(0)): Boolean {
        if (!isConnected) return false
        return sendSequenced(command, payload)
    }

    private suspend fun sendSequenced(command: Command, payload: ByteArray): Boolean {
        val seq = nextSequence()
        val bytes = PacketCodec.encode(command, seq, payload)
        if (command.acknowledged) {
            pendingLock.withLock {
                pending[seq] = Pending(bytes, command.id, config.maxRetries, clock() + config.retryIntervalMs)
            }
        }
        return send(bytes)
    }

    private suspend fun sendLand() {
        lastLandSent.set(clock())
        sendSequenced(Command.LAND, Payloads.singleByte(0))
    }

    /** 1..65535: 0 is reserved for unsequenced packets (and acks with seq 0 are ignored). */
    private fun nextSequence() = (sequence.getAndIncrement() % 0xFFFF) + 1

    /**
     * A failing transport (network blocked, interface down…) must never stop the session:
     * the error is swallowed and the watchdog deals with the missing replies.
     */
    private suspend fun send(datagram: ByteArray): Boolean = try {
        transport.send(datagram)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ periodic

    private suspend fun retransmitPending() {
        val now = clock()
        val due = mutableListOf<ByteArray>()
        pendingLock.withLock {
            val it = pending.values.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.nextAt > now) continue
                if (p.retriesLeft == 0) {
                    it.remove() // gave up: the drone never acknowledged
                } else {
                    p.retriesLeft--
                    p.nextAt = now + config.retryIntervalMs
                    due += p.bytes
                }
            }
        }
        due.forEach { send(it) }
    }

    private suspend fun repeatLandingIfNeeded() {
        if (!landRequested.get()) return
        val now = clock()
        // The timeout only counts while the link is up: a long reconnection must not drop the request.
        if (!isConnected) landRequestedAt.set(now - minOf(now - landRequestedAt.get(), config.landRepeatTimeoutMs / 2))
        if (now - landRequestedAt.get() > config.landRepeatTimeoutMs) {
            landRequested.set(false)
            return
        }
        if (now - lastLandSent.get() >= config.landRepeatMs) sendLand()
    }

    private suspend fun onConnected() {
        centerSticks()
        _link.value = LinkState.CONNECTED
        lastKeyFrameRequest.set(Long.MIN_VALUE / 2)
        // Same baseline as the reference apps: camera in video mode, no electronic stabilisation
        // (adds latency), then ask for a key frame so the picture starts immediately.
        command(Command.SET_VIDEO_MODE, Payloads.singleByte(1))
        command(Command.SET_EIS, Payloads.singleByte(0))
        requestKeyFrame()
        if (landRequested.get()) sendLand()
    }

    private fun onLinkLost() {
        centerSticks()
        _link.value = LinkState.RECONNECTING
    }

    // --------------------------------------------------------------- incoming

    private suspend fun onDatagram(datagram: ByteArray) {
        if (Handshake.isConnectAck(datagram)) {
            lastReceived.set(clock())
            if (!isConnected) onConnected()
            return
        }
        // Only valid packets prove the link is alive (garbage must not feed the watchdog).
        val packet = PacketCodec.decode(datagram) ?: return
        lastReceived.set(clock())
        if (packet.sequence != 0) {
            pendingLock.withLock {
                val p = pending[packet.sequence]
                if (p != null && p.messageId == packet.messageId) pending.remove(packet.sequence)
            }
        }
        when (val message = DroneMessage.from(packet)) {
            is DroneMessage.Flight -> {
                _telemetry.update { it.copy(flight = message.data) }
                if (landRequested.get()) confirmLanding(message.data.flying)
            }
            is DroneMessage.Wifi -> _telemetry.update { it.copy(wifi = message.status) }
            is DroneMessage.Light -> _telemetry.update { it.copy(tooDark = message.tooDark) }
            is DroneMessage.Firmware -> _telemetry.update { it.copy(firmware = message.version) }
            DroneMessage.TimeRequest -> if (isConnected) send(PacketCodec.encode(Command.TIMESTAMP, nextSequence(), Payloads.timestamp()))
            is DroneMessage.LogHeader -> if (isConnected) send(PacketCodec.encode(Command.ACK_LOG, nextSequence(), Payloads.logAck(message.logId)))
            is DroneMessage.Other -> Unit
        }
    }

    /**
     * "Landed" is only accepted from several consecutive not-flying samples received after a
     * grace period: a single stale or transitional sample must not stop the landing request.
     */
    private suspend fun confirmLanding(flying: Boolean) {
        if (flying || clock() - landRequestedAt.get() < config.landConfirmGraceMs) {
            notFlyingSamples = 0
            return
        }
        if (++notFlyingSamples >= config.landConfirmSamples) {
            landRequested.set(false)
            dropPending(Command.LAND)
        }
    }

    private fun Float.clampAxis() = if (isNaN()) 0f else coerceIn(-1f, 1f)

    private val Command.acknowledged: Boolean
        get() = sequenced && (packetType == PacketType.SET || packetType == PacketType.DATA_LOW)
}
