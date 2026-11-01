package com.drone.tello.control

import com.drone.tello.protocol.Command
import com.drone.tello.protocol.Crc
import com.drone.tello.protocol.MessageId
import com.drone.tello.protocol.Packet
import com.drone.tello.protocol.PacketCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope

/** In-memory drone link recording every datagram with its (virtual) send time. */
class FakeTransport(private val scope: TestScope) : ControlTransport {
    data class Sent(val timeMs: Long, val bytes: ByteArray) {
        val packet: Packet? get() = PacketCodec.decode(bytes)
        val isHandshake get() = String(bytes, Charsets.US_ASCII).startsWith("conn_req:")
        fun isCommand(c: Command) = packet?.messageId == c.id
    }

    override val videoPort = 7777
    val sent = mutableListOf<Sent>()
    override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    /** When set, every send fails like a blocked socket (EPERM). */
    var failing = false

    override suspend fun send(datagram: ByteArray) {
        if (failing) throw java.io.IOException("sendto failed: EPERM")
        sent += Sent(scope.testScheduler.currentTime, datagram)
    }

    fun droneSends(bytes: ByteArray) = check(incoming.tryEmit(bytes))
    fun ack() = droneSends("conn_ack:".toByteArray() + byteArrayOf(0x61, 0x1E))

    /** A packet as the drone would send it (type 0x48, as the firmware does for replies). */
    fun droneMessage(id: Int, payload: ByteArray = ByteArray(0), sequence: Int = 0) {
        val raw = PacketCodec.encode(Command.QUERY_FIRMWARE, 0, payload)
        raw[5] = id.toByte(); raw[6] = (id ushr 8).toByte()
        raw[7] = sequence.toByte(); raw[8] = (sequence ushr 8).toByte()
        val crc = Crc.crc16(raw, 0, raw.size - 2)
        raw[raw.size - 2] = crc.toByte(); raw[raw.size - 1] = (crc ushr 8).toByte()
        droneSends(raw)
    }

    /** The drone acknowledges [sent] (same id and sequence). */
    fun droneAcks(sent: Sent) {
        val p = sent.packet!!
        droneMessage(p.messageId, byteArrayOf(0), p.sequence)
    }

    fun droneHeartbeat() = droneMessage(MessageId.WIFI, byteArrayOf(90, 0))

    fun flightData(flying: Boolean) =
        droneMessage(MessageId.FLIGHT_DATA, ByteArray(24).also { if (flying) it[17] = 1 })

    fun sticks() = sent.filter { it.isCommand(Command.STICK) }
    fun all(c: Command) = sent.filter { it.isCommand(c) }
    fun count(c: Command) = sent.count { it.isCommand(c) }
    fun handshakes() = sent.count { it.isHandshake }
}

/** Decodes the 4 axes (11-bit raw values) and the fast flag of a stick packet. */
fun decodeSticks(sent: FakeTransport.Sent): List<Long> {
    val p = sent.packet!!.payload
    var packed = 0L
    for (i in 0 until 6) packed = packed or ((p[i].toLong() and 0xFF) shl (8 * i))
    return List(4) { (packed ushr (11 * it)) and 0x7FF } + ((packed ushr 44) and 1)
}

val NEUTRAL_AXES = listOf(1024L, 1024L, 1024L, 1024L)
fun FakeTransport.Sent.isNeutralStick() = decodeSticks(this).take(4) == NEUTRAL_AXES
