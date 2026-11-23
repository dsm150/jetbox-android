package net.jetnet.jetbox.ui

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.widget.JetBoxWidget
import net.jetnet.jetbox.widget.WidgetLayout

/**
 * Предпросмотр виджета (только debug, `--es widget OFF|ON`): настоящие RemoteViews виджета во всех
 * трёх разметках. Для снимков без установки виджета на рабочий стол.
 */
@Composable
fun ShowcaseWidget(state: VpnDisplayState) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF5B6F86)).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Sample("2×2", WidgetLayout.SQUARE, state, 170, 170)
        Sample("4×2", WidgetLayout.WIDE, state, 340, 150)
        Sample("4×1", WidgetLayout.COMPACT, state, 340, 64)
    }
}

@Composable
private fun Sample(title: String, layout: WidgetLayout, state: VpnDisplayState, w: Int, h: Int) {
    Text(title, color = Color.White, fontSize = 17.sp)
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).also { parent ->
                parent.addView(JetBoxWidget.preview(ctx, layout, state).apply(ctx, parent))
            }
        },
        modifier = Modifier.size(w.dp, h.dp),
    )
}
