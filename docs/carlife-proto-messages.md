# CMD 通道与 protobuf 消息结构(提取自 cmd_message_parser.py / 各音频 parser)

> 来源:stonexu1986/carlife-protocol-analysis-tool(Apache-2.0),2026-10-05 提取。
> M1 的 .proto 定义与本笔记字段一一对应;golden 单测用此文件对照。

## 1. 消息头布局(传输头 8B 之内)

### CMD(通道1)消息头 = 8 字节,大端
```
msg_data_len: u16   # protobuf 负载长度
reserved:     u16
service_type: u32   # bit15=方向: 1=车机HU→手机MD, 0=MD→HU
```

### VIDEO/MUSIC/TTS/VR 消息头 = 12 字节,大端
```
msg_data_len: u32   # 负载长度
timestamp:    u32   # A/V 同步用
service_type: u32
```

### TOUCH(通道6)消息头 = 8 字节,大端
```
msg_data_len: u16
reserved:     u16
service_type: u32
```

## 2. 26 个 protobuf 消息(字段名照录,字段号待 .proto 原文核对)

### 版本协商
- **CarlifeProtocolVersion**: majorVersion, minorVersion — HU 先发(HU_PROTOCOL_VERSION 0x00018001)
- **CarlifeProtocolVersionMatchStatus**: matchStatus — 手机回(PROTOCOL_VERSION_MATCH_STATUS 0x00010002)

### 设备信息(HU_INFO 0x00018003 / MD_INFO 0x00010004)
- **CarlifeDeviceInfo**: os, board, bootloader, brand, cpu_abi, cpu_abi2, device, display, fingerprint, hardware, host, cid, manufacturer, model, product, serial, codename, incremental, release, sdk, sdk_int, token, btaddress, btname
  - 解析器示例:HU 侧 os="C12346", model="JY-2016", brand="carlife"

### 视频编码器协商
- **CarlifeVideoEncoderInfo**: width, height, frameRate — VIDEO_ENCODER_INIT(0x00018007)→ 手机回 VIDEO_ENCODER_INIT_DONE(0x00010008)
- **CarlifeVideoFrameRate**: frameRate — VIDEO_ENCODER_FRAME_RATE_CHANGE(0x0001800C)/DONE(0x0001000D)

### 车辆数据订阅(CAR_DATA_SUBSCRIBE 0x00010031 等)
- **CarlifeVehicleInfo**: moduleID, supportFlag, frequency
- **CarlifeVehicleInfoList**: infos[] — HU_FEATURE_CONFIG 相关
- ModuleID 枚举: CAR_DATA_GPS=1, CAR_DATA_VELOCITY, CAR_DATA_GYROSCOPE, CAR_DATA_ACCELERATION, CAR_DATA_GEAR, CAR_DATA_OIL
- 数据消息:CarLifeCarGps(antennaState,latitude,longitude,height,speed,heading,satsUsed,satsVisible,timeStamp),
  CarlifeCarSpeed(car_speed,timeStamp), CarlifeGyroscope(gyroType=1单轴/2三轴,gyroX,gyroY,gyroZ,timeStamp),
  CarlifeAcceleration(accX,accY,accZ,timeStamp), CarlifeOil(level,range,lowFuleWarning), CarLifeGearInfo(gear_info)

### 模块状态(MODULE_STATUS 0x00010026 / MODULE_CONTROL 0x00018028)
- **CarlifeModuleStatusList**: list[] of CarlifeModuleStatus{moduleID, statusID}
- ModuleId: CARLIFE_PHONE, CARLIFE_NAVI, CARLIFE_MUSIC, CARLIFE_VR, CARLIFE_CONNECT, CARLIFE_MIC, CARLIFE_MEDIAPCM, CARLIFE_EDOG, CARLIFE_CRUISE
- 状态枚举: PhoneState{PHONE_STATUS_IDLE,INCOMING,OUTTING,CONNECTED}; NaviState{IDLE,START}; MusicState{IDLE,RUNNING};
  VRState{IDLE,RUNNING}; ConnectState{CONNECT_STATUS_ADB,AOA,NCM_ANDROID,IOS,WIFI}; MicState{MIC_STATUS_IDLE,RUNNING,USE_VEHICLE_MIC,USE_MOBILE_MIC};
  MediaPCMState{IDLE,RUNNING}; EDogState/CruiseState{IDLE,RUNNING}

