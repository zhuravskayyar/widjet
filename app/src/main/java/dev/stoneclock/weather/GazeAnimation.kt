package dev.stoneclock.weather

import kotlin.random.Random

internal data class Gaze(val x: Float = 0f, val y: Float = 0f)

/** A monotonic clock drives both Compose previews and the live wallpaper. */
internal class GazeAnimation(private val random: Random = Random.Default) {
    private var phase = -1
    private var start = 0L
    private var end = 0L
    private var from = Gaze()
    private var to = Gaze()

    fun sample(now: Long): Gaze {
        if (phase < 0 || now - end > 60_000L) { phase = -1; to = Gaze(); advance(now) }
        if (now >= end) advance(now)
        if (from == to) return to
        val t = ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        val eased = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        return Gaze(from.x + (to.x - from.x) * eased, from.y + (to.y - from.y) * eased)
    }

    fun nextDelay(now: Long): Long = if (from != to) 16L else (end - now).coerceAtLeast(1L)

    private fun advance(now: Long) {
        phase = (phase + 1) % 6
        from = to
        val duration = when (phase) {
            0 -> { to = Gaze(); random.nextLong(2_000, 4_500) }
            1, 3 -> { to = Gaze(random.nextFloat() * 1.8f - 0.9f, random.nextFloat() * 0.6f - 0.3f); random.nextLong(800, 1_400) }
            2, 4 -> random.nextLong(1_700, 3_500)
            else -> { to = Gaze(); random.nextLong(850, 1_200) }
        }
        start = now
        end = now + duration
    }
}
