package dev.stoneclock.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stoneclock.wallpaper.WallpaperPreview
import java.util.Locale
import kotlin.math.abs

private val Background = Color(0xFF090909)
private val Track = Color(0xFF393633)
private val Accent = Color(0xFFD8C3A3)
private val PrimaryText = Color(0xFFE8E2D8)
private val SecondaryText = Color(0xFF99948B)

@Composable
fun SettingsScreen(
    settings: ClockSettings,
    previewAspectRatio: Float,
    backgroundBusy: Boolean,
    exactWidgetUpdatesAllowed: Boolean,
    onSettingsChange: (ClockSettings) -> Unit,
    onAddHomeWidget: () -> Unit,
    onEnablePreciseWidgetUpdates: () -> Unit,
    onChooseBackground: () -> Unit,
    onRemoveBackground: () -> Unit,
    onPreviewWallpaper: (ClockSettings) -> Unit,
    onOpenWeatherDemo: () -> Unit,
    onOpenUpdates: () -> Unit,
    availableUpdateVersion: String? = null,
) {
    var draft by remember(settings) { mutableStateOf(settings) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        BasicText(
            "Stone Clock",
            style = TextStyle(color = PrimaryText, fontSize = 28.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(6.dp))
        BasicText("Живі шпалери з кам’яним часом", style = TextStyle(color = SecondaryText, fontSize = 14.sp))
        Spacer(Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            WallpaperPreview(
                settings = draft,
                modifier = Modifier
                .height(320.dp)
                .aspectRatio(previewAspectRatio)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color(0xFF252321), RoundedCornerShape(18.dp)),
            )
        }
        Spacer(Modifier.height(16.dp))
        SettingsActionButton("Прев’ю та встановлення шпалер") { onPreviewWallpaper(draft) }
        Spacer(Modifier.height(8.dp))
        SettingsActionButton("Погодні віджети", onClick = onOpenWeatherDemo)
        Spacer(Modifier.height(8.dp))
        SettingsActionButton(availableUpdateVersion?.let { "Доступне оновлення · $it" } ?: "Оновлення застосунку", onClick = onOpenUpdates)
        Spacer(Modifier.height(8.dp))
        BasicText(
            "У системному вікні обери екран блокування. Маленький штатний час Nothing залишається зверху.",
            style = TextStyle(color = SecondaryText, fontSize = 12.sp),
        )
        Spacer(Modifier.height(24.dp))

        SettingSlider(
            title = "Масштаб",
            valueText = String.format(Locale.ROOT, "%.0f%%", draft.scale * 100),
            value = draft.scale,
            range = 0.4f..1.6f,
            onValueChange = { draft = draft.copy(scale = it) },
            onValueChangeFinished = { onSettingsChange(draft) },
        )
        SettingSlider(
            title = "Горизонтальне положення",
            valueText = String.format(Locale.ROOT, "%+.0f", draft.offsetX),
            value = draft.offsetX,
            range = -160f..160f,
            onValueChange = { draft = draft.copy(offsetX = it) },
            onValueChangeFinished = { onSettingsChange(draft) },
        )
        SettingSlider(
            title = "Вертикальне положення",
            valueText = String.format(Locale.ROOT, "%+.0f", draft.offsetY),
            value = draft.offsetY,
            range = -260f..360f,
            onValueChange = { draft = draft.copy(offsetY = it) },
            onValueChangeFinished = { onSettingsChange(draft) },
        )
        SettingSlider(
            title = "Відстань між цифрами",
            valueText = String.format(Locale.ROOT, "%.0f dp", draft.digitSpacing),
            value = draft.digitSpacing,
            range = 0f..16f,
            onValueChange = { draft = draft.copy(digitSpacing = it) },
            onValueChangeFinished = { onSettingsChange(draft) },
        )
        SettingSlider(
            title = "Відстань біля двокрапки",
            valueText = String.format(Locale.ROOT, "%.0f dp", draft.colonSpacing),
            value = draft.colonSpacing,
            range = 0f..24f,
            onValueChange = { draft = draft.copy(colonSpacing = it) },
            onValueChangeFinished = { onSettingsChange(draft) },
        )
        SettingSlider(
            title = "Непрозорість",
            valueText = String.format(Locale.ROOT, "%.0f%%", draft.opacity * 100),
            value = draft.opacity,
            range = 0f..1f,
            onValueChange = { draft = draft.copy(opacity = it) },
            onValueChangeFinished = { onSettingsChange(draft) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    draft = draft.copy(is24Hour = !draft.is24Hour)
                    onSettingsChange(draft)
                }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                BasicText("Формат часу", style = TextStyle(color = PrimaryText, fontSize = 15.sp))
                Spacer(Modifier.height(4.dp))
                BasicText(
                    if (draft.is24Hour) "24 години" else "12 годин",
                    style = TextStyle(color = SecondaryText, fontSize = 13.sp),
                )
            }
            ToggleMark(enabled = draft.is24Hour)
        }
        Spacer(Modifier.height(16.dp))

        BasicText("ФОН ШПАЛЕР", style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        SettingsActionButton(if (backgroundBusy) "Зберігаю фон…" else "Обрати зображення", enabled = !backgroundBusy, onClick = onChooseBackground)
        if (draft.backgroundImage != null) {
            Spacer(Modifier.height(8.dp))
            SettingsActionButton("Прибрати зображення", onClick = onRemoveBackground)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0xFF090909.toInt(), 0xFF262626.toInt(), 0xFF453A2E.toInt()).forEach { color ->
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(Color(color))
                        .border(if (draft.backgroundColor == color) 2.dp else 1.dp, Accent, CircleShape)
                        .semantics {
                            contentDescription = when (color) {
                                0xFF090909.toInt() -> "Чорний фон"
                                0xFF262626.toInt() -> "Графітовий фон"
                                else -> "Кам’яний фон"
                            }
                        }
                        .clickable {
                            draft = draft.copy(backgroundColor = color)
                            onSettingsChange(draft)
                        },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        BasicText(
            "ВІДЖЕТ ГОЛОВНОГО ЕКРАНА",
            style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            "Додай кам’яний годинник на головний екран. Натисни віджет, щоб відкрити налаштування.",
            style = TextStyle(color = SecondaryText, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        SettingsActionButton("Додати віджет на головний екран", onClick = onAddHomeWidget)
        Spacer(Modifier.height(8.dp))
        if (exactWidgetUpdatesAllowed) {
            BasicText(
                "Хвилинні оновлення віджета увімкнені.",
                style = TextStyle(color = SecondaryText, fontSize = 12.sp),
            )
        } else {
            BasicText(
                "Щоб час у віджеті змінювався щохвилини, дозволь точні оновлення в системних налаштуваннях. Вони не вмикатимуть екран.",
                style = TextStyle(color = SecondaryText, fontSize = 12.sp),
            )
            Spacer(Modifier.height(8.dp))
            SettingsActionButton("Дозволити хвилинні оновлення", onClick = onEnablePreciseWidgetUpdates)
        }

    }
}

@Composable
internal fun SettingsActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF27231D))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TextStyle(color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
internal fun SettingSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(title, style = TextStyle(color = PrimaryText, fontSize = 14.sp))
            BasicText(valueText, style = TextStyle(color = SecondaryText, fontSize = 13.sp))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(CircleShape)
                .pointerInput(range) {
                    fun update(x: Float) {
                        val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        latestOnValueChange(range.start + fraction * (range.endInclusive - range.start))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) {
                                if (dragging) latestOnFinished()
                                break
                            }
                            val distance = change.position - down.position
                            if (!change.pressed) {
                                if (dragging || distance.getDistance() <= viewConfiguration.touchSlop) {
                                    update(change.position.x)
                                    change.consume()
                                    latestOnFinished()
                                }
                                break
                            }
                            if (!dragging) {
                                // Leave vertical drags to the settings screen's scroll container.
                                if (abs(distance.y) > viewConfiguration.touchSlop && abs(distance.y) >= abs(distance.x)) break
                                if (abs(distance.x) <= viewConfiguration.touchSlop) continue
                                dragging = true
                            }
                            update(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            val centerY = size.height / 2f
            val startX = 8.dp.toPx()
            val endX = size.width - 8.dp.toPx()
            val thumbX = startX + (endX - startX) * fraction
            drawLine(Track, Offset(startX, centerY), Offset(endX, centerY), 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Accent, Offset(startX, centerY), Offset(thumbX, centerY), 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Accent, 7.dp.toPx(), Offset(thumbX, centerY))
        }
    }
}

@Composable
internal fun ToggleMark(enabled: Boolean) {
    Canvas(Modifier.size(width = 46.dp, height = 28.dp)) {
        val radius = size.height / 2f
        drawRoundRect(
            color = if (enabled) Color(0xFF8C7659) else Track,
            cornerRadius = CornerRadius(radius, radius),
        )
        val knobX = if (enabled) size.width - radius else radius
        drawCircle(Color(0xFFF4EEE4), radius * 0.72f, Offset(knobX, radius))
    }
}
