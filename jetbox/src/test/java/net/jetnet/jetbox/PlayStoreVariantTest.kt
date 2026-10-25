package net.jetnet.jetbox

import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.UpdateSource
import net.jetnet.jetbox.engine.updateCheckerFor
import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext

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

    private companion object {
        const val SITE_VERSION_JSON = "https://box.jet-net.net/android/version.json"
    }

    @Test fun storesAreApkRustorePlay() =
        assertTrue(jetboxGradle.contains("require(store in listOf(\"apk\", \"rustore\", \"play\"))"))

    /** Без `-Pjetbox.store` сборка — для сайта (своя проверка обновлений). */
    @Test fun storeDefaultsToApk() =
        assertTrue(jetboxGradle.contains("val store = (project.findProperty(\"jetbox.store\") as String?) ?: \"apk\""))

    /** Адрес version.json — только в сборке «файл с сайта»; у rustore и play — пустая строка. */
    @Test fun updateUrlOnlyInSiteBuild() {
        assertTrue(
            "UPDATE_URL не только для apk",
            jetboxGradle.contains("val updateUrl = if (store == \"apk\") \"$SITE_VERSION_JSON\" else \"\""),
        )
        // Тесты идут в том варианте, что собран (`./gradlew test` — apk, jetbox-bundle.sh — play).
        when (BuildConfig.STORE) {
            "apk" -> assertEquals(SITE_VERSION_JSON, BuildConfig.UPDATE_URL)
            "rustore", "play" -> assertEquals("в магазинной сборке адреса version.json нет", "", BuildConfig.UPDATE_URL)
            else -> fail("неизвестный STORE ${BuildConfig.STORE}")
        }
    }

    /** Счётчик запросов вместо сети: `null` — «нет файла», проверка молча выходит. */
    private class CountingSource : UpdateSource {
        var calls = 0
        override suspend fun fetch(): String? { calls++; return null }
    }

    private fun checkerWith(url: String, source: CountingSource) = updateCheckerFor(
        updateUrl = url, kv = MemoryStore(), currentCode = 11, userAgent = "test",
        io = EmptyCoroutineContext, sourceFor = { source },
    )

    /** Фабрика: пустой адрес — проверка выключена и не спрашивает; адрес есть — спрашивает по нему. */
    @Test fun updateCheckerForFollowsUrl() = runTest {
        val off = CountingSource()
        checkerWith("", off).check(force = true)
        assertEquals("без адреса проверка не должна спрашивать", 0, off.calls)

        val on = CountingSource()
        var asked: URL? = null
        updateCheckerFor(
            updateUrl = SITE_VERSION_JSON, kv = MemoryStore(), currentCode = 11, userAgent = "test",
            io = EmptyCoroutineContext, sourceFor = { asked = it; on },
        ).check(force = true)
        assertEquals(1, on.calls)
        assertEquals(URL(SITE_VERSION_JSON), asked)
    }

    /** Проверка обновлений этой сборки: apk — спрашивает сайт, rustore/play — молчит (обновляет магазин). */
    @Test fun updatesFollowStore() = runTest {
        val source = CountingSource()
        checkerWith(BuildConfig.UPDATE_URL, source).check(force = true)
        assertEquals("STORE = ${BuildConfig.STORE}", if (BuildConfig.STORE == "apk") 1 else 0, source.calls)
    }

    /** Приложение берёт проверку из фабрики с адресом из BuildConfig — то, что проверено выше. */
    @Test fun appUsesUpdateCheckerFor() {
        val app = File(root, "jetbox/src/main/java/net/jetnet/jetbox/JetBoxApp.kt").readText()
        assertTrue(app.contains("updates ?: updateCheckerFor("))
        assertTrue(app.contains("updateUrl = BuildConfig.UPDATE_URL,"))
        assertFalse("проверка обновлений собрана мимо фабрики", app.contains("UpdateChecker("))
    }

    /** GPL-3.0: «О программе» ведёт на исходники форка (`JetBoxConfig.SOURCE_CODE_URL`) — во всех вариантах. */
    @Test fun aboutScreenLinksSourceCode() {
        assertEquals("https://github.com/dsm150/jetbox-android", JetBoxConfig.SOURCE_CODE_URL)
        val about = File(root, "jetbox/src/main/java/net/jetnet/jetbox/ui/JetBoxAboutScreen.kt").readText()
        assertTrue("нет ссылки на исходники", about.contains("onOpenUrl(JetBoxConfig.SOURCE_CODE_URL)"))
    }

    /**
     * В коде и ресурсах приложения (main: `:jetbox`, `:app`, `:tool`) нет строк с адресом APK или
     * version.json сайта — иначе они были бы и в магазинной сборке. Комментарии не считаются (в dex
     * не попадают); единственный адрес — `UPDATE_URL` в `jetbox/build.gradle.kts`. По готовому бандлу
     * то же проверяет `jetbox-bundle.sh` (dex, res, root, resources.pb).
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
        assertTrue("нет проверки бандла на адреса APK", script.contains("box\\.jet-net\\.net/android|JetBox\\.apk"))
        for (part in listOf("'base/dex/*'", "'base/res/*'", "'base/root/*'", "'base/resources.pb'"))
            assertTrue("проверка адресов не смотрит $part", script.contains(part))
        assertFalse(
            "grep -q в конвейере под pipefail: SIGPIPE (141) у источника — и проверка молча не срабатывает",
            Regex("""\|\s*(LC_ALL=C\s+)?grep\b[^\n]*\s-[a-zA-Z]*q""").containsMatchIn(code),
        )
        assertTrue("провал validate не останавливает скрипт", script.contains("validate --bundle \"\$out\" >/dev/null || {"))
        assertTrue("нет тестов варианта play перед бандлом", script.contains("-Pjetbox.store=play :jetbox:testDebugUnitTest"))
        assertTrue("нет проверки 16 КБ", script.contains("llvm-readelf") && script.contains("-ge 16384"))
        assertTrue("нет копии в dist/", script.contains("dist/JetBox-\${version_name}-play.aab"))
        assertFalse("пароль в выводе", Regex("echo[^\\n]*\\\$\\{?(password\\b|NETBIRD_UPLOAD_\\w*PASSWORD)").containsMatchIn(script))
    }

    /** Поддельный бандл: ZIP с заданными файлами (содержимое — строки ASCII). */
    private fun fakeBundle(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        return f
    }

    private fun checkApkUrls(aab: File): Int {
        val p = ProcessBuilder("bash", File(root, "jetbox-bundle.sh").path, "--check-apk-urls", aab.path)
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "jetbox-bundle.sh --check-apk-urls завис" }
        return p.exitValue()
    }

    /**
     * Самотест проверки адресов APK в `jetbox-bundle.sh` на подложенных файлах: находит адрес в dex
     * (в начале большого файла — там, где `unzip -p | grep -q` под pipefail ловил SIGPIPE и молчал),
     * в ресурсах, в `resources.pb` и в `base/root`; на чистом бандле — «не найдено»; без dex — ошибка.
     */
    @Test fun bundleScriptFindsApkUrls() {
        val dir = kotlin.io.path.createTempDirectory("jetbox-aab").toFile()
        try {
            // Строки по 80 байт: grep находит адрес в первых строках и выходит, а за ним ещё 4 МБ —
            // ровно тот случай, когда старый конвейер ловил SIGPIPE (воспроизведено 26.10.2026).
            val junk = ByteArray(4 * 1024 * 1024) { if (it % 80 == 0) '\n'.code.toByte() else (it % 61 + 33).toByte() }
            val cleanDex = "dex\n035\u0000https://box.jet-net.net/connect".toByteArray() + junk
            val dirtyDex = "dex\n035\u0000https://box.jet-net.net/android/version.json".toByteArray() + junk
            val base = mapOf(
                "base/manifest/AndroidManifest.xml" to "manifest".toByteArray(),
                "base/dex/classes.dex" to cleanDex,
                "base/res/values/strings.xml" to "JetBox".toByteArray(),
                "base/resources.pb" to "net.jetnet.jetbox".toByteArray(),
                "base/root/META-INF/x.version" to "1".toByteArray(),
                "base/lib/x86_64/libgojni.so" to "https://box.jet-net.net/android/JetBox.apk".toByteArray(),
            )
            assertEquals("чистый бандл (адрес в .so не проверяется)", 0, checkApkUrls(fakeBundle(dir, "clean.aab", base)))
            assertEquals("адрес в dex", 1, checkApkUrls(fakeBundle(dir, "dex.aab", base + ("base/dex/classes2.dex" to dirtyDex))))
            assertEquals("адрес в res", 1, checkApkUrls(fakeBundle(dir, "res.aab", base + ("base/res/raw/a.txt" to "get JetBox.apk".toByteArray()))))
            assertEquals("адрес в resources.pb", 1, checkApkUrls(fakeBundle(dir, "pb.aab", base + ("base/resources.pb" to "x box.jet-net.net/android/ y".toByteArray()))))
            assertEquals("адрес в root", 1, checkApkUrls(fakeBundle(dir, "root.aab", base + ("base/root/notes.txt" to "JetBox.apk".toByteArray()))))
            assertEquals("без dex проверять нечего — ошибка", 2, checkApkUrls(fakeBundle(dir, "nodex.aab", base - "base/dex/classes.dex")))
        } finally {
            dir.deleteRecursively()
        }
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
