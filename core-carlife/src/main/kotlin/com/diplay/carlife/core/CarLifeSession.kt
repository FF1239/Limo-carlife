package com.diplay.carlife.core

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

        fun onErrorCode(error: CarLifeMessages.ErrorCode)

        fun onBackground()

        fun onDisconnect(reason: String)

        /** Encryption handshake: mobile asks for the HU RSA public key. */
        fun onRsaPublicKeyRequest()

        /** Encryption handshake: mobile delivered an RSA-encrypted AES key. */
        fun onAesKeyDelivered(encryptedKey: String)
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

    /** VR uplink audio (1KB per packet in the reference implementation). */
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
                CarLifeMessages.TouchAction(action, x, y).encode(),
            ),
        )
    }

    fun sendHardKey(keycode: Int) {
        transport.write(
            CarLifeFrame.pack(
                CarLife.CHANNEL_TOUCH,
                CarLife.Stream.TOUCH_CAR_HARD_KEY_CODE.toLong(),
                CarLifeMessages.HardKeyCode(keycode).encode(),
            ),
        )
    }

    /** RSA/AES encryption handshake responses. */
    fun sendRsaPublicKey(base64PublicKey: String) {
        sendCmd(CarLife.Cmd.MD_RSA_PUBLIC_KEY_REQUEST, CarLifeMessages.RsaPublicKey(base64PublicKey).encode())
    }

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

    fun close(reason: String = "closed") {
        phase = Phase.CLOSED
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
        when (f.channel) {
            CarLife.CHANNEL_CMD -> dispatchCmd(f.serviceType, f.payload)
            CarLife.CHANNEL_VIDEO -> when (f.serviceType.toInt()) {
                CarLife.Stream.VIDEO_DATA -> listener.onVideoData(f.timestamp, f.payload)
                CarLife.Stream.VIDEO_HEARTBEAT -> listener.onVideoHeartbeat()
            }
            CarLife.CHANNEL_MUSIC -> when (f.serviceType.toInt()) {
                CarLife.Stream.MEDIA_INIT -> listener.onMusicInit(CarLifeMessages.AudioInit.decode(f.payload))
                CarLife.Stream.MEDIA_DATA -> listener.onMusicData(f.payload)
            }
            CarLife.CHANNEL_TTS -> when (f.serviceType.toInt()) {
                CarLife.Stream.NAVI_TTS_INIT -> listener.onTtsInit(CarLifeMessages.AudioInit.decode(f.payload))
                CarLife.Stream.NAVI_TTS_DATA -> listener.onTtsData(f.payload)
            }
            CarLife.CHANNEL_VR -> when (f.serviceType.toInt()) {
                CarLife.Stream.VR_AUDIO_INIT -> listener.onVrInit(CarLifeMessages.AudioInit.decode(f.payload))
                CarLife.Stream.VR_AUDIO_DATA -> listener.onVrData(f.payload)
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
            CarLife.Cmd.NAVI_NEXT_TURN_INFO ->
                listener.onNaviNextTurnInfo(CarLifeMessages.NaviNextTurnInfo.decode(payload))
            CarLife.Cmd.ERROR_CODE -> listener.onErrorCode(CarLifeMessages.ErrorCode.decode(payload))
            CarLife.Cmd.BACKGROUND -> listener.onBackground()
            CarLife.Cmd.MIC_RECORD_WAKEUP_START, CarLife.Cmd.MIC_RECORD_RECOG_START ->
                listener.onMicRecordStart()
            CarLife.Cmd.MIC_RECORD_END -> listener.onMicRecordEnd()
            CarLife.Cmd.MD_RSA_PUBLIC_KEY_REQUEST -> listener.onRsaPublicKeyRequest()
            CarLife.Cmd.MD_AES_KEY_SEND_REQUEST -> listener.onAesKeyDelivered(CarLifeMessages.AesKey.decode(payload).aesKey)
            CarLife.Cmd.CAR_GPS -> listener.onCarGps(CarLifeMessages.CarGps.decode(payload))
            CarLife.Cmd.CAR_VELOCITY -> listener.onCarVelocity(CarLifeMessages.CarSpeed.decode(payload))
            else -> listener.onUnknownCmd(serviceType.toInt(), payload.size)
        }
    }

    private fun sendCmd(serviceType: Int, payload: ByteArray) {
        transport.write(CarLifeFrame.pack(CarLife.CHANNEL_CMD, serviceType.toLong(), payload))
    }
}
