package net.jetnet.jetbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrandingResourcesTest {
    private val root: File = run {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        checkNotNull(dir) { "не найден settings.gradle.kts выше рабочей директории теста" }
    }

    private fun stringValue(file: File, name: String): String? =
        Regex("<string name=\"$name\"[^>]*>([^<]*)</string>").find(file.readText())?.groupValues?.get(1)

    private fun allStrings(module: String) =
        File(root, "$module/src/main/res").listFiles { f -> f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }.filter { it.exists() }

    @Test fun appNameIsJetBoxEverywhere() {
        // Базовый ресурс обязан существовать, иначе тест проходил бы вхолостую.
        val baseStrings = File(root, "app/src/main/res/values/strings.xml")
        val baseName = stringValue(baseStrings, "app_name")
        assertNotNull("app_name не найден в ${baseStrings.path}", baseName)
        assertEquals(baseStrings.path, "JetBox", baseName)
        allStrings("app").forEach { f ->
            stringValue(f, "app_name")?.let { assertEquals(f.path, "JetBox", it) }
            stringValue(f, "quick_settings_tile_label")?.let { assertEquals(f.path, "JetBox", it) }
        }
        allStrings("tool").forEach { f ->
            stringValue(f, "service_name")?.let { assertEquals(f.path, "JetBox", it) }
            stringValue(f, "fg_notification_channel_name")?.let { assertEquals(f.path, "JetBox", it) }
        }
    }

    /**
     * Тексты, которые клиент видит в уведомлениях сервиса туннеля (`ForegroundNotification`,
     * `SessionNotification`, `SessionWarningWorker`) и на плитке. Новый ключ уведомления в `tool` —
     * дописать сюда.
     */
    private val notificationStrings = listOf(
        "tool" to "service_name",
        "tool" to "fg_notification_channel_name",
        "tool" to "fg_notification_connected",
        "tool" to "fg_notification_connecting",
        "tool" to "fg_notification_disconnected",
        "tool" to "fg_notification_no_network",
        "tool" to "fg_notification_needs_login",
        "tool" to "fg_notification_error",
        "tool" to "fg_notification_session_expired",
        "tool" to "fg_notification_session_expires_soon",
        "tool" to "session_notification_channel_name",
        "tool" to "session_notification_expiring_title",
        "tool" to "session_notification_expiring_text",
        "tool" to "session_notification_expired_title",
        "tool" to "session_notification_expired_text",
        "tool" to "session_notification_extend",
        "app" to "app_name",
        "app" to "quick_settings_tile_label",
    )
    private val notificationPlurals = listOf(
        "fg_notification_session_expires_minutes",
        "fg_notification_session_expires_hours",
        "fg_notification_session_expires_days",
    )

    private fun pluralsText(file: File, name: String): String? =
        Regex("<plurals name=\"$name\"[^>]*>([\\s\\S]*?)</plurals>").find(file.readText())?.groupValues?.get(1)

    /** В уведомлениях и на плитке — JetBox, без «NetBird» (во всех языках модулей). */
    @Test fun notificationTextsDoNotSayNetBird() {
        notificationStrings.forEach { (module, key) ->
            listOf("values", "values-ru").forEach { dir ->
                val f = File(root, "$module/src/main/res/$dir/strings.xml")
                // Базовый и русский обязаны содержать ключ — иначе тест проходил бы вхолостую.
                // (service_name в переводах апстрима нет — берётся базовый.)
                if (dir == "values" || (module == "tool" && key != "service_name")) {
                    assertNotNull("$key нет в ${f.path}", stringValue(f, key))
                }
            }
            allStrings(module).forEach { f ->
                stringValue(f, key)?.let { assertFalse("NetBird в $key (${f.path}): $it", it.contains("netbird", ignoreCase = true)) }
            }
        }
        allStrings("tool").forEach { f ->
            notificationPlurals.forEach { name ->
                pluralsText(f, name)?.let { assertFalse("NetBird в $name (${f.path})", it.contains("netbird", ignoreCase = true)) }
            }
        }
        assertNotNull(pluralsText(File(root, "tool/src/main/res/values/strings.xml"), notificationPlurals.first()))
    }

    /** Уведомление сервиса говорит теми же словами, что экраны JetBox (iOS: «Подключаем…»). */
    @Test fun notificationTextsMatchJetBoxWording() {
        listOf("values", "values-ru").forEach { dir ->
            val tool = File(root, "tool/src/main/res/$dir/strings.xml")
            val jb = File(root, "jetbox/src/main/res/$dir/strings.xml")
            assertEquals(dir, stringValue(jb, "jb_connecting"), stringValue(tool, "fg_notification_connecting"))
            assertEquals(dir, stringValue(jb, "jb_home_offline"), stringValue(tool, "fg_notification_no_network"))
            assertEquals(dir, stringValue(jb, "jb_notification_login_required_body"), stringValue(tool, "fg_notification_needs_login"))
            assertEquals(dir, stringValue(jb, "jb_notification_login_required_title"), stringValue(tool, "session_notification_expired_title"))
            assertEquals(dir, stringValue(jb, "jb_notification_login_required_body"), stringValue(tool, "session_notification_expired_text"))
        }
    }

    @Test fun applicationIdIsJetBox() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("applicationId не net.jetnet.jetbox", gradle.contains("applicationId = \"net.jetnet.jetbox\""))
    }

    @Test fun noAdIdPermission() =
        assertFalse(File(root, "app/src/main/AndroidManifest.xml").readText().contains("AD_ID"))

    /**
     * Камера не нужна (QR только рисуется), а zxing-android-embedded вносит CAMERA и camera-фичи
     * в итоговый манифест — врезка снимает их `tools:node="remove"` (JETBOX-BUILD.md §4.2, стр. 7).
     * Итоговый APK проверяется `aapt2 dump permissions` (§6.1).
     */
    @Test fun cameraPermissionRemoved() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        fun removed(tag: String, name: String) =
            Regex("<$tag\\s+android:name=\"${Regex.escape(name)}\"\\s+tools:node=\"remove\"\\s*/>").containsMatchIn(manifest)
        assertTrue("CAMERA не снята", removed("uses-permission", "android.permission.CAMERA"))
        listOf("", ".front", ".autofocus", ".flash").forEach {
            assertTrue("android.hardware.camera$it не снята", removed("uses-feature", "android.hardware.camera$it"))
        }
        assertFalse(
            "CAMERA объявлена без tools:node=remove",
            Regex("<uses-permission\\s+android:name=\"android.permission.CAMERA\"\\s*/>").containsMatchIn(manifest),
        )
    }

    @Test fun socketDirectoryFollowsPackage() {
        val script = File(root, "build-android-lib.sh").readText()
        assertTrue(
            "socketDirectory не следует за пакетом",
            script.contains("socketDirectory=/data/data/net.jetnet.jetbox/cache/wireguard"),
        )
        assertFalse(script.contains("/data/data/io.netbird.client/"))
    }

    /** Релиз 1.0 (JETBOX-BUILD.md §4, стр. 1 и 6; §6.1): ABI телефонов, сжатые .so, R8 выключен, ядро без символов. */
    @Test fun releaseBuildIsSlim() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("нет abiFilters телефонов", gradle.contains("\"arm64-v8a,armeabi-v7a\"") && gradle.contains("abiFilters +="))
        assertTrue("нет переопределения -Pjetbox.abis", gradle.contains("findProperty(\"jetbox.abis\")"))
        assertTrue(".so в APK не сжаты", Regex("""useLegacyPackaging = \(project\.findProperty\("jetbox\.legacyPackaging"\).*\?: true""").containsMatchIn(gradle))
        assertTrue("R8 не выключен явно", gradle.contains("isMinifyEnabled = false"))
        val script = File(root, "build-android-lib.sh").readText()
        assertTrue("ядро собирается с символами", script.contains("-ldflags=\"-s -w "))
    }

    /** Релиз — только через jetbox-release.sh (JETBOX-BUILD.md §6.1): ключ, пароль, имя файла. */
    @Test fun releaseScriptAndKeyGuard() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("нет проверки ключа подписи", gradle.contains("gradle.taskGraph.whenReady"))
        assertTrue(gradle.contains("ключ подписи не задан — используйте ./jetbox-release.sh"))
        val script = File(root, "jetbox-release.sh").readText()
        assertTrue("демон Kotlin унаследует пароль", script.contains("-Pkotlin.compiler.execution.strategy=in-process"))
        assertTrue("демон Gradle останется с паролем", script.contains("--no-daemon"))
        assertTrue("нет проверки ANDROID_HOME", script.contains(": \"\${ANDROID_HOME:?"))
        assertTrue("нет копии в dist/", script.contains("dist/JetBox-\${version_name}-\${store}-"))
        assertFalse("пароль в выводе", Regex("echo[^\\n]*\\\$\\{?(password\\b|NETBIRD_UPLOAD_\\w*PASSWORD)").containsMatchIn(script))
        assertTrue("dist/ не в .gitignore", File(root, ".gitignore").readLines().contains("dist/"))
    }

    @Test fun vpnSessionNamed() =
        assertTrue(
            "VPN-сессия не названа JetBox",
            File(root, "tool/src/main/java/io/netbird/client/tool/IFace.java").readText().contains("setSession(\"JetBox\")"),
        )

    @Test fun noHardcodedUpstreamPackageInClassNames() {
        listOf("ForegroundNotification.java", "SessionNotification.java").forEach {
            val src = File(root, "tool/src/main/java/io/netbird/client/tool/$it").readText()
            // Пакет приложения берётся из getPackageName(); литерал upstream-пакета первым аргументом запрещён.
            // Имя класса (io.netbird.client.MainActivity) вторым аргументом разрешено.
            assertFalse(it, Regex("""setClassName\(\s*"io\.netbird\.client"""").containsMatchIn(src))
        }
    }

    @Test fun defaultServerIsJetNet() =
        assertTrue(
            "дефолтный сервер не netbird.jet-net.net",
            File(root, "tool/src/main/java/io/netbird/client/tool/Preferences.java").readText().contains("https://netbird.jet-net.net"),
        )
    private val upstreamOrange = Regex("(?i)F68330|F58431")

    private fun pathData(file: File): List<String> =
        Regex("android:pathData=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList()

    @Test fun noUpstreamOrangeInAppColors() {
        val files = File(root, "app/src/main/res").listFiles { f -> f.name.startsWith("values") }!!
            .map { File(it, "colors.xml") }.filter { it.exists() }
        assertTrue("не найдено ни одного colors.xml", files.isNotEmpty())
        files.forEach { assertFalse("оранжевый NetBird в ${it.path}", upstreamOrange.containsMatchIn(it.readText())) }
    }

    @Test fun launcherIconIsJetBox() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val xml = File(root, "app/src/main/res/mipmap-anydpi-v26/$name").readText()
            listOf("jb_icon_background", "jb_icon_foreground", "jb_icon_monochrome").forEach {
                assertTrue("$name не ссылается на $it", xml.contains("@drawable/$it"))
            }
            assertTrue("$name без <monochrome>", xml.contains("<monochrome"))
        }
    }

    @Test fun tileIconIsJetBoxMark() {
        val tile = File(root, "app/src/main/res/drawable/ic_netbird_tile.xml")
        assertFalse("оранжевый в ${tile.path}", upstreamOrange.containsMatchIn(tile.readText()))
        val mark = pathData(File(root, "jetbox/src/main/res/drawable/jb_tile.xml"))
        assertTrue(mark.isNotEmpty())
        assertEquals("плитка — не знак JB", mark, pathData(tile))
    }

    @Test fun notificationIconsAreJetBoxMark() {
        val mark = pathData(File(root, "jetbox/src/main/res/drawable/jb_notification.xml"))
        assertTrue(mark.isNotEmpty())
        val res = File(root, "tool/src/main/res")
        listOf("connected", "connecting", "disconnected", "error").forEach { st ->
            val name = "notification_icon_$st"
            assertEquals("$name — не знак JB", mark, pathData(File(res, "drawable/$name.xml")))
            // Растровые иконки апстрима перекрыли бы вектор на своей плотности.
            res.listFiles { f -> f.name.startsWith("drawable-") }!!.forEach { dir ->
                assertFalse("растровый $name в ${dir.path}", File(dir, "$name.png").exists())
            }
        }
    }

    // --- Остатки оранжевого NetBird в ресурсах апстрима (app/tool) -------------------------------

    private val resDirs = listOf("app", "tool").map { File(root, "$it/src/main/res") }

    private fun resFiles(ext: String): List<File> =
        resDirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == ext }.toList() }

    // Красно-оранжевый: оттенок < 50° или > 340°, насыщенность > 0.45, яркость > 0.5.
    private fun isWarm(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b).toFloat()
        val mn = minOf(r, g, b).toFloat()
        val v = mx / 255f
        val sat = if (mx == 0f) 0f else (mx - mn) / mx
        if (sat <= 0.45f || v <= 0.5f) return false
        val d = mx - mn
        val hue = when (mx) {
            r.toFloat() -> 60f * (((g - b) / d + 6f) % 6f)
            g.toFloat() -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return hue < 50f || hue > 340f
    }

    private val colorLiteral = Regex("#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{3})(?![0-9A-Fa-f])")

    private fun rgbOf(hex: String): Triple<Int, Int, Int> {
        val h = when (hex.length) {
            3, 4 -> hex.takeLast(3).map { "$it$it" }.joinToString("")
            else -> hex.takeLast(6)
        }
        return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }

    // Заведомо наши тёплые цвета не из бренда: «опасность» и «высокая задержка» (янтарь).
    private val allowedWarm = setOf("DC2626", "F87171", "B7791F", "FACC15")

    @Test fun noOrangeColorLiteralsInUpstreamResources() {
        val files = resFiles("xml").filterNot { it.name.startsWith("strings") }
        assertTrue("не найдено xml-ресурсов", files.size > 50)
        val offenders = mutableListOf<String>()
        files.forEach { f ->
            val text = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            colorLiteral.findAll(text).forEach { m ->
                val hex = m.groupValues[1]
                val (r, g, b) = rgbOf(hex)
                if (isWarm(r, g, b) && rgbOf(hex).let { String.format("%02X%02X%02X", it.first, it.second, it.third) } !in allowedWarm) {
                    offenders += "${f.relativeTo(root).path}: ${m.value}"
                }
            }
        }
        assertTrue("оранжевые цвета NetBird: $offenders", offenders.isEmpty())
    }

    // android.jar не знает java.desktop (ImageIO), а JVM теста знает: читаем растр рефлексией.
    private class Raster(val width: Int, val height: Int, private val image: Any) {
        fun rgb(x: Int, y: Int): Int =
            image.javaClass.getMethod("getRGB", Int::class.java, Int::class.java).invoke(image, x, y) as Int
    }

    private fun readImage(f: File): Raster {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, f)
        checkNotNull(image) { "ImageIO не прочитал ${f.path}" }
        val c = image.javaClass
        return Raster(c.getMethod("getWidth").invoke(image) as Int, c.getMethod("getHeight").invoke(image) as Int, image)
    }

    // Растры, где красный/тёплый — осознанный (значок ошибки).
    private val allowedWarmRasters = setOf("exclamation.png")

    @Test fun noOrangeRasters() {
        val offenders = mutableListOf<String>()
        resFiles("png").filterNot { it.name in allowedWarmRasters }.forEach { f ->
            val img = readImage(f)
            var n = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val argb = img.rgb(x, y)
                if ((argb ushr 24) > 128) { n++; sr += (argb shr 16) and 255; sg += (argb shr 8) and 255; sb += argb and 255 }
            }
            if (n > 0 && isWarm((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())) offenders += f.relativeTo(root).path
        }
        assertTrue("оранжевые растры: $offenders", offenders.isEmpty())
    }

    @Test fun onlyBannerWebpRemains() {
        // ImageIO не читает webp, поэтому они не проверяются по цвету — разрешён только баннер Android TV.
        val stray = resFiles("webp").filter { it.name != "ic_banner.webp" }.map { it.relativeTo(root).path }
        assertTrue("лишние webp (иконка — векторы anydpi-v26): $stray", stray.isEmpty())
    }

    @Test fun brandAccentIsJetBoxBlue() {
        val day = File(root, "app/src/main/res/values/colors.xml").readText()
        val night = File(root, "app/src/main/res/values-night/colors.xml").readText()
        fun nb(text: String) = Regex("""<color name="nb_orange">([^<]+)</color>""").find(text)?.groupValues?.get(1)
        assertEquals("#1288E1", nb(day)?.uppercase())
        assertEquals("#1A9BF5", nb(night)?.uppercase())
    }

    @Test fun iconMarkMatchesTile() {
        val tile = pathData(File(root, "jetbox/src/main/res/drawable/jb_tile.xml"))
        assertTrue(tile.isNotEmpty())
        listOf("jb_icon_foreground.xml", "jb_icon_monochrome.xml").forEach {
            assertEquals("знак в $it отличается от jb_tile.xml", tile, pathData(File(root, "jetbox/src/main/res/drawable/$it")))
        }
    }
}
