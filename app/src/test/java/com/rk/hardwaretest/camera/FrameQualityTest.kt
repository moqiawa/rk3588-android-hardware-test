package com.rk.hardwaretest.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameLumaStatsTest {
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
