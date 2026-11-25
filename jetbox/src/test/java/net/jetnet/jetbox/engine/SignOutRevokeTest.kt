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
 * «Выйти» 1.0.6 (решение владельца 26.11.2026): есть токен приложения (вход по рабочей почте) — Кабинет отзывает это
 * устройство и освобождает место; отказ, нет метода, нет сети или Кабинет молчит — выход всё равно локальный.
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

    private fun TestScope.signOut(env: Env): Result<Unit> {
        val r = async { env.ob.signOut() }
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

    @Test fun revokeFailureStillSignsOutLocally() = runTest {
        for (r in listOf(DeviceRevokeResult.Failed("HTTP 404"), DeviceRevokeResult.Failed("UnknownHostException"),
            DeviceRevokeResult.AlreadyRevoked)) {
            val env = Env(this)
            env.api.revokeResult = r
            env.signedIn("jbd_t")
            assertTrue(r.toString(), signOut(env).isSuccess)
            assertEquals(1, env.engine.forgetCalls)
            assertNull(env.store.currentBox)
            assertNull(env.store.appToken)
        }
    }

    @Test fun silentCabinetTimesOutThenLocal() = runTest {
        val env = Env(this)
        env.api.revokeHangs = true
        env.signedIn("jbd_t")
        assertTrue(signOut(env).isSuccess)
        assertEquals(1, env.engine.forgetCalls)
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
