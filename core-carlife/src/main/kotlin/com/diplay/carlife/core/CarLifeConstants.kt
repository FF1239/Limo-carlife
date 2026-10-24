package com.diplay.carlife.core

/**
 * CarLife protocol constants.
 *
 * Verified against:
 *  - carlife-protocol-analysis-tool common_constant.py (service types, hard keys, module states)
 *  - Baidu CarLife-Android-Vehicle CommonParams.java (encryption message IDs)
 *  - Baidu CarLife-Vehicle-Lib CConnectManager.h (channel TCP ports)
 *  - Baidu CarLife-Android-Vehicle AOAHostSetup.java (AOA identity strings)
 */
object CarLife {

    /** Transport channels. */
    const val CHANNEL_CMD = 1
    const val CHANNEL_VIDEO = 2
    const val CHANNEL_MUSIC = 3
    const val CHANNEL_TTS = 4
    const val CHANNEL_VR = 5
    const val CHANNEL_TOUCH = 6

    const val CHANNEL_MIN = CHANNEL_CMD
    const val CHANNEL_MAX = CHANNEL_TOUCH

    /** Transport header: [u32 channel][u32 msgLen]; msgLen = channel header + payload. */
    const val TRANSPORT_HEADER_SIZE = 8

    /** CMD / TOUCH channel header: [u16 payloadLen][u16 reserved][u32 serviceType]. */
    const val CHANNEL_HEADER_SIZE_CMD = 8

    /** VIDEO / MUSIC / TTS / VR channel header: [u32 payloadLen][u32 timestamp][u32 serviceType]. */
    const val CHANNEL_HEADER_SIZE_STREAM = 12

    /** Max value accepted for the transport msgLen field (Baidu Java bridge uses 64MB). */
    const val MAX_MSG_BYTES = 64 * 1024 * 1024

    /** Local TCP ports the head unit listens on per channel (CConnectManager.h). */
    const val HU_PORT_CMD = 7200
    const val HU_PORT_VIDEO = 8200
    const val HU_PORT_MUSIC = 9200
    const val HU_PORT_TTS = 9201
    const val HU_PORT_VR = 9202
    const val HU_PORT_TOUCH = 9300

    /** Ports the mobile device listens on in wireless TCP mode. */
    const val MD_PORT_CMD = 7240
    const val MD_PORT_VIDEO = 8240
    const val MD_PORT_MUSIC = 9240
    const val MD_PORT_TTS = 9241
    const val MD_PORT_VR = 9242
    const val MD_PORT_TOUCH = 9340

