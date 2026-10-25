package net.jetnet.jetbox

import net.jetnet.jetbox.engine.CoreState
import net.jetnet.jetbox.widget.WidgetToggleReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Охрана контрактов апстрима, на которые `:jetbox` опирается **без правки** апстримовых файлов
 * (список — JETBOX-BUILD.md, §4.6). Компилятор ловит не всё: строковые литералы, private-константы,
 * имена prefs и ключей, порядок кодов ядра. Если тест покраснел после слияния тега — апстрим сменил
 * контракт: поправить наш код по §4.6, а не тест.
 */
class UpstreamContractsTest {
    private val root: File = run {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        checkNotNull(dir) { "не найден settings.gradle.kts выше рабочей директории теста" }
    }

    private fun read(path: String): String {
        val f = File(root, path)
        assertTrue("нет ${f.path}", f.exists())
        return f.readText()
    }

    private val vpnService by lazy { read("tool/src/main/java/io/netbird/client/tool/VPNService.java") }

    /** Тело `public class MyLocalBinder … {` до закрывающей скобки класса. */
    private val binder by lazy {
        val start = vpnService.indexOf("public class MyLocalBinder")
        assertTrue("нет MyLocalBinder", start >= 0)
        var depth = 0
        var i = vpnService.indexOf('{', start)
        val from = i
        while (i < vpnService.length) {
            when (vpnService[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) break
            }
            i++
        }
        vpnService.substring(from, i)
    }

    /** Касание виджета включает туннель действием always-on (в VPNService оно private). */
    @Test fun alwaysOnActionLiteral() {
        assertTrue(
            "INTENT_ALWAYS_ON_START больше не \"android.net.VpnService\"",
            vpnService.contains("INTENT_ALWAYS_ON_START = \"android.net.VpnService\""),
        )
        assertEquals("android.net.VpnService", WidgetToggleReceiver.ALWAYS_ON_START)
        // Ветка always-on запускает ядро без логина (без URLOpener — браузер не открывается).
        assertTrue(vpnService.contains("INTENT_ALWAYS_ON_START.equals(intent.getAction())"))
        assertTrue(vpnService.contains("engineRunner.runWithoutAuth()"))
    }

    /** Виджет выключает туннель broadcast'ом ACTION_STOP_ENGINE; приёмник — только для своего пакета. */
    @Test fun stopEngineBroadcastIsPackagePrivate() {
        assertTrue(vpnService.contains("public static final String ACTION_STOP_ENGINE"))
        assertTrue(vpnService.contains("new android.content.IntentFilter(ACTION_STOP_ENGINE)"))
        val reg = Regex("registerReceiver\\(\\s*this,\\s*stopEngineReceiver,\\s*filter,\\s*Context\\.RECEIVER_NOT_EXPORTED")
        assertTrue("stopEngineReceiver больше не RECEIVER_NOT_EXPORTED — setPackage в виджете обязателен", reg.containsMatchIn(vpnService))
        assertTrue(vpnService.contains("public static final String INTENT_ACTION_START"))
    }

    /** Методы `VPNService.MyLocalBinder`, которые зовёт `AndroidEngine` (сигнатуры — как у нас в вызовах). */
    @Test fun binderMethodsWeRelyOn() {
        listOf(
            "public void runEngine(URLOpener urlOpener, boolean isAndroidTV)",
            "public void stopEngine()",
            "public boolean isRunning()",
            "public boolean isLoginRequired()",
            "public PeerInfoArray peersInfo()",
            "public NetworkArray networks()",
            "public void selectRoute(String route) throws Exception",
            "public void deselectRoute(String route) throws Exception",
            "public long connectedAtMillis()",
            "public void addConnectionObserver(ConnectionListener observer)",
            "public void removeConnectionObserver(ConnectionListener observer)",
            "public void setConnectionStateListener(ConnectionListener listener)",
            "public void addServiceStateListener(ServiceStateListener serviceStateListener)",
            "public void addRouteChangeListener(RouteChangeListener listener)",
        ).forEach { assertTrue("в MyLocalBinder нет: $it", binder.contains(it)) }
    }

