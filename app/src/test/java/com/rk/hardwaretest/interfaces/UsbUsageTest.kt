package com.rk.hardwaretest.interfaces

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbUsageTest {
    @Test fun labels_standard_usb_interface_classes() {
        assertEquals("视频输入（摄像头）", usbUsageLabel(14))
        assertEquals("音频输入/输出", usbUsageLabel(1))
        assertEquals("人机输入（触摸/键盘）", usbUsageLabel(3))
    }

    @Test fun keeps_unknown_classes_explicit() {
        assertEquals("通用 USB 外设", usbUsageLabel(255))
    }
}
