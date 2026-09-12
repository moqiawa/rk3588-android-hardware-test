package com.rk.hardwaretest.camera

import android.util.Size

/** Neutral luminance measurements only; no inference about lens condition or scene validity. */
data class FrameLumaStats(val meanLuma: Float, val darkPercent: Float, val brightPercent: Float)

data class CameraPreviewMetrics(
    val actualExposure: Int?,
    val exposureSupported: Boolean,
    val fps: Float?,
    val outputSize: Size?,
    val luma: FrameLumaStats?,
)

/** Keeps a retained Camera2 callback pointed at the currently selected camera page state. */
class LatestMetricsCallback(initial: (CameraPreviewMetrics) -> Unit) {
    @Volatile private var current: (CameraPreviewMetrics) -> Unit = initial

    fun update(callback: (CameraPreviewMetrics) -> Unit) {
        current = callback
    }

    fun publish(metrics: CameraPreviewMetrics) {
        current(metrics)
    }
}

fun CameraPreviewMetrics.withLuma(luma: FrameLumaStats): CameraPreviewMetrics = copy(luma = luma)

fun CameraPreviewMetrics.summaryLines(): List<String> = listOf(
    if (exposureSupported) "曝光补偿：${actualExposure ?: "不可用"}" else "曝光补偿：不支持",
    "实时帧率：${fps?.let { "%.1f FPS".format(it) } ?: "不可用"}",
    "实际输出：${outputSize?.let { "${it.width}×${it.height}" } ?: "不可用"}",
    "平均亮度：${luma?.let { "${"%.0f".format(it.meanLuma)} / 255" } ?: "不可用"}",
    "暗部比例：${luma?.let { "${"%.1f".format(it.darkPercent)}%" } ?: "不可用"}",
    "亮部比例：${luma?.let { "${"%.1f".format(it.brightPercent)}%" } ?: "不可用"}",
)

fun calculateLumaStats(luma: ByteArray): FrameLumaStats {
    if (luma.isEmpty()) return FrameLumaStats(0f, 0f, 0f)
    val values = luma.map { it.toInt() and 0xff }
    val mean = values.average().toFloat()
    val dark = values.count { it < 20 } * 100f / values.size
    val bright = values.count { it > 235 } * 100f / values.size
    return FrameLumaStats(mean, dark, bright)
}
