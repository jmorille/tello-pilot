package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.DroneLogParser
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.StickInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Controller behaviour not covered by [TelloControllerTest]: fast mode, landing limits, incoming messages, robustness. */
@OptIn(ExperimentalCoroutinesApi::class)
class TelloControllerSafetyTest {

    private class Session(val scope: TestScope) {
        val transport = FakeTransport(scope)
        val controller = TelloController(transport, clock = { scope.testScheduler.currentTime })
        lateinit var runJob: Job
        val events = mutableListOf<DroneEvent>()

        fun start() {
            runJob = scope.backgroundScope.launch { controller.run() }
            scope.backgroundScope.launch { controller.events.collect { events += it } }
            scope.runCurrent()
        }

        fun connect() {
            transport.ack()
            scope.backgroundScope.launch {
                while (isActive) {
                    transport.droneHeartbeat(); delay(100)
                }
            }
            scope.runCurrent()
        }

        fun ready(battery: Int = 80, heightDm: Int = 10) {
            connect()
            transport.flight(battery = battery, heightDm = heightDm)
            scope.runCurrent()
        }

        fun advance(ms: Long) = scope.advanceTimeBy(ms)
        fun runCurrent() = scope.runCurrent()
    }

    private fun session(block: suspend Session.() -> Unit) = runTest {
        Session(this).apply { start(); block() }
    }

    // --- Fast mode ------------------------------------------------------------------------

    @Test
    fun `fast mode sets the speed bit on every stick packet, even neutral ones`() = session {
        connect()
        controller.setFastMode(true)
        advance(100)
        val fast = transport.sticks().takeLast(3)
        assertTrue(fast.all { decodeSticks(it)[4] == 1L })
        assertTrue(fast.all { it.isNeutralStick() })

        controller.setSticks(StickInput(pitch = 1f)) // the pilot's input carries its own flag
        advance(40)
        assertEquals(0L, decodeSticks(transport.sticks().last())[4])

        controller.setFastMode(true)
        controller.setFastMode(false)
        advance(40)
        assertEquals(listOf(1024L, 1684L, 1024L, 1024L, 0L), decodeSticks(transport.sticks().last()))
    }

    // --- Landing --------------------------------------------------------------------------

    @Test
    fun `a landing request is given up after its timeout while the link is up`() = session {
        connect()
        transport.flightData(flying = true)
        assertTrue(controller.land())
        advance(29_000)
        assertTrue(controller.isLanding, "still repeated after 29 s")
        advance(1_500)
        assertFalse(controller.isLanding)
        val count = transport.count(Command.LAND)
        advance(2_000)
        assertTrue(transport.count(Command.LAND) - count <= 5, "only retries of the last request")
    }

    @Test
    fun `a cancelled landing request is no longer repeated`() = session {
        connect()
        transport.flightData(flying = true)
        controller.land()
        advance(400)
        controller.cancelLandingRequest()
        assertFalse(controller.isLanding)
        advance(1_000)
        val requests = transport.all(Command.LAND).map { it.packet!!.sequence }.toSet()
        advance(2_000)
        assertEquals(requests, transport.all(Command.LAND).map { it.packet!!.sequence }.toSet())
    }

    @Test
    fun `land is refused only when the session is not running`() = runTest {
        val controller = TelloController(FakeTransport(this), clock = { testScheduler.currentTime })
        assertFalse(controller.land())
        assertFalse(controller.isLanding)
    }

    @Test
    fun `palm land is acknowledged and retransmitted like takeoff`() = session {
        connect()
        assertTrue(controller.palmLand())
        val first = transport.all(Command.PALM_LAND).first().packet!!
        assertEquals(0x48, first.packetType)
        assertEquals(0, first.payload.size)
        advance(120)
        assertTrue(transport.count(Command.PALM_LAND) >= 2)
        assertEquals(setOf(first.sequence), transport.all(Command.PALM_LAND).map { it.packet!!.sequence }.toSet())
    }

    // --- Commands -------------------------------------------------------------------------

