package com.rk.hardwaretest.network

import kotlin.math.max

fun bytesPerSecond(previousBytes: Long, currentBytes: Long, elapsedMillis: Long): Long =
    if (previousBytes < 0L || currentBytes < previousBytes || elapsedMillis <= 0L) 0L
    else (currentBytes - previousBytes) * 1_000L / elapsedMillis

fun formatBitsPerSecond(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000L -> "%.1f Mbps".format(bitsPerSecond / 1_000_000.0)
    bitsPerSecond >= 1_000L -> "%.1f Kbps".format(bitsPerSecond / 1_000.0)
    else -> "${max(0L, bitsPerSecond)} bps"
}