    /** `runEngine(null, …)` = «без логина»: EngineRunner без URLOpener не открывает браузер. */
    @Test fun runEngineWithoutOpenerRunsWithoutLogin() {
        val runner = read("tool/src/main/java/io/netbird/client/tool/EngineRunner.java")
        assertTrue("EngineRunner.run(URLOpener, boolean) пропал", Regex("void run\\(\\s*@?\\w*\\s*URLOpener").containsMatchIn(runner))
        assertTrue("runWithoutAuth пропал", runner.contains("runWithoutAuth("))
        assertTrue("без URLOpener ядро больше не идёт в runWithoutLogin", Regex("if \\(urlOpener == null\\) \\{\\s*goClient\\.runWithoutLogin\\(").containsMatchIn(runner))
        assertTrue("connectedAtMillis (врезка JetBox) пропал", runner.contains("connectedAtMillis()"))
    }

    /** Имена prefs и ключей апстрима, которые мы пишем сами. */
    @Test fun upstreamPrefsKeys() {
        val ui = read("app/src/main/java/io/netbird/client/ui/PreferenceUI.java")
        assertTrue("ui_prefs переименован", ui.contains("\"ui_prefs\""))
        assertTrue("first_launch переименован", ui.contains("\"first_launch\""))
        val prefs = read("tool/src/main/java/io/netbird/client/tool/Preferences.java")
        assertTrue("prefs \"netbird\" переименованы", prefs.contains("\"netbird\""))
        assertTrue("isConnectionForceRelayed переименован", prefs.contains("\"isConnectionForceRelayed\""))
    }

    /** Регистрация по ключу — те же вызовы, что FirstInstallFragment.applyServer апстрима. */
    @Test fun registrationBridges() {
        val mdm = read("tool/src/main/java/io/netbird/client/tool/MDMBridge.java")
        assertTrue(Regex("public static\\s+\\S*Preferences\\s+openPreferences\\(Context context, String configPath\\)").containsMatchIn(mdm))
        assertTrue(Regex("public static\\s+\\S*Auth\\s+newAuth\\(Context context, String configPath, String managementUrl\\)").containsMatchIn(mdm))
        val pm = read("tool/src/main/java/io/netbird/client/tool/ProfileManagerWrapper.java")
        assertTrue(pm.contains("getActiveConfigPath()"))
    }

    /** Коды `ClientState` ядра: наш [CoreState] обязан совпадать с порядком `iota` в Go. */
    @Test fun coreClientStateCodes() {
        val go = read("netbird/client/internal/peer/listener.go")
        // Блок const до строки с одной «)» (в комментариях внутри блока скобки бывают).
        val block = Regex("const \\(([\\s\\S]*?)\\n\\)").find(go.substringAfter("type ClientState int"))!!.groupValues[1]
        val names = Regex("^\\s*(ClientState\\w+)", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("ClientStateDisconnected", "ClientStateConnected", "ClientStateConnecting", "ClientStateDisconnecting", "ClientStateNoNetwork"),
            names.take(5),
        )
        assertEquals(0L, CoreState.DISCONNECTED)
        assertEquals(1L, CoreState.CONNECTED)
        assertEquals(2L, CoreState.CONNECTING)
        assertEquals(3L, CoreState.DISCONNECTING)
        assertEquals(4L, CoreState.NO_NETWORK)
        val bind = read("netbird/client/android/connection_listener.go")
        assertTrue(bind.contains("ClientStateNoNetwork     = int(peer.ClientStateNoNetwork)"))
        assertTrue(bind.contains("ClientStateConnecting    = int(peer.ClientStateConnecting)"))
        // Пиры: «подключён» — ConnStatusConnected gomobile.
        assertTrue(read("netbird/client/android/peer_notifier.go").contains("ConnStatusConnected  = int(peer.StatusConnected)"))
    }
}
