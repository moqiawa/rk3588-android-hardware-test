# 固定场景摄像头正常基线 AI 检测 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 RK3588 Android 设备上使用 NPU 特征提取和正常录像基线，为固定场景摄像头检测提供端侧异常提示，并保持规则检测可用。

**Architecture:** 纯 Kotlin 模块保存正常特征的亮度分组基线、计算距离并聚合连续异常。Android 层负责导入/解码正常录像、调用 `AiFrameFeatureExtractor` 获取 224×224 帧特征；其 RKNN JNI 实现加载 APK 内的静态 INT8 模型。NPU 不可用时提取器返回不可用状态，录像检测继续仅使用规则结果。

**Tech Stack:** Kotlin、Jetpack Compose、Android NDK/CMake、RKNN Runtime C API、MobileNetV3-Small INT8 RKNN、MediaMetadataRetriever、JUnit 4、Kotlin coroutines。

**Spec:** `docs/superpowers/specs/2026-09-11-camera-normal-baseline-ai-design.md`

## Global Constraints

- Android 最低 API 26，运行 ABI 固定为 arm64-v8a，目标芯片为 RK3588。
- 视频、特征、基线和结果只保存在设备本地；不新增网络请求。
- 仅正常样本建立基线，AI 结论对污渍/水雾必须使用“疑似”表述。
- 录制期间维持预览与编码双输出流；视频结束后再执行规则与 AI 分析。
- 无模型、无匹配 Runtime、无基线或推理失败时，录像和规则检测继续运行。
- NPU 模型合同固定为 RGB 224×224 静态输入、1000 维 Float32 特征输出；模型转换与 JNI 后处理必须与该合同一致。

---

### Task 1: 正常基线和异常聚合纯逻辑

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaseline.kt`
- Create: `app/src/test/java/com/rk/hardwaretest/camera/NormalSceneBaselineTest.kt`

**Interfaces:**
- Produces: `data class AiFrameFeature(val luma: Float, val redMean: Float, val greenMean: Float, val blueMean: Float, val embedding: FloatArray)`.
- Produces: `data class SceneBaseline(val groups: Map<LightBand, FeatureBaseline>)`.
- Produces: `fun buildSceneBaseline(frames: List<AiFrameFeature>): SceneBaseline?` and `fun assessSceneAnomalies(baseline: SceneBaseline, frames: List<AiFrameFeature>): SceneAnomalyAssessment`.

- [ ] **Step 1: Write the failing grouping test**

```kotlin
@Test fun baseline_groups_normal_features_by_luminance() {
    val baseline = buildSceneBaseline(listOf(
        feature(luma = 70f, embedding = floatArrayOf(1f, 0f)),
        feature(luma = 80f, embedding = floatArrayOf(0.9f, 0.1f)),
        feature(luma = 150f, embedding = floatArrayOf(0f, 1f)),
        feature(luma = 160f, embedding = floatArrayOf(0.1f, 0.9f)),
        feature(luma = 210f, embedding = floatArrayOf(0.7f, 0.7f)),
        feature(luma = 220f, embedding = floatArrayOf(0.6f, 0.8f)),
    ))
    assertEquals(2, baseline!!.groups.getValue(LightBand.LOW).sampleCount)
    assertEquals(2, baseline.groups.getValue(LightBand.MID).sampleCount)
    assertEquals(2, baseline.groups.getValue(LightBand.HIGH).sampleCount)
}
```

- [ ] **Step 2: Run it to prove it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest`

Expected: compile failure because baseline types and `buildSceneBaseline` do not exist.

- [ ] **Step 3: Implement the smallest baseline contract**

```kotlin
enum class LightBand { LOW, MID, HIGH }
fun lightBand(luma: Float): LightBand = when {
    luma < 110f -> LightBand.LOW
    luma < 190f -> LightBand.MID
    else -> LightBand.HIGH
}
```

Require at least two non-extreme normal frames per present band; store L2-normalized feature center, per-dimension standard deviation, RGB means and sample count.

- [ ] **Step 4: Add anomaly behavior tests and verify green**

```kotlin
@Test fun three_consecutive_distant_features_are_anomalous() {
    val baseline = baselineWithMidEmbedding(floatArrayOf(1f, 0f))
    val assessment = assessSceneAnomalies(baseline, List(3) { feature(150f, floatArrayOf(0f, 1f)) })
    assertTrue(assessment.hasAnomaly)
}
@Test fun isolated_distant_feature_is_not_anomalous() {
    val assessment = assessSceneAnomalies(baselineWithMidEmbedding(floatArrayOf(1f, 0f)), listOf(feature(150f, floatArrayOf(0f, 1f))))
    assertFalse(assessment.hasAnomaly)
}
```

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest`

Expected: PASS.

### Task 2: 基线持久化与颜色归因

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaseline.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/NormalSceneBaselineTest.kt`

