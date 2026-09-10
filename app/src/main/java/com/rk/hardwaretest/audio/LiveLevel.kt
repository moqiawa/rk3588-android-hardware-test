package com.rk.hardwaretest.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class LiveLevel(val peakDbfs: Float, val rmsDbfs: Float)
enum class MicrophoneHealth { WAITING, NORMAL, QUIET, NO_INPUT }
enum class LevelBand(val label: String) { CLIPPING("红色：削波风险"), LOUD("橙色：音量偏高"), NORMAL("绿色：正常输入"), QUIET("黄色：音量偏低"), SILENT("灰色：静音或无输入") }

fun microphoneHealth(frameCount: Int, peakDbfs: Float): MicrophoneHealth = when {
    frameCount <= 0 -> MicrophoneHealth.WAITING
    peakDbfs < -60f -> MicrophoneHealth.QUIET
    else -> MicrophoneHealth.NORMAL
}
fun microphoneHealthAfterEmptyReads(emptyReads: Int, limit: Int): MicrophoneHealth =
    if (emptyReads >= limit) MicrophoneHealth.NO_INPUT else MicrophoneHealth.WAITING
fun levelBand(peakDbfs: Float): LevelBand = when {
    peakDbfs >= -3f -> LevelBand.CLIPPING
    peakDbfs >= -10f -> LevelBand.LOUD
    peakDbfs >= -35f -> LevelBand.NORMAL
    peakDbfs >= -60f -> LevelBand.QUIET
    else -> LevelBand.SILENT
}

fun pcmLevel(samples: ShortArray): LiveLevel {
    if (samples.isEmpty()) return LiveLevel(-80f, -80f)
    val peak = samples.maxOf { abs(it.toInt()) }.toDouble()
    val meanSquare = samples.map { it.toDouble() * it.toDouble() }.average()
    fun dbfs(value: Double): Float = (20.0 * log10(max(1.0, value) / Short.MAX_VALUE)).toFloat()
    return LiveLevel(dbfs(peak), dbfs(sqrt(meanSquare)))
}
