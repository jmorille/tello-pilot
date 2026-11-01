package com.drone.tellopilot.ui.flights

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drone.tellopilot.R
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.flightlog.FlightEntry
import com.drone.tellopilot.flightlog.FlightLog
import com.drone.tellopilot.flightlog.FlightLogStore
import com.drone.tellopilot.flightlog.FlightSample
import com.drone.tellopilot.flightlog.FlightStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private fun DroneSession.flyingState(vm: ViewModel): StateFlow<Boolean> =
    telemetry.map { it.flight?.flying == true }.distinctUntilChanged()
        .stateIn(vm.viewModelScope, SharingStarted.Eagerly, false)

@HiltViewModel
class FlightsViewModel @Inject constructor(
    private val store: FlightLogStore,
    session: DroneSession,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<FlightEntry>?>(null)
    val entries: StateFlow<List<FlightEntry>?> = _entries.asStateFlow()

    /** The drone took off: the screen gives way to the flight screen. */
    val flying: StateFlow<Boolean> = session.flyingState(this)

    init {
        // Reloads when a flight is saved or deleted.
        viewModelScope.launch { store.changes.collect { _entries.value = store.list() } }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun shareUri(id: String) = store.shareUri(id)
}

@HiltViewModel
class FlightDetailViewModel @Inject constructor(
    private val store: FlightLogStore,
    session: DroneSession,
) : ViewModel() {

    private val _log = MutableStateFlow<FlightLog?>(null)
    val log: StateFlow<FlightLog?> = _log.asStateFlow()

    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    val flying: StateFlow<Boolean> = session.flyingState(this)

    private var loaded: String? = null

    fun load(id: String) {
        if (loaded == id) return
        loaded = id
        viewModelScope.launch {
            val log = store.load(id)
            _log.value = log
            _missing.value = log == null
        }
    }

    fun delete(id: String, then: () -> Unit) {
        viewModelScope.launch {
            store.delete(id)
            then()
        }
    }

    fun shareUri(id: String) = store.shareUri(id)
}

@Composable
fun FlightsRoute(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onFlying: () -> Unit,
    viewModel: FlightsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onFlying() }
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        FlightsScreen(
            entries = entries,
            onBack = onBack,
            onOpen = onOpen,
            onShare = { id -> share(context, id, viewModel.shareUri(id)) },
            onDelete = viewModel::delete,
        )
    }
}

@Composable
private fun FlightsScreen(
    entries: List<FlightEntry>?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Text(stringResource(R.string.flights_title), style = MaterialTheme.typography.titleLarge)
        }
        when {
            entries == null -> Unit
            entries.isEmpty() -> Text(
                stringResource(R.string.flights_empty),
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "stats") { StatsPanel(FlightStats.of(entries.map { it.summary })) }
                items(entries, key = { it.id }) { entry ->
                    FlightRow(entry, onOpen = { onOpen(entry.id) }, onShare = { onShare(entry.id) }, onDelete = { confirmDelete = entry.id })
                }
            }
        }
    }
    confirmDelete?.let { id ->
        DeleteDialog(onConfirm = { onDelete(id); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

@Composable
private fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flights_delete_title)) },
        text = { Text(stringResource(R.string.flights_delete_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.gallery_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.flights_cancel)) } },
    )
}

@Composable
private fun StatsPanel(stats: FlightStats) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.flights_stats_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.flights_stat_count), stats.flights.toString())
            StatTile(stringResource(R.string.flights_stat_total), duration(stats.totalMs))
            StatTile(stringResource(R.string.flights_stat_average), duration(stats.averageMs))
            StatTile(stringResource(R.string.flights_stat_longest), duration(stats.longestMs))
            StatTile(stringResource(R.string.flights_stat_height), meters(stats.maxHeightM))
            StatTile(stringResource(R.string.flights_stat_speed), speed(stats.maxSpeedMps))
            StatTile(
                stringResource(R.string.flights_stat_battery),
                stats.batteryPerMinute?.let { stringResource(R.string.flights_battery_rate, String.format(Locale.getDefault(), "%.1f", it)) }
                    ?: stringResource(R.string.flights_unknown),
            )
        }
        Text(stringResource(R.string.flights_stats_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FlightRow(entry: FlightEntry, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val s = entry.summary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dateTime(context, entry.startEpochMs), style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    duration(s.durationMs),
                    stringResource(R.string.flights_row_height, meters(s.maxHeightM)),
                    speed(s.maxSpeedMps),
                    s.batteryUsed?.let { stringResource(R.string.flights_row_battery, it, s.batteryStart ?: 0, s.batteryEnd ?: 0) },
                    if (entry.interrupted) stringResource(R.string.flights_interrupted) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_open)) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.flights_export)) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun FlightDetailRoute(
    id: String,
    onBack: () -> Unit,
    onFlying: () -> Unit,
    viewModel: FlightDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(id) { viewModel.load(id) }
    val log by viewModel.log.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onFlying() }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                Text(
                    log?.let { dateTime(context, it.startEpochMs) } ?: stringResource(R.string.flights_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (log != null) {
                    TextButton(onClick = { share(context, id, viewModel.shareUri(id)) }) { Text(stringResource(R.string.flights_export)) }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.gallery_delete)) }
                }
            }
            when {
                missing -> Text(stringResource(R.string.flights_missing), Modifier.padding(24.dp))
                else -> log?.let { FlightDetail(it) }
            }
        }
    }
    if (confirmDelete) {
        DeleteDialog(onConfirm = { confirmDelete = false; viewModel.delete(id, onBack) }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun FlightDetail(log: FlightLog) {
    val s = log.summary
    val samples = remember(log) { downsample(log.samples, MAX_POINTS) }
    val track = remember(log) { log.samples.filter { it.odoValid == true && it.odoX != null && it.odoY != null } }
    val heightColor = MaterialTheme.colorScheme.primary
    val speedColor = MaterialTheme.colorScheme.tertiary
    val climbColor = MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.flights_stat_duration), duration(s.durationMs))
            StatTile(stringResource(R.string.flights_stat_height), meters(s.maxHeightM))
            StatTile(stringResource(R.string.flights_stat_speed), speed(s.maxSpeedMps))
            StatTile(
                stringResource(R.string.flights_stat_battery_used),
                s.batteryUsed?.let { stringResource(R.string.flights_battery_used, it, s.batteryStart ?: 0, s.batteryEnd ?: 0) }
                    ?: stringResource(R.string.flights_unknown),
            )
            if (log.interrupted) StatTile(stringResource(R.string.flights_status), stringResource(R.string.flights_interrupted))
        }
        if (samples.size < 2) {
            Text(stringResource(R.string.flights_too_short))
            return@Column
        }
        val endMs = samples.last().tMs
        ChartCard(
            title = stringResource(R.string.flights_chart_height),
            series = listOf(Series(stringResource(R.string.flights_series_height), heightColor, samples.map { it.tMs to it.heightM })),
            unit = "m", endMs = endMs,
        )
        ChartCard(
            title = stringResource(R.string.flights_chart_speed),
            series = listOf(
                Series(stringResource(R.string.flights_series_horizontal), speedColor, samples.map { it.tMs to it.horizontalMps }),
                Series(stringResource(R.string.flights_series_climb), climbColor, samples.map { it.tMs to it.climbMps }),
            ),
            unit = "m/s", endMs = endMs,
        )
        if (track.size >= 2) TrackCard(downsample(track, MAX_POINTS), heightColor)
        Spacer(Modifier.height(12.dp))
    }
}

