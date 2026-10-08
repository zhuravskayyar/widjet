package dev.stoneclock.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import dev.stoneclock.clock.WallpaperClockRenderer
import dev.stoneclock.clock.millisUntilNextMinute
import dev.stoneclock.settings.ClockSettings
import dev.stoneclock.settings.SettingsRepository
import dev.stoneclock.weather.GazeAnimation
import dev.stoneclock.weather.WeatherRenderer
import dev.stoneclock.weather.WeatherRepository
import dev.stoneclock.weather.WeatherSnapshot
import dev.stoneclock.weather.WeatherUpdateService
import dev.stoneclock.weather.weatherWallpaperBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

class StoneClockWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ClockEngine()

    private inner class ClockEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val choreographer = Choreographer.getInstance()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val clockRenderer = WallpaperClockRenderer(this@StoneClockWallpaperService)
        private val weatherRenderer = WeatherRenderer(this@StoneClockWallpaperService)
        private val weatherRepository = WeatherRepository.get(this@StoneClockWallpaperService)
        private val gaze = GazeAnimation()
        private var settings = ClockSettings()
        private var settingsLoaded = false
        private var weather: WeatherSnapshot? = null
        private var background: Bitmap? = null
        private var backgroundName: String? = null
        private var visible = false
        private var surfaceReady = false
        private var framePending = false
        private var scene: Bitmap? = null
        private var sceneDirty = true
        private val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        private val minuteTick = Runnable { invalidateScene("minute") }
        private val animationWake = Runnable { requestFrame() }
        private val animationFrame = Choreographer.FrameCallback {
            framePending = false
            if (canDraw()) { drawFrame(); scheduleAnimation() }
        }
        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { invalidateScene("time-change") }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(timeReceiver, filter)
            }
            WeatherUpdateService.ensureScheduled(this@StoneClockWallpaperService)
            scope.launch {
                combine(
                    SettingsRepository(this@StoneClockWallpaperService).settings,
                    weatherRepository.state.map { it.snapshot }.distinctUntilChanged(),
                ) { updated, snapshot -> updated to snapshot }.collectLatest { (updated, snapshot) ->
                    val image = if (backgroundName != updated.backgroundImage) {
                        withContext(Dispatchers.IO) { BackgroundImages.load(this@StoneClockWallpaperService, updated.backgroundImage) }
                    } else background
                    if (snapshot != null) withContext(Dispatchers.IO) { weatherRenderer.prepare(snapshot.condition) }
                    settings = updated
                    background = image
                    backgroundName = updated.backgroundImage
                    weather = snapshot
                    settingsLoaded = true
                    invalidateScene("settings-weather")
                }
            }
            debugLog("created preview=$isPreview")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            debugLog("visible=$visible")
            if (visible) {
                weatherRepository.refreshInBackground()
                invalidateScene("visible")
            } else stopDrawing()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            invalidateScene("surface-created")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            invalidateScene("surface")
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            invalidateScene("redraw")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            stopDrawing()
            scene?.recycle()
            scene = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopDrawing()
            scope.cancel()
            scene?.recycle()
            scene = null
            unregisterReceiver(timeReceiver)
            debugLog("destroyed")
            super.onDestroy()
        }

        private fun canDraw() = visible && surfaceReady && settingsLoaded

        private fun invalidateScene(reason: String) {
            sceneDirty = true
            handler.removeCallbacks(minuteTick)
            if (!canDraw()) return
            drawFrame()
            scheduleAnimation()
            handler.postDelayed(minuteTick, millisUntilNextMinute())
            debugLog("draw reason=$reason condition=" + weather?.condition)
        }

        private fun drawFrame() {
            if (!canDraw()) return
            val holder = surfaceHolder
            val canvas = try { holder.lockCanvas() }
            catch (_: IllegalStateException) { null }
            catch (_: IllegalArgumentException) { null }
            if (canvas != null) {
                try {
                    var cached = scene
                    if (cached == null || cached.width != canvas.width || cached.height != canvas.height) {
                        cached?.recycle()
                        cached = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
                        scene = cached
                        sceneDirty = true
                    }
                    val width = canvas.width.toFloat()
                    val height = canvas.height.toFloat()
                    val snapshot = weather
                    if (sceneDirty) {
                        val stableCanvas = Canvas(cached)
                        clockRenderer.draw(stableCanvas, width, height, settings, LocalTime.now(), background)
                        if (settings.weatherEnabled && snapshot != null) {
                            weatherRenderer.drawStatic(stableCanvas, weatherWallpaperBounds(width, height, settings), snapshot)
                        }
                        sceneDirty = false
                    }
                    canvas.drawBitmap(cached, 0f, 0f, null)
                    if (settings.weatherEnabled && snapshot != null) {
                        weatherRenderer.drawPupils(canvas, weatherWallpaperBounds(width, height, settings),
                            snapshot.condition, gaze.sample(SystemClock.uptimeMillis()))
                    }
                } finally { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun scheduleAnimation() {
            handler.removeCallbacks(animationWake)
            if (!canDraw() || !settings.weatherEnabled || weather == null) {
                choreographer.removeFrameCallback(animationFrame)
                framePending = false
                return
            }
            val wait = gaze.nextDelay(SystemClock.uptimeMillis())
            if (wait <= 16L) requestFrame() else handler.postDelayed(animationWake, wait)
        }

        private fun requestFrame() {
            if (!canDraw() || framePending) return
            framePending = true
            choreographer.postFrameCallback(animationFrame)
        }

        private fun stopDrawing() {
            handler.removeCallbacks(minuteTick)
            handler.removeCallbacks(animationWake)
            choreographer.removeFrameCallback(animationFrame)
            framePending = false
        }

        private fun debugLog(message: String) {
            if (debug) Log.d("StoneClockWallpaper", message)
        }
    }
}

