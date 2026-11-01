package com.drone.tellopilot.data

import android.net.Network
import com.drone.tello.control.ControlTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP sockets towards the drone, bound to the Tello Wi-Fi [network] so that traffic never
 * goes through mobile data even though the Tello access point has no Internet.
 */
class DroneSockets private constructor(
    private val control: DatagramSocket,
    val video: DatagramSocket,
) : Closeable, ControlTransport {

    private val drone = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 10, 1)), 8889)

    override val videoPort: Int get() = video.localPort

    @Volatile private var sent = 0L
    @Volatile private var lastErrorLog = 0L
    @Volatile private var received = 0L
    @Volatile private var lastReceiveErrorLog = 0L

    /** Packet dumps: `adb shell setprop log.tag.DroneSockets DEBUG` then reconnect. */
    private val verbose = android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)
    private var lastSender: java.net.SocketAddress? = null

    /** Diagnostics: datagrams sent / received on the control socket. */
    val stats: String get() = "control ${control.localPort} sent=$sent received=$received, video ${video.localPort}"

    /** Called from the control thread: a UDP send is a non-blocking syscall of a few µs. */
    override suspend fun send(datagram: ByteArray) {
        try {
            control.send(DatagramPacket(datagram, datagram.size, drone))
            sent++
            if (sent <= DUMP_COUNT && verbose) android.util.Log.d(TAG, "TX#$sent ${datagram.dump()}")
        } catch (e: IOException) {
            // Network going away, or blocked by the system (EPERM when the screen locks /
            // background restrictions): the controller's watchdog handles the link loss.
            // Logged at most every 5 s so a persistent failure is visible in logcat.
            val now = System.nanoTime()
            if (now - lastErrorLog > 5_000_000_000L) {
                lastErrorLog = now
                android.util.Log.w(TAG, "send failed (${sent} sent so far)", e)
            }
        }
    }

    override val incoming: Flow<ByteArray> = flow {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (currentCoroutineContext().isActive && !control.isClosed) {
            try {
                packet.setLength(buffer.size)
                control.receive(packet)
                received++
                if (verbose && (received <= DUMP_COUNT || packet.socketAddress != lastSender)) {
                    lastSender = packet.socketAddress
                    android.util.Log.d(TAG, "RX#$received from ${packet.socketAddress} ${buffer.copyOf(packet.length).dump()}")
                }
                emit(buffer.copyOf(packet.length))
            } catch (_: SocketTimeoutException) {
                // loop to check for cancellation
            } catch (e: IOException) {
                if (control.isClosed) break
                val now = System.nanoTime()
                if (now - lastReceiveErrorLog > 5_000_000_000L) {
                    lastReceiveErrorLog = now
                    android.util.Log.w(TAG, "receive failed", e)
                }
                delay(RECEIVE_ERROR_BACKOFF_MS) // transient (EPERM, ENETUNREACH…): keep listening
            }
        }
    }.flowOn(RealtimeThreads.controlReceive)

    override fun close() {
        control.close()
        video.close()
    }

    companion object {
        private const val TAG = "DroneSockets"
        /** Diagnostics: first datagrams dumped in hex. */
        private const val DUMP_COUNT = 30L

        private fun ByteArray.dump(): String =
            "(${size}B) " + take(48).joinToString(" ") { "%02x".format(it) } + if (size > 48) " …" else ""
        private const val CONTROL_PORT = 6525
        private const val VIDEO_PORT = 7777
        const val RECEIVE_ERROR_BACKOFF_MS = 50L
        private const val DSCP_EF = 0xB8 // Expedited Forwarding: lowest-latency Wi-Fi access category

        fun open(network: Network): DroneSockets {
            val control = bind(CONTROL_PORT).apply {
                trafficClass = DSCP_EF
                soTimeout = 250
                sendBufferSize = 16 * 1024
            }
            val video = try {
                bind(VIDEO_PORT).apply {
                    soTimeout = 250
                    receiveBufferSize = 2 * 1024 * 1024 // absorb bursts of I-frame fragments
                }
            } catch (e: Exception) {
                control.close(); throw e
            }
            // Diagnostics only: never allowed to fail the opening (and leak the bound sockets).
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                val interfaces = runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.joinToString { i ->
                        i.name + i.interfaceAddresses.joinToString(prefix = "[", postfix = "]") { it.address.hostAddress ?: "?" }
                    }
                }.getOrDefault("?")
                android.util.Log.d(TAG, "bound control=${control.localPort} video=${video.localPort} on $network; interfaces: $interfaces")
            }
            try {
                network.bindSocket(control)
                network.bindSocket(video)
            } catch (e: Exception) {
                control.close(); video.close(); throw e
            }
            return DroneSockets(control, video)
        }

        private fun bind(firstPort: Int): DatagramSocket {
            for (port in firstPort until firstPort + 200) {
                val socket = DatagramSocket(null).apply { reuseAddress = true }
                try {
                    socket.bind(InetSocketAddress(port))
                    return socket
                } catch (_: BindException) {
                    socket.close()
                }
            }
            throw BindException("No free UDP port from $firstPort")
        }
    }
}
