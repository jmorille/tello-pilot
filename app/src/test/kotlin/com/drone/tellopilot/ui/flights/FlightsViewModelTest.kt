package com.drone.tellopilot.ui.flights

import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.drone.tello.control.Telemetry
import com.drone.tellopilot.data.DroneSession
import com.drone.tellopilot.flightlog.ExportResult
import com.drone.tellopilot.flightlog.FlightLogStore
import com.drone.tellopilot.flightlog.FlightSample
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Multi-selection and export bookkeeping of the flight list. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightsViewModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: FlightLogStore
    private lateinit var savedState: SavedStateHandle
    private lateinit var vm: FlightsViewModel
    private lateinit var ids: List<String>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val files = tmp.root
        store = spyk(FlightLogStore(object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
        }))
        // Newest first, as listed.
        ids = listOf(1_700_000_200_000, 1_700_000_100_000, 1_700_000_000_000).map(::recordFlight)
        val session = mockk<DroneSession> { every { telemetry } returns flowOf(Telemetry()) }
        savedState = SavedStateHandle()
        vm = FlightsViewModel(store, session, savedState)
        awaitEntries(3)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    private fun awaitEntries(count: Int) = runBlocking {
        withTimeout(5_000) { vm.entries.first { it?.size == count } }
    }

    private fun awaitSelected(expected: Set<String>) = runBlocking {
        withTimeout(5_000) { vm.selected.first { it == expected } }
    }

    @Test
    fun `a long press enters the selection mode, a tap toggles, the last one leaves it`() {
        assertTrue(vm.selected.value.isEmpty())
        vm.toggle(ids[1])
        assertEquals(setOf(ids[1]), vm.selected.value)
        vm.toggle(ids[0])
        assertEquals(setOf(ids[0], ids[1]), vm.selected.value)
        vm.toggle(ids[1])
        vm.toggle(ids[0])
        assertTrue(vm.selected.value.isEmpty())
    }

    @Test
    fun `select all, then again to select none`() {
        vm.toggle(ids[2])
        vm.selectAll()
        assertEquals(ids.toSet(), vm.selected.value)
        vm.selectAll()
        assertTrue(vm.selected.value.isEmpty())
    }

    @Test
    fun `back clears the selection`() {
        vm.selectAll()
        vm.clearSelection()
        assertTrue(vm.selected.value.isEmpty())
    }

    @Test
    fun `selected ids follow the list order, whatever the selection order`() {
        vm.toggle(ids[2])
        vm.toggle(ids[0])
        assertEquals(listOf(ids[0], ids[2]), vm.selectedIds())
    }

    @Test
    fun `a flight deleted elsewhere leaves the selection`() {
        vm.selectAll()
        runBlocking { store.delete(ids[1]) }
        awaitEntries(2)
        awaitSelected(setOf(ids[0], ids[2]))
    }

    @Test
    fun `deleting the selection deletes those flights only and leaves the selection mode`() {
        vm.toggle(ids[0])
        vm.toggle(ids[2])
        vm.deleteSelected()
        assertTrue(vm.selected.value.isEmpty())
        awaitEntries(1)
        assertEquals(listOf(ids[1]), vm.entries.value!!.map { it.id })
    }

    @Test
    fun `cancelling the file picker writes nothing and keeps the selection`() {
        vm.selectAll()
        val name = vm.prepareZip(nowMs = 1_700_000_000_000)
        assertTrue(name.matches(Regex("tello-flights-\\d{8}-\\d{6}\\.zip")))
        vm.onSaveTarget(null)
        coVerify(exactly = 0) { store.saveTo(any(), any(), any()) }
        assertEquals(ids.toSet(), vm.selected.value)
        // The pending save is consumed: a late second result is ignored.
        vm.onSaveTarget(mockk())
        coVerify(exactly = 0) { store.saveTo(any(), any(), any()) }
    }

    @Test
    fun `saving a zip writes the selection, reports missing flights and leaves the selection mode`() = runBlocking {
        val target = mockk<Uri>()
        coEvery { store.saveTo(target, any(), true) } returns ExportResult(listOf(ids[0]), listOf(ids[2]))
        vm.toggle(ids[2])
        vm.toggle(ids[0])
        vm.prepareZip()
        // Survives the activity (and the process) while the picker is open.
        assertEquals(listOf(ids[0], ids[2]), savedState.get<ArrayList<String>>("pending_save_ids"))
        vm.onSaveTarget(target)
        assertEquals(ExportMessage.Saved(saved = 1, missing = 1), withTimeout(5_000) { vm.messages.first() })
        coVerify { store.saveTo(target, listOf(ids[0], ids[2]), true) }
        assertTrue(vm.selected.value.isEmpty())
    }

    @Test
    fun `saving one flight writes its bare csv`() = runBlocking {
        val target = mockk<Uri>()
        coEvery { store.saveTo(target, any(), false) } returns ExportResult(emptyList(), listOf(ids[1]))
        assertEquals("${ids[1]}.csv", vm.prepareCsv(ids[1]))
        vm.onSaveTarget(target)
        assertEquals(ExportMessage.NothingToExport, withTimeout(5_000) { vm.messages.first() })
        coVerify { store.saveTo(target, listOf(ids[1]), false) }
    }

    @Test
    fun `sharing reports the selected flights that no longer exist`() = runBlocking {
        every { store.shareUris(any()) } answers { firstArg<List<String>>().drop(1).map { it to mockk<Uri>() } }
        vm.selectAll()
        assertEquals(listOf(ids[1], ids[2]), vm.shareSelected().map { it.first })
        assertEquals(ExportMessage.ShareMissing(1), withTimeout(5_000) { vm.messages.first() })
    }

    @Test
    fun `sharing one flight deleted in the meantime is reported`() = runBlocking {
        every { store.shareUri(any()) } returns null
        assertTrue(vm.shareOne(ids[0]).isEmpty())
        assertEquals(ExportMessage.ShareMissing(1), withTimeout(5_000) { vm.messages.first() })
    }
}
