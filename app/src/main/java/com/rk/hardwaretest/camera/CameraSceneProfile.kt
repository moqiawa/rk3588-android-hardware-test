package com.rk.hardwaretest.camera

import android.content.Context
import kotlin.math.ceil

enum class CameraSceneMode { FIXED, VARIABLE }

data class NormalizedRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        fun fullFrame() = NormalizedRoi(0f, 0f, 1f, 1f)
    }
}

data class CroppedArgbFrame(val argb: IntArray, val width: Int, val height: Int)

data class CameraSceneProfile(
    val mode: CameraSceneMode,
    val roi: NormalizedRoi = NormalizedRoi.fullFrame(),
)

data class StoredSceneBaseline(val baseline: SceneBaseline, val roi: NormalizedRoi?)

fun NormalizedRoi.isValid(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom

fun normalizedRoiFromDrag(startX: Float, startY: Float, endX: Float, endY: Float): NormalizedRoi {
    val clampedStartX = startX.coerceIn(0f, 1f)
    val clampedStartY = startY.coerceIn(0f, 1f)
    val clampedEndX = endX.coerceIn(0f, 1f)
    val clampedEndY = endY.coerceIn(0f, 1f)
    return NormalizedRoi(
        left = minOf(clampedStartX, clampedEndX),
        top = minOf(clampedStartY, clampedEndY),
        right = maxOf(clampedStartX, clampedEndX),
        bottom = maxOf(clampedStartY, clampedEndY),
    )
}

fun NormalizedRoi.cropArgb(argb: IntArray, width: Int, height: Int): CroppedArgbFrame {
    require(width > 0 && height > 0 && argb.size == width * height && isValid())
    val leftPx = (left * width).toInt().coerceIn(0, width - 1)
    val topPx = (top * height).toInt().coerceIn(0, height - 1)
    val rightPx = ceil(right * width).toInt().coerceIn(leftPx + 1, width)
    val bottomPx = ceil(bottom * height).toInt().coerceIn(topPx + 1, height)
    val outputWidth = rightPx - leftPx
    val output = IntArray(outputWidth * (bottomPx - topPx))
    for (row in topPx until bottomPx) {
        argb.copyInto(output, (row - topPx) * outputWidth, row * width + leftPx, row * width + rightPx)
    }
    return CroppedArgbFrame(output, outputWidth, bottomPx - topPx)
}

fun shouldRunFixedSceneModel(profile: CameraSceneProfile): Boolean =
    profile.mode == CameraSceneMode.FIXED && profile.roi.isValid()

fun baselineMatchesRoi(stored: StoredSceneBaseline, roi: NormalizedRoi): Boolean = stored.roi == roi

private fun sceneProfileKey(cameraId: String) = "scene_profile_${cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")}"

fun saveCameraSceneProfile(context: Context, cameraId: String, profile: CameraSceneProfile) {
    require(profile.roi.isValid())
    val encoded = listOf(profile.mode.name, profile.roi.left, profile.roi.top, profile.roi.right, profile.roi.bottom).joinToString(",")
    context.getSharedPreferences("camera_ai_baseline", Context.MODE_PRIVATE).edit().putString(sceneProfileKey(cameraId), encoded).apply()
}

fun loadCameraSceneProfile(context: Context, cameraId: String): CameraSceneProfile {
    val encoded = context.getSharedPreferences("camera_ai_baseline", Context.MODE_PRIVATE).getString(sceneProfileKey(cameraId), null)
        ?: return CameraSceneProfile(CameraSceneMode.FIXED)
    val fields = encoded.split(",")
    val mode = fields.firstOrNull()?.let { runCatching { CameraSceneMode.valueOf(it) }.getOrNull() } ?: return CameraSceneProfile(CameraSceneMode.FIXED)
    val roi = fields.drop(1).mapNotNull(String::toFloatOrNull).takeIf { it.size == 4 }
        ?.let { NormalizedRoi(it[0], it[1], it[2], it[3]) }
        ?.takeIf(NormalizedRoi::isValid)
        ?: NormalizedRoi.fullFrame()
    return CameraSceneProfile(mode, roi)
}
