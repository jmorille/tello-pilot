package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.SmartVideoMode
import com.drone.tello.protocol.StickInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Flip safety in the controller (incident of 14 Nov. 2026: a pad flip that lost control at the
 * vertical): what goes to the drone around a flip, one flip at a time, the app's own
 * preconditions, and the flight-log events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelloControllerFlipTest {

    private class Session(val scope: TestScope) {
        val transport = FakeTransport(scope)
        val controller = TelloController(transport, clock = { scope.testScheduler.currentTime })
        val diagnostics = mutableListOf<String>()

        init {
            controller.diagnostics = { diagnostics += it }
            scope.backgroundScope.launch { controller.run() }
            scope.runCurrent()
        }

        var heightDm = 20
        var battery = 80
        var flyMode = 6
        /** The drone stops sending flight data (Wi-Fi heartbeat only). */
        var flightDataOn = true

        /** Connected, flying at [heightDm] for long enough; flight data and heartbeat every 100 ms. */
        fun flying(heightDm: Int = 20, battery: Int = 80, flyMode: Int = 6, settle: Boolean = true) {
            transport.ack()
            flight(heightDm, battery, flyMode)
            scope.backgroundScope.launch {
                while (isActive) {
                    delay(100)
                    transport.droneHeartbeat()
                    if (flightDataOn) sendFlight()
                }
            }
            scope.runCurrent()
            if (settle) scope.advanceTimeBy(FlightModeRules.FLIP_MIN_FLYING_MS)
        }

        /** New flight data values, sent at once and then every 100 ms. */
        fun flight(heightDm: Int = 20, battery: Int = 80, flyMode: Int = 6) {
            this.heightDm = heightDm; this.battery = battery; this.flyMode = flyMode
            sendFlight()
            scope.runCurrent()
        }

        private fun sendFlight() = transport.droneMessage(
            MessageId.FLIGHT_DATA,
            ByteArray(24).also {
                it[0] = heightDm.toByte(); it[1] = (heightDm ushr 8).toByte()
                it[12] = battery.toByte()
                it[17] = 0b101 // flying, motors on
                it[18] = flyMode.toByte()
            },
        )

        fun sticksSince(t: Long) = transport.sticks().filter { it.timeMs >= t }
    }

    private fun session(block: suspend Session.() -> Unit) = runTest { Session(this).block() }

    private val fullForwardFast = StickInput(roll = 1f, pitch = 1f, yaw = 0.5f, fastMode = true)

    // ------------------------------------------------------------------ sticks

    @Test
    fun `neutral sticks without the fast flag for 1,5 s after the flip, whatever the pilot holds`() = session {
        flying()
        controller.setSticks(fullForwardFast) // thumb resting on the pad stick, fast mode on
        scope.advanceTimeBy(100)
        assertTrue(decodeSticks(transport.sticks().last()) != NEUTRAL_AXES + 0L, "the pilot flies before")
        val sentAt = scope.testScheduler.currentTime
        assertEquals(ModeCheck.OK, controller.flip(FlipDirection.FORWARD))
        controller.setSticks(fullForwardFast) // the pad keeps sending its sticks during the flip
        scope.advanceTimeBy(FlightModeRules.FLIP_STICK_HOLD_MS - 20)
        val during = sticksSince(sentAt)
        assertTrue(during.size > 50, "the 50 Hz stream goes on: ${during.size}")
        assertTrue(during.all { decodeSticks(it) == NEUTRAL_AXES + 0L }, "neutral, fast flag off")
        assertEquals(StickInput.Neutral, controller.currentSticks, "the flight log sees what is sent")
    }

    @Test
    fun `after the hold, the pilot sticks only drive again once centred (re-armed)`() = session {
        flying()
        controller.setSticks(fullForwardFast)
        controller.flip(FlipDirection.FORWARD)
        scope.advanceTimeBy(FlightModeRules.FLIP_STICK_HOLD_MS + 500)
        assertTrue(transport.sticks().last().isNeutralStick(), "still held after the hold: neutral")
        controller.setSticks(StickInput(roll = 0.03f, fastMode = true)) // back to centre (within 0.05)
        scope.advanceTimeBy(40)
        controller.setSticks(StickInput(pitch = 0.6f, fastMode = true))
        scope.advanceTimeBy(40)
        val last = decodeSticks(transport.sticks().last())
        assertTrue(last.take(4) != NEUTRAL_AXES && last[4] == 1L, "the pilot flies again: $last")
    }

    @Test
    fun `centred sticks resume right after the hold`() = session {
        flying()
        controller.setSticks(StickInput(fastMode = true))
        controller.flip(FlipDirection.BACK)
        scope.advanceTimeBy(FlightModeRules.FLIP_STICK_HOLD_MS + 100)
        controller.setSticks(StickInput(yaw = 0.5f, fastMode = true))
        scope.advanceTimeBy(40)
        assertTrue(!transport.sticks().last().isNeutralStick())
    }

    @Test
    fun `a refused flip changes nothing to the sticks`() = session {
        flying(heightDm = 6) // the incident height: about 0.6 m
        controller.setSticks(StickInput(pitch = 0.5f))
        assertEquals(ModeCheck.TOO_LOW, controller.flip(FlipDirection.FORWARD))
        scope.advanceTimeBy(40)
        assertTrue(!transport.sticks().last().isNeutralStick())
        assertEquals(0, transport.count(Command.FLIP))
    }

    // ------------------------------------------------------------- one at a time

    @Test
    fun `one flip per 4 s, touch or pad alike`() = session {
        flying()
        assertEquals(ModeCheck.OK, controller.flip(FlipDirection.FORWARD))
        scope.advanceTimeBy(300)
        assertEquals(ModeCheck.FLIP_LOCKED, controller.flip(FlipDirection.FORWARD), "contact bounce")
        assertEquals(ModeCheck.FLIP_LOCKED, controller.flip(FlipDirection.RIGHT), "rolled press")
        scope.advanceTimeBy(FlightModeRules.FLIP_LOCK_MS - 400)
        assertEquals(ModeCheck.FLIP_LOCKED, controller.flip(FlipDirection.LEFT))
        assertEquals(1, transport.count(Command.FLIP))
        scope.advanceTimeBy(200)
        assertEquals(ModeCheck.OK, controller.flip(FlipDirection.LEFT))
        assertEquals(2, transport.count(Command.FLIP))
    }

    // ------------------------------------------------------------- preconditions

    @Test
    fun `the app rules - height, positioning, takeoff over, nothing else running`() = session {
        flying(settle = false)
        assertEquals(ModeCheck.NOT_STABILISED, controller.flip(FlipDirection.FORWARD), "just took off")
        scope.advanceTimeBy(FlightModeRules.FLIP_MIN_FLYING_MS)
        flight(heightDm = 19)
        assertEquals(ModeCheck.TOO_LOW, controller.flip(FlipDirection.FORWARD))
        flight(heightDm = -25) // implausible reading (sensor blind or upside down)
        assertEquals(ModeCheck.TOO_LOW, controller.flip(FlipDirection.FORWARD))
        flight(heightDm = 20, flyMode = 1)
        assertEquals(ModeCheck.NO_POSITIONING, controller.flip(FlipDirection.FORWARD))
        flight(heightDm = 20, battery = 54)
        assertEquals(ModeCheck.LOW_BATTERY, controller.flip(FlipDirection.FORWARD))
        flight(heightDm = 20)
        assertEquals(ModeCheck.BUSY, controller.flip(FlipDirection.FORWARD, appBusy = true), "autopilot / takeoff height")
        controller.startSmartVideo(SmartVideoMode.ROTATE_360)
        assertEquals(ModeCheck.BUSY, controller.flip(FlipDirection.FORWARD), "smart video")
        controller.stopSmartVideo()
        transport.droneMessage(MessageId.SMART_VIDEO, byteArrayOf(1), sequence = transport.all(Command.SMART_VIDEO).first().packet!!.sequence)
        scope.runCurrent()
        controller.land()
        assertEquals(ModeCheck.BUSY, controller.flip(FlipDirection.FORWARD), "landing")
        assertEquals(0, transport.count(Command.FLIP))
    }

    @Test
    fun `no flip on stale flight data`() = session {
        flying()
        flightDataOn = false
        scope.advanceTimeBy(FlightModeRules.FLIP_MAX_TELEMETRY_AGE_MS + 100)
        assertEquals(ModeCheck.TELEMETRY_STALE, controller.flip(FlipDirection.FORWARD))
        flightDataOn = true
        scope.advanceTimeBy(150)
        assertEquals(ModeCheck.OK, controller.flip(FlipDirection.FORWARD))
    }

    // ------------------------------------------------------------------ tracing

    @Test
    fun `flips sent, refused and answered are logged and queued for the flight log`() = session {
        flying()
        controller.flip(FlipDirection.FORWARD)
        controller.flip(FlipDirection.FORWARD)
        transport.droneMessage(MessageId.FLIP, byteArrayOf(0))
        scope.runCurrent()
        assertEquals(listOf("flip:forward", "flip_refused:flip_locked", "flip_reply:ok"), controller.drainLogEvents())
        assertEquals(emptyList<String>(), controller.drainLogEvents(), "drained")
        assertTrue(diagnostics.any { it.startsWith("flip forward sent=true") }, "$diagnostics")
        assertTrue(diagnostics.any { it == "flip reply: accepted" })
    }

    @Test
    fun `a flip that could not be sent is logged as such, and still locks`() = session {
        flying()
        transport.failing = true
        assertEquals(ModeCheck.OK, controller.flip(FlipDirection.FORWARD))
        transport.failing = false
        assertEquals(listOf("flip_unsent:forward"), controller.drainLogEvents())
        assertEquals(ModeCheck.FLIP_LOCKED, controller.flip(FlipDirection.FORWARD))
    }

    @Test
    fun `a flip refused by the drone ends the hold, the sticks still wait to be centred`() = session {
        flying()
        controller.setSticks(StickInput(pitch = 0.6f))
        controller.flip(FlipDirection.FORWARD)
        scope.advanceTimeBy(100)
        transport.droneMessage(MessageId.FLIP, byteArrayOf(1))
        scope.runCurrent()
        controller.setSticks(StickInput())
        scope.advanceTimeBy(40)
        controller.setSticks(StickInput(pitch = 0.6f))
        scope.advanceTimeBy(40)
        assertTrue(!transport.sticks().last().isNeutralStick(), "flies again well before 1.5 s")
        assertTrue(scope.testScheduler.currentTime < FlightModeRules.FLIP_MIN_FLYING_MS + FlightModeRules.FLIP_STICK_HOLD_MS)
    }

    @Test
    fun `the lock and the hold survive a new controller of the same session`() = session {
        flying()
        val guard = FlipGuard()
        val first = TelloController(FakeTransport(scope), clock = { scope.testScheduler.currentTime }, flipGuard = guard)
        guard.lastFlipAt.set(scope.testScheduler.currentTime)
        guard.stickHoldUntil.set(scope.testScheduler.currentTime + FlightModeRules.FLIP_STICK_HOLD_MS)
        guard.sticksNeedRecentre.set(true)
        first.setSticks(StickInput(roll = 1f))
        assertEquals(StickInput.Neutral, first.currentSticks)
        // Reconnection: a new controller with the session's guard.
        val second = TelloController(FakeTransport(scope), clock = { scope.testScheduler.currentTime }, flipGuard = guard)
        second.setSticks(StickInput(roll = 1f))
        assertEquals(StickInput.Neutral, second.currentSticks, "still held")
        scope.advanceTimeBy(FlightModeRules.FLIP_STICK_HOLD_MS)
        assertEquals(StickInput.Neutral, second.currentSticks, "still waiting to be centred")
    }

    @Test
    fun `undrained events stay bounded`() = session {
        flying(heightDm = 6)
        repeat(500) { controller.flip(FlipDirection.FORWARD) }
        assertEquals(64, controller.drainLogEvents().size)
    }
}
