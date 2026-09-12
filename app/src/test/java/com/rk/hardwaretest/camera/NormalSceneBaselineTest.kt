package com.rk.hardwaretest.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalSceneBaselineTest {
    // This fails if normal recordings are not separated by their expected brightness conditions.
    @Test fun baseline_groups_normal_features_by_luminance() {
        val baseline = buildSceneBaseline(listOf(
            feature(70f, floatArrayOf(1f, 0f)),
            feature(80f, floatArrayOf(0.9f, 0.1f)),
            feature(150f, floatArrayOf(0f, 1f)),
            feature(160f, floatArrayOf(0.1f, 0.9f)),
            feature(210f, floatArrayOf(0.7f, 0.7f)),
            feature(220f, floatArrayOf(0.6f, 0.8f)),
        ))

        assertEquals(2, baseline!!.groups.getValue(LightBand.LOW).sampleCount)
        assertEquals(2, baseline.groups.getValue(LightBand.MID).sampleCount)
        assertEquals(2, baseline.groups.getValue(LightBand.HIGH).sampleCount)
    }

    @Test fun three_consecutive_distant_features_are_anomalous() {
        val baseline = buildSceneBaseline(listOf(
            feature(150f, floatArrayOf(1f, 0f)),
            feature(160f, floatArrayOf(1f, 0f)),
        ))!!

        val assessment = assessSceneAnomalies(baseline, List(3) { feature(150f, floatArrayOf(0f, 1f)) })

        assertTrue(assessment.hasAnomaly)
    }

    // This fails if unusable extreme-lighting footage can contaminate a normal-scene baseline.
    @Test fun only_normally_exposed_frames_are_accepted_for_baseline_creation() {
        assertTrue(isUsableBaselineFrame(RecordedFrameSample(80f, 5f, 5f, 30f)))
        assertTrue(!isUsableBaselineFrame(RecordedFrameSample(80f, 61f, 5f, 30f)))
        assertTrue(!isUsableBaselineFrame(RecordedFrameSample(80f, 5f, 36f, 30f)))
    }

    // This fails if a sustained channel shift is hidden behind a generic AI anomaly message.
    @Test fun color_shift_outside_baseline_variation_is_reported() {
        val baseline = buildSceneBaseline(listOf(
            AiFrameFeature(150f, 100f, 100f, 100f, floatArrayOf(1f, 0f)),
            AiFrameFeature(160f, 100f, 100f, 100f, floatArrayOf(1f, 0f)),
        ))!!.groups.getValue(LightBand.MID)

        assertEquals("画面偏色", colorCastReason(baseline, AiFrameFeature(150f, 140f, 100f, 100f, floatArrayOf(1f, 0f))))
    }

    // This fails if two physical cameras can overwrite one another's normal-scene baseline.
    @Test fun every_camera_id_has_a_distinct_baseline_storage_key() {
        assertEquals("groups_camera_0", cameraBaselineKey("0"))
        assertEquals("groups_camera_1", cameraBaselineKey("1"))
        assertTrue(cameraBaselineKey("0") != cameraBaselineKey("1"))
    }

    // This fails if a uniform exposure change is mislabeled as a color cast.
    @Test fun uniform_brightness_change_is_not_a_color_cast() {
        val baseline = buildSceneBaseline(listOf(
            AiFrameFeature(150f, 100f, 100f, 100f, floatArrayOf(1f, 0f)),
            AiFrameFeature(160f, 100f, 100f, 100f, floatArrayOf(1f, 0f)),
        ))!!.groups.getValue(LightBand.MID)

        assertEquals(null, colorCastReason(baseline, AiFrameFeature(165f, 110f, 110f, 110f, floatArrayOf(1f, 0f))))
    }

    // This fails if the baseline has no record of where changes occurred within the frame.
    @Test fun spatial_signature_preserves_eight_by_eight_regions() {
        val pixels = IntArray(16 * 8) { index -> if (index % 16 < 8) 0xff202020.toInt() else 0xffe0e0e0.toInt() }
        val signature = spatialSignature(pixels, 16, 8)

        assertEquals(64, signature.luma.size)
        assertTrue(signature.luma.first() < signature.luma.last())
    }

    // This fails if persistent half-frame content loss remains invisible to a normal-scene baseline.
    @Test fun three_half_frame_spatial_changes_are_reported_as_occlusion() {
        val normal = signature(50f)
        val baseline = buildSceneBaseline(listOf(
            AiFrameFeature(150f, 100f, 100f, 100f, floatArrayOf(1f, 0f), normal),
            AiFrameFeature(160f, 100f, 100f, 100f, floatArrayOf(1f, 0f), normal),
        ))!!
        val occluded = SpatialSignature(FloatArray(64) { if (it < 32) 180f else 50f }, FloatArray(64) { 5f })

        assertTrue(assessSpatialOcclusion(baseline, List(3) { occluded }).hasOcclusion)
    }

    // This fails if a sustained localized bright hotspot is only classified as a generic spatial change.
    @Test fun three_localized_bright_regions_are_reported_as_a_hotspot() {
        val normal = signature(50f)
        val baseline = buildSceneBaseline(listOf(
            AiFrameFeature(150f, 100f, 100f, 100f, floatArrayOf(1f, 0f), normal),
            AiFrameFeature(160f, 100f, 100f, 100f, floatArrayOf(1f, 0f), normal),
        ))!!
        val hotspot = SpatialSignature(FloatArray(64) { if (it < 8) 150f else 50f }, FloatArray(64) { 5f })

        assertTrue(assessSpatialBrightHotspot(baseline, List(3) { hotspot }).hasBrightHotspot)
    }

    // This fails if a large bright region caused by exposure compensation after an obstruction is called a local hotspot.
    @Test fun half_frame_brightening_is_not_reported_as_a_hotspot() {
        val normal = signature(50f)
        val baseline = buildSceneBaseline(listOf(
            AiFrameFeature(150f, 100f, 100f, 100f, floatArrayOf(1f, 0f), normal),
            AiFrameFeature(160f, 100f, 100f, 100f, floatArrayOf(1f, 0f), normal),
        ))!!
        val halfBright = SpatialSignature(FloatArray(64) { if (it < 32) 150f else 50f }, FloatArray(64) { 5f })

        assertTrue(!assessSpatialBrightHotspot(baseline, List(3) { halfBright }).hasBrightHotspot)
    }

    private fun signature(luma: Float) = SpatialSignature(FloatArray(64) { luma }, FloatArray(64) { 5f })

    private fun feature(luma: Float, embedding: FloatArray) = AiFrameFeature(luma, 100f, 100f, 100f, embedding)
}