**Interfaces:**
- Produces: `fun SceneBaseline.encode(): String` and `fun decodeSceneBaseline(text: String): SceneBaseline?`.
- Produces: `fun colorCastReason(baseline: FeatureBaseline, frame: AiFrameFeature): String?`.

- [ ] **Step 1: Write the failing round-trip and color-cast tests**

```kotlin
@Test fun encoded_baseline_round_trips_without_changing_feature_center() {
    val original = baselineWithMidEmbedding(floatArrayOf(0.6f, 0.8f))
    assertArrayEquals(original.groups.getValue(LightBand.MID).center, decodeSceneBaseline(original.encode())!!.groups.getValue(LightBand.MID).center, 0.0001f)
}
@Test fun channel_shift_beyond_three_standard_deviations_is_reported_as_color_cast() {
    assertEquals("画面偏色", colorCastReason(baselineWithRgbDeviation(2f).groups.getValue(LightBand.MID), feature(150f, floatArrayOf(1f, 0f), red = 140f, green = 100f, blue = 100f)))
}
```

- [ ] **Step 2: Run tests to prove the APIs are missing**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest`

Expected: compile failure for the codec and color-cast functions.

- [ ] **Step 3: Implement atomic persistence inputs**

Serialize a versioned JSON representation using only Kotlin/Android platform JSON types. `decodeSceneBaseline` returns null for unknown version, empty groups, non-finite values or inconsistent feature dimensions.

```kotlin
fun colorCastReason(baseline: FeatureBaseline, frame: AiFrameFeature): String? {
    val shifted = abs(frame.redMean - baseline.redMean) > baseline.redDeviation * 3f ||
        abs(frame.greenMean - baseline.greenMean) > baseline.greenDeviation * 3f ||
        abs(frame.blueMean - baseline.blueMean) > baseline.blueDeviation * 3f
    return if (shifted) "画面偏色" else null
}
```

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest`

Expected: PASS.

### Task 3: RKNN model asset and native feature extractor

**Files:**
- Create: `app/src/main/assets/camera_quality_feature.rknn`
- Create: `app/src/main/jniLibs/arm64-v8a/librknnrt.so`
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/rknn_feature_extractor.cpp`
- Create: `app/src/main/java/com/rk/hardwaretest/camera/AiFrameFeatureExtractor.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: RGB bytes ordered `R,G,B`, exactly `224 * 224 * 3` bytes.
- Produces: `sealed interface AiFeatureResult { data class Available(val embedding: FloatArray) : AiFeatureResult; data class Unavailable(val reason: String) : AiFeatureResult }`.
- Produces: `class RknnFeatureExtractor(context: Context) : AiFrameFeatureExtractor` with `override fun extract(rgb224: ByteArray): AiFeatureResult`.

- [ ] **Step 1: Obtain and validate the vendor-compatible artifacts**

Obtain `librknnrt.so` and `rknn_api.h` from the RK3588 board BSP/RKNPU2 SDK matching the board NPU driver. Convert a static MobileNetV3-Small RGB 224×224 ONNX model with RKNN Toolkit2 for target `rk3588`, INT8 quantization and 1000 Float32 output features; place the output at `app/src/main/assets/camera_quality_feature.rknn`. Before packaging, run the vendor RKNN C demo on the same board and confirm it reports a 1000-element output.

- [ ] **Step 2: Write the Kotlin contract test first**

```kotlin
@Test fun unavailable_extractor_never_throws_and_preserves_reason() {
    val result = UnavailableFeatureExtractor("RKNN Runtime 不可用").extract(ByteArray(224 * 224 * 3))
    assertEquals("RKNN Runtime 不可用", (result as AiFeatureResult.Unavailable).reason)
}
```

- [ ] **Step 3: Run it to prove the contract does not exist**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.AiFrameFeatureExtractorTest`

Expected: compile failure because `AiFeatureResult` and `UnavailableFeatureExtractor` do not exist.

- [ ] **Step 4: Implement Kotlin fallback and JNI bridge**

```kotlin
interface AiFrameFeatureExtractor { fun extract(rgb224: ByteArray): AiFeatureResult }
class UnavailableFeatureExtractor(private val reason: String) : AiFrameFeatureExtractor {
    override fun extract(rgb224: ByteArray) = AiFeatureResult.Unavailable(reason)
}
```

In `rknn_feature_extractor.cpp`, load the asset bytes, call `rknn_init`, verify one input of shape `[1,224,224,3]`, set uint8 RGB input with `rknn_inputs_set`, call `rknn_run`, obtain exactly 1000 Float32 values with `rknn_outputs_get`, copy them to a Java float array and call `rknn_outputs_release`. Every nonzero RKNN return code becomes `AiFeatureResult.Unavailable` with the API name and code. Configure Gradle `externalNativeBuild` to package `libcamera_quality_ai.so` for arm64-v8a.

- [ ] **Step 5: Verify JVM fallback test and APK build**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`

