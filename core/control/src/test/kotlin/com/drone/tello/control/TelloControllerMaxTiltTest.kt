package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.Payloads
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Max tilt of the fast mode: sent on connect / change (4184), read back (4185). */
@OptIn(ExperimentalCoroutinesApi::class)
class TelloControllerMaxTiltTest {

    private class Session(val scope: TestScope) {
        val transport = FakeTransport(scope)
        val controller = TelloController(transport, clock = { scope.testScheduler.currentTime })
        private var heartbeat: Job? = null

        fun start() {
            scope.backgroundScope.launch { controller.run() }
            scope.runCurrent()
        }

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

        /** A fresh flight sample: the drone flying or on the ground. */
        fun flight(flying: Boolean) {
            transport.flight(flying = flying)
            scope.runCurrent()
        }

        /** Drone settings known, connected, on the ground, the max tilt acknowledged. */
        suspend fun connectedOnGround(settings: DroneSettings = DroneSettings()) {
            controller.applyDroneSettings(settings)
            connect()
            flight(flying = false)
            transport.droneAcks(distinct(Command.SET_MAX_TILT).last())
            scope.runCurrent()
        }
        fun advance(ms: Long) = scope.advanceTimeBy(ms)

        /** Distinct (non-retransmitted) packets of [c]. */
        fun distinct(c: Command) = transport.all(c).distinctBy { it.packet!!.sequence }

        /** The drone answers the last 4185 query with [payload]. */
        fun replyToQuery(payload: ByteArray) {
            val query = transport.all(Command.QUERY_MAX_TILT).last().packet!!
            transport.droneMessage(MessageId.MAX_TILT_QUERY, payload, query.sequence)
            scope.runCurrent()
        }
    }

    private fun session(block: suspend Session.() -> Unit) = runTest {
        Session(this).apply { start(); block() }
    }

    private fun angle(degrees: Float) = byteArrayOf(0) + Payloads.maxTilt(degrees)

    @Test
    fun `the angle is queried on connection even without settings`() = session {
        connect()
        assertEquals(1, distinct(Command.QUERY_MAX_TILT).size)
        assertEquals(0, transport.count(Command.SET_MAX_TILT), "nothing to send before the settings are known")
        assertTrue(transport.all(Command.QUERY_MAX_TILT).first().packet!!.payload.isEmpty())
    }

    @Test
    fun `stored angle is sent on connection and again on every reconnection`() = session {
        assertFalse(controller.applyDroneSettings(DroneSettings(maxTiltDeg = 20f)))
        assertEquals(0, transport.count(Command.SET_MAX_TILT), "not connected: nothing sent")
        connect()
        assertEquals(0, transport.count(Command.SET_MAX_TILT), "flying state not known yet")
        flight(flying = false)
        assertArrayEquals(Payloads.maxTilt(20f), distinct(Command.SET_MAX_TILT).single().packet!!.payload)
        flight(flying = false)
        assertEquals(1, distinct(Command.SET_MAX_TILT).size, "sent once per connection")

        droneGoesSilent()
        advance(1_500)
        assertEquals(LinkState.RECONNECTING, controller.link.value)
        connect()
        assertEquals(LinkState.CONNECTED, controller.link.value)
        flight(flying = false)
        val sets = distinct(Command.SET_MAX_TILT)
        assertEquals(2, sets.size)
        assertArrayEquals(Payloads.maxTilt(20f), sets.last().packet!!.payload)
        assertEquals(2, distinct(Command.QUERY_MAX_TILT).size, "queried again on reconnection")
    }

    @Test
    fun `a change is sent at once when connected, and only when it changes`() = session {
        connectedOnGround()
        assertArrayEquals(Payloads.maxTilt(25f), distinct(Command.SET_MAX_TILT).single().packet!!.payload, "official default")

        assertTrue(controller.applyDroneSettings(DroneSettings(maxTiltDeg = 16f)))
        assertArrayEquals(Payloads.maxTilt(16f), distinct(Command.SET_MAX_TILT).last().packet!!.payload)
        val count = distinct(Command.SET_MAX_TILT).size
        assertFalse(controller.applyDroneSettings(DroneSettings(maxTiltDeg = 16f)), "unchanged: not re-sent")
        assertEquals(count, distinct(Command.SET_MAX_TILT).size)
    }

