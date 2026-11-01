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
) {
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
            )
        }
    }
}

data class WifiStatus(val strength: Int, val interference: Int)

/** Messages received from the drone, already decoded. */
sealed interface DroneMessage {
    data class Flight(val data: FlightData) : DroneMessage
    data class Wifi(val status: WifiStatus) : DroneMessage
    data class Light(val tooDark: Boolean) : DroneMessage
    data object TimeRequest : DroneMessage
    data class LogHeader(val logId: Int) : DroneMessage
    data class Firmware(val version: String) : DroneMessage
    data class ActivationState(val activated: Boolean) : DroneMessage
    data class Other(val packet: Packet) : DroneMessage

    companion object {
        fun from(packet: Packet): DroneMessage {
            val p = packet.payload
            return when (packet.messageId) {
                MessageId.FLIGHT_DATA -> FlightData.parse(p)?.let(::Flight) ?: Other(packet)
                MessageId.WIFI if p.size >= 2 -> Wifi(WifiStatus(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF))
                MessageId.LIGHT if p.isNotEmpty() -> Light(p[0].toInt() != 0)
                MessageId.TIME_REQUEST -> TimeRequest
                MessageId.LOG_HEADER if p.size >= 2 -> LogHeader(PacketCodec.getShortLe(p, 0))
                MessageId.ACTIVATION_STATE if p.isNotEmpty() -> ActivationState(activated = p[0].toInt() == 0)
                // [status][ASCII x30]: the first 11 characters are the version (official app).
                MessageId.FIRMWARE if p.size > 1 -> Firmware(
                    String(p, 1, minOf(11, p.size - 1), Charsets.US_ASCII).trimEnd('\u0000', ' '),
                )
                else -> Other(packet)
            }
        }
    }
}
