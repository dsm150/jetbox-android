package net.jetnet.jetbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.logic.JetBoxFailure
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.ui.icons.JetBoxIcons

/*
 * Порт `JetBoxSimpleFlow.swift`: экран по ссылке из Кабинета в стиле «простое». Шаги и кнопки те же,
 * что у «техно» ([JetBoxFlowLayout], [JetBoxFlowActions]); меняется только вид. Системный диалог VPN,
 * «Назад» и переходы между шагами — в [JetBoxFlowScreen], общие для обоих стилей.
 *
 * Отличия от iOS (Android): шаг [Step.NeedsVpnConsent] — круг «в работе» с «Подключаем…»; отказ
 * [JetBoxFailure.VpnPermissionDenied] — свой заголовок и текст (как у «техно»).
 */

@Composable
fun JetBoxSimpleFlowLayout(step: Step, actions: JetBoxFlowActions, detailsInitiallyExpanded: Boolean = false) {
    Box(Modifier.fillMaxSize().background(JBSimple.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                    SimpleFlowContent(step, actions, detailsInitiallyExpanded)
                }
            }
            Box(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                SimpleFlowFooter(step, actions)
            }
        }
    }
}

@Composable
private fun SimpleFlowContent(step: Step, actions: JetBoxFlowActions, detailsInitiallyExpanded: Boolean) {
    when (step) {
        Step.Idle -> Unit

        is Step.Ready -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(
                painterResource(R.drawable.jb_simple_connect),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)),
            )
            JetBoxLogo(markHeight = 30.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.jb_ready_caption), style = JBSimple.secondary, color = JBSimple.muted)
                Text(
                    step.payload.displayName,
                    style = JBSimple.font(30, JBSimple.black),
                    color = JBSimple.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(stringResource(R.string.jb_ready_hint), style = JBSimple.secondary, color = JBSimple.muted)
            // Ключ целиком не показываем: только хвост для сверки с поддержкой.
            JBSimpleCard {
                JBSimpleRow(stringResource(R.string.jb_ready_key_label), step.payload.maskedKey)
            }
            JBSimplePrimaryButton(stringResource(R.string.jb_ready_button), Modifier.padding(top = 2.dp), actions.onConnect)
            SimplePrivacyNotice(actions.onOpenUrl)
        }

        is Step.ConfirmSwitch -> Centered(
            icon = JetBoxIcons.arrowTriangle2Circlepath,
            title = stringResource(R.string.jb_switch_title),
            message = stringResource(R.string.jb_switch_message, step.current, step.payload.displayName),
        ) {
            JBSimplePrimaryButton(stringResource(R.string.jb_switch_confirm), Modifier.padding(top = 4.dp), actions.onConfirmSwitch)
        }

        is Step.Registering -> Progress(stringResource(R.string.jb_registering))
        is Step.Connecting, Step.NeedsVpnConsent -> Progress(stringResource(R.string.jb_connecting))
        is Step.SelectingExit -> Progress(stringResource(R.string.jb_selecting_exit))

        is Step.ChooseExit -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.jb_exit_picker_title),
                style = JBSimple.font(30, JBSimple.black),
                color = JBSimple.ink,
                modifier = Modifier.padding(top = 24.dp).semantics { heading() },
            )
            Text(
                stringResource(if (step.options.isEmpty()) R.string.jb_exit_picker_empty else R.string.jb_exit_picker_message),
                style = JBSimple.secondary,
                color = JBSimple.muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            step.options.forEach { name ->
                JBSimpleExitOption(name, selected = false, showsChevron = true) { actions.onChooseExit(name) }
            }
            JBSimpleTextButton(
                stringResource(R.string.jb_exit_picker_skip),
                color = JBSimple.muted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { actions.onChooseExit(null) }
        }

        is Step.Protected -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val title = stringResource(R.string.jb_protected_title)
            JBSimpleCircle(ShieldPhase.ON, Modifier.padding(top = 40.dp), diameter = 200.dp, label = title)
            // Без отдельной галочки: длинный заголовок переносится, а галочка уже есть на значке круга.
            Text(
                title,
                style = JBSimple.font(28, JBSimple.black),
                color = JBSimple.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            JBSimpleTrafficLine()
            Text(
                step.exitNode?.let { stringResource(R.string.jb_protected_exit, it) }
                    ?: stringResource(R.string.jb_protected_no_exit),
                style = JBSimple.secondary,
                color = JBSimple.muted,
                textAlign = TextAlign.Center,
            )
        }

        is Step.Failed -> SimpleFailure(step.failure, detailsInitiallyExpanded)
    }
}

