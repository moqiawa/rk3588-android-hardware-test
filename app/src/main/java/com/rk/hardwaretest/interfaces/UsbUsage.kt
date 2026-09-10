package com.rk.hardwaretest.interfaces

fun usbUsageLabel(usbClass: Int): String = when (usbClass) {
    1 -> "音频输入/输出"
    2, 10 -> "网络/通信"
    3 -> "人机输入（触摸/键盘）"
    8 -> "存储设备"
    9 -> "USB 集线器"
    14 -> "视频输入（摄像头）"
    else -> "通用 USB 外设"
}
