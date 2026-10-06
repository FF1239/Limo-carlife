# CarLife 协议笔记(实现依据)

> 来源:eytb2 培训博客、stonexu1986/carlife-protocol-analysis-tool 常量表、pitate7777777/CarLifeWirelessBox 握手文档、
> CSDN 车载开发系列、百度 Apollo 车机端源码镜像(674809/carlife,Apache-2.0)。
> 本文档为 DiPlay-CarLife 的 M1/M2/M5 阶段实现参考,GPL-3.0 项目附属资料。

## 1. 通道架构(6 条通道)

| channel_id | 通道 | 方向 | 内容 |
|---|---|---|---|
| 1 | CMD(控制) | 双向 | protobuf 控制消息(握手/协商/心跳控制/GPS) |
| 2 | VIDEO | 手机→车机 | H.264 裸流 |
| 3 | MUSIC(媒体) | 手机→车机 | 音频数据 |
| 4 | TTS | 手机→车机 | 语音播报音频 |
| 5 | VR(语音识别) | 双向 | 麦克风上行音频 + 下行提示音 |
| 6 | TOUCH(控制) | 车机→手机 | 触摸事件、硬键/方向盘按键 |

- 有线:6 通道复用同一 USB bulk 连接(用帧头 channel_id 区分)。
- 无线:TCP 模式下手机端监听 6 个端口(见 §6),每个端口对应一条通道。

## 2. 传输层帧格式(有线 USB / TCP 通用)

8 字节传输头,两个大端 int32:

```
struct TransportHeader {
  int32_be channel_id;   // 1..6
  int32_be msg_len;      // 后续负载长度
}
```

Python 参考:`struct.unpack('>ii', header)`(协议分析工具原文)。

### 各通道子头
- video / music(audio) / vr 消息:12 字节子头,偏移 8 处为 4 字节 **service type**(即整个 12B 头里前 4B 是别的字段、第 9..12 字节是 service type——以 Apollo Vehicle-Lib 源码为准核对)。
- CMD 通道消息:直接是 protobuf 2.2 编码;消息头里携带 service type。
- **控制消息必须用 protobuf 2.x**(百度库绑定 protobuf 2.5.0;训练文档警告升级版本会不兼容)。

## 3. Service Type 常量表(来自协议分析工具)

前缀分段:cmd `0x0001xxxx`、video `0x0002xxxx`、music `0x0003xxxx`、tts `0x0004xxxx`、vr `0x0005xxxx`、touch `0x0006xxxx`。

### CMD 通道(车机 HU 发起为主)
| ID | 名称 | 说明 |
|---|---|---|
| 0x00018001 | HU_PROTOCOL_VERSION | 会话第一条消息,版本协商 |
| 0x00010004 | MD_INFO / HU_INFO | 设备信息交换 |
| 0x00018009 | VIDEO_ENCODER_START | 请求手机开始推流 |
| 0x00018010 | CAR_GPS | 车机→手机 GPS 数据 |
| 0x00018048 | HU_AUTHEN_REQUEST | 认证请求 |
| 0x00018055 | ERROR_CODE | 错误上报 |

其余完整表以 `refs/carlife-protocol-analysis-tool` 源码常量为准(克隆后整理成 Kotlin object)。

### VIDEO
| ID | 名称 |
|---|---|
| 0x00020001 | VIDEO_DATA |
| 0x00020002 | 心跳(截屏不可用时) |

### MUSIC / TTS / VR
- music 数据:`0x00030006`
- TTS:init `0x00040001` / end `0x00040002` / data `0x00040003`
- VR 命令:`0x00050002`–`0x00050004`;VR 音频数据:`0x00058001`

### TOUCH
| ID | 名称 | 说明 |
|---|---|---|
| 0x00068001 | 触摸动作 | down/move/up |
| 0x00068008 | 硬键 | 键码表 0x01–0x31(方向盘按键映射) |

## 4. 会话握手(7 阶段)

