package dev.stoneclock.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stoneclock.settings.SettingsActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val updater = remember(context) { AppUpdater.get(context) }
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    val install: () -> Unit = {
        scope.launch {
            installing = true
            try { context.startActivity(updater.installationIntent()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, "Не вдалося відкрити встановлення. Завантаж оновлення повторно.", Toast.LENGTH_LONG).show() }
            finally { installing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }
    val busy = state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING || installing
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFF090909)).windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicText("‹  Налаштування", Modifier.clickable(onClick = onBack).padding(vertical = 8.dp), TextStyle(color = Color(0xFF99948B), fontSize = 15.sp))
        BasicText("Оновлення застосунку", style = TextStyle(color = Color(0xFFE8E2D8), fontSize = 26.sp, fontWeight = FontWeight.Medium))
        BasicText("Встановлено ${state.currentName}", style = TextStyle(color = Color(0xFF99948B), fontSize = 15.sp))
        BasicText("Нові версії завантажуються з GitHub. USB для оновлень не потрібен.", style = TextStyle(color = Color(0xFF99948B), fontSize = 14.sp))
        if (state.available) BasicText("Доступна версія ${state.latest?.versionName}", style = TextStyle(color = Color(0xFFE8E2D8), fontSize = 18.sp))
        else if (state.checked) BasicText(if (state.latest == null) "Оновлення ще не опубліковані." else "Встановлена остання версія.", style = TextStyle(color = Color(0xFF99948B), fontSize = 14.sp))
        state.error?.let { BasicText(it, style = TextStyle(color = Color(0xFFE4A998), fontSize = 14.sp)) }
        SettingsActionButton(if (state.phase == UpdatePhase.CHECKING) "Перевіряю…" else "Перевірити оновлення", enabled = !busy) { updater.checkForUpdates(force = true) }
        if (state.available) {
            if (state.phase == UpdatePhase.DOWNLOADING) BasicText("Завантажую APK · ${state.progress}%", style = TextStyle(color = Color(0xFFE8E2D8), fontSize = 15.sp))
            if (state.phase == UpdatePhase.READY) SettingsActionButton(if (installing) "Відкриваю встановлення…" else "Встановити оновлення", enabled = !busy) {
                if (context.packageManager.canRequestPackageInstalls()) install()
                else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:${context.packageName}")))
            } else SettingsActionButton("Завантажити оновлення", enabled = !busy) { updater.downloadUpdate() }
        }
        BasicText("Першого разу Android попросить дозволити встановлення для Stone Clock. Після цього підтвердь оновлення у системному вікні.", style = TextStyle(color = Color(0xFF99948B), fontSize = 13.sp))
        SettingsActionButton("Відкрити проєкт на GitHub", enabled = !busy) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.REPOSITORY_URL))) }
    }
}
