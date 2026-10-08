package dev.stoneclock.weather

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stoneclock.settings.ClockSettings
import dev.stoneclock.settings.SettingSlider
import dev.stoneclock.settings.SettingsActionButton
import dev.stoneclock.settings.ToggleMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Primary = Color(0xFFE8E2D8)
private val Secondary = Color(0xFF99948B)

@Composable
internal fun WeatherScreen(settings: ClockSettings, onBack: () -> Unit,
    onSettingsChange: (ClockSettings) -> Unit, onAddWidget: () -> Unit, onSetWallpaper: (ClockSettings) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { WeatherRepository.get(context) }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember(settings) { mutableStateOf(settings) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<WeatherLocation>()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(repository) { WeatherUpdateService.ensureScheduled(context); repository.refresh() }

    Column(Modifier.fillMaxSize().background(Color(0xFF090909)).windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BasicText("‹  Налаштування", Modifier.clickable(onClick = onBack).padding(vertical = 8.dp), TextStyle(color = Secondary, fontSize = 15.sp))
        BasicText("Погодні віджети", style = TextStyle(color = Primary, fontSize = 26.sp, fontWeight = FontWeight.Medium))
        BasicText("Обери місто. Температура відображається у °C.", style = TextStyle(color = Secondary, fontSize = 14.sp))
        BasicTextField(query, onValueChange = { query = it }, singleLine = true,
            textStyle = TextStyle(color = Primary, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF53483A), RoundedCornerShape(12.dp)).padding(16.dp),
            decorationBox = { inner -> Box { if (query.isEmpty()) BasicText("Назва міста", style = TextStyle(color = Secondary, fontSize = 16.sp)); inner() } })
        SettingsActionButton(if (searching) "Шукаю…" else "Знайти місто", enabled = query.trim().length >= 2 && !searching) {
            scope.launch {
                searching = true; searchError = null; results = emptyList()
                try { results = WeatherApi.search(query); if (results.isEmpty()) searchError = "Місто не знайдено. Спробуй іншу назву." }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { searchError = "Пошук недоступний. Перевір з’єднання з інтернетом." }
                finally { searching = false }
            }
        }
        searchError?.let { BasicText(it, style = TextStyle(color = Secondary, fontSize = 13.sp)) }
        results.forEach { location ->
            BasicText(location.displayName, modifier = Modifier.fillMaxWidth().clickable {
                results = emptyList(); query = location.name
                scope.launch { repository.selectLocation(location) }
            }.padding(vertical = 12.dp), style = TextStyle(color = Primary, fontSize = 15.sp))
        }
        val snapshot = state.snapshot
        if (snapshot != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WeatherCharacter(snapshot, Modifier.fillMaxWidth().height(176.dp)) }
            val observed = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(snapshot.observedAt))
            BasicText("${state.location?.displayName}\nДані за $observed", style = TextStyle(color = Secondary, fontSize = 12.sp))
        } else if (state.location != null) {
            BasicText("${state.location?.displayName}\n${if (state.refreshing) "Завантажую погоду…" else "Дані ще не отримано"}", style = TextStyle(color = Primary, fontSize = 15.sp))
        }
        state.error?.let { BasicText(it, style = TextStyle(color = Color(0xFFE4A998), fontSize = 13.sp)) }
        if (state.location != null) SettingsActionButton(if (state.refreshing) "Оновлюю…" else "Оновити погоду", enabled = !state.refreshing) { scope.launch { repository.refresh(force = true) } }
        SettingsActionButton("Додати віджет погоди", enabled = snapshot != null, onClick = onAddWidget)
        Row(Modifier.fillMaxWidth().clickable {
            draft = draft.copy(weatherEnabled = !draft.weatherEnabled); onSettingsChange(draft)
        }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BasicText("Погода на живих шпалерах", style = TextStyle(color = Primary, fontSize = 15.sp))
            ToggleMark(draft.weatherEnabled)
        }
        SettingSlider("Масштаб персонажа", String.format(Locale.ROOT, "%.0f%%", draft.weatherScale * 100f), draft.weatherScale, 0.55f..1.3f,
            onValueChange = { draft = draft.copy(weatherScale = it) }, onValueChangeFinished = { onSettingsChange(draft) })
        SettingSlider("Положення погоди по горизонталі", String.format(Locale.ROOT, "%+.0f", draft.weatherOffsetX), draft.weatherOffsetX, -140f..140f,
            onValueChange = { draft = draft.copy(weatherOffsetX = it) }, onValueChangeFinished = { onSettingsChange(draft) })
        SettingSlider("Положення погоди по вертикалі", String.format(Locale.ROOT, "%+.0f", draft.weatherOffsetY), draft.weatherOffsetY, -240f..240f,
            onValueChange = { draft = draft.copy(weatherOffsetY = it) }, onValueChangeFinished = { onSettingsChange(draft) })
        SettingsActionButton("Встановити живі шпалери") { onSetWallpaper(draft) }
        BasicText("У системному вікні обери екран блокування. Очі анімуються, поки екран увімкнений і шпалери видно.", style = TextStyle(color = Secondary, fontSize = 12.sp))
        BasicText("Погода: Open‑Meteo · міста: GeoNames", Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/")))
        }.padding(vertical = 10.dp), TextStyle(color = Secondary, fontSize = 12.sp))
    }
}
