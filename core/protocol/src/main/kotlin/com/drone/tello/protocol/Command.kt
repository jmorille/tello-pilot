package com.drone.tello.protocol

/** Packet type byte (direction / ack flags) used by the drone firmware. */
object PacketType {
    const val DATA_LOW = 0x48
    const val DATA_ACK = 0x50
    const val STREAM = 0x60
    const val SET = 0x68
    const val ACTION = 0x70

    /** Bit set on replies to an app request (official app: `pacType = type & 0x10`). */
    const val REPLY_BIT = 0x10
}

/**
 * Outgoing commands understood by the Tello firmware.
 * Ids / types / payloads follow the official app (see `analysis/PROTOCOLE_PHASE2.md`).
 */
enum class Command(val id: Int, val packetType: Int, val sequenced: Boolean = true) {
    REQUEST_KEY_FRAME(37, PacketType.STREAM, sequenced = false),
    STICK(80, PacketType.STREAM, sequenced = false),
    TAKEOFF(84, PacketType.SET),
    /** Payload 0 = land, 1 = cancel an automatic landing. */
    LAND(85, PacketType.SET),
    /** Payload: u16 metres (2..30). */
    SET_MAX_ALTITUDE(88, PacketType.SET),
    FLIP(92, PacketType.ACTION),
    THROW_TAKEOFF(93, PacketType.DATA_LOW),
    PALM_LAND(94, PacketType.DATA_LOW),
    /** Payload: u8 0 = auto, 1 = 1, 2 = 1.5, 3 = 2, 4 = 3, 5 = 4 Mbit/s. */
    SET_BITRATE(32, PacketType.SET),
    SET_EIS(36, PacketType.SET),
    /** Payload: i8 exposure in 1/3 EV steps (-9..9). Type 0x48 as in the official app. */
    SET_EXPOSURE(52, PacketType.DATA_LOW),
    /** Camera mode: payload 1 = video (as sent by the reference apps on connect), 0 = photo. */
    SET_VIDEO_MODE(49, PacketType.SET),
    SET_RECORDING(50, PacketType.SET),
    /** Payload: u8 0/1 (normal / high). */
    SET_PHOTO_QUALITY(55, PacketType.SET),
    /** No payload; the drone answers [00] then pushes the file (98 / 99). */
    TAKE_PICTURE(48, PacketType.SET),
    /** App acks: 98 = file size received, 99 = piece / file received, 100 = transfer done. */
    ACK_FILE_SIZE(98, PacketType.DATA_ACK),
    ACK_FILE_PIECE(99, PacketType.DATA_ACK),
    FILE_DONE(100, PacketType.DATA_LOW),
    QUERY_FIRMWARE(69, PacketType.DATA_LOW),
    TIMESTAMP(70, PacketType.DATA_ACK),
    ACK_LOG(4176, PacketType.DATA_ACK),
    /** Payload: u16 percent (10..50). */
    SET_LOW_BATTERY_THRESHOLD(4181, PacketType.SET),
    /** Reply payload[0] == 0 means activated. */
    QUERY_ACTIVATION_STATE(75, PacketType.DATA_LOW),
    /** Payload: 7 x u16 (year, month 1-12, day, hour, minute, second, ms). Type 0x50 (official). */
    SET_ACTIVATION_TIMESTAMP(76, PacketType.DATA_ACK),
    /** Payload: 2 ASCII characters (country code), no terminator (official). */
    SET_REGION(22, PacketType.SET),

    // ---- Phase 3 (ids / types checked in decompiled `gcs/core/cmd/d.java`)
    /** Payload: u8 `(start & 3) | (mode & 7) << 2`; start 0 = stop. Reply [00] = accepted. */
    SMART_VIDEO(128, PacketType.SET),
    /** App ack of the drone's 129 smart-video status: payload [00], type 0x50, unsequenced reply. */
    ACK_SMART_VIDEO_STATUS(129, PacketType.DATA_ACK),
    /** Payload: 0x30 = enter bounce, 0x31 = leave. */
    BOUNCE(4179, PacketType.SET),
    /** Centre-of-gravity (level) calibration, payload 1. On the ground. */
    CALIBRATE_CENTER_OF_GRAVITY(4180, PacketType.SET),
    /** Starts the 6-face IMU calibration (reply [00] = started). */
    START_IMU_CALIBRATION(90, PacketType.DATA_LOW),
    /** Polled every 100 ms during the IMU calibration, never retried (official). */
    QUERY_IMU_CALIBRATION(91, PacketType.DATA_LOW),
    /** Reply: [00][ASCII SSID]. */
    QUERY_SSID(17, PacketType.DATA_LOW),
    /** Payload: ASCII SSID (1..32). Reply [00] = stored. */
    SET_SSID(18, PacketType.SET),
    /** Payload: ASCII password (empty = open, or 8..63). Reply [00] = stored. */
    SET_WIFI_PASSWORD(20, PacketType.SET),
    /** Restarts the drone's Wi-Fi so that a new SSID / password takes effect. */
    RESTART_WIFI(25, PacketType.DATA_LOW),
}

/** Identifiers of messages sent by the drone. */
object MessageId {
    const val WIFI = 26
    const val TAKE_PICTURE = 48
    const val LIGHT = 53
    const val FIRMWARE = 69
    const val TIME_REQUEST = 70
    const val ACTIVATION_STATE = 75
    const val ACTIVATION_TIMESTAMP = 76
    const val FLIGHT_DATA = 86
    const val FILE_SIZE = 98
    const val FILE_DATA = 99
    const val SSID = 17
    const val SET_SSID = 18
    const val SET_WIFI_PASSWORD = 20
    const val IMU_CALIBRATION_START = 90
    const val IMU_CALIBRATION_STATUS = 91
    const val FLIP = 92
    const val SMART_VIDEO = 128
    const val SMART_VIDEO_STATUS = 129
    const val BOUNCE = 4179
    const val CENTER_OF_GRAVITY_CALIBRATION = 4180
    const val LOG_HEADER = 4176
    const val LOG_DATA = 4177
}

/**
 * Flip directions, as encoded in the [Command.FLIP] payload. Checked against the official
 * 8-direction swipe pad (`ThreeDTouchView`): 0° forward, 45° → 7, 90° → 3 (right), 135° → 6,
 * 180° → 2 (back), 225° → 5, 270° → 1 (left), 315° → 4.
 */
enum class FlipDirection(val code: Int) {
    FORWARD(0), LEFT(1), BACK(2), RIGHT(3),
    FORWARD_LEFT(4), BACK_LEFT(5), BACK_RIGHT(6), FORWARD_RIGHT(7),
}

/** Smart video modes executed by the firmware ([Command.SMART_VIDEO]). */
enum class SmartVideoMode(val code: Int) {
    /** The drone turns 360° on itself while filming. */
    ROTATE_360(1),
    /** The drone circles around its starting point. */
    CIRCLE(2),
    /** The drone flies up and away backwards. */
    UP_AND_AWAY(3),
    ;

    companion object {
        fun fromCode(code: Int): SmartVideoMode? = entries.firstOrNull { it.code == code }
    }
}
