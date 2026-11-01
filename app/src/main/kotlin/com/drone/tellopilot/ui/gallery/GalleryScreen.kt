package com.drone.tellopilot.ui.gallery

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drone.tellopilot.R
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.video.TranscodeQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A photo or video of the app (`Pictures/TelloPilot`, `Movies/TelloPilot`). */
data class GalleryItem(
    val uri: Uri,
    val name: String,
    val video: Boolean,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateMs: Long,
    /** Video format, e.g. "H.264", "HEVC", "AV1"; null for photos or if unknown. */
    val codec: String?,
)

enum class GalleryFilter { ALL, PHOTOS, VIDEOS }

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcodeQueue: TranscodeQueue,
    session: DroneSession,
) : ViewModel() {

    private val _items = MutableStateFlow<List<GalleryItem>?>(null)
    val items: StateFlow<List<GalleryItem>?> = _items.asStateFlow()

    /** The drone took off (armed throw takeoff…): the gallery gives way to the flight screen. */
    val flying: StateFlow<Boolean> = session.telemetry.map { it.flight?.flying == true }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val thumbnails = object : LruCache<Uri, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    fun refresh() {
        viewModelScope.launch { _items.value = withContext(Dispatchers.IO) { query() } }
    }

    fun reencode(item: GalleryItem) = transcodeQueue.enqueueVideo(item.uri)

    suspend fun thumbnail(uri: Uri): Bitmap? = thumbnails.get(uri) ?: withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(uri, Size(THUMB_W, THUMB_H), null) }.getOrNull()
            ?.also { thumbnails.put(uri, it) }
    }

    private fun query(): List<GalleryItem> {
        val photos = queryCollection(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            Environment.DIRECTORY_PICTURES, video = false,
        )
        val videos = queryCollection(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            Environment.DIRECTORY_MOVIES, video = true,
        )
        return (photos + videos).sortedByDescending { it.dateMs }
    }

    private fun queryCollection(collection: Uri, dir: String, video: Boolean): List<GalleryItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (video) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        val result = ArrayList<GalleryItem>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("$dir/TelloPilot%"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                result += GalleryItem(
                    uri = uri,
                    name = c.getString(1) ?: "",
                    video = video,
                    sizeBytes = c.getLong(2),
                    durationMs = if (video) c.getLong(4) else 0,
                    dateMs = c.getLong(3) * 1000,
                    codec = if (video) codecOf(uri) else null,
                )
            }
        }
        return result
    }

    private fun codecOf(uri: Uri): String? = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val mime = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it?.startsWith("video/") == true }
            codecLabel(mime)
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private companion object {
        const val THUMB_W = 320
        const val THUMB_H = 240
        const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
    }
}

@Composable
fun GalleryRoute(onBack: () -> Unit, viewModel: GalleryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onBack() }
    // Back from the viewer / a deletion: the list may have changed.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val context = LocalContext.current
        GalleryScreen(items, onBack, viewModel::thumbnail, reencode = {
            viewModel.reencode(it)
            android.widget.Toast.makeText(context, R.string.gallery_reencode_started, android.widget.Toast.LENGTH_SHORT).show()
        }, refresh = viewModel::refresh)
    }
}

@Composable
private fun GalleryScreen(
    items: List<GalleryItem>?,
    onBack: () -> Unit,
    thumbnail: suspend (Uri) -> Bitmap?,
    reencode: (GalleryItem) -> Unit,
    refresh: () -> Unit,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(GalleryFilter.ALL) }
    // Deletion goes through Android's own confirmation dialog.
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refresh() }
    val shown = items.orEmpty().filter {
        when (filter) {
            GalleryFilter.ALL -> true
            GalleryFilter.PHOTOS -> !it.video
            GalleryFilter.VIDEOS -> it.video
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.titleLarge)
            GalleryFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = {
                        Text(stringResource(when (f) {
                            GalleryFilter.ALL -> R.string.gallery_all
                            GalleryFilter.PHOTOS -> R.string.gallery_photos
                            GalleryFilter.VIDEOS -> R.string.gallery_videos
                        }))
                    },
                )
            }
            Text(
                stringResource(R.string.gallery_summary, shown.size, Formatter.formatShortFileSize(context, shown.sumOf { it.sizeBytes })),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            items == null -> Unit
            shown.isEmpty() -> Text(
                stringResource(R.string.gallery_empty),
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.uri.toString() }) { item ->
                    GalleryCard(
                        item, thumbnail,
                        onOpen = { open(context, item) },
                        onShare = { share(context, item) },
                        onReencode = { reencode(item) },
                        onDelete = {
                            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.uri))
                            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(
    item: GalleryItem,
    thumbnail: suspend (Uri) -> Bitmap?,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReencode: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) { value = thumbnail(item.uri) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black).clickable(onClick = onOpen)) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (item.video) {
                Text(
                    listOfNotNull(item.codec, DateUtils.formatElapsedTime(item.durationMs / 1000)).joinToString(" · "),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    DateUtils.formatDateTime(context, item.dateMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Text(Formatter.formatShortFileSize(context, item.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_open)) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_share)) }, onClick = { menu = false; onShare() })
                    if (item.codec == "H.264") {
                        DropdownMenuItem(text = { Text(stringResource(R.string.gallery_reencode)) }, onClick = { menu = false; onReencode() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Short codec name shown on a video card ("H.264", "HEVC", "AV1"...), from its MIME type. */
internal fun codecLabel(mime: String?): String? = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
    MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
    MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
    else -> mime?.removePrefix("video/")?.uppercase()
}

private fun mimeOf(item: GalleryItem) = if (item.video) "video/mp4" else "image/jpeg"

private fun open(context: Context, item: GalleryItem) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, mimeOf(item))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

private fun share(context: Context, item: GalleryItem) {
    val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(item))
        .putExtra(Intent.EXTRA_STREAM, item.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
