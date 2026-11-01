package com.drone.tellopilot.video

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.drone.tello.control.TranscodeCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A flight video of `Movies/TelloPilot`. */
data class DroneVideo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateTakenMs: Long?,
    /** Video track MIME type (`video/avc`, `video/hevc`, `video/av01`), null if unreadable. */
    val mime: String?,
) {
    val isH264: Boolean get() = mime == MediaFormat.MIMETYPE_VIDEO_AVC
    val baseName: String get() = name.substringBeforeLast('.')

    /** Average bitrate, or 0 if unknown. */
    val bitrate: Int get() = if (durationMs > 0) (sizeBytes * 8_000 / durationMs).toInt() else 0
}

/**
 * The app's flight videos in the shared Movies collection. Without a storage permission
 * MediaStore only returns (and lets us delete) the files this app created.
 */
@Singleton
class VideoLibrary @Inject constructor(@ApplicationContext private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    suspend fun list(): List<DroneVideo> = query(
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
        arrayOf("$RELATIVE_DIR%"),
    )

    suspend fun get(uri: Uri): DroneVideo? = runCatching {
        query("${MediaStore.Video.Media._ID} = ?", arrayOf(ContentUris.parseId(uri).toString()))
    }.getOrNull()?.firstOrNull()

    private suspend fun query(selection: String, args: Array<String>): List<DroneVideo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_TAKEN,
        )
        val videos = ArrayList<DroneVideo>()
        resolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                // Size and duration of a file just published may not be scanned yet: read the file.
                val probe = probe(uri)
                val size = c.getLong(2).takeIf { it > 0 }
                    ?: runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
                videos += DroneVideo(
                    uri = uri,
                    name = c.getString(1) ?: "video.mp4",
                    sizeBytes = size,
                    durationMs = probe?.durationMs?.takeIf { it > 0 } ?: c.getLong(3),
                    dateTakenMs = if (c.isNull(4)) null else c.getLong(4),
                    mime = probe?.mime,
                )
            }
        }
        videos
    }

    /** Publishes [file] next to [original] (same folder and date, `-suffix` name), returns its Uri. */
    suspend fun publish(file: File, original: DroneVideo, suffix: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${original.baseName}-$suffix.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            original.dateTakenMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("cannot open $uri")
            // The original is deleted on the strength of this copy: it must be complete.
            val written = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            check(written == file.length()) { "copy incomplete: $written / ${file.length()}" }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * Videos of the app left pending (IS_PENDING=1, invisible everywhere) by a process that
     * died mid-recording (crash, kill, app update) or mid-publication: those still readable
     * (complete MP4) are published, the others (no index: the muxer never finished) deleted.
     * [isBusy]: skip everything while a recording runs; files written in the last minute are
     * skipped too (a publication in progress). Returns the published ones.
     */
    suspend fun recoverOrphans(isBusy: () -> Boolean): List<Uri> = withContext(Dispatchers.IO) {
        if (isBusy()) return@withContext emptyList()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$RELATIVE_DIR%"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        }
        val orphans = ArrayList<Triple<Uri, String, String?>>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                args,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    orphans += Triple(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1) ?: "?", c.getString(2))
                }
            }
        }.onFailure { Log.w(TAG, "pending videos query failed", it) }
        val now = System.currentTimeMillis()
        val published = ArrayList<Uri>()
        for ((uri, name, path) in orphans) {
            if (isBusy()) break
            val modified = path?.let { runCatching { File(it).lastModified() }.getOrNull() } ?: 0L
            val action = OrphanPolicy.decide(
                now - modified,
                readable = probe(uri)?.let { it.durationMs > 0 } == true,
                reencodedCopy = OrphanPolicy.isReencodedCopy(name),
            )
            Log.i(TAG, "pending video $name: $action")
            when (action) {
                OrphanPolicy.Action.SKIP -> Unit
                OrphanPolicy.Action.PUBLISH -> runCatching {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    published += uri
                }.onFailure { Log.w(TAG, "cannot publish $name", it) }
                OrphanPolicy.Action.DELETE -> runCatching { resolver.delete(uri, null, null) }
                    .onFailure { Log.w(TAG, "cannot delete $name", it) }
            }
        }
        published
    }

    /** Deletes a video the app created; false if Android refuses. */
    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    private class Probe(val mime: String?, val durationMs: Long)

    private fun probe(uri: Uri): Probe? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { f ->
                    Probe(
                        f.getString(MediaFormat.KEY_MIME),
                        if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1_000 else 0,
                    )
                }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    companion object {
        val RELATIVE_DIR = Environment.DIRECTORY_MOVIES + "/TelloPilot/"
        private const val TAG = "VideoLibrary"
    }
}

/** What to do with a video left pending by a dead process (pure, unit tested). */
object OrphanPolicy {
    /** Written more recently: maybe a publication in progress, not an orphan. */
    const val MIN_AGE_MS = 60_000L

    enum class Action { SKIP, PUBLISH, DELETE }

    /**
     * [ageMs]: time since the file was last written; [readable]: a complete, playable MP4;
     * [reencodedCopy]: a `-hevc`/`-av1` copy whose publication was interrupted. Such a copy is
     * always deleted (it may be truncated yet look readable, and its original still exists:
     * the original is only deleted after the copy is published), the video is simply redone.
     */
    fun decide(ageMs: Long, readable: Boolean, reencodedCopy: Boolean = false): Action = when {
        ageMs < MIN_AGE_MS -> Action.SKIP
        reencodedCopy -> Action.DELETE
        readable -> Action.PUBLISH
        else -> Action.DELETE
    }

    /** `tello_..._hhmmss-hevc.mp4` and the like: an output of the re-encoding. */
    fun isReencodedCopy(name: String): Boolean =
        TranscodeCodec.entries.any { name.substringBeforeLast('.').endsWith("-${it.suffix}") }
}
