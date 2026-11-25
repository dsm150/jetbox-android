package net.jetnet.jetbox.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.logic.JetBoxFailure
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Код из письма → ответ verify RU-Кабинета → тот же путь, что у ссылки из Кабинета: «Ваш JetBox» и одна кнопка
 * (RU-сценарий не меняется). Ответ — по контракту 06-cabinet.md §2а. Ссылка из verify — доверенная: подпись `s`
 * не проверяется (её может и не быть), `p` и `b` RU-приложение пропускает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailCodeSignInTest {
    private val key = "11111111-2222-4333-8444-555555555555"
    private val email = "ivan@fabrika-mfc.ru"
    private val exit = RouteInfo("sv-fabrika-exit", "0.0.0.0/0, ::/0", selected = false)

    private class Env(scope: TestScope) {
        val engine = FakeEngine()
        val store = JetBoxStore(MemoryStore())
        val api = FakeLoginApi()
        private val main = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob())
        val ob = JetBoxOnboarding(engine, store, main, log = {}, clock = { 7_000L })
        // Как в приложении: ссылка из ответа verify — доверенная, подпись JetBox safety не нужна.
        val form = EmailLoginForm(api, main, clock = { 0L }, onConnectUrl = { ob.handle(it, trusted = true) })
        init {
            engine.storedFingerprint = { store.currentBox?.setupKeyFingerprint }
            engine.onStart = {
                it.emit(VpnDisplayState.CONNECTED)
            }
        }
    }

    private fun TestScope.signIn(env: Env, connectUrl: String) {
        env.form.submit(email)
        advanceUntilIdle()
        env.api.codeResults += LoginVerifyResult.Success(connectUrl)
        env.form.submitCode("012345")
        advanceUntilIdle()
    }

    @Test fun signedCabinetLinkLeadsToReadyThenProtected() = runTest {
        val env = Env(this)
        val link = "https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=sv-fabrika-exit" +
            "&n=Fabrika&p=android&b=100.64.0.7&s=XY_KvXXY0YNGAxTwvJ_htjoL8Cm21W32NSuBWtG_CydTZeRFNku3RjxtLw7Todmn0WcstEA1Wjru0b24cCP3AA"
        signIn(env, link)

        val ready = env.ob.step.value as Step.Ready
        assertEquals(key, ready.payload.setupKey)
        assertEquals("https://netbird.jet-net.net", ready.payload.managementURL)
        assertEquals("sv-fabrika-exit", ready.payload.exitNodeName)
        assertEquals("Fabrika", ready.payload.boxName)
        assertTrue(ready.payload.enablesDefaultExit)
        assertEquals("регистрация — только по кнопке, как у ссылки", 0, env.engine.registerCalls)
        assertEquals(EmailLoginForm.State.Editing(), env.form.state.value)

        env.engine.onStart = {
            it.emit(VpnDisplayState.CONNECTED)
            it.routes.value = listOf(exit)
        }
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(1, env.engine.registerCalls)
        assertEquals("https://netbird.jet-net.net", env.engine.lastRegisterUrl)
        assertEquals(listOf("sv-fabrika-exit"), env.engine.selectedRoutes)
        assertTrue(env.ob.step.value is Step.Protected)
        assertEquals("Fabrika", env.store.currentBox?.boxName)
    }

    /** Без connect_url — ссылка из полей verify (k, m, e, n). */
    @Test fun fieldsOnlyResponseWorksToo() = runTest {
        val env = Env(this)
        val url = connectUrlFrom(
            JSONObject().put("setup_key", key).put("management_url", "https://netbird.jet-net.net")
                .put("exit_node", "sv-fabrika-exit").put("site_name", "Fabrika"),
        )!!
        signIn(env, url)
        val ready = env.ob.step.value as Step.Ready
        assertEquals("sv-fabrika-exit", ready.payload.exitNodeName)
    }

    /** JetBox safety (слово владельца 25.11): имя и место сервера не важны — ответ verify с любым `m` и без `s` принят. */
    @Test fun anyServerFromVerifyAccepted() = runTest {
        val env = Env(this)
        signIn(env, "https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fmgmt.other.example%3A33073&e=x-exit&n=Office")
        val ready = env.ob.step.value as Step.Ready
        assertEquals("https://mgmt.other.example:33073", ready.payload.managementURL)
    }

    /** Та же неподписанная ссылка извне (интент) — отказ «Эта ссылка не от JetBox» до любого действия. */
    @Test fun sameUnsignedLinkFromIntentRefused() = runTest {
        val env = Env(this)
        assertEquals(true, env.ob.handle("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net"))
        assertEquals(Step.Failed(JetBoxFailure.NotFromJetBox), env.ob.step.value)
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls)
    }
}