    /** CMD-channel service types; bit15 set = HU -> MD direction. */
    object Cmd {
        const val HU_PROTOCOL_VERSION = 0x00018001
        const val PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
        const val HU_INFO = 0x00018003
        const val MD_INFO = 0x00010004
        const val HU_BT_OOB_INFO = 0x00018005
        const val MD_BT_OOB_INFO = 0x00010006
        const val VIDEO_ENCODER_INIT = 0x00018007
        const val VIDEO_ENCODER_INIT_DONE = 0x00010008
        const val VIDEO_ENCODER_START = 0x00018009
        const val VIDEO_ENCODER_PAUSE = 0x0001800A
        const val VIDEO_ENCODER_RESET = 0x0001800B
        const val VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C
        const val VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D
        const val CAR_VELOCITY = 0x0001800F
        const val CAR_GPS = 0x00018010
        const val CAR_GYROSCOPE = 0x00018011
        const val CAR_ACCELERATION = 0x00018012
        const val CAR_OIL = 0x00018013
        const val SCREEN_ON = 0x00010018
        const val SCREEN_OFF = 0x00010019
        const val SCREEN_USERPRESENT = 0x0001001A
        const val FOREGROUND = 0x0001001B
        const val BACKGROUND = 0x0001001C
        const val LAUNCH_MODE_NORMAL = 0x0001801D
        const val LAUNCH_MODE_PHONE = 0x0001801E
        const val LAUNCH_MODE_MAP = 0x0001801F
        const val LAUNCH_MODE_MUSIC = 0x00018020
        const val GO_TO_DESKTOP = 0x00010021
        const val MIC_RECORD_WAKEUP_START = 0x00010022
        const val MIC_RECORD_END = 0x00010023
        const val MIC_RECORD_RECOG_START = 0x00010024
        const val GO_TO_FOREGROUND = 0x00018025
        const val MODULE_STATUS = 0x00010026
        const val STATISTIC_INFO = 0x00018027
        const val MODULE_CONTROL = 0x00018028
        const val CAR_DATA_GEAR = 0x00018029
        const val NAVI_NEXT_TURN_INFO = 0x00010030
        const val CAR_DATA_SUBSCRIBE = 0x00010031
        const val CAR_DATA_SUBSCRIBE_DONE = 0x00018032
        const val CAR_DATA_SUBSCRIBE_START = 0x00010033
        const val CAR_DATA_SUBSCRIBE_STOP = 0x00010034
        const val MEDIA_INFO = 0x00010035
        const val MEDIA_PROGRESS_BAR = 0x00010036
        const val CONNECT_EXCEPTION = 0x00010037
        const val REQUEST_GO_TO_FOREGROUND = 0x00010038
        const val UI_ACTION_SOUND = 0x00010039
        const val BT_HFP_REQUEST = 0x00010040
        const val BT_HFP_INDICATION = 0x00018041
        const val BT_HFP_CONNECTION = 0x00018042
        const val CARLIFE_DATA_SUBSCRIBE = 0x00018043
        const val CARLIFE_DATA_SUBSCRIBE_DONE = 0x00010044
        const val CARLIFE_DATA_SUBSCRIBE_START = 0x00018045
        const val CARLIFE_DATA_SUBSCRIBE_STOP = 0x00018046
        const val NAVI_ASSITANTGUIDE_INFO = 0x00010047
        const val HU_AUTHEN_REQUEST = 0x00018048
        const val MD_AUTHEN_RESPONSE = 0x00010049
        const val HU_AUTHEN_RESULT = 0x0001804A
        const val MD_AUTHEN_RESULT = 0x0001004B
        const val GO_TO_FOREGROUND_RESPONSE = 0x0001004C
        const val START_BT_AUTOPAIR_REQUEST = 0x0001004D
        const val BT_HFP_RESPONSE = 0x0001804E
        const val BT_HFP_STATUS_REQUEST = 0x0001004F
        const val BT_HFP_STATUS_RESPONSE = 0x00018050
        const val MD_FEATURE_CONFIG_REQUEST = 0x00010051
        const val HU_FEATURE_CONFIG_RESPONSE = 0x00018052
        const val BT_START_IDENTIFY_REQ = 0x00018053
        const val BT_IDENTIFY_RESULT_IND = 0x00010054
        const val ERROR_CODE = 0x00018055
        const val VIDEO_ENCODER_JPEG = 0x00018056
        const val VIDEO_ENCODER_JPEG_ACK = 0x00010057
        const val BT_HFP_CALL_STATUS_COVER = 0x00010058
        const val MD_EXIT = 0x00010059
        const val HU_CONNECT_STATISTIC = 0x00018070
        const val MD_RSA_PUBLIC_KEY_REQUEST = 0x0001006A
        const val HU_RSA_PUBLIC_KEY_RESPONSE = 0x0001806B
        const val MD_AES_KEY_SEND_REQUEST = 0x0001006C
        const val HU_AES_REC_RESPONSE = 0x0001806D
        const val MD_ENCRYPT_READY = 0x0001006E
    }

    /** Non-CMD channel service types. */
    object Stream {
        const val VIDEO_DATA = 0x00020001
        const val VIDEO_HEARTBEAT = 0x00020002

        const val MEDIA_INIT = 0x00030001
        const val MEDIA_STOP = 0x00030002
        const val MEDIA_PAUSE = 0x00030003
        const val MEDIA_RESUME_PLAY = 0x00030004
        const val MEDIA_SEEK_TO = 0x00030005
        const val MEDIA_DATA = 0x00030006

        const val NAVI_TTS_INIT = 0x00040001
        const val NAVI_TTS_END = 0x00040002
        const val NAVI_TTS_DATA = 0x00040003

        const val VR_DATA = 0x00058001
        const val VR_AUDIO_INIT = 0x00050002
        const val VR_AUDIO_DATA = 0x00050003
        const val VR_AUDIO_STOP = 0x00050004
        const val VR_MODULE_STATUS = 0x00050005
        const val VR_AUDIO_INTERRUPT = 0x00050006

        const val TOUCH_ACTION = 0x00068001
        const val TOUCH_CAR_HARD_KEY_CODE = 0x00068008
    }

