package dev.stoneclock.weather

internal data class WeatherAnimationFrame(val gaze: Gaze = Gaze(), val uptimeMillis: Long = 0L)

/** Shared timing for the settings previews and lock screen. Nothing runs while hidden. */
internal class WeatherAnimation {
    private val gaze = GazeAnimation()

    fun sample(now: Long) = WeatherAnimationFrame(gaze.sample(now), now)

    fun nextDelay(now: Long, condition: WeatherCondition): Long {
        val effectsDelay = when (condition) {
            WeatherCondition.RAIN, WeatherCondition.THUNDER, WeatherCondition.SNOW, WeatherCondition.WIND -> 33L
            else -> Long.MAX_VALUE
        }
        return minOf(gaze.nextDelay(now), effectsDelay)
    }
}
