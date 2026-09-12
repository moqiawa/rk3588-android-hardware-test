# Camera Scene Model and ROI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-camera fixed/variable scene selection and a persistent ROI so the existing baseline and diagnosis model can be applied only to a user-selected camera region.

**Architecture:** A pure camera-scene module owns normalized ROI validation, pixel cropping, baseline compatibility, and the fixed/variable execution decision. Android persistence stores each camera's scene profile and the ROI that created its baseline. `CameraPage` renders the approved landscape layout and routes fixed-scene frame data through the selected ROI; the Camera2 controller publishes actual preview metrics for the side panel.

**Tech Stack:** Kotlin, JUnit 4, Jetpack Compose, Camera2, Android SharedPreferences, MediaMetadataRetriever, RKNN JNI.

**Spec:** `docs/superpowers/specs/2026-09-12-camera-scene-model-roi-design.md`

## Global Constraints

- Target API remains 26 and camera/RKNN data remains local to the device.
- Scene profile, ROI, and baseline are isolated by camera ID.
- ROI coordinates are normalized to `0f..1f`; the default ROI is the full frame.
- Fixed-scene baseline creation and every fixed-scene detector consume only cropped ROI pixels.
- Variable-scene mode is a persistent placeholder and must not run baseline, RKNN, spatial, relative-overexposure, or local-hotspot models.
- Changing an ROI never silently reuses a baseline built for another ROI.
- The camera page preserves the six-tab top strip and uses a landscape 16:9 preview in its lower work area.

---

### Task 1: Pure scene profile, ROI validation, and frame cropping

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/camera/CameraSceneProfile.kt`
- Create: `app/src/test/java/com/rk/hardwaretest/camera/CameraSceneProfileTest.kt`

**Interfaces:**
- Produces: `enum class CameraSceneMode { FIXED, VARIABLE }`.
- Produces: `data class NormalizedRoi(val left: Float, val top: Float, val right: Float, val bottom: Float)`.
- Produces: `data class CameraSceneProfile(val mode: CameraSceneMode, val roi: NormalizedRoi = NormalizedRoi.fullFrame())`.
- Produces: `fun NormalizedRoi.isValid(): Boolean`, `fun NormalizedRoi.cropArgb(argb: IntArray, width: Int, height: Int): CroppedArgbFrame`, and `fun shouldRunFixedSceneModel(profile: CameraSceneProfile): Boolean`.

- [ ] **Step 1: Write failing tests for full-frame defaults, invalid rectangles, and exact normalized cropping.**

```kotlin
@Test fun middle_half_roi_crops_the_expected_argb_pixels() {
    val roi = NormalizedRoi(.25f, .25f, .75f, .75f)
    val cropped = roi.cropArgb(IntArray(16) { it }, 4, 4)

    assertEquals(2, cropped.width)
    assertEquals(2, cropped.height)
    assertArrayEquals(intArrayOf(5, 6, 9, 10), cropped.argb)
}

@Test fun zero_width_roi_is_invalid() {
    assertFalse(NormalizedRoi(.4f, .1f, .4f, .9f).isValid())
}
```

- [ ] **Step 2: Run the focused test to verify the types are missing.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraSceneProfileTest --console=plain`

Expected: FAIL with unresolved references for `NormalizedRoi` and `cropArgb`.

- [ ] **Step 3: Implement the smallest pure ROI model.**

```kotlin
data class CroppedArgbFrame(val argb: IntArray, val width: Int, val height: Int)

fun NormalizedRoi.cropArgb(argb: IntArray, width: Int, height: Int): CroppedArgbFrame {
    require(isValid() && argb.size == width * height)
    val leftPx = (left * width).toInt().coerceIn(0, width - 1)
    val topPx = (top * height).toInt().coerceIn(0, height - 1)
    val rightPx = kotlin.math.ceil(right * width).toInt().coerceIn(leftPx + 1, width)
    val bottomPx = kotlin.math.ceil(bottom * height).toInt().coerceIn(topPx + 1, height)
    val outputWidth = rightPx - leftPx
    val output = IntArray(outputWidth * (bottomPx - topPx))
    for (row in topPx until bottomPx) {
        argb.copyInto(output, (row - topPx) * outputWidth, row * width + leftPx, row * width + rightPx)
    }
    return CroppedArgbFrame(output, outputWidth, bottomPx - topPx)
}
```

