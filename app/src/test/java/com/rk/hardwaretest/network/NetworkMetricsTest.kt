package com.rk.hardwaretest.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkMetricsTest {
    @Test fun formats_transfer_rates_in_human_readable_units() {
        assertEquals("800 bps", formatBitsPerSecond(800))
        assertEquals("8.0 Mbps", formatBitsPerSecond(8_000_000))
    }

    @Test fun computes_bytes_per_second_from_two_counter_samples() {
        assertEquals(250L, bytesPerSecond(1_000L, 1_500L, 2_000L))
        assertEquals(0L, bytesPerSecond(1_500L, 1_000L, 2_000L))
    }
}
