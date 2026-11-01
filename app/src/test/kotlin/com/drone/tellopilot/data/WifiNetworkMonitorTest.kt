package com.drone.tellopilot.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.RouteInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Drone network request (system dialog) and the classification of the phone's Wi-Fi. */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiNetworkMonitorTest {

    /** In-memory preferences (the drone's Wi-Fi name / password). */
    private class Prefs : SharedPreferences {
        val values = HashMap<String, Any?>()
        override fun getAll() = values
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = ArrayList<() -> Unit>()
            override fun putString(key: String?, value: String?) = apply { changes += { values[key!!] = value } }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { changes += { values.remove(key) } }
            override fun clear() = apply { changes += { values.clear() } }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() = changes.forEach { it() }
        }
    }

    private val prefs = Prefs()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val unregistered = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var requestFails = false
    private val connectivity: ConnectivityManager = mockk(relaxed = true) {
        every { requestNetwork(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } answers {
            if (requestFails) throw SecurityException("background")
            callbacks += secondArg<ConnectivityManager.NetworkCallback>()
        }
        every { unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } answers { unregistered += firstArg<ConnectivityManager.NetworkCallback>() }
    }
    private val wifiManager: WifiManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true) {
        every { getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { getSystemService(WifiManager::class.java) } returns wifiManager
        every { getSharedPreferences(any(), any()) } returns prefs
    }
    private var now = 1_000_000L
    private val ssids = mutableListOf<String>()
    private var patterns = 0

    @Before
    fun setUp() {
        mockkConstructor(NetworkRequest.Builder::class, WifiNetworkSpecifier.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addTransportType(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().removeCapability(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().setNetworkSpecifier(any<android.net.NetworkSpecifier>()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk()
        every { anyConstructed<WifiNetworkSpecifier.Builder>().setSsid(any()) } answers {
            ssids += firstArg<String>(); self as WifiNetworkSpecifier.Builder
        }
        every { anyConstructed<WifiNetworkSpecifier.Builder>().setSsidPattern(any()) } answers {
            patterns++; self as WifiNetworkSpecifier.Builder
        }
        every { anyConstructed<WifiNetworkSpecifier.Builder>().build() } returns mockk()
    }

    @After fun tearDown() = unmockkAll()

    private val monitor by lazy { WifiNetworkMonitor(context) { now } }
    private val droneNet: Network = mockk(relaxed = true)

    // --------------------------------------------------- drone network request

    @Test
    fun `a request looks for any TELLO- network and stays pending until Android answers`() {
        monitor.requestDroneNetwork()
        assertTrue(monitor.requesting.value)
        assertEquals(1, callbacks.size)
        assertEquals(1, patterns)
        assertTrue(ssids.isEmpty())
    }

    @Test
    fun `granted - the drone network is used and a new request is not needed`() = runTest {
        val states = mutableListOf<WifiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.wifiState.toList(states) }
        monitor.requestDroneNetwork()
        val request = callbacks.last()
        request.onAvailable(droneNet)
        assertFalse(monitor.requesting.value)
        assertEquals(WifiState.Drone(droneNet), states.last())
        monitor.requestDroneNetwork()
        assertEquals(2, callbacks.size) // system Wi-Fi callback + one drone request only
        // Lost: back to the system Wi-Fi state, the request stays registered (Android may reconnect).
        request.onLost(droneNet)
        assertEquals(WifiState.Off, states.last())
        assertTrue(unregistered.isEmpty())
        // Asked again after the loss: the stale registration is replaced.
        monitor.requestDroneNetwork()
        assertEquals(listOf(request), unregistered)
        assertEquals(3, callbacks.size)
    }

    @Test
    fun `cancelled dialog or no drone - the request is dropped, the connect button stays`() {
        monitor.requestDroneNetwork()
        callbacks.last().onUnavailable()
        assertFalse(monitor.requesting.value)
        assertEquals(1, unregistered.size)
    }

    @Test
    fun `a late answer to a replaced request is ignored`() {
        monitor.requestDroneNetwork()
        val old = callbacks.last()
        monitor.requestDroneNetwork()
        old.onAvailable(droneNet)
        assertTrue(monitor.requesting.value)
        old.onUnavailable()
        assertTrue(monitor.requesting.value)
    }

    @Test
    fun `a request pending for more than 30 s is stale`() {
        assertFalse(monitor.requestStale())
        monitor.requestDroneNetwork()
        now += 30_000
        assertFalse(monitor.requestStale())
        now += 1
        assertTrue(monitor.requestStale())
        callbacks.last().onAvailable(droneNet)
        assertFalse(monitor.requestStale())
    }

    @Test
    fun `a request refused by the system (background) leaves nothing pending`() {
        requestFails = true
        monitor.requestDroneNetwork()
        assertFalse(monitor.requesting.value)
        requestFails = false
        monitor.requestDroneNetwork()
        assertTrue(monitor.requesting.value)
    }

    @Test
    fun `release drops the request`() {
        monitor.requestDroneNetwork()
        monitor.releaseDroneNetwork()
        assertFalse(monitor.requesting.value)
        assertEquals(1, unregistered.size)
        monitor.releaseDroneNetwork()
        assertEquals(1, unregistered.size)
    }

    @Test
    fun `a renamed open drone network is looked for by name`() {
        monitor.rememberDroneNetwork("TELLO-PILOT", "")
        monitor.requestDroneNetwork()
        assertEquals(listOf("TELLO-PILOT"), ssids)
        assertEquals(0, patterns)
    }

    @Test
    fun `a password that cannot be protected is not stored - any TELLO- network again`() {
        monitor.rememberDroneNetwork("TELLO-PILOT", "")
        monitor.rememberDroneNetwork("TELLO-SECRET", "password1") // no Android keystore on the JVM
        assertTrue(prefs.values.isEmpty())
        monitor.requestDroneNetwork()
        assertEquals(1, patterns)
    }

    @Test
    fun `a sealed password that can no longer be read falls back to the TELLO- pattern`() {
        prefs.values["ssid"] = "TELLO-SECRET"
        prefs.values["password_sealed"] = "garbage"
        monitor.requestDroneNetwork()
        assertTrue(ssids.isEmpty())
        assertEquals(1, patterns)
    }

    // ------------------------------------------------------------ system Wi-Fi

    private fun props(address: String, gateway: String?): LinkProperties = mockk {
        every { linkAddresses } returns listOf(mockk<LinkAddress> { every { this@mockk.address } returns InetAddress.getByName(address) })
        every { dhcpServerAddress } returns null
        every { routes } returns listOfNotNull(gateway?.let { g -> mockk<RouteInfo> { every { this@mockk.gateway } returns InetAddress.getByName(g) } })
    }

    @Test
    fun `system Wi-Fi - the drone, another network, a late loss of the previous network`() = runTest {
        val home: Network = mockk(relaxed = true)
        every { connectivity.getLinkProperties(droneNet) } returns props("192.168.10.2", "192.168.10.1")
        every { connectivity.getLinkProperties(home) } returns props("192.168.1.20", "192.168.1.1")
        val states = mutableListOf<WifiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.wifiState.toList(states) }
        val system = callbacks.single()
        system.onAvailable(home)
        assertEquals(WifiState.OtherNetwork, states.last())
        system.onAvailable(droneNet)
        assertEquals(WifiState.Drone(droneNet), states.last())
        system.onLost(home) // handover: late loss of the previous network
        assertEquals(WifiState.Drone(droneNet), states.last())
        system.onLost(droneNet)
        assertEquals(WifiState.Off, states.last())
        assertEquals(listOf(WifiState.Off, WifiState.OtherNetwork, WifiState.Drone(droneNet), WifiState.Off), states)
    }

    @Test
    fun `system Wi-Fi - addresses arriving later are classified, stopping unregisters`() = runTest {
        val noAddress: LinkProperties = mockk {
            every { linkAddresses } returns emptyList()
            every { dhcpServerAddress } returns null
            every { routes } returns emptyList()
        }
        every { connectivity.getLinkProperties(droneNet) } returns noAddress
        val states = mutableListOf<WifiState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.wifiState.toList(states) }
        val system = callbacks.single()
        system.onAvailable(droneNet)
        assertEquals(WifiState.Off, states.last()) // DHCP in progress
        system.onLinkPropertiesChanged(droneNet, props("192.168.10.3", "192.168.10.1"))
        assertEquals(WifiState.Drone(droneNet), states.last())
        job.cancel()
        verify { connectivity.unregisterNetworkCallback(system) }
    }

    @Test
    fun `the low latency lock is not reference counted`() {
        val lock: WifiManager.WifiLock = mockk(relaxed = true)
        every { wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, any()) } returns lock
        monitor.createLowLatencyLock()
        verify { lock.setReferenceCounted(false) }
    }
}
