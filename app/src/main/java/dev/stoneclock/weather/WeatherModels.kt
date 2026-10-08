package dev.stoneclock.weather

import kotlin.math.roundToInt

internal enum class WeatherCondition(val asset: String, val label: String) {
    SUN("sun", "Сонячно"), CLOUD("cloud", "Хмарно"), RAIN("rain", "Дощ"),
    THUNDER("thunder", "Гроза"), SNOW("snow", "Сніг"), FOG("fog", "Туман"),
    WIND("wind", "Сильний вітер"), MOON("moon", "Ясна ніч"),
}

internal fun weatherCondition(code: Int, isDay: Boolean, windKmh: Double): WeatherCondition = when {
    code in 95..99 -> WeatherCondition.THUNDER
    code in 71..77 || code in 85..86 -> WeatherCondition.SNOW
    code in 51..67 || code in 80..82 -> WeatherCondition.RAIN
    code == 45 || code == 48 -> WeatherCondition.FOG
    code in 0..3 && windKmh >= 35.0 -> WeatherCondition.WIND
    code in 0..1 && !isDay -> WeatherCondition.MOON
    code == 0 || code == 1 -> WeatherCondition.SUN
    else -> WeatherCondition.CLOUD
}

internal data class WeatherLocation(
    val id: String,
    val name: String,
    val detail: String,
    val latitude: Double,
    val longitude: Double,
) {
    val displayName: String get() = if (detail.isBlank()) name else "$name, $detail"
}

internal data class WeatherSnapshot(
    val location: WeatherLocation,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val windKmh: Double,
    val observedAt: Long,
    val fetchedAt: Long,
) {
    val condition: WeatherCondition get() = weatherCondition(code, isDay, windKmh)
    val temperatureDisplay: String get() = temperature.roundToInt().let { if (it > 0) "+$it°" else "$it°" }
}

internal data class WeatherState(
    val location: WeatherLocation? = null,
    val snapshot: WeatherSnapshot? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
)
