# RK3588 硬件测试程序实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 构建一个可安装到 RK3588 星光麒麟安卓兼容系统的独立 APK，用于测试所有摄像头、扬声器、麦克风和网络，并保存证据与 JSON 报告。

**架构：** `MainActivity` 使用 Compose 呈现总览和详情。`HardwareTestViewModel` 调用 `TestCoordinator` 串行运行硬件测试；可单元测试的分析与状态映射逻辑保持为纯 Kotlin 类，Android 平台服务封装 CameraX、音频和网络 API。`ReportStore` 将每轮结果和相对证据路径落盘为 JSON。

**技术栈：** Kotlin、Android Gradle Plugin 8.13.2、Jetpack Compose、CameraX 1.6.1、Kotlin Coroutines、JUnit 4。

**设计文档：** `docs/superpowers/specs/2026-09-10-rk3588-hardware-test-design.md`

## 全局约束

- 仅生成 `arm64-v8a` APK，`minSdk=26`、`targetSdk=35`、`compileSdk=36`、Java 17。
- 应用 ID 使用 `com.rk.hardwaretest`；不得依赖 RKNN、NPU 模型或参考项目的业务模块。
- 仅请求 `CAMERA` 与 `RECORD_AUDIO` 运行时权限；证据仅保存到 `getExternalFilesDir()` 返回的应用专属目录。
- 扬声器测试禁止调用 `setStreamVolume`、`adjustStreamVolume` 或任何会改变系统音量的 API。
- 所有可单元测试的新逻辑先写失败测试，再写最小实现；每完成一个任务运行其测试。

---

## 文件结构

- `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml`、`gradlew`、`gradlew.bat`、`gradle/wrapper/*`：独立 Gradle 工程、依赖版本与 Gradle Wrapper。
- `app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`：APK、权限、ABI 与启动入口配置。
- `app/src/main/java/com/rk/hardwaretest/model/TestModels.kt`：测试状态、结果和报告数据模型。
- `app/src/main/java/com/rk/hardwaretest/analysis/PcmQualityAnalyzer.kt`：PCM 信号质量计算。
- `app/src/main/java/com/rk/hardwaretest/analysis/SpeakerVolumePolicy.kt`：不改变音量的扬声器结果判定。
- `app/src/main/java/com/rk/hardwaretest/network/NetworkSnapshotMapper.kt`：网络平台数据到展示模型的映射。
- `app/src/main/java/com/rk/hardwaretest/report/ReportJsonEncoder.kt`、`ReportStore.kt`：报告编码和存储。
- `app/src/main/java/com/rk/hardwaretest/audio/AudioTestService.kt`：输入设备枚举、WAV 录音分析与测试音播放。
- `app/src/main/java/com/rk/hardwaretest/network/NetworkTestService.kt`：实际网络快照与 HTTPS 可达性检测。
- `app/src/main/java/com/rk/hardwaretest/camera/CameraTestService.kt`：摄像头枚举、逐路预览、拍照与录像。
- `app/src/main/java/com/rk/hardwaretest/TestCoordinator.kt`、`HardwareTestViewModel.kt`：异步测试编排和 UI 状态。
- `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`、`ui/HardwareTestScreen.kt`、`ui/Theme.kt`：运行时权限与界面。
- `app/src/test/java/com/rk/hardwaretest/...`：纯 Kotlin 单元测试。

## 任务 1：创建可构建的独立工程与测试模型

**文件：**

- 创建：`settings.gradle.kts`
- 创建：`build.gradle.kts`
- 创建：`gradle/libs.versions.toml`
- 创建：`app/build.gradle.kts`
- 创建：`app/src/main/AndroidManifest.xml`
- 创建：`app/src/main/java/com/rk/hardwaretest/model/TestModels.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/model/TestModelsTest.kt`

**接口：**

