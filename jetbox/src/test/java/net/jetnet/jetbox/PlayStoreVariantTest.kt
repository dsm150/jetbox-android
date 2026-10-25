package net.jetnet.jetbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Магазинные сборки (`-Pjetbox.store=rustore|play`, JETBOX-BUILD.md §6.1): своей проверки обновлений
 * нет, адресов APK с сайта в приложении нет (политика Google Play — обновляться только через магазин);
 * бандл для Play — только через `jetbox-bundle.sh`, подпись — ключом загрузки.
 */
class PlayStoreVariantTest {
    private val root: File = run {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        checkNotNull(dir) { "не найден settings.gradle.kts выше рабочей директории теста" }
    }

    private val jetboxGradle get() = File(root, "jetbox/build.gradle.kts").readText()

    @Test fun storesAreApkRustorePlay() =
        assertTrue(jetboxGradle.contains("require(store in listOf(\"apk\", \"rustore\", \"play\"))"))

    /** Адрес version.json — только в сборке «файл с сайта»; у rustore и play — пустая строка. */
    @Test fun updateUrlOnlyInSiteBuild() {
        assertTrue(
            "UPDATE_URL не только для apk",
            jetboxGradle.contains("val updateUrl = if (store == \"apk\") \"https://box.jet-net.net/android/version.json\" else \"\""),
        )
        // Тесты идут сборкой по умолчанию (apk) — у неё адрес есть.
        assertEquals("https://box.jet-net.net/android/version.json", BuildConfig.UPDATE_URL)
    }

    /** Проверка обновлений включается адресом из BuildConfig: нет адреса (магазин) — не спрашивает. */
    @Test fun updatesFollowUpdateUrl() {
        val app = File(root, "jetbox/src/main/java/net/jetnet/jetbox/JetBoxApp.kt").readText()
        assertTrue(app.contains("val url = BuildConfig.UPDATE_URL"))
        assertTrue(app.contains("enabled = url.isNotEmpty()"))
        assertTrue(app.contains("if (url.isEmpty()) UpdateSource { null }"))
    }

    /**
     * В коде и ресурсах приложения (main: `:jetbox`, `:app`, `:tool`) нет строк с адресом APK или
     * version.json сайта — иначе они были бы и в магазинной сборке. Комментарии не считаются (в dex
     * не попадают); единственный адрес — `UPDATE_URL` в `jetbox/build.gradle.kts`. По готовому бандлу
     * то же проверяет `jetbox-bundle.sh` (dex).
     */
    @Test fun noApkDownloadLinksInMainSources() {
        val link = Regex("""box\.jet-net\.net/android|JetBox\.apk""")
        val blockComment = Regex("""(?s)/\*.*?\*/|<!--.*?-->""")
        val lineComment = Regex("""(?m)^\s*//.*$""")
        val files = listOf("jetbox", "app", "tool").flatMap { m ->
            File(root, "$m/src/main").walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json", "txt", "html") }
                .toList()
        }
        assertTrue("не найдено исходников", files.size > 50)
        files.forEach { f ->
            val code = f.readText().replace(blockComment, "").replace(lineComment, "")
            assertFalse("адрес APK с сайта в ${f.relativeTo(root)}", link.containsMatchIn(code))
        }
    }

    /** Бандл (JETBOX-BUILD.md §6.1, «Google Play»): ключ загрузки, пароль из файла, проверки по готовому AAB. */
    @Test fun bundleScriptGuards() {
        val script = File(root, "jetbox-bundle.sh").readText()
        assertTrue("демон Kotlin унаследует пароль", script.contains("-Pkotlin.compiler.execution.strategy=in-process"))
        assertTrue("демон Gradle останется с паролем", script.contains("--no-daemon"))
        assertTrue("нет проверки ANDROID_HOME", script.contains(": \"\${ANDROID_HOME:?"))
        assertTrue("не ключ загрузки", script.contains("/.config/jetbox-android/upload.jks") && script.contains("NETBIRD_UPLOAD_KEY_ALIAS=\"upload\""))
        val code = script.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertFalse("release.jks — ключ подписи приложения, его держит Play", code.contains("release.jks") || code.contains("release.password"))
        assertTrue(script.contains("-Pjetbox.store=play"))
        assertTrue("ABI бандла", script.contains("abis=arm64-v8a,armeabi-v7a,x86_64"))
        assertTrue("нет проверки dex на адреса APK", script.contains("box\\.jet-net\\.net/android|JetBox\\.apk"))
        assertTrue("нет проверки 16 КБ", script.contains("llvm-readelf") && script.contains("-ge 16384"))
        assertTrue("нет копии в dist/", script.contains("dist/JetBox-\${version_name}-play.aab"))
        assertFalse("пароль в выводе", Regex("echo[^\\n]*\\\$\\{?(password\\b|NETBIRD_UPLOAD_\\w*PASSWORD)").containsMatchIn(script))
    }

    /** Экспорт ключа подписи для Play App Signing (JETBOX-BUILD.md §6.2): только по --run, пароль не в аргументах. */
    @Test fun pepkScriptIsDryRunByDefault() {
        val script = File(root, "jetbox-pepk-export.sh").readText()
        assertTrue(script.contains("--rsa-aes-encryption") && script.contains("--include-cert"))
        assertTrue("без --run скрипт должен только показывать команду", script.contains("[ \"\$run\" = yes ] ||"))
        assertFalse("пароль в выводе", Regex("echo[^\\n]*\\\$\\{?password\\b").containsMatchIn(script))
        assertFalse("пароль в аргументах pepk", Regex("--(keystore|key)-pass=pass:").containsMatchIn(script))
    }

    /** Бандл без языковых сплитов: язык меняется и внутри приложения (AppCompat, Android < 13). */
    @Test fun bundleKeepsAllLanguages() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(Regex("""bundle \{\s*language \{\s*enableSplit = false""").containsMatchIn(gradle))
    }
}
