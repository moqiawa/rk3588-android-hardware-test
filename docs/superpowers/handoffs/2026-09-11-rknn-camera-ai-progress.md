# RKNN 摄像头 AI 检测续办说明

## 用户目标

在 RK3588 Android 设备上，为固定场景摄像头测试加入端侧 AI。用户目前只能提供正常录像；系统应从正常录像建立基线，在后续 8 秒录像检测中识别异常，并对污渍/水雾使用“疑似”表述。用户要求：在 APK 完成前持续工作，不要主动结束对话。

## 已完成

- 已实现 8 秒录像后抽帧的规则检测：清晰度、过暗、过曝、对比度；录像使用预览与编码双输出，避免 RK3588 Camera HAL 拒绝三路输出。
- 已实现正常场景核心逻辑：
  - `NormalSceneBaseline.kt` 按 LOW/MID/HIGH 亮度组建立特征中心与波动统计。
  - `assessSceneAnomalies` 仅在连续 3 帧特征距离超过阈值时报告异常。
  - `NormalSceneBaselineStore.kt` 将基线保存到应用私有 `SharedPreferences`。
- 已从 Rockchip 官方 Toolkit2 取得并打包：
  - `app/src/main/assets/camera_quality_feature.rknn`：RK3588 MobileNet V1 模型，4,693,865 字节。
  - `app/src/main/jniLibs/arm64-v8a/librknnrt.so`：官方 Android RKNN Runtime，8,387,408 字节。
  - `app/src/main/cpp/include/rknn_api.h`：官方 C API 头文件。
- 已实现并成功编译 JNI：
  - `app/src/main/cpp/rknn_feature_extractor.cpp`：从 APK asset 加载 RKNN 模型、传入 RGB 224×224、调用 `rknn_inputs_set` / `rknn_run` / `rknn_outputs_get` 并返回浮点特征。
  - `app/src/main/cpp/CMakeLists.txt`：链接 `librknnrt.so`、`libandroid` 与 `liblog`。
  - `AiFrameFeatureExtractor.kt`：提供 `RknnFeatureExtractor`、`UnavailableFeatureExtractor` 和结果类型。
- 已下载项目本地 NDK：`.vendor/android-ndk-r27c`；`local.properties` 已设置 `ndk.dir`，`app/build.gradle.kts` 已设 `ndkVersion = "27.2.12479018"` 及 CMake externalNativeBuild。

## 最新验证证据

最后一次执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

结果：`BUILD SUCCESSFUL`，45 个 Gradle task 中 12 个执行。构建时存在 `ndk.dir` 已弃用的警告，但不影响 APK 生成。最新 APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 尚未完成（必须继续）

1. **正常录像导入界面**
   - 在 `CameraPage` 添加 `ActivityResultContracts.OpenDocument()`，接收 `video/*`。
   - 抽取最多 120 帧，过滤过暗/过曝帧，缩放为 224×224 RGB。
   - 使用 `RknnFeatureExtractor` 提取特征，转换为 `AiFrameFeature`，调用 `buildSceneBaseline`，成功后调用 `saveBaseline`。
   - 显示“尚未建立基线 / 正在建立 / 已建立（帧数）/ AI 不可用（原因）”。

2. **录像后 AI 融合**
   - 改造 `analyseRecordedVideo`，在每张已抽取的 Bitmap 上生成 RGB 224×224 和 RGB 均值。
   - 若 `loadBaseline(context)` 非空且 `RknnFeatureExtractor` 可用，则执行 `assessSceneAnomalies`；否则保留规则检测并显示 AI 跳过原因。
   - 将 AI 异常分、亮度组、原因、基线状态写入摄像头 `TestResult.details`。
   - 规则告警优先于无解释的 AI 异常；污渍提示必须是“画面异常，疑似镜头污渍、水雾或场景偏移”。

3. **RK3588 实机验证**
   - 使用 `adb -s 10.0.100.157:5555 install -r app/build/outputs/apk/debug/app-debug.apk` 安装。
   - 导入正常录像建立基线，再录制正常 8 秒视频。
   - 查看应用 AI 状态或 `logcat`，确认 `RknnFeatureExtractor` 没有返回不可用。
   - 使用遮挡/雾化/偏色/失焦/极端光照进行人工验证和阈值校准。

## 注意事项

- 当前资产使用 Toolkit2 自带的 MobileNet V1，而不是原设计中的 MobileNetV3-Small；它提供 1000 维图像特征用于正常基线距离比较。后续有训练好的固定场景模型时，可替换 `camera_quality_feature.rknn`，但 JNI 输入/输出合同必须同步验证。
- 设备 BSP 中的 NPU 驱动必须与 APK 内 `librknnrt.so` 兼容。若 Runtime 初始化失败，应保留规则检测并从 `AiFeatureResult.Unavailable` 显示原因。
- 当前工作区还存在与本任务无关的 `text.txt` 删除和未跟踪 `.gitignore`；不要擅自恢复或删除。
- `.vendor/` 含大型官方 SDK 与 NDK 文件；不应提交到版本库。