    @Test
    fun `onboard recording flag is sent as 1 then 0`() = session {
        connect()
        controller.setOnboardRecording(true)
        controller.setOnboardRecording(false)
        val sent = transport.all(Command.SET_RECORDING).map { it.packet!! }.distinctBy { it.sequence }
        assertEquals(listOf(1, 0), sent.map { it.payload.single().toInt() })
        assertTrue(sent.all { it.packetType == 0x68 })
    }

    @Test
    fun `the drone low-battery threshold gates bounce and smart video, flips keep their own 55 percent`() = session {
        ready(battery = 35)
        controller.applyDroneSettings(DroneSettings(lowBatteryPercent = 40))
        assertEquals(ModeCheck.LOW_BATTERY, controller.setBounce(true))
        assertEquals(ModeCheck.LOW_BATTERY, controller.startSmartVideo(SmartVideoMode.ROTATE_360))
        controller.applyDroneSettings(DroneSettings(lowBatteryPercent = 30))
        assertEquals(ModeCheck.OK, controller.setBounce(true))
        assertEquals(ModeCheck.LOW_BATTERY, controller.flip(FlipDirection.LEFT))
        assertArrayEquals(byteArrayOf(30, 0), transport.all(Command.SET_LOW_BATTERY_THRESHOLD).last().packet!!.payload)
    }

    @Test
    fun `max altitude setting is sent as a little-endian u16`() = session {
        connect()
        controller.applyDroneSettings(DroneSettings(maxAltitudeMeters = 25))
        assertArrayEquals(byteArrayOf(25, 0), transport.all(Command.SET_MAX_ALTITUDE).last().packet!!.payload)
        controller.applyDroneSettings(DroneSettings(maxAltitudeMeters = 99)) // sanitised to 30
        assertArrayEquals(byteArrayOf(30, 0), transport.all(Command.SET_MAX_ALTITUDE).last().packet!!.payload)
    }

    @Test
    fun `manoeuvres refused by the drone are reported`() = session {
        ready()
        controller.setBounce(true)
        val bounce = transport.all(Command.BOUNCE).first().packet!!
        transport.droneMessage(MessageId.BOUNCE, byteArrayOf(1), sequence = bounce.sequence)
        runCurrent()
        assertFalse(controller.maneuvers.value.bounce)

        controller.startSmartVideo(SmartVideoMode.CIRCLE)
        val video = transport.all(Command.SMART_VIDEO).first().packet!!
        transport.droneMessage(MessageId.SMART_VIDEO, byteArrayOf(1), sequence = video.sequence)
        runCurrent()
        assertNull(controller.maneuvers.value.requestedSmartVideo)

        controller.flip(FlipDirection.BACK)
        transport.droneMessage(MessageId.FLIP, byteArrayOf(1))
        runCurrent()
        assertEquals(
            listOf(
                DroneEvent.ManeuverRefused(FlightMode.Bounce),
                DroneEvent.ManeuverRefused(FlightMode.SmartVideo(SmartVideoMode.CIRCLE)),
                DroneEvent.ManeuverRefused(FlightMode.Flip),
            ),
            events,
        )
    }

    @Test
    fun `leaving bounce refused is not reported as a refused manoeuvre`() = session {
        ready()
        controller.setBounce(false)
        val leave = transport.all(Command.BOUNCE).first().packet!!
        transport.droneMessage(MessageId.BOUNCE, byteArrayOf(1), sequence = leave.sequence)
        runCurrent()
        assertTrue(events.isEmpty())
    }

    // --- Incoming messages ----------------------------------------------------------------

    @Test
    fun `wifi and light messages update the telemetry, short ones are ignored`() = session {
        connect()
        transport.droneMessage(MessageId.WIFI, byteArrayOf(72, 5))
        transport.droneMessage(MessageId.LIGHT, byteArrayOf(1))
        runCurrent()
        assertEquals(72, controller.telemetry.value.wifi!!.strength)
        assertEquals(5, controller.telemetry.value.wifi!!.interference)
        assertTrue(controller.telemetry.value.tooDark)
        transport.droneMessage(MessageId.LIGHT, byteArrayOf(0))
        transport.droneMessage(MessageId.LIGHT, ByteArray(0)) // truncated: ignored
        runCurrent()
        assertFalse(controller.telemetry.value.tooDark)
    }

