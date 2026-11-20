package net.jetnet.jetbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.logic.PowerAction
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox

/*
 * Порт `JetBoxSimpleHome.swift`: главный экран стиля «простое» — шестерёнка справа вверху, большой
 * круг по центру (нажатие включает и выключает), внизу свёрнутые «Подробности подключения». Данные и
 * действия те же, что у «техно» ([JetBoxHomeLayout]): [HomeUiState] и кнопки [JetBoxHomeScreen].
 *
 * Отличия от iOS (Android): капсула «Доступна новая версия» (только сборка «файл с сайта») — та же
 * строка, что у «техно», в цветах стиля.
 */

@Composable
fun JetBoxSimpleHomeLayout(
    ui: HomeUiState,
    nowMillis: Long,
    onPower: () -> Unit,
    onSettings: () -> Unit,
    onOpenExits: () -> Unit,
    onUpdate: () -> Unit = {},
    startsExpanded: Boolean = false,
) {
    val phase = ui.phase
    val statusText = stringResource(ui.status.text)
    val circleLabel = when (ui.power) {
        PowerAction.CONNECT -> stringResource(R.string.jb_home_connect)
        PowerAction.DISCONNECT -> stringResource(R.string.jb_home_disconnect)
        PowerAction.NONE -> statusText
    }
    Box(Modifier.fillMaxSize().background(JBSimple.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                JetBoxLogo(markHeight = 34.dp)
                Spacer(Modifier.weight(1f))
                JBSimpleIconButton(Icons.Rounded.Settings, stringResource(R.string.jb_settings_title), onSettings)
            }

            Spacer(Modifier.weight(1f).heightIn(min = 8.dp))

            JBSimpleCircle(
                phase = phase,
                label = circleLabel,
                onClick = if (ui.power == PowerAction.NONE) null else onPower,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            SimpleStatus(ui, statusText)

            Spacer(Modifier.weight(1f).heightIn(min = 16.dp))

            AnimatedVisibility(
                visible = ui.update != null,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                val shown = androidx.compose.runtime.remember { arrayOf("") }
                ui.update?.let { shown[0] = it.versionName }
                SimpleUpdateCapsule(shown[0], onUpdate)
            }

            Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 12.dp)) {
                SimpleBottom(ui, nowMillis, onOpenExits, startsExpanded)
            }
        }
    }
}