private data class Series(val label: String, val color: Color, val points: List<Pair<Long, Float>>)

@Composable
private fun ChartCard(title: String, series: List<Series>, unit: String, endMs: Long) {
    val all = series.flatMap { it.points }.map { it.second }
    val maxY = maxOf(all.maxOrNull() ?: 0f, 0f).let { if (it <= 0f) 1f else it }
    val minY = minOf(all.minOrNull() ?: 0f, 0f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            series.forEach { Legend(it.label, it.color) }
        }
        Row(Modifier.fillMaxWidth().height(160.dp)) {
            Column(Modifier.width(56.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(axis(maxY, unit), style = MaterialTheme.typography.labelSmall)
                Text(axis(minY, unit), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = title }) {
                val span = (maxY - minY).takeIf { it > 0f } ?: 1f
                fun x(t: Long) = if (endMs <= 0) 0f else t.toFloat() / endMs * size.width
                fun y(v: Float) = size.height - (v - minY) / span * size.height
                // Frame and zero line.
                drawLine(grid, Offset(0f, 0f), Offset(0f, size.height))
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
                if (minY < 0f) drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)))
                series.forEach { serie ->
                    val path = Path()
                    serie.points.forEachIndexed { i, (t, v) ->
                        if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                    }
                    drawPath(path, serie.color, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 56.dp)) {
            Text("0:00", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(DateUtils.formatElapsedTime(endMs / 1000), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Top-down track from the visual odometry (no GPS on the Tello: relative positions only). */
@Composable
private fun TrackCard(track: List<FlightSample>, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.error
    val xs = track.map { it.odoX!! }
    val ys = track.map { it.odoY!! }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val extent = maxOf(maxX - minX, maxY - minY, 0.5f)
    val title = stringResource(R.string.flights_chart_track)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Legend(stringResource(R.string.flights_track_start), startColor)
            Legend(stringResource(R.string.flights_track_end), endColor)
            Text(stringResource(R.string.flights_track_extent, meters(extent)), style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(R.string.flights_track_note), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.height(240.dp).aspectRatio(1f).semantics { contentDescription = title }) {
                // Same scale on both axes (a square stays a square), centred.
                val scale = size.minDimension / extent
                val cx = (minX + maxX) / 2
                val cy = (minY + maxY) / 2
                fun p(s: FlightSample) = Offset(
                    size.width / 2 + (s.odoX!! - cx) * scale,
                    size.height / 2 - (s.odoY!! - cy) * scale,
                )
                drawRect(grid, style = Stroke(width = 1.dp.toPx()))
                val path = Path()
                track.forEachIndexed { i, s ->
                    val o = p(s)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                drawCircle(startColor, 5.dp.toPx(), p(track.first()))
                drawCircle(endColor, 5.dp.toPx(), p(track.last()))
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** At most [max] points, evenly spaced, the last one kept. */
private fun downsample(samples: List<FlightSample>, max: Int): List<FlightSample> {
    if (samples.size <= max) return samples
    val step = samples.size.toFloat() / max
    return (0 until max).map { samples[(it * step).toInt()] } + samples.last()
}

private fun share(context: Context, id: String, uri: android.net.Uri?) {
    uri ?: return
    val intent = Intent(Intent.ACTION_SEND).setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "$id.csv")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(id, uri)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun dateTime(context: Context, epochMs: Long) =
    DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

private fun duration(ms: Long) = DateUtils.formatElapsedTime(ms / 1000)

private fun meters(v: Float) = String.format(Locale.getDefault(), "%.1f m", v)

private fun speed(v: Float) = String.format(Locale.getDefault(), "%.1f m/s", v)

private fun axis(v: Float, unit: String) = String.format(Locale.getDefault(), "%.1f %s", v, unit)

private const val MAX_POINTS = 600
