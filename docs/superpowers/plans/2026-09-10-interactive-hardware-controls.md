# 交互式硬件控制 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让硬件测试只由显式操作启动，并让相机和麦克风控制按设备真实能力生效。

**Architecture:** 将可单测的能力选择、结果可见性与音量计算提取为纯 Kotlin 模型。`MainActivity` 保持 Compose 宿主，摄像头由生命周期感知的 Camera2 控制器持有单一重复请求，麦克风监测由可取消会话持有。

**Tech Stack:** Kotlin、Jetpack Compose、Camera2、Android AudioRecord、JUnit 4、Coroutines。

**Spec:** `docs/superpowers/specs/2026-09-10-interactive-hardware-controls-design.md`

## Global Constraints

- 仅用户点击“重新测试”或“一键测试”可更新 `TestResult`。
- 一键页是唯一可同时显示全部硬件测试结果的页面。
- 只展示相机和音频系统声明支持的控制范围；失败状态不得伪装成功。
- 最终仅生成 `arm64-v8a` 调试 APK。

---

### Task 1: 测试状态与相机能力策略

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/control/HardwareControlModels.kt`
- Create: `app/src/test/java/com/rk/hardwaretest/control/HardwareControlModelsTest.kt`

**Interfaces:**
- Produces: `CameraControlCapabilities`, `ExposureSelection`, `zoomChoices`, `visibleResultsForPage`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun individual_page_keeps_only_its_own_result() {
    assertEquals(setOf("camera"), visibleResultsForPage("摄像头测试", allResults).keys)
}
@Test fun exposure_slider_covers_camera_range() {
    assertEquals(-6..9, CameraControlCapabilities(-6..9, 0.5f, 1f..4f).exposureIndices)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.control.HardwareControlModelsTest`

- [ ] **Step 3: Implement minimal pure models**

Implement result filtering, range clamping, labels, and dynamic option generation without Android dependencies.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.control.HardwareControlModelsTest`

### Task 2: 可持续的麦克风实时监测

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/audio/LiveLevel.kt`
- Create: `app/src/test/java/com/rk/hardwaretest/audio/LiveLevelTest.kt`

**Interfaces:**
- Produces: `fun pcmLevel(samples: ShortArray): LiveLevel`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun pcm_level_reports_peak_and_rms() {
    val level = pcmLevel(shortArrayOf(0, 1000, -1000))
    assertTrue(level.peakDbfs < 0f)
    assertTrue(level.rmsDbfs < level.peakDbfs)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.audio.LiveLevelTest`

- [ ] **Step 3: Implement the pure calculation and the cancellable AudioRecord effect**

Use `awaitDispose`/coroutine cancellation to release `AudioRecord`; do not call manual test functions from the effect.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.rk.hardwaretest.audio.LiveLevelTest`

### Task 3: Camera2 preview controller and page UI

**Files:**
- Create: `app/src/main/java/com/rk/hardwaretest/camera/CameraPreviewController.kt`
- Modify: `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`

- [ ] **Step 1: Bind a Camera2 preview session for a selected camera and TextureView surface**
- [ ] **Step 2: Apply exposure, zoom, autofocus, resolution and AE FPS to its repeated request**
- [ ] **Step 3: Replace page-level result leakage and CameraX rebinding with isolated controls**
- [ ] **Step 4: Build the app to validate Android APIs**

Run: `./gradlew.bat :app:assembleDebug`

### Task 4: Full verification and final APK

**Files:**
- Modify: `README.md` if absent, otherwise no documentation change.

- [ ] **Step 1: Run all JVM tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

- [ ] **Step 2: Build final APK**

Run: `./gradlew.bat :app:assembleDebug`

- [ ] **Step 3: Verify ABI and artifact**

Run: `jar tf app/build/outputs/apk/debug/app-debug.apk`
