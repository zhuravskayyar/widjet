package dev.stoneclock.weather

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dev.stoneclock.MainActivity
import dev.stoneclock.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, refresh = true)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context, refresh = false)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CHANGED || intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            update(context, refresh = intent.action != ACTION_CHANGED)
        }
    }

    private fun update(context: Context, refresh: Boolean) {
        WeatherUpdateService.ensureScheduled(context)
        if (refresh) WeatherUpdateService.requestImmediate(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = WeatherRepository.get(context)
                val state = repository.state.value
                val snapshot = state.snapshot
                val views = RemoteViews(context.packageName, R.layout.widget_weather)
                val open = PendingIntent.getActivity(context, 7402,
                    Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_WEATHER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.weather_widget_root, open)
                if (snapshot != null) {
                    val renderer = WeatherRenderer(context)
                    renderer.prepare(snapshot.condition)
                    val image = Bitmap.createBitmap(720, 380, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    val bounds = RectF(12f, 8f, 708f, 372f)
                    renderer.drawStatic(canvas, bounds, snapshot)
                    renderer.drawPupils(canvas, bounds, snapshot.condition, Gaze())
                    views.setImageViewBitmap(R.id.weather_widget_image, image)
                    views.setContentDescription(R.id.weather_widget_image, "${snapshot.location.name}, ${snapshot.condition.label}, ${snapshot.temperatureDisplay} Цельсія")
                    views.setViewVisibility(R.id.weather_widget_image, View.VISIBLE)
                    views.setViewVisibility(R.id.weather_widget_empty, View.GONE)
                } else {
                    views.setViewVisibility(R.id.weather_widget_image, View.GONE)
                    views.setViewVisibility(R.id.weather_widget_empty, View.VISIBLE)
                    views.setTextViewText(R.id.weather_widget_empty,
                        if (state.location == null) "Натисни, щоб обрати місто" else if (state.error != null) "Погода недоступна · натисни для повтору" else "Завантажую погоду…")
                }
                val manager = AppWidgetManager.getInstance(context)
                manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java)).forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_OPEN_WEATHER = "open_weather"
        private const val ACTION_CHANGED = "dev.stoneclock.action.WEATHER_CHANGED"
        fun notifyChanged(context: Context) {
            context.sendBroadcast(Intent(context, WeatherWidgetProvider::class.java).setAction(ACTION_CHANGED))
        }
    }
}
