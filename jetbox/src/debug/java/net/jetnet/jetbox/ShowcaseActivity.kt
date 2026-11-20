package net.jetnet.jetbox

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.jetnet.jetbox.logic.JetBoxStyle
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.ui.JetBoxStyleHolder
import net.jetnet.jetbox.ui.JetBoxStyleRoot
import net.jetnet.jetbox.ui.ShowcaseWidget
import net.jetnet.jetbox.widget.JetBoxWidget
import net.jetnet.jetbox.ui.ShowcaseHome
import net.jetnet.jetbox.ui.ShowcaseFlow
import net.jetnet.jetbox.ui.ShowcaseHomeState
import net.jetnet.jetbox.ui.ShowcaseScreenKind
import net.jetnet.jetbox.ui.ShowcaseSettings
import net.jetnet.jetbox.ui.ShowcaseScreen
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/**
 * Витрина компонентов (только debug). Запуск:
 * `adb -s emulator-5554 shell am start -n net.jetnet.jetbox/net.jetnet.jetbox.ShowcaseActivity`
 * — все фазы щита, капсулы, карточка, панель; `--es phase ON|OFF|WORKING|WORKING_REVERSE|OFFLINE`
 * — один щит 188 dp как на главном экране; `--es home OFF|CONNECTING|ON|OFFLINE|NOBOX` — главный экран
 * (настоящие экран и HomeViewModel на демо-движке, лист — нажатием на панель; `--ez update true` — с капсулой
 * «Доступна новая версия»);
 * `--es flow READY|SWITCH|REGISTERING|CONNECTING|CONSENT|SELECTING_EXIT|CHOOSE_EXIT|PROTECTED|PROTECTED_NO_EXIT|
 * FAILED_KEY|FAILED_UNREACHABLE|FAILED_BROKEN|FAILED_FOREIGN|FAILED_VPN` — экран по ссылке (`--ez details true` — подробности
 * отказа раскрыты); `--es screen SETTINGS|SETTINGS_ADVANCED|SETTINGS_NOBOX|ABOUT` — настройки и «О программе»
 * (`--ez update true` — со строкой «Обновить до …»).
 *
 * Стиль оформления: `--es style simple|techno` — сохраняется в настройках JetBox, как переключатель в
 * настройках (его видят и приложение, и виджет); без параметра — сохранённый. `--es home ON --ez details true`
 * — подробности открыты (лист «техно» / раскрытые подробности «простого»), `--ez exits true` — три выхода.
 * `--es widget OFF|ON` — предпросмотр виджета (три разметки), `--ez pinWidget true` — системный запрос
 * «добавить виджет на рабочий стол».
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val single = intent.getStringExtra("phase")?.let { name -> ShieldPhase.entries.firstOrNull { it.name == name } }
        val home = intent.getStringExtra("home")?.let { name -> ShowcaseHomeState.entries.firstOrNull { it.name == name } }
        val flow = intent.getStringExtra("flow")
        val details = intent.getBooleanExtra("details", false)
        val update = intent.getBooleanExtra("update", false)
        val screen = intent.getStringExtra("screen")?.let { name -> ShowcaseScreenKind.entries.firstOrNull { it.name == name } }
        val exits = intent.getBooleanExtra("exits", false)
        val widget = intent.getStringExtra("widget")?.let { name -> if (name == "ON") VpnDisplayState.CONNECTED else VpnDisplayState.DISCONNECTED }

        val prefs = JetBoxProcess.prefs(this)
        val save = { s: JetBoxStyle ->
            JetBoxStyle.write(prefs, s)
            JetBoxWidget.updateAll(applicationContext, JetBoxWidget.lastState(applicationContext))
        }
        intent.getStringExtra("style")?.let { save(JetBoxStyle.of(it)) }
        val style = JetBoxStyleHolder(JetBoxStyle.read(prefs), save)
        if (intent.getBooleanExtra("pinWidget", false)) {
            val manager = AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(this, JetBoxWidget::class.java), null, null)
            }
        }
        setContent {
            JetBoxTheme {
                JetBoxStyleRoot(style) {
                    when {
                        widget != null -> ShowcaseWidget(widget)
                        flow != null -> ShowcaseFlow(flow, details)
                        screen != null -> ShowcaseSettings(screen, update)
                        home != null -> ShowcaseHome(home, update, details, exits)
                        else -> ShowcaseScreen(single)
                    }
                }
            }
        }
    }
}
