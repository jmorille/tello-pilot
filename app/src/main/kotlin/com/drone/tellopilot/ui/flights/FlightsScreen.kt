package com.drone.tellopilot.ui.flights

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.SavedStateHandle
import com.drone.tellopilot.flightlog.FlightExport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
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

/** Result of an export, shown in a snackbar. */
sealed interface ExportMessage {
    /** [saved] flights written to the phone, [missing] skipped (deleted in the meantime). */
    data class Saved(val saved: Int, val missing: Int) : ExportMessage
    /** Some selected flights were gone when shared. */
    data class ShareMissing(val missing: Int) : ExportMessage
    /** None of the flights exists any more: nothing written. */
    data object NothingToExport : ExportMessage
    data object Failed : ExportMessage
}

/**
 * Save to the phone through the system file picker: what is being saved survives the
 * picker (and the process being killed meanwhile) in the [SavedStateHandle].
 */
private class PendingSave(private val state: SavedStateHandle) {
    var ids: List<String>?
        get() = state.get<ArrayList<String>>(KEY_IDS)
        set(value) { state[KEY_IDS] = value?.let(::ArrayList) }
    var zip: Boolean
        get() = state.get<Boolean>(KEY_ZIP) ?: true
        set(value) { state[KEY_ZIP] = value }

    /** Takes the pending save (at most once per picker). */
    fun take(): Pair<List<String>, Boolean>? = ids?.let { it to zip }.also { ids = null }

    private companion object {
        const val KEY_IDS = "pending_save_ids"
        const val KEY_ZIP = "pending_save_zip"
    }
}

private suspend fun FlightLogStore.saveMessage(target: Uri, ids: List<String>, zip: Boolean): ExportMessage = try {
    val result = saveTo(target, ids, zip)
    if (result.exported.isEmpty()) ExportMessage.NothingToExport else ExportMessage.Saved(result.exported.size, result.missing.size)
} catch (e: IOException) {
    ExportMessage.Failed
}

