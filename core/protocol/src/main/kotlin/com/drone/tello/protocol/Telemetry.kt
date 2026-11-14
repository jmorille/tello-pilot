package com.drone.tello.protocol

/**
 * Flight status sent by the drone ~10 times per second (message 86).
 * Layout from the official app's `FlyControllerEntity` (see `analysis/PROTOCOLE_PHASE2.md` §3).
 */
data class FlightData(
    val heightMeters: Float,
    val northSpeed: Float,
    val eastSpeed: Float,
    val verticalSpeed: Float,
    val flyTimeSeconds: Float,
    // byte 10 — sensor status bits (1 = problem)
    val imuError: Boolean = false,
    /** bit 1: barometer (pressure sensor) problem. */
    val barometerError: Boolean = false,
    val visionSensorError: Boolean = false,
    val batteryError: Boolean = false,
    val gravityCalibrationNeeded: Boolean = false,
    val windWarning: Boolean = false,
    /** byte 11: 1 = IMU preheating, 2 = IMU error. */
    val imuCalibrationState: Int = 0,
    val batteryPercent: Int,
    /** bytes 13-14 (seconds, as reported). */
    val flyTimeLeft: Int = 0,
    // byte 17
    val flying: Boolean,
    val onGround: Boolean,
    val motorsOn: Boolean = false,
    val hovering: Boolean,
    val droneRecording: Boolean = false,
    val batteryLow: Boolean,
    val batteryCritical: Boolean,
    /** byte 18: 1 = cannot localise (no VPS), 12 = landing. */
    val flyMode: Int,
    /** byte 19: throw-takeoff countdown in 1/10 s. */
    val throwTimer: Int = 0,
    /** byte 20: 0 = camera OK. */
    val cameraState: Int = 0,
    /** byte 21: motor / ESC error code, 0 = OK (206 = not activated, 205 = temperature…). */
    val motorErrorCode: Int = 0,
    /** byte 22 bit 7. */
    val overheating: Boolean = false,
    /** byte 22 bits 3-4: centre of gravity calibration, 1 = running, 2 = done (official app). */
    val gravityCalibrationStatus: Int = 0,
    /** byte 22 bit 5: flight controller sensor error (official "Fcerror": land as soon as possible). */
    val flightControllerError: Boolean = false,
) {
    /** Climb rate in m/s, positive upwards (the drone reports north / east / down speeds). */
    val climbRate: Float get() = -verticalSpeed

    /** Throw takeoff countdown in whole seconds (rounded up), 0 when none. */
    val throwCountdown: Int get() = (throwTimer + 9) / 10

    val horizontalSpeed: Float
        get() = kotlin.math.sqrt(northSpeed * northSpeed + eastSpeed * eastSpeed)

    val isLanding: Boolean get() = flyMode == FLY_MODE_LANDING
    val positioningUnavailable: Boolean get() = flyMode == FLY_MODE_NO_POSITIONING

    companion object {
        /** Bytes 0..20 are always present; 21 and 22 only on newer firmware. */
        const val MIN_SIZE = 21
        const val FLY_MODE_NO_POSITIONING = 1
        const val FLY_MODE_LANDING = 12

        fun parse(p: ByteArray): FlightData? {
            if (p.size < MIN_SIZE) return null
            fun s16(o: Int) = PacketCodec.getShortLe(p, o).toShort().toInt()
            fun u16(o: Int) = PacketCodec.getShortLe(p, o)
            fun u8(o: Int) = p[o].toInt() and 0xFF
            fun bit(o: Int, b: Int) = (u8(o) shr b) and 1 == 1
            return FlightData(
                heightMeters = s16(0) / 10f,
                northSpeed = s16(2) / 10f,
                eastSpeed = s16(4) / 10f,
                verticalSpeed = s16(6) / 10f,
                flyTimeSeconds = u16(8) / 10f,
                imuError = bit(10, 0),
                barometerError = bit(10, 1),
                visionSensorError = bit(10, 2),
                batteryError = bit(10, 4),
                gravityCalibrationNeeded = bit(10, 5),
                windWarning = bit(10, 7),
                imuCalibrationState = p[11].toInt(),
                batteryPercent = u8(12),
                flyTimeLeft = s16(13),
                flying = bit(17, 0),
                onGround = bit(17, 1),
                motorsOn = bit(17, 2),
                hovering = bit(17, 3),
                droneRecording = bit(17, 4),
                batteryLow = bit(17, 5),
                batteryCritical = bit(17, 6),
                flyMode = u8(18),
                throwTimer = u8(19),
                cameraState = u8(20),
                motorErrorCode = if (p.size > 21) u8(21) else 0,
                overheating = p.size > 22 && bit(22, 7),
                gravityCalibrationStatus = if (p.size > 22) (u8(22) shr 3) and 3 else 0,
                flightControllerError = p.size > 22 && bit(22, 5),
            )
        }
    }
}

data class WifiStatus(val strength: Int, val interference: Int)

/**
 * Smart-video status pushed by the drone (message 129, official `q.d()`):
 * bits 0-2 = exit reason (0 = none), bits 3-4 = state (0 = not running), bits 5-7 = video mode.
 * The meaning of the non-zero exit reasons is not documented (the official app only shows a
 * generic "smart video interrupted" tip).
 */
