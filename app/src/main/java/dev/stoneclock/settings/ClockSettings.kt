package dev.stoneclock.settings

data class ClockSettings(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val digitSpacing: Float = 0f,
    val colonSpacing: Float = 8f,
    val opacity: Float = 1f,
    val is24Hour: Boolean = true,
    val backgroundImage: String? = null,
    val backgroundColor: Int = 0xFF090909.toInt(),
    val weatherEnabled: Boolean = true,
    val weatherScale: Float = 1f,
    val weatherOffsetX: Float = 0f,
    val weatherOffsetY: Float = 0f,
)
