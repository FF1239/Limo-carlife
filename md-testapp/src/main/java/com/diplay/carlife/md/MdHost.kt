package com.diplay.carlife.md

import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeFrame
import com.diplay.carlife.core.CarLifeMessages
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarLife MOBILE-side (MD) role for protocol testing: listens on the six MD
 * ports, answers the HU handshake, streams a synthetic H.264 video plus a test
 * tone once the encoder is started, and forwards received touch events to a
 * callback. Mirrors Baidu's CarLife-MD behaviour from the reference sources.
 */
class MdHost(
    private val deviceInfo: CarLifeMessages.DeviceInfo,
    private val listener: Listener,
) {
    interface Listener {
        fun onEvent(line: String)

        fun onVideoStarted(width: Int, height: Int, frameRate: Int)

        fun onAudioStarted(sampleRate: Int, channels: Int)

        fun onTouch(action: Int, x: Int, y: Int)

        fun onHardKey(keycode: Int)

        fun onClientConnected(channel: Int)

        fun onAllConnected()
    }

    private val running = AtomicBoolean(false)
    private val servers = HashMap<Int, ServerSocket>()
    private var videoOut: BufferedOutputStream? = null
    private var musicOut: BufferedOutputStream? = null
    private val lock = Any()

    @Volatile
    var encoderStarted = false
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ports = intArrayOf(
            CarLife.MD_PORT_CMD, CarLife.MD_PORT_VIDEO, CarLife.MD_PORT_MUSIC,
            CarLife.MD_PORT_TTS, CarLife.MD_PORT_VR, CarLife.MD_PORT_TOUCH,
        )
        for (p in ports) {
            Thread({
                try {
                    val server = ServerSocket(p, 4, InetAddress.getByName("0.0.0.0"))
                    servers[p] = server
                    listener.onEvent("监听 :$p")
                    while (running.get()) {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        val channel = channelOfPort(p)
                        // Handle each client on its own thread — the accept loop
                        // must never be blocked by a held connection.
                        Thread({ serve(channel, socket) }, "md-serve-$p-${channel}").start()
                    }
                } catch (e: Exception) {
                    if (running.get()) listener.onEvent("端口 $p 异常: ${e.message}")
                }
            }, "md-port-$p").start()
        }
    }

    private fun channelOfPort(port: Int): Int = when (port) {
        CarLife.MD_PORT_CMD -> CarLife.CHANNEL_CMD
        CarLife.MD_PORT_VIDEO -> CarLife.CHANNEL_VIDEO
        CarLife.MD_PORT_MUSIC -> CarLife.CHANNEL_MUSIC
        CarLife.MD_PORT_TTS -> CarLife.CHANNEL_TTS
        CarLife.MD_PORT_VR -> CarLife.CHANNEL_VR
        else -> CarLife.CHANNEL_TOUCH
    }

    private fun connectedCount(): Int = synchronized(this) { this.connected }

    private var connected = 0

    fun stop() {
        running.set(false)
        synchronized(this) {
            runCatching { videoOut?.flush() }
            runCatching { musicOut?.flush() }
        }
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
    }

    private fun serve(channel: Int, socket: Socket) {
        listener.onClientConnected(channel)
        synchronized(this) {
            connected++
            if (connected >= 6) listener.onAllConnected()
        }
        val input = BufferedInputStream(socket.getInputStream(), 128 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 512 * 1024)
        synchronized(this) {
            if (channel == CarLife.CHANNEL_VIDEO) videoOut = output
            if (channel == CarLife.CHANNEL_MUSIC) musicOut = output
        }
        try {
            when (channel) {
                CarLife.CHANNEL_CMD -> cmdLoop(channel, input, output)
                CarLife.CHANNEL_TOUCH -> touchLoop(channel, input)
                else -> {
                    // Outbound-only channels (VIDEO/MUSIC/TTS/VR): the HU never
                    // sends on these; keep the socket open until it drops.
                    while (running.get() && !socket.isClosed && socket.isConnected) {
                        Thread.sleep(500)
                    }
                }
            }
        } catch (e: Exception) {
            listener.onEvent("通道 $channel 断开: ${e.message}")
        } finally {
            synchronized(this) {
                connected--
                if (channel == CarLife.CHANNEL_VIDEO) videoOut = null
                if (channel == CarLife.CHANNEL_MUSIC) musicOut = null
            }
            runCatching { socket.close() }
        }
    }

    /** CMD channel: answer the HU handshake, MD role. */
    private fun cmdLoop(channel: Int, input: BufferedInputStream, output: BufferedOutputStream) {
        val buf = ByteArray(65536)
        while (running.get()) {
            val head = readFully(input, buf, CarLife.CHANNEL_HEADER_SIZE_CMD) ?: return
            val payloadLen = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
            val payload = if (payloadLen > 0) {
                readFully(input, buf, payloadLen) ?: return
            } else {
                ByteArray(0)
            }
            val serviceType = readServiceType(head).toInt()
            when (serviceType) {
                CarLife.Cmd.HU_PROTOCOL_VERSION -> {
                    val v = CarLifeMessages.ProtocolVersion.decode(payload)
                    listener.onEvent("HU 版本 ${v.major}.${v.minor} → 匹配")
                    sendCmd(output, CarLife.Cmd.PROTOCOL_VERSION_MATCH_STATUS,
                        CarLifeMessages.ProtocolVersionMatchStatus(1).encode())
                }
                CarLife.Cmd.HU_INFO -> {
                    listener.onEvent("收到 HU 设备信息 → 回手机信息")
                    sendCmd(output, CarLife.Cmd.MD_INFO, deviceInfo.encode())
                    // MD-initiated feature negotiation (reference MD behaviour).
                    sendCmd(output, CarLife.Cmd.MD_FEATURE_CONFIG_REQUEST, ByteArray(0))
                }
                CarLife.Cmd.HU_AUTHEN_REQUEST -> {
                    listener.onEvent("HU 认证请求 → 响应")
                    sendCmd(output, CarLife.Cmd.MD_AUTHEN_RESPONSE,
                        CarLifeMessages.AuthenResponse("md-emulator").encode())
                    sendCmd(output, CarLife.Cmd.MD_AUTHEN_RESULT,
                        CarLifeMessages.AuthenResult(true).encode())
                }
                CarLife.Cmd.HU_FEATURE_CONFIG_RESPONSE -> {
                    listener.onEvent("特性协商完成")
                }
                CarLife.Cmd.VIDEO_ENCODER_INIT -> {
                    val e = CarLifeMessages.VideoEncoderInfo.decode(payload)
                    listener.onEvent("编码器协商 ${e.width}x${e.height}@${e.frameRate}")
                    acceptEncoder(e.width, e.height, e.frameRate)
                    sendCmd(output, CarLife.Cmd.VIDEO_ENCODER_INIT_DONE, payload)
                }
                CarLife.Cmd.VIDEO_ENCODER_START -> {
                    markEncoderStarted()
                    listener.onEvent("编码器启动 → 推流")
                }
                else -> listener.onEvent(String.format("CMD 0x%08X (%dB)", serviceType, payloadLen))
            }
        }
    }

    private fun readServiceType(head: ByteArray): Long =
        ((head[4].toLong() and 0xFF) shl 24) or ((head[5].toLong() and 0xFF) shl 16) or
            ((head[6].toLong() and 0xFF) shl 8) or (head[7].toLong() and 0xFF)

    private fun sendCmd(output: BufferedOutputStream, serviceType: Int, payload: ByteArray) {
        val frame = CarLifeFrame.packSplit(
            CarLife.CHANNEL_CMD, serviceType.toLong(), payload,
        )
        synchronized(lock) {
            output.write(frame.second)
            output.flush()
        }
    }

    private fun touchLoop(channel: Int, input: BufferedInputStream) {
        val buf = ByteArray(65536)
        while (running.get()) {
            val head = readFully(input, buf, CarLife.CHANNEL_HEADER_SIZE_CMD) ?: return
            val payloadLen = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
            val payload = if (payloadLen > 0) readFully(input, buf, payloadLen) ?: return else ByteArray(0)
            when (readServiceType(head).toInt()) {
                CarLife.Stream.TOUCH_ACTION -> {
                    val t = decodeTouch(payload)
                    listener.onTouch(t.action, t.x, t.y)
                }
                CarLife.Stream.TOUCH_CAR_HARD_KEY_CODE -> {
                    val r = com.diplay.carlife.core.ProtoReader(payload)
                    var f = r.nextField(); var code = -1
                    while (f != 0) {
                        if (f == 1) code = r.readInt32() else r.skip()
                        f = r.nextField()
                    }
                    listener.onHardKey(code)
                }
            }
        }
    }

    private fun decodeTouch(payload: ByteArray): CarLifeMessages.TouchAction {
        val r = com.diplay.carlife.core.ProtoReader(payload)
        var f = r.nextField(); var a = -1; var x = -1; var y = -1
        while (f != 0) {
            when (f) { 1 -> a = r.readInt32(); 2 -> x = r.readInt32(); 3 -> y = r.readInt32(); else -> r.skip() }
            f = r.nextField()
        }
        return CarLifeMessages.TouchAction(a, x, y)
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

    // ---- outbound streams (video / music), called by the activity ----

    /** Sends raw H.264 on the VIDEO channel (annex-b as produced by MediaCodec). */
    fun sendVideo(h264: ByteArray, timestampMs: Long) {
        val out = videoOut ?: return
        val body = CarLifeFrame.packSplit(
            CarLife.CHANNEL_VIDEO, CarLife.Stream.VIDEO_DATA.toLong(), h264, timestampMs,
        )
        synchronized(lock) {
            out.write(body.second)
            out.flush()
        }
    }

    fun sendMusicInit(sampleRate: Int, channels: Int) {
        val out = musicOut ?: return
        val init = CarLifeFrame.packSplit(
            CarLife.CHANNEL_MUSIC, CarLife.Stream.MEDIA_INIT.toLong(),
            CarLifeMessages.AudioInit(sampleRate, channels, 16).encode(),
        )
        synchronized(lock) {
            out.write(init.second)
            out.flush()
        }
        listener.onAudioStarted(sampleRate, channels)
    }

    fun sendMusicData(pcm: ByteArray) {
        val out = musicOut ?: return
        val body = CarLifeFrame.packSplit(
            CarLife.CHANNEL_MUSIC, CarLife.Stream.MEDIA_DATA.toLong(), pcm,
        )
        synchronized(lock) {
            out.write(body.second)
            out.flush()
        }
    }

    /** Announces the encoder parameters the HU asked for, then lets it start. */
    fun acceptEncoder(width: Int, height: Int, frameRate: Int) {
        listener.onVideoStarted(width, height, frameRate)
    }

    fun markEncoderStarted() {
        encoderStarted = true
    }
}
