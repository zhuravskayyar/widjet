package dev.stoneclock.weather

import android.content.Context
import android.annotation.SuppressLint
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One persisted weather cache shared by the app, home widget and wallpaper engines. */
internal class WeatherRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("weather_cache", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastAttempt = 0L
    private val mutableState = MutableStateFlow(readCache())
    val state: StateFlow<WeatherState> = mutableState
    init {
        if (!preferences.contains("location")) mutableState.value.location?.let {
            preferences.edit().putString("location", encodeLocation(it).toString()).apply()
        }
    }

    suspend fun selectLocation(location: WeatherLocation) {
        mutableState.value = WeatherState(location = location)
        preferences.edit().putString("location", encodeLocation(location).toString()).remove("snapshot").commit()
        lastAttempt = 0L
        WeatherUpdateService.ensureScheduled(app)
        WeatherWidgetProvider.notifyChanged(app)
        refresh(force = true)
    }

    fun refreshInBackground() { scope.launch { refresh() } }

    suspend fun refresh(force: Boolean = false, network: Network? = null): Boolean = mutex.withLock {
        val location = mutableState.value.location ?: return@withLock true
        val now = System.currentTimeMillis()
        val cached = mutableState.value.snapshot
        if (!force && cached != null && now - cached.fetchedAt in 0 until REFRESH_INTERVAL) return@withLock true
        if (!force && now - lastAttempt in 0 until RETRY_INTERVAL) return@withLock false
        lastAttempt = now
        mutableState.update { it.copy(refreshing = true, error = null) }
        try {
            val snapshot = WeatherApi.fetch(location, network)
            // A city changed while the previous request was in flight: never publish that old city's result.
            if (mutableState.value.location?.id != location.id) return@withLock true
            preferences.edit().putString("snapshot", JSONObject().apply {
                put("location_id", location.id); put("temperature", snapshot.temperature)
                put("code", snapshot.code); put("is_day", snapshot.isDay); put("wind", snapshot.windKmh)
                put("observed_at", snapshot.observedAt); put("fetched_at", snapshot.fetchedAt)
            }.toString()).commit()
            mutableState.value = WeatherState(location, snapshot)
            WeatherWidgetProvider.notifyChanged(app)
            true
        } catch (cancelled: CancellationException) {
            if (mutableState.value.location?.id == location.id) mutableState.update { it.copy(refreshing = false) }
            throw cancelled
        } catch (_: Exception) {
            if (mutableState.value.location?.id == location.id) mutableState.update {
                it.copy(refreshing = false, error = "Не вдалося оновити погоду. Перевір з’єднання й спробуй ще раз.")
            }
            WeatherWidgetProvider.notifyChanged(app)
            false
        }
    }

    private fun readCache(): WeatherState = try {
        val json = preferences.getString("location", null)?.let(::JSONObject)
            ?: app.assets.open("weather/default-location.json").bufferedReader().use { JSONObject(it.readText()) }
        val location = json?.let { WeatherLocation(it.getString("id"), it.getString("name"),
            it.getString("detail"), it.getDouble("lat"), it.getDouble("lon")) }
        val saved = preferences.getString("snapshot", null)?.let(::JSONObject)
        val snapshot = if (location != null && saved?.optString("location_id") == location.id) {
            WeatherSnapshot(location, saved.getDouble("temperature"), saved.getInt("code"),
                saved.getBoolean("is_day"), saved.getDouble("wind"), saved.getLong("observed_at"), saved.getLong("fetched_at"))
        } else null
        WeatherState(location, snapshot)
    } catch (_: Exception) { WeatherState() }

    private fun encodeLocation(location: WeatherLocation) = JSONObject().apply {
        put("id", location.id); put("name", location.name); put("detail", location.detail)
        put("lat", location.latitude); put("lon", location.longitude)
    }

    companion object {
        const val REFRESH_INTERVAL = 20 * 60 * 1_000L
        private const val RETRY_INTERVAL = 5 * 60 * 1_000L
        // The singleton retains only applicationContext; no activity or view is kept here.
        @SuppressLint("StaticFieldLeak") @Volatile private var instance: WeatherRepository? = null
        fun get(context: Context): WeatherRepository = instance ?: synchronized(this) {
            instance ?: WeatherRepository(context).also { instance = it }
        }
    }
}
