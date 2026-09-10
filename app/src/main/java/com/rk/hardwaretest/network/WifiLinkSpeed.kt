package com.rk.hardwaretest.network

fun wifiLinkSpeedLabel(mbps: Int): String = if (mbps < 0) "未提供" else "$mbps Mbps"