    /** Touch action IDs (CarlifeTouchAction.action). */
    const val TOUCH_ACTION_DOWN = 0
    const val TOUCH_ACTION_UP = 1
    const val TOUCH_ACTION_MOVE = 2

    /** Car hard key codes (CarlifeCarHardKeyCode.keycode), 0x01..0x31. */
    object HardKey {
        const val HOME = 0x01
        const val PHONE_CALL = 0x02
        const val PHONE_END = 0x03
        const val SELECTOR_NEXT = 0x06
        const val SELECTOR_PREVIOUS = 0x07
        const val NAVI = 0x0B
        const val BACK = 0x0E
        const val SEEK_SUB = 0x0F
        const val SEEK_ADD = 0x10
        const val OK = 0x14
        const val MOVE_LEFT = 0x15
        const val MOVE_RIGHT = 0x16
        const val MOVE_UP = 0x17
        const val MOVE_DOWN = 0x18
        const val MEDIA_START = 0x1F
        const val MEDIA_STOP = 0x20
        const val VR_START = 0x21
        const val VR_STOP = 0x22
    }

    /** Module IDs used by MODULE_STATUS / MODULE_CONTROL. */
    object Module {
        const val PHONE = 0x01
        const val NAVI = 0x02
        const val MUSIC = 0x03
        const val VR = 0x04
        const val CONNECT = 0x05
        const val MIC = 0x06
        const val MEDIA_PCM = 0x07
        const val EDOG = 0x08
        const val CRUISE = 0x09
    }

    /** MODULE_CONTROL / feature-config mic states. */
    const val MIC_STATUS_USE_MOBILE_MIC = 2
    const val MIC_STATUS_USE_VEHICLE_MIC = 3

    /**
     * MODULE_STATUS per-module states (docs/carlife-constants.md 模块状态值).
     * Every module enum is IDLE=0 / RUNNING=1; MicState extends with the
     * USE_* values above. The wire values have not been observed live yet —
     * the engine logs the raw statusId on each VR status so a real session
     * can confirm.
     */
    const val MODULE_STATUS_IDLE = 0
    const val MODULE_STATUS_RUNNING = 1

    /** AOA identity strings written by USB control request 52 (index 0..5). */
    const val AOA_MANUFACTURER = "Baidu"
    const val AOA_MODEL_NAME = "CarLife"
    const val AOA_DESCRIPTION = "Baidu CarLife"
    const val AOA_VERSION = "1.0.0"
    const val AOA_URI = "http://carlife.baidu.com/"
    const val AOA_SERIAL_NUMBER = "0720SerialNo."

    /** AOA USB control requests. */
    const val AOA_GET_PROTOCOL = 51
    const val AOA_SEND_IDENT = 52
    const val AOA_START_ACCESSORY = 53

    /** Accessory-mode VID/PID after re-enumeration. */
    const val AOA_VID_ACCESSORY = 0x18D1
    val AOA_ACCESSORY_PIDS = intArrayOf(0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05)

    /** Protocol version the HU announces (CarLifeLibTest: {1, 0}). */
    const val PROTOCOL_VERSION_MAJOR = 1
    const val PROTOCOL_VERSION_MINOR = 0

    /**
     * Default video encoder parameters.
     *
     * The head unit announces the canvas it wants through VIDEO_ENCODER_INIT and
     * the phone renders its own UI at that size, so the right value is the one
     * whose aspect ratio matches the unit — not the largest one. Measured on a
     * 19.5:9 head unit (observed live 2026-10-09): negotiating 1920x720 (8:3)
     * makes the phone lay out a wide car UI whose icon rail and side panel fall
     * outside the unit once it is scaled up to fill the screen, while 1280x720
     * (16:9) fits the same layout edge to edge — nothing cropped, no letterbox.
     */
    const val DEFAULT_VIDEO_WIDTH = 1280
    const val DEFAULT_VIDEO_HEIGHT = 720
    const val DEFAULT_VIDEO_FRAME_RATE = 30

    /** Fixed token the official HU demo sends as AuthenRequest.randomValue. */
    const val DEFAULT_AUTHEN_RANDOM_VALUE = "CarLife_1.0.0"

    /** Feature-config keys. */
    const val FEATURE_FOCUS_UI = "FOCUS_UI"
    const val FEATURE_VOICE_MIC = "VOICE_MIC"
}
