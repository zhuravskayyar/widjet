package dev.stoneclock.weather

import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun rememberWeatherAnimation(condition: WeatherCondition?, enabled: Boolean = true): WeatherAnimationFrame {
    val owner = LocalContext.current as? LifecycleOwner
    var visible by remember(owner) { mutableStateOf(owner == null || owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> visible = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val animation = remember { WeatherAnimation() }
    return produceState(WeatherAnimationFrame(), visible, enabled, condition) {
        if (!visible || !enabled || condition == null) return@produceState
        while (true) {
            withFrameNanos { value = animation.sample(SystemClock.uptimeMillis()) }
            val wait = animation.nextDelay(SystemClock.uptimeMillis(), condition)
            if (wait > 16L) delay(wait - 16L)
        }
    }.value
}

@Composable
internal fun WeatherCharacter(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val renderer = remember(context) { WeatherRenderer(context) }
    val ready = produceState(false, snapshot.condition) {
        value = false
        withContext(Dispatchers.IO) { renderer.prepare(snapshot.condition) }
        value = true
    }.value
    val animation = rememberWeatherAnimation(snapshot.condition, ready)
    Canvas(modifier.semantics { contentDescription = "${snapshot.location.name}, ${snapshot.condition.label}, ${snapshot.temperatureDisplay} Цельсія" }) {
        if (ready) drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val bounds = RectF(0f, 0f, size.width, size.height)
            renderer.drawStatic(canvas, bounds, snapshot, animated = true)
            renderer.drawAnimation(canvas, bounds, snapshot, animation)
        }
    }
}