- 产出：`enum class TestStatus { NOT_TESTED, RUNNING, PASS, WARNING, FAIL, PERMISSION_REQUIRED }`
- 产出：`data class TestResult(val status: TestStatus, val summary: String, val details: Map<String, String> = emptyMap(), val evidencePaths: List<String> = emptyList())`
- 产出：`data class TestRunReport(val runId: String, val createdAtEpochMs: Long, val device: Map<String, String>, val results: Map<String, TestResult>)`

- [ ] **步骤 1：建立最小 Gradle 测试环境**

从参考工程复制 Gradle Wrapper（`gradlew`、`gradlew.bat` 和 `gradle/wrapper/`），创建根设置、版本目录、应用模块和空的 `TestModels.kt`，使 `:app:testDebugUnitTest` 能发现 JVM 测试。此步骤只创建构建配置与空源文件，不定义 `TestStatus`、`TestResult` 或 `TestRunReport`。

- [ ] **步骤 2：编写失败测试**

```kotlin
@Test fun result_defaults_to_no_details_or_evidence() {
    val result = TestResult(TestStatus.PASS, "完成")
    assertTrue(result.details.isEmpty())
    assertTrue(result.evidencePaths.isEmpty())
}
```

- [ ] **步骤 3：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.model.TestModelsTest`

预期：失败，提示 `TestResult` 或 `TestStatus` 尚未定义。

- [ ] **步骤 4：写入最小模型实现**

```kotlin
enum class TestStatus { NOT_TESTED, RUNNING, PASS, WARNING, FAIL, PERMISSION_REQUIRED }
data class TestResult(
    val status: TestStatus,
    val summary: String,
    val details: Map<String, String> = emptyMap(),
    val evidencePaths: List<String> = emptyList(),
)
```

应用配置使用 `com.rk.hardwaretest`、`minSdk=26`、`targetSdk=35`、`compileSdk=36`、`ndk.abiFilters += "arm64-v8a"`；清单声明相机、录音和网络权限以及启动 Activity。

- [ ] **步骤 5：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.model.TestModelsTest`

预期：通过。

- [ ] **步骤 6：提交**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat app
git commit -m "feat: scaffold hardware test app"
```

## 任务 2：实现麦克风 PCM 质量分析

**文件：**

- 创建：`app/src/main/java/com/rk/hardwaretest/analysis/PcmQualityAnalyzer.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/analysis/PcmQualityAnalyzerTest.kt`

**接口：**

- 产出：`data class PcmQuality(val frameCount: Int, val peakDbfs: Double, val rmsDbfs: Double, val clippingPercent: Double, val nearSilencePercent: Double, val hasNonSilentInput: Boolean)`
- 产出：`fun analyzePcm16(samples: ShortArray, silenceThreshold: Int = 500): PcmQuality`

- [ ] **步骤 1：编写失败测试**

```kotlin
@Test fun analyzer_reports_non_silent_signal_and_peak() {
    val quality = analyzePcm16(shortArrayOf(0, 1_000, -2_000, 2_000))
    assertEquals(4, quality.frameCount)
    assertTrue(quality.hasNonSilentInput)
    assertEquals(0.0, quality.clippingPercent, 0.001)
    assertTrue(quality.peakDbfs < 0.0)
}

