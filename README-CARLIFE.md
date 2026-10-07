# DiPlay-CarLife

基于 [DiPlay-CN](https://github.com/serein-morii/DiPlay-CN)(GPL-3.0)改造的 Android 车机端投屏接收器,在保留原 CarPlay 通道的基础上**新增百度 CarLife 通道**:同一台车机,Android 手机走 CarLife、iPhone 走 CarPlay。

> 本项目是 DiPlay-CN 的 GPL-3.0 衍生作品,依许可证要求同样以 GPL-3.0 开源。

## CarLife 通道功能(对标 DiPlay 形态)

| 功能 | 状态 | 说明 |
|---|---|---|
| 有线 USB(AOA) | ✅ 已实现 | 控制请求 51/52/53 → 附件模式 → bulk 传输,帧格式与百度参考实现逐字节一致 |
| 无线连接 | ✅ 已实现 | 同网段扫描手机 CMD 端口(7240),六通道 TCP(7240/8240/9240/9241/9242/9340) |
| 七阶段握手 | ✅ 已实现 | 版本协商→设备信息→认证→结果→特性协商→编码器 INIT→START |
| H.264 视频硬解 | ✅ 已实现 | MediaCodec → Surface,分辨率/帧率按手机端协商(默认 768×480@30) |
| 三路音频 | ✅ 已实现 | 媒体/TTS/VR 独立 AudioTrack,参数由手机 INIT 消息动态下发(PCM 小端) |
| 麦克风上行(VR) | ✅ 已实现 | 16kHz 单声道 AudioRecord → VR 通道,1KB/包 |
| 触摸回传 | ✅ 已实现 | down/move/up 透传(坐标按协商分辨率换算)+ 硬键/方向盘键码 0x01–0x31 |
| 加密握手 | ✅ 已实现 | RSA 公钥交换(0x6A/0x6B/0x6C)消息与载荷结构;AES 会话加密待真机联调 |
| C1 Pro 碳纤 UI | ✅ 已实现 | 待机主页(仪表环 000/STANDBY + 连接 CTA)、投屏页(投屏 2/3 + 仪表列 1/3)、碳纤设置页 |
| 后台保活 | ✅ 已实现 | 投屏即启动前台服务(connectedDevice),熄屏/切后台会话不断 |
| 画面比例 | ✅ 已实现 | 投屏画面按协商分辨率 letterbox,不再拉伸变形 |
| 暂停/截屏 | ✅ 已实现 | 暂停冻结画面(解码挂起、流不断),截屏保存到应用图片目录 |
| 主题色 | ✅ 已实现 | 红/蓝/橙三色可切(设置 → 外观),全界面联动 |
| 语音策略 | ✅ 已实现 | 来电/导航提示时媒体音轨自动 duck,结束后恢复 |
| 多语言 | ✅ 已实现 | CarLife 全部文案覆盖 en/zh-CN/ar/es/ru/uk |
| 车速仪表 | ⏳ 待真机 | 暂为演示数据(可在设置关闭);已加未知 CMD 消息日志,真机抓包确认 GPS 消息号后接真实车速 |
| 日夜自动(光线传感器) | ⏳ 后续 | 手动夜间模式已有;自动模式复用 CarPlay 的 AndroidAmbientLight 待接 |
| 诊断导出 / ADB-TCP / CDC-NCM 传输 | ⏳ 后续 | 见 docs/protocol-notes.md 规划 |

## 模块结构

```
core-carlife/   纯 Kotlin 协议库(无 Android 依赖,17 个 golden 单测)
  ├─ CarLifeConstants.kt  全量常量(通道/消息 ID/硬键/端口/AOA 字符串)
  ├─ ProtoWire.kt         protobuf 2 wire 编解码(手写,避免 protobuf-java 2.x)
  ├─ CarLifeFrame.kt      8B 传输头 + 8B/12B 通道子头,增量流分帧
  ├─ CarLifeMessages.kt   26 个 protobuf 消息(字段号对齐百度源码)
  └─ CarLifeSession.kt    HU 侧会话状态机 + 收发
common/src/main/java/com/shilapi/xcertplay/carlife/
  ├─ AoaTransport.kt          有线 AOA 传输(USB host)
  ├─ TcpChannelTransport.kt   无线六端口 TCP 传输
  ├─ SubnetScanner.kt         /24 子网扫描发现手机
  ├─ CarLifeEngine.kt         引擎:传输+解码+音频+麦克风+会话
  └─ CarLifeVideoActivity.kt  全屏投屏界面
```

入口:桌面图标(碳纤仪表图标)直接进入 **CarLife 待机主页**;USB 插线自动连接(受「插入 USB 自动连接」开关控制),或点「无线连接」搜索同网段手机。主页右下角也保留原 CarPlay 通道入口。

## 构建

```bat
set GRADLE_USER_HOME=D:\gradle-home   (可选,缓存重定向)
gradlew.bat :mobile:assembleDebug
```

依赖:JDK 17+(Android Studio 自带 JBR 即可)、Android SDK platform 37、build-tools 36/37。

## 协议资料

`docs/` 目录:protocol-notes.md(协议总纲)、carlife-constants.md(常量表)、carlife-proto-messages.md(消息结构)。
参考实现(上游,见 `D:\refs\`):百度官方车机端库(Apache-2.0)、协议分析工具(Apache-2.0)。

## 兼容性说明

- 有线:Android 手机 + 百度 CarLife 手机版(理论上全版本,待真机验证)
- 无线:CarLife 手机端 ≤ 6.7.2 / 小米 CarWith(7.2+ 协议变更已知不兼容)
- 车机:通用 Android 9+(AOA 主机模式);无线模式需车机与手机同网
- 本项目未获百度认证,与 DiPlay 的 CarPlay 通道相同,仅面向个人研究与测试用途

## 许可

- 本项目:GPL-3.0(继承自 DiPlay/DiPlay-CN)
- 百度 CarLife-Vehicle-Lib / 协议分析工具:Apache-2.0(仅作协议参考,未直接复制代码;如后续引入 JNI 移植将保留其版权声明)
