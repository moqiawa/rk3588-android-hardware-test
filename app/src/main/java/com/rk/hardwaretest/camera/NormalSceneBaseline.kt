package com.rk.hardwaretest.camera

import kotlin.math.sqrt

data class AiFrameFeature(
    val luma: Float,
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
    val embedding: FloatArray,
    val spatial: SpatialSignature? = null,
    val brightPercent: Float? = null,
)

enum class LightBand { LOW, MID, HIGH }

data class FeatureBaseline(
    val center: FloatArray,
    val deviation: FloatArray,
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
    val sampleCount: Int,
    val redDeviation: Float = 1f,
    val greenDeviation: Float = 1f,
    val blueDeviation: Float = 1f,
    val spatial: SpatialBaseline? = null,
    val brightPercentMean: Float? = null,
    val brightPercentDeviation: Float? = null,
)

data class SpatialBaseline(
    val lumaCenter: FloatArray,
    val lumaDeviation: FloatArray,
    val textureCenter: FloatArray,
    val textureDeviation: FloatArray,
)

data class SpatialOcclusionAssessment(val hasOcclusion: Boolean, val longestRun: Int, val highestChangedPercent: Float)

data class SpatialBrightHotspotAssessment(val hasBrightHotspot: Boolean, val longestRun: Int, val highestBrightenedPercent: Float)

data class SceneBaseline(val groups: Map<LightBand, FeatureBaseline>)

data class SceneAnomalyAssessment(
    val hasAnomaly: Boolean,
    val longestRun: Int,
    val highestScore: Float,
)

data class RelativeOverexposureDiagnostic(
    val band: LightBand,
    val baselineBand: LightBand,
    val baselineBrightPercent: Float,
    val threshold: Float,
    val currentBrightPercent: Float,
)

fun lightBand(luma: Float): LightBand = when {
    luma < 110f -> LightBand.LOW
    luma < 190f -> LightBand.MID
    else -> LightBand.HIGH
}

fun buildSceneBaseline(frames: List<AiFrameFeature>): SceneBaseline? {
    val groups = frames.groupBy { lightBand(it.luma) }.mapNotNull { (band, samples) ->
        samples.takeIf { it.size >= 2 }?.let { band to featureBaseline(it) }
    }.toMap()
    return groups.takeIf { it.isNotEmpty() }?.let(::SceneBaseline)
}

private fun featureBaseline(samples: List<AiFrameFeature>): FeatureBaseline {
    val dimension = samples.first().embedding.size
    require(dimension > 0 && samples.all { it.embedding.size == dimension })
    val normalized = samples.map { normalize(it.embedding) }
    val center = FloatArray(dimension) { index -> normalized.map { it[index] }.average().toFloat() }
    val deviation = FloatArray(dimension) { index ->
        sqrt(normalized.map { (it[index] - center[index]) * (it[index] - center[index]) }.average()).toFloat().coerceAtLeast(0.001f)
    }
    val brightPercentStatistics = samples.completeBrightPercentStatistics()
    return FeatureBaseline(
        center = normalize(center), deviation = deviation,
        redMean = samples.map { it.redMean }.average().toFloat(),
        greenMean = samples.map { it.greenMean }.average().toFloat(),
        blueMean = samples.map { it.blueMean }.average().toFloat(),
        sampleCount = samples.size,
        redDeviation = samples.standardDeviation { it.redMean },
        greenDeviation = samples.standardDeviation { it.greenMean },
        blueDeviation = samples.standardDeviation { it.blueMean },
        spatial = samples.mapNotNull { it.spatial }.takeIf { it.size >= 2 }?.let(::spatialBaseline),
        brightPercentMean = brightPercentStatistics?.first,
        brightPercentDeviation = brightPercentStatistics?.second,
    )
}