1. 版本协商:`HU_PROTOCOL_VERSION` → 手机回 `VERSION_MATCH_STATUS`
2. 设备信息:`HU_INFO` / `MD_INFO` 交换
3. 认证:`HU_AUTHEN_REQUEST`(0x00018048)→ 手机回 `MD_AUTHEN_RESPONSE`
4. 认证结果确认
5. 特性协商:`MD_FEATURE_CONFIG_REQ` → `HU_FEATURE_CONFIG_RESP`
6. `VIDEO_ENCODER_INIT` / DONE(分辨率、帧率等参数在此协商)
7. `VIDEO_ENCODER_START` → 手机开始推视频流

**认证/加密是公开资料空白区**:认证载荷内容无公开 spec。对策:直接移植百度 Vehicle-Lib(Apache-2.0,含认证实现),JNI 保真优先。

## 5. 编解码参数

- **视频**:H.264,MediaCodec 硬解;分辨率/帧率在 VIDEO_ENCODER_INIT 阶段协商;有帧时间戳用于 A/V 同步;截屏不可用时发 0x00020002 心跳包;V4.0+ 支持后台截屏。
- **音频**:PCM 小端裸流;媒体/TTS/VR 各一路独立;帧大小 = sampleRate × bitWidth × duration × channels,duration 典型 2.5–120ms;**ADPCM 说法未获可靠佐证,按 PCM 实现**;无线 CarWith(小米,基于百度方案)用 **AAC**(部分实现里 Opus→AAC 转换)。
- 采样率/位深具体值:待从 Apollo Vehicle-Lib 源码核对(克隆后补充)。

## 6. 传输通道

### 有线(Android 手机)
- **AOA(Android Open Accessory,主路径)**:
  - 控制请求 51:`GET_PROTOCOL` 探测配件协议版本(车机需回 ≥2)
  - 控制请求 52:发送 manufacturer/model/version 字符串(手机端 CarLife app 会校验这些字符串,不匹配会报权限错误——具体字符串值从 Apollo 源码或抓包获取)
  - 控制请求 53:`START_ACCESSORY` → USB 重枚举为附件模式 → 打开 bulk 端点
- 旧设备:ADB forward TCP
- 新设备:CDC-NCM(USB 网卡模式)
- 连接状态常量(分析工具):`"adb" / "aoa" / "ncm android" / "ios" / "wifi"`
- iPhone:iAP2/EAP 会话(不在本期范围)

### 无线
- 模式:手机热点 / 车机热点 / WiFi Direct(P2P);UDP 发现对端 IP 后走 TCP
- TCP 模式下**手机端是 TCP 服务端**,车机主动连;手机端 CarLife 无线模式监听 6 个 TCP 端口:**7240 / 8240 / 9240 / 9241 / 9242 / 9340**(端口↔通道映射未公开文档化,需抓包核对)
- 发现:UDP 发现载荷未公开;至少一个实现用 mDNS(服务名未知)→ 需要真机抓包确认
- **兼容性红线**:CarLife 手机端 ≤6.7.2 无线可用;**7.2+ 协议变更导致黑屏**(CarLifeWirelessBox 实测),MVP 锁定 ≤6.7.2 / CarWith 现版本

## 7. 参考仓库(克隆到 refs/)

| 仓库 | 用途 | 许可 |
|---|---|---|
| serein-morii/DiPlay-CN | 项目基座(UI 外壳 + CarPlay 通道) | GPL-3.0 |
| 674809/carlife | 百度官方车机端实现(Apollo 镜像),认证/打包/解析的移植来源 | Apache-2.0 |
| stonexu1986/carlife-protocol-analysis-tool | 全量常量表 + 报文解析(golden 单测来源) | Apache-2.0 |
| pitate7777777/CarLifeWirelessBox | 7 阶段握手/6 端口/兼容性参考(smali) | MIT |

协议文档:eytb2.github.io Carlife培训(报文级)、CSDN zjfengdou30/81808551、u013620306/122661581、
官方《百度CarLife车机手机互联方案接入详细指南 V2.1.1》(gitcode)。

