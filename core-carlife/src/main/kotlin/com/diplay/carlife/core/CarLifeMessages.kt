package com.diplay.carlife.core

/**
 * CarLife protobuf messages, field numbers matching the Baidu
 * CarlifeXxxProto.java generated sources (protobuf 2.x, all fields scalar).
 *
 * Only the messages the head-unit side actually sends or consumes are modeled.
 */
object CarLifeMessages {

    // ---- CMD channel ----

    data class ProtocolVersion(val major: Int, val minor: Int) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, major)
            writeInt32(2, minor)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): ProtocolVersion {
                var major = 0; var minor = 0
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    when (f) { 1 -> major = r.readInt32(); 2 -> minor = r.readInt32(); else -> r.skip() }
                    f = r.nextField()
                }
                return ProtocolVersion(major, minor)
            }
        }
    }

    data class ProtocolVersionMatchStatus(val matchStatus: Int) {
        fun encode(): ByteArray = ProtoWriter().writeInt32(1, matchStatus).toByteArray()

        companion object {
            fun decode(bytes: ByteArray): ProtocolVersionMatchStatus {
                var status = 0
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    if (f == 1) status = r.readInt32() else r.skip()
                    f = r.nextField()
                }
                return ProtocolVersionMatchStatus(status)
            }
        }
    }

    /** HU_INFO / MD_INFO payload. */
    data class DeviceInfo(
        val os: String = "",
        val model: String = "",
        val brand: String = "",
        val manufacturer: String = "",
        val board: String = "",
        val device: String = "",
        val display: String = "",
        val fingerprint: String = "",
        val hardware: String = "",
        val host: String = "",
        val cid: String = "",
        val product: String = "",
        val serial: String = "",
        val codename: String = "",
        val incremental: String = "",
        val release: String = "",
        val sdk: String = "",
        val sdkInt: Int = 0,
        val bootloader: String = "",
        val cpuAbi: String = "",
        val cpuAbi2: String = "",
        val token: String = "",
        val btAddress: String = "",
    ) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeString(1, os)
            writeString(2, board)
            writeString(3, bootloader)
            writeString(4, brand)
            writeString(5, cpuAbi)
            writeString(6, cpuAbi2)
            writeString(7, device)
            writeString(8, display)
            writeString(9, fingerprint)
            writeString(10, hardware)
            writeString(11, host)
            writeString(12, cid)
            writeString(13, manufacturer)
            writeString(14, model)
            writeString(15, product)
            writeString(16, serial)
            writeString(17, codename)
            writeString(18, incremental)
            writeString(19, release)
            writeString(20, sdk)
            writeInt32(21, sdkInt)
            writeString(22, token)
            writeString(23, btAddress)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): DeviceInfo {
                var m = DeviceInfo()
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    m = when (f) {
                        1 -> m.copy(os = r.readString())
                        2 -> m.copy(board = r.readString())
                        3 -> m.copy(bootloader = r.readString())
                        4 -> m.copy(brand = r.readString())
                        5 -> m.copy(cpuAbi = r.readString())
                        6 -> m.copy(cpuAbi2 = r.readString())
                        7 -> m.copy(device = r.readString())
                        8 -> m.copy(display = r.readString())
                        9 -> m.copy(fingerprint = r.readString())
                        10 -> m.copy(hardware = r.readString())
                        11 -> m.copy(host = r.readString())
                        12 -> m.copy(cid = r.readString())
                        13 -> m.copy(manufacturer = r.readString())
                        14 -> m.copy(model = r.readString())
                        15 -> m.copy(product = r.readString())
                        16 -> m.copy(serial = r.readString())
                        17 -> m.copy(codename = r.readString())
                        18 -> m.copy(incremental = r.readString())
                        19 -> m.copy(release = r.readString())
                        20 -> m.copy(sdk = r.readString())
                        21 -> m.copy(sdkInt = r.readInt32())
                        22 -> m.copy(token = r.readString())
                        23 -> m.copy(btAddress = r.readString())
                        else -> m.also { r.skip() }
                    }
                    f = r.nextField()
                }
                return m
            }
        }
    }

    data class AuthenRequest(val randomValue: String) {
        fun encode(): ByteArray = ProtoWriter().writeString(1, randomValue).toByteArray()

        companion object {
            fun decode(bytes: ByteArray): AuthenRequest {
                var v = ""
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    if (f == 1) v = r.readString() else r.skip()
                    f = r.nextField()
                }
                return AuthenRequest(v)
            }
        }
    }

    data class AuthenResponse(val encryptValue: String) {
        fun encode(): ByteArray = ProtoWriter().writeString(1, encryptValue).toByteArray()

        companion object {
            fun decode(bytes: ByteArray): AuthenResponse {
                var v = ""
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    if (f == 1) v = r.readString() else r.skip()
                    f = r.nextField()
                }
                return AuthenResponse(v)
            }
        }
    }

    data class AuthenResult(val authenResult: Boolean) {
        fun encode(): ByteArray = ProtoWriter().writeBool(1, authenResult).toByteArray()

        companion object {
            fun decode(bytes: ByteArray): AuthenResult {
                var v = false
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    if (f == 1) v = r.readBool() else r.skip()
                    f = r.nextField()
                }
                return AuthenResult(v)
            }
        }
    }

    data class VideoEncoderInfo(val width: Int, val height: Int, val frameRate: Int) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, width)
            writeInt32(2, height)
            writeInt32(3, frameRate)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): VideoEncoderInfo {
                var w = 0; var h = 0; var fps = 0
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    when (f) { 1 -> w = r.readInt32(); 2 -> h = r.readInt32(); 3 -> fps = r.readInt32(); else -> r.skip() }
                    f = r.nextField()
                }
                return VideoEncoderInfo(w, h, fps)
            }
        }
    }

    data class VideoFrameRate(val frameRate: Int) {
        fun encode(): ByteArray = ProtoWriter().writeInt32(1, frameRate).toByteArray()
    }

    data class FeatureConfig(val key: String, val value: Int) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeString(1, key)
            writeInt32(2, value)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): FeatureConfig {
                var k = ""; var v = 0
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    when (f) { 1 -> k = r.readString(); 2 -> v = r.readInt32(); else -> r.skip() }
                    f = r.nextField()
                }
                return FeatureConfig(k, v)
            }
        }
    }

    /** HU_FEATURE_CONFIG_RESPONSE payload. */
    data class FeatureConfigList(val configs: List<FeatureConfig>) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, configs.size)
            configs.forEach { writeMessage(2, it.encode()) }
        }.toByteArray()
    }

    data class ModuleStatus(val moduleId: Int, val statusId: Int) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, moduleId)
            writeInt32(2, statusId)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): ModuleStatus {
                var id = 0; var status = 0
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    when (f) { 1 -> id = r.readInt32(); 2 -> status = r.readInt32(); else -> r.skip() }
                    f = r.nextField()
                }
                return ModuleStatus(id, status)
            }
        }
    }

    /** MODULE_STATUS (mobile -> HU) and MODULE_CONTROL (HU -> mobile) payload. */
    data class ModuleStatusList(val statuses: List<ModuleStatus>) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, statuses.size)
            statuses.forEach { writeMessage(2, it.encode()) }
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): ModuleStatusList {
                val list = ArrayList<ModuleStatus>()
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    when (f) {
                        2 -> list.add(ModuleStatus.decode(r.readBytes()))
                        else -> r.skip()
                    }
                    f = r.nextField()
                }
                return ModuleStatusList(list)
            }
        }
    }

    /** Error string reported via ERROR_CODE. */
    data class ErrorCode(val errorCode: String) {
        companion object {
            fun decode(bytes: ByteArray): ErrorCode {
                var v = ""
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    if (f == 1) v = r.readString() else r.skip()
                    f = r.nextField()
                }
                return ErrorCode(v)
            }
        }
    }

    /** RSA/AES key-exchange payloads (encryption handshake). */
    data class RsaPublicKey(val rsaPublicKey: String) {
        fun encode(): ByteArray = ProtoWriter().writeString(1, rsaPublicKey).toByteArray()
    }

    data class AesKey(val aesKey: String) {
        companion object {
            fun decode(bytes: ByteArray): AesKey {
                var v = ""
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    if (f == 1) v = r.readString() else r.skip()
                    f = r.nextField()
                }
                return AesKey(v)
            }
        }
    }

    // ---- Stream channels ----

    /** MEDIA_INIT / NAVI_TTS_INIT / VR_AUDIO_INIT payload. */
    data class AudioInit(val sampleRate: Int, val channelConfig: Int, val sampleFormat: Int) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, sampleRate)
            writeInt32(2, channelConfig)
            writeInt32(3, sampleFormat)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): AudioInit {
                var sr = 0; var ch = 0; var fmt = 0
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    when (f) { 1 -> sr = r.readInt32(); 2 -> ch = r.readInt32(); 3 -> fmt = r.readInt32(); else -> r.skip() }
                    f = r.nextField()
                }
                return AudioInit(sr, ch, fmt)
            }
        }
    }

    /** TOUCH_ACTION payload. */
    data class TouchAction(val action: Int, val x: Int, val y: Int) {
        fun encode(): ByteArray = ProtoWriter().apply {
            writeInt32(1, action)
            writeInt32(2, x)
            writeInt32(3, y)
        }.toByteArray()
    }

    /** TOUCH_CAR_HARD_KEY_CODE payload. */
    data class HardKeyCode(val keycode: Int) {
        fun encode(): ByteArray = ProtoWriter().writeInt32(1, keycode).toByteArray()
    }

    /** MEDIA_INFO payload (mobile -> HU, for the HUD/cluster widgets). */
    data class MediaInfo(
        val source: String,
        val song: String,
        val artist: String,
        val album: String,
        val duration: Int,
        val mode: Int,
    ) {
        companion object {
            fun decode(bytes: ByteArray): MediaInfo {
                var m = MediaInfo("", "", "", "", 0, 0)
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    m = when (f) {
                        1 -> m.copy(source = r.readString())
                        2 -> m.copy(song = r.readString())
                        3 -> m.copy(artist = r.readString())
                        4 -> m.copy(album = r.readString())
                        6 -> m.copy(duration = r.readInt32())
                        9 -> m.copy(mode = r.readInt32())
                        else -> m.also { r.skip() }
                    }
                    f = r.nextField()
                }
                return m
            }
        }
    }

    /** NAVI_NEXT_TURN_INFO payload (mobile -> HU). */
    data class NaviNextTurnInfo(
        val action: Int,
        val nextTurn: Int,
        val roadName: String,
        val totalDistance: Int,
        val remainDistance: Int,
    ) {
        companion object {
            fun decode(bytes: ByteArray): NaviNextTurnInfo {
                var m = NaviNextTurnInfo(0, 0, "", 0, 0)
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    m = when (f) {
                        1 -> m.copy(action = r.readInt32())
                        2 -> m.copy(nextTurn = r.readInt32())
                        3 -> m.copy(roadName = r.readString())
                        4 -> m.copy(totalDistance = r.readInt32())
                        5 -> m.copy(remainDistance = r.readInt32())
                        else -> m.also { r.skip() }
                    }
                    f = r.nextField()
                }
                return m
            }
        }
    }

    /**
     * CAR_GPS payload (mobile -> HU): the phone's own GNSS fix. Field numbers
     * from the Baidu CarlifeCarGpsProto; speed is cm/s (Location.speed * 100
     * in the reference app), lat/lon are GCJ-02 scaled ints, heading degrees.
     */
    data class CarGps(
        val latitude: Int,
        val longitude: Int,
        val speedCms: Int,
        val heading: Int,
        val fix: Int,
        val satsUsed: Int,
        val timestampSec: Long,
    ) {
        companion object {
            fun decode(bytes: ByteArray): CarGps {
                var m = CarGps(0, 0, 0, 0, 0, 0, 0)
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    m = when (f) {
                        3 -> m.copy(latitude = r.readInt32())
                        4 -> m.copy(longitude = r.readInt32())
                        6 -> m.copy(speedCms = r.readUint32().toInt())
                        7 -> m.copy(heading = r.readUint32().toInt())
                        14 -> m.copy(fix = r.readUint32().toInt())
                        18 -> m.copy(satsUsed = r.readUint32().toInt())
                        25 -> m.copy(timestampSec = r.readUint64())
                        else -> m.also { r.skip() }
                    }
                    f = r.nextField()
                }
                return m
            }
        }
    }

    /** CAR_VELOCITY payload (mobile -> HU): wheel-speed estimate. */
    data class CarSpeed(val speed: Int, val timestamp: Long) {
        companion object {
            fun decode(bytes: ByteArray): CarSpeed {
                var m = CarSpeed(0, 0)
                val r = ProtoReader(bytes)
                var f = r.nextField()
                while (f != 0) {
                    m = when (f) {
                        1 -> m.copy(speed = r.readUint32().toInt())
                        2 -> m.copy(timestamp = r.readUint64())
                        else -> m.also { r.skip() }
                    }
                    f = r.nextField()
                }
                return m
            }
        }
    }

    /** One car-data subscription entry (module / format / frequency). */
    data class VehicleInfo(val moduleId: Int, val flag: Int, val frequency: Int) {
        fun encode(): ByteArray = ProtoWriter()
            .writeInt32(1, moduleId)
            .writeInt32(2, flag)
            .writeInt32(3, frequency)
            .toByteArray()
    }

    /** CAR_DATA_SUBSCRIBE payload: entry count + repeated [VehicleInfo]. */
    data class VehicleInfoList(val cnt: Int, val items: List<VehicleInfo>) {
        fun encode(): ByteArray {
            val w = ProtoWriter().writeInt32(1, cnt)
            items.forEach { w.writeMessage(2, it.encode()) }
            return w.toByteArray()
        }
    }
}
