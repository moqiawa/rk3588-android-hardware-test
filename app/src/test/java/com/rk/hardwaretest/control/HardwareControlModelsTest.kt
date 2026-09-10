package com.rk.hardwaretest.control

import com.rk.hardwaretest.model.TestResult
import com.rk.hardwaretest.model.TestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HardwareControlModelsTest {
    private val results = mapOf(
        "camera" to TestResult(TestStatus.PASS, "camera"),
        "speaker" to TestResult(TestStatus.PASS, "speaker"),
        "microphone" to TestResult(TestStatus.PASS, "microphone"),
        "network" to TestResult(TestStatus.PASS, "network"),
    )

    @Test
    fun camera_page_exposes_only_camera_result() {
        assertEquals(setOf("camera"), visibleResultsForPage("摄像头测试", results).keys)
    }

    @Test
    fun one_click_page_exposes_all_results() {
        assertEquals(results, visibleResultsForPage("一键测试", results))
    }

    @Test
    fun exposure_control_covers_declared_camera_range_and_includes_zero_when_supported() {
        val capabilities = CameraControlCapabilities(-6..9, 0.5f, 1f..4f)

        assertEquals(-6..9, capabilities.exposureIndices)
        assertEquals(listOf(-6, 0, 9), capabilities.exposureQuickChoices)
    }

    @Test
    fun zoom_control_does_not_offer_unsupported_two_times_zoom() {
        val capabilities = CameraControlCapabilities(-1..1, 0.333f, 1f..1.5f)

        assertFalse(capabilities.zoomQuickChoices.contains(2f))
    }
}
