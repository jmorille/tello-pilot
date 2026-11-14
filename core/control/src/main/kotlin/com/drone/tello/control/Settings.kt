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

/**
 * How the 4:3 drone video fills a wide phone screen: the whole picture (black side bars),
 * cropped to 16:9, or cropped to the full screen width (top and bottom cut).
 */
/**
 * How the 4:3 video fills the screen: FIT/WIDE/FILL keep the proportions (FIT shows the whole
 * picture, WIDE/FILL crop the top and bottom), STRETCH_* show the whole height and stretch the
 * picture horizontally (16:9 or the full screen).
 */
enum class VideoFit { FIT, WIDE, FILL, STRETCH_WIDE, STRETCH_FULL }

/**
 * Information the pilot may show or hide on the flight screen. Critical alerts, the alerts
 * that matter in flight and the throw countdown are not listed: they are always shown.
 */
enum class HudItem { BATTERY, HEIGHT, SPEED, VARIO, FLIGHT_TIME, WIFI, GAMEPAD, RECORDING, HORIZON, MAP, TEMPERATURE, INFO_ALERTS }

/**
 * Look of the telemetry burnt into re-encoded videos: [PILOT] reproduces the flight screen HUD
 * (top panel, horizon, mini-map, alerts), [COMPACT] is the small bottom-left text box.
 */
enum class OverlayStyle { PILOT, COMPACT }

/** Snap positions of the artificial horizon on the flight screen. */
enum class HorizonAnchor {
    /** Middle of the left black band (4:3 video on a wide screen), above the left stick. */
    LEFT,
    /** Bottom centre, above the takeoff / land button (historical place). */
    CENTER,
    /** Middle of the right black band, above the right stick. */
    RIGHT,
}

/**
 * Where the pilot put the artificial horizon. Snapped ([anchor] not null): the place is
 * recomputed from the actual layout (screen size, video framing, sticks), so it stays in the
 * black band after a rotation, on another phone or with another framing. Free ([anchor] null):
 * the centre of the instrument at ([x], [y]), fractions of the screen width and height, then
 * kept on screen and pushed off the sticks and the takeoff / land button.
 */
data class HorizonPlacement(
    val anchor: HorizonAnchor? = HorizonAnchor.CENTER,
    val x: Float = 0.5f,
    val y: Float = DEFAULT_Y,
) {
    /** Finite fractions in 0..1 (a corrupt or hand-edited store never puts it off screen). */
    fun sanitized(): HorizonPlacement = copy(x = x.fraction(0.5f), y = y.fraction(DEFAULT_Y))

    companion object {
        /** About the historical bottom-centre place on a usual phone in landscape. */
        const val DEFAULT_Y = 0.62f
        val Default = HorizonPlacement()

        fun snapped(anchor: HorizonAnchor) = HorizonPlacement(anchor = anchor)

        private fun Float.fraction(default: Float) = if (isFinite()) coerceIn(0f, 1f) else default
    }
}

/** Target format of the post-flight re-encoding of recorded videos. */
enum class TranscodeCodec(val suffix: String) { HEVC("hevc"), AV1("av1") }

