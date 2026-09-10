package com.rk.hardwaretest.control

import com.rk.hardwaretest.model.TestResult

data class CameraControlCapabilities(
    val exposureIndices: IntRange,
    val exposureStepEv: Float,
    val zoomRange: ClosedFloatingPointRange<Float>,
) {
    val exposureQuickChoices: List<Int>
        get() = listOf(exposureIndices.first, 0, exposureIndices.last)
            .filter { it in exposureIndices }.distinct()
    val zoomQuickChoices: List<Float>
        get() = listOf(1f, 2f, zoomRange.endInclusive)
            .filter { it in zoomRange }.distinct()
}

fun visibleResultsForPage(page: String, all: Map<String, TestResult>): Map<String, TestResult> = when (page) {
    "摄像头测试" -> all.filterKeys { it == "camera" }
    "扬声器测试" -> all.filterKeys { it == "speaker" }
    "麦克风测试" -> all.filterKeys { it == "microphone" }
    "网络测试" -> all.filterKeys { it == "network" }
    else -> all
}

fun oneClickSummarySections(): List<String> = listOf("摄像头", "扬声器", "麦克风", "网络", "接口")
