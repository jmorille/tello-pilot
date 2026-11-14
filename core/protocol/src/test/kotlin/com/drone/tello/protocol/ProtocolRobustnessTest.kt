package com.drone.tello.protocol

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Incoming data from the drone: parsing in order, and no exception on truncated or corrupted input. */
class ProtocolRobustnessTest {

    private val random = Random(2026)

    /** All the message ids the app knows, plus unknown ones. */
    private val ids = listOf(
        MessageId.WIFI, MessageId.TAKE_PICTURE, MessageId.LIGHT, MessageId.FIRMWARE, MessageId.TIME_REQUEST,
        MessageId.ACTIVATION_STATE, MessageId.ACTIVATION_TIMESTAMP, MessageId.FLIGHT_DATA, MessageId.FILE_SIZE,
        MessageId.FILE_DATA, MessageId.SSID, MessageId.SET_SSID, MessageId.SET_WIFI_PASSWORD,
        MessageId.IMU_CALIBRATION_START, MessageId.IMU_CALIBRATION_STATUS, MessageId.FLIP, MessageId.SMART_VIDEO,
        MessageId.SMART_VIDEO_STATUS, MessageId.BOUNCE, MessageId.CENTER_OF_GRAVITY_CALIBRATION,
        MessageId.LOG_HEADER, MessageId.LOG_DATA, 0, 0xFFFF,
    )

    private fun packet(id: Int, payload: ByteArray, sequence: Int = 0) = Packet(id, 0x48, sequence, payload)

    @Test
    fun `random bytes never decode and never throw`() {
        repeat(20_000) {
            val data = random.nextBytes(random.nextInt(0, 64))
            if (data.isNotEmpty() && random.nextBoolean()) data[0] = 0xCC.toByte()
            val decoded = PacketCodec.decode(data)
            // A random buffer passing both CRCs and the size check is astronomically unlikely.
            assertNull(decoded)
        }
    }

    @Test
    fun `every truncation of a valid packet is rejected`() {
        val good = PacketCodec.encode(Command.SET_MAX_ALTITUDE, 42, Payloads.maxAltitude(10))
        for (n in 0 until good.size) {
            assertNull(PacketCodec.decode(good.copyOf(n)), "first $n bytes")
            assertNull(PacketCodec.decode(good, n), "length $n")
        }
        // Trailing garbage after a valid packet: the size field no longer matches.
        assertNull(PacketCodec.decode(good + byteArrayOf(0)))
        // A shorter length on a bigger receive buffer is how the socket hands packets over.
        assertEquals(42, PacketCodec.decode(good + ByteArray(100), good.size)!!.sequence)
    }

    @Test
    fun `every known message survives any payload length`() {
        for (id in ids) for (size in 0..64) {
            repeat(5) {
                assertDoesNotThrow({ DroneMessage.from(packet(id, random.nextBytes(size), random.nextInt(0, 0x10000))) }, "id $id size $size")
            }
        }
    }

