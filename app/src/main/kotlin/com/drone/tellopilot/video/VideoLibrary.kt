package com.drone.tellopilot.video

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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
    }
}
