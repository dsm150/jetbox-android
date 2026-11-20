package net.jetnet.jetbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import net.jetnet.jetbox.logic.JetBoxStyle
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Стиль оформления в интерфейсе: текущий стиль живёт в [JetBoxStyleHolder] (одно состояние на окно),
 * экраны читают его из [LocalJetBoxStyle] и выбирают «простой» или «техно» вид. Логика экранов общая.
 */

/**
 * Текущий стиль окна. [onChange] — сохранить выбор (настройки JetBox) и перерисовать виджеты;
 * по умолчанию (превью, витрина) — ничего.
 */
@Stable
class JetBoxStyleHolder(initial: JetBoxStyle, private val onChange: (JetBoxStyle) -> Unit = {}) {
    var style by mutableStateOf(initial)
        private set

    val isSimple: Boolean get() = style == JetBoxStyle.SIMPLE

    fun set(value: JetBoxStyle) {
        if (value == style) return
        style = value
        onChange(value)
    }
}

/** Без поставщика (превью Android Studio) — «техно», как по умолчанию в приложении. */
val LocalJetBoxStyle = staticCompositionLocalOf { JetBoxStyleHolder(JetBoxStyle.FALLBACK) }

/**
 * Корень стиля: отдаёт [holder] экранам. «Простое» — только светлое (iOS: `.colorScheme(.light)`),
 * поэтому тема Material в нём светлая и значки системных полос тёмные даже при тёмной теме системы;
 * «техно» — как прежде, по теме системы.
 */
@Composable
fun JetBoxStyleRoot(holder: JetBoxStyleHolder, content: @Composable () -> Unit) {
    val simple = holder.isSimple
    val dark = isSystemInDarkTheme()
    SystemBarIcons(lightBackground = simple || !dark)
    // Одна и та же структура дерева в обоих стилях: смена стиля не сбрасывает открытый экран (настройки).
    CompositionLocalProvider(LocalJetBoxStyle provides holder) {
        JetBoxTheme(darkTheme = !simple && dark, content = content)
    }
}

/** Цвет значков строки состояния и навигации: тёмные на светлом фоне, светлые на тёмном. */
@Composable
private fun SystemBarIcons(lightBackground: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, lightBackground) {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
