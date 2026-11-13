package net.jetnet.jetbox.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Файлы профиля ядра (конфиг с ключом WireGuard, состояние, учётная запись), отложенные на время
 * регистрации с новой личностью устройства. Ядро (`NewAuth` → `UpdateOrCreateConfig`) берёт ключ
 * из существующего конфига и создаёт новый только когда файла нет, поэтому прежние файлы
 * переименовываются в `*.jetbox-prev`, а успех ([commit]) или неудача ([restore]) решают их судьбу.
 *
 * * Первый файл списка — главный (конфиг): его копия удаляется в [commit] последней. Пока она
 *   есть, [restore] возвращает прежнюю личность целиком — это и есть точка фиксации.
 * * Файла не было — вместо копии пустой маркер `*.jetbox-absent`: [restore] удалит то, что ядро
 *   успело создать, и вернёт «как было». Отсутствующие файлы — не ошибка.
 * * Копии и маркеры переживают смерть процесса. Если они остались (процесс умер между stash и
 *   commit/restore), [stash] сначала восстанавливает прежнее — оно совпадает с сохранённым JetBox,
 *   ведь хранилище обновляется только после успешной регистрации. То же делает [AndroidEngine]
 *   при старте ([hasLeftovers] → [restore]).
 * * Переименование, а не копия: файл остаётся тем же (байты, владелец, права MODE_PRIVATE).
 *
 * Чистый JVM: проверяется во временной папке.
 */
class ProfileFilesSwap(
    private val files: List<File>,
    /** Для тестов: порядок удаления копий в [commit]. */
    private val onDelete: (File) -> Unit = {},
) {
    private fun backupOf(f: File) = File(f.path + BACKUP_SUFFIX)
    private fun markerOf(f: File) = File(f.path + ABSENT_SUFFIX)

    /** Остались копии или маркеры прошлого раза. */
    fun hasLeftovers(): Boolean = files.any { backupOf(it).exists() || markerOf(it).exists() }

    /**
     * Отложить файлы. Сначала — разбор остатков прошлого раза ([restore]).
     * @return `true`, если остатки были и прежние файлы восстановлены до stash.
     */
    @Throws(IOException::class)
    fun stash(): Boolean {
        val recovered = hasLeftovers()
        if (recovered) restore()
        for (f in files) {
            if (f.exists()) {
                move(f, backupOf(f))
            } else if (!markerOf(f).createNewFile() && !markerOf(f).exists()) {
                throw IOException("не удалось создать маркер ${markerOf(f).name}")
            }
        }
        return recovered
    }

    /** Новая личность принята: копии и маркеры удаляются, копия главного файла — последней. */
    fun commit() {
        for (f in files.asReversed()) {
            for (leftover in listOf(markerOf(f), backupOf(f))) {
                if (leftover.exists()) {
                    onDelete(leftover)
                    leftover.delete()
                }
            }
        }
    }

    /**
     * Вернуть файлы как были: копия — на место (поверх нового файла), маркер — удалить новый файл.
     * Файл без копии и без маркера не трогается: до него stash не дошёл. Повторный вызов безвреден.
     */
    @Throws(IOException::class)
    fun restore() {
        for (f in files) {
            val backup = backupOf(f)
            val marker = markerOf(f)
            when {
                backup.exists() -> {
                    move(backup, f)
                    marker.delete()
                }
                marker.exists() -> {
                    if (f.exists() && !f.delete()) throw IOException("не удалось удалить ${f.name}")
                    marker.delete()
                }
            }
        }
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val BACKUP_SUFFIX = ".jetbox-prev"
        const val ABSENT_SUFFIX = ".jetbox-absent"

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
    }
}
