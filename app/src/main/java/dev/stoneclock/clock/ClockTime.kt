package dev.stoneclock.clock

import java.time.LocalTime
import java.util.Locale

internal fun clockDisplay(time: LocalTime, is24Hour: Boolean): String {
    val hour = if (is24Hour) time.hour else (time.hour % 12).let { if (it == 0) 12 else it }
    return String.format(Locale.ROOT, "%02d:%02d", hour, time.minute)
}

internal fun millisUntilNextMinute(now: Long = System.currentTimeMillis()): Long =
    60_000L - Math.floorMod(now, 60_000L)
