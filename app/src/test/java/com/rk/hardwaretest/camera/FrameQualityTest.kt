package com.rk.hardwaretest.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLumaStatsTest {
    // This fails if a retained Camera2 controller keeps publishing to the previous camera page state.
    @Test fun latest_metrics_callback_receives_updates_after_camera_switch() {
        var firstCameraMetrics: CameraPreviewMetrics? = null
        var secondCameraMetrics: CameraPreviewMetrics? = null
        val callback = LatestMetricsCallback { firstCameraMetrics = it }
        val sample = CameraPreviewMetrics(2, true, 30f, null, null)

        callback.update { secondCameraMetrics = it }
        callback.publish(sample)

        assertEquals(null, firstCameraMetrics)
        assertEquals(sample, secondCameraMetrics)
    }

    // This fails if unavailable Camera2 metadata is rendered as a fabricated numeric value.
    @Test fun unavailable_metrics_are_not_rendered_as_zero_values() {
        val text = CameraPreviewMetrics(null, false, null, null, null).summaryLines()

        assertTrue(text.any { it == "曝光补偿：不支持" })
        assertTrue(text.any { it == "实时帧率：不可用" })
    }

    // This fails if a luminance sample replaces capture metadata that the side panel already received.
    @Test fun luminance_update_preserves_latest_capture_metadata() {
        val captured = CameraPreviewMetrics(
            actualExposure = 3,
            exposureSupported = true,
            fps = 29.5f,
            outputSize = null,
            luma = null,
        )

        val updated = captured.withLuma(FrameLumaStats(128f, 12.5f, 8.25f))

        assertEquals(3, updated.actualExposure)
        assertTrue(updated.exposureSupported)
        assertEquals(29.5f, updated.fps!!, 0.01f)
        assertEquals(null, updated.outputSize)
        assertEquals(FrameLumaStats(128f, 12.5f, 8.25f), updated.luma)
    }

    @Test fun dark_frame_reports_luminance_and_dark_pixel_share() {
        val stats = calculateLumaStats(ByteArray(100) { 2 })
        assertEquals(2f, stats.meanLuma, 0.01f)
        assertEquals(100f, stats.darkPercent, 0.01f)
        assertEquals(0f, stats.brightPercent, 0.01f)
    }

    @Test fun bright_frame_reports_bright_pixel_share() {
        val stats = calculateLumaStats(ByteArray(100) { 250.toByte() })
        assertEquals(250f, stats.meanLuma, 0.01f)
        assertEquals(100f, stats.brightPercent, 0.01f)
    }
}
