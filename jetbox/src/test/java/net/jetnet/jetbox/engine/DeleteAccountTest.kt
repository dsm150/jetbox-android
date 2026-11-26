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
 * «Удалить аккаунт» 1.0.6 (решение владельца 26.11.2026 — правило Google Play для аккаунта, созданного в приложении):
 * `account/delete` с токеном приложения отключает все устройства человека и закрывает вход, затем выход на телефоне.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountTest {
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
        )

        fun signedIn(token: String?) {
            store.currentBox = JetBoxBox("https://netbird.jet-net.net", "sv-fabrika-exit", null, "Fabrika", 1L)
            store.appToken = token
            engine.configExists = true
            engine.emit(VpnDisplayState.CONNECTED)
            engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }
        }
    }

    private fun TestScope.delete(env: Env): Result<Unit> {
        val r = async { env.ob.deleteAccount() }
        advanceUntilIdle()
        return r.getCompleted()
    }

    @Test fun deletedThenSignedOutWithoutSecondRevoke() = runTest {
        val env = Env(this)
        env.signedIn("jbd_secret")
        assertTrue(env.ob.hasAppToken)
        assertTrue(delete(env).isSuccess)
        assertEquals(listOf("jbd_secret"), env.api.deletes)
        assertTrue("отзыв устройства не нужен — Кабинет уже отключил все", env.api.revoked.isEmpty())
        assertEquals(1, env.engine.forgetCalls)
        assertNull(env.store.currentBox)
        assertNull(env.store.appToken)
        assertFalse(env.ob.hasAppToken)
        assertFalse(env.logs.any { "jbd_secret" in it })
    }

    @Test fun unauthorizedTokenStillSignsOut() = runTest {
        val env = Env(this)
        env.api.deleteResult = AccountDeleteResult.Unauthorized
        env.signedIn("jbd_t")
        assertTrue(delete(env).isSuccess)
        assertEquals(1, env.engine.forgetCalls)
        assertNull(env.store.currentBox)
    }

    /** 502, 429, сеть — на телефоне ничего не стёрто, туннель не тронут, можно повторить. */
    @Test fun failureKeepsEverything() = runTest {
        for (details in listOf("HTTP 502", "HTTP 429", "UnknownHostException")) {
            val env = Env(this)
            env.api.deleteResult = AccountDeleteResult.Failed(details)
            env.signedIn("jbd_t")
            assertTrue(delete(env).exceptionOrNull() is JetBoxOnboarding.AccountDeleteFailed)
            assertEquals(0, env.engine.stopCalls)
            assertEquals(0, env.engine.forgetCalls)
            assertEquals("jbd_t", env.store.appToken)
        }
    }

    /** 409 last_owner — единственный владелец клиента: ничего не удалено, на телефоне всё на месте, адрес поддержки — наружу. */
    @Test fun lastOwnerKeepsEverything() = runTest {
        for (support in listOf("support@jet-net.net", null)) {
            val env = Env(this)
            env.api.deleteResult = AccountDeleteResult.LastOwner(support)
            env.signedIn("jbd_t")
            val error = delete(env).exceptionOrNull()
            assertTrue(error is JetBoxOnboarding.LastOwner)
            assertEquals(support, (error as JetBoxOnboarding.LastOwner).support)
            assertEquals(0, env.engine.stopCalls)
            assertEquals(0, env.engine.forgetCalls)
            assertEquals("jbd_t", env.store.appToken)
        }
    }

    /** Подключение по ссылке или QR — токена нет: удаление в Кабинет не идёт (у них веб-страница). */
    @Test fun noTokenNoCall() = runTest {
        val env = Env(this)
        env.signedIn(null)
        assertFalse(env.ob.hasAppToken)
        assertTrue(delete(env).isFailure)
        assertTrue(env.api.deletes.isEmpty())
        assertEquals(0, env.engine.forgetCalls)
    }
}
