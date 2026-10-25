package net.jetnet.jetbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.launch
import net.jetnet.jetbox.R
import net.jetnet.jetbox.logic.ByteCountFormat
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxConnectionSheet.swift`: подробности подключения по нажатию на панель дома. Лист по
 * высоте содержимого. Выбор выхода — только если выходов больше одного: у клиента один дом — и выход
 * у него один (решение владельца 24.10.2026).
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JetBoxConnectionSheet(
    ui: HomeUiState,
    nowMillis: Long,
    onSelectExit: (String) -> Unit,
    onClearExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = JetBoxTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showsExitPicker by remember { mutableStateOf(false) }
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.background,
        contentColor = c.text,
        dragHandle = { SheetGrabber() },
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SheetHeader(ui.box?.displayName.orEmpty(), stringResource(R.string.jb_done), close)
            SheetCard {
                if (ui.isConnected) {
                    SheetRow(stringResource(R.string.jb_details_uptime), ui.uptimeText(nowMillis))
                    SheetDivider()
                    SheetRow(
                        stringResource(R.string.jb_details_latency),
                        ui.latencyMs?.let { stringResource(R.string.jb_milliseconds, it.toString()) } ?: HomeUiState.DASH,
                    )
                    SheetDivider()
                    SheetRow(
                        stringResource(R.string.jb_details_route),
                        ui.route?.let { stringResource(it.details) } ?: HomeUiState.DASH,
                    )
                    SheetDivider()
                    SheetRow(stringResource(R.string.jb_details_traffic), trafficText(ui))
                } else {
                    SheetRow(stringResource(R.string.jb_details_route), stringResource(R.string.jb_dock_off))
                }
            }
            if (ui.showsExitChoice) {
                SheetCard {
                    Row(
                        Modifier.clickable(role = Role.Button) { showsExitPicker = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            SheetRow(
                                stringResource(R.string.jb_settings_exit),
                                ui.selectedExit ?: stringResource(R.string.jb_home_exit_none),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = c.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    if (showsExitPicker) {
        JetBoxExitSheet(
            exits = ui.exits,
            selected = ui.selectedExit,
            onSelect = { onSelectExit(it); showsExitPicker = false },
            onClear = { onClearExit(); showsExitPicker = false },
            onDismiss = { showsExitPicker = false },
        )
    }
}

/** «48,2 МБ / 6,1 МБ» — как `ByteCountFormatter(.file)` iOS, в языке приложения. */
@Composable
private fun trafficText(ui: HomeUiState): String {
    val rx = ui.bytesRx ?: return HomeUiState.DASH
    val tx = ui.bytesTx ?: return HomeUiState.DASH
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: java.util.Locale.getDefault()
    return "${ByteCountFormat.format(rx, locale)} / ${ByteCountFormat.format(tx, locale)}"
}

/** Ручка листа как у iOS (`presentationDragIndicator`): короче и тоньше, чем в Material. */
@Composable
private fun SheetGrabber() {
    Box(
        Modifier
            .padding(top = 6.dp, bottom = 12.dp)
            .size(width = 36.dp, height = 5.dp)
            .background(JetBoxTheme.colors.muted.copy(alpha = 0.35f), RoundedCornerShape(percent = 50)),
    )
}

/** Заголовок листа: имя дома по центру, «Готово» справа (стеклянная капсула iOS 26). */
@Composable
internal fun SheetHeader(title: String, done: String, onDone: () -> Unit) {
    val c = JetBoxTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            title,
            style = JBType.headline,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 96.dp),
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(c.fill())
                .clickable(role = Role.Button, onClick = onDone)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(done, style = JBType.headline, color = c.text)
        }
    }
}

@Composable
internal fun SheetCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(JetBoxTheme.colors.fill())
            .padding(horizontal = 16.dp),
        content = content,
    )
}

/** Строка «подпись — значение»; значение не ужимается (замечание iOS: «24 мс» мельче трафика). */
@Composable
private fun SheetRow(title: String, value: String) {
    val c = JetBoxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = JBType.body,
            color = c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = JBType.body.tabular(), color = c.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SheetDivider() = HorizontalDivider(color = JetBoxTheme.colors.line)

/**
 * Смена выхода — порт `JetBoxExitSheet` (iOS). Список выходов, выбранный — с галочкой; последней
 * строкой — «Выход не выбран» (приглушённая, без галочки). Выбор закрывает лист с анимацией.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JetBoxExitSheet(
    exits: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = JetBoxTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    /** Сначала лист уезжает, потом действие (как «Закрыть»). */
    val closeThen: (() -> Unit) -> Unit = { then -> scope.launch { sheetState.hide() }.invokeOnCompletion { then() } }
    val close: () -> Unit = { closeThen(onDismiss) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.background,
        dragHandle = { SheetGrabber() },
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SheetHeader(stringResource(R.string.jb_exit_picker_title), stringResource(R.string.jb_close), close)
            SheetCard {
                if (exits.isEmpty()) {
                    Text(
                        stringResource(R.string.jb_exit_picker_empty),
                        style = JBType.subheadline,
                        color = c.muted,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                exits.forEachIndexed { i, name ->
                    if (i > 0) SheetDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { closeThen { onSelect(name) } }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = JBType.body, color = c.text, modifier = Modifier.weight(1f))
                        if (name == selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = c.accent)
                        }
                    }
                }
                if (exits.isNotEmpty()) {
                    SheetDivider()
                    Text(
                        stringResource(R.string.jb_home_exit_none),
                        style = JBType.body,
                        color = c.muted,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { closeThen(onClear) }.padding(vertical = 14.dp),
                    )
                }
            }
        }
    }
}
