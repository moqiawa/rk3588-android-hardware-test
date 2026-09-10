package com.rk.hardwaretest.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMonitorPolicyTest {
    @Test fun a_single_empty_read_keeps_monitor_waiting() {
        assertEquals(MicrophoneHealth.WAITING, microphoneHealth(0, -18f))
    }
    @Test fun intermittent_empty_read_does_not_mean_a_broken_microphone() {
        assertEquals(MicrophoneHealth.WAITING, microphoneHealthAfterEmptyReads(1, 20))
        assertEquals(MicrophoneHealth.NO_INPUT, microphoneHealthAfterEmptyReads(20, 20))
    }
    @Test fun peak_colours_are_human_readable() {
        assertEquals(LevelBand.CLIPPING, levelBand(-1f))
        assertEquals(LevelBand.NORMAL, levelBand(-14f))
        assertEquals(LevelBand.QUIET, levelBand(-45f))
    }
}