- [ ] **Step 4: Re-run the focused test and confirm it passes.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraSceneProfileTest --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit the isolated model and tests.**

```bash
git add app/src/main/java/com/rk/hardwaretest/camera/CameraSceneProfile.kt app/src/test/java/com/rk/hardwaretest/camera/CameraSceneProfileTest.kt
git commit -m "feat: add camera scene profile and roi crop"
```

### Task 2: Bind fixed-scene baselines to their ROI and persist camera profiles

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaselineStore.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/CameraSceneProfile.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/CameraSceneProfileTest.kt`

**Interfaces:**
- Consumes: `CameraSceneProfile`, `NormalizedRoi`, `SceneBaseline`, and `cameraBaselineKey`.
- Produces: `data class StoredSceneBaseline(val baseline: SceneBaseline, val roi: NormalizedRoi?)`.
- Produces: `fun baselineMatchesRoi(stored: StoredSceneBaseline, roi: NormalizedRoi): Boolean`.
- Produces: `fun saveCameraSceneProfile(context: Context, cameraId: String, profile: CameraSceneProfile)` and `fun loadCameraSceneProfile(context: Context, cameraId: String): CameraSceneProfile`.

- [ ] **Step 1: Write failing compatibility tests before changing storage.**

```kotlin
@Test fun baseline_is_rejected_after_roi_changes() {
    val stored = StoredSceneBaseline(SceneBaseline(emptyMap()), NormalizedRoi.fullFrame())

    assertFalse(baselineMatchesRoi(stored, NormalizedRoi(.1f, .1f, .9f, .9f)))
}

@Test fun legacy_baseline_without_roi_requires_rebuild() {
    val stored = StoredSceneBaseline(SceneBaseline(emptyMap()), roi = null)

    assertFalse(baselineMatchesRoi(stored, NormalizedRoi.fullFrame()))
}
```

- [ ] **Step 2: Run the focused test and verify it fails because baseline metadata is absent.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraSceneProfileTest --console=plain`

Expected: FAIL with unresolved `StoredSceneBaseline` or `baselineMatchesRoi`.

- [ ] **Step 3: Add baseline ROI metadata without changing the legacy group parser.**

```kotlin
private fun baselineRoiKey(cameraId: String) = "${cameraBaselineKey(cameraId)}_roi"
private fun sceneProfileKey(cameraId: String) = "scene_profile_${cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

fun baselineMatchesRoi(stored: StoredSceneBaseline, roi: NormalizedRoi): Boolean = stored.roi == roi
```

Persist the four ROI floats as a comma-separated value under `baselineRoiKey`, retain existing baseline group serialization unchanged, and return `roi = null` for existing baselines. Store scene mode plus ROI separately under `sceneProfileKey`; return `CameraSceneProfile(FIXED, fullFrame())` when no profile exists.

- [ ] **Step 4: Re-run the focused test and existing baseline tests.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraSceneProfileTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit persistence and migration behavior.**

```bash
git add app/src/main/java/com/rk/hardwaretest/camera/CameraSceneProfile.kt app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaselineStore.kt app/src/test/java/com/rk/hardwaretest/camera/CameraSceneProfileTest.kt
git commit -m "feat: persist camera scene profiles and baseline roi"
```

### Task 3: Apply ROI to fixed-scene baseline creation and recording diagnosis

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/CameraSceneProfileTest.kt`

**Interfaces:**
- Consumes: `CameraSceneProfile.cropArgb`, `StoredSceneBaseline`, `baselineMatchesRoi`, `AiFrameFeatureExtractor`.
- Produces: `fun analyseRecordedVideo(file: File, profile: CameraSceneProfile, storedBaseline: StoredSceneBaseline?, extractor: AiFrameFeatureExtractor): RecordedVideoAnalysis`.
- Produces: `fun createBaselineFromVideo(context: Context, uri: Uri, profile: CameraSceneProfile, extractor: AiFrameFeatureExtractor): BaselineCreationResult`.

- [ ] **Step 1: Write failing pure tests that prove variable scenes skip the fixed-scene model and fixed scenes use ROI pixels.**

```kotlin
@Test fun variable_scene_does_not_run_the_fixed_scene_model() {
    assertFalse(shouldRunFixedSceneModel(CameraSceneProfile(CameraSceneMode.VARIABLE)))
}

