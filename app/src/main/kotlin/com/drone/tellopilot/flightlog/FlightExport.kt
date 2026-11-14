package com.drone.tellopilot.flightlog

import android.content.Intent
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Outcome of an export: flights written, flights skipped because their file is gone. */
data class ExportResult(val exported: List<String>, val missing: List<String>)

/**
 * Exporting flight files (pure JVM, no Android framework): ZIP archive of several CSV,
 * names, and the share sheet plan for one or several files.
 */
object FlightExport {

    const val CSV_MIME = "text/csv"
    const val ZIP_MIME = "application/zip"

    /** `tello-flights-yyyyMMdd-HHmmss.zip`, local time. */
    fun zipFileName(nowMs: Long): String =
        "tello-flights-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs)) + ".zip"

    fun csvFileName(id: String): String = "$id${FlightLogStore.EXT}"

    /**
     * One entry name per id, unique (case-insensitive: the archive may be extracted on
     * a case-insensitive file system): `id.csv`, then `id-2.csv`, `id-3.csv`…
     */
    fun entryNames(ids: List<String>): List<String> {
        val used = HashSet<String>()
        return ids.map { id ->
            val base = id.ifBlank { "flight" }
            var name = csvFileName(base)
            var n = 2
            while (!used.add(name.lowercase(Locale.ROOT))) name = csvFileName("$base-${n++}")
            name
        }
    }

    /**
     * Writes a ZIP of the flight files to [out] (closed on return). [files] maps each id to
     * its file, `null` when the flight no longer exists; a missing or unreadable file is
     * skipped and reported in [ExportResult.missing]. Blocking I/O: call off the main thread.
     * Throws [IOException] when [out] fails, and when no file at all could be added
     * (a ZIP needs at least one entry).
     */
    fun writeZip(out: OutputStream, files: List<Pair<String, File?>>): ExportResult {
        val exported = ArrayList<String>()
        val missing = ArrayList<String>()
        val names = entryNames(files.map { it.first })
        ZipOutputStream(out.buffered()).use { zip ->
            files.forEachIndexed { i, (id, file) ->
                // Opened before the entry is started: a file deleted in the meantime leaves no empty entry.
                val input = file?.takeIf { it.isFile }?.let { runCatching { FileInputStream(it) }.getOrNull() }
                if (input == null) {
                    missing += id
                    return@forEachIndexed
                }
                input.use {
                    zip.putNextEntry(ZipEntry(names[i]).apply { time = file.lastModified() })
                    it.copyTo(zip)
                    zip.closeEntry()
                }
                exported += id
            }
            if (exported.isEmpty()) throw NothingToExport(missing)
        }
        return ExportResult(exported, missing)
    }

    /** No flight of the selection exists any more. */
    class NothingToExport(val missing: List<String>) : IOException("no flight file to export")

    /** What the share sheet receives: one file ([Intent.ACTION_SEND]) or several ([Intent.ACTION_SEND_MULTIPLE]). */
    data class SharePlan<U>(val action: String, val mimeType: String, val streams: List<U>, val subject: String?, val flags: Int)

    /** `null` when there is nothing to share. */
    fun <U> sharePlan(streams: List<U>, names: List<String>): SharePlan<U>? = when (streams.size) {
        0 -> null
        1 -> SharePlan(Intent.ACTION_SEND, CSV_MIME, streams, names.firstOrNull(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else -> SharePlan(Intent.ACTION_SEND_MULTIPLE, CSV_MIME, streams, null, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
