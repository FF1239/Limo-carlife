package com.shilapi.xcertplay.carlife

import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeTransport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Wireless transport: the phone (CarLife <= 6.7.2 / CarWith) acts as the TCP
 * server on six ports (CMD 7240 / VIDEO 8240 / MUSIC 9240 / TTS 9241 / VR 9242 /
 * TOUCH 9340); the head unit connects out and runs the same framed session over
 * each stream. The head unit is expected to already share a network with the
 * phone (hotspot or WiFi Direct — brought up by the DiPlay shell).
 *
 * Writes are per-channel; each socket sends body-only bytes for its channel
 * (channel routing happens by which socket a frame arrives on).
 */
class TcpChannelTransport(
    private val host: InetAddress,
    private val listener: Listener,
) : CarLifeTransport {
    interface Listener {
        /** Bytes from one channel socket, channel included. */
        fun onBytes(channel: Int, data: ByteArray, offset: Int, length: Int)

        fun onConnected(channel: Int)

        fun onClosed(reason: String)
    }

    private class Channel(
        val id: Int,
        val port: Int,
    ) {
        var socket: Socket? = null
        var input: BufferedInputStream? = null
        var output: BufferedOutputStream? = null
        var thread: Thread? = null

        @Volatile
        var open = false
    }

    private val channels = listOf(
        Channel(CarLife.CHANNEL_CMD, CarLife.MD_PORT_CMD),
        Channel(CarLife.CHANNEL_VIDEO, CarLife.MD_PORT_VIDEO),
        Channel(CarLife.CHANNEL_MUSIC, CarLife.MD_PORT_MUSIC),
        Channel(CarLife.CHANNEL_TTS, CarLife.MD_PORT_TTS),
        Channel(CarLife.CHANNEL_VR, CarLife.MD_PORT_VR),
        Channel(CarLife.CHANNEL_TOUCH, CarLife.MD_PORT_TOUCH),
    )

    @Volatile
    private var running = false

    fun start() {
        running = true
        for (c in channels) {
            val t = Thread({ runChannel(c) }, "carlife-tcp-${c.id}").apply { start() }
            c.thread = t
        }
    }

    fun stop() {
        running = false
        for (c in channels) {
            c.open = false
            runCatching { c.socket?.close() }
            c.socket = null
        }
    }

    override val isConnected: Boolean get() = channels.firstOrNull { it.open } != null

    private fun runChannel(channel: Channel) {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            socket.connect(InetSocketAddress(host, channel.port), CONNECT_TIMEOUT_MS)
            channel.socket = socket
            channel.input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            channel.output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            channel.open = true
            listener.onConnected(channel.id)

            val buf = ByteArray(16 * 1024)
            val input = channel.input!!
            while (running && channel.open) {
                // Per-channel stream: one message header + payload at a time.
                val head = readFully(input, buf, if (channel.id == CarLife.CHANNEL_CMD || channel.id == CarLife.CHANNEL_TOUCH) 8 else 12)
                    ?: break
                val payloadLen = if (channel.id == CarLife.CHANNEL_CMD || channel.id == CarLife.CHANNEL_TOUCH) {
                    ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                } else {
                    ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
                        ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                }
                if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024) throw java.io.IOException("bad payload len $payloadLen")
                val body = if (payloadLen > 0) readFully(input, buf, payloadLen) ?: break else ByteArray(0)
                // Re-frame with the CarLife transport header so the session splitter is transport-agnostic.
                val frame = packForChannel(channel.id, head, body)
                listener.onBytes(channel.id, frame, 0, frame.size)
            }
        } catch (e: Exception) {
            if (running) listener.onClosed("channel ${channel.id}: ${e.message}")
        } finally {
            channel.open = false
            runCatching { channel.socket?.close() }
        }
    }

    /** Prepends the 8-byte transport header expected by [CarLifeSession]. */
    private fun packForChannel(channel: Int, head: ByteArray, body: ByteArray): ByteArray {
        val out = ByteArray(8 + head.size + body.size)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = channel.toByte()
        val total = head.size + body.size
        out[4] = ((total ushr 24) and 0xFF).toByte()
        out[5] = ((total ushr 16) and 0xFF).toByte()
        out[6] = ((total ushr 8) and 0xFF).toByte()
        out[7] = (total and 0xFF).toByte()
        System.arraycopy(head, 0, out, 8, head.size)
        System.arraycopy(body, 0, out, 8 + head.size, body.size)
        return out
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray, len: Int): ByteArray? {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return null
            off += n
        }
        return if (len == buf.size) buf else buf.copyOf(len)
    }

    /** Sends a full CarLife frame on the matching channel socket (strips transport header). */
    override fun write(frame: ByteArray) {
        if (frame.size < 8) return
        val channel = ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val c = channels.firstOrNull { it.id == channel } ?: return
        val out = c.output ?: return
        try {
            out.write(frame, 8, frame.size - 8)
            out.flush()
        } catch (e: Exception) {
            // write failures surface via the channel read loop's onClosed
        }
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}
