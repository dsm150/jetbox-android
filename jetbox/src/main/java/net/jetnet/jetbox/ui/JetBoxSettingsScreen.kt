package net.jetnet.jetbox.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import net.jetnet.jetbox.BuildConfig
import net.jetnet.jetbox.R
import net.jetnet.jetbox.logic.JetBoxAdvancedAccess
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxSettingsView.swift`: настройки простого режима — что за JetBox, версия и «О программе».
 *
 * Служебные экраны NetBird спрятаны за «семью нажатиями» по строке версии — как «Для разработчиков»
 * в Android. Обычный клиент туда не проваливается, а поддержка открывает их по телефону за пять секунд.
 *
 * Отличия от iOS (Android):
 * - «Расширенные настройки» открывают апстримовую `MainActivity` поверх (в iOS переключается всё
 *   приложение), поэтому настройки не закрываются: «Назад» из NetBird возвращает сюда;
 * - «Выключить расширенные настройки» живёт здесь, отдельной секцией красным (в iOS — первой секцией
 *   апстримового экрана Settings): апстримовые экраны Android мы не правим.
 */

/** Имя апстримовой активности NetBird — служебные экраны расширенного режима. */
const val UPSTREAM_MAIN_ACTIVITY = "io.netbird.client.MainActivity"

/** «1.0 (1)» — версия и сборка, как `CFBundleShortVersionString (CFBundleVersion)`. */
val JetBoxVersionText: String
    get() = "${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})"

/** Открыть служебные экраны NetBird. */
fun Context.openUpstreamMainActivity() {
    try {
        startActivity(Intent().setClassName(packageName, UPSTREAM_MAIN_ACTIVITY))
    } catch (e: ActivityNotFoundException) {
        Log.w("JetBox", "нет $UPSTREAM_MAIN_ACTIVITY в сборке")
    }
}

/**
 * Настройки с данными: счёт нажатий — [access] (обнуляется при уходе с экрана), «О программе» —
 * внутри, как `NavigationLink` iOS. [onOpenAdvanced] по умолчанию — апстримовая `MainActivity`;
 * режим ([JetBoxAdvancedAccess.isAdvancedMode]) включается до запуска, как в iOS.
 *
 * [update] — версия новее установленной («Обновить до 1.1» открывает файл в браузере); при входе на
 * экран [onCheckUpdates] спрашивает сайт сразу, не дожидаясь суточной проверки.
 */
@Composable
fun JetBoxSettingsScreen(
    access: JetBoxAdvancedAccess,
    box: JetBoxBox?,
    onClose: () -> Unit,
    versionText: String = JetBoxVersionText,
    onOpenAdvanced: (() -> Unit)? = null,
    update: UpdateInfo? = null,
    onCheckUpdates: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onCheckUpdates() }
    val resources = LocalResources.current
    var unlocked by remember { mutableStateOf(access.isUnlocked) }
    var advancedMode by remember { mutableStateOf(access.isAdvancedMode) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastToken by remember { mutableIntStateOf(0) }
    var showsAbout by rememberSaveable { mutableStateOf(false) }

    // Флаги могли смениться, пока экран был скрыт (расширенный режим, другой экран).
    LifecycleResumeEffect(access) {
        unlocked = access.isUnlocked
        advancedMode = access.isAdvancedMode
        onPauseOrDispose { }
    }
    // Счётчик нажатий живёт ровно столько, сколько открыт экран: ушёл и вернулся — считаем заново.
    DisposableEffect(access) { onDispose { access.resetCounter() } }

    // 2,5 с: русская подсказка длиннее английской, за полторы секунды её не успеваешь прочесть.
    LaunchedEffect(toastToken) {
        if (toast == null) return@LaunchedEffect
        delay(2_500)
        toast = null
    }
    fun show(text: String) {
        toast = text
        toastToken += 1
    }

    AnimatedContent(
        targetState = showsAbout,
        transitionSpec = {
            val spec = tween<androidx.compose.ui.unit.IntOffset>(300)
            if (targetState) {
                (slideInHorizontally(spec) { it } + fadeIn()) togetherWith (slideOutHorizontally(spec) { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally(spec) { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally(spec) { it } + fadeOut())
            }
        },
        label = "settingsNav",
    ) { about ->
        if (about) {
            JetBoxAboutScreen(onBack = { showsAbout = false })
        } else {
            BackHandler(onBack = onClose)
            JetBoxSettingsLayout(
                boxName = box?.displayName,
                versionText = versionText,
                updateVersion = update?.versionName,
                onUpdate = { update?.let { context.openUrl(it.url) } },
                advancedUnlocked = unlocked,
                showsAdvancedOff = advancedMode,
                toast = toast,
                onVersionTap = {
                    when (val r = access.registerTap()) {
                        JetBoxAdvancedAccess.TapResult.Silent -> Unit
                        is JetBoxAdvancedAccess.TapResult.Hint ->
                            show(resources.getQuantityString(R.plurals.jb_settings_advanced_hint, r.remaining, r.remaining))
                        JetBoxAdvancedAccess.TapResult.Unlocked -> {
                            unlocked = true
                            show(resources.getString(R.string.jb_settings_advanced_unlocked))
                        }
                        JetBoxAdvancedAccess.TapResult.AlreadyOpen ->
                            show(resources.getString(R.string.jb_settings_advanced_already))
                    }
                },
                onOpenAbout = { showsAbout = true },
                onOpenAdvanced = {
                    access.isAdvancedMode = true
                    advancedMode = true
                    if (onOpenAdvanced != null) onOpenAdvanced() else context.openUpstreamMainActivity()
                },
                // Выключение закрывает и доступ: чтобы вернуться, снова нужны семь нажатий.
                onAdvancedOff = {
                    access.lock()
                    unlocked = false
                    advancedMode = false
                },
                onClose = onClose,
            )
        }
    }
}

/** Настройки без привязки к данным: всё приходит параметрами. */
@Composable
fun JetBoxSettingsLayout(
    boxName: String?,
    versionText: String,
    advancedUnlocked: Boolean,
    showsAdvancedOff: Boolean,
    toast: String?,
    onVersionTap: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onAdvancedOff: () -> Unit,
    onClose: () -> Unit,
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
) {
    val c = JetBoxTheme.colors
    JBPage(
        title = stringResource(R.string.jb_settings_title),
        trailing = { JBGlassCapsuleButton(stringResource(R.string.jb_close), onClick = onClose) },
        overlay = {
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
            ) {
                // Последний текст держим на время исчезновения.
                val shown = remember { arrayOf("") }
                if (toast != null) shown[0] = toast
                JBToast(shown[0])
            }
        },
    ) {
        JBSection(header = stringResource(R.string.jb_settings_section_box)) {
            if (boxName != null) {
                JBListRow { JBRow(stringResource(R.string.jb_settings_box_name), boxName) }
            } else {
                JBListRow {
                    Text(stringResource(R.string.jb_settings_no_box), style = JBType.subheadline, color = c.muted)
                }
            }
        }

        JBSection(header = stringResource(R.string.jb_settings_section_app)) {
            // Строка версии — вход в расширенные настройки. Нажимается вся строка, а не только
            // надписи: попасть в неё семь раз подряд не должно быть лотереей.
            JBListRow(onClick = onVersionTap, indication = false) {
                JBRow(stringResource(R.string.jb_settings_version), versionText)
            }
            if (updateVersion != null) {
                JBListDivider()
                JBListRow(onClick = onUpdate) {
                    Text(
                        stringResource(R.string.jb_update_install, updateVersion),
                        style = JBType.body,
                        color = c.accent,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.muted.copy(alpha = 0.6f))
                }
            }
            JBListDivider()
            JBListRow(onClick = onOpenAbout) {
                Text(stringResource(R.string.jb_about_title), style = JBType.body, color = c.text, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.muted.copy(alpha = 0.6f))
            }
        }

        if (advancedUnlocked) {
            JBSection(footer = stringResource(R.string.jb_settings_advanced_footer)) {
                JBListRow(onClick = onOpenAdvanced) {
                    Text(stringResource(R.string.jb_settings_advanced), style = JBType.body, color = c.accent)
                }
            }
        }

        if (showsAdvancedOff) {
            JBSection(footer = stringResource(R.string.jb_settings_advanced_off_footer)) {
                JBListRow(onClick = onAdvancedOff) {
                    Text(stringResource(R.string.jb_settings_advanced_off), style = JBType.body, color = c.danger)
                }
            }
        }
    }
}

// region Список в стиле insetGrouped iOS

/**
 * Страница со шапкой (`navigationTitle` inline + кнопки по краям): шапка — поверхность с тонкой
 * линией снизу, под ней — прокручиваемый список на фоне страницы.
 */
@Composable
internal fun JBPage(
    title: String,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = JetBoxTheme.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
                JBHeaderRow(
                    leading = leading,
                    trailing = trailing,
                    title = {
                        Text(
                            title,
                            style = JBType.headline,
                            color = c.text,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                )
                HorizontalDivider(thickness = 0.5.dp, color = c.line)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = content,
            )
        }
        overlay()
    }
}

/**
 * Шапка: слева и справа слоты одинаковой ширины (по большей из двух кнопок), заголовок — по центру
 * между ними, в одну строку с многоточием; при крупном шрифте шапка растёт (не меньше 60 dp).
 */
@Composable
private fun JBHeaderRow(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    title: @Composable () -> Unit,
) {
    val minHeight = 60.dp
    Layout(
        content = {
            Box { leading() }
            Box { title() }
            Box { trailing() }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 12.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val l = measurables[0].measure(loose)
        val r = measurables[2].measure(loose)
        val slot = maxOf(l.width, r.width)
        val titleWidth = (constraints.maxWidth - 2 * slot).coerceAtLeast(0)
        val t = measurables[1].measure(loose.copy(maxWidth = titleWidth))
        val width = constraints.maxWidth
        val height = maxOf(constraints.minHeight, l.height, r.height, t.height)
        layout(width, height) {
            l.placeRelative(0, (height - l.height) / 2)
            r.placeRelative(width - r.width, (height - r.height) / 2)
            t.placeRelative((width - t.width) / 2, (height - t.height) / 2)
        }
    }
}

/** Секция списка: заголовок над карточкой, пояснение под ней. */
@Composable
internal fun JBSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = JetBoxTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = if (header != null) 22.dp else 28.dp)) {
        if (header != null) {
            Text(
                header,
                style = JBType.headline,
                color = c.muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp).semantics { heading() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(c.surface),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = JBType.footnote,
                color = c.muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
    }
}

/** Строка списка: высота от 52 dp, поля 20 dp; с [onClick] — нажимается вся строка. */
@Composable
internal fun JBListRow(
    onClick: (() -> Unit)? = null,
    indication: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = JetBoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .background(if (pressed && indication) c.line.copy(alpha = 0.6f) else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun JBListDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp, color = JetBoxTheme.colors.line)
}

/** Капсула-кнопка шапки («Закрыть»): стекло, как на iOS 26. */
@Composable
internal fun JBGlassCapsuleButton(title: String, onClick: () -> Unit) {
    val c = JetBoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .jbGlassPanel(cornerRadius = 22.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = JBType.headline, color = if (pressed) c.text.copy(alpha = 0.5f) else c.text)
    }
}

/** Всплывающая подсказка внизу: капсула цвета `Ink`. */
@Composable
private fun JBToast(text: String) {
    val c = JetBoxTheme.colors
    Box(
        Modifier
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(c.ink.copy(alpha = 0.92f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // В тёмной теме `Ink` светлый — текст тёмный, иначе белое на светлом не читается.
        Text(
            text,
            style = JBType.footnote.copy(fontWeight = FontWeight.Medium),
            color = if (c.isDark) c.background else Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

// endregion
// region Превью

@Composable
private fun SettingsPreview(dark: Boolean, advanced: Boolean, toast: String? = null, update: String? = null) = JetBoxTheme(darkTheme = dark) {
    JetBoxSettingsLayout(
        boxName = "Дом на Озёрной",
        versionText = "1.0 (10)",
        updateVersion = update,
        advancedUnlocked = advanced,
        showsAdvancedOff = advanced,
        toast = toast,
        onVersionTap = {},
        onOpenAbout = {},
        onOpenAdvanced = {},
        onAdvancedOff = {},
        onClose = {},
    )
}

@Preview(name = "Настройки — светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SettingsLightPreview() = SettingsPreview(dark = false, advanced = false, toast = "Ещё 3 нажатия…")

@Preview(name = "Настройки — тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() = SettingsPreview(dark = true, advanced = false, toast = "Ещё 3 нажатия…")

@Preview(name = "Настройки, расширенные открыты — светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SettingsAdvancedLightPreview() = SettingsPreview(dark = false, advanced = true)

@Preview(name = "Настройки, расширенные открыты — тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsAdvancedDarkPreview() = SettingsPreview(dark = true, advanced = true)

@Preview(name = "Настройки, есть обновление — светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SettingsUpdateLightPreview() = SettingsPreview(dark = false, advanced = false, update = "1.1")

@Preview(name = "Настройки, есть обновление — тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsUpdateDarkPreview() = SettingsPreview(dark = true, advanced = false, update = "1.1")

@Preview(name = "Настройки без JetBox — светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SettingsNoBoxPreview() = JetBoxTheme(darkTheme = false) {
    JetBoxSettingsLayout(
        boxName = null, versionText = "1.0 (1)", advancedUnlocked = false, showsAdvancedOff = false, toast = null,
        onVersionTap = {}, onOpenAbout = {}, onOpenAdvanced = {}, onAdvancedOff = {}, onClose = {},
    )
}

// endregion

