package com.drone.tellopilot.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone's Wi-Fi is connected to, from the drone's point of view. */
sealed interface WifiState {
    data object Off : WifiState

    /** Wi-Fi up, but not on the drone's network (e.g. Android went back to the home Wi-Fi). */
    data object OtherNetwork : WifiState

    data class Drone(val network: Network) : WifiState
}

@Singleton
class WifiNetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The current Wi-Fi network, classified: the Tello access point gives addresses in
     * 192.168.10.0/24 and is itself 192.168.10.1 (it has no Internet, so INTERNET must not be
     * required).
     */
    private val systemWifi: Flow<WifiState> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                current = network
                trySend(classify(network, connectivity.getLinkProperties(network)))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == current) trySend(classify(network, linkProperties))
            }

            override fun onLost(network: Network) {
                // Ignore a late onLost of a previous network after a handover.
                if (network == current) {
                    current = null
                    trySend(WifiState.Off)
                }
            }

            override fun onUnavailable() {
                trySend(WifiState.Off)
            }
        }
        trySend(WifiState.Off)
        connectivity.requestNetwork(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Network obtained through [requestDroneNetwork] (system dialog), held while requested. */
    private val requested = MutableStateFlow<Network?>(null)
    private var droneRequest: ConnectivityManager.NetworkCallback? = null

    private val _requesting = MutableStateFlow(false)
    /** A drone network request is pending (dialog shown, drone searched) and not yet granted. */
    val requesting: StateFlow<Boolean> = _requesting.asStateFlow()

    /** The network to talk to the drone on: the requested one first, else the system Wi-Fi if it is the drone's. */
    val wifiState: Flow<WifiState> = combine(systemWifi, requested) { system, req ->
        if (req != null) WifiState.Drone(req) else system
    }.distinctUntilChanged()

    /**
     * Asks Android for the drone's Wi-Fi: a system dialog lists the drones in range, one tap
     * connects. While held, Android does not switch to another Wi-Fi and the app keeps the
     * drone (on dual Wi-Fi phones the home Wi-Fi even stays up). The drone is looked for by
     * the name / password set in the app if it was changed, else as an open `TELLO-…` network.
     * Android only accepts this request from an app in the foreground.
     */
    @Synchronized
    fun requestDroneNetwork() {
        // Already granted and working: nothing to do. Held but lost (drone switched off and
        // back on): Android does not always reconnect by itself, so ask again.
        if (droneRequest != null) {
            if (requested.value != null) return
            unregister()
        }
        val ssid = prefs.getString(KEY_SSID, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        val specifier = WifiNetworkSpecifier.Builder().apply {
            if (ssid != null) setSsid(ssid) else setSsidPattern(PatternMatcher(DRONE_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            if (ssid != null && !password.isNullOrEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(this@WifiNetworkMonitor) {
                if (droneRequest !== this) return
                Log.i(TAG, "drone network granted: $network")
                requested.value = network
                _requesting.value = false
            }

            override fun onLost(network: Network) = synchronized(this@WifiNetworkMonitor) {
                if (droneRequest !== this) return
                // The request stays registered: Android may reconnect when the drone is back.
                if (requested.value == network) requested.value = null
            }

            override fun onUnavailable() = synchronized(this@WifiNetworkMonitor) {
                if (droneRequest !== this) return
                // Dialog cancelled, no drone found or app in the background: drop the request.
                Log.i(TAG, "drone network request unavailable")
                unregister()
            }
        }
        droneRequest = callback
        _requesting.value = true
        try {
            connectivity.requestNetwork(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "cannot request the drone network", e)
            droneRequest = null
            _requesting.value = false
        }
    }

    @Synchronized
    fun releaseDroneNetwork() = unregister()

    private fun unregister() {
        val callback = droneRequest ?: return
        droneRequest = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        requested.value = null
        _requesting.value = false
    }

    /** The drone's Wi-Fi was renamed / protected from the app: look for it under that name next time. */
    fun rememberDroneNetwork(ssid: String, password: String) {
        prefs.edit().putString(KEY_SSID, ssid).putString(KEY_PASSWORD, password).apply()
    }

    private fun classify(network: Network, properties: LinkProperties?): WifiState {
        val addresses = properties?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>()
        // No IPv4 yet (DHCP in progress): not usable yet, onLinkPropertiesChanged will follow.
        if (addresses.isEmpty()) return WifiState.Off
        val inSubnet = addresses.any { isDroneSubnet(it) }
        // A home router on 192.168.10.x must not pass for the drone: the drone is the gateway / DHCP server .1.
        val droneIsServer = properties?.dhcpServerAddress?.hostAddress == DRONE_IP ||
            properties?.routes.orEmpty().any { it.gateway?.hostAddress == DRONE_IP }
        return if (inSubnet && droneIsServer) WifiState.Drone(network) else WifiState.OtherNetwork
    }

    private fun isDroneSubnet(address: Inet4Address): Boolean {
        val b = address.address
        return (b[0].toInt() and 0xFF) == 192 && (b[1].toInt() and 0xFF) == 168 && (b[2].toInt() and 0xFF) == 10
    }

    /**
     * Disables Wi-Fi power save while flying (active only while the app is in the foreground
     * with the screen on): removes the 100+ ms latency spikes of the power-save mode.
     */
    fun createLowLatencyLock(): WifiManager.WifiLock =
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tello-pilot").apply {
            setReferenceCounted(false)
        }

    private companion object {
        const val TAG = "WifiNetworkMonitor"
        const val DRONE_SSID_PREFIX = "TELLO-"
        const val DRONE_IP = "192.168.10.1"
        const val PREFS = "drone_wifi"
        const val KEY_SSID = "ssid"
        const val KEY_PASSWORD = "password"
    }
}
