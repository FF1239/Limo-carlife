# 假车机测试指南(DiPlay-CarLife Desktop Emulator)

在 Windows 上模拟一台 **2025 款雅阁中配车机**(12.3 英寸 1920×720 触摸屏),与你提供的 Android 手机跑通完整 CarLife 链路:握手 → 视频 → 音频 → 触摸/硬键回传。

## 启动

```bat
:: ADB 模式(推荐,最稳):手机 USB 线插电脑,打开「USB 调试」
D:\DiPlay-CarLife\desktop-emulator\build\install\desktop-emulator\bin\desktop-emulator.bat adb

:: WiFi 模式:手机与电脑同一 WiFi,手机 CarLife 点「无线连接」后,填手机 IP
desktop-emulator.bat wifi 192.168.1.23
```

## 手机端准备

1. 安装百度 CarLife 手机版(**建议 6.7.2 或更早;7.2+ 无线协议变更已知不兼容**;小米手机可用 CarWith)
2. ADB 模式:设置 → 开发者选项 → 打开「USB 调试」,插线,弹窗允许调试
3. 打开 CarLife 应用,停留在主界面/连接等待页

## 电脑端操作流程(ADB 模式)

1. 确认手机可见:`D:\Android\Sdk\platform-tools\adb.exe devices` 应列出设备
2. 运行 `desktop-emulator.bat adb` —— 自动执行 6 条端口转发(7240/8240/9240/9241/9242/9340)并连接
3. 窗口底部状态栏依次应显示:`6 通道已连接 → 发起握手 → 协议版本匹配 → 手机信息 → 视频协商`
4. 手机画面出现在 12.3" 窗口中即链路跑通

## 操作对照(模拟的是雅阁触摸屏)

| 电脑操作 | 发给手机 |
|---|---|
| 鼠标按下/拖动/松开 | 触摸 DOWN/MOVE/UP(坐标换算) |
| Enter / 空格 | OK 键 |
| Backspace / Esc | BACK 键 |
| 方向键 | 方向移动键 |
| PageUp/PageDown | 上一曲/下一曲 |
| F1 | 语音(VR)启动 |

## 验证清单

- [ ] 6 个 TCP 通道全部连上(ADB 模式 127.0.0.1;WiFi 模式手机 IP)
- [ ] 协议版本 matchStatus=1
- [ ] 收到手机设备信息(厂商/型号/Android 版本)
- [ ] 视频编码器协商(默认 768×480@30)并出现画面
- [ ] 媒体音频出声(手机放歌)
- [ ] 触摸点击手机上的图标,手机有响应
- [ ] 硬键返回/确认有响应

## 排错

| 现象 | 处置 |
|---|---|
| adb devices 无设备 | 换数据线/USB 口,重开 USB 调试,允许调试弹窗 |
| 通道连上但握手无响应 | 手机 CarLife 未处于连接等待;重进 CarLife 主界面 |
| 版本匹配失败(matchStatus≠1) | 手机端 CarLife 版本过新,换 6.7.2 及以下 |
| 画面黑屏但字节数增长 | 可能 AVCC 格式或加密流;抓包 `docs/protocol-notes.md` §9 |
| 画面正常但音画不同步 | 预期(首版未做精确 A/V 同步),不影响链路验证 |

## 技术栈

- 复用 `core-carlife` 纯 Kotlin 协议库(与车机端 APK 同一套实现)
- 视频:javacv FFmpegFrameGrabber(h264 裸流,回环 socket 喂流)→ Swing
- 音频:javax.sound.sampled 三路 SourceDataLine
- 窗口:1920×720 Swing(雅阁 12.3" 形态),沉浸黑底
