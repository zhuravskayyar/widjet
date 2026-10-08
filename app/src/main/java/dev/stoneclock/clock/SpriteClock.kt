package dev.stoneclock.clock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stoneclock.settings.ClockSettings
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalTime

@Composable
fun SpriteClock(
    settings: ClockSettings,
    modifier: Modifier = Modifier,
    digitHeight: Dp = 112.dp,
    fixedTime: LocalTime? = null,
) {
    val time = produceState(initialValue = fixedTime ?: LocalTime.now(), fixedTime) {
        if (fixedTime != null) {
            value = fixedTime
        } else {
            while (true) {
                val now = LocalTime.now()
                value = now
                val nextMinute = now.withSecond(0).withNano(0).plusMinutes(1)
                delay(Duration.between(now, nextMinute).toMillis().coerceAtLeast(1L))
            }
        }
    }.value

    val display = clockDisplay(time, settings.is24Hour)
    val drawingModifier = modifier
        .offset(x = settings.offsetX.dp, y = settings.offsetY.dp)
        .graphicsLayer {
            scaleX = settings.scale.coerceIn(0.1f, 3f)
            scaleY = settings.scale.coerceIn(0.1f, 3f)
        }
        .semantics { contentDescription = display }

    Row(
        modifier = drawingModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        display.forEachIndexed { index, character ->
            if (index > 0) {
                val previous = display[index - 1]
                val spacing = if (character == ':' || previous == ':') {
                    settings.colonSpacing
                } else {
                    settings.digitSpacing
                }
                Spacer(Modifier.width(spacing.coerceAtLeast(0f).dp))
            }
            Image(
                painter = painterResource(digitResource(character)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(digitHeight)
                    .alpha(settings.opacity.coerceIn(0f, 1f)),
            )
        }
    }
}
