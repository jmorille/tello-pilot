package com.drone.tellopilot.flightlog

import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** Export of several flights: ZIP archive, entry names, missing files, share plan. */
class FlightExportTest {

    @get:Rule val tmp = TemporaryFolder()

    private val store by lazy {
        val files = tmp.root
        FlightLogStore(object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
        })
    }

    private fun recordFlight(start: Long): String {
        val file = store.create(start)
        FlightLogStore.FlightWriter(file, start).apply {
            repeat(3) { append(FlightSample(tMs = it * 200L, heightM = 1f, northMps = 0f, eastMps = 0f, climbMps = 0f, batteryPct = 80, flying = true)) }
            close()
        }
        store.activeId = null
        return file.nameWithoutExtension
    }

    /** Entry name → content of a ZIP archive. */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    private fun file(name: String, content: String) = tmp.newFile(name).apply { writeText(content) }

    @Test
    fun `zip file name carries the local date and time`() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("tello-flights-20231114-221320.zip", FlightExport.zipFileName(1_700_000_000_000))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `entry names are unique, case-insensitively`() {
        assertEquals(
            listOf("a.csv", "b.csv", "a-2.csv", "A-3.csv", "flight.csv"),
            FlightExport.entryNames(listOf("a", "b", "a", "A", "")),
        )
    }

    @Test
    fun `the zip holds every csv with its content`() {
        val out = ByteArrayOutputStream()
        val result = FlightExport.writeZip(out, listOf("f1" to file("f1.csv", "one"), "f2" to file("f2.csv", "two")))
        assertEquals(listOf("f1", "f2"), result.exported)
        assertTrue(result.missing.isEmpty())
        assertEquals(mapOf("f1.csv" to "one", "f2.csv" to "two"), unzip(out.toByteArray()))
    }

    @Test
    fun `duplicate ids get distinct entries`() {
        val out = ByteArrayOutputStream()
        FlightExport.writeZip(out, listOf("f" to file("x.csv", "x"), "f" to file("y.csv", "y")))
        assertEquals(mapOf("f.csv" to "x", "f-2.csv" to "y"), unzip(out.toByteArray()))
    }

    @Test
    fun `a missing file is skipped and reported`() {
        val gone = File(tmp.root, "gone.csv")
        val out = ByteArrayOutputStream()
        val result = FlightExport.writeZip(out, listOf("ok" to file("ok.csv", "ok"), "gone" to gone, "null" to null))
        assertEquals(listOf("ok"), result.exported)
        assertEquals(listOf("gone", "null"), result.missing)
        assertEquals(mapOf("ok.csv" to "ok"), unzip(out.toByteArray()))
    }

    @Test
    fun `nothing to export when every file is missing`() {
        try {
            FlightExport.writeZip(ByteArrayOutputStream(), listOf("a" to null, "b" to File(tmp.root, "b.csv")))
            fail("expected NothingToExport")
        } catch (e: FlightExport.NothingToExport) {
            assertEquals(listOf("a", "b"), e.missing)
        }
    }

    @Test
    fun `the store zips its flights and reports a deleted one`() = runBlocking {
        val first = recordFlight(1_700_000_000_000)
        val second = recordFlight(1_700_000_100_000)
        val deleted = recordFlight(1_700_000_200_000)
        store.delete(deleted)
        val out = ByteArrayOutputStream()
        val result = store.exportZip(listOf(second, deleted, first), out)
        assertEquals(listOf(second, first), result.exported)
        assertEquals(listOf(deleted), result.missing)
        val entries = unzip(out.toByteArray())
        assertEquals(listOf("$second.csv", "$first.csv"), entries.keys.toList())
        assertEquals(File(tmp.root, "flights/$first.csv").readText(), entries["$first.csv"])
    }

    @Test
    fun `the store copies a single csv, and reports a missing one`() = runBlocking {
        val id = recordFlight(1_700_000_000_000)
        val out = ByteArrayOutputStream()
        assertTrue(store.exportCsv(id, out))
        assertEquals(File(tmp.root, "flights/$id.csv").readText(), out.toString(Charsets.UTF_8.name()))
        assertEquals(false, store.exportCsv("flight-missing", ByteArrayOutputStream()))
        assertEquals(false, store.exportCsv("../escape", ByteArrayOutputStream()))
    }

    @Test
    fun `the store deletes several flights at once`() = runBlocking {
        val a = recordFlight(1_700_000_000_000)
        val b = recordFlight(1_700_000_100_000)
        val c = recordFlight(1_700_000_200_000)
        store.delete(listOf(a, c))
        assertEquals(listOf(b), store.list().map { it.id })
    }

    @Test
    fun `one file is shared with ACTION_SEND, several with ACTION_SEND_MULTIPLE`() {
        assertNull(FlightExport.sharePlan(emptyList<String>(), emptyList()))

        val single = FlightExport.sharePlan(listOf("content://a"), listOf("a.csv"))!!
        assertEquals(Intent.ACTION_SEND, single.action)
        assertEquals("text/csv", single.mimeType)
        assertEquals(listOf("content://a"), single.streams)
        assertEquals("a.csv", single.subject)
        assertTrue(single.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val many = FlightExport.sharePlan(listOf("content://a", "content://b", "content://c"), listOf("a.csv", "b.csv", "c.csv"))!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, many.action)
        assertEquals("text/csv", many.mimeType)
        assertEquals(listOf("content://a", "content://b", "content://c"), many.streams)
        assertNull(many.subject)
        assertTrue(many.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
