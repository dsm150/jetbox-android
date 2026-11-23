package net.jetnet.jetbox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import net.jetnet.jetbox.JetBoxProcess
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.JetBoxEngine
import net.jetnet.jetbox.logic.VpnDisplayState

/**
 * Виджет на рабочем столе — `NetBirdWidget` (iOS, `NetBirdWidgetExtension`): знак JB, «JetBox»,
 * состояние с цветной точкой (`StatusIndicator`) и переключатель-капсула (`pillLabel`), касание —
 * вкл/выкл ([WidgetToggleReceiver]). RemoteViews, без Glance.
 *
 * Рисуется из последнего известного состояния туннеля (prefs `jetbox`, ключ `jetbox.lastState`):
 * его пишет [updateAll], которую зовёт `AndroidEngine` при каждой смене `state`. Процесс мог умереть
 * вместе с туннелем, и сохранённое устарело — поэтому [onUpdate] ещё и поднимает движок: тот
 * привяжется к сервису и перерисует виджет настоящим состоянием.
 */
class JetBoxWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = lastState(context)
        appWidgetIds.forEach { id ->
            manager.updateAppWidget(id, views(context, state, manager.getAppWidgetOptions(id)))
        }
        JetBoxEngine.get(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        // До API 31 разметку под размер выбираем сами; на 31+ это делает карта размеров.
        manager.updateAppWidget(appWidgetId, views(context, lastState(context), newOptions))
    }

    companion object {
        private const val TAG = "JetBoxWidget"

        /** Сохранить состояние и перерисовать все виджеты. Из любого потока. */
        fun updateAll(context: Context, state: VpnDisplayState) {
            val app = context.applicationContext ?: context
            val prefs = JetBoxProcess.prefs(app)
            val encoded = WidgetStateCodec.encode(state)
            if (prefs.getString(WidgetStateCodec.KEY) != encoded) prefs.putString(WidgetStateCodec.KEY, encoded)
            try {
                val manager = AppWidgetManager.getInstance(app) ?: return
                manager.getAppWidgetIds(ComponentName(app, JetBoxWidget::class.java)).forEach { id ->
                    manager.updateAppWidget(id, views(app, state, manager.getAppWidgetOptions(id)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${e.message}")
            }
        }

        /** Последнее известное состояние туннеля; нет записи — «Отключено». */
        fun lastState(context: Context): VpnDisplayState =
            WidgetStateCodec.decode(JetBoxProcess.prefs(context).getString(WidgetStateCodec.KEY))

        fun hasWidgets(context: Context): Boolean = try {
            val app = context.applicationContext ?: context
            AppWidgetManager.getInstance(app)
                ?.getAppWidgetIds(ComponentName(app, JetBoxWidget::class.java))
                ?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }

        private fun views(context: Context, state: VpnDisplayState, options: Bundle?): RemoteViews {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return RemoteViews(
                    WidgetLayout.entries.associate { SizeF(it.minWidthDp, it.minHeightDp) to build(context, it, state) },
                )
            }
            // Портрет: ширина — минимальная, высота — максимальная (так их отдаёт лаунчер).
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val layout = if (w == 0 || h == 0) WidgetLayout.SQUARE else widgetLayoutFor(w.toFloat(), h.toFloat())
            return build(context, layout, state)
        }

        private fun build(context: Context, layout: WidgetLayout, state: VpnDisplayState): RemoteViews {
            val res = when (layout) {
                WidgetLayout.COMPACT -> R.layout.jb_widget_compact
                WidgetLayout.SQUARE -> R.layout.jb_widget
                WidgetLayout.WIDE -> R.layout.jb_widget_wide
            }
            val on = state == VpnDisplayState.CONNECTED
            val status = context.getString(
                when (state) {
                    VpnDisplayState.CONNECTED -> R.string.jb_widget_state_connected
                    VpnDisplayState.CONNECTING -> R.string.jb_widget_state_connecting
                    VpnDisplayState.DISCONNECTING -> R.string.jb_widget_state_disconnecting
                    VpnDisplayState.DISCONNECTED -> R.string.jb_widget_state_disconnected
                },
            )
            val dot = when (state) {
                VpnDisplayState.CONNECTED -> R.drawable.jb_widget_dot_connected
                VpnDisplayState.CONNECTING, VpnDisplayState.DISCONNECTING -> R.drawable.jb_widget_dot_transition
                VpnDisplayState.DISCONNECTED -> R.drawable.jb_widget_dot_disconnected
            }
            // TalkBack — как accessibilityLabel/Value переключателя iOS: «Отключить JetBox, Подключено».
            val action = context.getString(if (on) R.string.jb_widget_action_disconnect else R.string.jb_widget_action_connect)
            return RemoteViews(context.packageName, res).apply {
                setTextViewText(R.id.jb_widget_status, status)
                setImageViewResource(R.id.jb_widget_dot, dot)
                if (layout != WidgetLayout.COMPACT) {
                    // В переходных состояниях капсула — «выкл», как transitionPill() iOS.
                    setImageViewResource(R.id.jb_widget_pill, if (on) R.drawable.jb_widget_pill_on else R.drawable.jb_widget_pill_off)
                }
                setContentDescription(android.R.id.background, "$action, $status")
                setOnClickPendingIntent(android.R.id.background, tapIntent(context))
            }
        }

        private fun tapIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WidgetToggleReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
