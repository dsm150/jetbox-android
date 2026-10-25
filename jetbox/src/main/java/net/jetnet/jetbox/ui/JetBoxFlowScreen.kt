package net.jetnet.jetbox.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.JetBoxOnboarding
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.logic.JetBoxFailure
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.ui.icons.JetBoxIcons
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxFlowView.swift`: экран, который клиент видит после перехода по ссылке из Кабинета, —
 * от «вот ваш JetBox, нажмите одну кнопку» до «вы под защитой». Перекрывает всё остальное, пока
 * сценарий не закончится: живой фон, щит на шагах и в успехе.
 *
 * Отличия от iOS (Android):
 * - шаг [Step.NeedsVpnConsent]: экран сам открывает системный диалог VPN (один раз на вход в шаг),
 *   пока он открыт — щит «в работе» с подписью «Подключаем…»;
 * - отказ [JetBoxFailure.VpnPermissionDenied] — свои заголовок и текст (в iOS это часть tunnelFailed);
 * - системная «Назад» на шагах с кнопкой «Отмена»/«Закрыть»/«Готово» закрывает экран, на шагах
 *   прогресса сворачивает приложение (сценарий продолжается), на выборе выхода — ничего не делает.
 */

/** Кнопки экрана по ссылке — методы [JetBoxOnboarding]. */
class JetBoxFlowActions(
    val onConnect: () -> Unit = {},
    val onConfirmSwitch: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** `null` — «Пока не выбирать». */
    val onChooseExit: (String?) -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
)

/**
 * Экран по ссылке со сценарием: шаги — из [JetBoxOnboarding.step], согласие VPN — системным
 * диалогом. [vpnConsentIntent] — `EngineApi.needsVpnConsent()`.
 */
@Composable
fun JetBoxFlowScreen(onboarding: JetBoxOnboarding, vpnConsentIntent: () -> Intent?) {
    val step by onboarding.step.collectAsStateWithLifecycle()
    val openUrl = rememberUrlOpener()
    val actions = remember(onboarding, openUrl) {
        JetBoxFlowActions(
            onConnect = onboarding::connect,
            onConfirmSwitch = onboarding::confirmSwitch,
            onRetry = onboarding::retry,
            onChooseExit = onboarding::chooseExit,
            onDismiss = onboarding::dismiss,
            onOpenUrl = openUrl,
        )
    }
    JetBoxFlowScreen(
        step = step,
        actions = actions,
        vpnConsentIntent = vpnConsentIntent,
        onVpnConsent = onboarding::onVpnConsent,
    )
}

/**
 * Экран по ссылке без привязки к сценарию: шаг и кнопки приходят параметрами. На шаге
 * [Step.NeedsVpnConsent] открывает системный диалог VPN; ответ уходит в [onVpnConsent]
 * (отказ сценарий превращает в `Failed(VpnPermissionDenied)`, а «Попробовать снова» снова приводит сюда).
 */
@Composable
fun JetBoxFlowScreen(
    step: Step,
    actions: JetBoxFlowActions,
    vpnConsentIntent: () -> Intent?,
    onVpnConsent: (Boolean) -> Unit,
) {
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onVpnConsent(result.resultCode == Activity.RESULT_OK)
    }
    // Диалог — один раз на вход в шаг: поворот экрана при открытом диалоге не открывает второй.
    var consentLaunched by rememberSaveable { mutableStateOf(false) }
    val needsConsent = step == Step.NeedsVpnConsent
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(needsConsent) {
        if (!needsConsent) {
            consentLaunched = false
            return@LaunchedEffect
        }
        if (consentLaunched) return@LaunchedEffect
        // Шаг мог наступить, пока окно свёрнуто («Назад» во время регистрации): из фона системный
        // диалог не покажется, а ответ потерялся бы. Ждём возвращения окна (как NotificationPermissionEffect);
        // отметка «запущен» — только когда диалог действительно ушёл.
        lifecycle.withResumed {
            val intent = vpnConsentIntent()
            if (intent == null) {
                consentLaunched = true
                onVpnConsent(true)
                return@withResumed
            }
            try {
                consent.launch(intent)
                consentLaunched = true
            } catch (e: ActivityNotFoundException) {
                onVpnConsent(false)
            }
        }
    }

    // «Назад» на шагах прогресса сворачивает приложение в фон, а не обрывает сценарий: регистрация и
    // подключение идут дальше (как «домой»). Выбор выхода — решение клиента, закрывать его жестом нельзя.
    val context = LocalContext.current
    BackHandler(enabled = step != Step.Idle) {
        when (step) {
            is Step.Ready, is Step.ConfirmSwitch, is Step.Failed, is Step.Protected -> actions.onDismiss()
            is Step.Registering, is Step.Connecting, is Step.SelectingExit, Step.NeedsVpnConsent ->
                (context as? Activity)?.moveTaskToBack(true)
            else -> Unit
        }
    }

    JetBoxFlowLayout(step, actions)
}

/** Сам экран (`JetBoxFlowView.body`): фон, щит или логотип, содержимое шага, нижняя кнопка. */
@Composable
fun JetBoxFlowLayout(step: Step, actions: JetBoxFlowActions, detailsInitiallyExpanded: Boolean = false) {
    val phase = step.shieldPhase
    Box(Modifier.fillMaxSize()) {
        JetBoxBackdrop(phase)
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Содержимое — по центру свободного места над нижней кнопкой; не влезло — прокручивается.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewport)
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Один щит на все шаги прогресса и успех: переход «крутится → под защитой»
                    // празднуется тем же видом.
                    if (step.showsShield) {
                        JetBoxShield(
                            phase = phase,
                            diameter = 156.dp,
                            label = if (phase == ShieldPhase.ON) stringResource(R.string.jb_protected_title) else stepTitle(step),
                            celebratesOnAppear = true,
                            modifier = Modifier.padding(bottom = 28.dp),
                        )
                    } else {
                        JetBoxLogo(markHeight = 52.dp, modifier = Modifier.padding(bottom = 32.dp))
                    }
                    Box(Modifier.widthIn(max = 420.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        FlowContent(step, actions, detailsInitiallyExpanded)
                    }
                }
            }
            Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.Center) {
                FlowFooter(step, actions)
            }
        }
    }
}

private val Step.showsShield: Boolean
    get() = when (this) {
        is Step.Registering, is Step.Connecting, is Step.SelectingExit, Step.NeedsVpnConsent, is Step.Protected -> true
        else -> false
    }

private val Step.shieldPhase: ShieldPhase
    get() = when (this) {
        is Step.Registering, is Step.Connecting, is Step.SelectingExit, Step.NeedsVpnConsent -> ShieldPhase.WORKING
        is Step.Protected -> ShieldPhase.ON
        is Step.Failed -> ShieldPhase.OFFLINE
        else -> ShieldPhase.OFF
    }

@Composable
private fun stepTitle(step: Step): String = when (step) {
    is Step.Registering -> stringResource(R.string.jb_registering)
    is Step.Connecting, Step.NeedsVpnConsent -> stringResource(R.string.jb_connecting)
    is Step.SelectingExit -> stringResource(R.string.jb_selecting_exit)
    else -> ""
}

// region Содержимое по шагам

@Composable
private fun FlowContent(step: Step, actions: JetBoxFlowActions, detailsInitiallyExpanded: Boolean) {
    val c = JetBoxTheme.colors
    when (step) {
        Step.Idle -> Unit

        is Step.Ready -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BoxCard(step.payload)
            Text(
                stringResource(R.string.jb_ready_hint),
                style = JBType.footnote,
                color = c.muted,
                textAlign = TextAlign.Center,
            )
            JBGlassButton(stringResource(R.string.jb_ready_button), onClick = actions.onConnect)
            PrivacyNotice(actions.onOpenUrl)
        }

        is Step.ConfirmSwitch -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Icon(JetBoxIcons.arrowTriangle2Circlepath, contentDescription = null, tint = c.warn, modifier = Modifier.size(52.dp))
            Text(
                stringResource(R.string.jb_switch_title),
                style = JBType.title3.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.jb_switch_message, step.current, step.payload.displayName),
                style = JBType.subheadline,
                color = c.muted,
                textAlign = TextAlign.Center,
            )
            JBGlassButton(stringResource(R.string.jb_switch_confirm), onClick = actions.onConfirmSwitch)
        }

        is Step.Registering, is Step.Connecting, is Step.SelectingExit, Step.NeedsVpnConsent ->
            Text(stepTitle(step), style = JBType.body, color = c.text, textAlign = TextAlign.Center)

        is Step.ChooseExit -> ExitPicker(step.options, actions.onChooseExit)

        is Step.Protected -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                stringResource(R.string.jb_protected_title),
                style = JBType.title2.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                step.exitNode?.let { stringResource(R.string.jb_protected_exit, it) }
                    ?: stringResource(R.string.jb_protected_no_exit),
                style = JBType.subheadline,
                color = c.muted,
                textAlign = TextAlign.Center,
            )
        }

        is Step.Failed -> FailureView(step.failure, detailsInitiallyExpanded)
    }
}

/**
 * Карточка «Ваш JetBox». Адрес сервера управления не показываем (как iOS): клиент подключается к
 * своему дому, а «сервер» сбивал бы с толку. Ключ целиком не показываем нигде — только хвост.
 */
@Composable
private fun BoxCard(payload: JetBoxLinkPayload) {
    val c = JetBoxTheme.colors
    JBCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.jb_ready_caption), style = JBType.footnote, color = c.muted)
            Text(
                payload.displayName,
                style = JBType.title3.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(thickness = 1.dp, color = c.line)
            JBRow(stringResource(R.string.jb_ready_key_label), payload.maskedKey)
        }
    }
}

/** Что уходит на сервер и что не уходит — под самой кнопкой подключения, до любого действия. */
@Composable
private fun PrivacyNotice(onOpenUrl: (String) -> Unit) {
    val c = JetBoxTheme.colors
    // iOS: текст и ссылка на расстоянии 6 pt. Зона касания ссылки — 48 dp, и пустое поле над надписью
    // втягиваем в текст отрицательным интервалом (кнопка рисуется позже и перекрывает его, касания целы).
    val textStyle = JBType.caption.copy(fontWeight = FontWeight.SemiBold)
    val emptyAbove = with(LocalDensity.current) { ((48.dp - textStyle.lineHeight.toDp()) / 2) }
    val pull = (emptyAbove - 6.dp).coerceAtLeast(0.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(-pull)) {
        Text(
            stringResource(R.string.jb_privacy_notice),
            style = JBType.caption,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        JBTextButton(
            stringResource(R.string.jb_privacy_more),
            color = c.accent,
            style = textStyle,
        ) { onOpenUrl(JetBoxConfig.PRIVACY_POLICY_URL) }
    }
}

// endregion
// region Нижняя кнопка

@Composable
private fun FlowFooter(step: Step, actions: JetBoxFlowActions) {
    when (step) {
        is Step.Protected ->
            JBGlassButton(stringResource(R.string.jb_done), role = JBGlassRole.SECONDARY, onClick = actions.onDismiss)

        is Step.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (step.failure.canRetry) {
                JBGlassButton(stringResource(R.string.jb_retry), onClick = actions.onRetry)
            }
            JBTextButton(stringResource(R.string.jb_close), onClick = actions.onDismiss)
        }

        is Step.Ready, is Step.ConfirmSwitch ->
            JBTextButton(stringResource(R.string.jb_cancel), onClick = actions.onDismiss)

        else -> Unit
    }
}

// endregion
// region Отказ

/** Заголовок отказа (`JetBoxFailure.title` iOS). */
private val JetBoxFailure.titleRes: Int
    get() = when (this) {
        JetBoxFailure.BrokenLink -> R.string.jb_err_broken_title
        JetBoxFailure.Offline -> R.string.jb_err_offline_title
        is JetBoxFailure.ManagementUnreachable -> R.string.jb_err_unreachable_title
        is JetBoxFailure.KeyRejected -> R.string.jb_err_key_title
        JetBoxFailure.TunnelFailed -> R.string.jb_err_tunnel_title
        JetBoxFailure.VpnPermissionDenied -> R.string.jb_err_vpn_permission_title
        is JetBoxFailure.Other -> R.string.jb_err_generic_title
    }

/** Объяснение отказа (`JetBoxFailure.message` iOS). */
private val JetBoxFailure.messageRes: Int
    get() = when (this) {
        JetBoxFailure.BrokenLink -> R.string.jb_err_broken_body
        JetBoxFailure.Offline -> R.string.jb_err_offline_body
        is JetBoxFailure.ManagementUnreachable -> R.string.jb_err_unreachable_body
        is JetBoxFailure.KeyRejected -> R.string.jb_err_key_body
        JetBoxFailure.TunnelFailed -> R.string.jb_err_tunnel_timeout_body
        JetBoxFailure.VpnPermissionDenied -> R.string.jb_err_tunnel_body
        is JetBoxFailure.Other -> R.string.jb_err_generic_body
    }

/**
 * Экран отказа (`JetBoxFailureView`). Техническая строка ядра нужна поддержке, а не клиенту — она
 * спрятана под раскрывающимися «Подробностями» (по-английски, как от ядра) и выделяется для копирования.
 */
@Composable
private fun FailureView(failure: JetBoxFailure, detailsInitiallyExpanded: Boolean) {
    val c = JetBoxTheme.colors
    var showsDetails by rememberSaveable(failure.details) { mutableStateOf(detailsInitiallyExpanded) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = c.warn, modifier = Modifier.size(56.dp))
        Text(
            stringResource(failure.titleRes),
            style = JBType.title3.copy(fontWeight = FontWeight.SemiBold),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(failure.messageRes), style = JBType.subheadline, color = c.muted, textAlign = TextAlign.Center)

        val details = failure.details
        if (!details.isNullOrEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                DisclosureRow(stringResource(R.string.jb_err_details), expanded = showsDetails) { showsDetails = !showsDetails }
                AnimatedVisibility(
                    visible = showsDetails,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SelectionContainer {
                        Text(
                            details,
                            style = JBType.caption.copy(fontFamily = FontFamily.Monospace),
                            color = c.muted,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Заголовок `DisclosureGroup`: подпись слева, шеврон справа поворачивается вниз при раскрытии. */
@Composable
private fun DisclosureRow(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = JetBoxTheme.colors
    val state = stringResource(if (expanded) R.string.jb_state_expanded else R.string.jb_state_collapsed)
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "disclosure")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = JBType.footnote, color = c.accent, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = c.text,
            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

// endregion
// region Выбор выхода

/** `JetBoxExitPickerView`: список выходов (до 260 dp, прокрутка) + «Пока не выбирать». */
@Composable
private fun ExitPicker(options: List<String>, onChoose: (String?) -> Unit) {
    val c = JetBoxTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(JetBoxIcons.point3ConnectedTriangle, contentDescription = null, tint = c.accent, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.jb_exit_picker_title),
            style = JBType.title3.copy(fontWeight = FontWeight.SemiBold),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (options.isEmpty()) R.string.jb_exit_picker_empty else R.string.jb_exit_picker_message),
            style = JBType.subheadline,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        if (options.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                options.forEach { name -> ExitOptionRow(name) { onChoose(name) } }
            }
        }
        JBTextButton(stringResource(R.string.jb_exit_picker_skip)) { onChoose(null) }
    }
}

@Composable
private fun ExitOptionRow(name: String, onClick: () -> Unit) {
    val c = JetBoxTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = JBType.subheadline.copy(fontWeight = FontWeight.Medium),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
    }
}

// endregion
// region Превью: каждый шаг, светлая и тёмная

/** Пример ссылки для превью и витрины: ключ — заведомо не настоящий. */
internal val PreviewPayload = JetBoxLinkPayload(
    setupKey = "00000000-0000-4000-8000-000000005555",
    managementURL = "https://netbird.jet-net.net",
    exitNodeName = "home-ivanov",
    boxName = "Дом на Озёрной",
)

/** Все шаги сценария по имени — для превью и debug-витрины (`--es flow <имя>`). */
internal val PreviewFlowSteps: Map<String, Step> = linkedMapOf(
    "READY" to Step.Ready(PreviewPayload),
    "SWITCH" to Step.ConfirmSwitch(PreviewPayload.copy(boxName = "Дача"), current = "Офис на Тверской"),
    "REGISTERING" to Step.Registering(PreviewPayload),
    "CONNECTING" to Step.Connecting(PreviewPayload),
    "CONSENT" to Step.NeedsVpnConsent,
    "SELECTING_EXIT" to Step.SelectingExit(PreviewPayload),
    "CHOOSE_EXIT" to Step.ChooseExit(PreviewPayload, listOf("home-ivanov", "dacha-router", "office-tverskaya")),
    "PROTECTED" to Step.Protected("home-ivanov"),
    "PROTECTED_NO_EXIT" to Step.Protected(null),
    "FAILED_KEY" to Step.Failed(
        JetBoxFailure.KeyRejected("rpc error: code = PermissionDenied desc = invalid setup key: key expired"),
    ),
    "FAILED_UNREACHABLE" to Step.Failed(
        JetBoxFailure.ManagementUnreachable("rpc error: code = Unavailable desc = connection error: dial tcp: i/o timeout"),
    ),
    "FAILED_BROKEN" to Step.Failed(JetBoxFailure.BrokenLink),
    "FAILED_VPN" to Step.Failed(JetBoxFailure.VpnPermissionDenied),
)

private class FlowStepProvider : PreviewParameterProvider<Step> {
    override val values = PreviewFlowSteps.values.asSequence()
}

@Preview(name = "Экран по ссылке — светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun FlowLightPreview(@PreviewParameter(FlowStepProvider::class) step: Step) =
    JetBoxTheme(darkTheme = false) { JetBoxFlowLayout(step, JetBoxFlowActions(), detailsInitiallyExpanded = true) }

@Preview(name = "Экран по ссылке — тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FlowDarkPreview(@PreviewParameter(FlowStepProvider::class) step: Step) =
    JetBoxTheme(darkTheme = true) { JetBoxFlowLayout(step, JetBoxFlowActions(), detailsInitiallyExpanded = true) }

// endregion