private fun spatialBaseline(samples: List<SpatialSignature>): SpatialBaseline {
    require(samples.all { it.luma.size == 64 && it.texture.size == 64 })
    fun center(values: (SpatialSignature) -> FloatArray) = FloatArray(64) { cell -> samples.map { values(it)[cell] }.average().toFloat() }
    fun deviation(values: (SpatialSignature) -> FloatArray, centers: FloatArray) = FloatArray(64) { cell ->
        sqrt(samples.map { (values(it)[cell] - centers[cell]) * (values(it)[cell] - centers[cell]) }.average()).toFloat().coerceAtLeast(1f)
    }
    val lumaCenter = center { it.luma }
    val textureCenter = center { it.texture }
    return SpatialBaseline(lumaCenter, deviation({ it.luma }, lumaCenter), textureCenter, deviation({ it.texture }, textureCenter))
}

fun normalize(values: FloatArray): FloatArray {
    val magnitude = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
    return if (magnitude > 0f) FloatArray(values.size) { values[it] / magnitude } else values.copyOf()
}

fun assessSceneAnomalies(baseline: SceneBaseline, frames: List<AiFrameFeature>): SceneAnomalyAssessment {
    var run = 0
    var longestRun = 0
    var highestScore = 0f
    frames.forEach { frame ->
        val group = baseline.groups[lightBand(frame.luma)] ?: baseline.groups.values.minByOrNull { kotlin.math.abs(it.redMean - frame.redMean) }
        val score = group?.let { 1f - cosineSimilarity(normalize(frame.embedding), it.center) } ?: 0f
        highestScore = maxOf(highestScore, score)
        if (score > 0.35f) { run++; longestRun = maxOf(longestRun, run) } else run = 0
    }
    return SceneAnomalyAssessment(longestRun >= 3, longestRun, highestScore)
}

fun colorCastReason(baseline: FeatureBaseline, frame: AiFrameFeature): String? {
    val baselineTotal = (baseline.redMean + baseline.greenMean + baseline.blueMean).coerceAtLeast(1f)
    val frameTotal = (frame.redMean + frame.greenMean + frame.blueMean).coerceAtLeast(1f)
    val redShifted = kotlin.math.abs(frame.redMean / frameTotal - baseline.redMean / baselineTotal) > 0.05f
    val greenShifted = kotlin.math.abs(frame.greenMean / frameTotal - baseline.greenMean / baselineTotal) > 0.05f
    val blueShifted = kotlin.math.abs(frame.blueMean / frameTotal - baseline.blueMean / baselineTotal) > 0.05f
    return if (redShifted || greenShifted || blueShifted) "画面偏色" else null
}

fun hasRelativeOverexposureStatistics(baseline: SceneBaseline): Boolean =
    baseline.groups.isNotEmpty() && baseline.groups.values.all {
        it.brightPercentMean != null && it.brightPercentDeviation != null
    }

fun relativeOverexposureDiagnostic(baseline: SceneBaseline, frames: List<AiFrameFeature>): RelativeOverexposureDiagnostic? {
    if (!hasRelativeOverexposureStatistics(baseline)) return null
    return frames.mapNotNull { frame ->
        val band = lightBand(frame.luma)
        val (baselineBand, group) = baseline.groups[band]?.let { band to it }
            ?: baseline.groups.entries.minByOrNull { kotlin.math.abs(it.key.ordinal - band.ordinal) }?.let { it.key to it.value }
            ?: return@mapNotNull null
        val brightPercent = frame.brightPercent
        val mean = group.brightPercentMean
        val deviation = group.brightPercentDeviation
        if (brightPercent != null && mean != null && deviation != null) {
            RelativeOverexposureDiagnostic(band, baselineBand, mean, mean + maxOf(5f, deviation * 3f), brightPercent)
        } else null
    }.maxByOrNull { it.currentBrightPercent - it.threshold }
}

fun relativeOverexposureReason(baseline: SceneBaseline, frames: List<AiFrameFeature>): String? {
    val diagnostic = relativeOverexposureDiagnostic(baseline, frames)
    return if (diagnostic != null && diagnostic.currentBrightPercent > diagnostic.threshold) "画面过曝" else null
}

