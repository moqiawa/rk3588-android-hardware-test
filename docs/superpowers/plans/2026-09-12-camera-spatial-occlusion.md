# Camera Spatial Occlusion Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect persistent large-area camera occlusion and severe spatial scene changes for every camera-specific normal baseline.

**Architecture:** Pure Kotlin derives an 8×8 luminance/texture signature from the existing decoded RGB frame and stores per-cell baseline means and deviations. Post-recording analysis compares matching signatures and reports a dedicated occlusion reason only after three consecutive frames exceed the 30% changed-cell threshold.

**Tech Stack:** Kotlin, JUnit 4, Jetpack Compose, MediaMetadataRetriever, RKNN JNI.

**Spec:** `docs/superpowers/specs/2026-09-12-camera-spatial-occlusion-design.md`

## Global Constraints

- Each Android camera ID retains an independent local baseline.
- Baseline creation samples at most 240 frames; post-recording detection samples at most 120 frames.
- A large spatial change must be persistent for three frames before reporting.
- Existing rule checks, RKNN checks, and missing-baseline fallback continue to work.
- Old baselines without spatial signatures must not produce a false occlusion result.

---

### Task 1: Spatial-signature pure model

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/RecordedFrameQuality.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaseline.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/NormalSceneBaselineTest.kt`

**Interfaces:**
- Produces: `data class SpatialSignature(val luma: FloatArray, val texture: FloatArray)` with 64 values per array.
- Produces: `fun spatialSignature(argb: IntArray, width: Int, height: Int): SpatialSignature`.
- Produces: `fun assessSpatialOcclusion(baseline: FeatureBaseline, frames: List<SpatialSignature>): SpatialOcclusionAssessment`.

- [ ] **Step 1: Write failing tests** for an 8×8 uniform frame signature and three consecutive 50%-changed frames.
- [ ] **Step 2: Run** `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest` and confirm the requested spatial symbols are unresolved.
- [ ] **Step 3: Implement** fixed-grid luma/texture extraction plus per-cell baseline center/deviation and 30% changed-cell detection.
- [ ] **Step 4: Re-run the focused test** and confirm one changed frame does not report occlusion while three do.

### Task 2: Persistence and baseline construction

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaselineStore.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/NormalSceneBaselineTest.kt`

**Interfaces:**
- Consumes: `AiFrameFeature.spatialSignature`, `buildSceneBaseline`, and `saveBaseline(context, cameraId, baseline)`.
- Produces: stored spatial fields only for newly built baselines; old fields decode with `null` spatial data.

- [ ] **Step 1: Write a failing round-trip test** showing a 64-cell spatial signature survives baseline persistence encoding.
- [ ] **Step 2: Run the focused test** and confirm it fails before persistence fields exist.
- [ ] **Step 3: Extend baseline construction** so decoded import and current-camera baseline recording attach a signature to each accepted frame; encode/decode optional spatial fields safely.
- [ ] **Step 4: Re-run the focused test** and confirm it passes.

### Task 3: Result fusion and delivery

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `README.MD`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/RecordedFrameQualityTest.kt`

**Interfaces:**
- Consumes: decoded recording frames, `assessSpatialOcclusion`, existing rule assessment, and camera-specific baseline.
- Produces: `AI 原因` containing `画面区域被遮挡或场景严重变化` only after persistent spatial detection.

- [ ] **Step 1: Write a failing reason-precedence test** showing an occlusion reason appears before generic AI anomaly and after deterministic exposure rules.
- [ ] **Step 2: Run the focused test** and confirm failure.
- [ ] **Step 3: Fuse spatial analysis** into post-recording results and add explicit status requiring re-baselining for legacy baselines.
- [ ] **Step 4: Update README** with spatial-baseline rebuild and half-occlusion validation instructions.
- [ ] **Step 5: Run** `./gradlew.bat testDebugUnitTest assembleDebug --console=plain` and confirm the debug APK exists.
