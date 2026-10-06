package com.diplay.carlife.md

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phone-side CarLife MD-role test app. Start the service, then point the
 * desktop fake head unit at this phone: it answers the CarLife handshake,
 * streams a synthetic test pattern over VIDEO and a 440Hz tone over MUSIC, and
 * shows touch/hard-key events arriving from the head unit.
 */
class MainActivity : android.app.Activity() {

    private lateinit var logView: TextView
    private var mdHost: MdHost? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val ui = object : MdHost.Listener {
        override fun onEvent(line: String) {
            log("• $line")
        }

        override fun onVideoStarted(width: Int, height: Int, frameRate: Int) {
            startVideo(width, height, frameRate)
            // The synthetic tone rides along with the video stream; real MD
            // apps send MEDIA_INIT when playback actually starts.
            startTone(44100, 2)
        }

        override fun onAudioStarted(sampleRate: Int, channels: Int) {
            startTone(sampleRate, channels)
        }

        override fun onTouch(action: Int, x: Int, y: Int) {
            val name = when (action) {
                0 -> "DOWN"; 1 -> "UP"; else -> "MOVE"
            }
            log("触摸 $name ($x, $y)")
        }

        override fun onHardKey(keycode: Int) {
            log("硬键 0x%02X".format(keycode))
        }

        override fun onClientConnected(channel: Int) {
            log("通道 $channel 已连入")
        }

        override fun onAllConnected() {
            log("★ 6 通道全部连接,等待车机握手…")
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(pad, pad, pad, pad)
        }

        val ip = wifiIp() ?: "未连接 WiFi/热点"
        val startButton = Button(this).apply {
            text = "启动 CarLife 手机端服务(端口 7240/8240/9240/9241/9242/9340)"
            setOnClickListener { startService() }
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF101418.toInt())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@MainActivity).apply {
                    text = "CarLife 手机端模拟(MD 角色)\n电脑假车机连接本机 IP:\n$ip\n热点网段下的电脑地址自行确认"
                    setTextColor(0xFFE4002B.toInt())
                    textSize = 16f
                })
                addView(startButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = pad
                })
                addView(logView)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            })
        })
        log("手机 IP: $ip")
        log("电脑端运行:desktop-emulator.bat wifi $ip")
    }

    private fun startService() {
        if (mdHost != null) {
            log("服务已在运行")
            return
        }
        val host = MdHost(
            com.diplay.carlife.core.CarLifeMessages.DeviceInfo(
                os = "android",
                model = android.os.Build.MODEL,
                brand = android.os.Build.BRAND,
                manufacturer = android.os.Build.MANUFACTURER,
                release = android.os.Build.VERSION.RELEASE,
                device = android.os.Build.DEVICE,
                product = android.os.Build.PRODUCT,
            ),
            ui,
        )
        mdHost = host
        host.start()
        log("服务已启动,等待车机连接 6 个通道…")
    }

    private fun startVideo(width: Int, height: Int, frameRate: Int) {
        if (videoThread != null) return
        log("启动编码器 ${width}x${height}@$frameRate")
        videoThread = thread(name = "md-video") {
            try {
                encodeTestPattern(width, height, frameRate)
            } catch (e: Exception) {
                log("编码异常: ${e.message}")
            }
        }
    }

    /** Animated test pattern → H.264 → VIDEO channel. */
    private fun encodeTestPattern(width: Int, height: Int, frameRate: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = encoder.createInputSurface()
        encoder.start()

        val frameDurationMs = 1000L / frameRate
        var frame = 0L
        val startNanos = System.nanoTime()

        // Draw loop
        thread(name = "md-draw") {
            val paints = Array(6) { i ->
                Paint().apply {
                    color = when (i) {
                        0 -> 0xFFFFFF; 1 -> 0xFFFF00; 2 -> 0x00FFFF; 3 -> 0x00FF00;
                        4 -> 0xFF00FF; else -> 0xFF0000
                    }.toInt()
                }
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = height / 10f
                isAntiAlias = true
            }
            while (!Thread.currentThread().isInterrupted) {
                val canvas: Canvas = try {
                    input.lockHardwareCanvas()
                } catch (e: Exception) {
                    break
                }
                if (canvas != null) {
                    val t = (System.nanoTime() - startNanos) / 1_000_000_000.0
                    canvas.drawColor(0xFF101418.toInt())
                    val barW = width / 12f
                    for (i in 0 until 6) {
                        paints[i].alpha = 255
                        canvas.drawRect(i * barW, 0f, (i + 1) * barW, height / 4f, paints[i])
                    }
                    val movingX = ((t * width / 3) % (width * 1.5)).toFloat() - width * 0.25f
                    canvas.drawCircle(movingX, height * 0.55f, height / 12f, paints[1])
                    canvas.drawText("CarLife MD", width * 0.18f, height * 0.8f, textPaint)
                    canvas.drawText("%.1fs".format(t), width * 0.18f, height * 0.93f, textPaint)
                    input.unlockCanvasAndPost(canvas)
                }
                frame++
                val targetNanos = startNanos + frame * frameDurationMs * 1_000_000
                val sleep = (targetNanos - System.nanoTime()) / 1_000_000
                if (sleep > 0) Thread.sleep(sleep)
            }
        }

        // Drain loop → wire
        val info = MediaCodec.BufferInfo()
        while (!Thread.currentThread().isInterrupted) {
            val idx = encoder.dequeueOutputBuffer(info, 10_000)
            if (idx >= 0) {
                val buf = encoder.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    mdHost?.sendVideo(bytes, info.presentationTimeUs / 1000)
                }
                encoder.releaseOutputBuffer(idx, false)
            } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(5)
            }
        }
        runCatching { encoder.stop(); encoder.release() }
    }

    private fun startTone(sampleRate: Int, channels: Int) {
        if (audioThread != null) return
        log("启动测试音 ${sampleRate}Hz ${channels}ch")
        audioThread = thread(name = "md-audio") {
            val chunkMs = 40
            val samplesPerChunk = sampleRate * chunkMs / 1000
            val chunk = ByteArray(samplesPerChunk * 2 * channels)
            var phase = 0.0
            val step = 440.0 * 2 * PI / sampleRate
            mdHost?.sendMusicInit(sampleRate, channels)
            while (!Thread.currentThread().isInterrupted) {
                var i = 0
                while (i < chunk.size) {
                    val v = (sin(phase) * 12000).toInt()
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                    for (ch in 0 until channels) {
                        chunk[i++] = (v and 0xFF).toByte()
                        chunk[i++] = ((v shr 8) and 0xFF).toByte()
                    }
                }
                mdHost?.sendMusicData(chunk)
                Thread.sleep(chunkMs.toLong())
            }
        }
    }

    private fun wifiIp(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        runOnUiThread {
            logView.append("[$ts] $line\n")
        }
    }

    override fun onDestroy() {
        videoThread?.interrupt()
        audioThread?.interrupt()
        mdHost?.stop()
        mdHost = null
        super.onDestroy()
    }
}