fun assessSpatialOcclusion(baseline: SceneBaseline, frames: List<SpatialSignature>): SpatialOcclusionAssessment {
    var run = 0
    var longestRun = 0
    var highestPercent = 0f
    val spatial = baseline.groups.values.mapNotNull { it.spatial }.firstOrNull()
        ?: return SpatialOcclusionAssessment(false, 0, 0f)
    frames.forEach { frame ->
        if (frame.luma.size != 64 || frame.texture.size != 64) return@forEach
        val changed = (0 until 64).count { cell ->
            kotlin.math.abs(frame.luma[cell] - spatial.lumaCenter[cell]) > maxOf(20f, spatial.lumaDeviation[cell] * 4f) ||
                kotlin.math.abs(frame.texture[cell] - spatial.textureCenter[cell]) > maxOf(12f, spatial.textureDeviation[cell] * 4f)
        }
        val percent = changed * 100f / 64f
        highestPercent = maxOf(highestPercent, percent)
        if (percent >= 30f) { run++; longestRun = maxOf(longestRun, run) } else run = 0
    }
    return SpatialOcclusionAssessment(longestRun >= 3, longestRun, highestPercent)
}

fun assessSpatialBrightHotspot(baseline: SceneBaseline, frames: List<SpatialSignature>): SpatialBrightHotspotAssessment {
    var run = 0
    var longestRun = 0
    var highestPercent = 0f
    val spatial = baseline.groups.values.mapNotNull { it.spatial }.firstOrNull()
        ?: return SpatialBrightHotspotAssessment(false, 0, 0f)
    frames.forEach { frame ->
        if (frame.luma.size != 64) return@forEach
        val brightened = BooleanArray(64) { cell ->
            frame.luma[cell] - spatial.lumaCenter[cell] > maxOf(40f, spatial.lumaDeviation[cell] * 4f)
        }
        val percent = largestConnectedBrightenedPercent(brightened)
        highestPercent = maxOf(highestPercent, percent)
        if (percent in 8f..30f) { run++; longestRun = maxOf(longestRun, run) } else run = 0
    }
    return SpatialBrightHotspotAssessment(longestRun >= 3, longestRun, highestPercent)
}

private fun largestConnectedBrightenedPercent(cells: BooleanArray): Float {
    require(cells.size == 64)
    val visited = BooleanArray(64)
    var largest = 0
    cells.indices.forEach { start ->
        if (!cells[start] || visited[start]) return@forEach
        val queue = IntArray(64)
        var head = 0
        var tail = 0
        var size = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val index = queue[head++]
            size++
            val x = index % 8
            val y = index / 8
            val neighbors = intArrayOf(
                if (x > 0) index - 1 else -1,
                if (x < 7) index + 1 else -1,
                if (y > 0) index - 8 else -1,
                if (y < 7) index + 8 else -1,
            )
            neighbors.forEach { neighbor ->
                if (neighbor >= 0 && cells[neighbor] && !visited[neighbor]) {
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
        }
        largest = maxOf(largest, size)
    }
    return largest * 100f / 64f
}

private fun List<AiFrameFeature>.standardDeviation(selector: (AiFrameFeature) -> Float): Float {
    val mean = map(selector).average().toFloat()
    return sqrt(map { (selector(it) - mean) * (selector(it) - mean) }.average()).toFloat().coerceAtLeast(0.001f)
}

private fun List<AiFrameFeature>.completeBrightPercentStatistics(): Pair<Float, Float>? {
    val values = map { it.brightPercent ?: return null }
    val mean = values.average().toFloat()
    val deviation = sqrt(values.map { (it - mean) * (it - mean) }.average()).toFloat().coerceAtLeast(0.001f)
    return mean to deviation
}

private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    if (left.size != right.size || left.isEmpty()) return 0f
    return left.indices.sumOf { (left[it] * right[it]).toDouble() }.toFloat().coerceIn(-1f, 1f)
}
