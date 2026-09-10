# RK3588 Android 硬件测试

面向 RK3588 Android 设备的硬件自检应用。应用以 Jetpack Compose 实现，集中展示测试结果，并支持摄像头、音频、网络和接口状态的现场核验。

## 功能

- **一键测试**：汇总摄像头、扬声器、麦克风和网络测试结果，并显示 GPIO、USB、音频设备及视频节点概况。
- **摄像头测试**：切换已发现的摄像头，预览画面，调整曝光、对焦和分辨率；显示亮度统计。
- **扬声器测试**：播放可调频率、时长和振幅的测试音，也可导入本地音频播放。
- **麦克风测试**：选择输入设备，实时显示峰值/RMS 电平，并执行约 3 秒的录音采样检查。
- **网络测试**：显示当前网络、IP、DNS、网关、Wi-Fi 链路速率与实时收发速率；可通过公开 CDN 下载测速。
- **接口测试**：只读刷新 GPIO 状态，列出 USB 外设用途、音频设备、摄像头/视频节点和网络接口。

## 环境要求

- Android Studio（可使用内置 Gradle）
- JDK 17
- Android SDK：编译 SDK 36；最低支持 Android 8.0（API 26）
- 目标设备：RK3588 Android 设备。摄像头、麦克风、扬声器、Wi-Fi、USB 或 GPIO 等项目须由对应硬件实际提供。

## 构建与安装

在本目录执行以下命令（Windows）：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/`。若使用 Android Studio，可打开本目录后运行 `app` 配置。

## 单元测试

```powershell
.\gradlew.bat testDebugUnitTest
```

单元测试覆盖音频电平与播放策略、摄像头画面质量、网络速率计算、GPIO 解析、USB 设备用途和测试结果模型等纯逻辑；真实硬件功能仍需在目标设备上手动验证。

## 权限与运行说明

首次运行时，请允许摄像头与录音权限。应用还在清单中声明网络、网络状态和 Wi-Fi 状态权限。

- GPIO 读取路径固定为 `/sys/class/gpio_sw/io1` 至 `io4`；设备内核未提供这些节点时会显示不可用。
- USB 页面展示内核已识别的设备用途；普通 Android 应用无法判断其他应用是否独占某设备。
- Wi-Fi 名称、链路速率等信息受 Android 版本与系统权限限制，缺失时会显示不可读取或未提供。
- 麦克风采样证据会写入应用专属外部存储的 `evidence/` 目录，不会写入本项目目录。

## 项目结构

```text
app/
  src/main/              应用源码与 AndroidManifest
  src/test/              本地 JVM 单元测试
docs/                    设计与实施文档
logs/build/              本地 Gradle 构建输出（不纳入版本控制）
diagnostics/heapdumps/   本地 JVM 堆转储（不纳入版本控制）
```

## 本地诊断文件

构建日志统一放在 `logs/build/`，每个测试场景保留成对的 `.stdout.log` 与 `.stderr.log` 文件。JVM 堆转储放在 `diagnostics/heapdumps/`，通常体积较大且可能包含运行时敏感数据，不应提交到版本库或对外共享。
