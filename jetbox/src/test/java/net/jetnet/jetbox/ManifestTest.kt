package net.jetnet.jetbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Манифесты: JetBox — ярлык (трамплин) и приёмник ссылок, `MainActivity` апстрима — без ярлыка. */
class ManifestTest {
    private val root: File = run {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        checkNotNull(dir) { "не найден settings.gradle.kts выше рабочей директории теста" }
    }

    private val jetbox = File(root, "jetbox/src/main/AndroidManifest.xml").readText()
    private val app = File(root, "app/src/main/AndroidManifest.xml").readText()

    /** Текст элемента `<activity … name="…$name"> … </activity>`. */
    private fun activity(manifest: String, name: String): String {
        val m = Regex("<activity\\b[^>]*android:name=\"[^\"]*$name\"[\\s\\S]*?</activity>").find(manifest)
        assertNotNull("нет активности $name", m)
        return m!!.value
    }

    private fun filters(activity: String): List<String> =
        Regex("<intent-filter\\b[\\s\\S]*?</intent-filter>").findAll(activity).map { it.value }.toList()

    private fun isLauncher(filter: String) =
        filter.contains("android.intent.action.MAIN") && filter.contains("android.intent.category.LAUNCHER")

    /** Ярлык — у трамплина: singleTask-окно под ярлыком снимало бы экраны NetBird поверх себя. */
    @Test fun launcherActivityIsLauncher() {
        val a = activity(jetbox, "\\.LauncherActivity")
        assertTrue(a.contains("android:exported=\"true\""))
        assertFalse("трамплин должен быть standard", a.contains("android:launchMode"))
        // Тема JetBox: с NoDisplay система не показывает заставку при холодном старте.
        assertTrue(a.contains("android:theme=\"@style/Theme.JetBox\""))
        assertTrue("нет MAIN/LAUNCHER", filters(a).any(::isLauncher))
        assertFalse("LEANBACK_LAUNCHER: ТВ на этапе 1 не поддерживается", jetbox.contains("android.intent.category.LEANBACK_LAUNCHER"))
    }

    @Test fun jetBoxActivityIsSingleTaskPortrait() {
        val a = activity(jetbox, "JetBoxActivity")
        assertTrue(a.contains("android:launchMode=\"singleTask\""))
        assertTrue(a.contains("android:exported=\"true\""))
        assertTrue(a.contains("android:theme=\"@style/Theme.JetBox\""))
        assertTrue(a.contains("android:screenOrientation=\"portrait\""))
        assertFalse("ярлык теперь у LauncherActivity", filters(a).any(::isLauncher))
    }

    @Test fun jetBoxActivityTakesHttpsLinks() {
        val f = filters(activity(jetbox, "JetBoxActivity")).single { it.contains("box.jet-net.net") }
        assertTrue(f.contains("android:autoVerify=\"true\""))
        assertTrue(f.contains("android.intent.action.VIEW"))
        assertTrue(f.contains("android.intent.category.BROWSABLE"))
        assertTrue(f.contains("android.intent.category.DEFAULT"))
        assertTrue(f.contains("android:scheme=\"https\""))
        assertTrue(f.contains("android:path=\"/connect\""))
        assertTrue(f.contains("android:pathPrefix=\"/connect/\""))
    }

    @Test fun jetBoxActivityTakesCustomScheme() {
        val f = filters(activity(jetbox, "JetBoxActivity")).single { it.contains("android:scheme=\"jetbox\"") }
        assertTrue(f.contains("android:host=\"connect\""))
        assertTrue(f.contains("android.intent.action.VIEW"))
        assertTrue(f.contains("android.intent.category.BROWSABLE"))
    }

    /** Разовые настройки должны примениться при старте процесса, а не при открытии окна. */
    @Test fun initProviderStartsBeforeWindow() {
        val m = Regex("<provider\\b[^>]*android:name=\"[^\"]*JetBoxInitProvider\"[^>]*>").find(jetbox)
        assertNotNull("нет провайдера JetBoxInitProvider", m)
        val p = m!!.value
        assertTrue(p.contains("android:authorities=\"\${applicationId}.jetbox-init\""))
        assertTrue(p.contains("android:exported=\"false\""))
        val order = Regex("android:initOrder=\"(-?\\d+)\"").find(p)
        assertNotNull("нет initOrder", order)
        assertTrue("initOrder должен быть высоким", order!!.groupValues[1].toInt() >= 100)
    }

    /** Плитка без согласия VPN ведёт на экран JetBox, а не в служебные экраны NetBird. */
    @Test fun tileOpensJetBoxWithoutConsent() {
        val tile = File(root, "app/src/main/java/io/netbird/client/NetbirdTileService.java").readText()
        assertTrue(tile.contains("setClassName(getPackageName(), \"net.jetnet.jetbox.JetBoxActivity\")"))
        assertFalse(tile.contains("new Intent(this, MainActivity.class)"))
    }

    @Test fun upstreamMainActivityIsNotLauncher() {
        val a = activity(app, "\\.MainActivity")
        assertFalse("LAUNCHER у MainActivity", a.contains("android.intent.category.LAUNCHER"))
        assertFalse("LEANBACK_LAUNCHER у MainActivity", a.contains("LEANBACK_LAUNCHER"))
        assertFalse("MAIN у MainActivity", a.contains("android.intent.action.MAIN"))
        // Все вызовы — изнутри пакета (наши настройки, PendingIntent уведомления, CustomTabURLOpener):
        // экспорт не нужен, а без него чужое приложение не откроет служебные экраны NetBird.
        assertTrue(a.contains("android:exported=\"false\""))
        assertFalse(a.contains("android:exported=\"true\""))
    }

