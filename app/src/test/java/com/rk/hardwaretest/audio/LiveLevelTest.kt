package com.rk.hardwaretest.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLevelTest {
    @Test
    fun level_reports_peak_above_rms_for_non_constant_pcm() {
        val level = pcmLevel(shortArrayOf(0, 1_000, -1_000))
        assertTrue(level.peakDbfs < 0f)
        assertTrue(level.rmsDbfs < level.peakDbfs)
    }
}
