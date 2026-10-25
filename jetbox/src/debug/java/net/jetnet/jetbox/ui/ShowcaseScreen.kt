package net.jetnet.jetbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.ui.icons.JetBoxIcons
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/** Витрина компонентов задачи 10 (только debug): щит во всех фазах, капсулы, карточка, панель. */
@Composable
fun ShowcaseScreen(single: ShieldPhase?) {
    val backdropPhase = single ?: ShieldPhase.ON
    Box(Modifier.fillMaxSize()) {
        JetBoxBackdrop(backdropPhase)
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Header()
            if (single != null) SingleShield(single) else AllPhases()
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        JetBoxLogo(markHeight = 34.dp)
        Spacer(Modifier.weight(1f))
        JBGlassIconButton(JetBoxIcons.gearshape, stringResource(R.string.jb_settings_title)) {}
    }
}

@Composable
private fun statusText(phase: ShieldPhase) = stringResource(
    when (phase) {
        ShieldPhase.OFF -> R.string.jb_home_disconnected
        ShieldPhase.WORKING -> R.string.jb_home_connecting
        ShieldPhase.WORKING_REVERSE -> R.string.jb_home_disconnecting
        ShieldPhase.ON -> R.string.jb_home_connected
        ShieldPhase.OFFLINE -> R.string.jb_home_offline
    },
)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SingleShield(phase: ShieldPhase) {
    val c = JetBoxTheme.colors
    Spacer(Modifier.weight(1f))
    JetBoxShield(phase, Modifier.align(Alignment.CenterHorizontally), label = statusText(phase), onClick = {})
    Spacer(Modifier.height(26.dp))
    Text(
        statusText(phase),
        style = JBType.title2.copy(fontWeight = FontWeight.SemiBold),
        color = if (phase == ShieldPhase.ON) c.accent else c.text,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.weight(1f))
    DockSample(phase)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun AllPhases() {
    val c = JetBoxTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val rows = listOf(
            listOf(ShieldPhase.OFF, ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE),
            listOf(ShieldPhase.ON, ShieldPhase.OFFLINE),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                row.forEach { phase ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                            JetBoxShield(phase, diameter = 92.dp, label = statusText(phase), onClick = {})
                        }
                        Text(phase.name, style = JBType.caption, color = c.muted)
                    }
                }
            }
        }
        JBCard {
            Column {
                Text(JB_BRAND, style = JBType.footnote, color = c.muted)
                Spacer(Modifier.height(6.dp))
                Text("Дом на Озёрной", style = JBType.title3.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = c.line)
                JBRow(stringResource(R.string.jb_settings_exit), "home-ivanov")
            }
        }
        DockSample(ShieldPhase.ON)
        JBGlassButton(stringResource(R.string.jb_home_connect)) {}
        JBGlassButton(stringResource(R.string.jb_home_disconnect), role = JBGlassRole.SECONDARY) {}
    }
}

/** Настоящая панель дома (задача 11) на примерных данных. */
@Composable
private fun DockSample(phase: ShieldPhase) {
    val state = when (phase) {
        ShieldPhase.OFF -> VpnDisplayState.DISCONNECTED
        ShieldPhase.WORKING -> VpnDisplayState.CONNECTING
        ShieldPhase.WORKING_REVERSE -> VpnDisplayState.DISCONNECTING
        ShieldPhase.ON, ShieldPhase.OFFLINE -> VpnDisplayState.CONNECTED
    }
    val ui = HomeUiState(
        box = JetBoxBox("https://netbird.jet-net.net", "home-ivanov", null, "Дом на Озёрной", 0L),
        state = state,
        online = phase != ShieldPhase.OFFLINE,
        connectedSinceMillis = 0L,
        latencyMs = 24,
        route = RouteKind.DIRECT,
    )
    JetBoxHomeDock(ui, nowMillis = 756_000L, onOpen = {})
}
