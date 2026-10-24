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
    /** When set, the head unit negotiates exactly this canvas instead of the
     *  persisted preset — used to match the unit's own panel size so the
     *  projection maps 1:1 with no cropping and no letterbox. */
    private val encoderSizeOverride: Pair<Int, Int>? = null,
) : AoaTransport.Listener, CarLifeSession.Listener {
    interface UiListener {
        fun onEngineState(state: String, detail: String? = null)

        fun onMobileInfo(info: CarLifeMessages.DeviceInfo)

        /** Negotiated video canvas, for touch coordinate scaling. */
        fun onEncoderSize(width: Int, height: Int)

        /** Phone media metadata (song/artist/album art bytes) for HUD cards. */
        fun onMediaInfo(info: CarLifeMessages.MediaInfo) {}

    /** Phone navigation next-turn info for HUD cards. */
    fun onNaviInfo(info: CarLifeMessages.NaviNextTurnInfo) {}

    /** Phone GNSS fix (subscribed after handshake); speed is cm/s. */
    fun onCarGps(gps: CarLifeMessages.CarGps) {}

    /** Playback progress percent 0..100 for the media card. */
    fun onMediaProgress(percent: Int) {}
    }

    /** WIRED uses AOA USB; WIRELESS connects to the phone's six TCP ports. */
    enum class Mode { WIRED, WIRELESS }

    private var mode: Mode = Mode.WIRED

    @Volatile
    private var aoa: AoaTransport? = null

    @Volatile
    private var tcp: TcpChannelTransport? = null

    // Read/written from session reader threads, the mic thread and the UI
    // thread; onClosed paths null it from whichever transport thread failed.
    @Volatile
    private var session: CarLifeSession? = null // ---- video ----
    private var codec: MediaCodec? = null

    /**
     * Guards [codec] across threads: video data arrives on the session reader
     * thread while release/setup run on session lifecycle paths. Without this
     * lock a concurrent release left feedDecoder holding a released codec and
     * dequeueInputBuffer threw IllegalStateException, killing the stream.
     */
    private val codecLock = Any()
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

    /** Last successful start parameters; reconnect reuses them verbatim. */
    private var lastMode: Mode = Mode.WIRED
    private var lastWirelessHost: java.net.InetAddress? = null
    private var lastUsePanelSize = false

    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var reconnectAttempt = 0

    /** Throttle for the TOUCH diagnostic log (1 line/sec max). */
    private var lastTouchLogAt = 0L

    /** Guards against the six per-channel onClosed calls stacking reconnects. */
    @Volatile
    private var reconnectScheduled = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start() {
        userStopped = false
        start(Mode.WIRED)
    }

    /** Whether the phone-side canvas follows the user's resolution pick (true)
     *  or the default 1280x720 whitelist size (false). */
    @Volatile
    private var panelSizeRequested = false

    fun start(newMode: Mode, wirelessHost: java.net.InetAddress? = null, usePanelSize: Boolean = false) {
        // An explicit start() is user intent: re-enable auto-reconnect even if
        // a previous stop() on this engine instance disabled it.
        userStopped = false
        panelSizeRequested = usePanelSize
        if (running) return
        running = true
        CarLifeSessionLog.newSession(context)
        mode = newMode
        lastMode = newMode
        lastWirelessHost = wirelessHost
        lastUsePanelSize = usePanelSize
        when (mode) {
            Mode.WIRED -> {
                val transport = AoaTransport(context, this)
                aoa = transport
                session = CarLifeSession(transport, this)
                session?.eventLogger = { CarLifeSessionLog.log(context, "CRYPTO", it) }
                transport.start()
                emitState("scanning")
            }
            Mode.WIRELESS -> {
                val host = wirelessHost ?: run {
                    emitState("wireless", "no host address")
                    running = false
                    return
                }
                val transport = TcpChannelTransport(host, tcpListener)
                tcp = transport
                session = CarLifeSession(transport, this)
                session?.eventLogger = { CarLifeSessionLog.log(context, "CRYPTO", it) }
                transport.start()
                emitState("wireless-connecting", host.hostAddress)
            }
        }
    }

    /** Central state tap: on-device session file + UI callback. */
    private fun emitState(state: String, detail: String? = null) {
        CarLifeSessionLog.log(context, "ENGINE", "$state${detail?.let { " | $it" } ?: ""}")
        uiListener.onEngineState(state, detail)
    }

    fun stop() {
        // Diagnostic: identify unexpected stop() callers (a second activity
        // instance or a late scan callback tearing down a live session showed
        // up live as "engine stopped" mid-streaming).
        CarLifeSessionLog.log(
            context, "ENGINE",
            "stop() from " + Exception().stackTrace.take(4).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
        )
        userStopped = true
        watchdog.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        running = false
        session?.close("engine stopped")
        session = null
        aoa?.stop()
        aoa = null
        tcp?.stop()
        tcp = null
        releaseVideo()
        releaseAudio()
        CarLifeNaviWidget.clear(context)
        emitState("stopped")
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
            // Wireless drops (phone-side resets, wifi hiccups) recover exactly
            // like the wired path - without this the projection dies on the
            // first phone-side reset.
            releaseVideo()
            releaseAudio()
            // Dereference before closing: the surviving channels must not keep
            // feeding (or writing through) a session whose CMD socket is gone.
            session?.close(reason)
            session = null
            if (running && !userStopped) {
                scheduleReconnect(reason)
            } else {
                emitState("disconnected", reason)
            }
        }

        override fun onChannelRetried(channel: Int, reason: String, delayMs: Long) {
            // A single audio/video socket reset healed in place; keep it in the
            // session log for diagnostics without touching engine state.
            CarLifeSessionLog.log(context, "ENGINE", "channel $channel retry in ${delayMs}ms ($reason)")
        }
    }

    // ---- touch / hard keys ----

    fun sendTouch(action: Int, x: Int, y: Int) {
        // Throttled diagnostic: proves touch frames flow (or not) during a test.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTouchLogAt > 1000) {
            lastTouchLogAt = now
            CarLifeSessionLog.log(context, "TOUCH", "action=$action $x,$y")
        }
        session?.sendTouchAction(action, x, y)
    }

    fun sendHardKey(keycode: Int) {
        session?.sendHardKey(keycode)
    }

    fun sendGoToForeground() {
        session?.sendGoToForeground()
    }

    fun sendLaunchMode(serviceType: Int) {
        session?.sendLaunchMode(serviceType)
    }

    // ---- video pause ----

    /**
     * Freezes the projection: H.264 packets keep being consumed (the watchdog
     * must not trip) but are not decoded, so the last frame stays on screen.
     */
    @Volatile
    private var videoPaused = false

    fun setVideoPaused(paused: Boolean) {
        videoPaused = paused
    }

    // ---- AoaTransport.Listener ----

    override fun onBytes(data: ByteArray, offset: Int, length: Int) {
        session?.onBytes(data, offset, length)
    }

    override fun onClosed(reason: String) {
        releaseVideo()
        releaseAudio()
        // Same as the TCP path: the session must not outlive its transport.
        // close() is idempotent, so the re-entrant transport.close() inside it
        // is safe.
        session?.close(reason)
        session = null
        scheduleReconnect(reason)
        emitState("disconnected", reason)
    }

    /**
     * Auto-reconnect with capped backoff (1s, 2s, 4s; max 15s), unless the
     * user stopped the engine. Keeps the projection alive across USB hiccups
     * and phone-side session drops, mirroring DiPlay's connection behaviour.
     */
    private fun scheduleReconnect(reason: String) {
        if (userStopped || !running) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val delayMs = (1000L shl minOf(reconnectAttempt, 4)).coerceAtMost(15_000L)
        reconnectAttempt++
        emitState("reconnecting", "$reason (retry $reconnectAttempt in ${delayMs / 1000}s)")
        reconnectHandler.postDelayed({
            reconnectScheduled = false
            if (userStopped || !running) return@postDelayed
            aoa?.stop()
            aoa = null
            tcp?.stop()
            tcp = null
            session = null
            running = false
            // Preserve the negotiated canvas: without usePanelSize the retry
            // falls back to the 1280x720 whitelist size and the user's
            // resolution pick silently reverts after every reconnect.
            start(lastMode, lastWirelessHost, lastUsePanelSize)
        }, delayMs)
    }

    override fun onState(state: AoaTransport.State, detail: String?) {
        emitState(state.name.lowercase(), detail)
    }

    // ---- CarLifeSession.Listener ----

    override fun onPhase(phase: CarLifeSession.Phase) {
        emitState("phase:${phase.name.lowercase()}")
        when (phase) {
            CarLifeSession.Phase.ENCODER_INIT -> armStreamWatchdog()
            else -> Unit
        }
    }

    /**
     * Live-testing finding: a phone stuck in the "connected" limbo accepts the
     * handshake but never answers VIDEO_ENCODER_INIT (no projection consent),
     * and a granted session can also stop flowing after the phone locks. One
     * rolling watchdog covers both: if no video packet arrived since the last
     * arm/check, drop the session so the reconnect loop retries fresh.
     */
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var videoPacketsSinceArm = 0L

    /**
     * Packets flowing but zero decodes means a dead decoder on a live stream:
     * permanent black video with audio. The watchdog counts both.
     */
    @Volatile
    private var decodedSinceArm = 0L

    private fun armStreamWatchdog() {
        videoPacketsSinceArm = 0
        decodedSinceArm = 0
        scheduleWatchdogCheck()
    }

    private fun scheduleWatchdogCheck() {
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed({
            if (!running) return@postDelayed
            if (videoPaused) {
                // Frame frozen by request: no decodes happen, so the counters
                // cannot move — hold the watchdog instead of reading it, or a
                // long pause reads as a dead stream and kills the session.
                scheduleWatchdogCheck()
                return@postDelayed
            }
            if (videoPacketsSinceArm == 0L || decodedSinceArm == 0L) {
                emitState("watchdog", "no video flowing")
                session?.close("watchdog: no video flowing")
            } else {
                videoPacketsSinceArm = 0
                decodedSinceArm = 0
                scheduleWatchdogCheck()
            }
        }, STREAM_WATCHDOG_MS)
    }

    private fun disarmStreamWatchdog() {
        watchdog.removeCallbacksAndMessages(null)
    }

    override fun onVersionMatch(matchStatus: Int) {
        if (matchStatus == 1) {
            reconnectAttempt = 0
            session?.sendAuthenRequest()
            // Real CarLife waits for the HU introduction before answering the
            // authen challenge (desktop parity, verified sequence).
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
            emitState("connected")
        } else {
            session?.close("version mismatch: $matchStatus")
        }
    }

    override fun onAuthenResponse(response: com.diplay.carlife.core.CarLifeMessages.AuthenResponse) {
        // Strict 4-step order, then feature answer and the encoder proposal;
        // the sequence verified against real CarLife. BT OOB identity goes out
        // only with a real adapter address (real CarLife+ stalls on an empty
        // one; the verified desktop session sent no BT OOB at all).
        session?.sendAuthenResult(true)
        val btAddress = try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address ?: ""
        } catch (e: SecurityException) {
            ""
        }
        if (btAddress.isNotEmpty()) {
            session?.sendHuBtOobInfo(btAddress, android.os.Build.MODEL)
        }
        val p = CarLifePersistence.get(context)
        session?.sendFeatureConfigResponse(
            listOf(CarLifeMessages.FeatureConfig(CarLife.FEATURE_FOCUS_UI, 1)),
        )
        if (panelSizeRequested) {
            session?.sendVideoEncoderInit(p.videoWidth(), p.videoHeight(), p.videoFrameRate())
        } else {
            // 1280x720: whitelist size, renders the full car UI uncropped.
            session?.sendVideoEncoderInit(1280, 720, p.videoFrameRate())
        }
        // Subscribe to the phone's GNSS module (0 = GPS, flag 1 = GCJ-02): the
        // phone then streams CAR_GPS, giving the instruments a real speed.
        session?.sendCarDataSubscribe(listOf(CarLifeMessages.VehicleInfo(MODULE_GPS_DATA, 1, 0)))
        session?.sendCarDataSubscribeStart(MODULE_GPS_DATA)
    }

    override fun onCarGps(gps: CarLifeMessages.CarGps) {
        uiListener.onCarGps(gps)
    }

    override fun onMicRecordStart() {
        if (CarLifePersistence.get(context).micUplink()) startMic()
    }

    override fun onMicRecordEnd() {
        stopMic()
    }

    override fun onMobileInfo(info: CarLifeMessages.DeviceInfo) {
        val name = listOf(info.manufacturer, info.model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (name.isNotEmpty()) {
            CarLifePersistence.get(context).saveLastPhone(name)
            CarLifeSessionLog.log(context, "PHONE", name)
        }
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
        videoPacketsSinceArm++
        if (!videoPaused) feedDecoder(h264, timestamp * 1000L)
    }

    override fun onVideoHeartbeat() = Unit

    /** Music gain while the phone is speaking a turn prompt (0..1). */
    private val naviDuckGain = 0.35f

    /** Set while guidance is active and the user asked for prompt ducking. */
    @Volatile
    private var naviDuckActive = false

    /**
     * Duck the music track while the phone is speaking turn prompts. The TTS
     * track keeps its own gain so the prompt itself stays audible.
     */
    fun duckForNavi(active: Boolean) {
        naviDuckActive = active
        runCatching { musicTrack?.setVolume(if (active) naviDuckGain else 1f) }
    }

    override fun onMusicInit(init: CarLifeMessages.AudioInit) {
        musicTrack = rebuildTrack(musicTrack, init, AudioManager.STREAM_MUSIC)
        if (naviDuckActive) runCatching { musicTrack?.setVolume(naviDuckGain) }
    }

    override fun onMusicData(pcm: ByteArray) {
        lastMusicDataAt = System.currentTimeMillis()
        writePcm(musicTrack, pcm)
    }

    private var lastMusicDataAt = 0L

    /**
     * True while music audio frames are still flowing. The phone sends no
     * MEDIA_PROGRESS_BAR on this build, so audio flow is the only reliable
     * play-state signal — and it is authoritative.
     */
    fun isMusicFlowing(): Boolean = System.currentTimeMillis() - lastMusicDataAt < 2500

    override fun onTtsInit(init: CarLifeMessages.AudioInit) {
        ttsTrack = rebuildTrack(ttsTrack, init, AudioManager.STREAM_MUSIC)
        // Navigation voice has its own volume so prompts can duck under music.
        val gain = (CarLifePersistence.get(context).ttsVolume() / 100f).coerceIn(0f, 1f)
        ttsTrack?.let { t -> runCatching { t.setVolume(gain) } }
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

    override fun onMediaInfo(info: CarLifeMessages.MediaInfo) {
        Log.i("CarLifeLog", "MEDIA: song=${info.song} artist=${info.artist} dur=${info.duration} src=${info.source.take(20)}")
        CarLifeNaviWidget.updateMedia(
            context, info.song.ifBlank { "" }, info.artist.ifBlank { "" },
        )
        uiListener.onMediaInfo(info)
    }

    override fun onMediaProgress(percent: Int) {
        // Observed live: the phone sends a 0..10000 scale (7040 == 70.4%).
        val pct = (percent / 100).coerceIn(0, 100)
        Log.i("CarLifeLog", "MEDIA: progress raw=$percent -> $pct")
        uiListener.onMediaProgress(pct)
    }

    // ---- media play-state mirror (session publish unavailable: stripped jar) ----

    @Volatile
    private var mediaPlaying = false

    /** Phone-side play state, toggled by the head-unit media bar. */
    fun setMediaPlaying(playing: Boolean) {
        mediaPlaying = playing
    }

    override fun onNaviNextTurnInfo(info: CarLifeMessages.NaviNextTurnInfo) {
        CarLifeNaviWidget.updateNavi(
            context, info.roadName, if (info.remainDistance > 0) "${info.remainDistance} m" else "",
        )
        uiListener.onNaviInfo(info)
    }

    override fun onUnknownCmd(id: Int, size: Int) {
        // Unknown mobile-HU CMD (GPS lives in this range), logged so a live
        // capture run identifies the message id for a future decoder.
        CarLifeSessionLog.log(context, "UNKNOWN_CMD", "id=0x%04X size=%d".format(id, size))
    }

    override fun onErrorCode(error: CarLifeMessages.ErrorCode) {
        CarLifeSessionLog.log(context, "PHONE_ERROR", "code=${error.errorCode}")
    }

    override fun onBackground() = Unit

    override fun onDisconnect(reason: String) {
        stopMic()
        releaseVideo()
        releaseAudio()
        // Session-level closes (watchdog trip, phone-side session teardown)
        // previously never reconnected: the engine went permanently silent
        // until a manual reconnect. Re-arm the loop like transport-level
        // closes do; stop() (userStopped) still wins.
        if (running && !userStopped) {
            scheduleReconnect(reason)
        }
        emitState("disconnected", reason)
    }

    override fun onRsaPublicKeyRequest() {
        // Real RSA/AES handshake is now driven inside CarLifeSession via
        // CarLifeCrypto; the engine no longer sends the placeholder public key.
    }

    override fun onAesKeyDelivered(encryptedKey: String) {
        // The AES session key is owned by CarLifeSession now; nothing to do here.
    }

    // ---- video decoder ----

    /**
     * Decoder setup with capability fallback (DiPlay 0.2.12 lesson: some
     * decoders reject unusual canvases): retry at standard sizes, always
     * releasing the codec on partial failure, and report the real canvas.
     */
    private fun setupDecoder() {
        releaseVideo()
        if (surface == null || !surface.isValid) return
        val candidates = linkedSetOf(
            decoderWidth to decoderHeight,
            1280 to 720,
            800 to 480,
        )
        for ((w, h) in candidates) {
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    c.configure(format, surface, null, 0)
                    c.start()
                } catch (e: Exception) {
                    runCatching { c.release() }
                    throw e
                }
                synchronized(codecLock) { codec = c }
                if (w != decoderWidth || h != decoderHeight) {
                    Log.w(TAG, "decoder fallback ${decoderWidth}x${decoderHeight} -> ${w}x${h}")
                    decoderWidth = w
                    decoderHeight = h
                    uiListener.onEncoderSize(w, h)
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "decoder init failed ${w}x${h}", e)
            }
        }
        Log.e(TAG, "decoder setup failed at every fallback size")
    }

    private fun feedDecoder(h264: ByteArray, ptsUs: Long) {
        // Snapshot the codec under the lock: a concurrent releaseVideo() must
        // not leave us operating on a released MediaCodec.
        val c = synchronized(codecLock) { codec } ?: return
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
                decodedSinceArm++
                out = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decode failed", e)
        }
    }

    private fun releaseVideo() {
        // Take ownership under the lock, then release outside it: feedDecoder
        // can never see a codec that is being stopped/released concurrently.
        val old = synchronized(codecLock) {
            val c = codec
            codec = null
            c
        } ?: return
        runCatching { old.stop() }
        runCatching { old.release() }
    }

    // ---- audio ----

    private fun rebuildTrack(current: AudioTrack?, init: CarLifeMessages.AudioInit, stream: Int): AudioTrack? {
        current?.let { runCatching { it.stop(); it.release() } }
        if (stream == AudioManager.STREAM_MUSIC) requestAudioFocus()
        val sampleRate = if (init.sampleRate > 0) init.sampleRate else 16000
        val channels = if (init.channelConfig == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = if (init.sampleFormat == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
            return try {
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
                val track = AudioTrack(
                    stream,
                    sampleRate,
                    channels,
                    encoding,
                    maxOf(minBuf, 64 * 1024),
                    AudioTrack.MODE_STREAM,
                )
                try {
                    track.play()
                } catch (e: Exception) {
                    runCatching { track.release() }
                    throw e
                }
                track
            } catch (e: Exception) {
                Log.e(TAG, "audio track init failed: $init", e)
                null
            }
    }

    /**
     * Non-blocking PCM write (DiPlay 0.2.0 lesson: blocking writes starve the
     * session thread on phone-side stalls, killing handshake and watchdog).
     * When the track buffer is full the remainder is dropped instead.
     */
    private fun writePcm(track: AudioTrack?, pcm: ByteArray) {
        track ?: return
        runCatching {
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        }
    }

    private fun releaseAudio() {
        stopMic()
        abandonAudioFocus()
        listOf(musicTrack, ttsTrack, vrTrack).forEach { t ->
            t?.let { runCatching { it.stop(); it.release() } }
        }
        musicTrack = null
        ttsTrack = null
        vrTrack = null
    }

    /** Volume for the media/TTS tracks, honouring the TTS preference on restore. */
    private fun setMediaVolume(volume: Float) {
        musicTrack?.let {
            runCatching { it.setVolume(if (naviDuckActive) minOf(volume, naviDuckGain) else volume) }
        }
        ttsTrack?.let {
            val gain = if (volume >= 1f) (CarLifePersistence.get(context).ttsVolume() / 100f).coerceIn(0f, 1f) else volume
            runCatching { it.setVolume(gain) }
        }
    }

    /** Last granted focus request; Android requires the same object to release it. */
    private var focusRequest: android.media.AudioFocusRequest? = null

    /** Take media focus so the car's own player ducks/pauses (head-unit correctness). */
    private fun requestAudioFocus() {
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                // Calls/nav prompts duck the projected media, then restore.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(0.18f)
                // Permanent loss (another full-screen app): mute until regained.
                AudioManager.AUDIOFOCUS_LOSS -> setMediaVolume(0.05f)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> setMediaVolume(0.05f)
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(1f)
            }
        }
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonAudioFocus() {
        // abandonAudioFocus(listener) does not match a request made with an
        // AudioFocusRequest — the focus would never actually be given back.
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
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

        /** CarLife car-data module id for the phone GNSS feed. */
        private const val MODULE_GPS_DATA = 0

        /** Encoder-init / video-flow watchdog window (live-testing: stalls). */
        private const val STREAM_WATCHDOG_MS = 12_000L
    }
}
