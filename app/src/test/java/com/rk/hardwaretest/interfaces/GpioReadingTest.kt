package com.rk.hardwaretest.interfaces

import org.junit.Assert.assertEquals
import org.junit.Test

class GpioReadingTest {
    @Test fun parses_high_and_low_sysfs_values() {
        assertEquals(GpioLevel.HIGH, parseGpioLevel("1\n"))
        assertEquals(GpioLevel.LOW, parseGpioLevel("0"))
    }

    @Test fun marks_unknown_sysfs_values_as_unavailable() {
        assertEquals(GpioLevel.UNAVAILABLE, parseGpioLevel("bad"))
    }
}
