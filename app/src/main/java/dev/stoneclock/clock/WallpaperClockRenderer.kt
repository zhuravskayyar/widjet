package dev.stoneclock.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.stoneclock.settings.ClockSettings
import java.time.LocalTime

/** Uses a 360-unit viewport so the miniature preview has the same layout as the wallpaper. */
internal class WallpaperClockRenderer(context: Context) {
    private val sprites = SpriteBitmaps.get(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        settings: ClockSettings,
        time: LocalTime,
        background: Bitmap?,
    ) {
        if (width <= 0f || height <= 0f) return
        canvas.drawColor(settings.backgroundColor)
        paint.alpha = 255
        if (background != null) {
            val zoom = maxOf(width / background.width, height / background.height)
            val imageWidth = background.width * zoom
            val imageHeight = background.height * zoom
            destination.set(
                (width - imageWidth) / 2f, (height - imageHeight) / 2f,
                (width + imageWidth) / 2f, (height + imageHeight) / 2f,
            )
            canvas.drawBitmap(background, null, destination, paint)
        }

        val display = clockDisplay(time, settings.is24Hour)
        val unit = width / 360f
        val requestedHeight = 104f * unit * settings.scale.coerceIn(0.25f, 2f)
        val gaps = display.indices.map { index ->
            if (index == 0) 0f else {
                val spacing = if (display[index] == ':' || display[index - 1] == ':') {
                    settings.colonSpacing
                } else settings.digitSpacing
                spacing.coerceAtLeast(0f) * unit * settings.scale
            }
        }
        val widths = display.map { character ->
            val bitmap = sprites.getValue(character)
            requestedHeight * bitmap.width / bitmap.height
        }
        val requestedWidth = widths.sum() + gaps.sum()
        val fit = minOf(1f, (width - 24f * unit) / requestedWidth)
        val clockWidth = requestedWidth * fit
        val clockHeight = requestedHeight * fit
        // Keep every digit visible even when position/scale is set near an edge.
        var left = (width / 2f - clockWidth / 2f + settings.offsetX * unit)
            .coerceIn(0f, (width - clockWidth).coerceAtLeast(0f))
        val top = (height * 0.27f + settings.offsetY * unit)
            .coerceIn(0f, (height - clockHeight).coerceAtLeast(0f))
        paint.alpha = (settings.opacity.coerceIn(0f, 1f) * 255).toInt()
        display.forEachIndexed { index, character ->
            left += gaps[index] * fit
            val digitWidth = widths[index] * fit
            destination.set(left, top, left + digitWidth, top + clockHeight)
            canvas.drawBitmap(sprites.getValue(character), null, destination, paint)
            left += digitWidth
        }
    }
}

private object SpriteBitmaps {
    private var cached: Map<Char, Bitmap>? = null

    @Synchronized
    fun get(context: Context): Map<Char, Bitmap> = cached ?: "0123456789:".associateWith { digit ->
        BitmapFactory.decodeResource(
            context.resources,
            digitResource(digit),
            BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 },
        ) ?: error("Missing sprite: $digit")
    }.also { cached = it }
}
