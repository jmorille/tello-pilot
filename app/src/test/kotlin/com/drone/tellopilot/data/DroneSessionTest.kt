package com.drone.tellopilot.data

import android.net.Network
import android.net.wifi.WifiManager
import com.drone.tello.control.LinkState
import com.drone.tello.control.TelloController
import com.drone.tellopilot.flightlog.FlightRecorder
import com.drone.tellopilot.video.VideoRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Connection state machine of the drone session: Wi-Fi changes, socket (re)opening, the
 * "drone Wi-Fi lost" request (once per loss, never a dialog loop, retried in the foreground)
 * and a session on a real (loopback) UDP link.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DroneSessionTest {

    private inner class Rig(val scope: TestScope, val open: ((Network) -> DroneSockets)? = null) {
        var opens = 0
        val wifiState = MutableStateFlow<WifiState>(WifiState.Off)
        val requesting = MutableStateFlow(false)
        var stale = false
        var lockHeld = false
        val lock: WifiManager.WifiLock = mockk(relaxed = true) {
            every { acquire() } answers { lockHeld = true }
            every { release() } answers { lockHeld = false }
            every { isHeld } answers { lockHeld }
        }
        val wifi: WifiNetworkMonitor = mockk(relaxed = true) {
            every { wifiState } returns this@Rig.wifiState
            every { requesting } returns this@Rig.requesting
            every { requestStale() } answers { stale }
            every { createLowLatencyLock() } returns lock
        }
        private val flightRecorder: FlightRecorder = mockk { coEvery { record(any()) } coAnswers { awaitCancellation() } }
        private val settings: SettingsRepository = mockk { every { settings } returns MutableStateFlow(AppSettings()) }
        private val recorder: VideoRecorder = mockk(relaxed = true)
        val session = DroneSession(
            wifi, settings, recorder, mockk(relaxed = true), mockk(relaxed = true), flightRecorder,
            mockk<BatteryVoiceAlert>(relaxed = true),
            StandardTestDispatcher(scope.testScheduler),
        ) { opens++; open?.invoke(it) ?: throw IOException("bind failed") }
        lateinit var job: Job

        fun start() {
            job = scope.backgroundScope.launch { session.run() }
            scope.runCurrent()
        }

        fun drone() = WifiState.Drone(network)

        fun wifi(state: WifiState) {
            wifiState.value = state
            scope.runCurrent()
        }

    }

    private val network: Network = mockk(relaxed = true)

    private fun test(block: suspend Rig.() -> Unit) = runTest { Rig(this).apply { start(); block() } }

    private fun Rig.requestCount(n: Int) = verify(exactly = n) { wifi.requestDroneNetwork() }

    // ------------------------------------------------------ connection states

    @Test
    fun `follows the Wi-Fi - off, another network, the drone`() = test {
        assertEquals(WifiState.Off, session.wifiState.value)
        assertTrue(lockHeld) // low-latency Wi-Fi while the session runs
        wifi(WifiState.OtherNetwork)
        assertEquals(WifiState.OtherNetwork, session.wifiState.value)
        assertEquals(0, opens)
        wifi(drone())
        assertEquals(drone(), session.wifiState.value)
        assertEquals(1, opens)
        requestCount(0)
    }

    @Test
    fun `sockets that cannot open are retried every second, without a controller`() = test {
        wifi(drone())
        assertEquals(1, opens)
        scope.advanceTimeBy(1_001)
        assertEquals(2, opens)
        scope.advanceTimeBy(1_000)
        assertEquals(3, opens)
        assertNull(session.controller.value)
        assertEquals(LinkState.IDLE, session.link.first())
    }

    @Test
    fun `reconnect reopens the sockets`() = test {
        wifi(drone())
        session.reconnect()
        scope.runCurrent()
        assertEquals(2, opens)
    }

    @Test
    fun `stopping the session releases the Wi-Fi lock and the drone network request`() = test {
        wifi(drone())
        job.cancel()
        scope.runCurrent()
        assertTrue(!lockHeld)
        verify { wifi.releaseDroneNetwork() }
        assertEquals(WifiState.Off, session.wifiState.value)
    }

    // ------------------------------------------- drone Wi-Fi lost: the request

    @Test
    fun `losing the drone Wi-Fi asks for it back once per loss`() = test {
        session.onForeground()
        wifi(drone())
        wifi(WifiState.OtherNetwork) // Android went back home
        requestCount(1)
        wifi(WifiState.Off)
        requestCount(1)
        // Found again, then lost again: a new request.
        wifi(drone())
        wifi(WifiState.Off)
        requestCount(2)
    }

    @Test
    fun `lost in the foreground - coming back from the system dialog does not open a second one`() = test {
        session.onForeground()
        wifi(drone())
        wifi(WifiState.Off)
        requestCount(1)
        // The dialog pauses and resumes the activity; the pilot may cancel it.
        session.onBackground()
        session.onForeground()
        session.onBackground()
        session.onForeground()
        requestCount(1)
    }

    @Test
    fun `lost in the background - asked again once when back in the foreground`() = test {
        session.onForeground()
        wifi(drone())
        session.onBackground()
        wifi(WifiState.Off)
        requestCount(1) // refused by Android: the app is in the background
        session.onForeground()
        requestCount(2)
        session.onBackground()
        session.onForeground()
        requestCount(2) // never a dialog loop
    }

    @Test
    fun `no foreground request while one is pending, unless it is stale`() = test {
        wifi(drone())
        wifi(WifiState.Off) // in the background (never in the foreground yet)
        requestCount(1)
        requesting.value = true
        session.onForeground()
        requestCount(1)
        // Still pending 30 s later: considered stuck, replaced.
        session.onBackground()
        stale = true
        session.onForeground()
        requestCount(2)
    }

    @Test
    fun `no request without a loss, or once the drone network is back`() = test {
        session.onForeground()
        requestCount(0)
        wifi(drone())
        session.onBackground()
        wifi(WifiState.Off)
        requestCount(1)
        wifi(drone())
        session.onForeground()
        requestCount(1)
    }

    @Test
    fun `connect button asks for the drone network`() = test {
        session.connectToDrone()
        requestCount(1)
        session.rememberDroneWifi("TELLO-ABC", "secret")
        verify { wifi.rememberDroneNetwork("TELLO-ABC", "secret") }
    }

    // ---------------------------------------------------- real loopback link

    /** A drone on the loopback interface: answers the handshake while [answering]. */
    private class LoopbackDrone : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 100 }
        @Volatile var answering = true
        @Volatile var received = 0
        private val thread = Thread {
            val buf = ByteArray(2048)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                received++
                if (answering && String(buf, 0, p.length, Charsets.US_ASCII).startsWith("conn_req:")) {
                    val ack = "conn_ack:".toByteArray() + byteArrayOf(0x61, 0x1E)
                    runCatching { socket.send(DatagramPacket(ack, ack.size, p.socketAddress)) }
                }
            }
        }.apply { isDaemon = true; start() }

        fun sockets(): DroneSockets {
            fun local() = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 250 }
            return DroneSockets(local(), local(), InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort))
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    /** Real sockets and threads: polls (virtual time advancing slowly) until [check] holds. */
    private fun TestScope.eventually(what: String, check: () -> Boolean) {
        repeat(400) {
            advanceTimeBy(20)
            runCurrent()
            if (check()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for $what")
    }

    @Test
    fun `on the drone network - connected over UDP, the drone silent - reconnecting, Wi-Fi lost - closed`() {
        LoopbackDrone().use { fake ->
            var sockets: DroneSockets? = null
            runTest {
                val rig = Rig(this) { fake.sockets().also { sockets = it } }
                rig.start()
                rig.session.onForeground()
                rig.wifi(rig.drone())
                eventually("the controller") { rig.session.controller.value != null }
                val controller: TelloController = rig.session.controller.value!!
                eventually("the handshake") { controller.link.value == LinkState.CONNECTED }
                assertTrue(fake.received > 0)
                // The drone stops answering: the controller reconnects on the same sockets.
                fake.answering = false
                eventually("the link loss") { controller.link.value == LinkState.RECONNECTING }
                assertSame(controller, rig.session.controller.value)
                rig.requestCount(0)
                // The Wi-Fi goes: controller dropped, sockets closed, the network asked back.
                rig.wifi(WifiState.Off)
                eventually("the session end") { rig.session.controller.value == null }
                assertNotNull(sockets)
                assertTrue(sockets!!.video.isClosed)
                assertEquals(false, rig.session.videoActive.value)
                rig.requestCount(1)
                rig.job.cancel()
            }
        }
    }
}