data class SmartVideoStatus(val exitReason: Int, val state: Int, val modeCode: Int) {
    /** Official app (`TelloCameraActivity`, event 269): running when the state is 1. */
    val running: Boolean get() = state == 1
    val mode: SmartVideoMode? get() = SmartVideoMode.fromCode(modeCode)

    companion object {
        fun parse(b: Byte): SmartVideoStatus {
            val v = b.toInt() and 0xFF
            return SmartVideoStatus(v and 7, (v shr 3) and 3, (v shr 5) and 7)
        }
    }
}

/** The six faces of the IMU calibration, in the order the official app asks for them. */
enum class ImuFace(val bit: Int) { BOTTOM(4), RIGHT(2), LEFT(3), FRONT(0), BACK(1), TOP(5) }

/** IMU calibration progress (message 91, official `IMUStatusEntity.setData`). */
data class ImuCalibrationStatus(val doneFaces: Set<ImuFace>, val status: Int, val progress: Int, val mode: Int) {
    val complete: Boolean get() = doneFaces.size == ImuFace.entries.size

    companion object {
        /** `[00][face bits][status][progress][mode]`, or `null` if the status byte is not 0. */
        fun parse(p: ByteArray): ImuCalibrationStatus? {
            if (p.size < 5 || p[0].toInt() != 0) return null
            val bits = p[1].toInt() and 0xFF
            return ImuCalibrationStatus(
                doneFaces = ImuFace.entries.filterTo(LinkedHashSet()) { (bits shr it.bit) and 1 == 1 },
                status = p[2].toInt(),
                progress = p[3].toInt() and 0xFF,
                mode = p[4].toInt(),
            )
        }
    }
}

/** Messages received from the drone, already decoded. */
sealed interface DroneMessage {
    data class Flight(val data: FlightData) : DroneMessage
    data class Wifi(val status: WifiStatus) : DroneMessage
    data class Light(val tooDark: Boolean) : DroneMessage
    data object TimeRequest : DroneMessage
    data class LogHeader(val logId: Int) : DroneMessage
    data class Firmware(val version: String) : DroneMessage
    data class ActivationState(val activated: Boolean) : DroneMessage
    data class SmartVideo(val status: SmartVideoStatus) : DroneMessage
    data class ImuCalibration(val status: ImuCalibrationStatus) : DroneMessage
    /** Reply to [Command.QUERY_SSID]; `null` when the drone could not read it. */
    data class Ssid(val name: String?) : DroneMessage
    /** Reply to [Command.QUERY_MAX_TILT]; `degrees` is `null` on failure or an implausible value. */
    data class MaxTilt(val degrees: Float?) : DroneMessage
    /** Status reply (`[0] == 0` = success) to one of [RESULT_IDS]. */
    data class CommandResult(val messageId: Int, val success: Boolean) : DroneMessage
    data class Other(val packet: Packet) : DroneMessage

    companion object {
        /** Commands whose reply is a single status byte handled by the controller. */
        val RESULT_IDS = setOf(
            MessageId.SET_SSID, MessageId.SET_WIFI_PASSWORD, MessageId.SMART_VIDEO, MessageId.BOUNCE,
            MessageId.CENTER_OF_GRAVITY_CALIBRATION, MessageId.IMU_CALIBRATION_START, MessageId.FLIP,
            MessageId.MAX_TILT,
        )

        fun from(packet: Packet): DroneMessage {
            val p = packet.payload
            return when (packet.messageId) {
                MessageId.FLIGHT_DATA -> FlightData.parse(p)?.let(::Flight) ?: Other(packet)
                MessageId.WIFI if p.size >= 2 -> Wifi(WifiStatus(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF))
                MessageId.LIGHT if p.isNotEmpty() -> Light(p[0].toInt() != 0)
                MessageId.TIME_REQUEST -> TimeRequest
                MessageId.LOG_HEADER if p.size >= 2 -> LogHeader(PacketCodec.getShortLe(p, 0))
                MessageId.ACTIVATION_STATE if p.isNotEmpty() -> ActivationState(activated = p[0].toInt() == 0)
                MessageId.SMART_VIDEO_STATUS if p.isNotEmpty() -> SmartVideo(SmartVideoStatus.parse(p[0]))
                MessageId.IMU_CALIBRATION_STATUS -> ImuCalibrationStatus.parse(p)?.let(::ImuCalibration) ?: Other(packet)
                MessageId.SSID if p.isNotEmpty() -> Ssid(
                    if (p[0].toInt() == 0) String(p, 1, p.size - 1, Charsets.US_ASCII).trimEnd(' ') else null,
                )
                MessageId.MAX_TILT_QUERY if p.isNotEmpty() -> MaxTilt(Payloads.parseMaxTilt(p))
                in RESULT_IDS if p.isNotEmpty() -> CommandResult(packet.messageId, p[0].toInt() == 0)
                // [status][ASCII x30]: the first 11 characters are the version (official app).
                MessageId.FIRMWARE if p.size > 1 -> Firmware(
                    String(p, 1, minOf(11, p.size - 1), Charsets.US_ASCII).trimEnd('\u0000', ' '),
                )
                else -> Other(packet)
            }
        }
    }
}
