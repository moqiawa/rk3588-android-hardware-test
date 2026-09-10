package com.rk.hardwaretest.interfaces

enum class GpioLevel(val label: String) { HIGH("高电平"), LOW("低电平"), UNAVAILABLE("不可读取") }

data class GpioReading(val level: GpioLevel, val changed: Boolean = false)

fun parseGpioLevel(value: String?): GpioLevel = when (value?.trim()) {
    "1" -> GpioLevel.HIGH
    "0" -> GpioLevel.LOW
    else -> GpioLevel.UNAVAILABLE
}
