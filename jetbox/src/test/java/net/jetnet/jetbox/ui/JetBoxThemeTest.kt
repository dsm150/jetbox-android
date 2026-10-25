package net.jetnet.jetbox.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import net.jetnet.jetbox.ui.theme.JetBoxColors
import net.jetnet.jetbox.ui.theme.jetBoxColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Схема Material 3 целиком из цветов JetBox: ни одна роль не осталась лиловой по умолчанию. */
class JetBoxThemeTest {

    @Test
    fun lightSchemeHasNoMaterialDefaults() = assertNoDefaults(jetBoxColorScheme(JetBoxColors.Light), lightColorScheme())

    @Test
    fun darkSchemeHasNoMaterialDefaults() = assertNoDefaults(jetBoxColorScheme(JetBoxColors.Dark), darkColorScheme())

    @Test
    fun surfacesFollowJetBox() {
        val light = jetBoxColorScheme(JetBoxColors.Light)
        assertEquals(JetBoxColors.Light.surface, light.surfaceContainerLowest)
        assertEquals(JetBoxColors.Light.background, light.surfaceContainerHighest)
        val dark = jetBoxColorScheme(JetBoxColors.Dark)
        assertEquals(JetBoxColors.Dark.background, dark.surfaceContainerLowest)
        assertEquals(JetBoxColors.Dark.surface, dark.surfaceContainer)
        assertEquals(JetBoxColors.Dark.accent, dark.primary)
    }

    /** Все роли-цвета схемы (геттеры value-класса `Color` компилируются в `long getXxx-…()`). */
    private fun roles(scheme: ColorScheme): Map<String, Long> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { it.name.substringBefore('-') to it.invoke(scheme) as Long }

    private fun assertNoDefaults(ours: ColorScheme, defaults: ColorScheme) {
        val a = roles(ours)
        val d = roles(defaults)
        assertTrue("ролей мало: ${a.keys}", a.size >= 30)
        // Совпадение с M3 допустимо только для нейтральных белого и чёрного (onPrimary, scrim…).
        val neutral = setOf(Color.White.value.toLong(), Color.Black.value.toLong())
        val leftovers = a.filter { (name, color) -> color == d[name] && color !in neutral }
        assertTrue("остались цвета M3 по умолчанию: ${leftovers.keys}", leftovers.isEmpty())
    }
}
