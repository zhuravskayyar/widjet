package dev.stoneclock.weather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import org.json.JSONArray
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

internal data class WeatherEffectArt(val base: Bitmap, val layers: List<WeatherEffectLayer>)
internal data class WeatherEffectLayer(
    val bitmap: Bitmap, val bounds: RectF, val motion: String,
    val period: Long, val phase: Float, val startY: Float, val travel: Float,
)

/** Extracts only decorative details once. Source PNGs and the stationary face are preserved. */
internal class WeatherEffects {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val lightningTint = PorterDuffColorFilter(Color.rgb(255, 247, 206), PorterDuff.Mode.SRC_ATOP)

    fun prepare(original: Bitmap, sourceWidth: Int, sourceHeight: Int, specs: JSONArray): WeatherEffectArt {
        val sx = original.width.toFloat() / sourceWidth
        val sy = original.height.toFloat() / sourceHeight
        val originalPixels = IntArray(original.width * original.height)
        original.getPixels(originalPixels, 0, original.width, 0, 0, original.width, original.height)
        val basePixels = originalPixels.copyOf()
        val layers = (0 until specs.length()).map { index ->
            val spec = specs.getJSONObject(index)
            val bounds = spec.getJSONArray("bounds")
            val left = floor(bounds.getDouble(0) * sx).toInt().coerceIn(0, original.width - 1)
            val top = floor(bounds.getDouble(1) * sy).toInt().coerceIn(0, original.height - 1)
            val right = ceil(bounds.getDouble(2) * sx).toInt().coerceIn(left + 1, original.width)
            val bottom = ceil(bounds.getDouble(3) * sy).toInt().coerceIn(top + 1, original.height)
            val w = right - left; val h = bottom - top
            val pixels = IntArray(w * h) { i -> originalPixels[(top + i / w) * original.width + left + i % w] }
            val seed = spec.getJSONArray("seed")
            val seedX = (seed.getDouble(0) * sx).toInt() - left
            val seedY = (seed.getDouble(1) * sy).toInt() - top
            val blue = spec.optString("mask") == "blue"
            var selected = component(pixels, w, h, seedX, seedY, blue)
            if (blue) {
                // Saturated water separates a drop from the grey cloud even where their outlines touch.
                // Include its original black outline and enclosed white reflections afterwards.
                selected = grow(selected, w, h, 3)
                pixels.indices.forEach { i ->
                    val color = pixels[i]
                    selected[i] = selected[i] && Color.alpha(color) > 8 &&
                        (isWater(color) || maxOf(Color.red(color), Color.green(color), Color.blue(color)) < 80 ||
                            minOf(Color.red(color), Color.green(color), Color.blue(color)) > 180)
                }
                val exterior = outside(selected, w, h)
                pixels.indices.forEach { i -> if (!exterior[i] && Color.alpha(pixels[i]) > 8) selected[i] = true }
            }
            pixels.indices.forEach { i ->
                if (selected[i]) basePixels[(top + i / w) * original.width + left + i % w] = Color.TRANSPARENT
                else pixels[i] = Color.TRANSPARENT
            }
            val layer = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            layer.setHasAlpha(true)
            WeatherEffectLayer(layer, RectF(left / sx, top / sy, right / sx, bottom / sy),
                spec.getString("motion"), spec.getLong("period"), spec.optDouble("phase", 0.0).toFloat(),
                spec.optDouble("startY", top / sy.toDouble()).toFloat(), spec.optDouble("travel", 140.0).toFloat())
        }
        val base = Bitmap.createBitmap(basePixels, original.width, original.height, Bitmap.Config.ARGB_8888)
        base.setHasAlpha(true)
        return WeatherEffectArt(base, layers)
    }