Expected: fallback contract passes and the APK packages both native libraries and the `.rknn` asset.

### Task 4: 正常录像导入与基线文件管理

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaselineStore.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/NormalSceneBaselineTest.kt`

**Interfaces:**
- Consumes: `AiFrameFeatureExtractor`, `AiFrameFeature`, `buildSceneBaseline`, `SceneBaseline.encode`.
- Produces: `suspend fun createBaselineFromVideo(context: Context, uri: Uri, extractor: AiFrameFeatureExtractor): BaselineCreationResult`.
- Produces: `fun loadBaseline(context: Context): SceneBaseline?`.
- Produces: `fun replaceBaselineIfValid(current: String?, candidate: String): String?`.

- [ ] **Step 1: Write the failing persistence safety test**

```kotlin
@Test fun invalid_new_baseline_does_not_replace_previous_valid_baseline() {
    val validBaselineText = baselineWithMidEmbedding(floatArrayOf(1f, 0f)).encode()
    assertEquals(validBaselineText, replaceBaselineIfValid(validBaselineText, "{bad json"))
}
```

- [ ] **Step 2: Run it to prove failure**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest`

Expected: compile failure because `replaceBaselineIfValid` does not exist.

- [ ] **Step 3: Implement creation and atomic replacement**

Decode at most 120 frames from the selected `video/*` URI, skip frames currently considered overdark or overbright, resize valid frames to RGB 224×224, compute RGB means and NPU embedding, then build a baseline. Write the encoded candidate to `camera_ai_baseline.tmp`, decode it back, and rename it to `camera_ai_baseline.json` only after validation succeeds. Retain an existing JSON file on every error.

```kotlin
fun replaceBaselineIfValid(current: String?, candidate: String): String? =
    decodeSceneBaseline(candidate)?.let { candidate } ?: current
```

- [ ] **Step 4: Add UI state and verify tests**

Use `ActivityResultContracts.OpenDocument()` with `arrayOf("video/*")`. Render `尚未建立基线` / `正在建立基线` / `已建立基线（N 帧）` / `AI 不可用（原因）`, then run:

Run: `./gradlew.bat testDebugUnitTest`

Expected: PASS.

### Task 5: 录像检测融合、结果展示和设备验证

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/RecordedFrameQuality.kt`
- Modify: `README.MD`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/RecordedFrameQualityTest.kt`

**Interfaces:**
- Consumes: `analyseRecordedVideo`, `SceneBaseline`, `assessSceneAnomalies`, `colorCastReason` and `AiFrameFeatureExtractor`.
- Produces: the existing camera `TestResult` with `AI 状态`, `AI 异常分`, `AI 亮度组`, `AI 原因`, and the evidence filename.

- [ ] **Step 1: Write the failing reason-precedence test**

```kotlin
@Test fun exposure_reason_precedes_unexplained_ai_anomaly() {
    assertEquals("画面过曝", combineQualityReasons(ruleReasons = listOf("画面过曝"), aiReasons = listOf("画面异常，疑似镜头污渍、水雾或场景偏移")).first())
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest`

Expected: compile failure because `combineQualityReasons` does not exist.

- [ ] **Step 3: Implement post-recording fusion**

For each of the existing at-most-16 decoded frames, derive its RGB 224×224 buffer and rule sample. If a baseline and available extractor exist, obtain its feature and run anomaly assessment; otherwise keep the rule result and add an `AI 状态` detail stating the fallback cause. Combine reasons in this order: `可能失焦或画面模糊`, `画面过暗或镜头被遮挡`, `画面过曝`, `对比度过低`, `画面偏色`, `画面异常，疑似镜头污渍、水雾或场景偏移`.

- [ ] **Step 4: Verify all automated checks**

Run: `./gradlew.bat testDebugUnitTest assembleDebug`

Expected: all unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Perform RK3588 manual smoke test**

Install the APK, import at least one normal recording, build the baseline, then record a fresh normal 8-second clip. Verify NPU initialization and feature extraction in application status/logcat. Repeat with an intentionally obscured or manually fogged lens, color filter, defocused lens and extreme lighting. Record whether each case produces its corresponding rule or AI warning before accepting thresholds.