@Test fun fixed_scene_model_receives_only_roi_pixels() {
    val profile = CameraSceneProfile(CameraSceneMode.FIXED, NormalizedRoi(.5f, 0f, 1f, 1f))

    assertArrayEquals(intArrayOf(1, 3, 5, 7), profile.roi.cropArgb(IntArray(8) { it }, 2, 4).argb)
}
```

- [ ] **Step 2: Run the focused tests and verify them against the current full-frame path.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraSceneProfileTest --console=plain`

Expected: PASS for existing pure gates only after Task 1; the production signatures remain unimplemented until the next step.

- [ ] **Step 3: Crop every decoded bitmap before feature extraction in fixed mode.**

In both functions, read full bitmap pixels once, then apply `profile.roi.cropArgb`. Pass `cropped.argb`, `cropped.width`, and `cropped.height` to `bitmapFrameSample`, `bitmapAiInput`, and `spatialSignature`. Save `StoredSceneBaseline(created.baseline, profile.roi)` only after a successful fixed-scene baseline build.

For `VARIABLE`, return the existing rule-only assessment with `aiStatus = "变化场景模型尚未配置"`; do not call `extractor.extract`, `assessSceneAnomalies`, `assessSpatialOcclusion`, `assessSpatialBrightHotspot`, or `relativeOverexposureReason`.

- [ ] **Step 4: Gate baseline use by both scene mode and ROI compatibility.**

```kotlin
val fixedBaseline = storedBaseline?.takeIf {
    profile.mode == CameraSceneMode.FIXED && baselineMatchesRoi(it, profile.roi)
}
```

When a fixed baseline exists but does not match, keep rule-only analysis and set `aiStatus = "ROI 已更新，需重建基线"`.

- [ ] **Step 5: Run all camera logic tests.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.CameraSceneProfileTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit fixed-scene ROI data flow.**

```bash
git add app/src/main/java/com/rk/hardwaretest/MainActivity.kt app/src/test/java/com/rk/hardwaretest/camera/CameraSceneProfileTest.kt
git commit -m "feat: apply roi to fixed scene diagnosis"
```

### Task 4: Publish actual preview metrics for the side panel

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/Camera2PreviewController.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/FrameQuality.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/FrameQualityTest.kt`

**Interfaces:**
- Produces: `data class CameraPreviewMetrics(val actualExposure: Int?, val exposureSupported: Boolean, val fps: Float?, val outputSize: Size?, val luma: FrameLumaStats?)`.
- Produces: `Camera2PreviewController(..., onMetrics: (CameraPreviewMetrics) -> Unit)`.

- [ ] **Step 1: Write failing formatting tests for unavailable metrics and supported exposure ranges.**

```kotlin
@Test fun unavailable_metrics_are_not_rendered_as_zero_values() {
    val text = CameraPreviewMetrics(null, false, null, null, null).summaryLines()

    assertTrue(text.any { it == "曝光补偿：不支持" })
    assertTrue(text.any { it == "实时帧率：不可用" })
}
```

- [ ] **Step 2: Run the focused test and verify it fails because `CameraPreviewMetrics` does not exist.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.FrameLumaStatsTest --console=plain`

Expected: FAIL with unresolved `CameraPreviewMetrics`.

- [ ] **Step 3: Implement immutable metrics updates from Camera2 callbacks.**

Create `CameraPreviewMetrics` and its pure `summaryLines()` in `FrameQuality.kt`. In `Camera2PreviewController`, derive exposure support from `CONTROL_AE_COMPENSATION_RANGE`, preserve the active `Size`, update calculated FPS from capture timestamps, and merge sampled `FrameLumaStats` before invoking `onMetrics`. Do not encode metrics into the existing `onState` string.

- [ ] **Step 4: Re-run focused metric tests.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.FrameLumaStatsTest --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit metrics publication.**

