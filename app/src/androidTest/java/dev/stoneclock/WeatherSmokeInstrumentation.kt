package dev.stoneclock

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import dev.stoneclock.weather.Gaze
import dev.stoneclock.weather.GazeAnimation
import dev.stoneclock.weather.WeatherApi
import dev.stoneclock.weather.WeatherCondition
import dev.stoneclock.weather.WeatherLocation
import dev.stoneclock.weather.WeatherRenderer
import dev.stoneclock.weather.WeatherRepository
import dev.stoneclock.weather.WeatherSnapshot
import dev.stoneclock.weather.weatherCondition
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** Runs without extra test dependencies: actual API, persisted cache and pixel invariants. */
class WeatherSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            verifyMappings()
            verifyGaze()
            runBlocking {
                val found = WeatherApi.search("Mannheim")
                check(found.any { it.id == "2873891" }) { "Mannheim was not found" }
                val repository = WeatherRepository.get(targetContext)
                check(repository.state.value.location?.id == "2873891") { "Expected the configured Mannheim location" }
                check(repository.refresh(force = true)) { "Actual device weather request failed: ${repository.state.value.error}" }
                val snapshot = checkNotNull(repository.state.value.snapshot)
                check(snapshot.temperature.isFinite() && snapshot.code in 0..99)
                val saved = targetContext.getSharedPreferences("weather_cache", 0).getString("snapshot", null)
                check(JSONObject(checkNotNull(saved)).getString("location_id") == "2873891")
                result.putString("weather", "${snapshot.location.name}: ${snapshot.temperature} Celsius, WMO ${snapshot.code}")
            }
            verifyArtwork()
            result.putString("passed", "API search, device forecast request, persisted cache, WMO mapping, gaze continuity, all 8 artworks, fixed silhouette")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyMappings() {
        check(weatherCondition(95, true, 10.0) == WeatherCondition.THUNDER)
        check(weatherCondition(86, false, 50.0) == WeatherCondition.SNOW)
        check(weatherCondition(61, false, 50.0) == WeatherCondition.RAIN)
        check(weatherCondition(48, true, 0.0) == WeatherCondition.FOG)
        check(weatherCondition(0, true, 40.0) == WeatherCondition.WIND)
        check(weatherCondition(0, false, 0.0) == WeatherCondition.MOON)
        check(weatherCondition(1, true, 0.0) == WeatherCondition.SUN)
        check(weatherCondition(3, false, 0.0) == WeatherCondition.CLOUD)
    }

    private fun verifyGaze() {
        val animation = GazeAnimation(Random(7))
        var previous = Gaze()
        var moved = false
        var idle = false
        for (now in 0L..40_000L step 16) {
            val gaze = animation.sample(now)
            check(abs(gaze.x) <= 1 && abs(gaze.y) <= 1)
            check(abs(gaze.x - previous.x) < 0.09f && abs(gaze.y - previous.y) < 0.09f) { "Gaze jumped between frames" }
            moved = moved || abs(gaze.x) > 0.2f
            idle = idle || animation.nextDelay(now) > 1_000L
            previous = gaze
        }
        check(moved && idle)
    }

    private fun verifyArtwork() {
        val renderer = WeatherRenderer(targetContext)
        renderer.prepare(WeatherCondition.SUN)
        "0123456789+-°".forEach { symbol ->
            val image = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            renderer.drawTemperature(Canvas(image), symbol.toString(), 120f, 210f, 180f, 200f)
            val pixels = IntArray(240 * 240)
            image.getPixels(pixels, 0, 240, 0, 0, 240, 240)
            check(pixels.count { it ushr 24 > 0 } > 400) { "Temperature glyph $symbol is empty or clipped" }
            image.recycle()
        }
        val configs = targetContext.assets.open("weather/weather-characters.json").bufferedReader().use { JSONObject(it.readText()) }
        val output = File(targetContext.cacheDir, "weather-checks").apply { mkdirs() }
        val location = WeatherLocation("test", "Мангейм", "", 49.4891, 8.46694)
        val cases = listOf(Triple(0, true, 0.0), Triple(3, true, 0.0), Triple(61, true, 0.0),
            Triple(95, true, 0.0), Triple(71, true, 0.0), Triple(45, true, 0.0),
            Triple(0, true, 40.0), Triple(0, false, 0.0))
        val temperatures = listOf(0.0, -7.0, 14.0, 23.0, 56.0, 89.0, -4.0, 108.0)
        cases.forEachIndexed { index, (code, day, wind) ->
            val snapshot = WeatherSnapshot(location, temperatures[index], code, day, wind, 0, 0)
            renderer.prepare(snapshot.condition)
            val before = Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888)
            val bounds = RectF(0f, 0f, 640f, 760f)
            for ((bitmap, gaze) in listOf(before to Gaze(), after to Gaze(0.85f, -0.25f))) {
                Canvas(bitmap).let { canvas ->
                    renderer.drawStatic(canvas, bounds, snapshot)
                    renderer.drawPupils(canvas, bounds, snapshot.condition, gaze)
                }
            }
            val a = IntArray(640 * 800); val b = IntArray(a.size)
            before.getPixels(a, 0, 640, 0, 0, 640, 800); after.getPixels(b, 0, 640, 0, 0, 640, 800)
            val charBounds = renderer.characterBounds(bounds, snapshot.condition)
            val spec = configs.getJSONObject(snapshot.condition.asset)
            val scale = charBounds.width() / spec.getInt("width")
            val eyes = spec.getJSONArray("eyes")
            var changed = 0
            a.indices.forEach { pixel ->
                if (a[pixel] != b[pixel]) {
                    changed++
                    val x = (pixel % 640 - charBounds.left) / scale
                    val y = (pixel / 640 - charBounds.top) / scale
                    val withinEye = (0 until eyes.length()).any { i ->
                        val eye = eyes.getJSONObject(i)
                        abs(x - eye.getDouble("x")) <= eye.getDouble("rx") + eye.getDouble("moveX") + 6 &&
                            abs(y - eye.getDouble("y")) <= eye.getDouble("ry") + eye.getDouble("moveY") + 6
                    }
                    check(withinEye) { "${snapshot.condition}: non-pupil pixel moved at $x, $y" }
                }
            }
            check(changed > 0) { "${snapshot.condition}: pupils do not animate" }
            File(output, "${snapshot.condition.asset}.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "${snapshot.condition.asset}-moved.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
            before.recycle(); after.recycle()
        }
    }
}
