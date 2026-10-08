package dev.stoneclock

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.stoneclock.weather.WeatherScreen
import dev.stoneclock.weather.WeatherRepository
import dev.stoneclock.weather.WeatherUpdateService
import dev.stoneclock.weather.WeatherWidgetProvider
import dev.stoneclock.settings.ClockSettings
import dev.stoneclock.settings.SettingsRepository
import dev.stoneclock.settings.SettingsScreen
import dev.stoneclock.widget.StoneClockWidgetProvider
import dev.stoneclock.wallpaper.BackgroundImages
import dev.stoneclock.wallpaper.StoneClockWallpaperService
import dev.stoneclock.updates.AppUpdater
import dev.stoneclock.updates.AppUpdateScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val exactWidgetUpdatesAllowed = mutableStateOf(false)
    private val openWeather = mutableStateOf(false)
    private val openUpdates = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openWeather.value = intent.getBooleanExtra(WeatherWidgetProvider.EXTRA_OPEN_WEATHER, false)
        openUpdates.value = intent.getBooleanExtra(AppUpdater.EXTRA_OPEN_UPDATES, false)
        WeatherUpdateService.ensureScheduled(this)
        setContent {
            val context = LocalContext.current
            val repository = remember(context) { SettingsRepository(context) }
            val settings by repository.settings.collectAsState(initial = ClockSettings())
            val updates by AppUpdater.get(context).state.collectAsState()
            val scope = rememberCoroutineScope()
            val latestSettings by rememberUpdatedState(settings)
            var showingWeatherDemo by rememberSaveable { mutableStateOf(openWeather.value) }
            var showingUpdates by rememberSaveable { mutableStateOf(openUpdates.value) }
            androidx.compose.runtime.LaunchedEffect(openWeather.value) {
                if (openWeather.value) { showingWeatherDemo = true; openWeather.value = false }
            }
            androidx.compose.runtime.LaunchedEffect(openUpdates.value) {
                if (openUpdates.value) { showingUpdates = true; openUpdates.value = false }
            }
            var backgroundBusy by remember { mutableStateOf(false) }
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    backgroundBusy = true
                    try {
                        val image = withContext(Dispatchers.IO) { BackgroundImages.importImage(context, uri) }
                        val previous = latestSettings.backgroundImage
                        repository.save(latestSettings.copy(backgroundImage = image))
                        withContext(Dispatchers.IO) { BackgroundImages.remove(context, previous) }
                    } catch (error: Exception) {
                        android.widget.Toast.makeText(
                            context, "Не вдалося відкрити фон. Обери інше зображення.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } finally {
                        backgroundBusy = false
                    }
                }
            }
            val previewAspectRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.let { it.width().toFloat() / it.height() }
            } else {
                resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels }
            }

            if (showingUpdates) {
                AppUpdateScreen(onBack = { showingUpdates = false })
            } else if (showingWeatherDemo) {
                WeatherScreen(settings = settings, onBack = { showingWeatherDemo = false },
                    onSettingsChange = { updated -> scope.launch { repository.save(updated) } },
                    onAddWidget = { requestWeatherWidget() },
                    onSetWallpaper = { draft -> scope.launch { repository.save(draft); openWallpaperPreview() } })
            } else {
                SettingsScreen(
                    settings = settings,
                    previewAspectRatio = previewAspectRatio,
                    backgroundBusy = backgroundBusy,
                    exactWidgetUpdatesAllowed = exactWidgetUpdatesAllowed.value,
                    onSettingsChange = { updated ->
                        scope.launch {
                            repository.save(updated)
                            StoneClockWidgetProvider.requestRefresh(context)
                        }
                    },
                    onAddHomeWidget = { requestHomeWidget() },
                    onEnablePreciseWidgetUpdates = { requestPreciseWidgetUpdates() },
                    onChooseBackground = { imagePicker.launch(arrayOf("image/*")) },
                    onRemoveBackground = {
                        scope.launch {
                            val previous = latestSettings.backgroundImage
                            repository.save(latestSettings.copy(backgroundImage = null))
                            withContext(Dispatchers.IO) { BackgroundImages.remove(context, previous) }
                        }
                    },
                    onPreviewWallpaper = { draft ->
                        scope.launch {
                            repository.save(draft)
                            openWallpaperPreview()
                        }
                    },
                    onOpenWeatherDemo = { showingWeatherDemo = true },
                    onOpenUpdates = { showingUpdates = true },
                    availableUpdateVersion = updates.latest?.versionName?.takeIf { updates.available },
                )
            }
        }
    }

    private fun openWallpaperPreview() {
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, StoneClockWallpaperService::class.java),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: ActivityNotFoundException) {
                android.widget.Toast.makeText(
                    this, "Відкрий Живі шпалери у системному виборі фонового малюнка",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WeatherRepository.get(this).refreshInBackground()
        AppUpdater.get(this).checkForUpdates()
        exactWidgetUpdatesAllowed.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }
        if (exactWidgetUpdatesAllowed.value) {
            StoneClockWidgetProvider.requestRefresh(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openWeather.value = intent.getBooleanExtra(WeatherWidgetProvider.EXTRA_OPEN_WEATHER, false)
        openUpdates.value = intent.getBooleanExtra(AppUpdater.EXTRA_OPEN_UPDATES, false)
    }

    private fun requestWeatherWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, WeatherWidgetProvider::class.java), null, null)
        } else {
            android.widget.Toast.makeText(this, "Додай Stone Clock · Погода через головний екран → Віджети", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun requestHomeWidget() {
        val widgetManager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, StoneClockWidgetProvider::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && widgetManager.isRequestPinAppWidgetSupported) {
            widgetManager.requestPinAppWidget(provider, null, null)
        } else {
            android.widget.Toast.makeText(
                this,
                "Додай Stone Clock через головний екран → Віджети",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun requestPreciseWidgetUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName")),
            )
        }
    }
}