```bash
git add app/src/main/java/com/rk/hardwaretest/camera/Camera2PreviewController.kt app/src/main/java/com/rk/hardwaretest/camera/FrameQuality.kt app/src/test/java/com/rk/hardwaretest/camera/FrameQualityTest.kt
git commit -m "feat: publish camera preview metrics"
```

### Task 5: Implement the approved camera-page controls and ROI editor

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/BaselineRecording.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/BaselineRecordingTest.kt`

**Interfaces:**
- Consumes: `CameraSceneProfile`, `CameraPreviewMetrics`, `saveCameraSceneProfile`, `loadCameraSceneProfile`, `StoredSceneBaseline`.
- Produces: `@Composable private fun RoiEditor(...)` and `fun sceneActionState(profile: CameraSceneProfile, baselineMatches: Boolean): SceneActionState`.

- [ ] **Step 1: Write failing action-state tests for fixed and variable scenes.**

```kotlin
@Test fun variable_scene_disables_baseline_and_model_actions() {
    val state = sceneActionState(CameraSceneProfile(CameraSceneMode.VARIABLE), baselineMatches = false)

    assertFalse(state.canCreateBaseline)
    assertFalse(state.canRunModelDetection)
    assertEquals("变化场景模型尚未配置", state.message)
}
```

- [ ] **Step 2: Run the focused test and confirm the action state is unresolved.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.BaselineRecordingTest --console=plain`

Expected: FAIL with unresolved `sceneActionState`.

- [ ] **Step 3: Implement scene action state and the two-row operation area.**

Add a pure `SceneActionState` in `BaselineRecording.kt`. In `CameraPage`, load profile per `currentId`; render the approved structure: camera switcher, landscape preview, first row for import/record-baseline/record-detect, second row for fixed/variable/ROI, and a right-side parameter and metrics panel. Disable fixed-scene baseline/model actions in variable mode while preserving preview controls.

- [ ] **Step 4: Implement ROI editing with draft state.**

Use a `Box` overlay around the existing `TextureView`. Store drag changes in a draft `NormalizedRoi`, clamp all edges to `0f..1f`, reject invalid rectangles using `isValid()`, and expose Confirm, Cancel, and Reset Full Frame controls. On Confirm, persist the profile, reload baseline compatibility, and show `ROI 已更新，需重建基线` when applicable.

- [ ] **Step 5: Re-run the focused action-state test.**

Run: `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.BaselineRecordingTest --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit the UI and state workflow.**

```bash
git add app/src/main/java/com/rk/hardwaretest/MainActivity.kt app/src/main/java/com/rk/hardwaretest/camera/BaselineRecording.kt app/src/test/java/com/rk/hardwaretest/camera/BaselineRecordingTest.kt
git commit -m "feat: add camera scene mode and roi controls"
```

### Task 6: Document behavior and verify the integrated build

**Files:**
- Modify: `README.MD`
- Modify: `docs/superpowers/handoffs/2026-09-12-camera-baseline-calibration-progress.md`

**Interfaces:**
- Consumes: final scene profile, ROI, baseline compatibility, and camera-page behavior.
- Produces: operator instructions for fixed/variable selection, ROI editing, baseline rebuild, and test evidence collection.

- [ ] **Step 1: Update the operator flow with concrete actions.**

Document: choose the camera, select Fixed Scene, set ROI or keep full frame, create a baseline, record a diagnosis, and rebuild the baseline after ROI changes. Document Variable Scene as “model not configured” and specify that it does not run the fixed-scene model.

- [ ] **Step 2: Update the handoff with the new verification matrix.**

Include full-frame default ROI, cropped ROI, camera switching, resolution switching, fixed-scene baseline match/mismatch, variable-scene placeholder, local flashlight, and half-frame obstruction.

- [ ] **Step 3: Run the complete automated verification.**

Run: `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 4: Perform target-device validation when the device is reachable.**

Install the generated APK, verify the matrix in Step 2, and record the displayed actual exposure support, FPS, output resolution, luminance metrics, ROI, AI status, and final summary. If no device/ADB is reachable, record that exact limitation in the handoff.

- [ ] **Step 5: Commit documentation.**

```bash
git add README.MD docs/superpowers/handoffs/2026-09-12-camera-baseline-calibration-progress.md
git commit -m "docs: describe camera scene mode and roi workflow"
```
