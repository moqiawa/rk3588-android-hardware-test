package com.rk.hardwaretest.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiLinkSpeedTest {
    @Test fun labels_unknown_link_rates_without_claiming_zero_speed() {
        assertEquals("未提供", wifiLinkSpeedLabel(-1))
        assertEquals("78 Mbps", wifiLinkSpeedLabel(78))
    }
}
