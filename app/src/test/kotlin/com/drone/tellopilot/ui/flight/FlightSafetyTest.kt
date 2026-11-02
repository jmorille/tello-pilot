package com.drone.tellopilot.ui.flight

import com.drone.tello.control.Mission
import com.drone.tello.control.MissionStep
import com.drone.tello.control.PadFunction
import com.drone.tello.control.TrackingMode
import com.drone.tello.protocol.FlightData
import com.drone.tello.protocol.FlipDirection
import com.drone.tello.protocol.SmartVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightSafetyTest {

    private val hovering = FlightData(
        heightMeters = 1.0f, northSpeed = 0f, eastSpeed = 0f, verticalSpeed = 0f, flyTimeSeconds = 3f,
        batteryPercent = 80, flying = true, onGround = false, motorsOn = true, hovering = true,
        batteryLow = false, batteryCritical = false, flyMode = 6,
    )
    private val onGround = hovering.copy(heightMeters = 0f, flying = false, onGround = true, motorsOn = false, hovering = false)

    // --- Stick arbitration ----------------------------------------------------------------

    @Test
    fun `touch and pad are merged per axis, the largest deflection wins`() {
        assertEquals(0.8f, mergeAxis(0.8f, -0.3f))
        assertEquals(-0.9f, mergeAxis(0.2f, -0.9f)) // the sign of the winner is kept
        assertEquals(0.5f, mergeAxis(0f, 0.5f)) // finger lifted: the held pad stick stays
        assertEquals(-0.4f, mergeAxis(-0.4f, 0f)) // pad centred: the held touch stick stays
        assertEquals(0.6f, mergeAxis(0.6f, -0.6f)) // tie: touch
    }

    @Test
    fun `only a clear stick movement takes over from the autopilot`() {
        assertFalse(overridesAutopilot(0f))
        assertFalse(overridesAutopilot(0.24f)) // drift, a resting thumb
        assertTrue(overridesAutopilot(AUTOPILOT_OVERRIDE))
        assertTrue(overridesAutopilot(1f))
        assertTrue(belowAutopilotOverride(0.24f))
        assertFalse(belowAutopilotOverride(AUTOPILOT_OVERRIDE))
        // A NaN deflection (bad input) neither keeps the autopilot nor counts as a deliberate move.
        assertFalse(belowAutopilotOverride(Float.NaN))
        assertFalse(overridesAutopilot(Float.NaN))
    }

    @Test
    fun `every manual flight command ends an automatic flight, camera commands do not`() {
        val ending = listOf(
            FlightAction.Takeoff, FlightAction.Land, FlightAction.ThrowTakeoff, FlightAction.PalmLand,
            FlightAction.Flip(FlipDirection.LEFT), FlightAction.SetBounce(true),
            FlightAction.StartSmartVideo(SmartVideoMode.CIRCLE), FlightAction.StopAutopilot, FlightAction.ReleaseSticks,
            FlightAction.StartMission(Mission("m", listOf(MissionStep.Hover(1f)))),
            FlightAction.StartTracking(TrackingMode.FOLLOW), FlightAction.ToggleFastMode, FlightAction.ToggleTripod,
        )
        ending.forEach { assertTrue("$it", it.endsAutopilot()) }
        listOf(FlightAction.TakePhoto, FlightAction.ToggleRecording, FlightAction.StopSmartVideo)
            .forEach { assertFalse("$it", it.endsAutopilot()) }
    }

    // --- Pad shortcuts -------------------------------------------------------------------

    private val missions = listOf(Mission("Carré", listOf(MissionStep.Hover(1f))))

    private fun decide(
        f: PadFunction,
        flying: Boolean = true,
        running: Boolean = false,
        bounce: Boolean = false,
    ) = shortcutDecision(f, missions, flying, running, bounce)

    @Test
    fun `shortcuts map to the screen actions`() {
        assertEquals(ShortcutDecision.Run(FlightAction.StartSmartVideo(SmartVideoMode.UP_AND_AWAY)), decide(PadFunction.SmartVideo(SmartVideoMode.UP_AND_AWAY)))
        assertEquals(ShortcutDecision.Run(FlightAction.Flip(FlipDirection.BACK_LEFT)), decide(PadFunction.Flip(FlipDirection.BACK_LEFT)))
        assertEquals(ShortcutDecision.Run(FlightAction.SetBounce(true)), decide(PadFunction.Bounce))
        assertEquals(ShortcutDecision.Run(FlightAction.StartMission(missions[0])), decide(PadFunction.RunMission("Carré")))
        assertEquals(ShortcutDecision.Run(FlightAction.StartTracking(TrackingMode.ORBIT, -1)), decide(PadFunction.Tracking(TrackingMode.ORBIT, -1)))
        assertEquals(ShortcutDecision.Run(FlightAction.StartTracking(TrackingMode.SPOTLIGHT, 1)), decide(PadFunction.Tracking(TrackingMode.SPOTLIGHT)))
        assertEquals(ShortcutDecision.Run(FlightAction.PalmLand), decide(PadFunction.PalmLand))
        assertEquals(ShortcutDecision.Run(FlightAction.ToggleTripod), decide(PadFunction.Tripod))
        assertEquals(ShortcutDecision.Run(FlightAction.ToggleFastMode), decide(PadFunction.FastMode))
        assertEquals(ShortcutDecision.Run(FlightAction.TakePhoto), decide(PadFunction.Photo))
        assertEquals(ShortcutDecision.Run(FlightAction.ToggleRecording), decide(PadFunction.Record))
        assertEquals(ShortcutDecision.StopAll, decide(PadFunction.Stop))
    }

    @Test
    fun `moving shortcuts are refused while a manoeuvre runs, stops never are`() {
        val busy = ShortcutDecision.Refuse(UserMessage.ModeRefused(com.drone.tello.control.ModeCheck.BUSY))
        (PadFunction.Catalog.filter { it.moves } + PadFunction.RunMission("Carré")).forEach { f ->
            assertEquals("$f", busy, decide(f, running = true))
        }
        assertEquals(ShortcutDecision.StopAll, decide(PadFunction.Stop, running = true))
        assertEquals(ShortcutDecision.Run(FlightAction.SetBounce(false)), decide(PadFunction.Bounce, running = true, bounce = true))
        assertEquals(ShortcutDecision.Run(FlightAction.TakePhoto), decide(PadFunction.Photo, running = true))
    }

    @Test
    fun `a deleted mission or a palm landing on the ground is refused with a message`() {
        assertEquals(ShortcutDecision.Refuse(UserMessage.ShortcutMissionMissing("Disparue")), decide(PadFunction.RunMission("Disparue")))
        assertEquals(ShortcutDecision.Refuse(UserMessage.ShortcutMissionMissing("Disparue")), decide(PadFunction.RunMission("Disparue"), running = true))
        assertEquals(
            ShortcutDecision.Refuse(UserMessage.ModeRefused(com.drone.tello.control.ModeCheck.NOT_FLYING)),
            decide(PadFunction.PalmLand, flying = false),
        )
    }

    // --- Gamepad takeoff / land combo: landing has priority -------------------------------

    @Test
    fun `pad combo takes off only from a certain on-ground state`() {
        assertEquals(FlightAction.Takeoff, padComboAction(onGround, knownFlying = false, landing = false))
    }

    @Test
    fun `pad combo lands whenever the drone may be airborne`() {
        assertEquals(FlightAction.Land, padComboAction(hovering, knownFlying = true, landing = false))
        assertEquals(FlightAction.Land, padComboAction(null, knownFlying = false, landing = false)) // unknown state
        assertEquals(FlightAction.Land, padComboAction(onGround.copy(motorsOn = true), knownFlying = false, landing = false))
        // Stale "on ground" sample while the last report said flying (link lost in flight).
        assertEquals(FlightAction.Land, padComboAction(onGround, knownFlying = true, landing = false))
        // A landing in progress is never turned into a takeoff.
        assertEquals(FlightAction.Land, padComboAction(onGround, knownFlying = false, landing = true))
    }

    // --- Takeoff height -------------------------------------------------------------------

    @Test
    fun `takeoff height is armed only by the app's own recent takeoff with the setting on`() {
        val takeoffAt = 50_000L
        assertTrue(TakeoffHeight.armed(1.5f, takeoffAt + 3_000, takeoffAt))
        assertTrue(TakeoffHeight.armed(1.5f, takeoffAt + TakeoffHeight.ARM_MS, takeoffAt))
        assertFalse(TakeoffHeight.armed(1.5f, takeoffAt + TakeoffHeight.ARM_MS + 1, takeoffAt)) // too late
        assertFalse(TakeoffHeight.armed(1.5f, takeoffAt, Long.MIN_VALUE / 2)) // app restarted in flight
        assertFalse(TakeoffHeight.armed(0f, takeoffAt + 1_000, takeoffAt)) // setting off
        assertFalse(TakeoffHeight.armed(-1f, takeoffAt + 1_000, takeoffAt))
    }

    private fun step(
        target: Float = 2f,
        flight: FlightData = hovering,
        landingRequested: Boolean = false,
        connected: Boolean = true,
        autopilotActive: Boolean = false,
        smartVideoRunning: Boolean = false,
        bounce: Boolean = false,
        pilotDeflection: Float = 0f,
    ) = TakeoffHeight.step(target, flight, landingRequested, connected, autopilotActive, smartVideoRunning, bounce, pilotDeflection)

    @Test
    fun `climbs or descends by the difference to the target`() {
        assertEquals(1f, step(target = 2f, flight = hovering.copy(heightMeters = 1f))!!.meters, 1e-6f)
        assertEquals(-0.7f, step(target = 0.5f, flight = hovering.copy(heightMeters = 1.2f))!!.meters, 1e-6f)
    }

    @Test
    fun `nothing happens within the tolerance`() {
        assertNull(step(target = 1.1f, flight = hovering.copy(heightMeters = 1.0f)))
        assertNull(step(target = 1.0f, flight = hovering.copy(heightMeters = 1.1f)))
        assertEquals(0.2f, step(target = 1.2f, flight = hovering.copy(heightMeters = 1.0f))!!.meters, 1e-6f)
    }

    @Test
    fun `the step is clamped to the mission climb range`() {
        // Took off from a high spot: the height sensor reads far above the target.
        val s = step(target = 1f, flight = hovering.copy(heightMeters = 9f))!!
        assertEquals(MissionStep.CLIMB_RANGE.start, s.meters, 1e-6f)
        assertTrue(s.isValid())
    }

    @Test
    fun `any unsafe condition vetoes the adjustment`() {
        assertNull(step(flight = hovering.copy(flying = false)))
        assertNull(step(flight = hovering.copy(flyMode = FlightData.FLY_MODE_LANDING)))
        assertNull(step(landingRequested = true))
        assertNull(step(flight = hovering.copy(batteryLow = true)))
        assertNull(step(flight = hovering.copy(batteryCritical = true)))
        assertNull(step(connected = false))
        assertNull(step(autopilotActive = true))
        assertNull(step(smartVideoRunning = true))
        assertNull(step(bounce = true))
    }

    @Test
    fun `a stick touched by the pilot cancels the adjustment`() {
        assertNull(step(pilotDeflection = 0.06f))
        assertTrue(TakeoffHeight.cancelledBySticks(0.06f))
        // Below the idle threshold (noise on a centred stick) the adjustment still runs.
        assertFalse(TakeoffHeight.cancelledBySticks(TakeoffHeight.STICK_IDLE))
        assertEquals(1f, step(pilotDeflection = TakeoffHeight.STICK_IDLE)!!.meters, 1e-6f)
    }
}
