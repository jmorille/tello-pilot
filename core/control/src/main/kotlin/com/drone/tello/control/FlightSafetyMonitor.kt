package com.drone.tello.control

/**
 * Safety checks that run during the whole flight (manual or automatic), fed with every
 * telemetry update: shock detection ([ImpactDetector]) and the manual-flight bubble alert
 * (centred on the first position after takeoff). It only reports: what to do (alert, end an
 * automatic flight) is up to the caller; the pilot's sticks are never touched. Each check
 * does nothing when its setting is off. Pure, clock-driven, single-threaded.
 */
class FlightSafetyMonitor(impactConfig: ImpactConfig = ImpactConfig()) {

    data class Report(
        /** Shock detected by this update. */
        val impact: ImpactKind? = null,
        /** Manual flight beyond the bubble radius (alert setting on). */
        val outsideFence: Boolean = false,
    )

    private val impacts = ImpactDetector(impactConfig)
    private var fence: Geofence? = null
    private var wasFlying = false
    private var lastImu: Any? = null
    private var lastOdometry: Any? = null

    /** No shock detection for [durationMs] (a flip or another manoeuvre was just commanded). */
    fun suppressImpacts(nowMs: Long, durationMs: Long) = impacts.suppress(nowMs, durationMs)

    /**
     * [maneuver]: the drone is flying something by itself or that shakes it on purpose
     * (landing requested, smart video, bounce…); a landing reported by the drone counts too.
     */
    fun onTelemetry(telemetry: Telemetry, pilot: PilotSettings, maneuver: Boolean, nowMs: Long): Report {
        val flight = telemetry.flight
        val flying = flight?.flying == true
        if (flying && !wasFlying) impacts.suppress(nowMs, impacts.config.takeoffQuietMs)
        wasFlying = flying

        var impact: ImpactKind? = null
        val imu = telemetry.imu
        if (imu != null && imu !== lastImu) {
            lastImu = imu
            if (pilot.impactDetection) {
                val attitude = imu.attitude
                impact = impacts.onSample(
                    nowMs, imu.velocityNorth, imu.velocityEast, imu.velocityDown,
                    attitude.rollDeg, attitude.pitchDeg,
                    flying = flying, maneuver = maneuver || flight?.isLanding == true,
                )
            }
        }
        if (!pilot.impactDetection) impacts.suppress(nowMs, impacts.config.maneuverQuietMs)

        var outside = false
        if (pilot.geofenceManualAlert && flying) {
            val f = fence ?: Geofence(
                GeofenceConfig(radiusMeters = pilot.geofenceRadius.coerceIn(PilotSettings.GEOFENCE_RADIUS_RANGE), maxHeightMeters = Float.MAX_VALUE),
            ).also { fence = it }
            val odometry = telemetry.odometry
            if (odometry != null && odometry !== lastOdometry) {
                lastOdometry = odometry
                f.onOdometry(odometry.x, odometry.y, odometry.positionValid, nowMs)
            }
            outside = f.status(null, nowMs) == FenceStatus.OUTSIDE
        } else {
            // A new flight (or the setting turned on) starts a new bubble.
            fence = null
            lastOdometry = telemetry.odometry
        }
        return Report(impact, outside)
    }
}
