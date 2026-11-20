package net.jetnet.jetbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.logic.JetBoxStyle

/*
 * Порт `JetBoxSimpleSettings.swift`: настройки и «О программе» в стиле «простое». Пункты те же, что у
 * «техно» ([JetBoxSettingsLayout]): имя JetBox, оформление, о программе, версия (семь нажатий открывают
 * расширенные настройки). Состояние и счёт нажатий — общие, в [JetBoxSettingsScreen].
 *
 * Отличия от iOS (Android): строка «Обновить до …» (сборка «файл с сайта»), «Выключить расширенные
 * настройки» здесь же (как у «техно» на Android), в «О программе» — ещё политика конфиденциальности.
 */

@Composable
fun JetBoxSimpleSettingsLayout(
    boxName: String?,
    versionText: String,
    advancedUnlocked: Boolean,
    showsAdvancedOff: Boolean,
    toast: String?,
    style: JetBoxStyle,
    onStyleChange: (JetBoxStyle) -> Unit,
    onVersionTap: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onAdvancedOff: () -> Unit,
    onClose: () -> Unit,
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(JBSimple.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            JBSimpleHeader(stringResource(R.string.jb_settings_title), stringResource(R.string.jb_close), onClose)

            JBSimpleSectionLabel(stringResource(R.string.jb_settings_section_box))
            JBSimpleCard {
                if (boxName != null) {
                    JBSimpleRow(stringResource(R.string.jb_settings_box_name), boxName)
                } else {
                    JBSimpleRow(stringResource(R.string.jb_settings_no_box), titleColor = JBSimple.muted)
                }
            }

            JBSimpleSectionLabel(stringResource(R.string.jb_settings_section_app))
            JBSimpleCard {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.jb_style_title), style = JBSimple.font(20, JBSimple.bold), color = JBSimple.ink)
                    JBSimpleStylePicker(style, onStyleChange)
                }
                JBSimpleDivider()
                JBSimpleRow(stringResource(R.string.jb_about_title), chevron = true, onClick = onOpenAbout)
                if (updateVersion != null) {
                    JBSimpleDivider()
                    JBSimpleRow(
                        stringResource(R.string.jb_update_install, updateVersion),
                        chevron = true,
                        titleColor = JBSimple.accent,
                        onClick = onUpdate,
                    )
                }
            }

            if (advancedUnlocked) {
                Spacer(Modifier.height(10.dp))
                JBSimpleCard {
                    JBSimpleRow(
                        stringResource(R.string.jb_settings_advanced),
                        chevron = true,
                        titleColor = JBSimple.accent,
                        onClick = onOpenAdvanced,
                    )
                }
                JBSimpleFootnote(stringResource(R.string.jb_settings_advanced_footer))
            }

            if (showsAdvancedOff) {
                Spacer(Modifier.height(10.dp))
                JBSimpleCard {
                    JBSimpleRow(stringResource(R.string.jb_settings_advanced_off), titleColor = JBSimple.error, onClick = onAdvancedOff)
                }
                JBSimpleFootnote(stringResource(R.string.jb_settings_advanced_off_footer))
            }

            // Строка версии — вход в расширенные настройки (семь нажатий); нажимается вся полоса.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .heightIn(min = 48.dp)
                    .jbSimplePress(onClick = onVersionTap),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${stringResource(R.string.jb_settings_version)} $versionText",
                    style = JBSimple.font(17, JBSimple.semibold),
                    color = JBSimple.muted,
                )
            }
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        ) {
            val shown = remember { arrayOf("") }
            if (toast != null) shown[0] = toast
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .background(JBSimple.ink.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(shown[0], style = JBSimple.font(17, JBSimple.bold), color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * «О программе» в стиле «простое»: картинка, «Что такое JetBox» — четыре пункта со значками, ссылки
 * (сайт, исходный код и лицензия — GPL-3.0 §5(d), политика конфиденциальности — Google Play) и
 * юридическая строка.
 */
@Composable
fun JetBoxSimpleAboutLayout(onBack: () -> Unit, onOpenUrl: (String) -> Unit, year: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .background(JBSimple.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).jbSimplePress(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = null,
                tint = JBSimple.accent,
                modifier = Modifier.size(28.dp),
            )
            Text(stringResource(R.string.jb_settings_title), style = JBSimple.font(19, JBSimple.heavy), color = JBSimple.accent)
        }

        Image(
            painterResource(R.drawable.jb_simple_about),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.jb_simple_about_title),
            style = JBSimple.font(30, JBSimple.black),
            color = JBSimple.ink,
            modifier = Modifier.semantics { heading() },
        )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AboutPoint(JBSimpleIcons.router, stringResource(R.string.jb_simple_about_router))
            AboutPoint(JBSimpleIcons.shield, stringResource(R.string.jb_simple_about_guard))
            AboutPoint(JBSimpleIcons.smartphone, stringResource(R.string.jb_simple_about_app))
            AboutPoint(JBSimpleIcons.visibilityOff, stringResource(R.string.jb_simple_about_privacy))
        }

        JBSimpleCard(Modifier.padding(top = 6.dp)) {
            JBSimpleRow(stringResource(R.string.jb_about_site), chevron = true) { onOpenUrl(JetBoxConfig.SITE_URL) }
            JBSimpleDivider()
            // GPL-3.0 обязывает открыть исходники форка — ссылка рядом с упоминанием лицензии.
            JBSimpleRow(stringResource(R.string.jb_about_source), chevron = true) { onOpenUrl(JetBoxConfig.SOURCE_CODE_URL) }
            JBSimpleDivider()
            JBSimpleRow(stringResource(R.string.jb_about_license), chevron = true) { onOpenUrl(JetBoxConfig.LICENSE_URL) }
            JBSimpleDivider()
            JBSimpleRow(stringResource(R.string.jb_about_privacy), chevron = true) { onOpenUrl(JetBoxConfig.PRIVACY_POLICY_URL) }
        }

        // Сведения, которые GPL-3.0 §5(d) требует показывать в интерфейсе.
        Text(
            stringResource(R.string.jb_about_legal, year.toString()),
            style = JBSimple.font(17, JBSimple.semibold),
            color = JBSimple.muted,
        )
    }
}

@Composable
private fun AboutPoint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        JBSimpleIconTile(icon)
        Spacer(Modifier.width(14.dp))
        Text(text, style = JBSimple.font(19, JBSimple.bold), color = JBSimple.ink, modifier = Modifier.padding(top = 2.dp))
    }
}

@Preview(name = "Простое — настройки", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SimpleSettingsPreview() = JetBoxSimpleSettingsLayout(
    boxName = "Дом на Озёрной", versionText = "1.0.4 (14)", advancedUnlocked = false, showsAdvancedOff = false,
    toast = null, style = JetBoxStyle.SIMPLE, onStyleChange = {}, onVersionTap = {}, onOpenAbout = {},
    onOpenAdvanced = {}, onAdvancedOff = {}, onClose = {},
)

@Preview(name = "Простое — о программе", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SimpleAboutPreview() = JetBoxSimpleAboutLayout({}, {}, 2026)
