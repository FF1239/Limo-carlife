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

        /**
         * Non-fatal: a non-CMD channel failed and will be reconnected in-place
         * after [delayMs]. The CMD session is unaffected.
         */
        fun onChannelRetried(channel: Int, reason: String, delayMs: Long) {}
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

    // Throttle timestamps for the per-instance drop/proof-of-life logs. These
    // were companion statics once, which made instances throttle each other.
    private var lastDropLogAt = 0L
    private var lastTouchOkLogAt = 0L

    /**
     * Outbound frame queue drained by a single writer thread. Touch and
     * hard-key frames are sent from the UI thread — writing to a socket there
     * throws NetworkOnMainThreadException (Android forbids main-thread network
     * I/O), which silently killed every touch before this queue existed. A
     * single writer also serializes per-channel output so frames from
     * different threads can never interleave mid-frame.
     *
     * Bounded: if the phone stops draining, an unbounded queue is a memory
     * leak. Overflow policy is per channel — see [write].
     */
    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(WRITE_QUEUE_CAPACITY)

    fun start() {
        running = true
        Thread({
            try {
                while (running) {
                    val frame = writeQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    routeWrite(frame)
                }
            } catch (_: InterruptedException) {
            }
        }, "carlife-tcp-writer").apply { start() }
        for (c in channels) {
            val t = Thread({ runChannel(c) }, "carlife-tcp-${c.id}").apply { start() }
            c.thread = t
        }
    }

    fun stop() {
        running = false
        writeQueue.clear()
        for (c in channels) {
            c.open = false
            runCatching { c.socket?.close() }
            c.socket = null
        }
    }

    override val isConnected: Boolean get() = channels.firstOrNull { it.open } != null

    private fun runChannel(channel: Channel) {
        var backoffMs = RETRY_BACKOFF_START_MS
        var failures = 0
        while (running) {
            val failure = runConnection(channel)
            if (!running) return
            if (channel.id == CarLife.CHANNEL_CMD) {
                // The CMD socket carries the framed session (handshake, encoder
                // init, module status...): its loss is fatal and escalates to the
                // engine-level full reconnect.
                listener.onClosed("channel ${channel.id}: ${failure ?: "stream ended"}")
                return
            }
            // Escalation policy: VIDEO and TOUCH are required for a usable
            // projection, so after the quick in-place retries fail (the phone
            // only listens on these ports around session establishment) they
            // escalate to the full reconnect — the only path that reopens the
            // ports. MUSIC/TTS/VR degrade gracefully instead: keep self-healing
            // forever so audio recovers the moment the phone reopens its port
            // (e.g. media starts playing) without ever tearing down video.
            failures++
            val mustHave = channel.id == CarLife.CHANNEL_VIDEO || channel.id == CarLife.CHANNEL_TOUCH
            if (mustHave && failures > CHANNEL_SELFHEAL_ATTEMPTS) {
                listener.onClosed(
                    "channel ${channel.id}: self-heal failed ${CHANNEL_SELFHEAL_ATTEMPTS}x: ${failure ?: "stream ended"}",
                )
                return
            }
            listener.onChannelRetried(channel.id, failure ?: "stream ended", backoffMs)
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(RETRY_BACKOFF_MAX_MS)
        }
    }

    /** One connect+read lifetime. Returns the failure description, or null on clean EOF. */
    private fun runConnection(channel: Channel): String? {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            socket.connect(InetSocketAddress(host, channel.port), CONNECT_TIMEOUT_MS)
            // Match the working desktop client: a 1 MB kernel receive window keeps
            // large H.264 I-frames (up to ~314 KB) from being dropped/desynced.
            runCatching { socket.receiveBufferSize = 1024 * 1024 }
            channel.socket = socket
            channel.input = BufferedInputStream(socket.getInputStream(), 128 * 1024)
            channel.output = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)
            channel.open = true
            listener.onConnected(channel.id)

            // Reusable read buffer. Must be >= the largest single CarLife frame
            // (H.264 I-frames can reach ~314 KB at 720p). The desktop client uses
            // 2 MB; mirror it so readFully never overruns the buffer on a big frame.
            val buf = ByteArray(2 * 1024 * 1024)
            val input = channel.input!!
            while (running && channel.open) {
                // Per-channel stream: one message header + payload at a time.
                val head = readFully(input, buf, if (channel.id == CarLife.CHANNEL_CMD || channel.id == CarLife.CHANNEL_TOUCH) 8 else 12)
                    ?: return null
                val payloadLen = if (channel.id == CarLife.CHANNEL_CMD || channel.id == CarLife.CHANNEL_TOUCH) {
                    ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                } else {
                    ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
                        ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
                }
                if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024) throw java.io.IOException("bad payload len $payloadLen")
                val body = if (payloadLen > 0) readFully(input, buf, payloadLen) ?: return null else ByteArray(0)
                // Re-frame with the CarLife transport header so the session splitter is transport-agnostic.
                val frame = packForChannel(channel.id, head, body)
                listener.onBytes(channel.id, frame, 0, frame.size)
            }
            return null
        } catch (e: Exception) {
            return if (running) "${e.javaClass.simpleName}: ${e.message}" else null
        } finally {
            channel.open = false
            runCatching { channel.socket?.close() }
            channel.socket = null
            channel.input = null
            channel.output = null
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
        // Guard against frames larger than the reusable buffer (defensive; the
        // 2 MB buffer already covers normal CarLife frames, but never overrun it).
        if (len > buf.size) {
            val big = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(big, off, len - off)
                if (n < 0) return null
                off += n
            }
            return big
        }
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return null
            off += n
        }
        return if (len == buf.size) buf else buf.copyOf(len)
    }

    /** Enqueues a full CarLife frame; the writer thread delivers it to the channel socket. */
    override fun write(frame: ByteArray) {
        if (writeQueue.offer(frame)) return
        // Queue full: the phone stopped draining. Per-channel policy keeps
        // memory bounded without killing more than the channel deserves.
        when (frameChannel(frame)) {
            CarLife.CHANNEL_TOUCH -> logWriteDropped("touch frame dropped: write queue full")
            CarLife.CHANNEL_CMD ->
                // The CMD frames carry the session itself; a full queue means
                // the phone is effectively gone — escalate to a full reconnect.
                listener.onClosed("cmd write queue full (${WRITE_QUEUE_CAPACITY} frames)")
            else -> {
                // Media channels: the oldest queued frame is stale anyway.
                writeQueue.poll()
                if (!writeQueue.offer(frame)) logWriteDropped("frame dropped: write queue full")
            }
        }
    }

    private fun frameChannel(frame: ByteArray): Int =
        ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

    private fun logWriteDropped(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastDropLogAt > 1000) {
            lastDropLogAt = now
            android.util.Log.w("CarLifeLog", message)
        }
    }

    /** Runs on the writer thread only: routes one frame to its channel socket. */
    private fun routeWrite(frame: ByteArray) {
        if (frame.size < 8) return
        val channel = frameChannel(frame)
        val c = channels.firstOrNull { it.id == channel } ?: return
        val out = c.output ?: run {
            // Silent drops here are how touch/hardkey input "dies" invisibly:
            // log (throttled) so a dead channel is visible in the session log.
            logWriteDropped("write dropped: channel $channel not open (${frame.size}B)")
            return
        }
        try {
            out.write(frame, 8, frame.size - 8)
            out.flush()
            if (channel == CarLife.CHANNEL_TOUCH) {
                // Throttled proof-of-life: touch frames flow to the socket.
                val now = System.currentTimeMillis()
                if (now - lastTouchOkLogAt > 1000) {
                    lastTouchOkLogAt = now
                    android.util.Log.i("CarLifeLog", "touch write ok ${frame.size - 8}B")
                }
            }
        } catch (e: Exception) {
            // write failures surface via the channel read loop's onClosed
            android.util.Log.w("CarLifeLog", "write failed ch=$channel: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val RETRY_BACKOFF_START_MS = 1_000L
        private const val RETRY_BACKOFF_MAX_MS = 5_000L

        /** In-place retries per non-CMD channel before escalating to a full reconnect. */
        private const val CHANNEL_SELFHEAL_ATTEMPTS = 2

        /** Mostly small TOUCH/CMD frames; 64 covers seconds of traffic. */
        private const val WRITE_QUEUE_CAPACITY = 64
    }
}
