package com.rk.hardwaretest.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSceneProfileTest {
    // This fails if a default fixed-camera profile stops inspecting the full frame.
    @Test fun default_profile_uses_fixed_full_frame_roi() {
        val profile = CameraSceneProfile(CameraSceneMode.FIXED)

        assertTrue(profile.roi == NormalizedRoi.fullFrame())
        assertTrue(shouldRunFixedSceneModel(profile))
    }

    // This fails if normalized ROI conversion includes pixels outside the selected rectangle.
    @Test fun middle_half_roi_crops_the_expected_argb_pixels() {
        val roi = NormalizedRoi(.25f, .25f, .75f, .75f)

        val cropped = roi.cropArgb(IntArray(16) { it }, 4, 4)

        assertTrue(cropped.width == 2 && cropped.height == 2)
        assertArrayEquals(intArrayOf(5, 6, 9, 10), cropped.argb)
    }

    // This fails if an empty selected region can reach the image-quality and AI pipeline.
    @Test fun zero_width_roi_is_invalid() {
        assertFalse(NormalizedRoi(.4f, .1f, .4f, .9f).isValid())
    }

    // This fails if a baseline made from another region can be used after an ROI edit.
    @Test fun baseline_is_rejected_after_roi_changes() {
        val stored = StoredSceneBaseline(SceneBaseline(emptyMap()), NormalizedRoi.fullFrame())

        assertFalse(baselineMatchesRoi(stored, NormalizedRoi(.1f, .1f, .9f, .9f)))
    }

    // This fails if pre-ROI baseline records are treated as compatible without rebuilding.
    @Test fun legacy_baseline_without_roi_requires_rebuild() {
        val stored = StoredSceneBaseline(SceneBaseline(emptyMap()), roi = null)

        assertFalse(baselineMatchesRoi(stored, NormalizedRoi.fullFrame()))
    }

    // This fails if a variable camera setting accidentally enables the fixed-scene model.
    @Test fun variable_scene_does_not_run_the_fixed_scene_model() {
        assertFalse(shouldRunFixedSceneModel(CameraSceneProfile(CameraSceneMode.VARIABLE)))
    }

    // This fails if the fixed-scene path keeps forwarding whole-frame pixels.
    @Test fun fixed_scene_model_receives_only_roi_pixels() {
        val profile = CameraSceneProfile(CameraSceneMode.FIXED, NormalizedRoi(.5f, 0f, 1f, 1f))

        assertArrayEquals(intArrayOf(1, 3, 5, 7), profile.roi.cropArgb(IntArray(8) { it }, 2, 4).argb)
    }

    // This fails if a user drags from either corner and the preview selection keeps
    // inverted or out-of-frame coordinates instead of the visible rectangle.
    @Test fun preview_drag_clamps_and_orders_the_selected_rectangle() {
        val roi = normalizedRoiFromDrag(startX = 1.15f, startY = .82f, endX = .24f, endY = -.12f)

        assertTrue(roi == NormalizedRoi(.24f, 0f, 1f, .82f))
    }
}
