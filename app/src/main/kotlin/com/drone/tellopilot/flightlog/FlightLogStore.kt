package com.drone.tellopilot.flightlog

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A recorded flight, as listed. */
data class FlightEntry(val id: String, val startEpochMs: Long, val summary: FlightSummary, val interrupted: Boolean, val sizeBytes: Long)

/** Flight files in app-private storage (`files/flights/flight-yyyyMMdd-HHmmss.csv`). */
@Singleton
class FlightLogStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir = File(context.filesDir, DIR)

    /** File being written by the recorder: never listed, repaired nor deleted. */
    @Volatile var activeId: String? = null
        internal set

    private val _changes = MutableStateFlow(0)
    /** Bumped when a flight is added or deleted (the list reloads). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    internal fun notifyChanged() {
        _changes.value++
    }

    /** Creates the file of a new flight (I/O thread). */
    internal fun create(startEpochMs: Long): File {
        dir.mkdirs()
        val base = "flight-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startEpochMs))
        var file = File(dir, "$base$EXT")
        var n = 2
        while (file.exists()) file = File(dir, "$base-${n++}$EXT")
        activeId = file.nameWithoutExtension
        return file
    }

    suspend fun list(): List<FlightEntry> = withContext(Dispatchers.IO) {
        val active = activeId
        files().filter { it.nameWithoutExtension != active }.mapNotNull { file ->
            try {
                entry(file)
            } catch (e: IOException) {
                Log.w(TAG, "cannot read ${file.name}", e)
                null
            }
        }.sortedByDescending { it.startEpochMs }
    }

    suspend fun load(id: String): FlightLog? = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext null
        try {
            file.bufferedReader().useLines { FlightLogCsv.parse(it) }
        } catch (e: IOException) {
            Log.w(TAG, "cannot read $id", e)
            null
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (id == activeId) return@withContext
        fileOf(id)?.delete()
        notifyChanged()
    }

    /** content:// URI of the CSV file for the share sheet (FileProvider). */
    fun shareUri(id: String): Uri? = fileOf(id)?.let { FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", it) }

    /** Applies the storage cap ([FlightRetention]), I/O thread. */
    internal fun prune() {
        val files = files()
        val stored = files.map { FlightRetention.Stored(it.nameWithoutExtension, it.length()) }
        val doomed = FlightRetention.toDelete(stored, keep = activeId).toSet()
        files.filter { it.nameWithoutExtension in doomed }.forEach {
            Log.i(TAG, "storage cap: deleting ${it.name}")
            it.delete()
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.toList().orEmpty()

    private fun fileOf(id: String): File? {
        // Ids come from file names; never let one escape the directory.
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        return File(dir, "$id$EXT").takeIf { it.isFile }
    }

    /** Footer read from the end of the file; without one (crash), parsed once and repaired. */
    private fun entry(file: File): FlightEntry? {
        val start = file.bufferedReader().use { FlightLogCsv.parseStart(it.readLine()) } ?: return null
        val footer = tail(file).lineSequence().mapNotNull { FlightLogCsv.parseFooter(it) }.lastOrNull()
        if (footer != null) {
            return FlightEntry(file.nameWithoutExtension, start, footer.first, footer.second, file.length())
        }
        val log = file.bufferedReader().useLines { FlightLogCsv.parse(it) } ?: return null
        // Interrupted flight: append its summary so that it is parsed only once.
        runCatching {
            FileOutputStream(file, true).use { out ->
                // The crash may have cut the last row: start the footer on a new line.
                out.write(("\n" + FlightLogCsv.footer(log.summary, interrupted = true)).toByteArray())
            }
        }.onFailure { Log.w(TAG, "cannot repair ${file.name}", it) }
        return FlightEntry(file.nameWithoutExtension, start, log.summary, interrupted = true, sizeBytes = file.length())
    }

    private fun tail(file: File): String = RandomAccessFile(file, "r").use { raf ->
        val size = minOf(raf.length(), TAIL_BYTES.toLong()).toInt()
        val buffer = ByteArray(size)
        raf.seek(raf.length() - size)
        raf.readFully(buffer)
        String(buffer, Charsets.UTF_8)
    }

    /** Appends rows to one flight file; each row is flushed (readable after a crash). */
    internal class FlightWriter(val file: File, startEpochMs: Long) {
        private val out = FileOutputStream(file, true)
        private val writer: Writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        private val summary = SummaryAccumulator()

        init {
            writer.write(FlightLogCsv.header(startEpochMs))
            writer.flush()
        }

        fun append(sample: FlightSample) {
            summary.add(sample)
            writer.write(FlightLogCsv.row(sample))
            writer.flush()
        }

        fun summary(): FlightSummary = summary.build()

        fun close() {
            try {
                writer.write(FlightLogCsv.footer(summary.build()))
                writer.flush()
                runCatching { out.fd.sync() }
            } finally {
                writer.close()
            }
        }

        fun abandon() {
            runCatching { writer.close() }
            file.delete()
        }
    }

    companion object {
        private const val TAG = "FlightLogStore"
        const val DIR = "flights"
        const val EXT = ".csv"
        /** Must match the provider declared in the manifest. */
        const val AUTHORITY_SUFFIX = ".flights"
        private const val TAIL_BYTES = 512
    }
}
