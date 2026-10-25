package net.jetnet.jetbox.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme
import java.util.Calendar

/*
 * Порт `JetBoxAboutView` (`JetBoxSettingsView.swift`): знак, что это за приложение, ссылки на сайт,
 * исходный код и лицензию, юридическая строка GPL-3.0 §5(d). Без бета-программы и документации NetBird.
 *
 * Отличие от iOS: ещё и ссылка на политику конфиденциальности (в iOS она только под кнопкой
 * «Подключить»; Google Play требует доступ к политике из приложения).
 */

/** «О программе» с навигацией: «Назад» — кнопкой в шапке и системным жестом. */
@Composable
fun JetBoxAboutScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = rememberUrlOpener(),
    year: Int = Calendar.getInstance().get(Calendar.YEAR),
) {
    BackHandler(onBack = onBack)
    JetBoxAboutLayout(onBack, onOpenUrl, year)
}

@Composable
fun JetBoxAboutLayout(onBack: () -> Unit, onOpenUrl: (String) -> Unit, year: Int) {
    val c = JetBoxTheme.colors
    JBPage(
        title = stringResource(R.string.jb_about_title),
        leading = {
            JBGlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.jb_back), onClick = onBack)
        },
    ) {
        JBSection {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                JetBoxLogo(markHeight = 40.dp)
                Text(stringResource(R.string.jb_about_body), style = JBType.subheadline, color = c.muted)
                AboutLink(stringResource(R.string.jb_about_site)) { onOpenUrl(JetBoxConfig.SITE_URL) }
                // GPL-3.0 обязывает открыть исходники форка — ссылка рядом с упоминанием лицензии.
                AboutLink(stringResource(R.string.jb_about_source)) { onOpenUrl(JetBoxConfig.SOURCE_CODE_URL) }
                AboutLink(stringResource(R.string.jb_about_license)) { onOpenUrl(JetBoxConfig.LICENSE_URL) }
                AboutLink(stringResource(R.string.jb_about_privacy)) { onOpenUrl(JetBoxConfig.PRIVACY_POLICY_URL) }
                // Сведения, которые GPL-3.0 §5(d) требует показывать в интерфейсе: авторские права,
                // отсутствие гарантий, лицензия.
                Text(stringResource(R.string.jb_about_legal, year.toString()), style = JBType.footnote, color = c.muted)
            }
        }
    }
}

/** Ссылка `Link` iOS: фирменный синий, средний вес. */
@Composable
private fun AboutLink(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = JBType.subheadline.copy(fontWeight = FontWeight.Medium),
        color = JetBoxTheme.colors.accent,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
    )
}

@Preview(name = "О программе — светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun AboutLightPreview() = JetBoxTheme(darkTheme = false) { JetBoxAboutLayout({}, {}, 2026) }

@Preview(name = "О программе — тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutDarkPreview() = JetBoxTheme(darkTheme = true) { JetBoxAboutLayout({}, {}, 2026) }
