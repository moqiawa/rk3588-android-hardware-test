package com.rk.hardwaretest.camera

import kotlin.math.ceil

data class RecordedFrameSample(
    val sharpness: Float,
    val darkPercent: Float,
    val brightPercent: Float,
    val contrast: Float,
)

data class AiBitmapInput(
    val rgb224: ByteArray,
    val luma: Float,
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
)

data class SpatialSignature(val luma: FloatArray, val texture: FloatArray)

enum class RecordedQualityStatus { PASS, WARNING, FAIL }

data class RecordedQualityAssessment(
    val status: RecordedQualityStatus,
    val reasons: List<String>,
    val worstSharpness: Float = 0f,
    val worstDarkPercent: Float = 0f,
    val worstBrightPercent: Float = 0f,
    val worstContrast: Float = 0f,
)

fun assessRecordedFrames(samples: List<RecordedFrameSample>, overexposureReason: String? = null): RecordedQualityAssessment {
    if (samples.isEmpty()) return RecordedQualityAssessment(RecordedQualityStatus.FAIL, listOf("未能解码视频画面"))
    val count = maxOf(1, ceil(samples.size / 10.0).toInt())
    val sharpness = samples.sortedBy { it.sharpness }.take(count).first().sharpness
    val dark = samples.sortedByDescending { it.darkPercent }.take(count).first().darkPercent
    val bright = samples.sortedByDescending { it.brightPercent }.take(count).first().brightPercent
    val contrast = samples.sortedBy { it.contrast }.take(count).first().contrast
    val reasons = buildList {
        if (sharpness < 40f) add("可能失焦或画面模糊")
        if (dark > 35f) add("画面过暗或镜头被遮挡")
        overexposureReason?.let(::add)
        if (contrast < 12f) add("对比度过低")
    }
    return RecordedQualityAssessment(
        status = if (reasons.isEmpty()) RecordedQualityStatus.PASS else RecordedQualityStatus.WARNING,
        reasons = reasons,
        worstSharpness = sharpness,
        worstDarkPercent = dark,
        worstBrightPercent = bright,
        worstContrast = contrast,
    )
}

fun videoSampleTimesUs(durationMs: Long, intervalMs: Long = 500): List<Long> {
    if (durationMs <= 0 || intervalMs <= 0) return emptyList()
    return generateSequence(0L) { previous -> (previous + intervalMs).takeIf { it < durationMs } }
        .map { it * 1_000L }
        .toList()
}

fun recordingAnalysisTimesUs(durationMs: Long): List<Long> {
    val intervalMs = maxOf(67L, durationMs / 120L)
    return videoSampleTimesUs(durationMs, intervalMs).take(120)
}

fun bitmapFrameSample(argb: IntArray, width: Int, height: Int): RecordedFrameSample {
    require(width > 0 && height > 0 && argb.size == width * height)
    val luma = FloatArray(argb.size)
    var total = 0f
    var dark = 0
    var bright = 0
    argb.forEachIndexed { index, pixel ->
        val value = ((77 * ((pixel shr 16) and 0xff) + 150 * ((pixel shr 8) and 0xff) + 29 * (pixel and 0xff)) shr 8).toFloat()
        luma[index] = value
        total += value
        if (value < 20f) dark++
        if (value > 235f) bright++
    }
    val mean = total / luma.size
    var variance = 0f
    luma.forEach { variance += (it - mean) * (it - mean) }
    val contrast = kotlin.math.sqrt(variance / luma.size).toFloat()
    return RecordedFrameSample(
        sharpness = laplacianVariance(luma, width, height),
        darkPercent = dark * 100f / luma.size,
        brightPercent = bright * 100f / luma.size,
        contrast = contrast,
    )
}

fun bitmapAiInput(argb: IntArray, width: Int, height: Int): AiBitmapInput {
    require(width > 0 && height > 0 && argb.size == width * height)
    var redTotal = 0L
    var greenTotal = 0L
    var blueTotal = 0L
    argb.forEach { pixel ->
        redTotal += (pixel shr 16) and 0xff
        greenTotal += (pixel shr 8) and 0xff
        blueTotal += pixel and 0xff
    }
    val pixelCount = argb.size.toFloat()
    val redMean = redTotal / pixelCount
    val greenMean = greenTotal / pixelCount
    val blueMean = blueTotal / pixelCount
    val rgb = ByteArray(224 * 224 * 3)
    for (y in 0 until 224) for (x in 0 until 224) {
        val sourceX = x * width / 224
        val sourceY = y * height / 224
        val pixel = argb[sourceY * width + sourceX]
        val index = (y * 224 + x) * 3
        rgb[index] = ((pixel shr 16) and 0xff).toByte()
        rgb[index + 1] = ((pixel shr 8) and 0xff).toByte()
        rgb[index + 2] = (pixel and 0xff).toByte()
    }
    return AiBitmapInput(
        rgb224 = rgb,
        luma = (77f * redMean + 150f * greenMean + 29f * blueMean) / 256f,
        redMean = redMean,
        greenMean = greenMean,
        blueMean = blueMean,
    )
}

fun spatialSignature(argb: IntArray, width: Int, height: Int): SpatialSignature {
    require(width > 0 && height > 0 && argb.size == width * height)
    val luma = FloatArray(64)
    val texture = FloatArray(64)
    for (cellY in 0 until 8) for (cellX in 0 until 8) {
        val startX = cellX * width / 8
        val endX = maxOf(startX + 1, (cellX + 1) * width / 8)
        val startY = cellY * height / 8
        val endY = maxOf(startY + 1, (cellY + 1) * height / 8)
        var total = 0f
        var horizontalDifference = 0f
        var count = 0
        for (y in startY until endY) for (x in startX until endX) {
            val pixel = argb[y * width + x]
            val value = ((77 * ((pixel shr 16) and 0xff) + 150 * ((pixel shr 8) and 0xff) + 29 * (pixel and 0xff)) shr 8).toFloat()
            total += value
            count++
            if (x > startX) {
                val left = argb[y * width + x - 1]
                val leftValue = ((77 * ((left shr 16) and 0xff) + 150 * ((left shr 8) and 0xff) + 29 * (left and 0xff)) shr 8).toFloat()
                horizontalDifference += kotlin.math.abs(value - leftValue)
            }
        }
        val index = cellY * 8 + cellX
        luma[index] = total / count
        texture[index] = horizontalDifference / count
    }
    return SpatialSignature(luma, texture)
}

fun combineQualityReasons(ruleReasons: List<String>, aiReasons: List<String>): List<String> =
    (ruleReasons + aiReasons).distinct()

fun isUsableBaselineFrame(sample: RecordedFrameSample): Boolean =
    sample.darkPercent <= 60f && sample.brightPercent <= 35f

private fun laplacianVariance(luma: FloatArray, width: Int, height: Int): Float {
    if (width < 3 || height < 3) return 0f
    var total = 0f
    var totalSquared = 0f
    var count = 0
    for (y in 1 until height - 1) for (x in 1 until width - 1) {
        val index = y * width + x
        val value = 4f * luma[index] - luma[index - 1] - luma[index + 1] - luma[index - width] - luma[index + width]
        total += value
        totalSquared += value * value
        count++
    }
    val mean = total / count
    return (totalSquared / count - mean * mean).coerceAtLeast(0f)
}