    /** Кто открывает `MainActivity` — только код нашего пакета, явным интентом (exported=false это позволяет). */
    @Test fun upstreamMainActivityIsOpenedOnlyFromInsidePackage() {
        val settings = File(root, "jetbox/src/main/java/net/jetnet/jetbox/ui/JetBoxSettingsScreen.kt").readText()
        assertTrue(settings.contains("setClassName(packageName, UPSTREAM_MAIN_ACTIVITY)"))
        val fg = File(root, "tool/src/main/java/io/netbird/client/tool/ForegroundNotification.java").readText()
        assertTrue(fg.contains("intent.setClassName(service.getPackageName(), \"io.netbird.client.MainActivity\")"))
        assertTrue("extendIntent — PendingIntent нашего пакета", fg.contains("PendingIntent.getActivity(service, 1, intent,"))
        val tabs = File(root, "app/src/main/java/io/netbird/client/CustomTabURLOpener.java").readText()
        assertTrue(tabs.contains("new Intent(this.context, MainActivity.class)"))
        // Плитка в MainActivity не ведёт вовсе (tileOpensJetBoxWithoutConsent).
    }

    @Test fun windowThemePaintsJetBoxBackground() {
        val day = File(root, "jetbox/src/main/res/values/themes.xml").readText()
        val night = File(root, "jetbox/src/main/res/values-night/themes.xml").readText()
        listOf(day, night).forEach {
            assertTrue(it.contains("Theme.JetBox"))
            assertTrue(it.contains("<item name=\"android:windowBackground\">@color/jb_window_background</item>"))
        }
        // Android 12+: заставка — фон JetBox без иконки (как UILaunchScreen iOS).
        listOf("values-v31", "values-night-v31").forEach { dir ->
            val t = File(root, "jetbox/src/main/res/$dir/themes.xml").readText()
            assertTrue(dir, t.contains("<item name=\"android:windowBackground\">@color/jb_window_background</item>"))
            assertTrue(dir, t.contains("<item name=\"android:windowSplashScreenBackground\">@color/jb_window_background</item>"))
            assertTrue(dir, t.contains("<item name=\"android:windowSplashScreenAnimatedIcon\">@drawable/jb_transparent</item>"))
        }
        assertTrue(File(root, "jetbox/src/main/res/values/colors.xml").readText().contains("jb_window_background\">#F2F6FA"))
        assertTrue(File(root, "jetbox/src/main/res/values-night/colors.xml").readText().contains("jb_window_background\">#0A1420"))
    }

    /**
     * Ничего с именем `io.netbird.permission…`: официальный NetBird объявляет такие разрешения со
     * своей подписью, и установка JetBox рядом с ним падала бы INSTALL_FAILED_DUPLICATE_PERMISSION.
     */
    @Test fun noNetBirdPermissionsDeclared() {
        listOf("app", "tool", "jetbox").forEach { module ->
            val m = File(root, "$module/src/main/AndroidManifest.xml")
            if (m.exists()) assertFalse("$module: io.netbird.permission", m.readText().contains("io.netbird.permission"))
        }
        // Собственные разрешения, если появятся, — только от applicationId.
        listOf(jetbox, app).forEach { manifest ->
            Regex("<permission\\b[^>]*android:name=\"([^\"]+)\"").findAll(manifest).forEach {
                assertTrue(it.groupValues[1], it.groupValues[1].startsWith("\${applicationId}."))
            }
        }
    }

    /** Провайдеры — от applicationId: authorities, совпавшие с официальным NetBird, тоже ломают установку. */
    @Test fun providerAuthoritiesAreApplicationIdBased() {
        listOf("app", "tool", "jetbox").forEach { module ->
            val m = File(root, "$module/src/main/AndroidManifest.xml")
            if (!m.exists()) return@forEach
            Regex("android:authorities=\"([^\"]+)\"").findAll(m.readText()).forEach {
                assertTrue("$module: ${it.groupValues[1]}", it.groupValues[1].startsWith("\${applicationId}."))
            }
        }
    }

    /** Отладочный пакет не уходит в NetBird GmbH: кнопка загрузки скрыта, остаётся «сохранить в файл». */
    @Test fun debugBundleUploadButtonHidden() {
        val t = File(root, "app/src/main/java/io/netbird/client/ui/troubleshoot/TroubleshootFragment.java").readText()
        assertTrue(t.contains("binding.buttonDebugBundle.setVisibility(View.GONE);"))
        assertFalse("кнопка загрузки не должна вызывать generateDebugBundle()",
            Regex("buttonDebugBundle\\.setOnClickListener").containsMatchIn(t))
        assertTrue(t.contains("binding.buttonDebugBundleFile.setOnClickListener"))
    }

    @Test fun remoteDebugBundlesSwitchHidden() {
        val t = File(root, "app/src/main/java/io/netbird/client/ui/troubleshoot/TroubleshootFragment.java").readText()
        assertTrue("переключатель удалённых отладочных пакетов должен быть скрыт",
            t.contains("binding.allowRemoteJobsLayout.setVisibility(View.GONE);"))
    }
}
