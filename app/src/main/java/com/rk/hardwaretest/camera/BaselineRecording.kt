package com.rk.hardwaretest.camera

enum class BaselineCapturePhase { IDLE, PREPARING, RECORDING, ANALYSING }

data class SceneActionState(
    val canCreateBaseline: Boolean,
    val canRunModelDetection: Boolean,
    val message: String,
)

fun sceneActionState(profile: CameraSceneProfile, baselineMatches: Boolean): SceneActionState = when {
    profile.mode == CameraSceneMode.VARIABLE -> SceneActionState(false, false, "变化场景模型尚未配置")
    !baselineMatches -> SceneActionState(true, false, "ROI 已更新，需重建基线")
    else -> SceneActionState(true, true, "固定场景模型就绪")
}

fun canStartCameraDetection(
    busy: Boolean,
    controllerReady: Boolean,
    actions: SceneActionState,
): Boolean = !busy && controllerReady && actions.canRunModelDetection

fun baselineCaptureLabel(phase: BaselineCapturePhase, remainingSeconds: Int): String = when (phase) {
    BaselineCapturePhase.IDLE -> "录制正常基线"
    BaselineCapturePhase.PREPARING -> "正在准备正常基线…"
    BaselineCapturePhase.RECORDING -> "录制正常基线：$remainingSeconds 秒"
    BaselineCapturePhase.ANALYSING -> "正在分析正常基线…"
}
