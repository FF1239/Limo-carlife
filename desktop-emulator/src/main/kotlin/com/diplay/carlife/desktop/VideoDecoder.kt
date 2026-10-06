package com.diplay.carlife.desktop

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * H.264 stream renderer built on javacv's high-level grabber: VIDEO_DATA
 * payloads are piped over a loopback socket into an FFmpegFrameGrabber opened
 * as raw h264; frames come back as BufferedImages for Swing. This avoids the
 * hand-rolled low-level avcodec API entirely.
 *
 * A small ring buffer keeps the most recent 256KB so the grabber (which starts
 * after the first bytes arrive) can still pick up an SPS/PPS+IDR quickly.
 */
class VideoDecoder(private val onFrame: (BufferedImage) -> Unit) {
    private val converter = Java2DFrameConverter()
    private var server: ServerSocket? = null
    private var socket: Socket? = null

    @Volatile
    var framesDecoded = 0L
        private set

    private val ring = object {
        val buf = ByteArray(RING_SIZE)
        var filled = 0

        fun add(data: ByteArray) {
            val n = data.size
            if (n >= RING_SIZE) {
                System.arraycopy(data, n - RING_SIZE, buf, 0, RING_SIZE)
                filled = RING_SIZE
                return
            }
            val keep = minOf(filled, RING_SIZE - n)
            System.arraycopy(buf, filled - keep, buf, 0, keep)
            System.arraycopy(data, 0, buf, keep, n)
            filled = keep + n
        }

        fun drainInto(out: java.io.OutputStream) {
            if (filled > 0) out.write(buf, 0, filled)
        }
    }

    fun start() {
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server = s
        Thread({
            try {
                val grabber = FFmpegFrameGrabber("tcp://127.0.0.1:${s.localPort}")
                grabber.format = "h264"
                grabber.start()
                println("[video] decoder up (${grabber.imageWidth}x${grabber.imageHeight})")
                while (!Thread.currentThread().isInterrupted) {
                    val f = grabber.grab() ?: break
                    if (f.image != null) {
                        framesDecoded++
                        val img = converter.convert(f)
                        onFrame(img)
                    }
                }
            } catch (e: Exception) {
                println("[video] decoder stopped: ${e.message}")
            }
        }, "carlife-video-decoder").start()

        Thread({
            try {
                val client = s.accept()
                socket = client
                val out = client.getOutputStream()
                synchronized(ring) { ring.drainInto(out) }
                println("[video] local grabber connected on :${s.localPort}")
            } catch (e: Exception) {
                println("[video] pipe server stopped: ${e.message}")
            }
        }, "carlife-video-pipe").start()
    }

    /** Called with every VIDEO_DATA payload from the phone. */
    fun feed(h264: ByteArray) {
        synchronized(ring) { ring.add(h264) }
        val s = socket ?: return
        try {
            synchronized(s) {
                s.getOutputStream().write(h264)
                s.getOutputStream().flush()
            }
        } catch (e: Exception) {
            // grabber side will surface the failure
        }
    }

    fun release() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    companion object {
        private const val RING_SIZE = 256 * 1024
    }
}
