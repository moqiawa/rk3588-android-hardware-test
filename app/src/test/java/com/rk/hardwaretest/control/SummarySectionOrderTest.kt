package com.rk.hardwaretest.control

import org.junit.Assert.assertEquals
import org.junit.Test

class SummarySectionOrderTest {
    @Test fun keeps_every_hardware_area_in_the_one_click_summary() {
        assertEquals(
            listOf("摄像头", "扬声器", "麦克风", "网络", "接口"),
            oneClickSummarySections(),
        )
    }
}
