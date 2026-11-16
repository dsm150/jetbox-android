package net.jetnet.jetbox.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.IdentityRegistration.Recovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Настоящая последовательность замены личности ([IdentityRegistration]): stash → вход → запись
 * хранилища → commit/restore, на настоящих файлах во временной папке. Вход и хранилище — подставные;
 * «смерть процесса» — [ProcessDeath] (не `Exception`: его не ловит ни один обработчик сценария).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRegistrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ProcessDeath : Throwable()

    private val oldFp = "old-fingerprint"
    private val newFp = "new-fingerprint"

    private inner class Env {
        val dir: File = tmp.newFolder()
        val cfg = File(dir, "netbird.cfg").apply { writeText("key=OLD") }
        val state = File(dir, "state.json").apply { writeText("state OLD") }
        val account = File(dir, "netbird.account.json")
        var failDelete: (File) -> Boolean = { false }
        val swap get() = ProfileFilesSwap(listOf(cfg, state, account)) { f -> if (failDelete(f)) false else f.delete() }

        /** Отпечаток в «хранилище». */
        var stored: String? = oldFp
        var running = false
        var gateClosed = false
        val gateLog = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val gate = object : IdentityRegistration.TunnelGate {
            override fun close() { gateClosed = true; gateLog += "close" }
            override fun open() { gateClosed = false; gateLog += "open" }
            override fun isEngineRunning() = running
        }

        fun registration() = IdentityRegistration(swap, gate) { logs += it }

        /** Вход ядра: `NewAuth` пишет новый конфиг с новым ключом (файла нет — значит, новый). */
        fun coreWritesNewConfig() {
            check(!cfg.exists()) { "ядро застало прежний конфиг — ключ остался бы прежним" }
            cfg.writeText("key=NEW")
            state.writeText("state NEW")
        }

        suspend fun register(login: suspend () -> Exception? = { coreWritesNewConfig(); null }): Result<Unit> =
            registration().register(newFp, stored, login) { stored = newFp }

        fun leftovers() = dir.listFiles()!!.filter { f -> f.name.contains(".jetbox-") }

        /** Старт процесса: как `JetBoxProcess.startup` — по меткам в каталоге, без ядра. */
        fun startupRecovery(): List<Recovery?> = ProfileFilesSwap.findPending(dir).map {
            IdentityRegistration.recover(ProfileFilesSwap.fromPending(it), stored)
        }

        fun assertOld() {
            assertEquals("key=OLD", cfg.readText())
            assertEquals("state OLD", state.readText())
            assertFalse(account.exists())
            assertEquals(oldFp, stored)
            assertEquals(emptyList<File>(), leftovers())
        }

        fun assertNew() {
            assertEquals("key=NEW", cfg.readText())
            assertEquals(newFp, stored)
            assertEquals(emptyList<File>(), leftovers())
        }
    }

    @Test fun successOrder() = runTest {
        val env = Env()
        val order = mutableListOf<String>()
        val r = env.registration().register(newFp, env.stored, login = {
            assertTrue("запуск ядра закрыт на время входа", env.gateClosed)
            assertEquals("метка с отпечатком новой ссылки — до входа", newFp, env.swap.pendingFingerprint())
            order += "login"
            env.coreWritesNewConfig()
            null
        }) {
            assertTrue("копии ещё на месте: хранилище пишется до commit", env.leftovers().isNotEmpty())
            order += "remember"
            env.stored = newFp
        }
        assertTrue(r.isSuccess)
        assertEquals(listOf("login", "remember"), order)
        env.assertNew()
        assertEquals(listOf("close", "open"), env.gateLog)
    }

    @Test fun loginErrorRestores() = runTest {
        val env = Env()
        val r = env.register { env.coreWritesNewConfig(); Exception("invalid setup key") }
        assertEquals("invalid setup key", r.exceptionOrNull()?.message)
        env.assertOld()
        assertFalse(env.gateClosed)
    }

    @Test fun cancelDuringLoginRestores() = runTest {
        val env = Env()
        val inLogin = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Exception?>()
        var remembered = false
        val job = launch {
            env.registration().register(newFp, env.stored, login = {
                env.coreWritesNewConfig()
                inLogin.complete(Unit)
                never.await()
            }) { remembered = true }
        }
        inLogin.await()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse(remembered)
        env.assertOld()
        assertFalse("запуск ядра снова открыт — прежний туннель можно поднять", env.gateClosed)
    }

    @Test fun stashFailureRestoresAndSkipsLogin() = runTest {
        val env = Env()
        // Учётная запись — в несуществующем каталоге: маркер не создать, stash падает на третьем файле.
        val broken = File(env.dir, "missing/netbird.account.json")
        var loginCalled = false
        val r = IdentityRegistration(ProfileFilesSwap(listOf(env.cfg, env.state, broken)), env.gate)
            .register(newFp, env.stored, { loginCalled = true; null }) { env.stored = newFp }
        assertTrue(r.isFailure)
        assertFalse(loginCalled)
        env.assertOld()
    }

    @Test fun restoreFailureIsSeparateErrorAndRetryRestoresFirst() = runTest {
        val env = Env()
        env.state.delete() // состояния не было: при откате ядро-созданный state.json надо удалить
        env.failDelete = { it == env.state }
        val r = env.register { env.coreWritesNewConfig(); Exception("invalid setup key") }
        val e = r.exceptionOrNull()
        assertTrue("$e", e is IdentityRegistration.RestoreFailed)
        assertFalse(env.gateClosed)
        assertEquals(oldFp, env.stored)
        assertEquals("метка цела", newFp, env.swap.pendingFingerprint())

        // «Попробовать снова»: сначала разбор остатков (возврат), потом новая попытка.
        env.failDelete = { false }
        val again = env.register()
        assertTrue(again.isSuccess)
        env.assertNew()
        assertTrue(env.logs.any { it.contains("RESTORED") })
    }

    @Test fun commitDeleteFailureStillSucceedsAndStartupFinishesCommit() = runTest {
        val env = Env()
        val backup = File(env.cfg.path + ProfileFilesSwap.BACKUP_SUFFIX)
        env.failDelete = { it == backup }
        val r = env.register()
        assertTrue("хранилище уже новое — регистрация удалась", r.isSuccess)
        assertEquals(newFp, env.stored)
        assertTrue(backup.exists())
        assertEquals("метка цела: следующий старт не примет копию за оборванную замену", newFp, env.swap.pendingFingerprint())

        env.failDelete = { false }
        assertEquals(listOf(Recovery.COMMITTED), env.startupRecovery())
        env.assertNew()
    }

    @Test fun deathBetweenRememberAndCommitCommitsOnStartup() = runTest {
        val env = Env()
        try {
            env.registration().register(newFp, env.stored, { env.coreWritesNewConfig(); null }) {
                env.stored = newFp
                throw ProcessDeath()
            }
            fail()
        } catch (_: ProcessDeath) {
        }
        assertTrue(env.leftovers().isNotEmpty())
        assertEquals(listOf(Recovery.COMMITTED), env.startupRecovery())
        env.assertNew()
    }

    @Test fun deathBeforeRememberRestoresOnStartup() = runTest {
        val env = Env()
        try {
            env.register { env.coreWritesNewConfig(); throw ProcessDeath() }
            fail()
        } catch (_: ProcessDeath) {
        }
        assertEquals("key=NEW", env.cfg.readText())
        assertEquals(listOf(Recovery.RESTORED), env.startupRecovery())
        env.assertOld()
    }

    @Test fun deathMidStashRestoresOnStartup() = runTest {
        val env = Env()
        // Метка записана, конфиг отложен, до состояния stash не дошёл.
        env.swap.stash(newFp)
        File(env.state.path + ProfileFilesSwap.BACKUP_SUFFIX).renameTo(env.state)
        assertEquals(listOf(Recovery.RESTORED), env.startupRecovery())
        env.assertOld()
    }

    @Test fun firstRegistrationWithEmptyStoreRestoresOnStartup() = runTest {
        // Конфиг не от JetBox, хранилище пусто: умерли до записи хранилища — вернуть чужой конфиг.
        val env = Env()
        env.stored = null
        try {
            env.registration().register(newFp, null, { env.coreWritesNewConfig(); throw ProcessDeath() }) {}
            fail()
        } catch (_: ProcessDeath) {
        }
        assertEquals(listOf(Recovery.RESTORED), env.startupRecovery())
        assertEquals("key=OLD", env.cfg.readText())
    }

    @Test fun runningEngineRefusedBeforeTouchingFiles() = runTest {
        val env = Env()
        env.running = true
        var loginCalled = false
        val r = env.register { loginCalled = true; null }
        assertTrue(r.exceptionOrNull() is IdentityRegistration.EngineStillRunning)
        assertFalse(loginCalled)
        env.assertOld()
        assertEquals(listOf("close", "open"), env.gateLog)
    }

    @Test fun nothingToRecover() {
        val env = Env()
        assertNull(IdentityRegistration.recover(env.swap, oldFp))
        assertEquals(emptyList<Recovery?>(), env.startupRecovery())
    }
}
