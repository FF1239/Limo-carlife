# CarLife 协议常量表(完整版,提取自 carlife-protocol-analysis-tool/common_constant.py)

> 提取时间:2026-10-05。来源仓库:stonexu1986/carlife-protocol-analysis-tool(Apache-2.0)。
> 这是 M1 core-carlife 模块 `CarLifeConstants.kt` 的直接转换依据。

## 帧头布局(关键!)

传输头(PACKET_HEADER_LENGTH)= 8 字节:[int32_be channel_id][int32_be msg_len]

各通道消息头(在传输头之后):
| 通道 | 头长 | service_type 偏移 | service_type 长度 |
|---|---|---|---|
| CMD (1) | 8 | 4 | 4 |
| VIDEO (2) | 12 | 8 | 4 |
| MUSIC (3) | 12 | 8 | 4 |
| TTS (4) | 12 | 8 | 4 |
| VR (5) | 12 | 8 | 4 |
| TOUCH (6) | 8 | 4 | 4 |

即 CMD/TOUCH 消息头 8B(前 4 字节待从 parser 确认,疑为时间戳/计数),VIDEO/AUDIO/VR 消息头 12B(前 8 字节含时间戳)。
视频负载 = 原始 H.264;其余负载 = protobuf 2.2 序列化。

## 通道 ID
CMD=1, VIDEO=2, MUSIC=3, TTS=4, VR=5, TOUCH=6

## CMD 通道消息(service_type)
```
HU_PROTOCOL_VERSION            0x00018001  (会话首条,车机→手机)
PROTOCOL_VERSION_MATCH_STATUS  0x00010002  (手机→车机)
HU_INFO                        0x00018003
MD_INFO                        0x00010004
HU_BT_OOB_INFO                 0x00018005
MD_BT_OOB_INFO                 0x00010006
VIDEO_ENCODER_INIT             0x00018007
VIDEO_ENCODER_INIT_DONE        0x00010008
VIDEO_ENCODER_START            0x00018009
VIDEO_ENCODER_PAUSE            0x0001800A
VIDEO_ENCODER_RESET            0x0001800B
VIDEO_ENCODER_FRAME_RATE_CHANGE      0x0001800C
VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE 0x0001000D
CAR_VELOCITY                   0x0001800F
CAR_GPS                        0x00018010
CAR_GYROSCOPE                  0x00018011
CAR_ACCELERATION               0x00018012
CAR_OIL                        0x00018013
SCREEN_ON                      0x00010018
SCREEN_OFF                     0x00010019
SCREEN_USERPRESENT             0x0001001A
FOREGROUND                     0x0001001B
BACKGROUND                     0x0001001C
LAUNCH_MODE_NORMAL             0x0001801D
LAUNCH_MODE_PHONE              0x0001801E
LAUNCH_MODE_MAP                0x0001801F
LAUNCH_MODE_MUSIC              0x00018020
GO_TO_DESKTOP                  0x00010021
MIC_RECORD_WAKEUP_START        0x00010022
MIC_RECORD_END                 0x00010023
MIC_RECORD_RECOG_START         0x00010024
GO_TO_FOREGROUND               0x00018025
MODULE_STATUS                  0x00010026
STATISTIC_INFO                 0x00018027
MODULE_CONTROL                 0x00018028
CAR_DATA_GEAR                  0x00018029
NAVI_NEXT_TURN_INFO            0x00010030
CAR_DATA_SUBSCRIBE             0x00010031
CAR_DATA_SUBSCRIBE_DONE        0x00018032
CAR_DATA_SUBSCRIBE_START       0x00010033
CAR_DATA_SUBSCRIBE_STOP        0x00010034
MEDIA_INFO                     0x00010035
MEDIA_PROGRESS_BAR             0x00010036
CONNECT_EXCEPTION              0x00010037
REQUEST_GO_TO_FOREGROUND       0x00010038
UI_ACTION_SOUND                0x00010039
BT_HFP_REQUEST                 0x00010040
BT_HFP_INDICATION              0x00018041
BT_HFP_CONNECTION              0x00018042
CARLIFE_DATA_SUBSCRIBE         0x00018043
CARLIFE_DATA_SUBSCRIBE_DONE    0x00010044
CARLIFE_DATA_SUBSCRIBE_START   0x00018045
CARLIFE_DATA_SUBSCRIBE_STOP    0x00018046
NAVI_ASSITANTGUIDE_INFO        0x00010047
HU_AUTHEN_REQUEST              0x00018048
MD_AUTHEN_RESPONSE             0x00010049
HU_AUTHEN_RESULT               0x0001804A
MD_AUTHEN_RESULT               0x0001004B
GO_TO_FOREGROUND_RESPONSE      0x0001004C
START_BT_AUTOPAIR_REQUEST      0x0001004D
BT_HFP_RESPONSE                0x0001804E
BT_HFP_STATUS_REQUEST          0x0001004F
BT_HFP_STATUS_RESPONSE         0x00018050
MD_FEATURE_CONFIG_REQUEST      0x00010051
HU_FEATURE_CONFIG_RESPONSE     0x00018052
BT_START_IDENTIFY_REQ          0x00018053
BT_IDENTIFY_RESULT_IND         0x00010054
ERROR_CODE                     0x00018055
VIDEO_ENCODER_JPEG             0x00018056
VIDEO_ENCODER_JPEG_ACK         0x00010057
BT_HFP_CALL_STATUS_COVER       0x00010058
MD_EXIT                        0x00010059
HU_CONNECT_STATISTIC           0x00018070
```

