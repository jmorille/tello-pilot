package com.drone.tellopilot.ui.flight

import com.drone.tello.control.Telemetry
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.WifiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every condition of [Warning.from], in flight and on the ground. */
class WarningTest {

    private val air = FlightData(
        heightMeters = 1f, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 10f,
        batteryPercent = 80, flying = true, onGround = false, hovering = true,
        batteryLow = false, batteryCritical = false, flyMode = 6,
    )
    private val ground = air.copy(flying = false, onGround = true, hovering = false)

    private fun warnings(flight: FlightData, telemetry: Telemetry = Telemetry(), groundTooLong: Boolean = false) =
        Warning.from(flight, telemetry, groundTooLong)

    @Test
    fun `nominal flight raises nothing`() {
        assertTrue(warnings(air).isEmpty())
        assertTrue(warnings(ground).isEmpty())
    }

    @Test
    fun `each drone flag maps to its warning`() {
        val cases = mapOf(
            air.copy(batteryCritical = true) to Warning.BATTERY_CRITICAL,
            air.copy(overheating = true) to Warning.OVERHEATING,
            air.copy(motorErrorCode = 1) to Warning.MOTOR_ERROR,
            air.copy(imuError = true) to Warning.IMU_ERROR,
            air.copy(imuCalibrationState = 2) to Warning.IMU_ERROR,
            air.copy(batteryError = true) to Warning.BATTERY_ERROR,
            air.copy(batteryLow = true) to Warning.BATTERY_LOW,
            air.copy(windWarning = true) to Warning.WIND,
            air.copy(visionSensorError = true) to Warning.VISION_SENSOR,
            air.copy(imuCalibrationState = 1) to Warning.IMU_PREHEATING,
        )
        cases.forEach { (flight, expected) -> assertEquals(flight.toString(), listOf(expected), warnings(flight)) }
    }

    @Test
    fun `imu error and preheating are exclusive states of the calibration`() {
        assertEquals(listOf(Warning.IMU_ERROR), warnings(air.copy(imuError = true, imuCalibrationState = 2)))
        assertEquals(listOf(Warning.IMU_ERROR, Warning.IMU_PREHEATING), warnings(air.copy(imuError = true, imuCalibrationState = 1)))
    }

    @Test
    fun `telemetry flags map to their warning, in flight or not`() {
        assertEquals(listOf(Warning.TOO_DARK), warnings(ground, Telemetry(tooDark = true)))
        assertEquals(listOf(Warning.CAMERA_FAULT), warnings(air, Telemetry(cameraFault = true)))
    }

    @Test
    fun `sensor error is only raised in flight`() {
        assertEquals(listOf(Warning.SENSOR_ERROR), warnings(air.copy(flightControllerError = true)))
        assertTrue(warnings(ground.copy(flightControllerError = true)).isEmpty())
    }

    @Test
    fun `barometer error is only raised in flight`() {
        assertEquals(listOf(Warning.BAROMETER), warnings(air.copy(barometerError = true)))
        assertTrue(warnings(ground.copy(barometerError = true)).isEmpty())
    }

    @Test
    fun `wifi interference warns in flight from the official threshold`() {
        assertEquals(listOf(Warning.WIFI_INTERFERENCE), warnings(air, Telemetry(wifi = WifiStatus(strength = 90, interference = 100))))
        assertEquals(listOf(Warning.WIFI_INTERFERENCE), warnings(air, Telemetry(wifi = WifiStatus(strength = 90, interference = 250))))
        assertTrue("below the threshold", warnings(air, Telemetry(wifi = WifiStatus(strength = 90, interference = 99))).isEmpty())
        assertTrue("on the ground", warnings(ground, Telemetry(wifi = WifiStatus(strength = 90, interference = 200))).isEmpty())
        assertTrue("unknown wifi status", warnings(air, Telemetry(wifi = null)).isEmpty())
    }

    @Test
    fun `ground heat only on the ground and only when asked`() {
        assertEquals(listOf(Warning.GROUND_HEAT), warnings(ground, groundTooLong = true))
        assertTrue(warnings(air, groundTooLong = true).isEmpty())
        assertTrue(warnings(ground, groundTooLong = false).isEmpty())
    }

    @Test
    fun `motor code 206 means not activated, not a failure`() {
        assertTrue(warnings(air.copy(motorErrorCode = 206)).isEmpty())
        assertTrue(warnings(air.copy(motorErrorCode = 0)).isEmpty())
        assertEquals(listOf(Warning.MOTOR_ERROR), warnings(air.copy(motorErrorCode = 207)))
    }

    @Test
    fun `low battery is replaced by critical battery`() {
        assertEquals(listOf(Warning.BATTERY_CRITICAL), warnings(air.copy(batteryLow = true, batteryCritical = true)))
    }

    @Test
    fun `no positioning in flight only, calibration on the ground only`() {
        assertEquals(listOf(Warning.NO_POSITIONING), warnings(air.copy(flyMode = FlightData.FLY_MODE_NO_POSITIONING)))
        assertTrue(warnings(ground.copy(flyMode = FlightData.FLY_MODE_NO_POSITIONING)).isEmpty())
        assertEquals(listOf(Warning.CALIBRATION_NEEDED), warnings(ground.copy(gravityCalibrationNeeded = true)))
        assertTrue(warnings(air.copy(gravityCalibrationNeeded = true)).isEmpty())
    }

    @Test
    fun `everything at once comes sorted by declaration order`() {
        val all = air.copy(
            batteryLow = true, flightControllerError = true, overheating = true, motorErrorCode = 1,
            imuError = true, batteryError = true, barometerError = true, windWarning = true,
            flyMode = FlightData.FLY_MODE_NO_POSITIONING, visionSensorError = true,
        )
        val result = warnings(all, Telemetry(wifi = WifiStatus(50, 120), tooDark = true, cameraFault = true))
        assertEquals(result.sortedBy { it.ordinal }, result)
        assertEquals(
            listOf(
                Warning.SENSOR_ERROR, Warning.OVERHEATING, Warning.MOTOR_ERROR, Warning.IMU_ERROR,
                Warning.BATTERY_ERROR, Warning.BATTERY_LOW, Warning.WIFI_INTERFERENCE, Warning.BAROMETER,
                Warning.WIND, Warning.NO_POSITIONING, Warning.VISION_SENSOR, Warning.TOO_DARK, Warning.CAMERA_FAULT,
            ),
            result,
        )
    }

    @Test
    fun `critical warnings are declared before the information ones`() {
        val firstInfo = Warning.entries.indexOfFirst { !it.critical }
        assertTrue(Warning.entries.drop(firstInfo).none { it.critical })
    }

    @Test
    fun `always shown warnings are the critical ones plus those that matter in flight`() {
        val expected = Warning.entries.filter { it.critical }.toSet() +
            setOf(Warning.BATTERY_LOW, Warning.NO_POSITIONING, Warning.WIFI_INTERFERENCE, Warning.GEOFENCE_OUT, Warning.GEOFENCE_NEAR)
        assertEquals(expected, Warning.entries.filter { it.alwaysShown }.toSet())
        assertFalse(Warning.WIND.alwaysShown)
        assertFalse(Warning.PHONE_HOT.alwaysShown)
        assertTrue(Warning.PHONE_OVERHEATING.alwaysShown)
    }
}
