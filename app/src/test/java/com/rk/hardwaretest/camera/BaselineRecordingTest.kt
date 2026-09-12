package com.rk.hardwaretest.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BaselineRecordingTest {
    // This fails if a variable-scene selection leaves baseline or model actions operable.
    @Test fun variable_scene_disables_baseline_and_model_actions() {
        val state = sceneActionState(CameraSceneProfile(CameraSceneMode.VARIABLE), baselineMatches = false)

        assertFalse(state.canCreateBaseline)
        assertFalse(state.canRunModelDetection)
        assertEquals("变化场景模型尚未配置", state.message)
    }

    // This fails if the camera page accidentally re-enables recording detection for a variable scene.
    @Test fun recording_detection_requires_an_enabled_fixed_scene_model() {
        val variable = sceneActionState(CameraSceneProfile(CameraSceneMode.VARIABLE), baselineMatches = false)
        val fixedReady = sceneActionState(CameraSceneProfile(CameraSceneMode.FIXED), baselineMatches = true)

        assertFalse(canStartCameraDetection(busy = false, controllerReady = true, actions = variable))
        assertFalse(canStartCameraDetection(busy = true, controllerReady = true, actions = fixedReady))
        assertFalse(canStartCameraDetection(busy = false, controllerReady = false, actions = fixedReady))
        org.junit.Assert.assertTrue(canStartCameraDetection(busy = false, controllerReady = true, actions = fixedReady))
    }

    // This fails if the baseline-capture UI cannot distinguish a ready state from recording or analysis.
    @Test fun baseline_capture_phase_has_a_clear_user_visible_label() {
        assertEquals("录制正常基线：8 秒", baselineCaptureLabel(BaselineCapturePhase.RECORDING, 8))
        assertEquals("正在分析正常基线…", baselineCaptureLabel(BaselineCapturePhase.ANALYSING, 0))
        assertEquals("录制正常基线", baselineCaptureLabel(BaselineCapturePhase.IDLE, 0))
    }
}
