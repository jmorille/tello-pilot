package com.drone.tellopilot.flightlog

import android.content.ContextWrapper
import com.drone.tello.control.Telemetry
import com.drone.tellopilot.testutil.flightData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The black box: one file per flight, closed after landing, kept when interrupted, never a crash. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy {
        val files = tmp.root
        FlightLogStore(object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
        })
    }
    private val dir get() = File(tmp.root, FlightLogStore.DIR)

    /** What the drone currently reports (`null`: link down). */
    private var telemetry: Telemetry? = Telemetry(flight = flightData(flying = false))

    private var instance: FlightRecorder? = null

    /** A session run of the (singleton) recorder. */
    private fun TestScope.recorder(): kotlinx.coroutines.Job {
        val r = instance ?: FlightRecorder(store, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler)).also { instance = it }
        return backgroundScope.launch { r.record { telemetry } }
    }

    private fun flying(height: Float = 1f) {
        telemetry = Telemetry(flight = flightData(flying = true, height = height))
    }

    private fun landed() {
        telemetry = Telemetry(flight = flightData(flying = false))
    }

    private fun flights() = runBlocking { store.list() }

    @Test
    fun `a flight is recorded from takeoff to the end of the landing tail`() = runTest {
        recorder()
        advanceTimeBy(1_000)
        assertFalse(dir.exists() && dir.listFiles()!!.isNotEmpty()) // nothing on the ground
        flying()
        advanceTimeBy(300)
        assertTrue(store.activeId != null)
        flying(height = 3f)
        advanceTimeBy(3_000)
        landed()
        advanceTimeBy(4_000)
        assertTrue(store.activeId != null) // landing tail
        advanceTimeBy(1_500)
        assertNull(store.activeId)
        val flight = flights().single()
        assertFalse(flight.interrupted)
        assertEquals(3f, flight.summary.maxHeightM, 0.01f)
        assertTrue(flight.summary.samples in 40..45) // 5 Hz: 3.3 s in flight + 5 s of tail
        assertEquals(1, store.changes.value)
    }

    @Test
    fun `a spurious flying bit is not a flight`() = runTest {
        recorder()
        flying()
        advanceTimeBy(250)
        telemetry = null // link lost right away
        advanceTimeBy(61_000)
        assertNull(store.activeId)
        assertTrue(flights().isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a link lost in flight for a minute closes the flight, kept`() = runTest {
        recorder()
        flying()
        advanceTimeBy(2_000)
        telemetry = null
        advanceTimeBy(30_000)
        assertTrue(store.activeId != null) // a reconnection would continue the same flight
        advanceTimeBy(31_000)
        assertNull(store.activeId)
        assertEquals(1, flights().size)
    }

    @Test
    fun `a reconnection within the minute continues the same flight`() = runTest {
        recorder()
        flying()
        advanceTimeBy(2_000)
        telemetry = null
        advanceTimeBy(20_000)
        flying()
        advanceTimeBy(2_000)
        landed()
        advanceTimeBy(6_000)
        assertEquals(1, flights().size)
    }

    @Test
    fun `the session closing in flight keeps the flight, marked interrupted`() = runTest {
        val job = recorder()
        flying()
        advanceTimeBy(2_000)
        job.cancel()
        runCurrent()
        assertNull(store.activeId)
        assertTrue(flights().single().interrupted)
    }

    @Test
    fun `a disk error drops that flight only, the next one is recorded`() = runTest {
        // The flights folder cannot be created: a file is in the way.
        File(tmp.root, FlightLogStore.DIR).writeText("not a folder")
        recorder()
        flying()
        advanceTimeBy(2_000)
        assertNull(store.activeId)
        landed()
        advanceTimeBy(6_000)
        File(tmp.root, FlightLogStore.DIR).delete()
        flying()
        advanceTimeBy(2_000)
        landed()
        advanceTimeBy(6_000)
        assertEquals(1, flights().size)
    }

    @Test
    fun `overlapping sessions record a flight once`() = runTest {
        recorder()
        recorder()
        flying()
        advanceTimeBy(2_000)
        landed()
        advanceTimeBy(6_000)
        assertEquals(1, flights().size)
    }
}
