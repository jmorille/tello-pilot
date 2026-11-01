package com.drone.tellopilot.flightlog

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Flight files on disk: writer, listing, crash repair, deletion and storage cap. */
class FlightLogStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy {
        val files = tmp.root
        FlightLogStore(object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
        })
    }
    private val dir get() = File(tmp.root, FlightLogStore.DIR)

    private fun sample(t: Long, flying: Boolean = true, height: Float = 1f, battery: Int = 80) =
        FlightSample(tMs = t, heightM = height, northMps = 0f, eastMps = 0f, climbMps = 0f, batteryPct = battery, flying = flying)

    /** A finished flight file, written by the recorder's writer. */
    private fun recordFlight(start: Long, samples: Int = 6, interrupted: Boolean = false): String {
        val file = store.create(start)
        FlightLogStore.FlightWriter(file, start).apply {
            repeat(samples) { append(sample(it * 200L, height = it.toFloat(), battery = 90 - it)) }
            close(interrupted)
        }
        store.activeId = null
        file.setLastModified(start)
        return file.nameWithoutExtension
    }

    @Test
    fun `a written flight reads back with its samples and summary`() = runBlocking {
        val id = recordFlight(1_700_000_000_000)
        val log = store.load(id)!!
        assertEquals(1_700_000_000_000, log.startEpochMs)
        assertEquals(6, log.samples.size)
        assertFalse(log.interrupted)
        assertEquals(5f, log.summary.maxHeightM)
        assertEquals(5, log.summary.batteryUsed)
        assertEquals(1_000L, log.summary.durationMs)
    }

    @Test
    fun `rows are readable while the flight is still being written`() {
        val file = store.create(1_000)
        val writer = FlightLogStore.FlightWriter(file, 1_000)
        writer.append(sample(0))
        writer.append(sample(200))
        // Flushed row by row: a crash now keeps both samples.
        val log = FlightLogCsv.parse(file.readLines().asSequence())!!
        assertEquals(2, log.samples.size)
        assertTrue(log.interrupted)
        assertEquals(2, writer.summary().samples)
        writer.close()
    }

    @Test
    fun `an abandoned flight leaves no file`() {
        val file = store.create(1_000)
        FlightLogStore.FlightWriter(file, 1_000).apply { append(sample(0)) }.abandon()
        assertFalse(file.exists())
    }

    @Test
    fun `two flights in the same second get distinct files`() {
        val a = store.create(1_700_000_000_000)
        a.writeText("x")
        val b = store.create(1_700_000_000_000)
        b.writeText("x")
        val c = store.create(1_700_000_000_000)
        assertTrue(a.name.matches(Regex("flight-\\d{8}-\\d{6}\\.csv")))
        assertEquals(a.nameWithoutExtension + "-2.csv", b.name)
        assertEquals(a.nameWithoutExtension + "-3.csv", c.name)
        assertEquals(c.nameWithoutExtension, store.activeId)
    }

    @Test
    fun `list is newest first and skips the active flight and foreign files`() = runBlocking {
        val old = recordFlight(1_600_000_000_000)
        val new = recordFlight(1_700_000_000_000, interrupted = true)
        File(dir, "notes.csv").apply { writeText("hello\n"); setLastModified(0) }
        File(dir, "flight-x.txt").writeText("# tellopilot-flight v1 start=5\n")
        val entries = store.list()
        assertEquals(listOf(new, old), entries.map { it.id })
        assertTrue(entries[0].interrupted)
        assertFalse(entries[1].interrupted)
        assertEquals(6, entries[1].summary.samples)
        store.activeId = new
        assertEquals(listOf(old), store.list().map { it.id })
    }

    @Test
    fun `a flight cut by a crash is repaired once with an interrupted footer`() = runBlocking {
        val file = store.create(1_700_000_000_000)
        // Killed mid-row: no footer, a truncated last line.
        file.writeText(
            FlightLogCsv.header(1_700_000_000_000) +
                (0 until 5).joinToString("") { FlightLogCsv.row(sample(it * 200L, height = 2f)) } + "1000,2.0",
        )
        store.activeId = null
        file.setLastModified(System.currentTimeMillis() - 60_000)

        val entry = store.list().single()
        assertTrue(entry.interrupted)
        assertEquals(5, entry.summary.samples)
        val repaired = file.length()
        assertTrue(file.readLines().last().startsWith("# summary"))

        // Second listing: read from the footer, the file is not touched again.
        assertEquals(entry.summary, store.list().single().summary)
        assertEquals(repaired, file.length())
        assertEquals(5, store.load(file.nameWithoutExtension)!!.samples.size)
    }

    @Test
    fun `a footer-less file written moments ago is neither listed nor repaired`() = runBlocking {
        val file = store.create(1_700_000_000_000)
        file.writeText(FlightLogCsv.header(1_700_000_000_000) + FlightLogCsv.row(sample(0)))
        store.activeId = null // e.g. a second recorder instance owns it
        val size = file.length()
        assertTrue(store.list().isEmpty())
        assertEquals(size, file.length())
    }

    @Test
    fun `ids never escape the flights directory`() = runBlocking {
        File(tmp.root, "secret.csv").writeText("# tellopilot-flight v1 start=1\n")
        listOf("../secret", "..", "", "a/b", "a\\b").forEach { assertNull(it, store.load(it)) }
        store.delete("../secret")
        assertTrue(File(tmp.root, "secret.csv").exists())
    }

    @Test
    fun `delete removes a flight and notifies, but never the active one`() = runBlocking {
        val id = recordFlight(1_700_000_000_000)
        val before = store.changes.value
        store.delete(id)
        assertNull(store.load(id))
        assertTrue(store.changes.value > before)

        val active = recordFlight(1_700_000_100_000)
        store.activeId = active
        store.delete(active)
        assertNotNull(store.load(active))
    }

    @Test
    fun `prune keeps the newest flights and the one being recorded`() {
        dir.mkdirs()
        val count = FlightRetention.MAX_FLIGHTS + 2
        val names = (0 until count).map { i ->
            File(dir, "flight-%08d-000000.csv".format(i)).apply {
                writeText("# tellopilot-flight v1 start=$i\n")
                setLastModified(1_600_000_000_000 + i * 1_000L)
            }.nameWithoutExtension
        }
        store.activeId = names[0] // oldest, but being recorded
        store.prune()
        val left = dir.list()!!.map { it.removeSuffix(FlightLogStore.EXT) }.toSet()
        assertEquals(FlightRetention.MAX_FLIGHTS, left.size)
        assertTrue(names[0] in left)
        assertFalse(names[1] in left)
        assertFalse(names[2] in left)
        assertTrue(names.last() in left)
    }

    @Test
    fun `retention orders by file time rather than by name`() {
        // A clock / time zone change can make an older flight carry a later name.
        val files = listOf(
            FlightRetention.Stored("flight-20260105-120000", 10, modifiedMs = 1_000),
            FlightRetention.Stored("flight-20260101-120000", 10, modifiedMs = 5_000),
        )
        assertEquals(listOf("flight-20260105-120000"), FlightRetention.toDelete(files, maxFlights = 1))
    }
}
