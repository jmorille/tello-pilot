package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Phase3ProtocolTest {

    private fun msg(id: Int, vararg payload: Byte) = DroneMessage.from(Packet(id, 0x48, 0, payload))

    @Test
    fun `command ids and packet types match the official app`() {
        assertEquals(128 to 0x68, Command.SMART_VIDEO.id to Command.SMART_VIDEO.packetType)
        assertEquals(129 to 0x50, Command.ACK_SMART_VIDEO_STATUS.id to Command.ACK_SMART_VIDEO_STATUS.packetType)
        assertEquals(4179 to 0x68, Command.BOUNCE.id to Command.BOUNCE.packetType)
        assertEquals(4180 to 0x68, Command.CALIBRATE_CENTER_OF_GRAVITY.id to Command.CALIBRATE_CENTER_OF_GRAVITY.packetType)
        assertEquals(90 to 0x48, Command.START_IMU_CALIBRATION.id to Command.START_IMU_CALIBRATION.packetType)
        assertEquals(91 to 0x48, Command.QUERY_IMU_CALIBRATION.id to Command.QUERY_IMU_CALIBRATION.packetType)
        assertEquals(17 to 0x48, Command.QUERY_SSID.id to Command.QUERY_SSID.packetType)
        assertEquals(18 to 0x68, Command.SET_SSID.id to Command.SET_SSID.packetType)
        assertEquals(20 to 0x68, Command.SET_WIFI_PASSWORD.id to Command.SET_WIFI_PASSWORD.packetType)
        assertEquals(25 to 0x48, Command.RESTART_WIFI.id to Command.RESTART_WIFI.packetType)
    }

    @Test
    fun `smart video and bounce payloads`() {
        assertArrayEquals(byteArrayOf(0b0101), Payloads.smartVideo(true, SmartVideoMode.ROTATE_360))
        assertArrayEquals(byteArrayOf(0b1000), Payloads.smartVideo(false, SmartVideoMode.CIRCLE))
        assertArrayEquals(byteArrayOf(0b1101), Payloads.smartVideo(true, SmartVideoMode.UP_AND_AWAY))
        assertArrayEquals(byteArrayOf(0x30), Payloads.bounce(true))
        assertArrayEquals(byteArrayOf(0x31), Payloads.bounce(false))
    }

    @Test
    fun `Wi-Fi credentials are validated`() {
        assertArrayEquals("TELLO-1".toByteArray(), Payloads.ssid("TELLO-1"))
        assertThrows<IllegalArgumentException> { Payloads.ssid("") }
        assertThrows<IllegalArgumentException> { Payloads.ssid("x".repeat(33)) }
        assertThrows<IllegalArgumentException> { Payloads.ssid("café") }
        assertEquals(0, Payloads.wifiPassword("").size)
        assertThrows<IllegalArgumentException> { Payloads.wifiPassword("1234567") }
        assertEquals(63, Payloads.wifiPassword("p".repeat(63)).size)
        assertThrows<IllegalArgumentException> { Payloads.wifiPassword("p".repeat(64)) }
    }

    @Test
    fun `smart video status bits`() {
        val s = (msg(129, ((2) or (1 shl 3) or (3 shl 5)).toByte()) as DroneMessage.SmartVideo).status
        assertEquals(2, s.exitReason)
        assertTrue(s.running)
        assertEquals(SmartVideoMode.UP_AND_AWAY, s.mode)
        assertFalse((msg(129, 0) as DroneMessage.SmartVideo).status.running)
    }

    @Test
    fun `IMU calibration status`() {
        val s = (msg(91, 0, 0b100001, 1, 50, 0) as DroneMessage.ImuCalibration).status
        assertEquals(setOf(ImuFace.FRONT, ImuFace.TOP), s.doneFaces)
        assertEquals(50, s.progress)
        assertFalse(s.complete)
        assertTrue((msg(91, 0, 0b111111, 0, 100, 0) as DroneMessage.ImuCalibration).status.complete)
        assertInstanceOf(DroneMessage.Other::class.java, msg(91, 1, 0, 0, 0, 0), "error status ignored")
        assertInstanceOf(DroneMessage.Other::class.java, msg(91, 0, 1), "too short")
    }

    @Test
    fun `SSID and command results`() {
        assertEquals(DroneMessage.Ssid("TELLO-AB"), msg(17, 0, *"TELLO-AB".toByteArray()))
        assertEquals(DroneMessage.Ssid(null), msg(17, 1))
        assertEquals(DroneMessage.CommandResult(4179, true), msg(4179, 0))
        assertEquals(DroneMessage.CommandResult(18, false), msg(18, 1))
        assertNull(SmartVideoMode.fromCode(0))
    }
}
