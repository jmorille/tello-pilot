package com.drone.tello.control

/** Video bitrate presets of the Tello encoder (protocol value in [code]). */
enum class VideoBitrate(val code: Int, val label: String) {
    AUTO(0, "Auto"),
    MBPS_1(1, "1 Mb/s"),
    MBPS_1_5(2, "1,5 Mb/s"),
    MBPS_2(3, "2 Mb/s"),
    MBPS_3(4, "3 Mb/s"),
    MBPS_4(5, "4 Mb/s"),
}

enum class Units { METRIC, IMPERIAL }

/** What target tracking looks for in the video. */
enum class TrackingTarget { PERSON, FACE }

/** Target format of the post-flight re-encoding of recorded videos. */
enum class TranscodeCodec(val suffix: String) { HEVC("hevc"), AV1("av1") }

/** Settings that only affect the app (how the pilot's input is interpreted and shown). */
data class PilotSettings(
    val stickMode: StickMode = StickMode.MODE_2,
    val deadZone: Float = 0.05f,
    val expo: Float = 0.3f,
    val joystickSizeDp: Int = 200,
    val units: Units = Units.METRIC,
    /** Takeoff requires a slide gesture instead of a tap. */
    val confirmTakeoff: Boolean = true,
    /** Start recording on the phone automatically at takeoff. */
    val autoRecord: Boolean = false,
    /** Fade the screen sticks out while the gamepad flies (a touch brings them back). */
    val hideSticksWithPad: Boolean = true,
    /** GPU upscaling + sharpening of the 960x720 video (about 1 ms more). */
    val videoSharpen: Boolean = true,
    /** Re-encode each recorded video once landed (H.264 from the drone is large). */
    val autoTranscode: Boolean = true,
    val transcodeCodec: TranscodeCodec = TranscodeCodec.HEVC,
    /** Keep the original H.264 file next to the re-encoded one. */
    val keepOriginal: Boolean = false,
    /** Missions created by the pilot (the presets come in addition). */
    val customMissions: List<Mission> = emptyList(),
    val trackingTarget: TrackingTarget = TrackingTarget.PERSON,
    /** Gamepad button assignment. */
    val padBindings: GamepadBindings = GamepadBindings.Default,
) {
    val shaping: StickShaping get() = StickShaping(deadZone.coerceIn(0f, 0.5f), expo.coerceIn(0f, 1f))

    companion object {
        val DEAD_ZONE_RANGE = 0f..0.2f
        val EXPO_RANGE = 0f..0.8f
        val JOYSTICK_SIZE_RANGE = 140..280
    }
}

/** Settings stored in the drone, sent on every connection and whenever they change. */
data class DroneSettings(
    val maxAltitudeMeters: Int = 10,
    val lowBatteryPercent: Int = 20,
    /** Exposure compensation in 1/3 EV steps (-9..9 = -3..+3 EV). */
    val exposureSteps: Int = 0,
    val bitrate: VideoBitrate = VideoBitrate.AUTO,
) {
    fun sanitized() = copy(
        maxAltitudeMeters = maxAltitudeMeters.coerceIn(MAX_ALTITUDE_RANGE),
        lowBatteryPercent = lowBatteryPercent.coerceIn(LOW_BATTERY_RANGE),
        exposureSteps = exposureSteps.coerceIn(EXPOSURE_RANGE),
    )

    companion object {
        val MAX_ALTITUDE_RANGE = 2..30
        /** Firmware > 01.03.33.01 refuses values under 15 (official app). */
        val LOW_BATTERY_RANGE = 15..50
        val EXPOSURE_RANGE = -9..9
    }
}
