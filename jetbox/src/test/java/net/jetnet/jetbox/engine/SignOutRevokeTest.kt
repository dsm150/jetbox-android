package net.jetnet.jetbox.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Выйти» 1.0.6 (решение владельца 26.11.2026, контракт Кабинета db532b4): есть токен приложения (вход по рабочей
 * почте) — Кабинет отзывает это устройство и освобождает место. 200 или 401 — выход; 502, 429, нет сети или Кабинет
 * молчит — ничего не стёрто, человек выбирает: повторить или выйти только на телефоне (`localOnly`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutRevokeTest {
    private val box = JetBoxBox("https://netbird.jet-net.net", "sv-fabrika-exit", null, "Fabrika", 1L)

    private class Env(scope: TestScope) {
        val engine = FakeEngine()
        val store = JetBoxStore(MemoryStore())
        val api = FakeLoginApi()
        val logs = mutableListOf<String>()
        val ob = JetBoxOnboarding(
            engine, store, CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            stopTimeoutMs = 5_000,
            log = { logs += it },
            login = api,
            revokeTimeoutMs = 10_000,
        )
    }

    private fun Env.signedIn(token: String?) {
        store.currentBox = JetBoxBox("https://netbird.jet-net.net", "sv-fabrika-exit", null, "Fabrika", 1L)
        store.appToken = token
        engine.configExists = true
        engine.emit(VpnDisplayState.CONNECTED)
        engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }
    }

    private fun TestScope.signOut(env: Env, localOnly: Boolean = false): Result<Unit> {
        val r = async { env.ob.signOut(localOnly) }
        advanceUntilIdle()
        return r.getCompleted()
    }

    @Test fun tokenIsRevokedThenEverythingForgotten() = runTest {
        val env = Env(this)
        env.signedIn("jbd_secret_token")
        assertTrue(signOut(env).isSuccess)
        assertEquals(listOf("jbd_secret_token"), env.api.revoked)
        assertEquals(1, env.engine.forgetCalls)
        assertNull(env.store.currentBox)
        assertNull("токен забыт", env.store.appToken)
        assertFalse("токен не в журнале", env.logs.any { "jbd_secret_token" in it })
    }

    /** 401 bad_token — Кабинет токена не знает: отзывать нечего, выход на телефоне. */
    @Test fun unknownTokenSignsOutLocally() = runTest {
        val env = Env(this)
        env.api.revokeResult = DeviceRevokeResult.AlreadyRevoked
        env.signedIn("jbd_t")
        assertTrue(signOut(env).isSuccess)
        assertEquals(1, env.engine.forgetCalls)
        assertNull(env.store.currentBox)
        assertNull(env.store.appToken)
    }

    /** 502, 429, нет сети — ничего не стёрто, туннель не тронут; «Повторить» снова идёт в Кабинет. */
    @Test fun revokeFailureKeepsEverythingAndRetries() = runTest {
        for (details in listOf("HTTP 502", "HTTP 429", "UnknownHostException")) {
            val env = Env(this)
            env.api.revokeResult = DeviceRevokeResult.Failed(details)
            env.signedIn("jbd_t")
            val first = signOut(env)
            assertTrue(details, first.exceptionOrNull() is JetBoxOnboarding.RevokeFailed)
            assertEquals(0, env.engine.stopCalls)
            assertEquals(0, env.engine.forgetCalls)
            assertEquals("jbd_t", env.store.appToken)
            assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
            env.api.revokeResult = DeviceRevokeResult.Revoked
            assertTrue(signOut(env).isSuccess)
            assertEquals(listOf("jbd_t", "jbd_t"), env.api.revoked)
            assertNull(env.store.appToken)
        }
    }

    /** «Только на телефоне» — локальный выход без Кабинета; место освобождает администратор. */
    @Test fun localOnlySkipsCabinet() = runTest {
        val env = Env(this)
        env.api.revokeResult = DeviceRevokeResult.Failed("HTTP 502")
        env.signedIn("jbd_t")
        assertTrue(signOut(env).isFailure)
        assertTrue(signOut(env, localOnly = true).isSuccess)
        assertEquals(listOf("jbd_t"), env.api.revoked)
        assertEquals(1, env.engine.forgetCalls)
        assertNull(env.store.currentBox)
        assertNull(env.store.appToken)
    }

    @Test fun silentCabinetTimesOutAndAsks() = runTest {
        val env = Env(this)
        env.api.revokeHangs = true
        env.signedIn("jbd_t")
        assertTrue(signOut(env).exceptionOrNull() is JetBoxOnboarding.RevokeFailed)
        assertEquals(0, env.engine.forgetCalls)
        assertTrue(env.logs.any { "не ответил" in it })
    }

    /** Подключение по ссылке или QR — токена нет: Кабинет не трогаем, выход локальный. */
    @Test fun noTokenNoCabinetCall() = runTest {
        val env = Env(this)
        env.signedIn(null)
        assertTrue(signOut(env).isSuccess)
        assertTrue(env.api.revoked.isEmpty())
        assertEquals(1, env.engine.forgetCalls)
    }

    /** Токен из ответа verify сохраняется с подключением; ссылка извне его не несёт; другой JetBox — прежний забыт. */
    @Test fun appTokenStoredOnlyFromVerify() = runTest {
        val env = Env(this)
        val key = "11111111-2222-4333-8444-555555555555"
        val link = "https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=sv-fabrika-exit&n=Fabrika"
        env.engine.onStart = { it.emit(VpnDisplayState.CONNECTED) }
        assertTrue(env.ob.handle(link, trusted = true, appToken = "jbd_from_verify"))
        env.ob.connect()
        advanceUntilIdle()
        assertEquals("jbd_from_verify", env.store.appToken)
        assertFalse(env.logs.any { "jbd_from_verify" in it })
        // Чужой (ненадёжный) путь токен не передаёт, даже если его подсунуть.
        assertEquals(null, (net.jetnet.jetbox.link.JetBoxLink.parse(link, trusted = true) as
            net.jetnet.jetbox.link.JetBoxLink.Outcome.Payload).payload.appToken)
    }
}