    /** A 4177 payload holding one IMU record (identity attitude, 42.5 °C). */
    private fun imuLogPayload(yaw90: Boolean = false): ByteArray {
        val data = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN)
        val h = Math.sqrt(0.5).toFloat()
        if (yaw90) data.putFloat(48, h).putFloat(60, h) else data.putFloat(48, 1f)
        data.putShort(106, 4250)
        val body = data.array()
        val length = DroneLogParser.HEADER + body.size + DroneLogParser.CRC16
        val record = ByteArray(length)
        record[0] = 0x55
        record[1] = length.toByte(); record[2] = (length ushr 8).toByte()
        record[4] = DroneLogParser.TYPE_IMU.toByte(); record[5] = (DroneLogParser.TYPE_IMU ushr 8).toByte()
        body.copyInto(record, DroneLogParser.HEADER) // key 0: no XOR
        return byteArrayOf(0) + record
    }

    @Test
    fun `log stream 4177 updates attitude and IMU temperature`() = session {
        connect()
        transport.droneMessage(MessageId.LOG_DATA, imuLogPayload())
        runCurrent()
        val t = controller.telemetry.value
        assertNotNull(t.attitude)
        assertEquals(0f, t.attitude!!.yawDeg, 0.01f)
        assertEquals(42.5f, t.imuTemperatureC!!, 0.01f)

        transport.droneMessage(MessageId.LOG_DATA, imuLogPayload(yaw90 = true))
        runCurrent()
        assertEquals(90f, controller.telemetry.value.attitude!!.yawDeg, 0.1f)

        // Garbage keeps the last good values.
        transport.droneMessage(MessageId.LOG_DATA, byteArrayOf(0, 0x55, 1, 2))
        transport.droneMessage(MessageId.LOG_DATA, byteArrayOf(1, 2, 3))
        runCurrent()
        assertEquals(90f, controller.telemetry.value.attitude!!.yawDeg, 0.1f)
    }

    @Test
    fun `a failing diagnostics sink never breaks the session`() = session {
        controller.diagnostics = { error("sink down") }
        connect()
        repeat(201) { transport.droneMessage(MessageId.LOG_DATA, imuLogPayload()) }
        advance(100)
        assertTrue(runJob.isActive)
        assertEquals(LinkState.CONNECTED, controller.link.value)
    }

    @Test
    fun `random valid-checksum packets of every known id never break the session`() = session {
        ready()
        val ids = listOf(
            MessageId.FLIGHT_DATA, MessageId.WIFI, MessageId.LIGHT, MessageId.LOG_DATA, MessageId.LOG_HEADER,
            MessageId.TIME_REQUEST, MessageId.FIRMWARE, MessageId.ACTIVATION_STATE, MessageId.ACTIVATION_TIMESTAMP,
            MessageId.TAKE_PICTURE, MessageId.FILE_SIZE, MessageId.FILE_DATA, MessageId.SSID, MessageId.SET_SSID,
            MessageId.SET_WIFI_PASSWORD, MessageId.IMU_CALIBRATION_START, MessageId.IMU_CALIBRATION_STATUS,
            MessageId.FLIP, MessageId.SMART_VIDEO, MessageId.SMART_VIDEO_STATUS, MessageId.BOUNCE,
            MessageId.CENTER_OF_GRAVITY_CALIBRATION, 1234,
        )
        val random = Random(42)
        repeat(20) { round ->
            ids.forEach { id ->
                val payload = random.nextBytes(random.nextInt(0, 40))
                transport.droneMessage(id, payload, sequence = random.nextInt(0, 0x10000))
            }
            // Raw noise too: truncated packets, bad checksums, bare magic bytes.
            transport.droneSends(random.nextBytes(random.nextInt(0, 30)))
            transport.droneSends(byteArrayOf(0xCC.toByte()) + random.nextBytes(round))
            advance(30)
        }
        assertTrue(runJob.isActive, "session still running")
        // Still flying the drone: a stick change is sent at once.
        controller.setSticks(StickInput(yaw = 1f))
        advance(20)
        assertEquals(1684L, decodeSticks(transport.sticks().last())[3])
    }
}
