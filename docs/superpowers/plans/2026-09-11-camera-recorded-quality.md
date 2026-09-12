# 录制式摄像头质量检测 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在摄像头测试页录制 8 秒视频，并在本地抽帧输出可解释的画面质量测试结果。

**Architecture:** `RecordedFrameQuality` 提供可单测的帧质量汇总和视频抽样时间点。`Camera2PreviewController` 添加视频录制 Surface 并管理 MediaRecorder 生命周期；Compose 页面负责按钮、倒计时、后台解码和显示/回写测试结果。

**Tech Stack:** Kotlin、Android Camera2、MediaRecorder、MediaMetadataRetriever、Jetpack Compose、JUnit 4、Kotlin coroutines。

**Spec:** `docs/superpowers/specs/2026-09-11-camera-recorded-quality-design.md`

## Global Constraints

- Android 最低 API 26，目标为 RK3588 Android 设备。
- 仅在设备本地处理；不增加网络或模型依赖。
- 录像时长固定 8 秒，视频仅写入应用专属外部存储 `evidence/`。
- 录制期间必须锁定相机选择、曝光、对焦和分辨率。
- 视频抽样每 500 ms 一帧，最多 16 帧；解码与分析不阻塞主线程。

---

### Task 1: 录制帧质量汇总逻辑

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/camera/RecordedFrameQuality.kt`
- Create: `app/src/test/java/com/rk/hardwaretest/camera/RecordedFrameQualityTest.kt`

**Interfaces:**
- Produces: `data class RecordedFrameSample(sharpness: Float, darkPercent: Float, brightPercent: Float, contrast: Float)`.
- Produces: `fun assessRecordedFrames(samples: List<RecordedFrameSample>): RecordedQualityAssessment`.
- Produces: `fun videoSampleTimesUs(durationMs: Long, intervalMs: Long = 500): List<Long>`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun sharp_and_well_exposed_frames_pass() {
    val result = assessRecordedFrames(List(16) { RecordedFrameSample(80f, 2f, 2f, 35f) })
    assertEquals(RecordedQualityStatus.PASS, result.status)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest`

Expected: compile failure because `RecordedFrameSample` and `assessRecordedFrames` do not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
enum class RecordedQualityStatus { PASS, WARNING, FAIL }
data class RecordedQualityAssessment(val status: RecordedQualityStatus, val reasons: List<String>)

fun assessRecordedFrames(samples: List<RecordedFrameSample>): RecordedQualityAssessment {
    if (samples.isEmpty()) return RecordedQualityAssessment(RecordedQualityStatus.FAIL, listOf("未能解码视频画面"))
    val worstCount = maxOf(1, kotlin.math.ceil(samples.size / 10.0).toInt())
    val blur = samples.sortedBy { it.sharpness }.take(worstCount).first().sharpness
    val dark = samples.sortedByDescending { it.darkPercent }.take(worstCount).first().darkPercent
    val bright = samples.sortedByDescending { it.brightPercent }.take(worstCount).first().brightPercent
    val contrast = samples.sortedBy { it.contrast }.take(worstCount).first().contrast
    val reasons = buildList {
        if (blur < 40f) add("可能失焦或画面模糊")
        if (dark > 60f) add("画面过暗或镜头被遮挡")
        if (bright > 35f) add("画面过曝")
        if (contrast < 12f) add("对比度过低")
    }
    return RecordedQualityAssessment(if (reasons.isEmpty()) RecordedQualityStatus.PASS else RecordedQualityStatus.WARNING, reasons)
}
```

- [ ] **Step 4: Add and run boundary tests**

```kotlin
@Test fun empty_video_frames_fail() = assertEquals(RecordedQualityStatus.FAIL, assessRecordedFrames(emptyList()).status)
@Test fun blurry_frames_warn() = assertEquals(RecordedQualityStatus.WARNING, assessRecordedFrames(listOf(RecordedFrameSample(39f, 0f, 0f, 20f))).status)
@Test fun eight_second_video_has_sixteen_sample_times() = assertEquals(16, videoSampleTimesUs(8_000).size)
```

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest`

Expected: PASS.

