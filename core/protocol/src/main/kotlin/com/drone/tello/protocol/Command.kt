package com.drone.tello.protocol

/** Packet type byte (direction / ack flags) used by the drone firmware. */
object PacketType {
    const val DATA_LOW = 0x48
    const val DATA_ACK = 0x50
    const val STREAM = 0x60
    const val SET = 0x68
    const val ACTION = 0x70
}

/** Outgoing commands understood by the Tello firmware. */
enum class Command(val id: Int, val packetType: Int, val sequenced: Boolean = true) {
    REQUEST_KEY_FRAME(37, PacketType.STREAM, sequenced = false),
    STICK(80, PacketType.STREAM, sequenced = false),
    TAKEOFF(84, PacketType.SET),
    LAND(85, PacketType.SET),
    SET_MAX_ALTITUDE(88, PacketType.SET),
    FLIP(92, PacketType.ACTION),
    THROW_TAKEOFF(93, PacketType.DATA_LOW),
    PALM_LAND(94, PacketType.DATA_LOW),
    SET_BITRATE(32, PacketType.SET),
    SET_EIS(36, PacketType.SET),
    SET_EXPOSURE(52, PacketType.SET),
    /** Camera mode: payload 1 = video (as sent by the reference apps on connect), 0 = photo. */
    SET_VIDEO_MODE(49, PacketType.SET),
    SET_RECORDING(50, PacketType.SET),
    QUERY_FIRMWARE(69, PacketType.DATA_LOW),
    TIMESTAMP(70, PacketType.DATA_ACK),
    ACK_LOG(4176, PacketType.DATA_ACK),
    SET_LOW_BATTERY_THRESHOLD(4181, PacketType.SET),
}

/** Identifiers of messages sent by the drone. */
object MessageId {
    const val WIFI = 26
    const val LIGHT = 53
    const val FIRMWARE = 69
    const val TIME_REQUEST = 70
    const val FLIGHT_DATA = 86
    const val LOG_HEADER = 4176
    const val LOG_DATA = 4177
}

/** Flip directions, as encoded in the [Command.FLIP] payload. */
enum class FlipDirection(val code: Int) {
    FORWARD(0), LEFT(1), BACK(2), RIGHT(3),
    FORWARD_LEFT(4), BACK_LEFT(5), BACK_RIGHT(6), FORWARD_RIGHT(7),
}
