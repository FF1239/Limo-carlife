package com.diplay.carlife.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HandshakeTest {

    private open class RecordingListener : CarLifeSession.Listener {
        val phases = ArrayList<CarLifeSession.Phase>()
        var versionMatchStatus: Int? = null
        var encoderInitDone: CarLifeMessages.VideoEncoderInfo? = null
        val musicData = ArrayList<ByteArray>()
        val videoData = ArrayList<ByteArray>()
        var background = false

        override fun onPhase(phase: CarLifeSession.Phase) {
            phases.add(phase)
        }

        override fun onVersionMatch(matchStatus: Int) {
            versionMatchStatus = matchStatus
        }

        override fun onAuthenResponse(response: CarLifeMessages.AuthenResponse) = Unit

        override fun onMicRecordStart() = Unit

        override fun onMicRecordEnd() = Unit

        override fun onMobileInfo(info: CarLifeMessages.DeviceInfo) = Unit
        override fun onVideoEncoderInitDone(info: CarLifeMessages.VideoEncoderInfo) {
            encoderInitDone = info
        }

        override fun onVideoData(timestamp: Long, h264: ByteArray) {
            videoData.add(h264)
        }

        override fun onVideoHeartbeat() = Unit
        override fun onMusicInit(init: CarLifeMessages.AudioInit) = Unit
        override fun onMusicData(pcm: ByteArray) {
            musicData.add(pcm)
        }

        override fun onTtsInit(init: CarLifeMessages.AudioInit) = Unit
        override fun onTtsData(pcm: ByteArray) = Unit
        override fun onVrInit(init: CarLifeMessages.AudioInit) = Unit
        override fun onVrData(pcm: ByteArray) = Unit
        override fun onModuleStatus(list: CarLifeMessages.ModuleStatusList) = Unit
        override fun onMediaInfo(info: CarLifeMessages.MediaInfo) = Unit
        override fun onNaviNextTurnInfo(info: CarLifeMessages.NaviNextTurnInfo) = Unit
        override fun onErrorCode(error: CarLifeMessages.ErrorCode) = Unit
        override fun onBackground() {
            background = true
        }

        override fun onDisconnect(reason: String) = Unit
        override fun onRsaPublicKeyRequest() = Unit
        override fun onAesKeyDelivered(encryptedKey: String) = Unit
    }

    /** Feeds a mobile->HU frame into the session as the transport would. */
    private fun CarLifeSession.feedMobile(channel: Int, serviceType: Int, payload: ByteArray, timestamp: Long = 0) {
        onBytes(CarLifeFrame.pack(channel, serviceType.toLong(), payload, timestamp))
    }

    @Test
    fun `handshake golden - first frame is version negotiation`() {
        val transport = LoopbackTransport()
        val session = CarLifeSession(transport, RecordingListener())
        session.startHandshake()

        assertEquals(1, transport.written.size)
        // channel=1, msgLen=12, header serviceType=0x00018001, payload {1,0}
        val expected = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0C,
            0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x80.toByte(), 0x01,
            0x08, 0x01, 0x10, 0x00,
        )
        assertArrayEquals(expected, transport.written[0])
        assertEquals(CarLifeSession.Phase.VERSION_SENT, session.phase)
    }

    @Test
    fun `full handshake to streaming`() {
        val transport = LoopbackTransport()
        val listener = RecordingListener()
        val session = CarLifeSession(transport, listener)

        session.startHandshake()

        // phone: version matched
        session.feedMobile(
            CarLife.CHANNEL_CMD,
            CarLife.Cmd.PROTOCOL_VERSION_MATCH_STATUS,
            CarLifeMessages.ProtocolVersionMatchStatus(1).encode(),
        )
        assertEquals(CarLifeSession.Phase.VERSION_MATCHED, session.phase)
        assertEquals(1, listener.versionMatchStatus)

        // HU: auth + feature config + encoder init/start (official demo order)
        session.sendAuthenRequest()
        session.sendAuthenResult(true)
        session.sendFeatureConfigResponse(listOf(CarLifeMessages.FeatureConfig(CarLife.FEATURE_FOCUS_UI, 1)))
        session.sendVideoEncoderInit(800, 480, 30)

        // phone: encoder init done
        session.feedMobile(
            CarLife.CHANNEL_CMD,
            CarLife.Cmd.VIDEO_ENCODER_INIT_DONE,
            CarLifeMessages.VideoEncoderInfo(800, 480, 30).encode(),
        )
        assertEquals(CarLifeSession.Phase.STREAMING, session.phase)
        assertEquals(CarLifeMessages.VideoEncoderInfo(800, 480, 30), listener.encoderInitDone)

        session.sendVideoEncoderStart()

        // phone: video + music data flow
        session.feedMobile(CarLife.CHANNEL_VIDEO, CarLife.Stream.VIDEO_DATA, byteArrayOf(1, 2, 3), 42L)
        session.feedMobile(CarLife.CHANNEL_MUSIC, CarLife.Stream.MEDIA_DATA, byteArrayOf(4, 5, 6))
        assertEquals(1, listener.videoData.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), listener.videoData[0])
        assertEquals(1, listener.musicData.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), listener.musicData[0])
    }

    @Test
    fun `touch and hard key frames`() {
        val transport = LoopbackTransport()
        val session = CarLifeSession(transport, RecordingListener())
        session.sendTouchAction(CarLife.TOUCH_ACTION_MOVE, 320, 240)
        session.sendHardKey(CarLife.HardKey.SELECTOR_NEXT)

        val touch = CarLifeFrame.Splitter().feed(transport.written[0]).single()
        assertEquals(CarLife.CHANNEL_TOUCH, touch.channel)
        assertEquals(CarLife.Stream.TOUCH_ACTION.toLong(), touch.serviceType)
        assertEquals(CarLifeMessages.TouchAction(2, 320, 240), decodeTouch(touch.payload))

        val key = CarLifeFrame.Splitter().feed(transport.written[1]).single()
        assertEquals(CarLife.Stream.TOUCH_CAR_HARD_KEY_CODE.toLong(), key.serviceType)
    }

    private fun decodeTouch(bytes: ByteArray): CarLifeMessages.TouchAction {
        var action = -1; var x = -1; var y = -1
        val r = ProtoReader(bytes)
        var f = r.nextField()
        while (f != 0) {
            when (f) { 1 -> action = r.readInt32(); 2 -> x = r.readInt32(); 3 -> y = r.readInt32(); else -> r.skip() }
            f = r.nextField()
        }
        return CarLifeMessages.TouchAction(action, x, y)
    }

    @Test
    fun `background notification and mic control`() {
        val transport = LoopbackTransport()
        val listener = RecordingListener()
        val session = CarLifeSession(transport, listener)
        session.feedMobile(CarLife.CHANNEL_CMD, CarLife.Cmd.BACKGROUND, ByteArray(0))
        assertEquals(true, listener.background)

        session.sendMicSelection(useMobileMic = true)
        val frame = CarLifeFrame.Splitter().feed(transport.written[0]).single()
        val list = CarLifeMessages.ModuleStatusList.decode(frame.payload)
        assertEquals(1, list.statuses.size)
        assertEquals(CarLife.Module.MIC, list.statuses[0].moduleId)
        assertEquals(CarLife.MIC_STATUS_USE_MOBILE_MIC, list.statuses[0].statusId)
    }

    // ---- RSA/AES encryption handshake (reference EncryptSetupManager flow) ----

    private fun loadPublicKey(base64: String): java.security.PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(
            java.util.Base64.getDecoder().decode(base64),
        )
        return java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    @Test
    fun `rsa and aes handshake - reply ids, ack, and channel sealing`() {
        val transport = LoopbackTransport()
        val session = CarLifeSession(transport, RecordingListener())

        // phone asks for the HU RSA public key; the answer must carry the
        // RESPONSE id (0x0001806B), not the request id the phone sent
        session.feedMobile(CarLife.CHANNEL_CMD, CarLife.Cmd.MD_RSA_PUBLIC_KEY_REQUEST, ByteArray(0))
        val keyReply = CarLifeFrame.Splitter().feed(transport.written[0]).single()
        assertEquals(CarLife.Cmd.HU_RSA_PUBLIC_KEY_RESPONSE.toLong(), keyReply.serviceType)
        var pubB64 = ""
        val kr = ProtoReader(keyReply.payload)
        var kf = kr.nextField()
        while (kf != 0) {
            when (kf) { 1 -> pubB64 = kr.readString(); else -> kr.skip() }
            kf = kr.nextField()
        }

        // phone seals its AES key with the HU public key (RSA/PKCS1, Base64)
        val aesKey = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, loadPublicKey(pubB64))
        val encrypted = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(aesKey))
        val aesKeyPayload = ProtoWriter().writeString(1, encrypted).toByteArray()
        session.feedMobile(
            CarLife.CHANNEL_CMD,
            CarLife.Cmd.MD_AES_KEY_SEND_REQUEST,
            aesKeyPayload,
        )

        // the phone waits for HU_AES_REC_RESPONSE before trusting the channel;
        // the ACK itself is already sealed with the new key
        val ack = CarLifeFrame.Splitter().feed(transport.written[1]).single()
        assertEquals(CarLife.Cmd.HU_AES_REC_RESPONSE.toLong(), ack.serviceType)
        assertArrayEquals(
            ByteArray(0),
            CarLifeCrypto.aesDecrypt(aesKey, ack.payload),
        )

        // stream channels stay plaintext: VR mic uplink is raw
        session.sendVrData(byteArrayOf(9, 8, 7))
        val vr = CarLifeFrame.Splitter().feed(transport.written[2]).single()
        assertArrayEquals(byteArrayOf(9, 8, 7), vr.payload)

        // CMD/TOUCH are sealed: decrypting the hard key frame yields the message
        session.sendHardKey(CarLife.HardKey.SELECTOR_NEXT)
        val keyFrame = CarLifeFrame.Splitter().feed(transport.written[3]).single()
        assertEquals(CarLife.Stream.TOUCH_CAR_HARD_KEY_CODE.toLong(), keyFrame.serviceType)
        val plain = CarLifeCrypto.aesDecrypt(aesKey, keyFrame.payload)!!
        var keycode = -1
        val r = ProtoReader(plain)
        var f = r.nextField()
        while (f != 0) {
            when (f) { 1 -> keycode = r.readInt32(); else -> r.skip() }
            f = r.nextField()
        }
        assertEquals(CarLife.HardKey.SELECTOR_NEXT, keycode)
    }

    @Test
    fun `md encrypt ready is recognized not unknown`() {
        val listener = object : RecordingListener() {
            var unknownId = -1
            override fun onUnknownCmd(id: Int, size: Int) {
                unknownId = id
            }
        }
        val session = CarLifeSession(LoopbackTransport(), listener)
        session.feedMobile(CarLife.CHANNEL_CMD, CarLife.Cmd.MD_ENCRYPT_READY, ByteArray(0))
        assertEquals(-1, listener.unknownId)
    }
}