    @Test
    fun `truncated payloads fall back to Other instead of half-parsed values`() {
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.FLIGHT_DATA, ByteArray(FlightData.MIN_SIZE - 1))))
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.WIFI, byteArrayOf(80))))
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.LIGHT, ByteArray(0))))
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.LOG_HEADER, byteArrayOf(1))))
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.FIRMWARE, byteArrayOf(0))))
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.IMU_CALIBRATION_STATUS, byteArrayOf(0, 1))))
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.BOUNCE, ByteArray(0))))
        // 4177 is never interpreted here: the controller feeds it to the log parser.
        assertInstanceOf(DroneMessage.Other::class.java, DroneMessage.from(packet(MessageId.LOG_DATA, byteArrayOf(0, 1, 2))))
    }

    @Test
    fun `flight data 86, wifi 26, light 53 decoded in arrival order`() {
        val flight = ByteArray(24).also {
            it[0] = 23 // 2.3 m
            it[12] = 67 // battery
            it[17] = 0b0110_0101 // flying, motors on, battery low + critical
            it[18] = FlightData.FLY_MODE_LANDING.toByte()
        }
        val stream = listOf(
            PacketCodec.encode(Command.QUERY_FIRMWARE, 0, flight).also { setId(it, MessageId.FLIGHT_DATA) },
            PacketCodec.encode(Command.QUERY_FIRMWARE, 0, byteArrayOf(55, 3)).also { setId(it, MessageId.WIFI) },
            PacketCodec.encode(Command.QUERY_FIRMWARE, 0, byteArrayOf(1)).also { setId(it, MessageId.LIGHT) },
            PacketCodec.encode(Command.QUERY_FIRMWARE, 0, byteArrayOf(0, 0x50, 0x10)).also { setId(it, MessageId.LOG_HEADER) },
        )
        val messages = stream.map { DroneMessage.from(PacketCodec.decode(it)!!) }
        val f = (messages[0] as DroneMessage.Flight).data
        assertEquals(2.3f, f.heightMeters, 1e-6f)
        assertEquals(67, f.batteryPercent)
        assertTrue(f.flying && f.motorsOn && f.batteryLow && f.batteryCritical && f.isLanding)
        assertEquals(DroneMessage.Wifi(WifiStatus(55, 3)), messages[1])
        assertEquals(DroneMessage.Light(tooDark = true), messages[2])
        assertEquals(DroneMessage.LogHeader(0x5000), messages[3])
    }

    /** Rewrites the id of an encoded packet and fixes its CRC-16 (as the drone would send it). */
    private fun setId(raw: ByteArray, id: Int) {
        raw[5] = id.toByte(); raw[6] = (id ushr 8).toByte()
        val crc = Crc.crc16(raw, 0, raw.size - 2)
        raw[raw.size - 2] = crc.toByte(); raw[raw.size - 1] = (crc ushr 8).toByte()
    }

    @Test
    fun `flight data of every length parses or is refused, without exception`() {
        for (size in 0..40) {
            val parsed = FlightData.parse(random.nextBytes(size))
            if (size < FlightData.MIN_SIZE) assertNull(parsed) else assertTrue(parsed != null)
        }
        // Signed fields: a negative height (below the takeoff point) stays negative.
        val below = FlightData.parse(ByteArray(24).also { it[0] = 0xF6.toByte(); it[1] = 0xFF.toByte() })!!
        assertEquals(-1f, below.heightMeters, 1e-6f)
    }

    @Test
    fun `log parser survives random, truncated and hostile input`() {
        val parser = DroneLogParser(maxPending = 512)
        repeat(5_000) {
            val payload = random.nextBytes(random.nextInt(0, 200))
            if (payload.isNotEmpty() && random.nextInt(3) != 0) payload[0] = 0
            // Sprinkle record magics with plausible lengths to exercise the split-record path.
            if (payload.size > 4 && random.nextBoolean()) {
                payload[1] = 0x55
                payload[2] = random.nextInt(12, 1200).toByte()
                payload[3] = random.nextInt(0, 5).toByte()
            }
            // Whatever gets through the plausibility checks is usable: finite, unit quaternion.
            for (record in parser.feed(payload)) when (record) {
                is LogRecord.Imu -> {
                    val norm = listOf(record.qw, record.qx, record.qy, record.qz).sumOf { (it * it).toDouble() }
                    val speeds = listOf(record.velocityNorth, record.velocityEast, record.velocityDown)
                    assertTrue(norm in 0.81..1.21 && speeds.all { it.isFinite() && kotlin.math.abs(it) <= 30f }, "$record")
                }
                is LogRecord.VisualOdometry ->
                    assertTrue(listOf(record.x, record.y, record.z).all { it.isFinite() }, "$record")
            }
        }
    }

    /** One valid IMU record (identity quaternion), key 0. */
    private fun imuRecord(): ByteArray {
        val body = java.nio.ByteBuffer.allocate(88).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(48, 1f).array()
        val length = DroneLogParser.HEADER + body.size + DroneLogParser.CRC16
        return ByteArray(length).also {
            it[0] = 0x55
            PacketCodec.putShortLe(it, 1, length)
            PacketCodec.putShortLe(it, 4, DroneLogParser.TYPE_IMU)
            body.copyInto(it, DroneLogParser.HEADER)
        }
    }

    @Test
    fun `an oversized pending record is dropped so later records still decode`() {
        val parser = DroneLogParser(maxPending = 512)
        // Announces a 1023-byte record but only 603 bytes arrive (the rest was lost).
        val hostile = byteArrayOf(0, 0x55, 0xFF.toByte(), 0x03) + ByteArray(600) { 0x55 }
        assertTrue(parser.feed(hostile).isEmpty())
        assertInstanceOf(LogRecord.Imu::class.java, parser.feed(byteArrayOf(0) + imuRecord()).single())
    }

    @Test
    fun `a record split over two packets is decoded once complete`() {
        val parser = DroneLogParser()
        val record = imuRecord()
        assertTrue(parser.feed(byteArrayOf(0) + record.copyOfRange(0, 40)).isEmpty())
        assertInstanceOf(LogRecord.Imu::class.java, parser.feed(byteArrayOf(0) + record.copyOfRange(40, record.size)).single())
        // A payload not starting with 0 is not log data and does not disturb the parser.
        assertTrue(parser.feed(byteArrayOf(1) + record).isEmpty())
        assertFalse(parser.feed(byteArrayOf(0) + record).isEmpty())
    }
}
