package dev.stoneclock.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import dev.stoneclock.settings.ClockSettings
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

internal data class EyeSpec(val x: Float, val y: Float, val rx: Float, val ry: Float,
    val moveX: Float, val moveY: Float, val clipTop: Float, val clipSlope: Float)
internal data class CharacterArt(val bitmap: Bitmap, val eyes: List<EyeSpec>, val sourceWidth: Int, val sourceHeight: Int,
    val contentBounds: RectF, val effects: WeatherEffectArt?)
private data class TemperatureGlyph(val bitmap: Bitmap, val baseline: Float)

/** Prepares artwork once, then shares identical drawing code across all three surfaces. */
internal class WeatherRenderer(context: Context) {
    private val assets = context.applicationContext.assets
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val effects = WeatherEffects()
    private val specs = assets.open("weather/weather-characters.json").bufferedReader().use { JSONObject(it.readText()) }
    private val characters = ConcurrentHashMap<WeatherCondition, CharacterArt>()
    private var glyphs: Map<Char, TemperatureGlyph>? = null

    /** Call on an IO dispatcher before drawing a condition. */
    @Synchronized fun prepare(condition: WeatherCondition) {
        if (characters.containsKey(condition)) return
        val config = specs.getJSONObject(condition.asset)
        val sourceWidth = config.getInt("width")
        val sourceHeight = config.getInt("height")
        val bitmap = assets.open(config.getString("file")).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false })!!
        }.copy(Bitmap.Config.ARGB_8888, true)
        // RGB sources retain the opaque flag after copy; Surface Canvas needs this before clearing pixels.
        bitmap.setHasAlpha(true)
        if (config.optBoolean("removeBlackBackground")) clearExteriorBlack(bitmap)
        val array = config.getJSONArray("eyes")
        val eyes = (0 until array.length()).map { i ->
            val e = array.getJSONObject(i)
            EyeSpec(e.getDouble("x").toFloat(), e.getDouble("y").toFloat(), e.getDouble("rx").toFloat(),
                e.getDouble("ry").toFloat(), e.getDouble("moveX").toFloat(), e.getDouble("moveY").toFloat(),
                e.optDouble("clipTop", 0.0).toFloat(), e.optDouble("clipSlope", 0.0).toFloat())
        }
        // Only the originally painted pupils are covered; the eye whites and outlines keep their source texture.
        eyes.forEach { clearPupil(bitmap, it, bitmap.width.toFloat() / sourceWidth, bitmap.height.toFloat() / sourceHeight) }
        val content = contentBounds(bitmap, sourceWidth, sourceHeight)
        val effectArt = config.optJSONArray("effects")?.let { effects.prepare(bitmap, sourceWidth, sourceHeight, it) }
        characters[condition] = CharacterArt(bitmap, eyes, sourceWidth, sourceHeight, content, effectArt)
        prepareGlyphs()
    }

    fun characterBounds(bounds: RectF, condition: WeatherCondition): RectF {
        val art = characters[condition] ?: return RectF()
        val content = art.contentBounds
        val gap = bounds.width() * 0.018f
        val temperatureWidth = bounds.width() * 0.37f
        val fit = minOf((bounds.width() - temperatureWidth - gap) / content.width(), bounds.height() / content.height())
        val left = bounds.centerX() - (content.width() * fit + gap + temperatureWidth) / 2f - content.left * fit
        val top = bounds.centerY() - content.height() * fit / 2f - content.top * fit
        return RectF(left, top, left + art.sourceWidth * fit, top + art.sourceHeight * fit)
    }

    fun drawStatic(canvas: Canvas, bounds: RectF, snapshot: WeatherSnapshot, animated: Boolean = false) {
        val art = characters[snapshot.condition] ?: return
        val character = characterBounds(bounds, snapshot.condition)
        canvas.drawBitmap(if (animated) art.effects?.base ?: art.bitmap else art.bitmap, null, character, paint)
        val sprites = glyphs ?: return
        val display = snapshot.temperatureDisplay
        val artScale = character.width() / art.sourceWidth
        val height = art.contentBounds.height() * artScale * 0.34f
        val maxWidth = bounds.width() * 0.37f
        val rawWidth = display.sumOf { sprites.getValue(it).bitmap.width.toDouble() }.toFloat() + (display.length - 1) * 6f
        val scale = minOf(height / 350f, maxWidth / rawWidth)
        val top = display.minOf { -sprites.getValue(it).baseline } * scale
        val bottom = display.maxOf { sprites.getValue(it).bitmap.height - sprites.getValue(it).baseline } * scale
        val left = character.left + art.contentBounds.right * artScale + bounds.width() * 0.018f
        drawTemperature(canvas, display, left + rawWidth * scale / 2f,
            bounds.centerY() - (top + bottom) / 2f, height, maxWidth)
    }

    fun drawAnimation(canvas: Canvas, bounds: RectF, snapshot: WeatherSnapshot, frame: WeatherAnimationFrame) {
        val art = characters[snapshot.condition] ?: return
        art.effects?.let { effectArt ->
            val character = characterBounds(bounds, snapshot.condition)
            canvas.save()
            canvas.translate(character.left, character.top)
            val scale = character.width() / art.sourceWidth
            canvas.scale(scale, scale)
            // The temperature and face remain stable; moving details stay inside the original silhouette bounds.
            canvas.clipRect(art.contentBounds)
            effects.draw(canvas, effectArt, art.contentBounds, snapshot, frame.uptimeMillis)
            canvas.restore()
        }
        drawPupils(canvas, bounds, snapshot.condition, frame.gaze)
    }

    fun drawPupils(canvas: Canvas, bounds: RectF, condition: WeatherCondition, gaze: Gaze) {
        val art = characters[condition] ?: return
        val char = characterBounds(bounds, condition)
        val scale = char.width() / art.sourceWidth
        canvas.save()
        canvas.translate(char.left, char.top)
        canvas.scale(scale, scale)
        art.eyes.forEach { eye ->
            canvas.save()
            if (eye.clipTop > 0f) {
                clip.reset()
                val left = eye.x - eye.rx - eye.moveX - 5f
                val right = eye.x + eye.rx + eye.moveX + 5f
                clip.moveTo(left, eye.clipTop + eye.clipSlope * (left - eye.x))
                clip.lineTo(right, eye.clipTop + eye.clipSlope * (right - eye.x))
                clip.lineTo(right, eye.y + eye.ry + eye.moveY + 5f)
                clip.lineTo(left, eye.y + eye.ry + eye.moveY + 5f)
                clip.close(); canvas.clipPath(clip)
            }
            val x = eye.x + gaze.x * eye.moveX
            val y = eye.y + gaze.y * eye.moveY
            paint.color = Color.rgb(4, 4, 4)
            rect.set(x - eye.rx, y - eye.ry, x + eye.rx, y + eye.ry)
            canvas.drawOval(rect, paint)
            paint.color = Color.WHITE
            canvas.drawOval(x - eye.rx * 0.45f, y - eye.ry * 0.5f, x - eye.rx * 0.08f, y - eye.ry * 0.14f, paint)
            canvas.restore()
        }
        canvas.restore()
    }

    internal fun drawTemperature(canvas: Canvas, display: String, centerX: Float, baseline: Float, height: Float, maxWidth: Float) {
        val sprites = glyphs ?: return
        val rawWidth = display.sumOf { sprites.getValue(it).bitmap.width.toDouble() }.toFloat() + (display.length - 1) * 6f
        val scale = minOf(height / 350f, maxWidth / rawWidth)
        var left = centerX - rawWidth * scale / 2f
        display.forEach { symbol ->
            val glyph = sprites.getValue(symbol)
            val top = baseline - glyph.baseline * scale
            rect.set(left, top, left + glyph.bitmap.width * scale, top + glyph.bitmap.height * scale)
            canvas.drawBitmap(glyph.bitmap, null, rect, paint)
            left += (glyph.bitmap.width + 6f) * scale
        }
    }

    private fun prepareGlyphs() {
        if (glyphs != null) return
        val sheet = assets.open("weather/temperature.png").use { BitmapFactory.decodeStream(it)!! }
        val config = assets.open("weather/temperature-glyphs.json").bufferedReader().use { JSONObject(it.readText()) }
        glyphs = "0123456789+-°".associateWith { symbol ->
            val spec = config.getJSONObject(symbol.toString())
            val b = spec.getJSONArray("bounds")
            val bitmap = Bitmap.createBitmap(sheet, b.getInt(0), b.getInt(1), b.getInt(2) - b.getInt(0), b.getInt(3) - b.getInt(1)).copy(Bitmap.Config.ARGB_8888, true)
            bitmap.setHasAlpha(true)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.indices.forEach { i ->
                val color = pixels[i]
                val bright = maxOf(Color.red(color), Color.green(color), Color.blue(color))
                val alpha = ((bright - 8) * 255 / 12).coerceIn(0, 255)
                pixels[i] = (color and 0x00ffffff) or (alpha shl 24)
            }
            removeBorderFragments(pixels, bitmap.width, bitmap.height)
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            TemperatureGlyph(bitmap, spec.getDouble("baseline").toFloat())
        }
        sheet.recycle()
    }

    private fun clearPupil(bitmap: Bitmap, eye: EyeSpec, sx: Float, sy: Float) {
        val x = eye.x * sx; val y = eye.y * sy
        val rx = (eye.rx + 2f) * sx; val ry = (eye.ry + 2f) * sy
        for (py in (y - ry).toInt().coerceAtLeast(0)..(y + ry).toInt().coerceAtMost(bitmap.height - 1)) {
            for (px in (x - rx).toInt().coerceAtLeast(0)..(x + rx).toInt().coerceAtMost(bitmap.width - 1)) {
                if (eye.clipTop > 0 && py / sy < eye.clipTop + eye.clipSlope * (px / sx - eye.x)) continue
                if ((px - x) * (px - x) / (rx * rx) + (py - y) * (py - y) / (ry * ry) > 1f) continue
                val original = bitmap.getPixel(px, py)
                if (minOf(Color.red(original), Color.green(original), Color.blue(original)) > 180) continue
                var replacement = Color.WHITE
                var found = false
                for (distance in 1..(rx * 3f).roundToInt()) {
                    for (direction in intArrayOf(-1, 1)) {
                        val nx = px + distance * direction
                        if (nx !in 0 until bitmap.width) continue
                        val candidate = bitmap.getPixel(nx, py)
                        if (minOf(Color.red(candidate), Color.green(candidate), Color.blue(candidate)) > 215) {
                            replacement = candidate; found = true; break
                        }
                    }
                    if (found) break
                }
                bitmap.setPixel(px, py, replacement)
            }
        }
    }

    private fun clearExteriorBlack(bitmap: Bitmap) {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        floodBorder(pixels, width, height) { color -> maxOf(Color.red(color), Color.green(color), Color.blue(color)) < 26 }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** Ignore transparent source margins when placing the temperature beside the silhouette. */
    private fun contentBounds(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int): RectF {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width; var right = 0; var top = height; var bottom = 0
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) > 8) {
                val x = index % width; val y = index / width
                left = minOf(left, x); right = maxOf(right, x + 1)
                top = minOf(top, y); bottom = maxOf(bottom, y + 1)
            }
        }
        if (left >= right || top >= bottom) return RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
        val sx = sourceWidth.toFloat() / width; val sy = sourceHeight.toFloat() / height
        return RectF(left * sx, top * sy, right * sx, bottom * sy)
    }

    private fun removeBorderFragments(pixels: IntArray, width: Int, height: Int) =
        floodBorder(pixels, width, height) { Color.alpha(it) > 0 }

    private fun floodBorder(pixels: IntArray, width: Int, height: Int, eligible: (Int) -> Boolean) {
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var read = 0; var write = 0
        fun add(index: Int) {
            if (!visited[index] && eligible(pixels[index])) { visited[index] = true; queue[write++] = index }
        }
        for (x in 0 until width) { add(x); add((height - 1) * width + x) }
        for (y in 0 until height) { add(y * width); add(y * width + width - 1) }
        while (read < write) {
            val index = queue[read++]; pixels[index] = Color.TRANSPARENT
            val x = index % width; val y = index / width
            if (x > 0) add(index - 1)
            if (x < width - 1) add(index + 1)
            if (y > 0) add(index - width)
            if (y < height - 1) add(index + width)
        }
    }
}

internal fun weatherWallpaperBounds(width: Float, height: Float, settings: ClockSettings): RectF {
    val unit = width / 360f
    val scale = settings.weatherScale.coerceIn(0.4f, 1.5f)
    val fit = minOf(1f, (width - unit * 16f) / (336f * unit * scale), height * 0.55f / (176f * unit * scale))
    val w = 336f * unit * scale * fit
    val h = 176f * unit * scale * fit
    val x = ((width - w) / 2f + settings.weatherOffsetX * unit).coerceIn(0f, (width - w).coerceAtLeast(0f))
    val y = (height * 0.46f + settings.weatherOffsetY * unit).coerceIn(0f, (height - h - 16f * unit).coerceAtLeast(0f))
    return RectF(x, y, x + w, y + h)
}
