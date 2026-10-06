package com.diplay.carlife.core

/**
 * Byte-pipe abstraction the session runs over. Implementations: AOA USB bridge
 * (wired), 6-socket TCP client set (wireless). The session owns one Transport;
 * incoming bytes are pushed through [onBytes] from the implementation's read loop.
 */
interface CarLifeTransport {
    /** Sends one frame's bytes. Implementations may split as their wire needs. */
    fun write(frame: ByteArray)

    fun writeHeaded(head: ByteArray, body: ByteArray) {
        write(head + body)
    }

    fun close()

    /** Whether the transport currently accepts writes. */
    val isConnected: Boolean
}

/** No-op transport used by tests; records everything written. */
class LoopbackTransport : CarLifeTransport {
    val written = ArrayList<ByteArray>()
    override var isConnected: Boolean = true
        set(value) {
            field = value
        }

    override fun write(frame: ByteArray) {
        written.add(frame)
    }

    override fun close() {
        isConnected = false
    }
}
