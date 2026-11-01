package com.drone.tello.protocol

/** Flight status sent by the drone ~10 times per second (message 86). */
data class FlightData(
    val heightMeters: Float,
    val northSpeed: Float,
    val eastSpeed: Float,
    val verticalSpeed: Float,
    val flyTimeSeconds: Float,
    val batteryPercent: Int,
    val flying: Boolean,
    val onGround: Boolean,
    val hovering: Boolean,
    val batteryLow: Boolean,
    val batteryCritical: Boolean,
    val windWarning: Boolean,
    val imuOk: Boolean,
    val flyMode: Int,
) {
    val horizontalSpeed: Float
        get() = kotlin.math.sqrt(northSpeed * northSpeed + eastSpeed * eastSpeed)

    companion object {
        const val MIN_SIZE = 24

        fun parse(p: ByteArray): FlightData? {
            if (p.size < MIN_SIZE) return null
            fun s16(o: Int) = PacketCodec.getShortLe(p, o).toShort().toInt()
            fun u8(o: Int) = p[o].toInt() and 0xFF
            fun bit(o: Int, b: Int) = (u8(o) shr b) and 1 == 1
            return FlightData(
                heightMeters = s16(0) / 10f,
                northSpeed = s16(2) / 10f,
                eastSpeed = s16(4) / 10f,
                verticalSpeed = s16(6) / 10f,
                flyTimeSeconds = s16(8) / 10f,
                imuOk = bit(10, 0),
                windWarning = bit(10, 7),
                batteryPercent = u8(12),
                flying = bit(17, 0),
                onGround = bit(17, 1),
                hovering = bit(17, 3),
                batteryLow = bit(17, 5),
                batteryCritical = bit(17, 6),
                flyMode = u8(18),
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
                MessageId.FIRMWARE if p.size > 1 ->
                    Firmware(String(p, 1, p.size - 1, Charsets.US_ASCII).trimEnd('\u0000'))
                else -> Other(packet)
            }
        }
    }
}
