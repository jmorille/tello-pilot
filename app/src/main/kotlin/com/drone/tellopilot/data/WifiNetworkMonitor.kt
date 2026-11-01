package com.drone.tellopilot.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiNetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    /**
     * The current Wi-Fi network (the Tello access point has no Internet, so the
     * INTERNET capability must not be required), or `null` when Wi-Fi is down.
     */
    val wifiNetwork: Flow<Network?> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                current = network
                trySend(network)
            }

            override fun onLost(network: Network) {
                // Ignore a late onLost of a previous network after a handover.
                if (network == current) {
                    current = null
                    trySend(null)
                }
            }

            override fun onUnavailable() {
                trySend(null)
            }
        }
        trySend(null)
        connectivity.requestNetwork(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Disables Wi-Fi power save while flying (active only while the app is in the foreground
     * with the screen on): removes the 100+ ms latency spikes of the power-save mode.
     */
    fun createLowLatencyLock(): WifiManager.WifiLock =
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tello-pilot").apply {
            setReferenceCounted(false)
        }
}
