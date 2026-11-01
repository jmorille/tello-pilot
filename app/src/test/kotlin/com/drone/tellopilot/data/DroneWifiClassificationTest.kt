package com.drone.tellopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class DroneWifiClassificationTest {

    private fun ip(text: String): InetAddress = InetAddress.getByName(text) // numeric literal: no DNS lookup
    private val drone = ip("192.168.10.1")

    @Test
    fun `no IPv4 address yet is not usable`() {
        assertNull(isDroneWifi(emptyList(), dhcpServer = drone, gateways = listOf(drone)))
        assertNull("IPv6 only", isDroneWifi(listOf(ip("fe80::1")), dhcpServer = drone, gateways = listOf(drone)))
    }

    @Test
    fun `drone subnet served by the drone is the drone`() {
        assertEquals(true, isDroneWifi(listOf(ip("192.168.10.2")), dhcpServer = drone, gateways = emptyList()))
        assertEquals(true, isDroneWifi(listOf(ip("192.168.10.7")), dhcpServer = null, gateways = listOf(null, drone)))
        assertEquals("IPv6 next to the IPv4", true, isDroneWifi(listOf(ip("fe80::1"), ip("192.168.10.3")), drone, emptyList()))
    }

    @Test
    fun `home router on the same subnet is not the drone`() {
        assertEquals(false, isDroneWifi(listOf(ip("192.168.10.20")), dhcpServer = ip("192.168.10.254"), gateways = listOf(ip("192.168.10.254"))))
        assertEquals("nothing known about the server", false, isDroneWifi(listOf(ip("192.168.10.20")), null, emptyList()))
    }

    @Test
    fun `another subnet is never the drone`() {
        assertEquals(false, isDroneWifi(listOf(ip("192.168.1.20")), dhcpServer = drone, gateways = listOf(drone)))
        assertEquals(false, isDroneWifi(listOf(ip("10.168.10.2")), drone, emptyList()))
        assertEquals(false, isDroneWifi(listOf(ip("192.169.10.2")), drone, emptyList()))
        assertEquals(false, isDroneWifi(listOf(ip("192.168.11.2")), drone, emptyList()))
    }
}
