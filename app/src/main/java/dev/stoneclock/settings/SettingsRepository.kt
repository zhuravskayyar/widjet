package dev.stoneclock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.clockSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "clock_settings",
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.clockSettingsStore

    val settings: Flow<ClockSettings> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            ClockSettings(
                scale = preferences[Keys.scale] ?: 1f,
                offsetX = preferences[Keys.offsetX] ?: 0f,
                offsetY = preferences[Keys.offsetY] ?: 0f,
                digitSpacing = preferences[Keys.digitSpacing] ?: 0f,
                colonSpacing = preferences[Keys.colonSpacing] ?: 8f,
                opacity = preferences[Keys.opacity] ?: 1f,
                is24Hour = preferences[Keys.is24Hour] ?: true,
                backgroundImage = preferences[Keys.backgroundImage],
                backgroundColor = preferences[Keys.backgroundColor] ?: 0xFF090909.toInt(),
                weatherEnabled = preferences[Keys.weatherEnabled] ?: true,
                weatherScale = preferences[Keys.weatherScale] ?: 1f,
                weatherOffsetX = preferences[Keys.weatherOffsetX] ?: 0f,
                weatherOffsetY = preferences[Keys.weatherOffsetY] ?: 0f,
            )
        }

    suspend fun save(settings: ClockSettings) {
        store.edit { preferences ->
            preferences[Keys.scale] = settings.scale
            preferences[Keys.offsetX] = settings.offsetX
            preferences[Keys.offsetY] = settings.offsetY
            preferences[Keys.digitSpacing] = settings.digitSpacing
            preferences[Keys.colonSpacing] = settings.colonSpacing
            preferences[Keys.opacity] = settings.opacity
            preferences[Keys.is24Hour] = settings.is24Hour
            if (settings.backgroundImage == null) {
                preferences.remove(Keys.backgroundImage)
            } else {
                preferences[Keys.backgroundImage] = settings.backgroundImage
            }
            preferences[Keys.backgroundColor] = settings.backgroundColor
            preferences[Keys.weatherEnabled] = settings.weatherEnabled
            preferences[Keys.weatherScale] = settings.weatherScale
            preferences[Keys.weatherOffsetX] = settings.weatherOffsetX
            preferences[Keys.weatherOffsetY] = settings.weatherOffsetY
        }
    }

    private object Keys {
        val scale = floatPreferencesKey("scale")
        val offsetX = floatPreferencesKey("offset_x")
        val offsetY = floatPreferencesKey("offset_y")
        val digitSpacing = floatPreferencesKey("digit_spacing")
        val colonSpacing = floatPreferencesKey("colon_spacing")
        val opacity = floatPreferencesKey("opacity")
        val is24Hour = booleanPreferencesKey("is_24_hour")
        val backgroundImage = stringPreferencesKey("background_image")
        val backgroundColor = intPreferencesKey("background_color")
        val weatherEnabled = booleanPreferencesKey("weather_enabled")
        val weatherScale = floatPreferencesKey("weather_scale")
        val weatherOffsetX = floatPreferencesKey("weather_offset_x")
        val weatherOffsetY = floatPreferencesKey("weather_offset_y")
    }
}
