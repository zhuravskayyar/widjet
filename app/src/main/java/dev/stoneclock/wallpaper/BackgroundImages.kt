package dev.stoneclock.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.UUID

/** The selected document is copied into private storage, so it also works after reboot. */
internal object BackgroundImages {
    private const val MAX_EDGE = 2048
    private var cachedName: String? = null
    private var cachedBitmap: Bitmap? = null

    fun importImage(context: Context, uri: Uri): String {
        val file = File.createTempFile("clock-import-", ".image", context.cacheDir)
        val result = File(context.filesDir, "clock-background-${UUID.randomUUID()}.png")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Не вдалося прочитати зображення")
            val bitmap = decode(file)
            try {
                result.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("Не вдалося зберегти фон")
                    }
                }
            } finally {
                bitmap.recycle()
            }
            return result.name
        } catch (error: Exception) {
            result.delete()
            throw error
        } finally {
            file.delete()
        }
    }

    @Synchronized
    fun load(context: Context, name: String?): Bitmap? {
        if (name == null) return null
        if (cachedName == name && cachedBitmap != null) return cachedBitmap
        val file = backgroundFile(context, name) ?: return null
        val bitmap = try {
            decode(file)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Engines and preview can share this immutable bitmap. Do not recycle it here.
        cachedName = name
        cachedBitmap = bitmap
        return bitmap
    }

    fun remove(context: Context, name: String?) {
        if (name != null) backgroundFile(context, name)?.delete()
    }

    private fun backgroundFile(context: Context, name: String): File? {
        if (!name.startsWith("clock-background-") || !name.endsWith(".png")) return null
        val file = File(context.filesDir, name)
        return file.takeIf { it.canonicalFile.parentFile == context.filesDir.canonicalFile }
    }

    private fun decode(file: File): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val factor = (MAX_EDGE.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                decoder.setTargetSize(
                    (info.size.width * factor).toInt().coerceAtLeast(1),
                    (info.size.height * factor).toInt().coerceAtLeast(1),
                )
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Непідтримуване зображення")
    }
}
