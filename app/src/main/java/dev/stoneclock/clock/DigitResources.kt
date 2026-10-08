package dev.stoneclock.clock

import androidx.annotation.DrawableRes
import dev.stoneclock.R

@DrawableRes
internal fun digitResource(character: Char): Int = when (character) {
    '0' -> R.drawable.digit_0
    '1' -> R.drawable.digit_1
    '2' -> R.drawable.digit_2
    '3' -> R.drawable.digit_3
    '4' -> R.drawable.digit_4
    '5' -> R.drawable.digit_5
    '6' -> R.drawable.digit_6
    '7' -> R.drawable.digit_7
    '8' -> R.drawable.digit_8
    '9' -> R.drawable.digit_9
    ':' -> R.drawable.digit_colon
    else -> error("Unsupported clock character: $character")
}
