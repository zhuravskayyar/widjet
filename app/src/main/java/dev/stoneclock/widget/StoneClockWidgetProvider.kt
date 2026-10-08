package dev.stoneclock.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import dev.stoneclock.MainActivity
import dev.stoneclock.R
import dev.stoneclock.clock.digitResource
import dev.stoneclock.settings.ClockSettings
import dev.stoneclock.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale

class StoneClockWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgets(context, appWidgetIds)
        scheduleNextMinute(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_MINUTE_TICK,
            ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> refreshAll(context)
        }
    }

    override fun onDisabled(context: Context) {
        cancelMinuteUpdates(context)
    }

    private fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, StoneClockWidgetProvider::class.java))
        if (ids.isEmpty()) {
            cancelMinuteUpdates(context)
            return
        }

        updateWidgets(context, ids)
        scheduleNextMinute(context)
    }

    private fun updateWidgets(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).settings.first()
                val display = clockDisplay(LocalTime.now(), settings)
                val views = RemoteViews(context.packageName, R.layout.widget_stone_clock).apply {
                    setImageViewResource(R.id.widget_hour_tens, digitResource(display[0]))
                    setImageViewResource(R.id.widget_hour_ones, digitResource(display[1]))
                    setImageViewResource(R.id.widget_colon, digitResource(display[2]))
                    setImageViewResource(R.id.widget_minute_tens, digitResource(display[3]))
                    setImageViewResource(R.id.widget_minute_ones, digitResource(display[4]))

                    val imageAlpha = (settings.opacity.coerceIn(0f, 1f) * 255).toInt()
                    listOf(
                        R.id.widget_hour_tens,
                        R.id.widget_hour_ones,
                        R.id.widget_colon,
                        R.id.widget_minute_tens,
                        R.id.widget_minute_ones,
                    ).forEach { setInt(it, "setImageAlpha", imageAlpha) }

                    val openApp = PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    setOnClickPendingIntent(R.id.widget_clock_root, openApp)
                }

                val manager = AppWidgetManager.getInstance(context)
                appWidgetIds.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun scheduleNextMinute(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, StoneClockWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = minuteTickPendingIntent(context)
        val now = System.currentTimeMillis()
        val nextMinute = (now / MILLIS_PER_MINUTE + 1) * MILLIS_PER_MINUTE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Best-effort fallback until the user enables precise widget updates in settings.
            alarmManager.set(AlarmManager.RTC, nextMinute, pendingIntent)
        } else {
            // RTC (not RTC_WAKEUP) keeps the widget current while the screen is in use
            // without waking the phone just to redraw a home-screen clock.
            alarmManager.setExact(AlarmManager.RTC, nextMinute, pendingIntent)
        }
    }

    private fun cancelMinuteUpdates(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(minuteTickPendingIntent(context))
    }

    private fun minuteTickPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_MINUTE_TICK,
        Intent(context, StoneClockWidgetProvider::class.java).setAction(ACTION_MINUTE_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val REQUEST_CODE_MINUTE_TICK = 2718
        private const val ACTION_MINUTE_TICK = "dev.stoneclock.action.WIDGET_MINUTE_TICK"
        private const val ACTION_REFRESH = "dev.stoneclock.action.WIDGET_REFRESH"

        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, StoneClockWidgetProvider::class.java).setAction(ACTION_REFRESH),
            )
        }
    }
}

private fun clockDisplay(time: LocalTime, settings: ClockSettings): String {
    val hour = if (settings.is24Hour) time.hour else (time.hour % 12).let { if (it == 0) 12 else it }
    return String.format(Locale.ROOT, "%02d:%02d", hour, time.minute)
}
