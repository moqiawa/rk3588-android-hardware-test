package com.rk.hardwaretest.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDeviceLabelsTest {
    @Test fun labels_common_input_device_types() {
        assertEquals("内置麦克风", inputDeviceTypeLabel(15))
        assertEquals("USB 音频", inputDeviceTypeLabel(11))
        assertEquals("蓝牙音频", inputDeviceTypeLabel(7))
    }
}
