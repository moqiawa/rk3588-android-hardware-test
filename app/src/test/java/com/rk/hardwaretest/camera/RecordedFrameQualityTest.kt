package com.rk.hardwaretest.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedFrameQualityTest {
    // This fails if the quality gate stops accepting consistently sharp, well-exposed footage.
    @Test fun sharp_and_well_exposed_frames_pass() {
        val result = assessRecordedFrames(List(16) { RecordedFrameSample(80f, 2f, 2f, 35f) })

        assertEquals(RecordedQualityStatus.PASS, result.status)
        assertTrue(result.reasons.isEmpty())
    }

    // This fails if a uniform capture is mistaken for detailed footage or has fabricated contrast.
    @Test fun uniform_gray_frame_has_zero_contrast_and_sharpness() {
        val sample = bitmapFrameSample(IntArray(9) { 0xff808080.toInt() }, 3, 3)

        assertEquals(0f, sample.contrast, 0.01f)
        assertEquals(0f, sample.sharpness, 0.01f)
    }

    @Test fun single_blurry_frame_in_recording_is_reported() {
        val frames = List(15) { RecordedFrameSample(80f, 2f, 2f, 35f) } + RecordedFrameSample(10f, 2f, 2f, 35f)

        val result = assessRecordedFrames(frames)

        assertEquals(RecordedQualityStatus.WARNING, result.status)
        assertTrue(result.reasons.contains("可能失焦或画面模糊"))
    }

    @Test fun empty_video_fails_and_eight_seconds_yield_sixteen_samples() {
        assertEquals(RecordedQualityStatus.FAIL, assessRecordedFrames(emptyList()).status)
        assertEquals(16, videoSampleTimesUs(8_000).size)
    }

    // This fails if a half-frame dark obstruction remains below the camera quality gate.
    @Test fun half_frame_obstruction_is_reported() {
        val result = assessRecordedFrames(List(30) { RecordedFrameSample(80f, 45f, 2f, 30f) })

        assertTrue(result.reasons.contains("画面过暗或镜头被遮挡"))
    }

    // This fails if an uncalibrated recording falls back to the former fixed eight-percent highlight threshold.
    @Test fun uncalibrated_highlights_are_not_reported_as_overexposure() {
        val result = assessRecordedFrames(List(30) { RecordedFrameSample(80f, 2f, 10f, 30f) })

        assertTrue(!result.reasons.contains("画面过曝"))
    }

    // This fails if a sustained local hotspot is hidden behind the generic spatial-change reason.
    @Test fun local_hotspot_is_reported_as_localized_overexposure() {
        val result = assessRecordedFrames(
            List(30) { RecordedFrameSample(80f, 2f, 2f, 30f) },
            overexposureReason = "画面过曝（局部强光）",
        )

        assertTrue(result.reasons.contains("画面过曝（局部强光）"))
    }

    // This fails if normal high-light regions in a calibrated scene are still reported as overexposure.
    @Test fun baseline_highlight_ratio_of_ten_percent_is_not_reported_as_overexposure() {
        val baseline = buildSceneBaseline(List(2) { feature(luma = 150f, brightPercent = 10f) })!!

        assertEquals(
            null,
            relativeOverexposureReason(baseline, listOf(feature(luma = 150f, brightPercent = 10f))),
        )
    }

    // This fails if a localized flashlight raises the highlight ratio well beyond its calibrated scene without a warning.
    @Test fun highlight_ratio_of_twenty_five_percent_is_reported_relative_to_baseline() {
        val baseline = buildSceneBaseline(List(2) { feature(luma = 150f, brightPercent = 10f) })!!

        assertEquals(
            "画面过曝",
            relativeOverexposureReason(baseline, listOf(feature(luma = 150f, brightPercent = 25f))),
        )
    }

    // This fails if field calibration cannot see the actual reference and threshold behind a relative exposure decision.
    @Test fun relative_overexposure_diagnostic_reports_baseline_threshold_and_current_highlight_ratio() {
        val baseline = buildSceneBaseline(List(2) { feature(luma = 150f, brightPercent = 10f) })!!

        val diagnostic = relativeOverexposureDiagnostic(baseline, listOf(feature(luma = 150f, brightPercent = 25f)))!!

        assertEquals(LightBand.MID, diagnostic.band)
        assertEquals(10f, diagnostic.baselineBrightPercent, 0.01f)
        assertEquals(15f, diagnostic.threshold, 0.01f)
        assertEquals(25f, diagnostic.currentBrightPercent, 0.01f)
    }

    // This fails if baseline records created before highlight statistics can produce a relative-exposure warning.
    @Test fun baseline_without_highlight_statistics_does_not_report_overexposure() {
        val oldBaseline = SceneBaseline(mapOf(LightBand.MID to FeatureBaseline(
            center = floatArrayOf(1f), deviation = floatArrayOf(0.001f),
            redMean = 100f, greenMean = 100f, blueMean = 100f, sampleCount = 2,
        )))

        assertEquals(
            null,
            relativeOverexposureReason(oldBaseline, listOf(feature(luma = 150f, brightPercent = 25f))),
        )
    }

    // This fails if a flashlight-driven brightness-group shift skips exposure detection despite a large highlight increase.
    @Test fun brighter_group_uses_nearest_calibrated_group_for_overexposure() {
        val baseline = buildSceneBaseline(List(2) { feature(luma = 150f, brightPercent = 10f) })!!

        assertEquals(
            "画面过曝",
            relativeOverexposureReason(baseline, listOf(feature(luma = 210f, brightPercent = 25f))),
        )
    }

    // This fails if the post-recording AI path continues to inspect only sixteen frames.
    @Test fun eight_second_recording_uses_up_to_one_hundred_twenty_analysis_frames() {
        assertEquals(120, recordingAnalysisTimesUs(8_000).size)
    }

    // This fails if an unexplained AI signal hides a deterministic camera-quality failure.
    @Test fun rule_reason_precedes_ai_only_scene_anomaly() {
        assertEquals(
            listOf("画面过曝", "画面异常，疑似镜头污渍、水雾或场景偏移"),
            combineQualityReasons(
                ruleReasons = listOf("画面过曝"),
                aiReasons = listOf("画面异常，疑似镜头污渍、水雾或场景偏移"),
            ),
        )
    }

    // This fails if baseline creation and recorded-video analysis feed different RGB ordering to RKNN.
    @Test fun ai_input_converts_argb_to_rgb_and_reports_color_statistics() {
        val input = bitmapAiInput(intArrayOf(0xffc86432.toInt()), 1, 1)

        assertEquals(224 * 224 * 3, input.rgb224.size)
        assertEquals(0xc8.toByte(), input.rgb224[0])
        assertEquals(0x64.toByte(), input.rgb224[1])
        assertEquals(0x32.toByte(), input.rgb224[2])
        assertEquals(200f, input.redMean, 0.01f)
        assertEquals(100f, input.greenMean, 0.01f)
        assertEquals(50f, input.blueMean, 0.01f)
    }

    private fun feature(luma: Float, brightPercent: Float) = AiFrameFeature(
        luma = luma,
        redMean = 100f,
        greenMean = 100f,
        blueMean = 100f,
        embedding = floatArrayOf(1f),
        brightPercent = brightPercent,
    )
}
