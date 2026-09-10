package com.rk.hardwaretest.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerVolumeTest {
    @Test fun percentage_maps_to_the_audio_manager_volume_range() {
        assertEquals(0, speakerVolumeIndex(0f, 15))
        assertEquals(8, speakerVolumeIndex(50f, 15))
        assertEquals(15, speakerVolumeIndex(100f, 15))
    }

    @Test fun percentage_is_clamped_before_mapping() {
        assertEquals(0, speakerVolumeIndex(-10f, 15))
        assertEquals(15, speakerVolumeIndex(150f, 15))
    }
}
