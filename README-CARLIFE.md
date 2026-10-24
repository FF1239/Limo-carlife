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
| 暂停/截屏 | ✅ 已实现 | 暂停冻结画面(设置菜单"冻结画面":手机编码器暂停+车机停止解码,恢复发 START 并重置看门狗——恢复失败自动重连而非永久卡死),截屏保存到应用图片目录 |
| 主题色 | ✅ 已实现 | 红/蓝/橙三色可切(设置 → 外观),全界面联动 |
| 语音策略 | ✅ 已实现 | 来电/导航提示时媒体音轨自动 duck,结束后恢复 |
| 多语言 | ✅ 已实现 | CarLife 全部文案覆盖 en/zh-CN/ar/es/ru/uk |
| 车速仪表 | ✅ 已实现,待真机验证 | 握手后向手机订阅 GNSS 模块(0x00010031/0x00010033),解码 CAR_GPS(0x00018010,速度 cm/s,字段对齐百度 CarlifeCarGpsProto)→ 仪表环显示真实车速;无 GPS 流时回落演示数据(设置可关) |
| 日夜自动 | ✅ 已实现,待真机验证 | 复用 CarPlay 的光线传感器适配器,lux>15 转白天 / <8 转夜间(滞回防抖),设置可选 自动/白天/夜间 |
| 会话日志 | ✅ 已实现 | 引擎状态/未知 CMD/手机信息/亮度切换全部落盘 Android/data/<pkg>/files/logs,每次连接一个文件,7 天自动清理;设置页显示路径(无 adb 时的调试通道) |
| 日夜手动模式 | ✅ 已实现 | 设置三态切换,夜间降亮度压蓝光 |
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

## 机型适配:25 款雅阁(广汽本田,第十一代,Honda CONNECT 4.0)

本项目的主要目标车机。要点:

| 项 | 说明 |
|---|---|
| 屏幕 | 12.3 寸悬浮触控屏(超宽约 16:6,第三方拆解一致为 **1920×720**,本田不官方公布像素数)+ 10.2 寸全液晶仪表。**默认画布已设为 1920×720**,实测不准时设置里可换 |
| 系统 | Honda CONNECT 4.0(安卓底座封闭系统,2023.3 发布);官方互联为**无线 CarPlay / CarLife** |
| 安装 APK | 车机设置里**连续点击时间/日期约 78 次**进入工程模式(部分年款在电话拨号盘输 `*#*#888…` 类代码+密码),开启 ADB 调试;然后①电脑 `adb connect 车机IP` 后 `adb install`,或②手机端「甲壳虫 ADB 助手」推送,或③U 盘直装。**若桌面不显示本 App 图标**(启动器白名单),插 USB 线会自动拉起,或 `adb shell am start -n com.shihab.diplay.cn/com.shilapi.xcertplay.carlife.CarLifeHomeActivity` |
| 内置 CarLife 共存 | 25 款自带无线 CarLife。USB 插线若弹"选择应用"选本 App 并勾"始终";若内置服务抢占 USB,先在车机端断开它的连接再进本 App;两套无线发现( ours 扫描 7240 端口 vs 内置)理论上互不干扰 |
| 车速来源 | 原车屏无开放 CAN 接口。速度来自**双源**:车机自带 GPS(优先,天线更好)+ 手机 CAR_GPS 订阅(兜底),都断时才用演示数据。首次进入投屏需允许定位权限 |
| 挡位 | 雅阁 CVT(燃油)/E-CVT(e:PHEV),仪表挡位显示 P/D,不显示模拟的 1-5 |
| 日夜 | 12.3 寸屏自带光线传感器(自动亮度)→"自动"模式生效(lux 8/15 滞回);没有则手动三选 |
| 方向盘按键 | 标准 Android 键码直接转发手机(媒体/方向键已映射);本田自定义键码待真机日志确认,对应工程模式菜单可查 |
| 无 adb 调试 | 会话日志自动写 Android/data/<包名>/files/logs/,设置页可见路径,拷出即可 |
| 保修提醒 | 工程模式/侧载属于非官方操作,风险自担;本 App 无需 root |

## 构建

```bat
set GRADLE_USER_HOME=D:\gradle-home   (可选,缓存重定向)
gradlew.bat :mobile:assembleDebug
```

依赖:JDK 17+(Android Studio 自带 JBR 即可)、Android SDK platform 37、build-tools 36/37。

## 协议资料

`docs/` 目录:protocol-notes.md(协议总纲)、carlife-constants.md(常量表)、carlife-proto-messages.md(消息结构)。
参考实现(上游,见 `D:\refs\`):百度官方车机端库(Apache-2.0)、协议分析工具(Apache-2.0)。

## 品牌与签名（有意为之的决定，须知风险）

- **包名沿用 `com.shihab.diplay.cn`**：品牌已改名 Limo,包名不变是为了让 DiPlay-CN 老用户直接覆盖安装、保留旧数据。如果未来要做应用内更新或上架,需要迁移包名并做数据迁移,越晚成本越高。
- **签名密钥随仓库分发**(`signing/diplay-cn.jks`,密码在 `mobile/build.gradle.kts`):这是"任何人可从源码构建出可覆盖安装的包"的有意取舍。后果:**任何人都能用这把 key 签出冒名官方的更新包**。在引入应用内更新之前,更新验证只能依赖人工核对下载来源;届时应改用服务器端签名校验,而不是依赖包名。

## 兼容性说明

- 有线:Android 手机 + 百度 CarLife 手机版(理论上全版本,待真机验证)
- 无线:CarLife 手机端 ≤ 6.7.2;小米 CarWith 已实测互通(2026-10-24,CarWith 3.8.0 + 小米定制 CarLife 1.1.5,双侧日志对齐:版本协商/四步鉴权/编码器协商全部通过,明文会话)。注意:首次连接需在手机上确认 MIUI 投屏授权弹窗,车机侧看门狗给 36 秒宽限(3.1.1+);早期"7.2+ 协议变更不兼容"的记录不成立
- 车机:通用 Android 9+(AOA 主机模式);无线模式需车机与手机同网
- 本项目未获百度认证,与 DiPlay 的 CarPlay 通道相同,仅面向个人研究与测试用途

## 许可

- 本项目:GPL-3.0(继承自 DiPlay/DiPlay-CN)
- 百度 CarLife-Vehicle-Lib / 协议分析工具:Apache-2.0(仅作协议参考,未直接复制代码;如后续引入 JNI 移植将保留其版权声明)
