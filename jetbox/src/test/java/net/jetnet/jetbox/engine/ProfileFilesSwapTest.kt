package net.jetnet.jetbox.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Отложенные файлы профиля ядра при регистрации с новой личностью устройства
 * ([ProfileFilesSwap]): конфиг с ключом WireGuard, состояние, учётная запись, метка замены.
 */
class ProfileFilesSwapTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir: File by lazy { tmp.root }
    private val cfg by lazy { File(dir, "netbird.cfg") }
    private val state by lazy { File(dir, "state.json") }
    private val account by lazy { File(dir, "netbird.account.json") }
    private fun swap(deleter: (File) -> Boolean = { it.delete() }) = ProfileFilesSwap(listOf(cfg, state, account), deleter)

    private val oldCfg = byteArrayOf(0x7b, 0x00, 0xff.toByte(), 0x0a, 'K'.code.toByte(), 0x7d)
    private val oldState = "{\"routes\":[]}".toByteArray()
    private val fp = "0123456789abcdef0123456789abcdef"

    private fun leftovers() = dir.walk().filter {
        it.isFile && listOf(ProfileFilesSwap.BACKUP_SUFFIX, ProfileFilesSwap.ABSENT_SUFFIX, ProfileFilesSwap.PENDING_SUFFIX)
            .any { s -> it.name.endsWith(s) }
    }.toList()

    @Test fun stashWritesPendingAndMovesFilesAway() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val s = swap()
        s.stash(fp)
        assertFalse("ядро должно создать новый конфиг — со своим ключом", cfg.exists())
        assertFalse(state.exists())
        assertArrayEquals(oldCfg, File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX).readBytes())
        assertArrayEquals(oldState, File(dir, "state.json" + ProfileFilesSwap.BACKUP_SUFFIX).readBytes())
        assertEquals(File(dir, "netbird.cfg" + ProfileFilesSwap.PENDING_SUFFIX), s.pendingFile)
        assertEquals(fp, s.pendingFingerprint())
        assertFalse("во временном файле метки ничего не осталось", File(s.pendingFile.path + ".tmp").exists())
    }

    @Test fun restoreReturnsFilesByteForByte() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val s = swap()
        s.stash(fp)
        cfg.writeText("{\"PrivateKey\":\"new\"}")
        state.writeText("{}")
        s.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertArrayEquals(oldState, state.readBytes())
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test fun restoreRemovesFilesThatWereAbsent() {
        cfg.writeBytes(oldCfg)
        val s = swap()
        s.stash(fp)
        cfg.writeText("new")
        state.writeText("new state")
        account.writeText("new account")
        s.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertFalse("состояния до регистрации не было — и после отката нет", state.exists())
        assertFalse(account.exists())
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test fun commitKeepsNewFilesAndDeletesBackupsAndPending() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val s = swap()
        s.stash(fp)
        cfg.writeText("new")
        s.commit()
        assertEquals("new", cfg.readText())
        assertFalse(state.exists())
        assertEquals(emptyList<File>(), leftovers())
        assertFalse(swap().hasLeftovers())
    }

    @Test fun missingFilesAreNotAnError() {
        val s = swap()
        s.stash(fp)
        s.restore()
        s.stash(fp)
        cfg.writeText("new")
        s.commit()
        assertEquals("new", cfg.readText())
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test fun permissionsSurviveStashAndRestore() {
        cfg.writeBytes(oldCfg)
        val perms = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(cfg.toPath(), perms)
        val s = swap()
        s.stash(fp)
        assertEquals(perms, Files.getPosixFilePermissions(File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX).toPath()))
        cfg.writeText("new")
        s.restore()
        assertEquals(perms, Files.getPosixFilePermissions(cfg.toPath()))
    }

    /** Остатки разбирает [IdentityRegistration.recover]: stash поверх них — отказ, файлы не тронуты. */
    @Test fun stashRefusesLeftovers() {
        cfg.writeBytes(oldCfg)
        swap().stash(fp)
        cfg.writeText("crashed attempt")
        try {
            swap().stash("ffff")
            fail("stash поверх остатков")
        } catch (_: IOException) {
        }
        assertEquals("crashed attempt", cfg.readText())
        assertEquals(fp, swap().pendingFingerprint())
    }

    /** Восстановление при старте приложения: другой экземпляр — по метке, без ядра. */
    @Test fun fromPendingFindsFilesWithoutCore() {
        val profiles = File(dir, "profiles").apply { mkdirs() }
        val pcfg = File(profiles, "4c5f.json").apply { writeBytes(oldCfg) }
        val pstate = File(profiles, "4c5f.state.json").apply { writeBytes(oldState) }
        val pacc = File(ProfileFilesSwap.accountPathFor(pcfg.path))
        ProfileFilesSwap(listOf(pcfg, pstate, pacc)).stash(fp)
        pcfg.writeText("crashed attempt")
        pacc.writeText("crashed account")

        val found = ProfileFilesSwap.findPending(dir)
        assertEquals(listOf(File(profiles, "4c5f.json" + ProfileFilesSwap.PENDING_SUFFIX)), found)
        val again = ProfileFilesSwap.fromPending(found.single())
        assertEquals(fp, again.pendingFingerprint())
        again.restore()
        assertArrayEquals(oldCfg, pcfg.readBytes())
        assertArrayEquals(oldState, pstate.readBytes())
        assertFalse(pacc.exists())
        assertEquals(emptyList<File>(), leftovers())
        assertEquals(emptyList<File>(), ProfileFilesSwap.findPending(dir))
    }

    @Test fun unreadablePendingFallsBackToConfigAndIsRemoved() {
        cfg.writeBytes(oldCfg)
        val pending = File(dir, "netbird.cfg" + ProfileFilesSwap.PENDING_SUFFIX).apply { writeText("{оборвано") }
        val s = ProfileFilesSwap.fromPending(pending)
        assertNull(s.pendingFingerprint())
        assertTrue(s.hasLeftovers())
        s.restore()
        assertArrayEquals(oldCfg, cfg.readBytes())
        assertFalse(pending.exists())
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

    /** Смерть посреди commit: копия конфига — последней из копий, метка — после всех. */
    @Test fun commitRemovesPrimaryBackupLastAndPendingAfterAll() {
        cfg.writeBytes(oldCfg)
        state.writeBytes(oldState)
        val order = mutableListOf<String>()
        val s = swap { order += it.name; it.delete() }
        s.stash(fp)
        s.commit()
        assertEquals(
            listOf(
                "state.json" + ProfileFilesSwap.BACKUP_SUFFIX,
                "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX,
                "netbird.cfg" + ProfileFilesSwap.PENDING_SUFFIX,
            ),
            order.filter { !it.endsWith(ProfileFilesSwap.ABSENT_SUFFIX) },
        )
    }

    /** Копия не удалилась — не успех: исключение, метка осталась (следующий старт доделает). */
    @Test fun commitFailsWhenBackupCannotBeDeleted() {
        cfg.writeBytes(oldCfg)
        val stuck = File(dir, "netbird.cfg" + ProfileFilesSwap.BACKUP_SUFFIX)
        var attempts = 0
        val s = swap { f -> if (f == stuck) { attempts++; false } else f.delete() }
        s.stash(fp)
        cfg.writeText("new")
        try {
            s.commit()
            fail("commit без удаления копии конфига")
        } catch (_: IOException) {
        }
        assertEquals("одна повторная попытка", 2, attempts)
        assertTrue(stuck.exists())
        assertEquals("метка цела — разбор при старте", fp, swap().pendingFingerprint())
        swap().commit()
        assertEquals("new", cfg.readText())
        assertEquals(emptyList<File>(), leftovers())
    }

    /** Удаление «не удалось», но файла уже нет (удалил кто-то ещё) — это успех. */
    @Test fun deleteReportingFalseButFileGoneIsSuccess() {
        cfg.writeBytes(oldCfg)
        val s = swap { f -> f.delete(); false }
        s.stash(fp)
        cfg.writeText("new")
        s.commit()
        assertEquals(emptyList<File>(), leftovers())
    }

    @Test fun hasConfigSeesStashedConfig() {
        assertFalse(swap().hasConfig())
        cfg.writeBytes(oldCfg)
        assertTrue(swap().hasConfig())
        swap().stash(fp)
        assertTrue("прежний конфиг отложен — личность есть", swap().hasConfig())
    }

    @Test fun accountPathFollowsCore() {
        assertEquals("/d/netbird.account.json", ProfileFilesSwap.accountPathFor("/d/netbird.cfg"))
        assertEquals("/d/profiles/4c5f.account.json", ProfileFilesSwap.accountPathFor("/d/profiles/4c5f.json"))
    }
}
