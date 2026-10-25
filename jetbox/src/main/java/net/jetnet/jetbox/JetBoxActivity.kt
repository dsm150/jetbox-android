package net.jetnet.jetbox

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import net.jetnet.jetbox.engine.JetBoxEngine
import net.jetnet.jetbox.link.LinkIntent
import net.jetnet.jetbox.logic.JetBoxAdvancedAccess
import net.jetnet.jetbox.ui.openUpstreamMainActivity
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/**
 * Стартовый экран JetBox и приёмник ссылок из Кабинета (`https://box.jet-net.net/connect…`,
 * `jetbox://connect…`) — `NetBirdApp` + `onOpenURL` iOS. `singleTask`: ссылка при открытом
 * приложении приходит в [onNewIntent], экраны NetBird поверх закрываются, и сценарий перекрывает всё.
 *
 * Ярлык на рабочем столе — у [LauncherActivity]: она поднимает задачу как есть, не снимая экраны
 * NetBird, открытые поверх (singleTask снял бы их).
 *
 * Расширенный режим сохраняется, как в iOS (`MainView`: при `advancedMode` — экраны апстрима):
 * открыли приложение без ссылки (ярлык, уведомление, плитка) — сразу экраны NetBird
 * (`MainActivity` апстрима) поверх JetBox; «Назад» оттуда возвращает на главный экран JetBox.
 *
 * `AppCompatActivity` — чтобы язык и тема, выбранные в экранах NetBird (AppCompat-пикеры апстрима),
 * применялись и здесь на API 26–32.
 */
class JetBoxActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val onboarding = JetBoxProcess.onboarding(this)
        val engine = JetBoxEngine.get(this)
        val prefs = JetBoxProcess.prefs(this)
        setContent { JetBoxTheme { JetBoxApp(onboarding, engine, prefs) } }

        // Пересоздание (поворот, возврат после смерти процесса) ссылку повторно не разбирает:
        // шаг сценария живёт в JetBoxProcess, а интент пересозданного окна — тот же, что при старте.
        if (savedInstanceState == null) route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** Ссылка — сценарий; открытие без ссылки в расширенном режиме — экраны NetBird. */
    private fun route(intent: Intent) {
        if (handleLink(intent)) return
        if (LinkIntent.isPlainOpen(intent.action, intent.dataString) &&
            JetBoxAdvancedAccess(JetBoxProcess.prefs(this)).isAdvancedMode
        ) {
            openUpstreamMainActivity()
        }
    }

    /** `true` — в интенте была ссылка (наша или нет): её разобрали, дальше не идём. */
    private fun handleLink(intent: Intent): Boolean {
        val launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val url = LinkIntent.linkFrom(intent.action, intent.dataString, launchedFromHistory) ?: return false
        // Значение ключа в журнал не попадает: сценарий пишет сам — исход разбора и маску ключа.
        val ours = JetBoxProcess.onboarding(this).handle(url)
        if (!ours) Log.i(TAG, "JetBox: ссылка не наша — пропускаем")
        // Ссылка разобрана — убираем её из интента окна, чтобы пересоздание не разобрало её снова.
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN).setData(null))
        return true
    }

    private companion object {
        const val TAG = "JetBox"
    }
}