@Test fun analyzer_reports_clipping_and_silence() {
    val quality = analyzePcm16(shortArrayOf(0, 0, Short.MAX_VALUE, Short.MIN_VALUE))
    assertEquals(50.0, quality.clippingPercent, 0.001)
    assertEquals(50.0, quality.nearSilencePercent, 0.001)
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.analysis.PcmQualityAnalyzerTest`

预期：失败，提示 `analyzePcm16` 尚未定义。

- [ ] **步骤 3：写入最小实现**

```kotlin
fun analyzePcm16(samples: ShortArray, silenceThreshold: Int = 500): PcmQuality {
    val absValues = samples.map { kotlin.math.abs(it.toInt()) }
    val peak = absValues.maxOrNull() ?: 0
    val rms = kotlin.math.sqrt(samples.map { it.toDouble() * it }.average().takeIf { !it.isNaN() } ?: 0.0)
    fun dbfs(value: Double) = if (value <= 0.0) -160.0 else 20.0 * kotlin.math.log10(value / Short.MAX_VALUE)
    return PcmQuality(
        samples.size, dbfs(peak.toDouble()), dbfs(rms),
        100.0 * absValues.count { it >= Short.MAX_VALUE }.toDouble() / samples.size.coerceAtLeast(1),
        100.0 * absValues.count { it < silenceThreshold }.toDouble() / samples.size.coerceAtLeast(1),
        absValues.any { it >= silenceThreshold },
    )
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.analysis.PcmQualityAnalyzerTest`

预期：通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/rk/hardwaretest/analysis app/src/test/java/com/rk/hardwaretest/analysis
git commit -m "feat: analyze microphone PCM quality"
```

## 任务 3：实现扬声器音量判定与网络状态映射

**文件：**

- 创建：`app/src/main/java/com/rk/hardwaretest/analysis/SpeakerVolumePolicy.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/network/NetworkSnapshotMapper.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/analysis/SpeakerVolumePolicyTest.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/network/NetworkSnapshotMapperTest.kt`

**接口：**

- 产出：`fun speakerResult(currentVolume: Int, maxVolume: Int, playbackAdvanced: Boolean): TestResult`
- 产出：`data class RawNetworkState(val connected: Boolean, val transports: Set<String>, val validated: Boolean, val addresses: List<String>, val dnsServers: List<String>)`
- 产出：`fun mapNetworkState(raw: RawNetworkState, httpsReachable: Boolean?): TestResult`

- [ ] **步骤 1：编写失败测试**

```kotlin
@Test fun zero_system_volume_is_warning_without_failure() {
    assertEquals(TestStatus.WARNING, speakerResult(0, 15, playbackAdvanced = true).status)
}

@Test fun validated_ethernet_with_https_is_pass() {
    val result = mapNetworkState(RawNetworkState(true, setOf("Ethernet"), true, listOf("192.168.1.2"), listOf("192.168.1.1")), true)
    assertEquals(TestStatus.PASS, result.status)
    assertEquals("Ethernet", result.details.getValue("transport"))
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.analysis.SpeakerVolumePolicyTest --tests com.rk.hardwaretest.network.NetworkSnapshotMapperTest`

预期：失败，提示所需函数和模型尚未定义。

- [ ] **步骤 3：写入最小实现**

`speakerResult` 仅基于传入的音量和播放进度构造 `TestResult`，不得接收或调用任何音量修改器。`mapNetworkState` 按“未连接=失败；已连接但未验证/HTTPS 不可达=警告；已验证且 HTTPS 可达=通过”生成结果，并将传输、IP、DNS 写入 `details`。

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.analysis.SpeakerVolumePolicyTest --tests com.rk.hardwaretest.network.NetworkSnapshotMapperTest`

预期：通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/rk/hardwaretest app/src/test/java/com/rk/hardwaretest
git commit -m "feat: add speaker and network evaluation"
```

## 任务 4：实现报告 JSON 编码和证据存储

**文件：**

- 创建：`app/src/main/java/com/rk/hardwaretest/report/ReportJsonEncoder.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/report/ReportStore.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/report/ReportJsonEncoderTest.kt`

**接口：**

- 产出：`fun encodeReport(report: TestRunReport): String`
- 产出：`class ReportStore(context: Context) { fun createRunDirectory(runId: String): File; fun saveReport(report: TestRunReport): File }`

- [ ] **步骤 1：编写失败测试**

```kotlin
@Test fun encoder_includes_run_status_and_evidence_path() {
    val report = TestRunReport("run-1", 1L, mapOf("model" to "RK3588"), mapOf("camera" to TestResult(TestStatus.PASS, "完成", evidencePaths = listOf("camera/a.jpg"))))
    val json = encodeReport(report)
    assertTrue(json.contains("\"runId\":\"run-1\""))
    assertTrue(json.contains("camera/a.jpg"))
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.report.ReportJsonEncoderTest`

预期：失败，提示编码器尚未定义。

- [ ] **步骤 3：写入最小实现**

使用 Android `JSONObject`/`JSONArray` 从模型构造 UTF-8 JSON；`ReportStore` 将报告写入 `context.getExternalFilesDir("reports")/<runId>/report.json`，创建目录失败时抛出带路径说明的 `IOException`。

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.report.ReportJsonEncoderTest`

预期：通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/rk/hardwaretest/report app/src/test/java/com/rk/hardwaretest/report
git commit -m "feat: persist hardware test reports"
```

## 任务 5：实现音频平台服务

**文件：**

- 创建：`app/src/main/java/com/rk/hardwaretest/audio/AudioTestService.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/audio/WavWriter.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/audio/WavWriterTest.kt`

**接口：**

- 产出：`suspend fun enumerateInputs(): List<AudioInputInfo>`
- 产出：`suspend fun recordMicrophone(runDirectory: File, durationMs: Long = 5_000): TestResult`
- 产出：`suspend fun playSpeakerTone(durationMs: Long = 3_000): TestResult`
- 产出：`fun writeWavHeader(output: OutputStream, sampleRate: Int, channels: Int, pcmByteCount: Long)`

- [ ] **步骤 1：编写失败测试**

```kotlin
@Test fun wav_header_declares_pcm_sample_rate_and_data_size() {
    val bytes = ByteArrayOutputStream().also { writeWavHeader(it, 48_000, 1, 9_600) }.toByteArray()
    assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
    assertEquals("WAVE", bytes.copyOfRange(8, 12).decodeToString())
    assertEquals(9_600, ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.audio.WavWriterTest`

预期：失败，提示 `writeWavHeader` 尚未定义。

- [ ] **步骤 3：写入最小实现**

`AudioTestService` 使用 `AudioManager.getDevices(GET_DEVICES_INPUTS)` 生成输入设备详情；选择首个 `AudioRecord.getMinBufferSize` 成功的 48 kHz/44.1 kHz/16 kHz 单声道 PCM 配置，录制 5 秒、写入正确 WAV 头和 PCM 内容，再调用 `analyzePcm16`。播放使用 `AudioTrack` 与 `USAGE_MEDIA`、`CONTENT_TYPE_MUSIC`，且只读取 `STREAM_MUSIC` 音量，将播放头推进结果交给 `speakerResult`。

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.audio.WavWriterTest`

预期：通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/rk/hardwaretest/audio app/src/test/java/com/rk/hardwaretest/audio
git commit -m "feat: add microphone and speaker hardware tests"
```

## 任务 6：实现网络和摄像头平台服务

**文件：**

- 创建：`app/src/main/java/com/rk/hardwaretest/network/NetworkTestService.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/camera/CameraTestService.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/camera/CameraTestState.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/camera/CameraTestStateTest.kt`

**接口：**

- 产出：`suspend fun NetworkTestService.test(): TestResult`
- 产出：`data class CameraDescriptor(val id: String, val lensFacing: String, val outputSizes: List<String>)`
- 产出：`suspend fun CameraTestService.enumerate(): List<CameraDescriptor>`
- 产出：`suspend fun CameraTestService.testAll(runDirectory: File): List<Pair<String, TestResult>>`
- 产出：`fun nextCameraState(current: CameraTestPhase, event: CameraTestEvent): CameraTestPhase`

- [ ] **步骤 1：编写失败测试**

```kotlin
@Test fun completed_photo_advances_to_video_recording() {
    assertEquals(CameraTestPhase.RECORDING_VIDEO, nextCameraState(CameraTestPhase.CAPTURING_PHOTO, CameraTestEvent.PHOTO_SAVED))
}

@Test fun failure_reaches_terminal_failed_state() {
    assertEquals(CameraTestPhase.FAILED, nextCameraState(CameraTestPhase.OPENING, CameraTestEvent.FAILED))
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraTestStateTest`

预期：失败，提示摄像头状态类型和转换函数尚未定义。

- [ ] **步骤 3：写入最小实现**

`NetworkTestService` 从 `ConnectivityManager` 取得默认网络、`NetworkCapabilities` 和 `LinkProperties`，在 `Dispatchers.IO` 上对固定 HTTPS 地址执行 5 秒超时的 `HEAD` 请求，再调用 `mapNetworkState`。`CameraTestService` 以 `CameraManager.cameraIdList` 枚举全部 ID，并在主线程绑定 CameraX 的 `Preview`、`ImageCapture`、`VideoCapture`；每路相机分别保存 JPEG 和 5 秒 MP4，验证非空后解绑。任何单路异常转化为该路 `FAIL`，循环继续。

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraTestStateTest`

预期：通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/rk/hardwaretest/camera app/src/main/java/com/rk/hardwaretest/network app/src/test/java/com/rk/hardwaretest/camera
git commit -m "feat: add camera and network hardware services"
```

## 任务 7：实现协调器、权限处理和总览界面

**文件：**

- 创建：`app/src/main/java/com/rk/hardwaretest/TestCoordinator.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/HardwareTestViewModel.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/ui/HardwareTestScreen.kt`
- 创建：`app/src/main/java/com/rk/hardwaretest/ui/Theme.kt`
- 创建：`app/src/test/java/com/rk/hardwaretest/TestCoordinatorTest.kt`

**接口：**

- 产出：`data class HardwareTestUiState(val results: Map<String, TestResult>, val isRunning: Boolean, val reportPath: String? = null)`
- 产出：`suspend fun TestCoordinator.runAll(): TestRunReport`
- 产出：`fun HardwareTestViewModel.runAllTests()`

- [ ] **步骤 1：编写失败测试**

```kotlin
@Test fun run_all_keeps_camera_failures_and_runs_network_afterwards() = runTest {
    val report = coordinatorWithFailingCameraAndPassingNetwork.runAll()
    assertEquals(TestStatus.FAIL, report.results.getValue("camera").status)
    assertEquals(TestStatus.PASS, report.results.getValue("network").status)
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.TestCoordinatorTest`

预期：失败，提示协调器尚未定义。

- [ ] **步骤 3：写入最小实现**

`TestCoordinator` 以 `supervisorScope` 串行运行摄像头、扬声器、麦克风、网络测试，保留各项失败并最终调用 `ReportStore.saveReport`。`MainActivity` 通过 `RequestMultiplePermissions` 请求摄像头/录音权限。Compose 总览逐项展示状态、详细键值、证据路径、系统媒体音量与“运行全部测试”按钮；运行期间禁用重复触发，界面销毁时取消 ViewModel 协程。

- [ ] **步骤 4：运行测试确认通过**

运行：`./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.TestCoordinatorTest`

预期：通过。

- [ ] **步骤 5：提交**

```bash
git add app/src/main/java/com/rk/hardwaretest app/src/test/java/com/rk/hardwaretest
git commit -m "feat: add hardware test dashboard"
```

## 任务 8：全量验证与生成 APK

**文件：**

- 修改：`README.md`

- [ ] **步骤 1：补充运行说明**

说明 APK 路径、首次启动的权限授予、每路摄像头依次进行拍照/5 秒录像、扬声器音量跟随系统、麦克风指标含义、网络“局域网”和“互联网”状态差异，以及证据/JSON 报告目录。

- [ ] **步骤 2：运行全部 JVM 测试**

运行：`./gradlew.bat :app:testDebugUnitTest`

预期：全部通过，且没有编译警告或失败。

- [ ] **步骤 3：构建调试 APK**

运行：`./gradlew.bat :app:assembleDebug`

预期：通过，并生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **步骤 4：检查 APK ABI**

运行：`jar tf app/build/outputs/apk/debug/app-debug.apk | findstr "lib/arm64-v8a"`

预期：输出仅包含 `lib/arm64-v8a` 下的本机库（若无本机库则 APK 仍可安装；Gradle 的 ABI 过滤器已生效）。

- [ ] **步骤 5：提交**

```bash
git add README.md
git commit -m "docs: document RK3588 hardware test app"
```
