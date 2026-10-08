package dev.stoneclock.wallpaper

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.stoneclock.clock.WallpaperClockRenderer
import dev.stoneclock.clock.clockDisplay
import dev.stoneclock.clock.millisUntilNextMinute
import dev.stoneclock.settings.ClockSettings
import dev.stoneclock.weather.WeatherRepository
import dev.stoneclock.weather.WeatherRenderer
import dev.stoneclock.weather.rememberWeatherAnimation
import dev.stoneclock.weather.weatherWallpaperBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime

@Composable
internal fun WallpaperPreview(settings: ClockSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val renderer = remember(context) { WallpaperClockRenderer(context) }
    val weatherRenderer = remember(context) { WeatherRenderer(context) }
    val weatherState by WeatherRepository.get(context).state.collectAsState()
    val snapshot = weatherState.snapshot
    val weatherReady = produceState(false, snapshot?.condition) {
        value = false
        if (snapshot != null) {
            withContext(Dispatchers.IO) { weatherRenderer.prepare(snapshot.condition) }
            value = true
        }
    }.value
    val animation = rememberWeatherAnimation(snapshot?.condition, settings.weatherEnabled && weatherReady)
    val background = produceState<Bitmap?>(null, settings.backgroundImage) {
        value = withContext(Dispatchers.IO) { BackgroundImages.load(context, settings.backgroundImage) }
    }.value
    val time = produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(millisUntilNextMinute())
        }
    }.value
    Canvas(modifier.semantics { contentDescription = "Прев’ю ${clockDisplay(time, settings.is24Hour)}" }) {
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, size.width, size.height, settings, time, background)
            if (settings.weatherEnabled && weatherReady && snapshot != null) {
                val bounds = weatherWallpaperBounds(size.width, size.height, settings)
                weatherRenderer.drawStatic(canvas.nativeCanvas, bounds, snapshot, animated = true)
                weatherRenderer.drawAnimation(canvas.nativeCanvas, bounds, snapshot, animation)
            }
        }
    }
}
