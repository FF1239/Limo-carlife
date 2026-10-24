package com.diplay.carlife.core

import java.security.PrivateKey

/**
 * CarLife HU-side session: drives the 7-phase handshake and dispatches decoded
 * traffic to a listener.
 *
 * Phases (see docs/protocol-notes.md §10):
 *  1 version negotiation  2 device info  3 auth  4 auth result
 *  5 feature negotiation  6 encoder init  7 encoder start
 *
 * The official demo treats auth and stream encryption as optional paths; this
 * implementation always offers the version handshake and honors the encryption
 * request when the mobile asks for it.
 */
class CarLifeSession(
    private val transport: CarLifeTransport,
    private val listener: Listener,
    private val splitter: CarLifeFrame.Splitter = CarLifeFrame.Splitter(),
) {
    interface Listener {
        fun onPhase(phase: Phase)

        fun onVersionMatch(matchStatus: Int)

        /** The phone answered the authen challenge; HU should send its result now. */
        fun onAuthenResponse(response: CarLifeMessages.AuthenResponse)

        /** Phone started the VR mic session (MIC_RECORD_WAKEUP_START / RECOG_START). */
        fun onMicRecordStart()

        /** Phone ended the VR mic session (MIC_RECORD_END). */
        fun onMicRecordEnd()

        fun onMobileInfo(info: CarLifeMessages.DeviceInfo)

        fun onVideoEncoderInitDone(info: CarLifeMessages.VideoEncoderInfo)

        fun onVideoData(timestamp: Long, h264: ByteArray)

        fun onVideoHeartbeat()

        fun onMusicInit(init: CarLifeMessages.AudioInit)

        fun onMusicData(pcm: ByteArray)

        fun onTtsInit(init: CarLifeMessages.AudioInit)

        fun onTtsData(pcm: ByteArray)

        fun onVrInit(init: CarLifeMessages.AudioInit)

        fun onVrData(pcm: ByteArray)

        fun onModuleStatus(list: CarLifeMessages.ModuleStatusList)

        fun onMediaInfo(info: CarLifeMessages.MediaInfo)

        fun onNaviNextTurnInfo(info: CarLifeMessages.NaviNextTurnInfo)

        /** CMD message outside the known set (e.g. GPS) — default no-op. */
        fun onUnknownCmd(id: Int, size: Int) {}

        /** CAR_GPS (mobile -> HU): the phone's GNSS fix, speed in cm/s. */
        fun onCarGps(gps: CarLifeMessages.CarGps) {}

        /** CAR_VELOCITY (mobile -> HU): wheel-speed estimate. */
        fun onCarVelocity(speed: CarLifeMessages.CarSpeed) {}

        /** MEDIA_PROGRESS_BAR (mobile -> HU): playback percent 0..100. */
        fun onMediaProgress(percent: Int) {}

        fun onErrorCode(error: CarLifeMessages.ErrorCode)

        fun onBackground()

        fun onDisconnect(reason: String)

        /** Encryption handshake: mobile asks for the HU RSA public key. */
        fun onRsaPublicKeyRequest()

        /** Encryption handshake: mobile delivered an RSA-encrypted AES key. */
        fun onAesKeyDelivered(encryptedKey: String) {}

        /**
         * Feature-config exchange phase 5, phone-initiated: the phone asks
         * which optional features the HU supports. The HU must answer
         * [sendFeatureConfigResponse] — answering before the request stalls
         * the phone's FeatureConfigManager ("Wait for Feature Config Stop"
         * observed live on a CarWith phone 2026-10-24: the session never
         * reaches video).
         */
        fun onFeatureConfigRequest(configs: List<CarLifeMessages.FeatureConfig>) {}
    }

    enum class Phase {
        IDLE,
        VERSION_SENT,
        VERSION_MATCHED,
        DEVICE_INFO,
        AUTHEN,
        FEATURE_CONFIG,
        ENCODER_INIT,
        STREAMING,
        CLOSED,
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Optional hook: every CMD service type seen, for diagnostics. */
    @Volatile
    var cmdLogger: ((serviceType: Int, payloadLen: Int) -> Unit)? = null

    /** Optional hook: protocol lifecycle notes (RSA/AES handshake) for the session log. */
    @Volatile
    var eventLogger: ((message: String) -> Unit)? = null

    init {
        // Crypto failures used to vanish into null returns; surface them on the
        // session log (reads the current eventLogger on each call, so late
        // assignment still works).
        CarLifeCrypto.logger = { message -> eventLogger?.invoke(message) }
    }

    /** RSA private key for the active session, set on MD_RSA_PUBLIC_KEY_REQUEST. */
    private var rsaPrivateKey: PrivateKey? = null

    /** AES-128 session key (raw bytes) established after MD_AES_KEY_SEND_REQUEST. */
    @Volatile
    private var aesKey: ByteArray? = null

    // ---- Send side ----

    fun startHandshake() {
        sendCmd(
            CarLife.Cmd.HU_PROTOCOL_VERSION,
            CarLifeMessages.ProtocolVersion(
                CarLife.PROTOCOL_VERSION_MAJOR,
                CarLife.PROTOCOL_VERSION_MINOR,
            ).encode(),
        )
        phase = Phase.VERSION_SENT
        listener.onPhase(phase)
    }

    fun sendHuInfo(info: CarLifeMessages.DeviceInfo) {
        sendCmd(CarLife.Cmd.HU_INFO, info.encode())
    }

    /** Phase 3: challenge the phone. The demo value works; phone replies encryptValue. */
    fun sendAuthenRequest(randomValue: String = CarLife.DEFAULT_AUTHEN_RANDOM_VALUE) {
        sendCmd(CarLife.Cmd.HU_AUTHEN_REQUEST, CarLifeMessages.AuthenRequest(randomValue).encode())
        phase = Phase.AUTHEN
        listener.onPhase(phase)
    }

    /** Phase 4: the reference HU accepts the phone unconditionally. */
    fun sendAuthenResult(ok: Boolean = true) {
        sendCmd(CarLife.Cmd.HU_AUTHEN_RESULT, CarLifeMessages.AuthenResult(ok).encode())
    }

    fun sendFeatureConfigResponse(configs: List<CarLifeMessages.FeatureConfig>) {
        sendCmd(CarLife.Cmd.HU_FEATURE_CONFIG_RESPONSE, CarLifeMessages.FeatureConfigList(configs).encode())
    }

    /**
     * HU_BT_OOB_INFO: the head unit announces its Bluetooth identity so the
     * phone can match it against the paired-car record (CarWith verifies this
     * before it commits to a session).
     */
    fun sendHuBtOobInfo(address: String, name: String) {
        val payload = ProtoWriter().apply {
            writeString(1, address)   // CarlifeBTPairInfo.address
            writeString(5, "")        // uuid
            writeString(6, name)      // name
            writeInt32(7, 0)          // status
        }.toByteArray()
        sendCmd(CarLife.Cmd.HU_BT_OOB_INFO, payload)
    }

    fun sendVideoEncoderInit(
        width: Int = CarLife.DEFAULT_VIDEO_WIDTH,
        height: Int = CarLife.DEFAULT_VIDEO_HEIGHT,
        frameRate: Int = CarLife.DEFAULT_VIDEO_FRAME_RATE,
    ) {
        sendCmd(CarLife.Cmd.VIDEO_ENCODER_INIT, CarLifeMessages.VideoEncoderInfo(width, height, frameRate).encode())
        phase = Phase.ENCODER_INIT
        listener.onPhase(phase)
    }

    fun sendVideoEncoderStart() {
        sendCmd(CarLife.Cmd.VIDEO_ENCODER_START, ByteArray(0))
    }

    fun sendVideoEncoderPause() = sendCmd(CarLife.Cmd.VIDEO_ENCODER_PAUSE, ByteArray(0))

    fun sendVideoEncoderReset() = sendCmd(CarLife.Cmd.VIDEO_ENCODER_RESET, ByteArray(0))

    fun sendModuleControl(list: CarLifeMessages.ModuleStatusList) {
        sendCmd(CarLife.Cmd.MODULE_CONTROL, list.encode())
    }

    /** Mic selection: tell the phone whether the vehicle or mobile mic is used. */
    fun sendMicSelection(useMobileMic: Boolean) {
        sendModuleControl(
            CarLifeMessages.ModuleStatusList(
                listOf(
                    CarLifeMessages.ModuleStatus(
                        CarLife.Module.MIC,
                        if (useMobileMic) CarLife.MIC_STATUS_USE_MOBILE_MIC else CarLife.MIC_STATUS_USE_VEHICLE_MIC,
                    ),
                ),
            ),
        )
    }

    /** VR uplink audio (1KB per packet in the reference implementation).
     *  Never sealed: the reference writes VR mic data through the raw byte[]
     *  path — stream channels stay plaintext even when the session is
     *  encrypted (only CMD and TOUCH go through the encrypted path). */
    fun sendVrData(pcm: ByteArray, timestamp: Long = 0) {
        transport.write(
            CarLifeFrame.pack(CarLife.CHANNEL_VR, CarLife.Stream.VR_DATA.toLong(), pcm, timestamp),
        )
    }

    fun sendTouchAction(action: Int, x: Int, y: Int) {
        transport.write(
            CarLifeFrame.pack(
                CarLife.CHANNEL_TOUCH,
                CarLife.Stream.TOUCH_ACTION.toLong(),
                seal(CarLifeMessages.TouchAction(action, x, y).encode()),
            ),
        )
    }

    fun sendHardKey(keycode: Int) {
        transport.write(
            CarLifeFrame.pack(
                CarLife.CHANNEL_TOUCH,
                CarLife.Stream.TOUCH_CAR_HARD_KEY_CODE.toLong(),
                seal(CarLifeMessages.HardKeyCode(keycode).encode()),
            ),
        )
    }

    /** RSA/AES encryption handshake responses. */
    fun sendRsaPublicKey(base64PublicKey: String) {
        // The phone asked for our key; the answer must carry HU_RSA_PUBLIC_KEY_RESPONSE
        // (0x0001806B) — the request id 0x0001006A is phone->HU only and the
        // phone ignores a key delivered under it (reference ConnectSocket flow).
        sendCmd(CarLife.Cmd.HU_RSA_PUBLIC_KEY_RESPONSE, CarLifeMessages.RsaPublicKey(base64PublicKey).encode())
    }

    // ---- RSA/AES session crypto (real CarLife path) ----

    private fun handleRsaPublicKeyRequest() {
        val (pub, priv) = CarLifeCrypto.ensureKeyPair()
        rsaPrivateKey = priv
        sendRsaPublicKey(pub)
        eventLogger?.invoke("rsa public key served (${pub.length} b64 chars)")
    }

    private fun handleAesKeyDelivered(encryptedKey: String) {
        val priv = rsaPrivateKey ?: run {
            eventLogger?.invoke("aes key delivered but no rsa private key held")
            return
        }
        val key = CarLifeCrypto.rsaDecryptToBytes(priv, encryptedKey)
        if (key == null) {
            eventLogger?.invoke("aes session key rsa decrypt failed (${encryptedKey.length} b64 chars)")
            return
        }
        aesKey = key
        eventLogger?.invoke("aes session key established (${key.size} bytes); cmd/touch sealed")
        // ACK the phone before it flips its own encrypt switch: the reference HU
        // answers HU_AES_REC_RESPONSE immediately after storing the key; without
        // it the phone keeps expecting plaintext while we already seal, and
        // every HU->phone CMD (hard keys, launch modes) is silently discarded.
        sendCmd(CarLife.Cmd.HU_AES_REC_RESPONSE, ByteArray(0))
    }

    /** Encrypt an outbound payload once the AES session key exists. */
    private fun seal(payload: ByteArray): ByteArray =
        aesKey?.let { CarLifeCrypto.aesEncrypt(it, payload) } ?: payload

    /** Decrypt an inbound payload; falls back to the original on any failure
     *  (e.g. a channel the phone left unencrypted). */
    private fun unseal(payload: ByteArray): ByteArray =
        aesKey?.let { CarLifeCrypto.aesDecrypt(it, payload) } ?: payload

    /**
     * Subscribes to the phone's car-data modules (0 = GPS). The phone answers
     * CAR_DATA_SUBSCRIBE_DONE and then streams CAR_GPS while it has a fix.
     */
    fun sendCarDataSubscribe(items: List<CarLifeMessages.VehicleInfo>) {
        sendCmd(CarLife.Cmd.CAR_DATA_SUBSCRIBE, CarLifeMessages.VehicleInfoList(items.size, items).encode())
    }

    fun sendCarDataSubscribeStart(moduleId: Int, flag: Int = 1, frequency: Int = 0) {
        sendCmd(CarLife.Cmd.CAR_DATA_SUBSCRIBE_START, CarLifeMessages.VehicleInfo(moduleId, flag, frequency).encode())
    }

    /** Asks the phone to come to the foreground (home screen of CarLife). */
    fun sendGoToForeground() = sendCmd(CarLife.Cmd.GO_TO_FOREGROUND, ByteArray(0))

    /**
     * Asks the phone to open a specific app view; [serviceType] is one of
     * CarLife.Cmd.LAUNCH_MODE_* (MAP / MUSIC / PHONE).
     */
    fun sendLaunchMode(serviceType: Int) = sendCmd(serviceType, ByteArray(0))

    fun close(reason: String = "closed") {
        // Idempotent: protocol errors, the watchdog and transport teardown can
        // all race here — onDisconnect must fire exactly once.
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        // Record whether the session was ever encrypted: a session closed with
        // aes established=false means the phone never delivered a key — the
        // single most useful line when debugging a broken handshake on a car
        // unit (unencrypted sessions are protocol-legal but suspicious).
        eventLogger?.invoke("session closed ($reason); aes established=${aesKey != null}")
        listener.onDisconnect(reason)
        transport.close()
    }

    // ---- Receive side ----

    /** Feed incoming transport bytes; called by the transport read loop. */
    fun onBytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        // Channels arrive on their own threads; the splitter state is shared.
        val frames = synchronized(splitter) {
            try {
                splitter.feed(data, offset, length)
            } catch (e: ProtocolException) {
                close("frame error: ${e.message}")
                return
            }
        }
        for (f in frames) dispatch(f)
    }

    private fun dispatch(f: CarLifeFrame.Parsed) {
        // Only the CMD channel is encrypted in both directions (reference
        // EncryptSetupManager/AESManager guard the CarlifeCmdMessage paths);
        // video/music/tts/vr arrive plaintext, so a per-frame unseal attempt
        // there would just burn a failed PKCS5 decrypt on every frame.
        val p = if (f.channel == CarLife.CHANNEL_CMD) unseal(f.payload) else f.payload
        when (f.channel) {
            CarLife.CHANNEL_CMD -> dispatchCmd(f.serviceType, p)
            CarLife.CHANNEL_VIDEO -> when (f.serviceType.toInt()) {
                CarLife.Stream.VIDEO_DATA -> listener.onVideoData(f.timestamp, p)
                CarLife.Stream.VIDEO_HEARTBEAT -> listener.onVideoHeartbeat()
            }
            CarLife.CHANNEL_MUSIC -> when (f.serviceType.toInt()) {
                CarLife.Stream.MEDIA_INIT -> listener.onMusicInit(CarLifeMessages.AudioInit.decode(p))
                CarLife.Stream.MEDIA_DATA -> listener.onMusicData(p)
            }
            CarLife.CHANNEL_TTS -> when (f.serviceType.toInt()) {
                CarLife.Stream.NAVI_TTS_INIT -> listener.onTtsInit(CarLifeMessages.AudioInit.decode(p))
                CarLife.Stream.NAVI_TTS_DATA -> listener.onTtsData(p)
            }
            CarLife.CHANNEL_VR -> when (f.serviceType.toInt()) {
                CarLife.Stream.VR_AUDIO_INIT -> listener.onVrInit(CarLifeMessages.AudioInit.decode(p))
                CarLife.Stream.VR_AUDIO_DATA -> listener.onVrData(p)
            }
            else -> Unit // TOUCH carries no mobile->HU traffic we care about
        }
    }

    private fun dispatchCmd(serviceType: Long, payload: ByteArray) {
        cmdLogger?.invoke(serviceType.toInt(), payload.size)
        when (serviceType.toInt()) {
            CarLife.Cmd.PROTOCOL_VERSION_MATCH_STATUS -> {
                val s = CarLifeMessages.ProtocolVersionMatchStatus.decode(payload)
                phase = Phase.VERSION_MATCHED
                listener.onPhase(phase)
                listener.onVersionMatch(s.matchStatus)
            }
            CarLife.Cmd.MD_INFO -> listener.onMobileInfo(CarLifeMessages.DeviceInfo.decode(payload))
            CarLife.Cmd.MD_AUTHEN_RESPONSE -> {
                phase = Phase.FEATURE_CONFIG
                listener.onAuthenResponse(CarLifeMessages.AuthenResponse.decode(payload))
            }
            CarLife.Cmd.MD_FEATURE_CONFIG_REQUEST -> {
                phase = Phase.FEATURE_CONFIG
                listener.onPhase(phase)
            }
            CarLife.Cmd.VIDEO_ENCODER_INIT_DONE -> {
                listener.onVideoEncoderInitDone(CarLifeMessages.VideoEncoderInfo.decode(payload))
                phase = Phase.STREAMING
                listener.onPhase(phase)
            }
            CarLife.Cmd.MODULE_STATUS -> listener.onModuleStatus(CarLifeMessages.ModuleStatusList.decode(payload))
            CarLife.Cmd.MEDIA_INFO -> listener.onMediaInfo(CarLifeMessages.MediaInfo.decode(payload))
            CarLife.Cmd.MEDIA_PROGRESS_BAR ->
                listener.onMediaProgress(CarLifeMessages.MediaProgressBar.decode(payload).progressBar)
            CarLife.Cmd.NAVI_NEXT_TURN_INFO ->
                listener.onNaviNextTurnInfo(CarLifeMessages.NaviNextTurnInfo.decode(payload))
            CarLife.Cmd.ERROR_CODE -> listener.onErrorCode(CarLifeMessages.ErrorCode.decode(payload))
            CarLife.Cmd.BACKGROUND -> listener.onBackground()
            // One-way phone notifications (no payload, no reply expected);
            // recognizing them keeps the log free of misleading UNKNOWN_CMD.
            CarLife.Cmd.SCREEN_ON, CarLife.Cmd.FOREGROUND -> Unit
            CarLife.Cmd.MIC_RECORD_WAKEUP_START, CarLife.Cmd.MIC_RECORD_RECOG_START ->
                listener.onMicRecordStart()
            CarLife.Cmd.MIC_RECORD_END -> listener.onMicRecordEnd()
            CarLife.Cmd.MD_RSA_PUBLIC_KEY_REQUEST -> handleRsaPublicKeyRequest()
            CarLife.Cmd.MD_AES_KEY_SEND_REQUEST -> handleAesKeyDelivered(CarLifeMessages.AesKey.decode(payload).aesKey)
            // Phone confirms the encrypted channel is up. The reference HU
            // (re)starts the video codec negotiation here; this build already
            // proposed the canvas at authen time and the phone accepted it.
            CarLife.Cmd.MD_ENCRYPT_READY -> eventLogger?.invoke("md encrypt ready")
            CarLife.Cmd.CAR_GPS -> listener.onCarGps(CarLifeMessages.CarGps.decode(payload))
            CarLife.Cmd.CAR_VELOCITY -> listener.onCarVelocity(CarLifeMessages.CarSpeed.decode(payload))
            else -> listener.onUnknownCmd(serviceType.toInt(), payload.size)
        }
    }

    private fun sendCmd(serviceType: Int, payload: ByteArray) {
        transport.write(CarLifeFrame.pack(CarLife.CHANNEL_CMD, serviceType.toLong(), seal(payload)))
    }
}
