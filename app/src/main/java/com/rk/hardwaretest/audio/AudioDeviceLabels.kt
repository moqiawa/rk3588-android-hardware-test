package com.rk.hardwaretest.audio

fun inputDeviceTypeLabel(type: Int): String = when (type) {
    15 -> "内置麦克风"
    4 -> "有线耳机麦克风"
    7 -> "蓝牙音频"
    11, 22 -> "USB 音频"
    else -> "其他输入设备"
}
