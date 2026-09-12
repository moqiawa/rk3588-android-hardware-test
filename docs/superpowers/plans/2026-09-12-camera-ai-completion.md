# Camera AI Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish on-device normal-video baseline creation and AI-assisted post-recording diagnosis for the RK3588 camera test.

**Architecture:** A small pure Kotlin fusion module converts decoded frames into reusable RGB/feature samples and combines rule and AI reasons deterministically. `CameraPage` owns document selection and asynchronous baseline creation; it persists a candidate only after valid feature extraction and keeps recording analysis fully usable when the NPU or baseline is unavailable.

**Tech Stack:** Kotlin, JUnit 4, Jetpack Compose, MediaMetadataRetriever, coroutines, RKNN JNI.

**Spec:** `docs/superpowers/specs/2026-09-11-camera-normal-baseline-ai-design.md`

## Global Constraints

- Target API 26 and `arm64-v8a` RK3588 only; all video and feature data remains local.
- Import accepts only `video/*`, samples no more than 120 frames, and excludes over-dark/over-exposed frames.
- A missing baseline or unavailable RKNN extractor must preserve the existing rule-only recording result.
- AI-only failures say `画面异常，疑似镜头污渍、水雾或场景偏移`; no definite physical-defect claim is allowed.
- Rule reasons precede AI-only reasons.

---

### Task 1: Testable AI result fusion

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/RecordedFrameQuality.kt`
- Modify: `app/src/test/java/com/rk/hardwaretest/camera/RecordedFrameQualityTest.kt`

**Interfaces:**
- Produces: `fun combineQualityReasons(ruleReasons: List<String>, aiReasons: List<String>): List<String>`.
- Produces: `fun bitmapAiInput(argb: IntArray, width: Int, height: Int): AiBitmapInput`.

- [ ] **Step 1: Write failing tests** for rule-reason precedence and RGB/luma conversion from a 1×1 pixel.
- [ ] **Step 2: Run** `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.RecordedFrameQualityTest` and confirm the new symbols are unresolved.
- [ ] **Step 3: Implement minimal helpers**: retain rule ordering, append unique AI reasons, resize ARGB input to RGB 224×224, and calculate RGB means/luma.
- [ ] **Step 4: Re-run the focused test** and confirm it passes.

### Task 2: Baseline import workflow

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaselineStore.kt`
- Test: `app/src/test/java/com/rk/hardwaretest/camera/NormalSceneBaselineTest.kt`

**Interfaces:**
- Consumes: `bitmapAiInput`, `AiFrameFeatureExtractor.extract`, `buildSceneBaseline`, `saveBaseline`.
- Produces: `BaselineCreationResult` and `createBaselineFromVideo(context, uri, extractor)`.

- [ ] **Step 1: Write failing tests** for rejecting an empty candidate and retaining a previously valid baseline text.
- [ ] **Step 2: Run** `./gradlew.bat testDebugUnitTest --tests com.rk.hardwaretest.camera.NormalSceneBaselineTest` and confirm the new contract fails.
- [ ] **Step 3: Implement** video frame extraction (120-frame cap), quality filtering, feature extraction, candidate validation, and safe persistence.
- [ ] **Step 4: Add CameraPage state** using `OpenDocument(video/*)`, showing no baseline, building, established count, and unavailable/failure status.
- [ ] **Step 5: Re-run focused tests** and confirm they pass.

### Task 3: Recording fusion and APK validation

**Files:**
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`
- Modify: `README.MD`

**Interfaces:**
- Consumes: existing `analyseRecordedVideo`, `loadBaseline`, `RknnFeatureExtractor`, `assessSceneAnomalies`, `colorCastReason`.
- Produces: camera `TestResult.details` keys `AI 状态`, `AI 异常分`, `AI 亮度组`, and `AI 原因`.

- [ ] **Step 1: Wire post-recording analysis** so its decoded frames produce rule samples and, where possible, AI features; preserve a precise AI skip reason otherwise.
- [ ] **Step 2: Apply deterministic reason order**: rule reasons, color-cast, then AI-only suspected scene anomaly.
- [ ] **Step 3: Update README** with import, fallback, and device verification steps.
- [ ] **Step 4: Run** `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`; confirm all unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 5: If the configured device is reachable, install the APK** and perform the documented normal/obscured/lighting smoke checks; otherwise report this as the remaining physical-device action.