/** Settings that only affect the app (how the pilot's input is interpreted and shown). */
data class PilotSettings(
    val stickMode: StickMode = StickMode.MODE_2,
    /** Global stick dead zone (every axis, both flight modes). */
    val deadZone: Float = 0.05f,
    /**
     * Response of each axis in the normal (slow) flight mode: gain, amplitude, expo. The
     * defaults reproduce the former single expo setting (0.3), which seeds both profiles.
     */
    val axisNormal: FlightTuning = FlightTuning.Default,
    /** Response of each axis while the fast mode is on. */
    val axisFast: FlightTuning = FlightTuning.Default,
    /** Stick amplitude in tripod mode (slow, smooth shots). */
    val tripodScale: Float = 0.3f,
    val joystickSizeDp: Int = 200,
    val units: Units = Units.METRIC,
    /** Takeoff requires a slide gesture instead of a tap. */
    val confirmTakeoff: Boolean = true,
    /**
     * Height reached automatically right after the app's takeoff (drone height sensor, i.e.
     * relative to the ground / takeoff surface), 0 = off: the Tello always takes off to ~1 m.
     */
    val takeoffHeight: Float = 0f,
    /** Start recording on the phone automatically at takeoff. */
    val autoRecord: Boolean = false,
    /** Fade the screen sticks out while the gamepad flies (a touch brings them back). */
    val hideSticksWithPad: Boolean = true,
    /** GPU upscaling + sharpening of the 960x720 video (about 1 ms more). */
    val videoSharpen: Boolean = true,
    val videoFit: VideoFit = VideoFit.FIT,
    /** Flight screen items the pilot chose to hide (new items are therefore shown by default). */
    val hiddenHud: Set<HudItem> = emptySet(),
    /** Place of the artificial horizon on the flight screen (moved by a long press then a drag). */
    val horizon: HorizonPlacement = HorizonPlacement.Default,
    /** Re-encode each recorded video once landed (H.264 from the drone is large). */
    val autoTranscode: Boolean = true,
    val transcodeCodec: TranscodeCodec = TranscodeCodec.HEVC,
    /** Keep the original H.264 file next to the re-encoded one. */
    val keepOriginal: Boolean = false,
    /** Burn a telemetry overlay (time, height, speed, climb, battery) into the re-encoded copy. */
    val overlayOnTranscode: Boolean = false,
    /** Look of that overlay; the pilot view follows [hiddenHud]. */
    val overlayStyle: OverlayStyle = OverlayStyle.PILOT,
    /** Missions created by the pilot (the presets come in addition). */
    val customMissions: List<Mission> = emptyList(),
    val trackingTarget: TrackingTarget = TrackingTarget.PERSON,
    /** Gamepad button assignment. */
    val padBindings: GamepadBindings = GamepadBindings.Default,
    /**
     * Safety bubble of the automatic flights: a cylinder around their start point (radius
     * [geofenceRadius], ceiling = the drone's max altitude). Leaving it aborts the flight.
     */
    val geofence: Boolean = true,
    val geofenceRadius: Float = GEOFENCE_RADIUS_DEFAULT,
    /** Manual flight: alert (never blocks the sticks) beyond [geofenceRadius] from the takeoff point. */
    val geofenceManualAlert: Boolean = false,
    /** Shock / crash detection: aborts an automatic flight, alerts in manual flight. */
    val impactDetection: Boolean = true,
    /** Missions turn toward each horizontal move first, so that the camera sees where it goes. */
    val missionNoseFirst: Boolean = true,
    /** Missions never fly faster than [missionMaxSpeed] horizontally. */
    val missionSpeedLimit: Boolean = true,
    /** m/s. */
    val missionMaxSpeed: Float = MISSION_SPEED_DEFAULT,
    /** Tone + spoken announcement (and vibration) when the drone battery gets low in flight. */
    val batteryVoiceAlert: Boolean = true,
    /** Fast mode at launch: always off (default, safer) or as it was when the app was last used. */
    val fastModeStartup: FastModeStartup = FastModeStartup.ALWAYS_NORMAL,
) {
    /** Mission runner tuning that follows these settings. */
    fun missionConfig(): MissionConfig = MissionConfig(
        noseFirst = missionNoseFirst,
        maxHorizontalSpeed = missionMaxSpeed.coerceIn(MISSION_SPEED_RANGE).takeIf { missionSpeedLimit },
    )

    /** Bubble of the automatic flights, null when disabled; its ceiling is the drone's max altitude. */
    fun geofenceConfig(maxAltitudeMeters: Int): GeofenceConfig? =
        if (!geofence) null else GeofenceConfig(geofenceRadius.coerceIn(GEOFENCE_RADIUS_RANGE), maxAltitudeMeters.toFloat())

    fun shows(item: HudItem): Boolean = item !in hiddenHud

    /** Everything the stick mixer applies to the pilot's sticks, every value within its bounds. */
    val stickTuning: StickTuning get() = StickTuning(
        deadZone = (if (deadZone.isNaN()) 0.05f else deadZone).coerceIn(0f, 0.5f),
        normal = axisNormal.sanitized(),
        fast = axisFast.sanitized(),
    )

    /** Axis profile of a flight mode. */
    fun axisTuning(fastMode: Boolean): FlightTuning = if (fastMode) axisFast else axisNormal

    fun withAxisTuning(fastMode: Boolean, tuning: FlightTuning): PilotSettings =
        if (fastMode) copy(axisFast = tuning) else copy(axisNormal = tuning)

    companion object {
        val DEAD_ZONE_RANGE = 0f..0.2f
        val TRIPOD_RANGE = 0.15f..0.6f
        val TAKEOFF_HEIGHT_RANGE = 0.5f..3f
        const val TAKEOFF_HEIGHT_DEFAULT = 1.5f
        val JOYSTICK_SIZE_RANGE = 140..280
        val GEOFENCE_RADIUS_RANGE = 2f..30f
        const val GEOFENCE_RADIUS_DEFAULT = 5f
        val MISSION_SPEED_RANGE = 0.2f..0.6f
        const val MISSION_SPEED_DEFAULT = 0.5f
    }
}

/** Flight mode the app starts in. */
enum class FastModeStartup {
    /** Always the normal (slow) mode: the safe default. */
    ALWAYS_NORMAL,
    /** The mode of the last session (unless the tripod mode was on, which turns the fast mode off). */
    REMEMBER,
}

/** Settings stored in the drone, sent on every connection and whenever they change. */
data class DroneSettings(
    val maxAltitudeMeters: Int = 10,
    val lowBatteryPercent: Int = 20,
    /** Exposure compensation in 1/3 EV steps (-9..9 = -3..+3 EV). */
    val exposureSteps: Int = 0,
    val bitrate: VideoBitrate = VideoBitrate.AUTO,
    /**
     * Max tilt angle of the fast mode, degrees (cmd 4184). Official app: slider 15..25°, shown
     * in fast mode only, 25° by default.
     */
    val maxTiltDeg: Float = MAX_TILT_DEFAULT,
) {
    fun sanitized() = copy(
        maxAltitudeMeters = maxAltitudeMeters.coerceIn(MAX_ALTITUDE_RANGE),
        lowBatteryPercent = lowBatteryPercent.coerceIn(LOW_BATTERY_RANGE),
        exposureSteps = exposureSteps.coerceIn(EXPOSURE_RANGE),
        maxTiltDeg = sanitizeMaxTilt(maxTiltDeg),
    )

    companion object {
        val MAX_ALTITUDE_RANGE = 2..30
        /** Firmware > 01.03.33.01 refuses values under 15 (official app). */
        val LOW_BATTERY_RANGE = 15..50
        val EXPOSURE_RANGE = -9..9
        val MAX_TILT_RANGE = 15f..25f
        const val MAX_TILT_DEFAULT = 25f

        /** Within [MAX_TILT_RANGE], whole degrees (the slider's steps); NaN / infinite: the default. */
        fun sanitizeMaxTilt(degrees: Float): Float =
            if (degrees.isFinite()) Math.round(degrees).toFloat().coerceIn(MAX_TILT_RANGE) else MAX_TILT_DEFAULT
    }
}