@Composable
private fun SimpleStatus(ui: HomeUiState, statusText: String) {
    val phase = ui.phase
    val on = phase == ShieldPhase.ON
    val color by animateColorAsState(if (on) JBSimple.accent else JBSimple.ink, tween(300), label = "simpleStatus")
    val subtitle = when (phase) {
        ShieldPhase.OFF -> if (ui.hasBox) stringResource(R.string.jb_simple_tap_to_connect) else ""
        ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> stringResource(R.string.jb_simple_wait)
        ShieldPhase.ON -> ui.box?.displayName.orEmpty()
        ShieldPhase.OFFLINE -> stringResource(R.string.jb_simple_offline_hint)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (on) {
                Box(Modifier.size(32.dp).background(JBSimple.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(statusText, style = JBSimple.font(30, JBSimple.black), color = color, textAlign = TextAlign.Center)
        }
        if (on) JBSimpleTrafficLine()
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = JBSimple.secondary,
                color = JBSimple.muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SimpleBottom(ui: HomeUiState, nowMillis: Long, onOpenExits: () -> Unit, startsExpanded: Boolean) {
    when {
        !ui.hasBox -> Column(
            Modifier.fillMaxWidth().background(JBSimple.card, RoundedCornerShape(26.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.jb_home_no_box_title), style = JBSimple.font(22, JBSimple.black), color = JBSimple.ink)
            Text(
                stringResource(R.string.jb_home_no_box_body),
                style = JBSimple.secondary,
                color = JBSimple.muted,
                textAlign = TextAlign.Center,
            )
        }
        ui.showsMetrics -> SimpleDetails(ui, nowMillis, onOpenExits, startsExpanded)
        // Место под подробности держим, чтобы круг не прыгал.
        else -> Spacer(Modifier.height(52.dp))
    }
}

@Composable
private fun SimpleDetails(ui: HomeUiState, nowMillis: Long, onOpenExits: () -> Unit, startsExpanded: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(startsExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "detailsChevron")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(ui.uptimeText(nowMillis), stringResource(R.string.jb_dock_uptime), 1f)
                    Chip(
                        ui.latencyMs?.let { stringResource(R.string.jb_milliseconds, it.toString()) } ?: HomeUiState.DASH,
                        stringResource(R.string.jb_dock_latency),
                        0.9f,
                    )
                    // «Ретранслятор» длиннее прочих — плитке соединения чуть больше места.
                    Chip(ui.route?.let { stringResource(it.dock) } ?: HomeUiState.DASH, stringResource(R.string.jb_dock_route), 1.3f)
                }
                if (ui.showsExitChoice) {
                    JBSimpleCard(Modifier.fillMaxWidth()) {
                        JBSimpleRow(
                            title = stringResource(R.string.jb_settings_exit),
                            value = ui.selectedExit ?: stringResource(R.string.jb_home_exit_none),
                            chevron = true,
                            onClick = onOpenExits,
                        )
                    }
                }
            }
        }

        val state = stringResource(if (expanded) R.string.jb_state_expanded else R.string.jb_state_collapsed)
        Row(
            Modifier
                .heightIn(min = 52.dp)
                .jbSimplePress { expanded = !expanded }
                .background(JBSimple.card, RoundedCornerShape(50))
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.jb_simple_details), style = JBSimple.font(19, JBSimple.heavy), color = JBSimple.muted)
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = JBSimple.muted,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

@Composable
private fun RowScope.Chip(value: String, caption: String, weight: Float) {
    Column(
        Modifier
            .weight(weight)
            .background(JBSimple.card, RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(
            value,
            style = JBSimple.font(21, JBSimple.heavy).copy(color = JBSimple.ink, textAlign = TextAlign.Center).tabular(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = 21.sp, stepSize = 0.5.sp),
        )
        Text(caption, style = JBSimple.font(17, JBSimple.semibold), color = JBSimple.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** «Доступна новая версия 1.1 ›» в цветах стиля: пилюля карточки с фиолетовой точкой. */
@Composable
private fun SimpleUpdateCapsule(versionName: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .heightIn(min = 48.dp)
            .jbSimplePress(onClick = onClick)
            .background(JBSimple.card, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(JBSimple.accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.jb_update_available, versionName),
            style = JBSimple.font(17, JBSimple.bold),
            color = JBSimple.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// region Выбор выхода

/**
 * Список выходов в стиле «простое» (`JetBoxSimpleExitList`): на весь экран, выбранный — с галочкой;
 * внизу «Выход не выбран» ([onSelect] с `null`). Данные приходят параметрами.
 */
@Composable
fun JetBoxSimpleExitList(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(JBSimple.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JBSimpleHeader(stringResource(R.string.jb_exit_picker_title), stringResource(R.string.jb_close), onClose)
            Spacer(Modifier.height(6.dp))
            if (options.isEmpty()) {
                Text(stringResource(R.string.jb_exit_picker_empty), style = JBSimple.secondary, color = JBSimple.muted)
            } else {
                Text(
                    stringResource(R.string.jb_exit_picker_message),
                    style = JBSimple.secondary,
                    color = JBSimple.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                options.forEach { name ->
                    JBSimpleExitOption(name, selected = name == selected, showsChevron = false) { onSelect(name) }
                }
                JBSimpleTextButton(
                    stringResource(R.string.jb_home_exit_none),
                    color = JBSimple.muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { onSelect(null) }
            }
        }
    }
}

// endregion
// region Превью

private fun simplePreviewUi(state: VpnDisplayState) = HomeUiState(
    box = JetBoxBox("https://netbird.jet-net.net", "home-ivanov", null, "Дом на Озёрной", 0L),
    state = state,
    connectedSinceMillis = if (state == VpnDisplayState.CONNECTED) 0L else null,
    latencyMs = 24,
    route = RouteKind.DIRECT,
    exits = listOf("dacha-router", "home-ivanov"),
    selectedExit = "home-ivanov",
)

@Preview(name = "Простое — выкл", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SimpleHomeOffPreview() =
    JetBoxSimpleHomeLayout(simplePreviewUi(VpnDisplayState.DISCONNECTED), 756_000L, {}, {}, {})

@Preview(name = "Простое — подключено, подробности", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun SimpleHomeOnPreview() =
    JetBoxSimpleHomeLayout(simplePreviewUi(VpnDisplayState.CONNECTED), 756_000L, {}, {}, {}, startsExpanded = true)

// endregion
