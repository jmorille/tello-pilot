package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.ImuFace
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.PacketCodec
import com.drone.tello.protocol.StickInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelloControllerTest {

    private class Session(val scope: TestScope) {
        val transport = FakeTransport(scope)
        val controller = TelloController(transport, clock = { scope.testScheduler.currentTime })
        lateinit var runJob: Job
        private var heartbeat: Job? = null

        fun start() {
            runJob = scope.backgroundScope.launch { controller.run() }
            scope.runCurrent()
        }

        /** Drone answers the handshake and keeps sending telemetry every 100 ms. */
        fun connect() {
            transport.ack()
            heartbeat?.cancel()
            heartbeat = scope.backgroundScope.launch {
                while (isActive) {
                    transport.droneHeartbeat(); delay(100)
                }
            }
            scope.runCurrent()
        }

        fun droneGoesSilent() = heartbeat?.cancel()
        fun advance(ms: Long) = scope.advanceTimeBy(ms)
    }

    private fun session(block: suspend Session.() -> Unit) = runTest {
        Session(this).apply { start(); block() }
    }

    @Nested
    inner class Connection {
        @Test
        fun `handshake is repeated every 500 ms until the drone answers`() = session {
            advance(1_600)
            assertEquals(4, transport.handshakes())
            assertEquals(LinkState.CONNECTING, controller.link.value)
        }

        @Test
        fun `only neutral sticks are sent before the connection`() = session {
            controller.setSticks(StickInput(pitch = 1f))
            advance(300)
            assertTrue(transport.sticks().isNotEmpty())
            // user input given before the connection is discarded on connect
            connect()
            advance(40)
            assertTrue(transport.sticks().all { it.isNeutralStick() })
        }

        @Test
        fun `conn_ack connects, sets video mode, disables EIS and requests a key frame`() = session {
            connect()
            assertEquals(LinkState.CONNECTED, controller.link.value)
            assertArrayEquals(byteArrayOf(0), transport.all(Command.SET_VIDEO_MODE).first().packet!!.payload)
            assertArrayEquals(byteArrayOf(0), transport.all(Command.SET_EIS).first().packet!!.payload)
            assertEquals(1, transport.count(Command.REQUEST_KEY_FRAME))
        }

        @Test
        fun `conn_req heartbeat every second while connected`() = session {
            connect()
            val before = transport.handshakes()
            advance(3_050)
            assertTrue(transport.handshakes() - before in 3..4, "heartbeats: ${transport.handshakes() - before}")
        }

        @Test
        fun `activation state and firmware are queried on connect`() = session {
            connect()
            assertEquals(1, transport.count(Command.QUERY_ACTIVATION_STATE))
            assertEquals(1, transport.count(Command.QUERY_FIRMWARE))
        }

        @Test
        fun `a brand-new drone is activated with a timestamp, retried until it reports activated`() = session {
            connect()
            transport.droneMessage(MessageId.ACTIVATION_STATE, byteArrayOf(1))
            transport.droneMessage(MessageId.ACTIVATION_STATE, byteArrayOf(1))
            scope.runCurrent()
            assertEquals(false, controller.telemetry.value.activated)
            val stamp = transport.all(Command.SET_ACTIVATION_TIMESTAMP)
            assertEquals(1, stamp.map { it.packet!!.sequence }.toSet().size, "not repeated immediately")
            assertEquals(14, stamp.first().packet!!.payload.size)
            assertEquals(0, transport.count(Command.SET_REGION), "region is only for RoboMaster TT")

            // reply to 76 → query 75 again
            val queries = transport.count(Command.QUERY_ACTIVATION_STATE)
            transport.droneMessage(MessageId.ACTIVATION_TIMESTAMP, byteArrayOf(0))
            scope.runCurrent()
            assertTrue(transport.count(Command.QUERY_ACTIVATION_STATE) > queries)

            // the 76 was lost: still not activated 3 s later → activated again
            advance(3_100)
            transport.droneMessage(MessageId.ACTIVATION_STATE, byteArrayOf(1))
            scope.runCurrent()
            assertEquals(2, transport.all(Command.SET_ACTIVATION_TIMESTAMP).map { it.packet!!.sequence }.toSet().size)
        }

        @Test
        fun `an activated drone is left untouched`() = session {
            connect()
            transport.droneMessage(MessageId.ACTIVATION_STATE, byteArrayOf(0))
            scope.runCurrent()
            assertEquals(true, controller.telemetry.value.activated)
            assertEquals(0, transport.count(Command.SET_ACTIVATION_TIMESTAMP) + transport.count(Command.SET_REGION))
        }

        @Test
        fun `a duplicate conn_ack does not reset the sticks`() = session {
            connect()
            controller.setSticks(StickInput(roll = 1f))
            transport.ack()
            advance(10)
            assertEquals(1f, controller.currentSticks.roll)
        }

        @Test
        fun `run fails when the receiver ends so that the owner restarts the session`() = runTest {
            val transport = object : ControlTransport {
                override val videoPort = 7777
                override suspend fun send(datagram: ByteArray) = Unit
                override val incoming: Flow<ByteArray> = emptyFlow()
            }
            val scheduler = testScheduler
            val result = backgroundScope.async { runCatching { TelloController(transport) { scheduler.currentTime }.run() } }
            advanceTimeBy(100)
            assertTrue(result.await().isFailure)
        }
    }

    @Nested
    inner class Sticks {
        @Test
        fun `sticks are streamed at 50 Hz while connected`() = session {
            connect()
            val before = transport.sticks().size
            advance(1_000)
            val perSecond = transport.sticks().size - before
            assertTrue(perSecond in 49..51, "expected ~50 stick packets/s, got $perSecond")
            val gaps = transport.sticks().zipWithNext { a, b -> b.timeMs - a.timeMs }
            assertTrue(gaps.all { it <= 20 }, "keep-alive gap above 20 ms: ${gaps.max()}")
        }

        @Test
        fun `a stick movement is sent within 10 ms instead of waiting for the next tick`() = session {
            connect()
            advance(41) // somewhere between two keep-alive ticks
            val movedAt = scope.testScheduler.currentTime
            controller.setSticks(StickInput(roll = 1f))
            advance(11)
            val first = transport.sticks().first { decodeSticks(it)[0] == 1684L }
            val latency = first.timeMs - movedAt
            assertTrue(latency <= 10, "stick latency $latency ms")
        }

        @Test
        fun `fast stick updates are rate limited to protect the link`() = session {
            connect()
            advance(20)
            val before = transport.sticks().size
            repeat(100) { i ->
                controller.setSticks(StickInput(yaw = (i % 2).toFloat()))
                advance(1)
            }
            val sent = transport.sticks().size - before
            assertTrue(sent <= 11, "too many stick packets: $sent in 100 ms (max 100 Hz)")
        }

        @Test
        fun `axes are mapped to the right bits and clamped`() = session {
            connect()
            controller.setSticks(StickInput(roll = 0.5f, pitch = -2f, throttle = Float.NaN, yaw = 1f, fastMode = true))
            advance(15)
            assertEquals(listOf(1354L, 364L, 1024L, 1684L, 1L), decodeSticks(transport.sticks().last()))
        }

        @Test
        fun `releasing the sticks centers them but keeps fast mode`() = session {
            connect()
            controller.setSticks(StickInput(roll = 1f, throttle = 1f, fastMode = true))
            controller.releaseSticks()
            advance(15)
            assertEquals(NEUTRAL_AXES + 1L, decodeSticks(transport.sticks().last()))
        }
    }

    @Nested
    inner class Commands {
        @Test
        fun `flight commands are refused while not connected`() = session {
            assertFalse(controller.takeoff())
            assertEquals(ModeCheck.NOT_CONNECTED, controller.flip(FlipDirection.LEFT))
            assertFalse(controller.palmLand())
            assertEquals(0, transport.count(Command.TAKEOFF) + transport.count(Command.FLIP) + transport.count(Command.PALM_LAND))
        }

        @Test
        fun `flight commands carry the expected ids, payloads and increasing sequence`() = session {
            connect()
            transport.flight(heightDm = 20)
            advance(FlightModeRules.FLIP_MIN_FLYING_MS)
            transport.flight(heightDm = 20) // fresh flight data (flips need it < 500 ms old)
            scope.runCurrent()
            assertTrue(controller.takeoff())
            assertEquals(ModeCheck.OK, controller.flip(FlipDirection.BACK_RIGHT))
            assertTrue(controller.throwTakeoff())
            assertTrue(controller.palmLand())

            val takeoff = transport.all(Command.TAKEOFF).first().packet!!
            val flip = transport.all(Command.FLIP).first().packet!!
            val thrown = transport.all(Command.THROW_TAKEOFF).first().packet!!
            val palm = transport.all(Command.PALM_LAND).first().packet!!
            assertEquals(0x68, takeoff.packetType)
            assertEquals(0x70, flip.packetType)
            assertArrayEquals(byteArrayOf(FlipDirection.BACK_RIGHT.code.toByte()), flip.payload)
            assertEquals(0x48, thrown.packetType)
            assertEquals(0, thrown.payload.size)
            assertEquals(0x48, palm.packetType)
            assertEquals(0, palm.payload.size, "palm land has no payload (11-byte packet)")
            assertTrue(flip.sequence == takeoff.sequence + 1 && thrown.sequence == flip.sequence + 1)
        }

        @Test
        fun `acknowledged commands are retransmitted every 50 ms up to 5 times`() = session {
            connect()
            controller.takeoff()
            advance(1_000)
            val sends = transport.all(Command.TAKEOFF)
            assertEquals(6, sends.size, "1 send + 5 retries")
            assertTrue(sends.map { it.packet!!.sequence }.toSet().size == 1, "same sequence on retries")
            val gaps = sends.zipWithNext { a, b -> b.timeMs - a.timeMs }
            assertTrue(gaps.all { it in 40..70 }, "retry spacing $gaps")
        }

        @Test
        fun `retransmission stops when the drone acknowledges`() = session {
            connect()
            controller.takeoff()
            transport.droneAcks(transport.all(Command.TAKEOFF).first())
            advance(500)
            assertEquals(1, transport.count(Command.TAKEOFF))
        }

        @Test
        fun `flips are not retransmitted`() = session {
            connect()
            transport.flight(heightDm = 20)
            advance(FlightModeRules.FLIP_MIN_FLYING_MS)
            transport.flight(heightDm = 20) // fresh flight data (flips need it < 500 ms old)
            scope.runCurrent()
            controller.flip(FlipDirection.FORWARD)
            advance(500)
            assertEquals(1, transport.count(Command.FLIP), "a repeated flip would flip twice")
        }

        @Test
        fun `key frame requests are throttled`() = session {
            connect()
            advance(200)
            assertTrue(controller.requestKeyFrame())
            assertFalse(controller.requestKeyFrame())
            advance(100)
            assertTrue(controller.requestKeyFrame())
        }

        @Test
        fun `sequence wraps from 65535 to 1 and never uses 0`() = session {
            connect()
            repeat(65_540) { controller.palmLand() }
            val seqs = transport.all(Command.PALM_LAND).map { it.packet!!.sequence }
            assertTrue(seqs.all { it in 1..0xFFFF })
            assertTrue(seqs.contains(0xFFFF))
        }

        @Test
        fun `an ack with the same sequence but another id does not stop retries`() = session {
            connect()
            controller.takeoff()
            val sent = transport.all(Command.TAKEOFF).first().packet!!
            transport.droneMessage(Command.LAND.id, byteArrayOf(0), sent.sequence)
            advance(500)
            assertEquals(6, transport.count(Command.TAKEOFF))
        }
    }

    @Nested
    inner class Landing {
        @Test
        fun `land is sent with payload 0 and repeated until the drone reports it is not flying`() = session {
            connect()
            transport.flightData(flying = true)
            assertTrue(controller.land())
            assertArrayEquals(byteArrayOf(0), transport.all(Command.LAND).first().packet!!.payload)
            advance(1_000)
            val distinctRequests = transport.all(Command.LAND).map { it.packet!!.sequence }.toSet().size
            assertTrue(distinctRequests >= 3, "landing request repeated ($distinctRequests)")
            assertTrue(controller.isLanding)

            repeat(3) { transport.flightData(flying = false) }
            advance(10)
            assertFalse(controller.isLanding)
            val count = transport.count(Command.LAND)
            advance(1_000)
            assertEquals(count, transport.count(Command.LAND), "no LAND once landed")
        }

        @Test
        fun `land works while reconnecting and is resent after reconnection`() = session {
            connect()
            transport.flightData(flying = true)
            droneGoesSilent()
            advance(1_200)
            assertEquals(LinkState.RECONNECTING, controller.link.value)
            val before = transport.count(Command.LAND)
            assertTrue(controller.land(), "land accepted while reconnecting")
            advance(10)
            assertTrue(transport.count(Command.LAND) > before, "sent best-effort over the uplink")

            val beforeReconnect = transport.count(Command.LAND)
            connect()
            assertTrue(transport.count(Command.LAND) > beforeReconnect, "re-sent on reconnection")
        }

        @Test
        fun `takeoff cancels a pending landing request and its retries`() = session {
            connect()
            transport.flightData(flying = true)
            controller.land()
            val lands = transport.count(Command.LAND)
            controller.takeoff()
            assertFalse(controller.isLanding)
            advance(500)
            assertEquals(lands, transport.count(Command.LAND), "no LAND retry after a takeoff")
        }

        @Test
        fun `land purges pending takeoff retries`() = session {
            connect()
            controller.takeoff()
            controller.land()
            val takeoffs = transport.count(Command.TAKEOFF)
            advance(500)
            assertEquals(takeoffs, transport.count(Command.TAKEOFF), "a takeoff must never be retried after a land")
        }

        @Test
        fun `a refused takeoff does not cancel a landing in progress`() = session {
            connect()
            transport.flightData(flying = true)
            droneGoesSilent()
            advance(1_200)
            controller.land()
            assertFalse(controller.takeoff())
            assertTrue(controller.isLanding)
        }

        @Test
        fun `a stale not-flying sample right after land is ignored`() = session {
            connect()
            transport.flightData(flying = true)
            controller.land()
            repeat(3) { transport.flightData(flying = false) }
            advance(10)
            assertTrue(controller.isLanding, "samples within the grace period do not confirm the landing")
        }

        @Test
        fun `a landing request survives a reconnection longer than its timeout`() = session {
            connect()
            transport.flightData(flying = true)
            droneGoesSilent()
            advance(1_200)
            controller.land()
            advance(45_000)
            assertTrue(controller.isLanding)
            val before = transport.count(Command.LAND)
            connect()
            assertTrue(transport.count(Command.LAND) > before, "LAND re-sent after the long outage")
        }
    }

    @Nested
    inner class Incoming {
        @Test
        fun `time requests and log headers are acknowledged`() = session {
            connect()
            transport.droneMessage(MessageId.TIME_REQUEST)
            transport.droneMessage(MessageId.LOG_HEADER, byteArrayOf(0x34, 0x12))
            scope.runCurrent()
            assertEquals(1, transport.count(Command.TIMESTAMP))
            assertEquals(0x50, transport.all(Command.TIMESTAMP).first().packet!!.packetType)
            val ack = transport.all(Command.ACK_LOG).last().packet!!
            assertEquals(0x50, ack.packetType)
            assertArrayEquals(byteArrayOf(0, 0x34, 0x12), ack.payload)
        }

        @Test
        fun `flight data updates telemetry`() = session {
            connect()
            val payload = ByteArray(24).also { it[0] = 25; it[12] = 64; it[17] = 1 }
            transport.droneMessage(MessageId.FLIGHT_DATA, payload)
            scope.runCurrent()
            val flight = controller.telemetry.value.flight!!
            assertEquals(2.5f, flight.heightMeters)
            assertEquals(64, flight.batteryPercent)
            assertTrue(flight.flying)
        }

        @Test
        fun `corrupted datagrams do not keep the link alive`() = session {
            connect()
            droneGoesSilent()
            val garbage = scope.backgroundScope.launch {
                while (isActive) { transport.droneSends(byteArrayOf(1, 2, 3, 4)); delay(50) }
            }
            advance(1_200)
            assertEquals(LinkState.RECONNECTING, controller.link.value)
            garbage.cancel()
        }

        @Test
        fun `corrupted datagrams are ignored`() = session {
            connect()
            val bad = PacketCodec.encode(Command.STICK, 0, ByteArray(24)).also { it[10] = 0x55 }
            transport.droneSends(bad)
            scope.runCurrent()
            assertEquals(null, controller.telemetry.value.flight)
        }
    }

    @Nested
    inner class Safety {
        @Test
        fun `link loss centers the sticks and restarts the handshake`() = session {
            connect()
            controller.setSticks(StickInput(pitch = 1f, throttle = 1f))
            advance(100)
            droneGoesSilent()
            advance(1_200)

            assertEquals(LinkState.RECONNECTING, controller.link.value)
            assertEquals(StickInput.Neutral, controller.currentSticks)
            val handshakesBefore = transport.handshakes()
            advance(1_000)
            assertTrue(transport.handshakes() > handshakesBefore)
        }

        @Test
        fun `neutral sticks keep flowing at 50 Hz while reconnecting`() = session {
            connect()
            controller.setSticks(StickInput(pitch = 1f))
            droneGoesSilent()
            advance(1_100)
            val from = transport.sticks().size
            advance(1_000)
            val during = transport.sticks().drop(from)
            assertTrue(during.size in 49..51, "keep-alive while reconnecting: ${during.size}")
            assertTrue(during.all { it.isNeutralStick() }, "only neutral sticks after link loss")
        }

        @Test
        fun `after reconnection the sticks are neutral until the pilot moves them`() = session {
            connect()
            controller.setSticks(StickInput(roll = -1f))
            droneGoesSilent()
            advance(1_200)
            val from = transport.sticks().size
            connect()
            advance(40)
            assertTrue(transport.sticks().drop(from).all { it.isNeutralStick() })
        }

        @Test
        fun `a failing transport neither crashes nor stops the session`() = session {
            transport.failing = true
            advance(1_100)
            assertTrue(runJob.isActive)
            transport.failing = false
            advance(500)
            assertTrue(transport.handshakes() > 0, "handshake resumes once the network is back")
            connect()
            transport.failing = true
            assertFalse(controller.takeoff(), "command reports the failure")
            advance(100)
            assertTrue(runJob.isActive)
            transport.failing = false
            val from = transport.sticks().size
            advance(40)
            assertTrue(transport.sticks().size > from, "stick stream resumes")
        }

        @Test
        fun `stopping the session sends neutral sticks and returns to idle`() = session {
            connect()
            controller.setSticks(StickInput(yaw = -1f))
            advance(10)
            val from = transport.sticks().size
            runJob.cancel()
            advance(200)
            assertEquals(LinkState.IDLE, controller.link.value)
            assertEquals(StickInput.Neutral, controller.currentSticks)
            val last = transport.sticks().drop(from)
            assertTrue(last.size >= 3 && last.takeLast(3).all { it.isNeutralStick() }, "neutral packets on stop")
            assertFalse(controller.land(), "no command once stopped")
        }
    }

    @Nested
    inner class CameraAndSettings {
        private fun le32(v: Int) = ByteArray(4) { (v ushr (8 * it)).toByte() }
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

        @Test
        fun `a photo is taken, downloaded, acknowledged and emitted`() = session {
            connect()
            val photo = ByteArray(1500) { it.toByte() }
            val received = scope.async { controller.photos.first() }
            scope.runCurrent()
            assertTrue(controller.takePicture())
            assertFalse(controller.takePicture(), "one photo at a time")
            assertEquals(1, transport.count(Command.TAKE_PICTURE))
            assertEquals(0f, controller.photoProgress.value)

            transport.droneMessage(MessageId.FILE_SIZE, byteArrayOf(1) + le32(photo.size) + le16(5))
            scope.runCurrent()
            assertEquals(1, transport.count(Command.ACK_FILE_SIZE))
            listOf(0 to 1024, 1 to 476).forEach { (chunk, len) ->
                val data = photo.copyOfRange(chunk * 1024, chunk * 1024 + len)
                transport.droneMessage(MessageId.FILE_DATA, le16(5) + le32(0) + le32(chunk) + le16(len) + data)
            }
            scope.runCurrent()
            assertArrayEquals(photo, received.await())
            assertEquals(null, controller.photoProgress.value)
            assertEquals(1, transport.count(Command.FILE_DONE))
            advance(500)
            assertEquals(1, transport.count(Command.FILE_DONE), "file acks are not retransmitted")
        }

        @Test
        fun `a photo transfer without data is abandoned after the timeout`() = session {
            connect()
            assertTrue(controller.takePicture())
            advance(19_000)
            assertEquals(0f, controller.photoProgress.value)
            advance(1_500)
            assertEquals(null, controller.photoProgress.value)
            assertTrue(controller.takePicture(), "a new photo can be taken")
        }

        @Test
        fun `a photo refused by the drone frees the button at once`() = session {
            connect()
            assertTrue(controller.takePicture())
            val seq = transport.all(Command.TAKE_PICTURE).first().packet!!.sequence
            transport.droneMessage(MessageId.TAKE_PICTURE, byteArrayOf(1), sequence = seq)
            scope.runCurrent()
            assertEquals(null, controller.photoProgress.value)
        }

        @Test
        fun `take picture is retried slowly (500 ms, twice) to avoid a double capture`() = session {
            connect()
            controller.takePicture()
            advance(400)
            assertEquals(1, transport.count(Command.TAKE_PICTURE))
            advance(2_000)
            assertEquals(3, transport.count(Command.TAKE_PICTURE))
        }

        @Test
        fun `a rejected file announcement does not show a stuck progress`() = session {
            connect()
            transport.droneMessage(MessageId.FILE_SIZE, byteArrayOf(1, 0, 0, 0, 0, 1, 0)) // size 0
            scope.runCurrent()
            assertEquals(null, controller.photoProgress.value)
        }

        @Test
        fun `no photo when disconnected`() = session {
            assertFalse(controller.takePicture())
            assertEquals(0, transport.count(Command.TAKE_PICTURE))
        }

        @Test
        fun `drone settings are sanitized, stored and sent on every connection`() = session {
            assertFalse(controller.applyDroneSettings(DroneSettings(maxAltitudeMeters = 99, exposureSteps = 3, bitrate = VideoBitrate.MBPS_2)))
            assertEquals(0, transport.count(Command.SET_MAX_ALTITUDE))
            connect()
            assertArrayEquals(byteArrayOf(30, 0), transport.all(Command.SET_MAX_ALTITUDE).first().packet!!.payload)
            assertArrayEquals(byteArrayOf(3), transport.all(Command.SET_EXPOSURE).first().packet!!.payload)
            assertArrayEquals(byteArrayOf(3), transport.all(Command.SET_BITRATE).first().packet!!.payload)
            assertEquals(1, transport.count(Command.SET_LOW_BATTERY_THRESHOLD))

            val before = transport.all(Command.SET_MAX_ALTITUDE).map { it.packet!!.sequence }.toSet().size
            assertTrue(controller.applyDroneSettings(DroneSettings(maxAltitudeMeters = 5)))
            val altitudes = transport.all(Command.SET_MAX_ALTITUDE)
            assertEquals(before + 1, altitudes.map { it.packet!!.sequence }.toSet().size)
            assertArrayEquals(byteArrayOf(5, 0), altitudes.last().packet!!.payload)

            val sent = transport.count(Command.SET_BITRATE)
            assertFalse(controller.applyDroneSettings(DroneSettings(maxAltitudeMeters = 5)), "unchanged: not re-sent")
            assertEquals(sent, transport.count(Command.SET_BITRATE))
        }

        @Test
        fun `camera fault is reported only after 20 consecutive frames`() = session {
            connect()
            val faulty = ByteArray(24).also { it[20] = 1 }
            repeat(19) { transport.droneMessage(MessageId.FLIGHT_DATA, faulty) }
            scope.runCurrent()
            assertFalse(controller.telemetry.value.cameraFault)
            transport.droneMessage(MessageId.FLIGHT_DATA, faulty)
            scope.runCurrent()
            assertTrue(controller.telemetry.value.cameraFault)
            transport.droneMessage(MessageId.FLIGHT_DATA, ByteArray(24))
            scope.runCurrent()
            assertFalse(controller.telemetry.value.cameraFault, "cleared by a good frame")
        }
    }

    @Nested
    inner class FlightModesAndMaintenance {
        private fun Session.ready(flying: Boolean = true, battery: Int = 80, heightDm: Int = 10, wind: Boolean = false) {
            connect()
            transport.flight(flying, battery, heightDm, wind)
            scope.runCurrent()
        }

        @Test
        fun `flips follow the official preconditions, with the app margins`() = session {
            ready(battery = 54, heightDm = 20)
            assertEquals(ModeCheck.LOW_BATTERY, controller.flip(FlipDirection.FORWARD))
            transport.flight(battery = 55, heightDm = 41); scope.runCurrent()
            assertEquals(ModeCheck.TOO_HIGH, controller.flip(FlipDirection.FORWARD))
            transport.flight(battery = 55, heightDm = 19); scope.runCurrent()
            assertEquals(ModeCheck.TOO_LOW, controller.flip(FlipDirection.FORWARD))
            transport.flight(flying = false); scope.runCurrent()
            assertEquals(ModeCheck.NOT_FLYING, controller.flip(FlipDirection.FORWARD))
            transport.flight(wind = true); scope.runCurrent()
            assertEquals(ModeCheck.WIND, controller.flip(FlipDirection.FORWARD))
            transport.flight(battery = 55, heightDm = 40); scope.runCurrent()
            assertEquals(ModeCheck.NOT_STABILISED, controller.flip(FlipDirection.FORWARD), "flying again since 0 s")
            assertEquals(0, transport.count(Command.FLIP), "nothing sent when refused")
            advance(FlightModeRules.FLIP_MIN_FLYING_MS)
            transport.flight(battery = 55, heightDm = 40); scope.runCurrent()
            assertEquals(ModeCheck.OK, controller.flip(FlipDirection.FORWARD_RIGHT))
            assertArrayEquals(byteArrayOf(7), transport.all(Command.FLIP).single().packet!!.payload)
        }

        @Test
        fun `bounce enters with 0x30, state follows the drone reply, leaving is always allowed`() = session {
            ready()
            assertEquals(ModeCheck.OK, controller.setBounce(true))
            val enter = transport.all(Command.BOUNCE).first().packet!!
            assertEquals(0x68, enter.packetType)
            assertArrayEquals(byteArrayOf(0x30), enter.payload)
            assertFalse(controller.maneuvers.value.bounce, "not before the drone confirms")
            transport.droneMessage(MessageId.BOUNCE, byteArrayOf(0), sequence = enter.sequence)
            scope.runCurrent()
            assertTrue(controller.maneuvers.value.bounce)

            transport.flight(battery = 5, heightDm = 60); scope.runCurrent()
            assertEquals(ModeCheck.OK, controller.setBounce(false), "leave even with low battery / high")
            assertArrayEquals(byteArrayOf(0x31), transport.all(Command.BOUNCE).last().packet!!.payload)
        }

        @Test
        fun `smart video start, drone status acked with 129, stop uses the same mode`() = session {
            ready()
            assertEquals(ModeCheck.OK, controller.startSmartVideo(SmartVideoMode.CIRCLE))
            assertArrayEquals(byteArrayOf((1 or (2 shl 2)).toByte()), transport.all(Command.SMART_VIDEO).first().packet!!.payload)
            // drone: running (state 1), mode circle (2)
            transport.droneMessage(MessageId.SMART_VIDEO_STATUS, byteArrayOf(((1 shl 3) or (2 shl 5)).toByte()))
            scope.runCurrent()
            assertTrue(controller.maneuvers.value.smartVideoRunning)
            assertEquals(SmartVideoMode.CIRCLE, controller.maneuvers.value.smartVideo!!.mode)
            val ack = transport.all(Command.ACK_SMART_VIDEO_STATUS).single().packet!!
            assertEquals(0x50, ack.packetType)
            assertArrayEquals(byteArrayOf(0), ack.payload)

            assertEquals(ModeCheck.BUSY, controller.startSmartVideo(SmartVideoMode.ROTATE_360))
            assertEquals(ModeCheck.BUSY, controller.setBounce(true))
            assertTrue(controller.stopSmartVideo())
            assertArrayEquals(byteArrayOf((2 shl 2).toByte()), transport.all(Command.SMART_VIDEO).last().packet!!.payload)
        }

        @Test
        fun `smart video is refused during a photo`() = session {
            ready()
            controller.takePicture()
            assertEquals(ModeCheck.PHOTO_IN_PROGRESS, controller.startSmartVideo(SmartVideoMode.UP_AND_AWAY))
        }

        @Test
        fun `calibrations only on the ground`() = session {
            ready(flying = true)
            assertEquals(ModeCheck.MUST_BE_ON_GROUND, controller.calibrateCenterOfGravity())
            assertEquals(ModeCheck.MUST_BE_ON_GROUND, controller.startImuCalibration())
            transport.flight(flying = false); scope.runCurrent()
            val events = scope.async { controller.events.first() }
            scope.runCurrent()
            assertEquals(ModeCheck.OK, controller.calibrateCenterOfGravity())
            val cg = transport.all(Command.CALIBRATE_CENTER_OF_GRAVITY).first().packet!!
            assertArrayEquals(byteArrayOf(1), cg.payload)
            transport.droneMessage(MessageId.CENTER_OF_GRAVITY_CALIBRATION, byteArrayOf(0), sequence = cg.sequence)
            scope.runCurrent()
            assertEquals(DroneEvent.CenterOfGravityCalibrated(true), events.await())
        }

        @Test
        fun `IMU calibration starts with 90, polls 91 every 100 ms and completes with six faces`() = session {
            ready(flying = false)
            assertEquals(ModeCheck.OK, controller.startImuCalibration())
            assertEquals(ImuCalibrationState.Starting, controller.imuCalibration.value)
            advance(300)
            assertEquals(0, transport.count(Command.QUERY_IMU_CALIBRATION), "no poll before the drone accepts")
            val start = transport.all(Command.START_IMU_CALIBRATION).first().packet!!
            transport.droneMessage(MessageId.IMU_CALIBRATION_START, byteArrayOf(0), sequence = start.sequence)
            scope.runCurrent()
            assertTrue(controller.imuCalibration.value is ImuCalibrationState.Running)
            val polls = transport.count(Command.QUERY_IMU_CALIBRATION)
            advance(1_000)
            assertTrue(transport.count(Command.QUERY_IMU_CALIBRATION) - polls in 8..12, "~10 polls per second")

            transport.droneMessage(MessageId.IMU_CALIBRATION_STATUS, byteArrayOf(0, 0b010000, 0, 20, 0)) // bottom done
            scope.runCurrent()
            val running = controller.imuCalibration.value as ImuCalibrationState.Running
            assertEquals(ImuFace.RIGHT, running.nextFace)

            transport.droneMessage(MessageId.IMU_CALIBRATION_STATUS, byteArrayOf(0, 0b111111, 0, 100, 0))
            scope.runCurrent()
            assertEquals(ImuCalibrationState.Succeeded, controller.imuCalibration.value)
            val after = transport.count(Command.QUERY_IMU_CALIBRATION)
            advance(500)
            assertEquals(after, transport.count(Command.QUERY_IMU_CALIBRATION), "polling stops")
        }

        @Test
        fun `IMU calibration refused by the drone`() = session {
            ready(flying = false)
            controller.startImuCalibration()
            val start = transport.all(Command.START_IMU_CALIBRATION).first().packet!!
            transport.droneMessage(MessageId.IMU_CALIBRATION_START, byteArrayOf(1), sequence = start.sequence)
            scope.runCurrent()
            assertEquals(ImuCalibrationState.Failed(ImuCalibrationState.Reason.REFUSED), controller.imuCalibration.value)
        }

        @Test
        fun `Wi-Fi change sends SSID and password, restarts Wi-Fi only once both are stored`() = session {
            ready(flying = false)
            assertFalse(controller.changeWifi("", "12345678"), "empty SSID")
            assertFalse(controller.changeWifi("TELLO-X", "short"), "password too short")
            assertTrue(controller.changeWifi("TELLO-PILOT", "secret123"))
            val ssid = transport.all(Command.SET_SSID).first().packet!!
            val pwd = transport.all(Command.SET_WIFI_PASSWORD).first().packet!!
            assertEquals("TELLO-PILOT", String(ssid.payload, Charsets.US_ASCII))
            assertEquals("secret123", String(pwd.payload, Charsets.US_ASCII))

            transport.droneMessage(MessageId.SET_SSID, byteArrayOf(0), sequence = ssid.sequence)
            scope.runCurrent()
            assertEquals(0, transport.count(Command.RESTART_WIFI), "password not confirmed yet")
            transport.droneMessage(MessageId.SET_WIFI_PASSWORD, byteArrayOf(0), sequence = pwd.sequence)
            scope.runCurrent()
            assertEquals(1, transport.all(Command.RESTART_WIFI).map { it.packet!!.sequence }.toSet().size)
            assertEquals(WifiChangeState.Applied("TELLO-PILOT"), controller.wifiChangeState.value)
        }

        @Test
        fun `Wi-Fi change is refused in flight and fails without restart on error`() = session {
            ready(flying = true)
            assertFalse(controller.changeWifi("TELLO-PILOT", ""))
            transport.flight(flying = false); scope.runCurrent()
            assertTrue(controller.changeWifi("TELLO-PILOT", ""))
            val ssid = transport.all(Command.SET_SSID).first().packet!!
            transport.droneMessage(MessageId.SET_SSID, byteArrayOf(1), sequence = ssid.sequence)
            scope.runCurrent()
            assertEquals(WifiChangeState.Failed, controller.wifiChangeState.value)
            assertEquals(0, transport.count(Command.RESTART_WIFI))
        }

        @Test
        fun `a late reply to a retransmission does not undo the state`() = session {
            ready()
            controller.setBounce(true)
            advance(60) // retransmitted once, same sequence
            val seq = transport.all(Command.BOUNCE).first().packet!!.sequence
            transport.droneMessage(MessageId.BOUNCE, byteArrayOf(0), sequence = seq)
            transport.droneMessage(MessageId.BOUNCE, byteArrayOf(1), sequence = seq) // reply to the duplicate
            scope.runCurrent()
            assertTrue(controller.maneuvers.value.bounce, "first reply wins")
        }

        @Test
        fun `stop uses the requested mode, not a stale finished status, and stops all modes when unknown`() = session {
            ready()
            // a previous 360 finished (state 0, mode 1)
            transport.droneMessage(MessageId.SMART_VIDEO_STATUS, byteArrayOf((1 shl 5).toByte()))
            scope.runCurrent()
            controller.startSmartVideo(SmartVideoMode.CIRCLE)
            assertTrue(controller.maneuvers.value.smartVideoActive, "Stop offered before the first status")
            controller.stopSmartVideo()
            assertArrayEquals(byteArrayOf((2 shl 2).toByte()), transport.all(Command.SMART_VIDEO).last().packet!!.payload)
        }

        @Test
        fun `stop with no known mode stops every mode`() = session {
            ready()
            assertTrue(controller.stopSmartVideo())
            assertEquals(
                setOf(1 shl 2, 2 shl 2, 3 shl 2),
                transport.all(Command.SMART_VIDEO).map { it.packet!!.payload[0].toInt() }.toSet(),
            )
        }

        @Test
        fun `manoeuvre state is cleared on the ground`() = session {
            ready()
            controller.setBounce(true)
            val seq = transport.all(Command.BOUNCE).first().packet!!.sequence
            transport.droneMessage(MessageId.BOUNCE, byteArrayOf(0), sequence = seq)
            scope.runCurrent()
            assertTrue(controller.maneuvers.value.bounce)
            transport.flight(flying = false); scope.runCurrent()
            assertEquals(ManeuverState(), controller.maneuvers.value)
        }

        @Test
        fun `Wi-Fi change without answer fails after 5 s and re-reads the SSID`() = session {
            ready(flying = false)
            controller.changeWifi("TELLO-PILOT", "secret123")
            val queries = transport.count(Command.QUERY_SSID)
            advance(4_900)
            assertEquals(WifiChangeState.Sending, controller.wifiChangeState.value)
            advance(300)
            assertEquals(WifiChangeState.Failed, controller.wifiChangeState.value)
            assertTrue(transport.count(Command.QUERY_SSID) > queries)
            assertEquals(0, transport.count(Command.RESTART_WIFI))
        }

        @Test
        fun `IMU faces accumulate over the run and progress extends the timeout`() = session {
            ready(flying = false)
            controller.startImuCalibration()
            val start = transport.all(Command.START_IMU_CALIBRATION).first().packet!!
            transport.droneMessage(MessageId.IMU_CALIBRATION_START, byteArrayOf(0), sequence = start.sequence)
            transport.droneMessage(MessageId.IMU_CALIBRATION_STATUS, byteArrayOf(0, 0b010000, 0, 10, 0)) // bottom
            transport.droneMessage(MessageId.IMU_CALIBRATION_STATUS, byteArrayOf(0, 0b000100, 0, 20, 0)) // right only
            scope.runCurrent()
            val r = controller.imuCalibration.value as ImuCalibrationState.Running
            assertEquals(setOf(ImuFace.BOTTOM, ImuFace.RIGHT), r.doneFaces)
            advance(50_000)
            transport.droneMessage(MessageId.IMU_CALIBRATION_STATUS, byteArrayOf(0, 0b001000, 0, 30, 0)) // left
            scope.runCurrent()
            advance(50_000)
            assertTrue(controller.imuCalibration.value is ImuCalibrationState.Running, "still running 100 s in")
            advance(15_000)
            assertEquals(ImuCalibrationState.Failed(ImuCalibrationState.Reason.TIMEOUT), controller.imuCalibration.value)
        }

        @Test
        fun `SSID query`() = session {
            ready(flying = false)
            controller.queryWifiName()
            transport.droneMessage(MessageId.SSID, byteArrayOf(0) + "TELLO-ABC123".toByteArray())
            scope.runCurrent()
            assertEquals("TELLO-ABC123", controller.telemetry.value.ssid)
        }
    }
}