## VIDEO / MUSIC / TTS / VR / TOUCH
```
VIDEO_DATA        0x00020001     VIDEO_HEARTBEAT    0x00020002
MEDIA_INIT        0x00030001     MEDIA_PAUSE        0x00030003
MEDIA_RESUME_PLAY 0x00030004     MEDIA_DATA         0x00030006
NAVI_TTS_INIT     0x00040001     NAVI_TTS_END       0x00040002     NAVI_TTS_DATA 0x00040003
VR_DATA           0x00058001
VR_AUDIO_INIT     0x00050002     VR_AUDIO_DATA      0x00050003     VR_AUDIO_STOP 0x00050004
VR_MODULE_STATUS  0x00050005     VR_AUDIO_INTERRUPT 0x00050006
TOUCH_ACTION      0x00068001     TOUCH_CAR_HARD_KEY_CODE 0x00068008
```

## 触摸动作 ID
DOWN=0, UP=1, MOVE=2

## 硬键码表(0x00068008 载荷)
```
0x01 home           0x02 phone call    0x03 phone end     0x04 phone end mute(deprecated)
0x05 hfp            0x06 selector next 0x07 selector previous
0x08 setting(dep)   0x09 media         0x0A radio(dep)    0x0B navi    0x0C src(dep)
0x0D mode(dep)      0x0E back          0x0F seek sub      0x10 seek add
0x11 volume sub(dep) 0x12 volume add(dep) 0x13 mute       0x14 ok
0x15 move left      0x16 move right    0x17 move up       0x18 move down
0x19 up left        0x1A up right      0x1B down left     0x1C down right
0x1D tel            0x1E main          0x1F media start   0x20 media stop
0x21 vr start       0x22 vr stop
0x23-0x2C 数字 0-9  0x2D *  0x2E #  0x2F delete  0x30 clear  0x31 +
```

## 模块状态值(MSG_CMD_MODULE_STATUS 载荷用)
- Phone: idle / incoming / outing / connected
- Navi: idle / start
- Music: idle / running
- VR: idle / running
- Connect: adb / aoa / ncm android / ios / wifi
- Mic: vehicle(车机麦) / mobile(手机麦)
- MediaPCM: idle / running
- EDog / Cruise: idle / running

## 其他
- 坐标系:WGS-84=1, GCJ-02=2
- 编码器错误=1, 权限拒绝=2, 截屏请求=3
- 播放模式:repeat one / shuffle / repeat all
- 关键握手节点:认证四条(AUTHEN_REQUEST 0x18048 → MD_AUTHEN_RESPONSE 0x10049 → HU_AUTHEN_RESULT 0x1804A → MD_AUTHEN_RESULT 0x1004B);
  特性协商(MD_FEATURE_CONFIG_REQUEST 0x10051 → HU_FEATURE_CONFIG_RESPONSE 0x18052);
  麦克风特性:VOICE_MIC = USE_MOBILE_MIC(或 MODULE_CONTROL, ModuleId=CARLIFE_MIC_MODULE_ID, MicState=MIC_STATUS_USE_MOBILE_MIC)
- 背景:MSG_CMD_BACKGROUND(0x0001001C)后手机只发视频心跳