### 媒体信息(MEDIA_INFO 0x00010035 / MEDIA_PROGRESS_BAR 0x00010036)
- **CarlifeMediaInfo**: source, song, artist, album, albumArt, duration, playlistNum, songId, mode
- PlayMode 枚举: PLAY_MODE_REPEAT_ONE, PLAY_MODE_SHUFFLE, PLAY_MODE_REPEAT_ALL
- **CarlifeMediaProgressBar**: progressBar

### 导航(NAVI_NEXT_TURN_INFO 0x00010030 / NAVI_ASSITANTGUIDE_INFO 0x00010047)
- **CarlifeNaviNextTurnInfo**: action, nextTurn, roadName, totalDistance, remaindistance, turnIconData
- **CarlifeNaviAssitantGuideInfo**: action, assistantType, trafficSignType, totalDistance, remainDistance, cameraSpeed

### 认证(四步)
1. HU → MD:**HU_AUTHEN_REQUEST 0x00018048** = **CarlifeAuthenRequest{ randomValue }**
2. MD → HU:**MD_AUTHEN_RESPONSE 0x00010049** = **CarlifeAuthenResponse{ encryptValue }** ← 加密算法在百度 Vehicle-Lib(配 bcprov/BouncyCastle)
3. HU → MD:**HU_AUTHEN_RESULT 0x0001804A** = **CarlifeAuthenResult{ result }**
4. MD → HU:**MD_AUTHEN_RESULT 0x0001004B** = CarlifeAuthenResult{ result }

### 特性协商(MD_FEATURE_CONFIG_REQUEST 0x00010051 → HU_FEATURE_CONFIG_RESPONSE 0x00018052)
- **CarlifeFeatureConfigList**: list[] of CarlifeFeatureConfig{key, value}
- 已知键值:VOICE_MIC → USE_MOBILE_MIC(手机麦)/ USE_VEHICLE_MIC;麦克风也可经 MODULE_CONTROL(moduleId=CARLIFE_MIC, MicState)

### 其他
- **CarlifeConnectException**: exceptionType(CONNECT_EXCEPTION 0x00010037)
- **CarlifeErrorCode**: errorCode(ERROR_CODE 0x00018055;1=编码器错误,2=权限拒绝,3=截屏请求)
- **CarlifeStatisticsInfo**: cuid, versionName, versionCode, channel, connectCount, connectSuccessCount, connectTime, crashLog
- **CarlifeConStatisticProto**: nConnectTotal, nSuccessCount, nFailedCount, nConnectTime, nErrorCount, ErrorType
- MIC 唤醒/录音:MIC_RECORD_WAKEUP_START 0x00010022, MIC_RECORD_END 0x00010023, MIC_RECORD_RECOG_START 0x00010024;VR 音频 1KB/包
- BACKGROUND(0x0001001C)后手机端只发视频心跳(VIDEO_HEARTBEAT 0x00020002)

## 3. 音频参数(TTS/VR/MEDIA 共用头;INIT 消息为 protobuf)
- **CarlifeMediaInit / CarlifeTTSInit / VR init**: sampleRate, channelConfig, sampleFormat
- PCM 负载为原始小端数据;VR 数据约 1KB/包
- 解析器确认:三路音频头都是 `>III`(len,timestamp,service_type)

## 4. 触摸(TOUCH_ACTION 0x00068001,protobuf:carlife_touch_action_pb2)
- 动作:DOWN=0, UP=1, MOVE=2;透传方式连续发 down/move/up,发送线程不得阻塞
- protobuf 消息:CarlifeTouchAction(字段含 action/x/y;具体字段号待 .proto 原文)

## 5. 方向位规则(重要)
service_type bit15:1 = 车机→手机(HU 发起,0x0001xxxx 全部如此),0 = 手机→车机(0x0000xxxx)
通道号在 bit16-19(cmd=1, video=2, music=3, tts=4, vr=5, touch=6)

## 待办
- [ ] .proto 原文字段号(protobuf/ 目录;WebFetch 404,克隆后从仓库拿)
- [ ] 认证 encryptValue 算法(Apollo Vehicle-Lib + bcprov)
