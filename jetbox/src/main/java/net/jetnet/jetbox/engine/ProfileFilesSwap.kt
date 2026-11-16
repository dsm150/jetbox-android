package net.jetnet.jetbox.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Файлы профиля ядра (конфиг с ключом WireGuard, состояние, учётная запись), отложенные на время
 * регистрации с новой личностью устройства. Ядро (`NewAuth` → `UpdateOrCreateConfig`) берёт ключ
 * из существующего конфига и создаёт новый только когда файла нет, поэтому прежние файлы
 * переименовываются в `*.jetbox-prev`, а успех ([commit]) или неудача ([restore]) решают их судьбу.
 * Порядок шагов и решение после смерти процесса — [IdentityRegistration].
 *
 * * **Метка `<конфиг>.jetbox-pending`** пишется первой в [stash] (на диск — с `fsync`): отпечаток
 *   setup key новой ссылки и список файлов. По ней восстановление при старте ([fromPending],
 *   [IdentityRegistration.recover]) решает, чья личность на диске, не загружая ядро: отпечаток в
 *   хранилище совпал с меткой — замена удалась (хранилище пишется после входа) → [commit], иначе
 *   → [restore]. Метка удаляется последней — и в [commit], и в [restore].
 * * Первый файл списка — главный (конфиг): его копия удаляется в [commit] последней из копий.
 * * Файла не было — вместо копии пустой маркер `*.jetbox-absent`: [restore] удалит то, что ядро
 *   успело создать, и вернёт «как было». Отсутствующие файлы — не ошибка.
 * * [commit] проверяет каждое удаление: не удалось (и повтор не помог) — `IOException`, метка
 *   остаётся, и следующий старт (или следующая регистрация) доделает фиксацию. Без проверки
 *   оставшаяся копия конфига выглядела бы как оборванная замена и вернула бы прежний ключ.
 * * Переименование, а не копия: файл остаётся тем же (байты, владелец, права MODE_PRIVATE).
 *
 * Чистый JVM: проверяется во временной папке.
 */
class ProfileFilesSwap(
    private val files: List<File>,
    /** Для тестов: удаление файла; `false` — «не удалось». */
    private val deleter: (File) -> Boolean = { it.delete() },
) {
    init {
        require(files.isNotEmpty()) { "нет файлов профиля" }
    }

    private val primary: File get() = files.first()

    /** Метка незавершённой замены рядом с конфигом. */
    val pendingFile: File get() = File(primary.path + PENDING_SUFFIX)

    private fun backupOf(f: File) = File(f.path + BACKUP_SUFFIX)
    private fun markerOf(f: File) = File(f.path + ABSENT_SUFFIX)

    /** Остались метка, копии или маркеры прошлого раза. */
    fun hasLeftovers(): Boolean =
        pendingFile.exists() || files.any { backupOf(it).exists() || markerOf(it).exists() }

    /** Есть ли прежний конфиг — на месте или отложенный (оборванная замена). */
    fun hasConfig(): Boolean = primary.exists() || backupOf(primary).exists()

    /** Отпечаток setup key из метки; `null` — метки нет или она нечитаема. */
    fun pendingFingerprint(): String? = readPending(pendingFile)?.first

    /**
     * Отложить файлы: сначала метка с [newFingerprint], потом переименования. Остатки прошлого раза
     * сюда не доходят — их разбирает [IdentityRegistration.recover]; если они есть — `IOException`.
     * Сбой посреди stash оставляет остатки, их вернёт [restore].
     */
    @Throws(IOException::class)
    fun stash(newFingerprint: String) {
        if (hasLeftovers()) throw IOException("остатки прошлой замены не разобраны")
        writePending(newFingerprint)
        for (f in files) {
            if (f.exists()) {
                move(f, backupOf(f))
            } else if (!markerOf(f).createNewFile() && !markerOf(f).exists()) {
                throw IOException("не удалось создать маркер ${markerOf(f).name}")
            }
        }
    }

    /**
     * Новая личность принята и записана в хранилище: маркеры и копии удаляются (копия конфига —
     * последней), метка — после всех. Неудача удаления — `IOException`, метка остаётся.
     */
    @Throws(IOException::class)
    fun commit() {
        for (f in files.asReversed()) {
            remove(markerOf(f))
            remove(backupOf(f))
        }
        remove(pendingFile)
    }

    /**
     * Вернуть файлы как были: копия — на место (поверх нового файла), маркер — удалить новый файл.
     * Файл без копии и без маркера не трогается: до него stash не дошёл. Метка — последней.
     * Повторный вызов безвреден.
     */
    @Throws(IOException::class)
    fun restore() {
        for (f in files) {
            val backup = backupOf(f)
            val marker = markerOf(f)
            when {
                backup.exists() -> {
                    move(backup, f)
                    remove(marker)
                }
                marker.exists() -> {
                    remove(f)
                    remove(marker)
                }
            }
        }
        remove(pendingFile)
    }

    /** Удалить, если есть; не вышло и со второй попытки — `IOException`. */
    private fun remove(f: File) {
        repeat(2) {
            if (!f.exists() || deleter(f) || !f.exists()) return
        }
        throw IOException("не удалось удалить ${f.name}")
    }

    private fun writePending(fingerprint: String) {
        val json = JSONObject()
            .put(K_FINGERPRINT, fingerprint)
            .put(K_FILES, JSONArray(files.map { it.path }))
            .toString()
        val tmp = File(pendingFile.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        move(tmp, pendingFile)
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val BACKUP_SUFFIX = ".jetbox-prev"
        const val ABSENT_SUFFIX = ".jetbox-absent"
        const val PENDING_SUFFIX = ".jetbox-pending"
        private const val K_FINGERPRINT = "keyFingerprint"
        private const val K_FILES = "files"

        /**
         * Файл учётной записи профиля рядом с конфигом — как `profileAccountPathFor` ядра
         * (`client/mobile/profile_state.go`): `netbird.cfg` → `netbird.account.json`,
         * `<id>.json` → `<id>.account.json`.
         */
        fun accountPathFor(configPath: String): String {
            val file = File(configPath)
            val base = file.name.substringBeforeLast('.')
            return File(file.parentFile, base + ".account.json").path
        }

        /**
         * Метки незавершённых замен в каталоге конфигов ядра (`filesDir`) и в `profiles/` — там
         * ядро держит конфиги профилей (`client/mobile/profile_manager.go`). Ядро не загружается.
         */
        fun findPending(configDir: File): List<File> =
            listOf(configDir, File(configDir, "profiles")).flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(PENDING_SUFFIX) }?.toList().orEmpty()
            }

        /**
         * Замена по её метке — список файлов записан в метке. Метка нечитаема (оборвана запись) —
         * stash до переименований не дошёл: хватит одного конфига, чтобы убрать метку.
         */
        fun fromPending(pending: File): ProfileFilesSwap {
            val config = File(pending.path.removeSuffix(PENDING_SUFFIX))
            val files = readPending(pending)?.second?.map(::File)?.takeIf { it.firstOrNull() == config }
                ?: listOf(config)
            return ProfileFilesSwap(files)
        }

        private fun readPending(pending: File): Pair<String, List<String>>? = runCatching {
            val o = JSONObject(pending.readText(Charsets.UTF_8))
            val arr = o.getJSONArray(K_FILES)
            o.getString(K_FINGERPRINT) to (0 until arr.length()).map { arr.getString(it) }
        }.getOrNull()
    }
}
