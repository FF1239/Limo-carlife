package com.diplay.carlife.desktop

import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeMessages
import com.diplay.carlife.core.CarLifeSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.WindowConstants
import kotlin.concurrent.thread

/**
 * 2025 Accord mid-trim head-unit form: 12.3" 1920x720 landscape touch screen.
 * Paints the phone's projection, forwards mouse/touch and hard keys back,
 * plays the three audio channels, and logs the protocol conversation.
 */
class AccordHeadUnitWindow {
    companion object {
        // 2025 Honda Accord mid trim: 12.3-inch Honda Connect panel.
        const val SCREEN_W = 1920
        const val SCREEN_H = 720
        private val BG = Color(0x101418)
    }

    val pipe = Pipe()

    @Volatile
    private var frame: BufferedImage? = null

    private var touchDown = false

    private val bytesCount = LongArray(1)
    private val msgCount = LongArray(1)

    private val screen = object : JPanel(true) {
        init {
            preferredSize = Dimension(SCREEN_W, SCREEN_H)
            background = BG
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val img = frame
            if (img != null) {
                // Letterbox the phone canvas into the 1920x720 panel.
                val scale = minOf(width / img.width.toDouble(), height / img.height.toDouble())
                val w = (img.width * scale).toInt()
                val h = (img.height * scale).toInt()
                g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
            } else {
                g2.color = Color.WHITE
                g2.font = g2.font.deriveFont(26f)
                val msg = "CarLife 等待手机画面…  (视频 ${bytesCount[0] / 1024}KB / ${msgCount[0]} 包)"
                val w = g2.fontMetrics.stringWidth(msg)
                g2.drawString(msg, (width - w) / 2, height / 2)
            }
        }
    }

    private val statusBar = JLabel("initializing…", SwingConstants.LEFT).apply {
        foreground = Color.WHITE
        isOpaque = true
        background = Color(0x000000)
        font = font.deriveFont(15f)
    }

