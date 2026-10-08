package dev.stoneclock.weather

import android.net.Network
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object WeatherApi {
    suspend fun search(query: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        val url = Uri.parse("https://geocoding-api.open-meteo.com/v1/search").buildUpon()
            .appendQueryParameter("name", query.trim())
            .appendQueryParameter("count", "6")
            .appendQueryParameter("language", "uk")
            .appendQueryParameter("format", "json").build().toString()
        val results = request(url).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).map { index ->
            val item = results.getJSONObject(index)
            WeatherLocation(
                id = item.getLong("id").toString(), name = item.getString("name"),
                detail = listOf(item.optString("admin1"), item.optString("country"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", "),
                latitude = item.getDouble("latitude"), longitude = item.getDouble("longitude"),
            )
        }
    }

    suspend fun fetch(location: WeatherLocation, network: Network? = null): WeatherSnapshot = withContext(Dispatchers.IO) {
        val url = Uri.parse("https://api.open-meteo.com/v1/forecast").buildUpon()
            .appendQueryParameter("latitude", location.latitude.toString())
            .appendQueryParameter("longitude", location.longitude.toString())
            .appendQueryParameter("current", "temperature_2m,weather_code,is_day,wind_speed_10m")
            .appendQueryParameter("temperature_unit", "celsius")
            .appendQueryParameter("wind_speed_unit", "kmh")
            .appendQueryParameter("timeformat", "unixtime")
            .appendQueryParameter("timezone", "auto").build().toString()
        val current = request(url, network).getJSONObject("current")
        WeatherSnapshot(location, current.getDouble("temperature_2m"), current.getInt("weather_code"),
            current.getInt("is_day") == 1, current.getDouble("wind_speed_10m"),
            current.getLong("time") * 1_000L, System.currentTimeMillis())
    }

    private fun request(url: String, network: Network? = null): JSONObject {
        val connection = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }
}
