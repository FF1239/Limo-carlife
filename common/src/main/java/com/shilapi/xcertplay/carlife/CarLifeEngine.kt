package com.shilapi.xcertplay.carlife

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeMessages
import com.diplay.carlife.core.CarLifeSession

/**
 * Owns the CarLife session resources on the head unit: AOA transport, H.264
 * decoder, three PCM output tracks (media/TTS/VR), mic uplink and the protocol
 * session. Lifecycle mirrors DiPlay's session components (create with a Surface,
 * start when the phone connects, release when it detaches).
 */
class CarLifeEngine(
    private val context: Context,
    private val surface: Surface?,
    private val uiListener: UiListener,
) : AoaTransport.Listener, CarLifeSession.Listener {
    interface UiListener {
        fun onEngineState(state: String, detail: String? = null)

        fun onMobileInfo(info: CarLifeMessages.DeviceInfo)

        /** Negotiated video canvas, for touch coordinate scaling. */
        fun onEncoderSize(width: Int, height: Int)
    }

    /** WIRED uses AOA USB; WIRELESS connects to the phone's six TCP ports. */
    enum class Mode { WIRED, WIRELESS }

    private var mode: Mode = Mode.WIRED
    private var aoa: AoaTransport? = null
    private var tcp: TcpChannelTransport? = null
    private var session: CarLifeSession? = null    // ---- video ----
    private var codec: MediaCodec? = null
    private var decoderWidth = CarLife.DEFAULT_VIDEO_WIDTH
    private var decoderHeight = CarLife.DEFAULT_VIDEO_HEIGHT

    // ---- audio ----
    private var musicTrack: AudioTrack? = null
    private var ttsTrack: AudioTrack? = null
    private var vrTrack: AudioTrack? = null
    private var micRecord: AudioRecord? = null
    private var micThread: Thread? = null

    @Volatile
    private var running = false

    /** Set when the user stops the engine; suppresses auto-reconnect. */
    @Volatile
    private var userStopped = false

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var reconnectAttempt = 0

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start() {
        userStopped = false
        start(Mode.WIRED)
    }

    fun start(newMode: Mode, wirelessHost: java.net.InetAddress? = null) {
        if (running) return
        running = true
        mode = newMode
        when (mode) {
            Mode.WIRED -> {
                val transport = AoaTransport(context, this)
                aoa = transport
                session = CarLifeSession(transport, this)
                transport.start()
                uiListener.onEngineState("scanning")
            }
            Mode.WIRELESS -> {
                val host = wirelessHost ?: run {
                    uiListener.onEngineState("wireless", "no host address")
                    running = false
                    return
                }
                val transport = TcpChannelTransport(host, tcpListener)
                tcp = transport
                session = CarLifeSession(transport, this)
                transport.start()
                uiListener.onEngineState("wireless-connecting", host.hostAddress)
            }
        }
    }

    fun stop() {
        userStopped = true
        reconnectHandler.removeCallbacksAndMessages(null)
        running = false
        session?.close("engine stopped")
        session = null
        aoa?.stop()
        aoa = null
        tcp?.stop()
        tcp = null
        releaseVideo()
        releaseAudio()
        uiListener.onEngineState("stopped")
    }

    /**
     * Bridges the per-channel TCP transport into the session: incoming bytes
     * already carry the re-framed transport header.
     */
    private val tcpListener = object : TcpChannelTransport.Listener {
        override fun onBytes(channel: Int, data: ByteArray, offset: Int, length: Int) {
            session?.onBytes(data, offset, length)
        }

        override fun onConnected(channel: Int) {
            if (channel == CarLife.CHANNEL_CMD) {
                session?.startHandshake()
                uiListener.onEngineState("connected")
            }
        }

        override fun onClosed(reason: String) {
            uiListener.onEngineState("disconnected", reason)
        }
    }

    // ---- touch / hard keys ----

    fun sendTouch(action: Int, x: Int, y: Int) {
        session?.sendTouchAction(action, x, y)
    }

    fun sendHardKey(keycode: Int) {
        session?.sendHardKey(keycode)
    }

    // ---- AoaTransport.Listener ----

    override fun onBytes(data: ByteArray, offset: Int, length: Int) {
        session?.onBytes(data, offset, length)
    }

    override fun onClosed(reason: String) {
        releaseVideo()
        releaseAudio()
        scheduleReconnect(reason)
        uiListener.onEngineState("disconnected", reason)
    }

    /**
     * Auto-reconnect with capped backoff (1s, 2s, 4s … max 15s), unless the
     * user stopped the engine. Keeps the projection alive across USB hiccups
     * and phone-side session drops, mirroring DiPlay's connection behaviour.
     */
    private fun scheduleReconnect(reason: String) {
        if (userStopped || !running) return
        val delayMs = (1000L shl minOf(reconnectAttempt, 4)).coerceAtMost(15_000L)
        reconnectAttempt++
        uiListener.onEngineState("reconnecting", "$reason (retry $reconnectAttempt in ${delayMs / 1000}s)")
        reconnectHandler.postDelayed({
            if (userStopped || !running) return@postDelayed
            aoa?.stop()
            aoa = null
            tcp?.stop()
            tcp = null
            session = null
            running = false
            start(mode)
        }, delayMs)
    }

    override fun onState(state: AoaTransport.State, detail: String?) {
        uiListener.onEngineState(state.name.lowercase(), detail)
    }

    // ---- CarLifeSession.Listener ----

    override fun onPhase(phase: CarLifeSession.Phase) {
        uiListener.onEngineState("phase:${phase.name.lowercase()}")
    }

    override fun onVersionMatch(matchStatus: Int) {
        if (matchStatus == 1) {
            reconnectAttempt = 0
            session?.sendAuthenRequest()
            uiListener.onEngineState("connected")
        } else {
            session?.close("version mismatch: $matchStatus")
        }
    }

    override fun onAuthenResponse(response: com.diplay.carlife.core.CarLifeMessages.AuthenResponse) {
        // Strict 4-step order, then HU identity, feature answer and the
        // encoder proposal — the sequence verified against real CarLife.
        session?.sendAuthenResult(true)
        val btAddress = try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address ?: ""
        } catch (e: SecurityException) {
            ""
        }
        session?.sendHuBtOobInfo(btAddress, android.os.Build.MODEL)
        val p = CarLifePersistence.get(context)
        session?.sendHuInfo(
            CarLifeMessages.DeviceInfo(
                os = "android",
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                brand = android.os.Build.BRAND,
                release = android.os.Build.VERSION.RELEASE,
                sdkInt = android.os.Build.VERSION.SDK_INT,
            ),
        )
        session?.sendFeatureConfigResponse(
            listOf(CarLifeMessages.FeatureConfig(CarLife.FEATURE_FOCUS_UI, 1)),
        )
        session?.sendVideoEncoderInit(p.videoWidth(), p.videoHeight(), p.videoFrameRate())
    }

    override fun onMicRecordStart() {
        if (CarLifePersistence.get(context).micUplink()) startMic()
    }

    override fun onMicRecordEnd() {
        stopMic()
    }

    override fun onMobileInfo(info: CarLifeMessages.DeviceInfo) {
        uiListener.onMobileInfo(info)
    }

    override fun onVideoEncoderInitDone(info: CarLifeMessages.VideoEncoderInfo) {
        decoderWidth = info.width
        decoderHeight = info.height
        uiListener.onEncoderSize(info.width, info.height)
        setupDecoder()
        session?.sendVideoEncoderStart()
    }

    override fun onVideoData(timestamp: Long, h264: ByteArray) {
        feedDecoder(h264, timestamp * 1000L)
    }

    override fun onVideoHeartbeat() = Unit

    override fun onMusicInit(init: CarLifeMessages.AudioInit) {
        musicTrack = rebuildTrack(musicTrack, init, AudioManager.STREAM_MUSIC)
    }

    override fun onMusicData(pcm: ByteArray) {
        writePcm(musicTrack, pcm)
    }

    override fun onTtsInit(init: CarLifeMessages.AudioInit) {
        ttsTrack = rebuildTrack(ttsTrack, init, AudioManager.STREAM_MUSIC)
    }

    override fun onTtsData(pcm: ByteArray) {
        writePcm(ttsTrack, pcm)
    }

    override fun onVrInit(init: CarLifeMessages.AudioInit) {
        vrTrack = rebuildTrack(vrTrack, init, AudioManager.STREAM_VOICE_CALL)
    }

    override fun onVrData(pcm: ByteArray) {
        writePcm(vrTrack, pcm)
    }

    override fun onModuleStatus(list: CarLifeMessages.ModuleStatusList) {
        // Phone VR module state drives the mic uplink per the reference flow.
        val vr = list.statuses.firstOrNull { it.moduleId == CarLife.Module.VR } ?: return
        if (vr.statusId == CarLife.Module.VR /* RUNNING == module value in reference states */) {
            startMic()
        } else {
            stopMic()
        }
    }

    override fun onMediaInfo(info: CarLifeMessages.MediaInfo) = Unit

    override fun onNaviNextTurnInfo(info: CarLifeMessages.NaviNextTurnInfo) = Unit

    override fun onErrorCode(error: CarLifeMessages.ErrorCode) {
        Log.w(TAG, "phone error: ${error.errorCode}")
    }

    override fun onBackground() = Unit

    override fun onDisconnect(reason: String) {
        stopMic()
        releaseVideo()
        releaseAudio()
        uiListener.onEngineState("disconnected", reason)
    }

    override fun onRsaPublicKeyRequest() {
        // Matching the open demo default: pre-shared AES key, no real RSA.
        session?.sendRsaPublicKey("")
    }

    override fun onAesKeyDelivered(encryptedKey: String) = Unit

    // ---- video decoder ----

    private fun setupDecoder() {
        releaseVideo()
        if (surface == null || !surface.isValid) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, decoderWidth, decoderHeight)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
        } catch (e: Exception) {
            Log.e(TAG, "decoder setup failed", e)
            codec = null
        }
    }

    private fun feedDecoder(h264: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                val input = c.getInputBuffer(index) ?: return
                input.clear()
                input.put(h264)
                c.queueInputBuffer(index, 0, h264.size, ptsUs, 0)
            }
            val info = MediaCodec.BufferInfo()
            var out = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            while (out >= 0) {
                c.releaseOutputBuffer(out, true)
                out = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode failed", e)
        }
    }

    private fun releaseVideo() {
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }

    // ---- audio ----

    private fun rebuildTrack(current: AudioTrack?, init: CarLifeMessages.AudioInit, stream: Int): AudioTrack? {
        current?.let { runCatching { it.stop(); it.release() } }
        val sampleRate = if (init.sampleRate > 0) init.sampleRate else 16000
        val channels = if (init.channelConfig == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = if (init.sampleFormat == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
            AudioTrack(
                stream,
                sampleRate,
                channels,
                encoding,
                maxOf(minBuf, 8 * 1024),
                AudioTrack.MODE_STREAM,
            ).apply { play() }
        } catch (e: Exception) {
            Log.e(TAG, "audio track init failed: $init", e)
            null
        }
    }

    private fun writePcm(track: AudioTrack?, pcm: ByteArray) {
        track?.let { t -> runCatching { t.write(pcm, 0, pcm.size) } }
    }

    private fun releaseAudio() {
        stopMic()
        listOf(musicTrack, ttsTrack, vrTrack).forEach { t ->
            t?.let { runCatching { it.stop(); it.release() } }
        }
        musicTrack = null
        ttsTrack = null
        vrTrack = null
    }

    // ---- mic uplink (VR) ----

    private fun startMic() {
        if (micRecord != null) return
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8 * 1024),
            )
            record.startRecording()
            micRecord = record
            micThread = Thread {
                val buf = ByteArray(MIC_PACKET_BYTES)
                while (running && micRecord === record) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        session?.sendVrData(buf.copyOf(n))
                    }
                }
            }.apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "mic start failed", e)
        }
    }

    private fun stopMic() {
        micThread = null
        micRecord?.let { r -> runCatching { r.stop(); r.release() } }
        micRecord = null
    }

    companion object {
        private const val TAG = "CarLifeEngine"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MIC_SAMPLE_RATE = 16_000
        private const val MIC_PACKET_BYTES = 1024
    }
}