    private val window = JFrame("DiPlay-CarLife 假车机 — 2025 雅阁中配 12.3\" (1920x720)").apply {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        layout = BorderLayout()
        add(screen, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(null)
    }

    fun show() {
        window.isVisible = true
    }

    fun status(text: String) {
        javax.swing.SwingUtilities.invokeLater { statusBar.text = "  $text" }
    }

    fun onFrame(img: BufferedImage) {
        frame = img
        screen.repaint()
    }

    /** Mouse on the panel → CarLife touch, mapped through the letterbox rect. */
    fun installTouchInput() {
        fun send(action: Int, e: MouseEvent) {
            val canvasW = pipe.videoCanvas[0]
            val canvasH = pipe.videoCanvas[1]
            // The frame is letterboxed exactly as paintComponent draws it; map
            // the click into that rect first, or edge clicks land offset.
            val img = frame
            val scale = if (img != null) {
                minOf(screen.width / img.width.toDouble(), screen.height / img.height.toDouble())
            } else {
                minOf(screen.width / canvasW.toDouble(), screen.height / canvasH.toDouble())
            }
            val drawW = (if (img != null) img.width else canvasW) * scale
            val drawH = (if (img != null) img.height else canvasH) * scale
            val offX = (screen.width - drawW) / 2
            val offY = (screen.height - drawH) / 2
            val vx = e.x - offX
            val vy = e.y - offY
            if (vx < 0 || vy < 0 || vx > drawW || vy > drawH) return
            val x = (vx / drawW * canvasW).toInt().coerceIn(0, canvasW - 1)
            val y = (vy / drawH * canvasH).toInt().coerceIn(0, canvasH - 1)
            pipe.session?.sendTouchAction(action, x, y)
        }
        screen.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                touchDown = true
                send(CarLife.TOUCH_ACTION_DOWN, e)
            }

            override fun mouseReleased(e: MouseEvent) {
                touchDown = false
                send(CarLife.TOUCH_ACTION_UP, e)
            }
        })
        screen.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (touchDown) send(CarLife.TOUCH_ACTION_MOVE, e)
            }
        })
    }

    fun installHardKeys() {
        window.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val code: Int? = when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> CarLife.HardKey.OK
                    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE -> CarLife.HardKey.BACK
                    KeyEvent.VK_HOME -> CarLife.HardKey.HOME
                    KeyEvent.VK_LEFT -> CarLife.HardKey.MOVE_LEFT
                    KeyEvent.VK_RIGHT -> CarLife.HardKey.MOVE_RIGHT
                    KeyEvent.VK_UP -> CarLife.HardKey.MOVE_UP
                    KeyEvent.VK_DOWN -> CarLife.HardKey.MOVE_DOWN
                    KeyEvent.VK_PAGE_UP -> CarLife.HardKey.SEEK_ADD
                    KeyEvent.VK_PAGE_DOWN -> CarLife.HardKey.SEEK_SUB
                    KeyEvent.VK_F1 -> CarLife.HardKey.VR_START
                    else -> null
                }
                code?.let { pipe.session?.sendHardKey(it) }
            }
        })
    }

    /**
     * Bridges phone traffic into the UI. The session is injected after both
     * sides exist (session needs a listener; the pipe needs the session).
     */
    inner class Pipe : CarLifeSession.Listener {
        var session: CarLifeSession? = null

        var videoCanvas = intArrayOf(CarLife.DEFAULT_VIDEO_WIDTH, CarLife.DEFAULT_VIDEO_HEIGHT)
            private set

        private val music = AudioPlayer("music")
        private val tts = AudioPlayer("tts")
        private val vr = AudioPlayer("vr")
        private val decoder = VideoDecoder { img -> javax.swing.SwingUtilities.invokeLater { onFrame(img) } }.apply {
            start()
        }
        private val logFile = java.io.PrintWriter(java.io.FileWriter("D:\\dev-tools\\hu-session.log", true))

        private var rsaPrivate: java.security.PrivateKey? = null

        private fun slog(line: String) {
            synchronized(logFile) {
                logFile.println("[${java.time.LocalTime.now()}] $line")
                logFile.flush()
            }
            println("[pipe] $line")
        }

        override fun onPhase(phase: CarLifeSession.Phase) {
            slog("phase=$phase")
            status("阶段: $phase")
            // Fallback path: if the phone runs the reference negotiation
            // (FEATURE_CONFIG_REQUEST first), answer it here once.
            if (phase == CarLifeSession.Phase.FEATURE_CONFIG && !encoderProposed) {
                encoderProposed = true
                session?.sendFeatureConfigResponse(
                    listOf(CarLifeMessages.FeatureConfig(CarLife.FEATURE_FOCUS_UI, 1)),
                )
                session?.sendVideoEncoderInit(1280, 480, 30)
            }
        }

        override fun onVersionMatch(matchStatus: Int) {
            slog("versionMatch=$matchStatus")
            status("协议版本匹配: $matchStatus — 继续认证")
            if (matchStatus == 1) {
                session?.sendAuthenRequest()
                // AUTHEN_RESULT goes out only after the phone's AUTHEN_RESPONSE
                // (strict 4-step order), then HU_INFO triggers feature negotiation.
                session?.sendHuInfo(
                    CarLifeMessages.DeviceInfo(
                        os = "android",
                        manufacturer = "DiPlay",
                        model = "CarLife-FakeHU",
                        brand = "DiPlay",
                        release = "9",
                        sdk = "28",
                        sdkInt = 28,
                    ),
                )
            }
        }

        override fun onAuthenResponse(response: CarLifeMessages.AuthenResponse) {
            slog("authen response received (${response.encryptValue.length}B) → send result")
            session?.sendAuthenResult(true)
            // NOTE: HU_BT_OOB_INFO with the desktop's random adapter MAC made
            // real CarLife+ halt before VIDEO_ENCODER_INIT_DONE (the verified
            // 10:34 success sent no BT OOB at all) — so it stays off here.
            // Real CarLife may never send FEATURE_CONFIG_REQUEST — the HU owns
            // the feature answer and the encoder proposal (steps 5→6).
            if (!encoderProposed) {
                encoderProposed = true
                session?.sendFeatureConfigResponse(
                    listOf(CarLifeMessages.FeatureConfig(CarLife.FEATURE_FOCUS_UI, 1)),
                )
                // Ask for the panel's native canvas; real CarLife renegotiates
                // if it prefers something else (observed: 480p ask → 720p grant).
                session?.sendVideoEncoderInit(1920, 720, 30)
            }
        }

        private var encoderProposed = false

        private var micThread: Thread? = null

        /**
         * Real CarLife stalls until the HU streams VR mic audio after
         * MIC_RECORD_WAKEUP_START. The desktop HU sends silence (16kHz mono
         * 16-bit, 1KB packets as the reference does) to keep the state machine
         * moving; a real unit would capture its microphone here.
         */
        override fun onMicRecordStart() {
            slog("mic record start → streaming silence on VR")
            if (micThread?.isAlive == true) return
            micThread = thread(name = "hu-vr-silence") {
                val silence = ByteArray(1024)
                while (!Thread.currentThread().isInterrupted) {
                    session?.sendVrData(silence)
                    Thread.sleep(32)
                }
            }
        }

        override fun onMicRecordEnd() {
            slog("mic record end")
            micThread?.interrupt()
            micThread = null
        }

        override fun onMobileInfo(info: CarLifeMessages.DeviceInfo) {
            slog("mobile info: ${info.manufacturer} ${info.model} android ${info.release} sdk=${info.sdkInt}")
            status("手机: ${info.manufacturer} ${info.model} Android ${info.release} (SDK ${info.sdkInt})")
            println("[md-info] ${info.manufacturer} ${info.model} ${info.release}")
        }

        override fun onVideoEncoderInitDone(info: CarLifeMessages.VideoEncoderInfo) {
            slog("encoder init done ${info.width}x${info.height}@${info.frameRate} → start")
            videoCanvas = intArrayOf(info.width, info.height)
            status("视频协商: ${info.width}x${info.height}@${info.frameRate} — 请求推流")
            session?.sendVideoEncoderStart()
        }

        override fun onVideoData(timestamp: Long, h264: ByteArray) {
            bytesCount[0] += h264.size
            msgCount[0]++
            if (msgCount[0] % 30 == 1L) {
                slog("video pkt#${msgCount[0]} ${h264.size}B total=${bytesCount[0] / 1024}KB")
            }
            decoder.feed(h264)
        }

        override fun onVideoHeartbeat() {
            status("视频心跳(手机后台/无截屏)")
        }

        override fun onMusicInit(init: CarLifeMessages.AudioInit) {
            music.init(init.sampleRate, init.channelConfig, init.sampleFormat)
        }

        override fun onMusicData(pcm: ByteArray) = music.play(pcm)

        override fun onTtsInit(init: CarLifeMessages.AudioInit) {
            tts.init(init.sampleRate, init.channelConfig, init.sampleFormat)
        }

        override fun onTtsData(pcm: ByteArray) = tts.play(pcm)

        override fun onVrInit(init: CarLifeMessages.AudioInit) {
            vr.init(init.sampleRate, init.channelConfig, init.sampleFormat)
        }

        override fun onVrData(pcm: ByteArray) = vr.play(pcm)

        override fun onModuleStatus(list: CarLifeMessages.ModuleStatusList) {
            list.statuses.forEach { println("[module] id=${it.moduleId} status=${it.statusId}") }
        }

        override fun onMediaInfo(info: CarLifeMessages.MediaInfo) {
            status("媒体: ${info.artist} — ${info.song}")
        }

        override fun onNaviNextTurnInfo(info: CarLifeMessages.NaviNextTurnInfo) {
            status("导航: ${info.roadName} 剩余 ${info.remainDistance}m")
        }

        override fun onErrorCode(error: CarLifeMessages.ErrorCode) {
            status("手机错误: ${error.errorCode}")
        }

        override fun onBackground() {
            status("手机进入后台,仅视频心跳")
        }

        override fun onDisconnect(reason: String) {
            status("断开: $reason")
        }

        override fun onRsaPublicKeyRequest() {
            slog("RSA pubkey requested — serving generated key")
            val (pub, priv) = RsaCrypto.ensureKeyPair()
            rsaPrivate = priv
            session?.sendRsaPublicKey(pub)
        }

        override fun onAesKeyDelivered(encryptedKey: String) {
            val key = rsaPrivate?.let { RsaCrypto.decrypt(it, encryptedKey) }
            slog("AES key delivered, decrypted=${key != null} len=${key?.length ?: 0}")
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "真实 CarLife 请求加密会话,已交付 AES 密钥。加密流解密尚未实现——画面可能无法显示。",
                "CarLife 加密握手",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }
}
