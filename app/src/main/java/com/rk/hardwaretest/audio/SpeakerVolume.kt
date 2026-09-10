package com.rk.hardwaretest.audio

import kotlin.math.roundToInt

fun speakerVolumeIndex(percent: Float, maxIndex: Int): Int =
    (percent.coerceIn(0f, 100f) * maxIndex / 100f).roundToInt().coerceIn(0, maxIndex)
