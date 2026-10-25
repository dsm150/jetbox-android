package net.jetnet.jetbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Патчи ядра `patches/netbird-core/` (JETBOX-BUILD.md §1, «Патчи ядра»): лежат в форке, ложатся на
 * текущий пин сабмодуля `netbird`, а `build-android-lib.sh` накладывает их перед `gomobile bind`
 * и откатывает после. Покраснел `patchesApplyToCurrentPin` после сдвига пина — патч не ложится или
 * уже принят апстримом: обновить или удалить его (и поднять `.jetbox<N>`), а не тест.
 */
class CorePatchesTest {
    private val root: File = run {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        checkNotNull(dir) { "не найден settings.gradle.kts выше рабочей директории теста" }
    }

    private val patchesDir = File(root, "patches/netbird-core")
    private val patches: List<File> by lazy {
        patchesDir.listFiles { f -> f.name.endsWith(".patch") }.orEmpty().sortedBy { it.name }
    }

    private fun git(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("git", "-C", File(root, "netbird").path) + args)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("git ${args.joinToString(" ")} не завершился", p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    /** Ядро не читает серийный номер и ANDROID_ID и не шлёт их серверу управления (SystemSerialNumber). */
    @Test fun noDeviceSerialPatch() {
        val patch = File(patchesDir, "0001-android-no-device-serial.patch")
        assertTrue("нет ${patch.path}", patch.exists())
        val text = patch.readText()
        assertTrue(text.contains("+++ b/client/system/info_android.go"))
        // Удаляется чтение свойств серийника и фолбэк на ANDROID_ID…
        assertTrue(text.contains("-\tproperties := []string{\"ril.serialnumber\", \"ro.serialno\""))
        assertTrue(text.contains("-\treturn run(\"/system/bin/settings\", \"get\", \"secure\", \"android_id\")"))
        // …а serial() возвращает пустую строку.
        assertTrue(Regex("\\n func serial\\(\\) string \\{\\n[\\s\\S]*?\\n\\+\\treturn \"\"\\n").containsMatchIn(text))
        // Ни одна добавленная строка не читает серийник снова.
        val added = text.lines().filter { it.startsWith("+") && !it.startsWith("+++") }
        assertFalse(added.any { it.contains("getprop(") || it.contains("android_id\"") || it.contains("ro.serialno") })
    }

    /** Каждый патч ложится на текущий пин (сабмодуль инициализирован и чист — патчи откатаны). */
    @Test fun patchesApplyToCurrentPin() {
        assertTrue("нет патчей в ${patchesDir.path}", patches.isNotEmpty())
        assertTrue("сабмодуль netbird не инициализирован", File(root, "netbird/go.mod").exists())
        patches.forEach {
            val (code, out) = git("apply", "--check", it.path)
            assertEquals("${it.name} не ложится на пин ядра:\n$out", 0, code)
        }
    }

    /** Скрипт сборки накладывает все патчи перед `gomobile bind` и откатывает их при любом выходе. */
    @Test fun buildScriptAppliesAndRevertsPatches() {
        val script = File(root, "build-android-lib.sh").readText()
        assertTrue(script.contains("CORE_PATCHES_DIR=\"\$app_path/patches/netbird-core\""))
        assertTrue(script.contains("for patch in \"\$CORE_PATCHES_DIR\"/*.patch; do"))
        assertTrue(script.contains("git -C \"\$app_path/netbird\" apply \"\$patch\""))
        assertTrue(script.contains("git -C \"\$app_path/netbird\" apply -R"))
        assertTrue("откат не на trap EXIT", script.contains("trap revert_core_patches EXIT"))
        val apply = script.indexOf("\napply_core_patches\n")
        val bind = script.indexOf("gomobile bind \\")
        assertTrue("apply_core_patches не вызывается", apply >= 0)
        assertTrue("патчи накладываются не перед gomobile bind", bind > apply)
    }

    /** Сборка с патчем — новая версия ядра `.jetbox<N>` (§1, таблица пинов). */
    @Test fun patchedCoreVersionDocumented() {
        val doc = File(root, "JETBOX-BUILD.md").readText()
        assertTrue(doc.contains("./build-android-lib.sh 0.80.0+01185ba.jetbox2"))
        assertTrue(doc.contains("0001-android-no-device-serial.patch"))
    }
}