@Composable
private fun Progress(title: String) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        JBSimpleCircle(ShieldPhase.WORKING, Modifier.padding(top = 60.dp), diameter = 200.dp, label = title)
        Text(title, style = JBSimple.font(28, JBSimple.black), color = JBSimple.ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.jb_simple_wait), style = JBSimple.secondary, color = JBSimple.muted)
    }
}

@Composable
private fun Centered(icon: ImageVector, title: String, message: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier.padding(top = 60.dp).size(150.dp).background(JBSimple.accentTint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = JBSimple.accent, modifier = Modifier.size(64.dp))
        }
        Text(
            title,
            style = JBSimple.font(30, JBSimple.black),
            color = JBSimple.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(message, style = JBSimple.secondary, color = JBSimple.muted, textAlign = TextAlign.Center)
        action()
    }
}

/** Что уходит на сервер — сразу под кнопкой подключения, до любого действия. */
@Composable
private fun SimplePrivacyNotice(onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.jb_privacy_notice),
            style = JBSimple.font(17, JBSimple.semibold),
            color = JBSimple.muted,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.heightIn(min = 48.dp).jbSimplePress { onOpenUrl(JetBoxConfig.PRIVACY_POLICY_URL) }.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.jb_privacy_more), style = JBSimple.font(17, JBSimple.heavy), color = JBSimple.accent)
        }
    }
}

@Composable
private fun SimpleFlowFooter(step: Step, actions: JetBoxFlowActions) {
    when (step) {
        is Step.Protected -> JBSimplePrimaryButton(stringResource(R.string.jb_done), onClick = actions.onDismiss)

        is Step.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (step.failure.canRetry) JBSimplePrimaryButton(stringResource(R.string.jb_retry), onClick = actions.onRetry)
            JBSimpleTextButton(stringResource(R.string.jb_close), color = JBSimple.muted, onClick = actions.onDismiss)
        }

        is Step.Ready, is Step.ConfirmSwitch ->
            JBSimpleTextButton(stringResource(R.string.jb_cancel), color = JBSimple.muted, onClick = actions.onDismiss)

        else -> Unit
    }
}

/** Отказ: знак в красном круге, заголовок, объяснение; техническая строка — под «Подробностями». */
@Composable
private fun SimpleFailure(failure: JetBoxFailure, detailsInitiallyExpanded: Boolean) {
    var showsDetails by rememberSaveable(failure.details) { mutableStateOf(detailsInitiallyExpanded) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier.padding(top = 60.dp).size(150.dp).background(JBSimple.error.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = JBSimple.error, modifier = Modifier.size(64.dp))
        }
        Text(
            stringResource(failure.titleRes),
            style = JBSimple.font(30, JBSimple.black),
            color = JBSimple.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(failure.messageRes), style = JBSimple.secondary, color = JBSimple.muted, textAlign = TextAlign.Center)

        val details = failure.details
        if (!details.isNullOrEmpty()) {
            val state = stringResource(if (showsDetails) R.string.jb_state_expanded else R.string.jb_state_collapsed)
            val rotation by animateFloatAsState(if (showsDetails) 180f else 0f, label = "simpleDisclosure")
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .jbSimplePress { showsDetails = !showsDetails }
                        .semantics { stateDescription = state },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.jb_err_details),
                        style = JBSimple.font(19, JBSimple.heavy),
                        color = JBSimple.accent,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = JBSimple.accent,
                        modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = rotation },
                    )
                }
                AnimatedVisibility(visible = showsDetails, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    SelectionContainer {
                        Text(
                            details,
                            style = JBSimple.font(17, JBSimple.semibold).copy(fontFamily = FontFamily.Monospace),
                            color = JBSimple.muted,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// region Превью

private class SimpleFlowStepProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<Step> {
    override val values = PreviewFlowSteps.values.asSequence()
}

@Preview(name = "Простое — экран по ссылке", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SimpleFlowPreview(@PreviewParameter(SimpleFlowStepProvider::class) step: Step) =
    JetBoxSimpleFlowLayout(step, JetBoxFlowActions(), detailsInitiallyExpanded = true)

// endregion
