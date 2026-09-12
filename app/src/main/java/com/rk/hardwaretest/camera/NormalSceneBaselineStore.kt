package com.rk.hardwaretest.camera

import android.content.Context

private const val BASELINE_FILE = "camera_ai_baseline"

fun cameraBaselineKey(cameraId: String): String = "groups_camera_${cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")}" 
private fun baselineRoiKey(cameraId: String) = "${cameraBaselineKey(cameraId)}_roi"

fun saveBaseline(context: Context, cameraId: String, baseline: SceneBaseline) {
    val groups = baseline.groups.entries.joinToString("|") { (band, item) ->
        "$band;${item.sampleCount};${item.redMean};${item.greenMean};${item.blueMean};${item.center.joinToString(",")};${item.deviation.joinToString(",")};${item.redDeviation};${item.greenDeviation};${item.blueDeviation}" + item.spatial?.let { ";${it.lumaCenter.joinToString(",")};${it.lumaDeviation.joinToString(",")};${it.textureCenter.joinToString(",")};${it.textureDeviation.joinToString(",")}" }.orEmpty() + item.brightPercentMean?.let { mean -> item.brightPercentDeviation?.let { deviation -> ";$mean;$deviation" } }.orEmpty() }
    context.getSharedPreferences(BASELINE_FILE, Context.MODE_PRIVATE).edit().putString(cameraBaselineKey(cameraId), groups).apply()
}

fun saveStoredBaseline(context: Context, cameraId: String, stored: StoredSceneBaseline) {
    saveBaseline(context, cameraId, stored.baseline)
    val editor = context.getSharedPreferences(BASELINE_FILE, Context.MODE_PRIVATE).edit()
    val roi = stored.roi
    if (roi == null) editor.remove(baselineRoiKey(cameraId))
    else editor.putString(baselineRoiKey(cameraId), listOf(roi.left, roi.top, roi.right, roi.bottom).joinToString(","))
    editor.apply()
}

fun loadBaseline(context: Context, cameraId: String): SceneBaseline? = runCatching {
    val groups = context.getSharedPreferences(BASELINE_FILE, Context.MODE_PRIVATE).getString(cameraBaselineKey(cameraId), null) ?: return null
    groups.split("|").associate { encoded ->
        val fields = encoded.split(";")
        val center = fields[5].split(",").map(String::toFloat).toFloatArray()
        val deviation = fields[6].split(",").map(String::toFloat).toFloatArray()
        val spatial = if (fields.size >= 14) SpatialBaseline(
            fields[10].split(",").map(String::toFloat).toFloatArray(), fields[11].split(",").map(String::toFloat).toFloatArray(),
            fields[12].split(",").map(String::toFloat).toFloatArray(), fields[13].split(",").map(String::toFloat).toFloatArray(),
        ).takeIf { it.lumaCenter.size == 64 && it.lumaDeviation.size == 64 && it.textureCenter.size == 64 && it.textureDeviation.size == 64 } else null
        val brightIndex = when (fields.size) {
            12 -> 10
            in 16..Int.MAX_VALUE -> 14
            else -> null
        }
        LightBand.valueOf(fields[0]) to FeatureBaseline(
            center, deviation, fields[2].toFloat(), fields[3].toFloat(), fields[4].toFloat(), fields[1].toInt(),
            fields.getOrNull(7)?.toFloatOrNull() ?: 1f,
            fields.getOrNull(8)?.toFloatOrNull() ?: 1f,
            fields.getOrNull(9)?.toFloatOrNull() ?: 1f,
            spatial,
            brightIndex?.let { fields[it].toFloatOrNull() },
            brightIndex?.let { fields[it + 1].toFloatOrNull() },
        )
    }.takeIf { it.isNotEmpty() }?.let(::SceneBaseline)
}.getOrNull()

fun loadStoredBaseline(context: Context, cameraId: String): StoredSceneBaseline? {
    val baseline = loadBaseline(context, cameraId) ?: return null
    val fields = context.getSharedPreferences(BASELINE_FILE, Context.MODE_PRIVATE)
        .getString(baselineRoiKey(cameraId), null)?.split(",")?.mapNotNull(String::toFloatOrNull)
    val roi = fields?.takeIf { it.size == 4 }?.let { NormalizedRoi(it[0], it[1], it[2], it[3]) }?.takeIf(NormalizedRoi::isValid)
    return StoredSceneBaseline(baseline, roi)
}