## 8. 待核对清单(已全部核实,2016-10-05)

- [x] AOA 52 请求的确切字符串常量(AOAHostSetup.java):Manufacturer="Baidu", Model="CarLife", Description="Baidu CarLife", Version="1.0.0", URI="http://carlife.baidu.com/", Serial="0720SerialNo."(index 0-5)
- [x] 各通道子头逐字节布局(§2 + carlife-proto-messages.md §1)
- [x] 音频参数:三路 INIT 均为 protobuf{sampleRate=1, channelConfig=2, sampleFormat=3},由手机在会话中动态下发,车机据此建 AudioTrack
- [x] HU_AUTHEN_REQUEST 载荷(CarlifeAuthenRequest.randomValue=1:string;官方 demo 用固定串 "CarLife_1.0.0",HU 不校验 encryptValue 直接回 result=true)
- [x] 无线 6 端口 ↔ 6 通道映射(CConnectManager.h):MD 端 CMD=7240/VIDEO=8240/MEDIA=9240/TTS=9241/VR=9242/TOUCH=9340;HU 端 7200/8200/9200/9201/9202/9300
- [x] VIDEO_ENCODER_INIT 的 protobuf 字段(CarlifeVideoEncoderInfo):width=1/height=2/frameRate=3;官方 demo 默认 768x480@30fps

## 9. 加密层(2017+ 版本新增,分析工具常量表未覆盖)

消息 ID(CommonParams.java):
- MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST = 0x0001006A(手机请求公钥)
- MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE = 0x0001806B(车机回 RSA 公钥)
- MSG_CMD_MD_AES_KEY_SEND_REQUEST = 0x0001006C(手机发 RSA 加密的 AES 密钥)

流程(EncryptSetupManager/RSAManager/AESManager):
1. 手机发 0x6A → 车机生成 RSA-2048 密钥对,回 0x6B(Base64 编码 X.509 公钥串)
2. 手机用公钥加密自己的 AES 密钥(RSA/ECB/PKCS1Padding,Base64 传输),经 0x6C 发回
3. 车机私钥解出 AES 密钥;之后消息负载 AES 加密(javax.crypto Cipher "AES" = AES/ECB/PKCS5Padding,SecretKeySpec(key.getBytes()))

三种模式(EncryptConfig):明文(DEBUG_ENABLE=false)/预共享 AES(AES_ENCRYPT_AS_BEGINE=true,默认密钥 "1234567890123456")/RSA 协商。
开源 demo 出厂配置:DEBUG_ENABLE=true + AES_ENCRYPT_AS_BEGINE=false(即走 RSA 协商路径)。

## 10. 官方 HU 会话时序(CarLifeLibTest.cpp 实证)

1. carLifeLibInit() → connectionSetup()(6 通道 socket 建立)
2. HU 发 cmdHUProtoclVersion({major=1, minor=0})
3. (可选)cmdHuAuthenRequest(randomValue="CarLife_1.0.0") → 收 MD_AUTHEN_RESPONSE(encryptValue) → HU 回 AUTHEN_RESULT(true)
4. 手机发 FEATURE_CONFIG_REQUEST → HU 回 FEATURE_CONFIG_RESPONSE(FOCUS_UI=1)
5. HU 发 cmdVideoEncoderInit({768,480,30}) → 收 ENCODER_INIT_DONE
6. HU 发 cmdVideoEncoderStart() → 视频流开始(VIDEO_DATA)
7. 音频:mediaInit/ttsInit/vrInit 回调(参数动态下发)→ normalData PCM 流
8. VR 上行:sendVRRecordData(buf, 1024, 0)(1KB/包)
9. 触摸:ctrlTouchAction({action,x,y});硬键:ctrlTouchCarHardKeyCode({keycode})

注:demo 中认证与加密均为可选路径;手机端对 HU 的强制校验主要是协议版本与设备信息。全部 protobuf 字段号的权威来源:D:\refs\carlife\CarLife-Android-Vehicle\src\com\baidu\carlife\protobuf\*.java 的字段注释。

