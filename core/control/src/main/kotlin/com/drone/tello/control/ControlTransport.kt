package com.drone.tello.control

import kotlinx.coroutines.flow.Flow

/**
 * Datagram link to the drone control port (192.168.10.1:8889).
 * Implemented with UDP sockets on Android and with a fake in unit tests.
 */
interface ControlTransport {
    /** Local UDP port on which the drone must stream the video. */
    val videoPort: Int

    /** Sends one datagram. Must not block for long: called from the stick loop. */
    suspend fun send(datagram: ByteArray)

    /** Datagrams received on the control port. */
    val incoming: Flow<ByteArray>
}
