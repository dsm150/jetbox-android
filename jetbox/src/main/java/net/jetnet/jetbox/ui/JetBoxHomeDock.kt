package net.jetnet.jetbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.ui.icons.JetBoxIcons
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxColors
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxHomeDock.swift`: стеклянная панель дома внизу главного экрана. Имя дома, состояние
 * и под защитой — три живых показателя. Нажатие — лист подробностей.
 */

/** `caption2` iOS (11 pt) — подписи плиток. */
internal val JBCaption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)

/** Цифры одной ширины (`monospacedDigit`). */
internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Подложка плиток и карточек листа — `Color.primary.opacity(0.06)`. */
internal fun JetBoxColors.fill(): Color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.06f)

@Composable
fun JetBoxHomeDock(
    ui: HomeUiState,
    nowMillis: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .jbGlassPanel(28.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(16.dp),
    ) {
        DockHead(ui)
        AnimatedVisibility(
            visible = ui.showsMetrics,
            enter = fadeIn(tween(350, easing = EaseInOut)) + expandVertically(tween(350, easing = EaseInOut)),
            exit = fadeOut(tween(350, easing = EaseInOut)) + shrinkVertically(tween(350, easing = EaseInOut)),
        ) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(ui.uptimeText(nowMillis), stringResource(R.string.jb_dock_uptime))
                Metric(
                    ui.latencyMs?.let { stringResource(R.string.jb_milliseconds, it.toString()) } ?: HomeUiState.DASH,
                    stringResource(R.string.jb_dock_latency),
                )
                Metric(ui.route?.let { stringResource(it.dock) } ?: HomeUiState.DASH, stringResource(R.string.jb_dock_route))
            }
        }
    }
}

@Composable
private fun DockHead(ui: HomeUiState) {
    val c = JetBoxTheme.colors
    val tint by animateColorAsState(
        if (ui.phase == ShieldPhase.OFFLINE) c.warn else c.accent,
        tween(400, easing = EaseInOut),
        label = "dockTint",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(JetBoxIcons.houseFill, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                ui.box?.displayName.orEmpty(),
                style = JBType.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(ui.dockSubtitle.text),
                style = JBType.footnote,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = c.muted,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RowScope.Metric(value: String, caption: String) {
    val c = JetBoxTheme.colors
    Column(
        Modifier
            .weight(1f)
            .background(c.fill(), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        // minimumScaleFactor(0.75) iOS: длинное «Ретранслятор» ужимается, а не обрезается.
        BasicText(
            value,
            style = JBType.callout.copy(fontWeight = FontWeight.SemiBold, color = c.text).tabular(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
        )
        Text(caption, style = JBCaption2, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Панель без JetBox: единственный путь дальше — ссылка из письма (`JetBoxNoBoxDock`). */
@Composable
fun JetBoxNoBoxDock(modifier: Modifier = Modifier) {
    val c = JetBoxTheme.colors
    Column(
        modifier.fillMaxWidth().jbGlassPanel(28.dp).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.jb_home_no_box_title), style = JBType.headline, color = c.text)
        Text(
            stringResource(R.string.jb_home_no_box_body),
            style = JBType.subheadline,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
    }
}
