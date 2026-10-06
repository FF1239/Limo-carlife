package com.diplay.carlife.desktop

import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeTransport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Desktop CarLife TCP client: one socket per protocol channel, matching the
 * mobile side's six servers (CMD 7240 / VIDEO 8240 / MUSIC 9240 / TTS 9241 /
 * VR 9242 / TOUCH 9340).
 *
 * Two connection targets:
 *  - ADB mode: phone plugged in with USB debugging on; the operator runs
 *    `adb forward tcp:7240 tcp:7240` (etc.) so the same ports exist on
 *    localhost — [target] is then 127.0.0.1.
 *  - WiFi mode: phone and PC on the same network, CarLife wireless mode
 *    waiting; [target] is the phone's IP directly.
 *
 * Incoming per-channel streams carry only the channel header + payload (the
 * 8-byte transport header exists only on the AOA multiplex), so we re-frame
 * with the transport header before feeding [CarLifeSession].
 */
class CarLifeTcpClient(
    private val target: String,
    private val useMdPorts: Boolean = true,
    private val listener: Listener,
) : CarLifeTransport {
    interface Listener {
        fun onChannelConnected(channel: Int)

        fun onChannelClosed(channel: Int, reason: String)

        fun onBytes(channel: Int, data: ByteArray, offset: Int, length: Int)

        /** All six sockets connected — session handshake should start now. */
        fun onAllConnected()
    }

    private class Channel(val id: Int, val port: Int) {
        var socket: Socket? = null
        var output: BufferedOutputStream? = null
        var open = false
    }

    private fun ports(): IntArray = if (useMdPorts) {
        intArrayOf(
            CarLife.MD_PORT_CMD, CarLife.MD_PORT_VIDEO, CarLife.MD_PORT_MUSIC,
            CarLife.MD_PORT_TTS, CarLife.MD_PORT_VR, CarLife.MD_PORT_TOUCH,
        )
    } else {
        intArrayOf(
            CarLife.HU_PORT_CMD, CarLife.HU_PORT_VIDEO, CarLife.HU_PORT_MUSIC,
            CarLife.HU_PORT_TTS, CarLife.HU_PORT_VR, CarLife.HU_PORT_TOUCH,
        )
    }

    private val channels = ports().mapIndexed { i, p -> Channel(i + 1, p) }

    @Volatile
    private var running = false

    @Volatile
    var connectedCount = 0
        private set

    override val isConnected: Boolean get() = connectedCount == 6

    fun start() {
        running = true
        channels.forEach { c ->
            Thread({ runChannel(c) }, "carlife-tcp-${c.id}").start()
        }
    }

    fun stop() {
        running = false
        channels.forEach { c ->
            c.open = false
            runCatching { c.socket?.close() }
            c.socket = null
        }
    }

    override fun close() {
        stop()
    }

    private fun runChannel(c: Channel) {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target, c.port), CONNECT_TIMEOUT_MS)
            socket.receiveBufferSize = 1024 * 1024
            c.socket = socket
            val input = BufferedInputStream(socket.getInputStream(), 128 * 1024)
            c.output = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
            c.open = true
            synchronized(this) {
                connectedCount++
                listener.onChannelConnected(c.id)
                if (connectedCount == channels.size) listener.onAllConnected()
            }

            val headerLen = if (c.id == CarLife.CHANNEL_CMD || c.id == CarLife.CHANNEL_TOUCH) 8 else 12
            val buf = ByteArray(2 * 1024 * 1024)
            while (running && c.open) {
                val head = readFully(input, buf, headerLen) ?: break
                val payloadLen: Int = if (headerLen == 8) {
                    ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                } else {
                    ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
                        ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                }
                if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) throw java.io.IOException("bad payload len $payloadLen")
                val body = if (payloadLen > 0) readFully(input, buf, payloadLen) ?: break else ByteArray(0)
                if (body.size != payloadLen) {
                    throw java.io.IOException("body ${body.size} != header $payloadLen")
                }

                // Re-frame with the 8-byte transport header for the session splitter.
                val frame = ByteArray(8 + headerLen + body.size)
                frame[3] = c.id.toByte()
                val total = headerLen + body.size
                frame[4] = ((total ushr 24) and 0xFF).toByte()
                frame[5] = ((total ushr 16) and 0xFF).toByte()
                frame[6] = ((total ushr 8) and 0xFF).toByte()
                frame[7] = (total and 0xFF).toByte()
                System.arraycopy(head, 0, frame, 8, headerLen)
                System.arraycopy(body, 0, frame, 8 + headerLen, body.size)
                listener.onBytes(c.id, frame, 0, frame.size)
            }
            if (running) listener.onChannelClosed(c.id, "stream ended")
        } catch (e: Exception) {
            e.printStackTrace()
            if (running) listener.onChannelClosed(c.id, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            synchronized(this) {
                if (c.open) {
                    c.open = false
                    connectedCount--
                }
            }
            runCatching { c.socket?.close() }
        }
    }

    /** Sends a full frame; routes to the right socket and strips the transport header. */
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
            if (running) listener.onChannelClosed(c.id, "write failed: ${e.message}")
        }
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val MAX_PAYLOAD = 16 * 1024 * 1024
    }
}
