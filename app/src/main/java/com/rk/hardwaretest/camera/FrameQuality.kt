package com.rk.hardwaretest.camera

/** Neutral luminance measurements only; no inference about lens condition or scene validity. */
data class FrameLumaStats(val meanLuma: Float, val darkPercent: Float, val brightPercent: Float)

fun calculateLumaStats(luma: ByteArray): FrameLumaStats {
    if (luma.isEmpty()) return FrameLumaStats(0f, 0f, 0f)
    val values = luma.map { it.toInt() and 0xff }
    val mean = values.average().toFloat()
    val dark = values.count { it < 20 } * 100f / values.size
    val bright = values.count { it > 235 } * 100f / values.size
    return FrameLumaStats(mean, dark, bright)
}