@HiltViewModel
class FlightsViewModel @Inject constructor(
    private val store: FlightLogStore,
    session: DroneSession,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<FlightEntry>?>(null)
    val entries: StateFlow<List<FlightEntry>?> = _entries.asStateFlow()

    /** Selected flights; empty outside the selection mode. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _messages = Channel<ExportMessage>(Channel.BUFFERED)
    val messages: Flow<ExportMessage> = _messages.receiveAsFlow()

    private val pending = PendingSave(savedState)

    /** The drone took off: the screen gives way to the flight screen. */
    val flying: StateFlow<Boolean> = session.flyingState(this)

    init {
        // Reloads when a flight is saved or deleted; a deleted flight leaves the selection.
        viewModelScope.launch {
            store.changes.collect {
                val entries = store.list()
                _entries.value = entries
                val ids = entries.mapTo(HashSet()) { it.id }
                _selected.update { selected -> selected.filterTo(LinkedHashSet()) { it in ids } }
            }
        }
    }

    /** Long press: enters the selection mode with [id]; once in, a tap toggles a flight. */
    fun toggle(id: String) {
        _selected.update { if (id in it) it - id else it + id }
    }

    /** All the flights, or none when they all are already selected (leaves the selection mode). */
    fun selectAll() {
        val all = _entries.value.orEmpty().map { it.id }
        _selected.update { if (it.containsAll(all)) emptySet() else all.toSet() }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Selected ids in list order (newest first). */
    fun selectedIds(): List<String> {
        val selected = _selected.value
        return _entries.value.orEmpty().map { it.id }.filter { it in selected }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun deleteSelected() {
        val ids = selectedIds()
        clearSelection()
        viewModelScope.launch { store.delete(ids) }
    }

    /** Share URI of one flight; a flight deleted in the meantime is reported. */
    fun shareOne(id: String): List<Pair<String, Uri>> =
        listOfNotNull(store.shareUri(id)?.let { id to it }).also { if (it.isEmpty()) _messages.trySend(ExportMessage.ShareMissing(1)) }

    /** Share URIs of the selected flights that still exist; the others are reported. */
    fun shareSelected(): List<Pair<String, Uri>> {
        val ids = selectedIds()
        val uris = store.shareUris(ids)
        if (uris.size < ids.size) _messages.trySend(ExportMessage.ShareMissing(ids.size - uris.size))
        return uris
    }

    /** Before opening the file picker for a ZIP of the selection; returns the suggested file name. */
    fun prepareZip(nowMs: Long = System.currentTimeMillis()): String {
        pending.ids = selectedIds()
        pending.zip = true
        return FlightExport.zipFileName(nowMs)
    }

    /** Before opening the file picker for one flight's CSV; returns the suggested file name. */
    fun prepareCsv(id: String): String {
        pending.ids = listOf(id)
        pending.zip = false
        return FlightExport.csvFileName(id)
    }

    /** File picker result: [target] `null` when cancelled (nothing written, selection kept). */
    fun onSaveTarget(target: Uri?) {
        val (ids, zip) = pending.take() ?: return
        if (target == null || ids.isEmpty()) return
        viewModelScope.launch {
            val message = store.saveMessage(target, ids, zip)
            if (zip && message is ExportMessage.Saved) clearSelection()
            _messages.send(message)
        }
    }
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

    /** Share URI of one flight; a flight deleted in the meantime is reported. */
    fun shareOne(id: String): List<Pair<String, Uri>> =
        listOfNotNull(store.shareUri(id)?.let { id to it }).also { if (it.isEmpty()) _messages.trySend(ExportMessage.ShareMissing(1)) }

    private val _messages = Channel<ExportMessage>(Channel.BUFFERED)
    val messages: Flow<ExportMessage> = _messages.receiveAsFlow()

    /** File picker result for this flight's CSV: [target] `null` when cancelled. */
    fun saveCsv(id: String, target: Uri?) {
        target ?: return
        viewModelScope.launch { _messages.send(store.saveMessage(target, listOf(id), zip = false)) }
    }
}

@Composable
fun FlightsRoute(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onFlying: () -> Unit,
    viewModel: FlightsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val flying by viewModel.flying.collectAsStateWithLifecycle()
    LaunchedEffect(flying) { if (flying) onFlying() }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    ExportMessages(viewModel.messages, snackbar)
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FlightExport.ZIP_MIME), viewModel::onSaveTarget)
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FlightExport.CSV_MIME), viewModel::onSaveTarget)
    // Back leaves the selection mode first.
    BackHandler(enabled = selected.isNotEmpty(), onBack = viewModel::clearSelection)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            FlightsScreen(
                entries = entries,
                selected = selected,
                onBack = onBack,
                onOpen = onOpen,
                onToggle = viewModel::toggle,
                onShare = { id -> share(context, viewModel.shareOne(id)) },
                onSave = { id -> launchSave(saveCsv, viewModel.prepareCsv(id)) },
                onDelete = viewModel::delete,
                onSelectAll = viewModel::selectAll,
                onClearSelection = viewModel::clearSelection,
                onShareSelected = { share(context, viewModel.shareSelected()) },
                onSaveSelected = { launchSave(saveZip, viewModel.prepareZip()) },
                onDeleteSelected = viewModel::deleteSelected,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        }
    }
}

@Composable
private fun FlightsScreen(
    entries: List<FlightEntry>?,
    selected: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onShare: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onShareSelected: () -> Unit,
    onSaveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                allSelected = entries.orEmpty().all { it.id in selected },
                onClose = onClearSelection,
                onSelectAll = onSelectAll,
                onShare = onShareSelected,
                onSave = onSaveSelected,
                onDelete = { confirmDeleteSelected = true },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                Text(stringResource(R.string.flights_title), style = MaterialTheme.typography.titleLarge)
            }
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
                    FlightRow(
                        entry,
                        selection = if (selecting) entry.id in selected else null,
                        onOpen = { if (selecting) onToggle(entry.id) else onOpen(entry.id) },
                        onLongPress = { onToggle(entry.id) },
                        onShare = { onShare(entry.id) },
                        onSave = { onSave(entry.id) },
                        onDelete = { confirmDelete = entry.id },
                    )
                }
            }
        }
    }
    confirmDelete?.let { id ->
        DeleteDialog(onConfirm = { onDelete(id); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
    if (confirmDeleteSelected && selecting) {
        DeleteDialog(
            count = selected.size,
            onConfirm = { confirmDeleteSelected = false; onDeleteSelected() },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
}

/** Top bar of the selection mode: count, select all, export (share or save), delete. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.flights_cancel)) }
        Text(
            pluralStringResource(R.plurals.flights_selected_count, count, count),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) {
            Text(stringResource(if (allSelected) R.string.flights_select_none else R.string.flights_select_all))
        }
        ExportMenu(label = stringResource(R.string.flights_export_many), saveLabel = stringResource(R.string.flights_save_zip), onShare = onShare, onSave = onSave)
        TextButton(onClick = onDelete) { Text(stringResource(R.string.gallery_delete)) }
    }
}

/** "Export" button and its menu: share sheet, or saved on the phone (system file picker). */
@Composable
private fun ExportMenu(label: String, saveLabel: String, onShare: () -> Unit, onSave: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }) { Text(label) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.flights_share)) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(text = { Text(saveLabel) }, onClick = { menu = false; onSave() })
        }
    }
}

