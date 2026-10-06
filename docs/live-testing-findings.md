# CarLife 实战联调结论(2026-10-06,小米 22041216UC / Android 13 / 小米版 CarLife+)

> 本文是桌面假车机 + 真实百度 CarLife+ 多轮联调的实证记录,所有结论均已复现验证。
> 实现依据:core-carlife 协议库;联调环境:adb forward TCP(手机 USB 调试/无线调试均可)。

## 1. 经过验证的完整会话时序(与真实 CarLife+ 实测)

```
HU 连接 6 个 TCP 通道(CMD=7240/VIDEO=8240/MUSIC=9240/TTS=9241/VR=9242/TOUCH=9340)
→ HU 发 HU_PROTOCOL_VERSION {1,0}
← MD 回 PROTOCOL_VERSION_MATCH_STATUS {1}
→ HU 发 HU_AUTHEN_REQUEST {randomValue:"CarLife_1.0.0"} + HU_INFO(设备信息)
     ※ HU_INFO 必须在认证请求之后立即发,MD 等它才会继续
← MD 回 MD_INFO(约 340B) + MD_AUTHEN_RESPONSE(172B encryptValue)
→ HU 发 HU_AUTHEN_RESULT {true}
     ※ 严格四步序:result 必须在收到 response 之后发;提前发会卡死会话
→ HU 发 HU_FEATURE_CONFIG_RESPONSE {FOCUS_UI=1}
→ HU 发 VIDEO_ENCODER_INIT {width,height,frameRate}
     ※ 真实 CarLife+ 不会主动发 FEATURE_CONFIG_REQUEST,车机必须主动提编码器
     ※ 直接请求 1920x720@30 会被接受;请求 1280x480 会协商升到 720p
← MD 回 VIDEO_ENCODER_INIT_DONE {实际分辨率} → 进入 STREAMING
← 视频 H.264 30fps(720p 实测 4-31MB/min)、MEDIA_INIT 44100Hz/2ch/16bit
← MODULE_STATUS / MEDIA_INFO(含专辑封面约 28KB)/ MEDIA_PROGRESS_BAR 持续上报
← MIC_RECORD_WAKEUP_START → HU 开始 VR 上行(桌面用静音,车机用真实麦克风)
→ HU 全程:触摸(TOUCH_ACTION)/硬键(TOUCH_CAR_HARD_KEY_CODE)下行
```

## 2. 不触发/毒丸消息(实测踩坑)

| 消息 | 结果 |
|---|---|
| HU_BT_OOB_INFO(0x00018005)带真实电脑蓝牙 MAC | CarLife+ 收到后停在 ENCODER_INIT,不再回 INIT_DONE(12:02/12:04/12:05 三次复现;去掉后 12:13 成功) |
| 认证 result 抢在 response 前发 | MD_INFO 之后会话静默 |
| 只发认证请求不发 HU_INFO | MD 等待 12 分钟无响应 |
| CarWith(com.miui.carlink) | 有独立车机身份校验(蓝牙配对记录),TCP 直连可完成握手并短暂推流(~1MB)后被踢;未破解,独立课题 |

## 3. 启动顺序(关键!错误顺序 = 手机端状态污染)

**正确顺序(可 100% 复现)**:
1. 车机端先启动,监听/轮询连接
2. 手机端 CarLife+ **全新启动**(若它处于半死会话,先 force-stop 再开)
3. CarLife+ 起来后自动完成投屏授权,连接即推流

**污染症状**:CarLife+ 显示"CarLife已连接!"(转换盒页)但对任何新会话无响应;
`dumpsys media_projection` 显示 `Media Projection: null`;日志循环 `bindEncodeSurface wait 300ms`。
恢复方法:force-stop CarLife+ 重开,且车机端先于手机端就绪。

## 4. 断连规律

- **手机锁屏/省电**:手机端主动全通道断开(实测推流 2 分钟后被断;电量 27% 时 MIUI 收紧)
- 车机端自动重连(1s→2s→4s…上限 15s 退避)+ 手机端重开即可恢复
- 真车机场景(AOA 有线/车机端常亮)不存在此问题的主形态
- CarLife+ 切后台:发 BACKGROUND(0x0001001C),只发视频心跳,不推画面(协议版权保护)

## 5. 网络路径

- CarLife+ 只接受**本机回环**连接;跨设备必须走中转:
  - PC 车机:`adb forward tcp:7240 tcp:7240`(六条)→ 连 127.0.0.1
  - 模拟器车机:连 10.0.2.2(模拟器回环即宿主机)→ 宿主 adb forward → 手机
  - 真车机:AOA 有线(裸 USB bulk)或无线同网直连(无线模式下 CarLife+ 监听全网卡)
- 无线调试(开发者选项)等同完整 ADB,与插线无差别

## 6. 实测性能

- 720p@30 H.264:帧 1KB-314KB(大帧=I 帧),实测 30fps 无卡顿
- 1920x720@30:CarLife+ 直接接受,雅阁 12.3" 面板点对点
- 触摸:down/move/up 透传,坐标按显示区域(信箱变换后)映射;信箱偏移会导致
  边缘按键错位(已在桌面端修复,映射公式见 AccordHeadUnitWindow.installTouchInput)
- 音频:PCM 44100Hz/2ch/16bit 小端,直写声卡无问题
- 麦克风:16kHz 单声道 1KB/包静音即可维持状态机;真实麦克风采集见 CarLifeEngine.startMic