### Task 2: 从视频帧生成质量样本

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/RecordedFrameQuality.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/RecordedFrameQualityTest.kt`

**Interfaces:**
- Consumes: `RecordedFrameSample` and `videoSampleTimesUs` from Task 1.
- Produces: `fun bitmapFrameSample(argb: IntArray, width: Int, height: Int): RecordedFrameSample`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun uniform_gray_frame_has_zero_contrast_and_sharpness() {
    val sample = bitmapFrameSample(IntArray(9) { 0xff808080.toInt() }, 3, 3)
    assertEquals(0f, sample.contrast, 0.01f)
    assertEquals(0f, sample.sharpness, 0.01f)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest`

Expected: compile failure because `bitmapFrameSample` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
fun bitmapFrameSample(argb: IntArray, width: Int, height: Int): RecordedFrameSample {
    require(argb.size == width * height)
    val luma = FloatArray(argb.size) { index ->
        val pixel = argb[index]
        ((77 * ((pixel shr 16) and 0xff) + 150 * ((pixel shr 8) and 0xff) + 29 * (pixel and 0xff)) shr 8).toFloat()
    }
    val mean = luma.average().toFloat()
    val contrast = kotlin.math.sqrt(luma.sumOf { (it - mean) * (it - mean) / luma.size }).toFloat()
    val sharpness = laplacianVariance(luma, width, height)
    return RecordedFrameSample(sharpness, luma.count { it < 20f } * 100f / luma.size, luma.count { it > 235f } * 100f / luma.size, contrast)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest`

Expected: PASS.

### Task 3: Camera2 视频录制生命周期

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/Camera2PreviewController.kt`

**Interfaces:**
- Consumes: active `CameraRequestConfig` and `File` output.
- Produces: `fun startRecording(output: File, onStarted: () -> Unit, onError: (String) -> Unit)` and `fun stopRecording(onStopped: (File) -> Unit, onError: (String) -> Unit)`.

- [ ] **Step 1: Add a compile-time caller test seam**

```kotlin
// The controller exposes startRecording/stopRecording so the UI can drive it;
// pure frame analysis remains covered by JVM tests because Camera2 needs hardware.
```

- [ ] **Step 2: Implement recorder setup and session recreation**

```kotlin
val recorder = MediaRecorder().apply {
    setVideoSource(MediaRecorder.VideoSource.SURFACE)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    setVideoSize(size.width, size.height)
    setVideoFrameRate(30)
    setVideoEncodingBitRate(size.width * size.height * 4)
    setOutputFile(output.absolutePath)
    prepare()
}
```

Include `recorder.surface` in `createCaptureSession` only while recording. On configuration success, start the recorder; on stop, stop/release it and recreate preview-only session. Release the recorder in every error and `release()` path.

- [ ] **Step 3: Build the debug APK**

Run: `./gradlew.bat assembleDebug`

Expected: build succeeds. Hardware validation is deferred to a physical RK3588 device.

### Task 4: 摄像头页面交互、视频解码和结果回写

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`

**Interfaces:**
- Consumes: controller recording callbacks, `bitmapFrameSample`, `videoSampleTimesUs`, and `assessRecordedFrames`.
- Produces: `TestResult` under `"camera"` with quality status, reasons, frame count, duration, and evidence filename.

- [ ] **Step 1: Implement the UI flow**

```kotlin
Button(enabled = phase == Idle, onClick = { controller?.startRecording(output, ::onStarted, ::onError) }) {
    Text(when (phase) { Recording -> "录制中：$remaining 秒"; Analysing -> "正在分析视频…"; else -> "录制 8 秒并检测" })
}
```

Have `CameraPreview` expose its controller and an event callback. Start a coroutine that stops at eight seconds, decodes frames using `MediaMetadataRetriever` on `Dispatchers.Default`, maps assessment status to `TestStatus`, and then calls the camera result updater on the main thread. Use `DisposableEffect` to cancel a job and release controller when leaving the page.

- [ ] **Step 2: Run all JVM tests and build**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`

Expected: all tests and APK build pass.

### Task 5: Documentation and manual validation checklist

**Files:**
- Modify: `README.MD`

**Interfaces:**
- Consumes: final UI labels and evidence location.
- Produces: instructions for operating the recording quality test and interpreting its output.

- [ ] **Step 1: Document device validation**

```markdown
- 摄像头页可录制 8 秒视频后离线检测清晰度、曝光与对比度；录像证据写入应用专属外部存储的 `evidence/` 目录。
- 请在目标设备验证预览不中断、倒计时正确、录像可回放，以及故意遮挡/失焦/强光场景会产生对应提示。
```

- [ ] **Step 2: Re-run final verification**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`

Expected: all unit tests pass and debug APK is generated.
