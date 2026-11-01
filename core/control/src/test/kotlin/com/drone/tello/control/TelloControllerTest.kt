package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.PacketCodec
import com.drone.tello.protocol.StickInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
            assertArrayEquals(byteArrayOf(1), transport.all(Command.SET_VIDEO_MODE).first().packet!!.payload)
            assertArrayEquals(byteArrayOf(0), transport.all(Command.SET_EIS).first().packet!!.payload)
            assertEquals(1, transport.count(Command.REQUEST_KEY_FRAME))
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
            assertFalse(controller.flip(FlipDirection.LEFT))
            assertFalse(controller.palmLand())
            assertEquals(0, transport.count(Command.TAKEOFF) + transport.count(Command.FLIP) + transport.count(Command.PALM_LAND))
        }

        @Test
        fun `flight commands carry the expected ids, payloads and increasing sequence`() = session {
            connect()
            assertTrue(controller.takeoff())
            assertTrue(controller.flip(FlipDirection.BACK_RIGHT))
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
}