    @Test
    fun `out of range and NaN angles are sanitized before sending`() = session {
        connectedOnGround(DroneSettings(maxTiltDeg = 99f))
        assertArrayEquals(Payloads.maxTilt(25f), distinct(Command.SET_MAX_TILT).last().packet!!.payload)
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 1f))
        assertArrayEquals(Payloads.maxTilt(15f), distinct(Command.SET_MAX_TILT).last().packet!!.payload)
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = Float.NaN))
        assertArrayEquals(Payloads.maxTilt(25f), distinct(Command.SET_MAX_TILT).last().packet!!.payload)
    }

    @Test
    fun `the set command is acknowledged and retransmitted until the drone answers`() = session {
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 18f))
        connect()
        flight(flying = false)
        advance(120)
        val set = distinct(Command.SET_MAX_TILT).single()
        assertTrue(transport.all(Command.SET_MAX_TILT).size > 1, "retransmitted while unanswered")
        transport.droneAcks(set)
        scope.runCurrent()
        val after = transport.all(Command.SET_MAX_TILT).size
        advance(300)
        assertEquals(after, transport.all(Command.SET_MAX_TILT).size, "no retry once acknowledged")
    }

    @Test
    fun `the drone reply updates the exposed value`() = session {
        connect()
        assertNull(controller.droneMaxTilt.value)
        replyToQuery(angle(22f))
        assertEquals(22f, controller.droneMaxTilt.value)
    }

    @Test
    fun `implausible or failed replies are ignored`() = session {
        connectedOnGround()
        replyToQuery(angle(21f))
        assertEquals(21f, controller.droneMaxTilt.value)

        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 19f))
        transport.droneAcks(distinct(Command.SET_MAX_TILT).last())
        scope.runCurrent()
        replyToQuery(byteArrayOf(0) + byteArrayOf(0, 0, 0xC0.toByte(), 0x7F)) // NaN
        assertEquals(21f, controller.droneMaxTilt.value, "NaN keeps the last plausible value")

        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 17f))
        transport.droneAcks(distinct(Command.SET_MAX_TILT).last())
        scope.runCurrent()
        replyToQuery(byteArrayOf(1) + Payloads.maxTilt(17f)) // status != 0
        assertEquals(21f, controller.droneMaxTilt.value)
    }

    @Test
    fun `an unsolicited reply is ignored`() = session {
        connect()
        transport.droneMessage(MessageId.MAX_TILT_QUERY, angle(20f), sequence = 0)
        scope.runCurrent()
        assertNull(controller.droneMaxTilt.value)
    }

    @Test
    fun `an acknowledged change is read back from the drone`() = session {
        connectedOnGround()
        replyToQuery(angle(25f))
        val queries = distinct(Command.QUERY_MAX_TILT).size
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 15f))
        assertEquals(queries, distinct(Command.QUERY_MAX_TILT).size, "no query before the drone answers")
        transport.droneAcks(distinct(Command.SET_MAX_TILT).last())
        scope.runCurrent()
        assertEquals(queries + 1, distinct(Command.QUERY_MAX_TILT).size)
        replyToQuery(angle(15f))
        assertEquals(15f, controller.droneMaxTilt.value)
    }

    // ------------------------------------------------- never changed in flight on its own

    @Test
    fun `a reconnection in flight waits for the landing before sending the angle`() = session {
        connectedOnGround(DroneSettings(maxTiltDeg = 20f))
        val sets = distinct(Command.SET_MAX_TILT).size
        flight(flying = true)

        droneGoesSilent()
        advance(1_500)
        connect()
        flight(flying = true)
        advance(500)
        flight(flying = true)
        assertEquals(sets, distinct(Command.SET_MAX_TILT).size, "not sent in flight without a pilot action")

        flight(flying = false) // landed
        assertEquals(sets + 1, distinct(Command.SET_MAX_TILT).size)
        assertArrayEquals(Payloads.maxTilt(20f), distinct(Command.SET_MAX_TILT).last().packet!!.payload)
    }

    @Test
    fun `other settings are still sent at once on a reconnection in flight`() = session {
        connectedOnGround()
        val altitudes = distinct(Command.SET_MAX_ALTITUDE).size
        flight(flying = true)
        droneGoesSilent()
        advance(1_500)
        connect()
        assertEquals(altitudes + 1, distinct(Command.SET_MAX_ALTITUDE).size)
    }

    @Test
    fun `settings first known in flight do not send the angle until the ground`() = session {
        connect()
        flight(flying = true)
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 18f))
        flight(flying = true)
        assertEquals(0, transport.count(Command.SET_MAX_TILT))
        flight(flying = false)
        assertArrayEquals(Payloads.maxTilt(18f), distinct(Command.SET_MAX_TILT).single().packet!!.payload)
    }

    @Test
    fun `a slider change by the pilot is sent at once, even in flight`() = session {
        connectedOnGround(DroneSettings(maxTiltDeg = 25f))
        flight(flying = true)
        assertTrue(controller.applyDroneSettings(DroneSettings(maxTiltDeg = 16f)))
        assertArrayEquals(Payloads.maxTilt(16f), distinct(Command.SET_MAX_TILT).last().packet!!.payload)
        val sets = distinct(Command.SET_MAX_TILT).size
        flight(flying = false)
        assertEquals(sets, distinct(Command.SET_MAX_TILT).size, "nothing left to send at the landing")
    }

    @Test
    fun `a pilot change in flight also replaces a pending send`() = session {
        connectedOnGround(DroneSettings(maxTiltDeg = 25f))
        flight(flying = true)
        droneGoesSilent()
        advance(1_500)
        connect()
        flight(flying = true)
        val sets = distinct(Command.SET_MAX_TILT).size
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 18f))
        assertEquals(sets + 1, distinct(Command.SET_MAX_TILT).size)
        flight(flying = false)
        assertEquals(sets + 1, distinct(Command.SET_MAX_TILT).size, "not sent twice")
    }

    @Test
    fun `a refusal by the drone is reported`() = session {
        val events = mutableListOf<DroneEvent>()
        scope.backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(scope.testScheduler)) {
            controller.events.collect { events += it }
        }
        controller.applyDroneSettings(DroneSettings(maxTiltDeg = 20f))
        connect()
        flight(flying = false)
        val set = distinct(Command.SET_MAX_TILT).single().packet!!
        transport.droneMessage(MessageId.MAX_TILT, byteArrayOf(1), set.sequence)
        scope.runCurrent()
        assertEquals(listOf<DroneEvent>(DroneEvent.MaxTiltRefused), events)
    }

    @Test
    fun `an accepted angle is not reported`() = session {
        val events = mutableListOf<DroneEvent>()
        scope.backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(scope.testScheduler)) {
            controller.events.collect { events += it }
        }
        connectedOnGround()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the drone value is forgotten when the link is lost`() = session {
        connect()
        replyToQuery(angle(22f))
        assertEquals(22f, controller.droneMaxTilt.value)
        droneGoesSilent()
        advance(1_500)
        assertEquals(LinkState.RECONNECTING, controller.link.value)
        assertNull(controller.droneMaxTilt.value)
    }
}