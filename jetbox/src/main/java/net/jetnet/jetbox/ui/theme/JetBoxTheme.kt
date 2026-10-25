package net.jetnet.jetbox.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LocalJetBoxColors = staticCompositionLocalOf { JetBoxColors.Light }

/** «Уменьшение движения»: на Android — системная «Масштаб длительности анимации» = 0. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Тема JetBox: следует системной (светлая/тёмная). Material 3 получает наши цвета целиком — все роли
 * схемы, чтобы системные компоненты (листы, диалоги, поля, меню) нигде не показали лиловую палитру M3.
 */
@Composable
fun JetBoxTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) JetBoxColors.Dark else JetBoxColors.Light
    val scheme = remember(colors) { jetBoxColorScheme(colors) }
    val reduceMotion = rememberReduceMotion()
    CompositionLocalProvider(LocalJetBoxColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Полная схема Material 3 из цветов JetBox. Светлая: контейнеры поверхностей идут от `surface` (белый)
 * к `background`; тёмная — от `background` через `surface` к смеси с `line`. Вторичные роли — `ink`,
 * третичные — `accent`; контейнеры — те же цвета с непрозрачностью 0,16 поверх поверхности.
 */
internal fun jetBoxColorScheme(c: JetBoxColors): ColorScheme {
    fun container(color: Color, alpha: Float = 0.16f) = color.copy(alpha = alpha).compositeOver(c.surface)
    val other = if (c.isDark) JetBoxColors.Light else JetBoxColors.Dark
    val onAccent = Color.White
    val onInk = if (c.isDark) c.background else c.surface
    // «Fixed»-роли одинаковы в обеих темах — светлые плашки фирменного цвета с тёмным текстом.
    val light = JetBoxColors.Light
    fun fixed(color: Color, alpha: Float) = color.copy(alpha = alpha).compositeOver(light.surface)
    val scheme = if (c.isDark) {
        val raised = { f: Float -> lerp(c.surface, c.line, f) }
        darkColorScheme(
            primary = c.accent, onPrimary = onAccent,
            primaryContainer = container(c.accent), onPrimaryContainer = c.accent,
            inversePrimary = other.accent,
            secondary = c.ink, onSecondary = onInk,
            secondaryContainer = container(c.ink, 0.12f), onSecondaryContainer = c.ink,
            tertiary = c.accent, onTertiary = onAccent,
            tertiaryContainer = container(c.accent), onTertiaryContainer = c.accent,
            background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted,
            surfaceTint = Color.Transparent,
            inverseSurface = c.ink, inverseOnSurface = c.surface,
            error = c.danger, onError = onAccent,
            errorContainer = container(c.danger), onErrorContainer = c.danger,
            outline = c.line, outlineVariant = c.line,
            scrim = Color.Black,
            surfaceBright = raised(0.6f),
            surfaceDim = c.background,
            surfaceContainerLowest = c.background,
            surfaceContainerLow = lerp(c.background, c.surface, 0.5f),
            surfaceContainer = c.surface,
            surfaceContainerHigh = raised(0.35f),
            surfaceContainerHighest = raised(0.6f),
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = onAccent,
            primaryContainer = container(c.accent), onPrimaryContainer = c.accent,
            inversePrimary = other.accent,
            secondary = c.ink, onSecondary = onInk,
            secondaryContainer = container(c.ink, 0.12f), onSecondaryContainer = c.ink,
            tertiary = c.accent, onTertiary = onAccent,
            tertiaryContainer = container(c.accent), onTertiaryContainer = c.accent,
            background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface, onSurfaceVariant = c.muted,
            surfaceTint = Color.Transparent,
            inverseSurface = c.text, inverseOnSurface = c.background,
            error = c.danger, onError = onAccent,
            errorContainer = container(c.danger), onErrorContainer = c.danger,
            outline = c.line, outlineVariant = c.line,
            scrim = Color.Black,
            surfaceBright = c.surface,
            surfaceDim = c.background,
            surfaceContainerLowest = c.surface,
            surfaceContainerLow = c.surface,
            surfaceContainer = lerp(c.surface, c.background, 0.5f),
            surfaceContainerHigh = lerp(c.surface, c.background, 0.75f),
            surfaceContainerHighest = c.background,
        )
    }
    return scheme.copy(
        primaryFixed = fixed(light.accent, 0.16f), primaryFixedDim = fixed(light.accent, 0.28f),
        onPrimaryFixed = light.ink, onPrimaryFixedVariant = light.accent,
        secondaryFixed = fixed(light.ink, 0.12f), secondaryFixedDim = fixed(light.ink, 0.22f),
        onSecondaryFixed = light.ink, onSecondaryFixedVariant = light.muted,
        tertiaryFixed = fixed(light.accent, 0.16f), tertiaryFixedDim = fixed(light.accent, 0.28f),
        onTertiaryFixed = light.ink, onTertiaryFixedVariant = light.accent,
    )
}

/**
 * Системная «Масштаб длительности анимации» = 0 → «уменьшение движения». Перечитывается при смене
 * настройки (ContentObserver), как `accessibilityReduceMotion` на iOS — без перезапуска экрана.
 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember(resolver) { mutableStateOf(readReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduce = readReduceMotion(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Доступ к нашим цветам и стилям: `JetBoxTheme.colors.accent`. */
object JetBoxTheme {
    val colors: JetBoxColors
        @Composable @ReadOnlyComposable get() = LocalJetBoxColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/** Текстовые стили — размеры Dynamic Type iOS (Large), шрифт системный. */
object JBType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
}
