package net.jetnet.jetbox.widget

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import io.netbird.client.tool.VPNService
import net.jetnet.jetbox.JetBoxActivity
import net.jetnet.jetbox.JetBoxProcess
import net.jetnet.jetbox.engine.JetBoxEngine
import net.jetnet.jetbox.store.JetBoxStore

/**
 * Касание виджета — `ToggleVPNIntent` (iOS). Решение — [widgetTap]:
 * * открыть приложение — `JetBoxActivity`;
 * * выключить — `ACTION_STOP_ENGINE` сервису (его приёмник зарегистрирован RECEIVER_NOT_EXPORTED:
 *   принимает только из нашего пакета, поэтому `setPackage`);
 * * включить — FGS сервиса с действием always-on (`android.net.VpnService` → `runWithoutAuth`,
 *   то же, что `runEngine(null, false)` движка: без URLOpener, браузер не открывается). Запуск FGS
 *   из фона после касания виджета Android 12+ разрешает (исключение «взаимодействие с виджетом»);
 * * перерисовать — виджет показывал «включено» от умершего процесса, а туннеля нет ([widgetStateIsStale]).
 *
 * Разовые настройки процесса (`ConnectionDefaults`) к этому моменту уже применены:
 * процесс, поднятый ради этого приёмника, сначала создаёт `JetBoxInitProvider`.
 */
class WidgetToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext ?: context
        // Состояние — до создания движка: свежий движок сразу пишет «Отключено» (туннель нового
        // процесса не поднят), и решение зависело бы от гонки с этой записью.
        val state = JetBoxWidget.lastState(app)
        // Движок следит за туннелем и перерисует виджет, кто бы туннель ни переключил.
        val engine = JetBoxEngine.get(app)
        val running = engine.isRunning()
        val tap = widgetTap(
            hasBox = JetBoxStore(JetBoxProcess.prefs(app)).currentBox != null,
            consentGranted = VpnService.prepare(app) == null,
            state = state,
            engineRunning = running,
            // Свежий процесс: движок ещё не привязан и isRunning == false даже при живом туннеле
            // (always-on поднял сервис без окна) — тогда спрашиваем систему, в foreground ли сервис.
            stale = widgetStateIsStale(state, running, serviceForeground = !running && vpnServiceInForeground(app)),
        )
        Log.i(TAG, "tap: $tap ($state)")
        when (tap) {
            WidgetTap.OPEN_APP -> openApp(app)
            WidgetTap.REFRESH -> JetBoxWidget.updateAll(app, engine.state.value)
            WidgetTap.STOP -> app.sendBroadcast(Intent(VPNService.ACTION_STOP_ENGINE).setPackage(app.packageName))
            WidgetTap.START -> try {
                app.startForegroundService(Intent(app, VPNService::class.java).setAction(ALWAYS_ON_START))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException и т. п.: включит приложение.
                Log.w(TAG, "start from widget refused: ${e.message}")
                openApp(app)
            }
            WidgetTap.NONE -> Unit
        }
    }

    /**
     * Сервис туннеля в foreground — туннель поднят или поднимается (апстрим держит foreground на всё
     * время работы ядра). `getRunningServices` для своих сервисов работает и на новых Android.
     */
    private fun vpnServiceInForeground(context: Context): Boolean = try {
        val am = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        am?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == VPNService::class.java.name && it.foreground } == true
    } catch (e: Exception) {
        // Не знаем — не объявляем устаревшим: касание сработает по сохранённому, как раньше.
        Log.w(TAG, "running services unavailable: ${e.message}")
        true
    }

    private fun openApp(context: Context) {
        context.startActivity(
            Intent(context, JetBoxActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        private const val TAG = "JetBoxWidget"

        /** Действие always-on апстрима (`VPNService.INTENT_ALWAYS_ON_START`, там оно private). */
        const val ALWAYS_ON_START = "android.net.VpnService"
    }
}