    fun draw(canvas: Canvas, art: WeatherEffectArt, content: RectF, snapshot: WeatherSnapshot, now: Long) {
        art.layers.forEachIndexed { index, layer ->
            val speed = if (snapshot.condition == WeatherCondition.RAIN) when (snapshot.code) {
                65, 67, 82 -> 1.25f
                in 51..55 -> 0.85f
                else -> 1f
            } else 1f
            val period = (layer.period / speed).toLong().coerceAtLeast(1L)
            val phase = ((now % period).toFloat() / period + layer.phase) % 1f
            val wave = sin(phase * 6.2831855f)
            rect.set(layer.bounds)
            paint.alpha = 255
            paint.colorFilter = null
            canvas.save()
            when (layer.motion) {
                "rain" -> {
                    val endY = content.bottom - rect.height()
                    val y = layer.startY + phase * (endY - layer.startY).coerceAtLeast(0f)
                    rect.offset(wave * 5f, y - rect.top)
                    paint.alpha = (255f * fade(phase, 0.1f)).toInt()
                }
                "lightning" -> {
                    val elapsed = phase * period
                    val strength = maxOf(flash(elapsed, 0f, 180f), flash(elapsed, 245f, 140f) * 0.8f)
                    paint.alpha = (48f + strength * 207f).toInt()
                    canvas.drawBitmap(layer.bitmap, null, rect, paint)
                    paint.colorFilter = lightningTint
                    paint.alpha = (strength * 110f).toInt()
                }
                "snow" -> {
                    rect.offset(wave * 18f, (phase - 0.5f) * layer.travel)
                    paint.alpha = (255f * fade(phase, 0.14f)).toInt()
                    canvas.rotate(wave * 18f * if (index % 2 == 0) 1f else -1f, rect.centerX(), rect.centerY())
                }
                "wind" -> {
                    rect.offset((phase - 0.5f) * layer.travel, wave * 14f)
                    paint.alpha = (255f * fade(phase, 0.18f)).toInt()
                    canvas.rotate(wave * 8f, rect.centerX(), rect.centerY())
                }
                "gust" -> {
                    rect.offset(wave * 12f, wave * 4f)
                    paint.alpha = (220f + wave * 35f).toInt()
                    canvas.rotate(wave * 1.5f, rect.centerX(), rect.centerY())
                }
            }
            canvas.drawBitmap(layer.bitmap, null, rect, paint)
            canvas.restore()
        }
        paint.alpha = 255
        paint.colorFilter = null
    }

    private fun fade(phase: Float, edge: Float) = minOf(1f, phase / edge, (1f - phase) / edge).coerceIn(0f, 1f)

    private fun flash(time: Float, start: Float, duration: Float): Float {
        val progress = (time - start) / duration
        return when {
            progress !in 0f..1f -> 0f
            progress < 0.15f -> progress / 0.15f
            else -> (1f - progress) / 0.85f
        }
    }

    private fun isWater(color: Int) = Color.blue(color) - Color.red(color) > 28 && Color.green(color) - Color.red(color) > 10

    private fun component(pixels: IntArray, w: Int, h: Int, seedX: Int, seedY: Int, blue: Boolean): BooleanArray {
        require(seedX in 0 until w && seedY in 0 until h) { "Weather layer seed outside bounds" }
        val selected = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var read = 0; var write = 0
        fun add(i: Int) {
            val color = pixels[i]
            if (!selected[i] && Color.alpha(color) > 8 && (!blue || isWater(color))) {
                selected[i] = true; queue[write++] = i
            }
        }
        add(seedY * w + seedX)
        require(write > 0) { "Weather layer seed must be inside its decoration" }
        while (read < write) {
            val i = queue[read++]; val x = i % w; val y = i / w
            if (x > 0) add(i - 1)
            if (x < w - 1) add(i + 1)
            if (y > 0) add(i - w)
            if (y < h - 1) add(i + w)
        }
        return selected
    }

    private fun grow(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val expanded = mask.copyOf()
        mask.indices.forEach { i ->
            if (mask[i]) {
                val x = i % w; val y = i / w
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && dx * dx + dy * dy <= radius * radius) expanded[ny * w + nx] = true
                }
            }
        }
        return expanded
    }

    private fun outside(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val exterior = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var read = 0; var write = 0
        fun add(i: Int) { if (!mask[i] && !exterior[i]) { exterior[i] = true; queue[write++] = i } }
        for (x in 0 until w) { add(x); add((h - 1) * w + x) }
        for (y in 0 until h) { add(y * w); add(y * w + w - 1) }
        while (read < write) {
            val i = queue[read++]; val x = i % w; val y = i / w
            if (x > 0) add(i - 1)
            if (x < w - 1) add(i + 1)
            if (y > 0) add(i - w)
            if (y < h - 1) add(i + w)
        }
        return exterior
    }
}
