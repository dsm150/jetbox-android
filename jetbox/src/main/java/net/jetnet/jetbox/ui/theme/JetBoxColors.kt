package net.jetnet.jetbox.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Цвета JetBox — порт `JBColor` (iOS, `JetBoxUI.swift`). Значения сняты с colorset'ов
 * `jetbox-ios/NetBird/Assets.xcassets/Colors/{JetBox*,BgPrimary,TextPrimary,TextSecondary}.colorset`.
 */
@Immutable
data class JetBoxColors(
    val ink: Color,
    val accent: Color,
    val surface: Color,
    val line: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    /** `BgPrimary` — фон страницы. */
    val background: Color,
    /** `TextPrimary`. */
    val text: Color,
    /** `TextSecondary`. */
    val muted: Color,
    val isDark: Boolean,
) {
    /**
     * Замена `.ultraThinMaterial` (iOS 15–25). Размытия на Android до API 31 нет (minSdk 26),
     * поэтому — полупрозрачная поверхность; альфа подобрана по скриншотам iOS (`proof/store-69`):
     * в светлой теме сквозь диск щита и панели просвечивает синева фона — диск «выкл» на iOS
     * (224,235,243), панель (240,247,253). Материал iOS ещё и насыщает то, что под ним, поэтому
     * светлый — не чисто белый, а с лёгкой голубизной [MATERIAL_TINT_LIGHT]: диск «выкл» на
     * Android (225,235,240) вместо прежних (229,236,240), панель почти не сдвигается.
     */
    val material: Color
        get() = if (isDark) surface.copy(alpha = 0.62f) else MATERIAL_TINT_LIGHT.copy(alpha = 0.58f)

    companion object {
        /** Основа светлого материала: белый с лёгкой голубизной (247,252,255). */
        internal val MATERIAL_TINT_LIGHT = Color(0xFFF7FCFF)

        val Light = JetBoxColors(
            ink = Color(0xFF273D58),
            accent = Color(0xFF1288E1),
            surface = Color(0xFFFFFFFF),
            line = Color(0xFFD9E3EC),
            ok = Color(0xFF1F9D6B),
            warn = Color(0xFFB3831D),
            danger = Color(0xFFC94747),
            background = Color(0xFFF2F6FA),
            text = Color(0xFF16283A),
            muted = Color(0xFF5C7288),
            isDark = false,
        )

        val Dark = JetBoxColors(
            ink = Color(0xFFDCE8F3),
            accent = Color(0xFF1A9BF5),
            surface = Color(0xFF101D2C),
            line = Color(0xFF1D3247),
            ok = Color(0xFF34C98E),
            warn = Color(0xFFF0C674),
            danger = Color(0xFFEF6A6A),
            background = Color(0xFF0A1420),
            text = Color(0xFFDCE8F3),
            muted = Color(0xFFA9BACB),
            isDark = true,
        )
    }
}
