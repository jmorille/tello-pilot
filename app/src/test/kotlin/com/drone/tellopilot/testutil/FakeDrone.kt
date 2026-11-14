package com.drone.tellopilot.testutil

import com.drone.tello.control.LinkState
import com.drone.tello.control.ManeuverState
import com.drone.tello.control.ModeCheck
import com.drone.tello.control.Telemetry
import com.drone.tello.control.TelloController
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.LogRecord
import com.drone.tello.protocol.StickInput
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/** Flight data as the drone reports it (only the fields the app reads are meaningful). */
internal fun flightData(
    flying: Boolean = true,
    height: Float = 1f,
    battery: Int = 80,
    low: Boolean = false,
    critical: Boolean = false,
    motorsOn: Boolean = flying,
    gravityCalibration: Int = 0,
) = FlightData(
    heightMeters = height, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 10f,
    batteryPercent = battery, flying = flying, onGround = !flying, motorsOn = motorsOn, hovering = flying,
    batteryLow = low, batteryCritical = critical, flyMode = 6, gravityCalibrationStatus = gravityCalibration,
)

/** IMU record rolled by [rollDeg] (a new instance each time, as the log stream gives them). */
internal fun imu(rollDeg: Float = 0f, vn: Float = 0f): LogRecord.Imu {
    val half = Math.toRadians(rollDeg / 2.0)
    return LogRecord.Imu(
        qw = kotlin.math.cos(half).toFloat(), qx = kotlin.math.sin(half).toFloat(), qy = 0f, qz = 0f,
        velocityNorth = vn, velocityEast = 0f, velocityDown = 0f, temperatureC = null,
    )
}

internal fun odometry(x: Float, y: Float, valid: Boolean = true) = LogRecord.VisualOdometry(x, y, 0f, valid)

/**
 * The drone seen through a [TelloController] test double: the test drives the link, the
 * telemetry and the manoeuvres; every stick written by the app (pilot or autopilot) is kept.
 */
internal class FakeDrone {
    val linkFlow = MutableStateFlow(LinkState.CONNECTED)
    val telemetryFlow = MutableStateFlow(Telemetry())
    val maneuversFlow = MutableStateFlow(ManeuverState())

    /** Flight data packets received (the autopilot's freshness check). */
    @Volatile var samples = 0L
    @Volatile var landing = false
    val sticks: MutableList<StickInput> = java.util.Collections.synchronizedList(mutableListOf())
    val lastSticks: StickInput? get() = synchronized(sticks) { sticks.lastOrNull() }

    val controller: TelloController = mockk(relaxed = true)

    init {
        val c = controller
        every { c.link } returns linkFlow
        every { c.telemetry } returns telemetryFlow
        every { c.maneuvers } returns maneuversFlow
        every { c.flightSamples } answers { samples }
        every { c.isLanding } answers { landing }
        every { c.isConnected } answers { linkFlow.value == LinkState.CONNECTED }
        every { c.setSticks(any()) } answers { sticks += firstArg<StickInput>() }
        coEvery { c.takeoff() } returns true
        coEvery { c.throwTakeoff() } returns true
        coEvery { c.land() } answers { landing = true; true }
        coEvery { c.palmLand() } returns true
        coEvery { c.stopSmartVideo() } returns true
        coEvery { c.takePicture() } returns true
        coEvery { c.requestKeyFrame() } returns true
        coEvery { c.setOnboardRecording(any()) } returns true
        coEvery { c.flip(any(), any()) } returns ModeCheck.OK
        coEvery { c.setBounce(any()) } returns ModeCheck.OK
        coEvery { c.startSmartVideo(any()) } returns ModeCheck.OK
    }

    /** A new flight data packet. */
    fun flight(data: FlightData) {
        telemetryFlow.value = telemetryFlow.value.copy(flight = data)
        samples++
    }

    fun telemetry(update: (Telemetry) -> Telemetry) {
        telemetryFlow.value = update(telemetryFlow.value)
        samples++
    }

    /**
     * Crude flying drone, 10 Hz flight data: the height follows the throttle (1 m/s at full
     * stick), the battery is [battery]; stops when [running] turns false.
     */
    var running = true
    var battery: (FlightData) -> FlightData = { it }

    fun simulate(scope: TestScope, startHeight: Float = 1f) {
        var height = startHeight
        scope.backgroundScope.launch {
            while (isActive && running) {
                delay(100)
                height += (lastSticks?.throttle ?: 0f) * 0.1f
                flight(battery(flightData(flying = true, height = height)))
            }
        }
    }
}
