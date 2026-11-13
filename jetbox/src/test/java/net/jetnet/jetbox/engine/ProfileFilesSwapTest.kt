package net.jetnet.jetbox.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Отложенные файлы профиля ядра при регистрации с новой личностью устройства
 * ([ProfileFilesSwap]): конфиг с ключом WireGuard, состояние, учётная запись.
 */
class ProfileFilesSwapTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir: File by lazy { tmp.root }
    private val cfg by lazy { File(dir, "netbird.cfg") }
    private val state by lazy { File(dir, "state.json") }
    private val account by lazy { File(dir, "netbird.account.json") }
    private fun swap() = ProfileFilesSwap(listOf(cfg, state, account))

    private val oldCfg = byteArrayOf(0x7b, 0x00, 0xff.toByte(), 0x0a, 'K'.code.toByte(), 0x7d)
    private val oldState = "{\"routes\":[]}".toByteArray()

    private fun backups() = dir.listFiles()!!.filter {
        it.name.endsWith(ProfileFilesSwap.BACKUP_SUFFIX) || it.name.endsWith(ProfileFilesSwap.ABSENT_SUFFIX)
    }

    @Test fun stashMovesFilesAway() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        swap().stash()
        assertFalse("ядро должно создать новый конфиг — со своим ключом", cfg.exists())
        assertFalse(state.exists())
        assertArrayEquals(oldCfg, File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX).readBytes())
        assertArrayEquals(oldState, File(dir, "state.json" + ProfileFilesSwap.BACKUP_SUFFIX).readBytes())
    }

    @Test fun restoreReturnsFilesByteForByte() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val s = swap()
        s.stash()
        cfg.writeText("{\"PrivateKey\":\"new\"}")
        state.writeText("{}")
        s.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertArrayEquals(oldState, state.readBytes())
        assertEquals(emptyList<File>(), backups())
    }

    @Test fun restoreRemovesFilesThatWereAbsent() {
        cfg.writeBytes(oldCfg)
        val s = swap()
        s.stash()
        cfg.writeText("new")
        state.writeText("new state")
        account.writeText("new account")
        s.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertFalse("состояния до регистрации не было — и после отката нет", state.exists())
        assertFalse(account.exists())
        assertEquals(emptyList<File>(), backups())
    }

    @Test fun commitKeepsNewFilesAndDeletesBackups() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val s = swap()
        s.stash()
        cfg.writeText("new")
        s.commit()
        assertEquals("new", cfg.readText())
        assertFalse(state.exists())
        assertEquals(emptyList<File>(), backups())
        assertFalse(swap().hasLeftovers())
    }

    @Test fun missingFilesAreNotAnError() {
        val s = swap()
        s.stash()
        s.restore()
        s.stash()
        cfg.writeText("new")
        s.commit()
        assertEquals("new", cfg.readText())
        assertEquals(emptyList<File>(), backups())
    }

    @Test fun permissionsSurviveStashAndRestore() {
        cfg.writeBytes(oldCfg)
        val perms = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(cfg.toPath(), perms)
        val s = swap()
        s.stash()
        assertEquals(perms, Files.getPosixFilePermissions(File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX).toPath()))
        cfg.writeText("new")
        s.restore()
        assertEquals(perms, Files.getPosixFilePermissions(cfg.toPath()))
    }

    /** Процесс умер между stash и commit/restore: следующий stash сначала возвращает прежнее. */
    @Test fun stashOverLeftoversRestoresFirst() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        swap().stash()
        cfg.writeText("crashed attempt") // ядро успело создать новый конфиг, а процесс умер

        val again = swap()
        assertTrue(again.hasLeftovers())
        assertTrue("копии прошлого раза восстановлены до нового stash", again.stash())
        assertFalse(cfg.exists())
        assertArrayEquals(
            "в копии — исходный конфиг, а не конфиг оборванной попытки",
            oldCfg, File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX).readBytes(),
        )
        again.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertArrayEquals(oldState, state.readBytes())
    }

    @Test fun stashWithoutLeftoversReportsNothingRecovered() {
        cfg.writeBytes(oldCfg)
        assertFalse(swap().stash())
    }

    /** Восстановление при старте приложения: другой экземпляр, без stash. */
    @Test fun restoreByFreshInstanceRecoversLeftovers() {
        cfg.writeBytes(oldCfg)
        swap().stash()
        cfg.writeText("crashed attempt")
        val fresh = swap()
        assertTrue(fresh.hasLeftovers())
        fresh.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertEquals(emptyList<File>(), backups())
    }

    /** Смерть посреди stash: второй файл ещё не тронут — восстановление его не удаляет. */
    @Test fun crashMidStashKeepsUntouchedFiles() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        assertTrue(cfg.renameTo(File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX)))
        swap().restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertArrayEquals(oldState, state.readBytes())
    }

    /** Смерть посреди commit: копия конфига удаляется последней — пока она есть, откат цельный. */
    @Test fun commitRemovesPrimaryBackupLast() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val order = mutableListOf<String>()
        val s = ProfileFilesSwap(listOf(cfg, state, account), onDelete = { order += it.name })
        s.stash()
        s.commit()
        assertEquals("netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX, order.last())
    }

    @Test fun accountPathFollowsCore() {
        assertEquals("/d/netbird.account.json", ProfileFilesSwap.accountPathFor("/d/netbird.cfg"))
        assertEquals("/d/profiles/4c5f.account.json", ProfileFilesSwap.accountPathFor("/d/profiles/4c5f.json"))
    }
}