/** Snackbar for each export result. */
@Composable
private fun ExportMessages(messages: Flow<ExportMessage>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(exportMessageText(resources, it)) }
    }
}

private fun exportMessageText(res: Resources, message: ExportMessage): String = when (message) {
    is ExportMessage.Saved -> listOfNotNull(
        res.getQuantityString(R.plurals.flights_saved, message.saved, message.saved),
        message.missing.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.flights_missing_skipped, it, it) },
    ).joinToString(". ")
    is ExportMessage.ShareMissing -> res.getQuantityString(R.plurals.flights_missing_skipped, message.missing, message.missing)
    ExportMessage.NothingToExport -> res.getString(R.string.flights_nothing_exported)
    ExportMessage.Failed -> res.getString(R.string.flights_save_failed)
}

/** Opens the system file picker ("save as"); without any document provider, nothing happens. */
private fun launchSave(launcher: ActivityResultLauncher<String>, fileName: String) {
    runCatching { launcher.launch(fileName) }
}

@Composable
private fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, count: Int = 1) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (count == 1) stringResource(R.string.flights_delete_title)
                else pluralStringResource(R.plurals.flights_delete_many_title, count, count),
            )
        },
        text = { Text(stringResource(if (count == 1) R.string.flights_delete_text else R.string.flights_delete_many_text)) },
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

/** [selection] `null` outside the selection mode, else whether this flight is selected. */
@Composable
private fun FlightRow(
    entry: FlightEntry,
    selection: Boolean?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val s = entry.summary
    val background = if (selection == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(background)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = if (selection != null) 0.dp else 12.dp, top = 6.dp, bottom = 6.dp, end = if (selection != null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) Checkbox(checked = selection, onCheckedChange = { onOpen() })
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
        if (selection == null) {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_open)) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.flights_export)) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.flights_save_device)) }, onClick = { menu = false; onSave() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.flights_select)) }, onClick = { menu = false; onLongPress() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_delete)) }, onClick = { menu = false; onDelete() })
                }
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
    val snackbar = remember { SnackbarHostState() }
    ExportMessages(viewModel.messages, snackbar)
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FlightExport.CSV_MIME)) { viewModel.saveCsv(id, it) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
                    Text(
                        log?.let { dateTime(context, it.startEpochMs) } ?: stringResource(R.string.flights_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (log != null) {
                        ExportMenu(
                            label = stringResource(R.string.flights_export),
                            saveLabel = stringResource(R.string.flights_save_device),
                            onShare = { share(context, viewModel.shareOne(id)) },
                            onSave = { launchSave(saveCsv, FlightExport.csvFileName(id)) },
                        )
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.gallery_delete)) }
                    }
                }
                when {
                    missing -> Text(stringResource(R.string.flights_missing), Modifier.padding(24.dp))
                    else -> log?.let { FlightDetail(it) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
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

/** Share sheet for one or several flight files ([FlightExport.sharePlan]); [files] are (id, content URI). */
private fun share(context: Context, files: List<Pair<String, Uri>>) {
    val plan = FlightExport.sharePlan(files.map { it.second }, files.map { FlightExport.csvFileName(it.first) }) ?: return
    val intent = Intent(plan.action).setType(plan.mimeType).addFlags(plan.flags)
    if (plan.action == Intent.ACTION_SEND_MULTIPLE) {
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(plan.streams))
    } else {
        intent.putExtra(Intent.EXTRA_STREAM, plan.streams.single())
    }
    plan.subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
    // The read grant covers every URI of the ClipData (EXTRA_STREAM alone is not enough for several).
    intent.clipData = ClipData.newRawUri(files.first().first, plan.streams.first()).apply {
        plan.streams.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun dateTime(context: Context, epochMs: Long) =
    DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

private fun duration(ms: Long) = DateUtils.formatElapsedTime(ms / 1000)

private fun meters(v: Float) = String.format(Locale.getDefault(), "%.1f m", v)

private fun speed(v: Float) = String.format(Locale.getDefault(), "%.1f m/s", v)

private fun axis(v: Float, unit: String) = String.format(Locale.getDefault(), "%.1f %s", v, unit)

private const val MAX_POINTS = 600
