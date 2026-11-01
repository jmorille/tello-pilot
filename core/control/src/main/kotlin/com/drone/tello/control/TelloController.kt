package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.DroneMessage
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.Handshake
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.PhotoDownload
import com.drone.tello.protocol.SmartVideoMode
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** `false` = brand-new drone that the controller is activating. */
    val activated: Boolean? = null,
    /** Camera reported a fault for 20 consecutive frames. */
    val cameraFault: Boolean = false,
    /** Drone Wi-Fi name, once queried. */
    val ssid: String? = null,
)

/** One-shot results the UI reports to the pilot. */
sealed interface DroneEvent {
    data class CenterOfGravityCalibrated(val success: Boolean) : DroneEvent
    /** A manoeuvre was refused by the drone itself (reply status != 0). */
    data class ManeuverRefused(val mode: FlightMode) : DroneEvent
}

data class ControlConfig(
    /** Keep-alive period of the stick stream (the drone expects ~50 Hz). */
    val stickIntervalMs: Long = 20,
    /** Minimum spacing between two stick packets when the user moves the sticks fast. */
    val stickMinIntervalMs: Long = 10,
    /** Period of `conn_req` while waiting for the drone. */
    val handshakeIntervalMs: Long = 500,
    /** `conn_req` keep-alive once connected (the official app sends it every second). */
    val heartbeatIntervalMs: Long = 1_000,
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
    /** TAKE_PICTURE retransmission (official app: 500 ms, 2 retries). */
    val photoRetryIntervalMs: Long = 500,
    val photoMaxRetries: Int = 2,
    /** A never-activated drone is activated again if it still reports "not activated" after this. */
    val activationRetryMs: Long = 3_000,
    /** A photo transfer is abandoned after this long without data (official app: 20 s). */
    val photoTimeoutMs: Long = 20_000,
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
 *
 * Threading: [run] and every suspend command must be dispatched on the same single-threaded
 * dispatcher (the app uses `RealtimeThreads.control`). Incoming datagrams are handled on that
 * dispatcher too, so the photo/activation/camera state below is confined to it. Only
 * [setSticks], [releaseSticks], [setFastMode] are safe from any thread.
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

    /**
     * Number of flight data packets received: tells a fresh sample from a frozen one (the
     * StateFlow above does not re-emit an equal value). Readable from any thread.
     */
    @Volatile var flightSamples: Long = 0
        private set

    private val sticks = AtomicReference(StickInput.Neutral)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val sequence = AtomicInteger(0)
    private val lastReceived = AtomicLong(0)
    private val lastKeyFrameRequest = AtomicLong(Long.MIN_VALUE / 2)

    private class Pending(val bytes: ByteArray, val messageId: Int, var retriesLeft: Int, var nextAt: Long, val intervalMs: Long)
    private val pendingLock = Mutex()
    private val pending = HashMap<Int, Pending>()

    private val landRequested = AtomicBoolean(false)
    private val landRequestedAt = AtomicLong(0)
    private val lastLandSent = AtomicLong(Long.MIN_VALUE / 2)
    private var notFlyingSamples = 0 // control thread only

    @Volatile private var droneSettings: DroneSettings? = null
    private var lastActivationAt = Long.MIN_VALUE / 2 // control thread only

    private val photoDownload = PhotoDownload()

    /** Optional diagnostic sink (photo transfer milestones); the core has no logger of its own. */
    @Volatile var diagnostics: ((String) -> Unit)? = null
    private var photoDataPackets = 0

    /** A failing diagnostic sink must never break the control loop. */
    private fun diag(message: String) {
        val sink = diagnostics ?: return
        runCatching { sink(message) }
    }
    private var photoStartedAt = 0L
    private var photoLastData = 0L
    private val _photo = MutableStateFlow<Float?>(null)
    /** Photo transfer progress (0..1) while a photo is being taken / downloaded, else `null`. */
    val photoProgress: StateFlow<Float?> = _photo.asStateFlow()
    private val _photos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    /** Completed photos (JPEG bytes) downloaded from the drone. */
    val photos: SharedFlow<ByteArray> = _photos.asSharedFlow()

    private var cameraErrorFrames = 0 // control thread only

    private val _maneuvers = MutableStateFlow(ManeuverState())
    /** Bounce / smart video state. */
    val maneuvers: StateFlow<ManeuverState> = _maneuvers.asStateFlow()
    private var requestedBounce = false // control thread only
    private var requestedSmartVideo: SmartVideoMode?
        get() = _maneuvers.value.requestedSmartVideo
        set(value) = _maneuvers.update { it.copy(requestedSmartVideo = value) }

    private val imu = ImuCalibration()
    private val _imuCalibration = MutableStateFlow<ImuCalibrationState>(ImuCalibrationState.Idle)
    val imuCalibration: StateFlow<ImuCalibrationState> = _imuCalibration.asStateFlow()
    private var lastImuPoll = Long.MIN_VALUE / 2

    private val wifiChange = WifiChange()
    private val _wifiChange = MutableStateFlow<WifiChangeState>(WifiChangeState.Idle)
    val wifiChangeState: StateFlow<WifiChangeState> = _wifiChange.asStateFlow()

    private val _events = MutableSharedFlow<DroneEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DroneEvent> = _events.asSharedFlow()

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

                val handshakePeriod = if (isConnected) config.heartbeatIntervalMs else config.handshakeIntervalMs
                if (now - lastHandshake >= handshakePeriod) {
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
                checkPhotoTimeout()
                tickCalibrationAndWifi()

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

    // ------------------------------------------------------- firmware manoeuvres

    private fun check(mode: FlightMode): ModeCheck {
        if (!isConnected) return ModeCheck.NOT_CONNECTED
        val threshold = (droneSettings ?: DroneSettings()).lowBatteryPercent
        return FlightModeRules.check(mode, _telemetry.value.flight, threshold, photoInProgress = _photo.value != null)
    }

    /** 8-direction flip, with the official preconditions (battery >= 50 %, height <= 4 m). */
    suspend fun flip(direction: FlipDirection): ModeCheck {
        val result = check(FlightMode.Flip)
        if (result == ModeCheck.OK) command(Command.FLIP, Payloads.flip(direction))
        return result
    }

    /** Enters / leaves bounce mode (leaving is always allowed). */
    suspend fun setBounce(enabled: Boolean): ModeCheck {
        if (!isConnected) return ModeCheck.NOT_CONNECTED
        if (enabled) {
            check(FlightMode.Bounce).let { if (it != ModeCheck.OK) return it }
            if (_maneuvers.value.smartVideoActive) return ModeCheck.BUSY
        }
        requestedBounce = enabled
        command(Command.BOUNCE, Payloads.bounce(enabled))
        return ModeCheck.OK
    }

    suspend fun startSmartVideo(mode: SmartVideoMode): ModeCheck {
        val result = check(FlightMode.SmartVideo(mode))
        if (result != ModeCheck.OK) return result
        if (_maneuvers.value.bounce || _maneuvers.value.smartVideoActive) return ModeCheck.BUSY
        requestedSmartVideo = mode
        command(Command.SMART_VIDEO, Payloads.smartVideo(start = true, mode = mode))
        return ModeCheck.OK
    }

    /**
     * Stops the smart video (always allowed while connected). Uses the mode the drone reports as
     * running, else the one requested; if neither is known (app restarted mid-manoeuvre), sends
     * the stop for every mode.
     */
    suspend fun stopSmartVideo(): Boolean {
        val status = _maneuvers.value.smartVideo
        val mode = status?.mode?.takeIf { status.running } ?: requestedSmartVideo
        val modes = mode?.let(::listOf) ?: SmartVideoMode.entries
        var sent = false
        modes.forEach { sent = command(Command.SMART_VIDEO, Payloads.smartVideo(start = false, mode = it)) || sent }
        return sent
    }

    // ------------------------------------------------------------ calibrations

    /** Centre-of-gravity (level) calibration; on the ground only. Result on [events]. */
    suspend fun calibrateCenterOfGravity(): ModeCheck {
        if (!isConnected) return ModeCheck.NOT_CONNECTED
        FlightModeRules.checkCalibration(_telemetry.value.flight).let { if (it != ModeCheck.OK) return it }
        command(Command.CALIBRATE_CENTER_OF_GRAVITY, Payloads.singleByte(1))
        return ModeCheck.OK
    }

    /** Starts the 6-face IMU calibration; progress on [imuCalibration]. On the ground only. */
    suspend fun startImuCalibration(): ModeCheck {
        if (!isConnected) return ModeCheck.NOT_CONNECTED
        FlightModeRules.checkCalibration(_telemetry.value.flight).let { if (it != ModeCheck.OK) return it }
        if (!imu.start(clock())) return ModeCheck.BUSY
        publishImu()
        command(Command.START_IMU_CALIBRATION)
        return ModeCheck.OK
    }

    /** Clears a finished / failed calibration (does not abort one in progress on the drone). */
    fun resetImuCalibration() {
        if (!imu.polling) {
            imu.reset()
            publishImu()
        }
    }

    private fun publishImu() {
        _imuCalibration.value = imu.state
    }

    // ------------------------------------------------------------------- Wi-Fi

    suspend fun queryWifiName() = command(Command.QUERY_SSID)

    /**
     * Renames the drone's network / changes its password, then restarts its Wi-Fi: the link drops
     * and the phone must join the new network. Returns `false` if the values are invalid.
     */
    suspend fun changeWifi(ssid: String, password: String): Boolean {
        if (!isConnected || _telemetry.value.flight?.flying == true) return false
        val ssidBytes = runCatching { Payloads.ssid(ssid) }.getOrNull() ?: return false
        val passwordBytes = runCatching { Payloads.wifiPassword(password) }.getOrNull() ?: return false
        if (!wifiChange.start(ssid, clock())) return false
        _wifiChange.value = wifiChange.state
        command(Command.SET_SSID, ssidBytes)
        command(Command.SET_WIFI_PASSWORD, passwordBytes)
        return true
    }

    fun resetWifiChange() {
        wifiChange.reset()
        _wifiChange.value = wifiChange.state
    }

    private suspend fun tickCalibrationAndWifi() {
        val now = clock()
        if (imu.polling && isConnected && now - lastImuPoll >= IMU_POLL_MS) {
            lastImuPoll = now
            // Unacknowledged poll (official: never retried).
            send(PacketCodec.encode(Command.QUERY_IMU_CALIBRATION, nextSequence()))
        }
        val before = imu.state
        imu.onTick(now)
        if (imu.state != before) publishImu()
        if (wifiChange.onTick(now, WIFI_CHANGE_TIMEOUT_MS)) {
            _wifiChange.value = wifiChange.state
            command(Command.QUERY_SSID) // show what the drone actually stored
        }
    }

    /**
     * Stores [settings] and sends them to the drone now (if connected) and on every connection.
     * Returns `true` when they were sent.
     */
    suspend fun applyDroneSettings(settings: DroneSettings): Boolean {
        val s = settings.sanitized()
        if (s == droneSettings) return false // already sent (and re-sent on every connection)
        droneSettings = s
        if (!isConnected) return false
        sendDroneSettings(s)
        return true
    }

    private suspend fun sendDroneSettings(s: DroneSettings) {
        command(Command.SET_MAX_ALTITUDE, Payloads.maxAltitude(s.maxAltitudeMeters))
        command(Command.SET_LOW_BATTERY_THRESHOLD, Payloads.lowBatteryThreshold(s.lowBatteryPercent))
        command(Command.SET_EXPOSURE, Payloads.exposure(s.exposureSteps))
        command(Command.SET_BITRATE, Payloads.singleByte(s.bitrate.code))
    }

    /** Tells the drone the phone is recording (as both reference apps do). */
    suspend fun setOnboardRecording(recording: Boolean) =
        command(Command.SET_RECORDING, Payloads.singleByte(if (recording) 1 else 0))

    /**
     * Asks the drone to take a full-resolution photo; it is then downloaded automatically and
     * emitted on [photos]. Returns `false` if not connected or a photo is already in progress.
     */
    suspend fun takePicture(): Boolean {
        if (!isConnected || _photo.value != null) return false
        photoStartedAt = clock()
        _photo.value = 0f
        val sent = command(Command.TAKE_PICTURE)
        diag("photo requested, sent=$sent")
        if (!sent) _photo.value = null
        return sent
    }

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
                // TAKE_PICTURE: the drone answers after the capture, and a fast retry could trigger a
                // second photo → official pacing (500 ms, twice).
                val (retries, interval) = if (command == Command.TAKE_PICTURE) {
                    config.photoMaxRetries to config.photoRetryIntervalMs
                } else {
                    config.maxRetries to config.retryIntervalMs
                }
                pending[seq] = Pending(bytes, command.id, retries, clock() + interval, interval)
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
                    p.nextAt = now + p.intervalMs
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
        // A smart-video status from before the reconnection is stale (the drone resends 129).
        _maneuvers.update { it.copy(smartVideo = null) }
        _link.value = LinkState.CONNECTED
        lastKeyFrameRequest.set(Long.MIN_VALUE / 2)
        // Camera in photo mode (49 = 0): the drone silently ignores TAKE_PICTURE in video mode
        // (the official app only offers the photo button in that mode), and the stream stays
        // 960x720 either way, so recording is unaffected. No electronic stabilisation (adds
        // latency), then ask for a key frame so the picture starts immediately.
        command(Command.SET_VIDEO_MODE, Payloads.singleByte(0))
        command(Command.SET_EIS, Payloads.singleByte(0))
        command(Command.QUERY_ACTIVATION_STATE)
        command(Command.QUERY_FIRMWARE)
        droneSettings?.let { sendDroneSettings(it) }
        requestKeyFrame()
        if (landRequested.get()) sendLand()
    }

    private fun checkPhotoTimeout() {
        if (_photo.value == null) return
        val last = maxOf(photoStartedAt, photoLastData)
        if (clock() - last > config.photoTimeoutMs) {
            diag("photo timeout (progress ${_photo.value}, $photoDataPackets data packets)")
            photoDownload.reset()
            _photo.value = null
        }
    }

    private suspend fun onPhotoEvent(event: PhotoDownload.Event) {
        when (event) {
            PhotoDownload.Event.None -> Unit
            is PhotoDownload.Event.Reply -> sendFileAcks(event.commands)
            is PhotoDownload.Event.Completed -> {
                diag("photo complete: ${event.jpeg.size} B in $photoDataPackets data packets")
                sendFileAcks(event.commands)
                _photo.value = null
                _photos.tryEmit(event.jpeg)
            }
        }
        photoDownload.progress?.let { _photo.value = it }
    }

    /** File-transfer acks are fire-and-forget in the official app (no retransmission). */
    private suspend fun sendFileAcks(commands: List<Pair<Command, ByteArray>>) {
        commands.forEach { (command, payload) -> send(PacketCodec.encode(command, nextSequence(), payload)) }
    }

    /** A camera fault is only reported after 20 consecutive frames (official debounce). */
    private fun cameraFault(flight: FlightData): Boolean {
        cameraErrorFrames = if (flight.cameraState != 0) cameraErrorFrames + 1 else 0
        return cameraErrorFrames >= CAMERA_FAULT_FRAMES
    }

    private fun onLinkLost() {
        centerSticks()
        imu.onLinkLost()
        publishImu()
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
        // Like the official handlers (`a.b(seqNum)`), a status reply is only acted upon when it
        // answers a pending request: a late reply to a retransmission must not undo the state.
        var answeredPending = false
        if (packet.sequence != 0) {
            pendingLock.withLock {
                val p = pending[packet.sequence]
                if (p != null && p.messageId == packet.messageId) {
                    pending.remove(packet.sequence)
                    answeredPending = true
                }
            }
        }
        when (val message = DroneMessage.from(packet)) {
            is DroneMessage.Flight -> {
                flightSamples++
                val fault = cameraFault(message.data)
                _telemetry.update { it.copy(flight = message.data, cameraFault = fault) }
                if (landRequested.get()) confirmLanding(message.data.flying)
                // Firmware manoeuvres end on the ground.
                if (!message.data.flying && _maneuvers.value != ManeuverState()) {
                    _maneuvers.value = ManeuverState()
                    requestedBounce = false
                    requestedSmartVideo = null
                }
            }
            is DroneMessage.Wifi -> _telemetry.update { it.copy(wifi = message.status) }
            is DroneMessage.Light -> _telemetry.update { it.copy(tooDark = message.tooDark) }
            is DroneMessage.Firmware -> _telemetry.update { it.copy(firmware = message.version) }
            is DroneMessage.ActivationState -> {
                _telemetry.update { it.copy(activated = message.activated) }
                // A never-activated Tello refuses to fly (motor code 206): activate it like the
                // official app (timestamp only — the region is only sent to RoboMaster TT). 76 is
                // unacknowledged, so it is retried while the drone still reports "not activated".
                val now = clock()
                if (!message.activated && isConnected && now - lastActivationAt >= config.activationRetryMs) {
                    lastActivationAt = now
                    command(Command.SET_ACTIVATION_TIMESTAMP, Payloads.activationTimestamp())
                }
            }
            DroneMessage.TimeRequest -> if (isConnected) send(PacketCodec.encode(Command.TIMESTAMP, nextSequence(), Payloads.timestamp()))
            is DroneMessage.LogHeader -> if (isConnected) send(PacketCodec.encode(Command.ACK_LOG, nextSequence(), Payloads.logAck(message.logId)))
            is DroneMessage.SmartVideo -> {
                _maneuvers.update { it.copy(smartVideo = message.status) }
                if (!message.status.running) requestedSmartVideo = null
                // The official app acks every 129 with [00] (type 0x50).
                send(PacketCodec.encode(Command.ACK_SMART_VIDEO_STATUS, nextSequence(), byteArrayOf(0)))
            }
            is DroneMessage.ImuCalibration -> {
                imu.onStatus(message.status, clock())
                publishImu()
            }
            is DroneMessage.Ssid -> _telemetry.update { it.copy(ssid = message.name ?: it.ssid) }
            // FLIP (type 0x70) is never pending: its reply is only informative.
            is DroneMessage.CommandResult -> if (answeredPending || message.messageId == MessageId.FLIP) onCommandResult(message)
            is DroneMessage.Other -> when (packet.messageId) {
                // Reply to 76: check the result (official app queries 75 again).
                MessageId.ACTIVATION_TIMESTAMP -> command(Command.QUERY_ACTIVATION_STATE)
                // Reply to 48: a non-zero status means the drone refused (busy, camera error).
                MessageId.TAKE_PICTURE -> if (packet.payload.isNotEmpty() && packet.payload[0].toInt() != 0) {
                    diag("photo refused by the drone: status ${packet.payload[0]}")
                    photoDownload.reset()
                    _photo.value = null
                }
                MessageId.FILE_SIZE -> {
                    val event = photoDownload.onFileSize(packet)
                    photoDataPackets = 0
                    diag("photo file size: type ${packet.packetType}, ${packet.payload.size} B payload, accepted=${event != PhotoDownload.Event.None}")
                    if (event != PhotoDownload.Event.None) {
                        photoLastData = clock()
                        if (_photo.value == null) _photo.value = 0f // photo triggered from the drone itself
                    }
                    onPhotoEvent(event)
                }
                MessageId.FILE_DATA -> {
                    if (photoDataPackets++ == 0) diag("photo first data packet (${packet.payload.size} B)")
                    photoLastData = clock()
                    onPhotoEvent(photoDownload.onFileData(packet))
                }
            }
        }
    }

    private suspend fun onCommandResult(result: DroneMessage.CommandResult) {
        when (result.messageId) {
            MessageId.BOUNCE -> if (result.success) {
                _maneuvers.update { it.copy(bounce = requestedBounce) }
            } else if (requestedBounce) {
                _events.tryEmit(DroneEvent.ManeuverRefused(FlightMode.Bounce))
            }
            MessageId.SMART_VIDEO -> if (!result.success) {
                requestedSmartVideo?.let { _events.tryEmit(DroneEvent.ManeuverRefused(FlightMode.SmartVideo(it))) }
                requestedSmartVideo = null
            }
            MessageId.FLIP -> if (!result.success) _events.tryEmit(DroneEvent.ManeuverRefused(FlightMode.Flip))
            MessageId.CENTER_OF_GRAVITY_CALIBRATION -> _events.tryEmit(DroneEvent.CenterOfGravityCalibrated(result.success))
            MessageId.IMU_CALIBRATION_START -> {
                imu.onStartResult(result.success)
                publishImu()
            }
            MessageId.SET_SSID, MessageId.SET_WIFI_PASSWORD -> {
                val restart = wifiChange.onResult(ssidReply = result.messageId == MessageId.SET_SSID, success = result.success)
                _wifiChange.value = wifiChange.state
                if (restart) command(Command.RESTART_WIFI)
                // Part of the change may be stored (applied at the next reboot): show the real SSID.
                if (wifiChange.state == WifiChangeState.Failed) command(Command.QUERY_SSID)
            }
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
        get() = sequenced && (packetType == PacketType.SET || packetType == PacketType.DATA_LOW) &&
            this != Command.FILE_DONE

    private companion object {
        const val CAMERA_FAULT_FRAMES = 20
        const val IMU_POLL_MS = 100L
        const val WIFI_CHANGE_TIMEOUT_MS = 5_000L
    }
}
