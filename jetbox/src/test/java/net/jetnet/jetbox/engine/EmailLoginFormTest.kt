package net.jetnet.jetbox.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.EmailLoginForm.CodeError
import net.jetnet.jetbox.engine.EmailLoginForm.Error
import net.jetnet.jetbox.engine.EmailLoginForm.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Мок API Кабинета: ответы по очереди, запросы записываются. */
class FakeLoginApi : AppLoginApi {
    val requests = mutableListOf<Pair<String, String>>()
    val codes = mutableListOf<Pair<String, String>>()
    var requestResult: LoginRequestResult = LoginRequestResult.Accepted
    var codeResults = ArrayDeque<LoginVerifyResult>()

    override suspend fun request(email: String, lang: String): LoginRequestResult {
        requests += email to lang
        return requestResult
    }

    override suspend fun verifyCode(email: String, code: String): LoginVerifyResult {
        codes += email to code
        return codeResults.removeFirstOrNull() ?: LoginVerifyResult.Failed("no mock")
    }

    val revoked = mutableListOf<String>()
    var revokeResult: DeviceRevokeResult = DeviceRevokeResult.Revoked
    /** Ответ отзыва задерживается навсегда — проверка таймаута выхода. */
    var revokeHangs = false

    override suspend fun revokeDevice(appToken: String): DeviceRevokeResult {
        revoked += appToken
        if (revokeHangs) kotlinx.coroutines.awaitCancellation()
        return revokeResult
    }
}

/** Вход по рабочей почте с кодом: адрес → `login/request` → код → `login/verify` → ссылка в сценарий. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailLoginFormTest {
    private var now = 1_000_000L
    private val email = "ivan@fabrika-mfc.ru"
    private val connected = mutableListOf<String>()
    private val tokens = mutableListOf<String?>()
    private var accepts = true

    private fun TestScope.form(api: FakeLoginApi) = EmailLoginForm(
        api = api,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        clock = { now },
        onConnectUrl = { url, token -> connected += url; tokens += token; accepts },
    )

    private fun TestScope.atCode(api: FakeLoginApi): EmailLoginForm {
        val f = form(api)
        f.submit(email)
        advanceUntilIdle()
        return f
    }

    /** После запроса — сразу экран кода (ссылки из письма в RU-приложении нет); язык письма — ru. */
    @Test fun acceptedGoesStraightToCode() = runTest {
        val api = FakeLoginApi()
        val f = form(api)
        f.submit("  $email ")
        assertEquals(State.Sending(email), f.state.value)
        advanceUntilIdle()
        assertEquals(State.Code(email, now + 60_000), f.state.value)
        assertEquals(listOf(email to "ru"), api.requests)
    }

    @Test fun rightCodeHandsConnectUrlToScenario() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        api.codeResults += LoginVerifyResult.Success("https://box.jet-net.net/connect#k=x", appToken = "jbd_token")
        f.submitCode("012345")
        assertTrue((f.state.value as State.Code).verifying)
        advanceUntilIdle()
        assertEquals(listOf(email to "012345"), api.codes)
        assertEquals(listOf("https://box.jet-net.net/connect#k=x"), connected)
        assertEquals("токен приложения уходит в сценарий вместе со ссылкой", listOf<String?>("jbd_token"), tokens)
        assertEquals(State.Editing(), f.state.value)
    }

    @Test fun connectUrlRejectedStaysOnCode() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        accepts = false
        api.codeResults += LoginVerifyResult.Success("https://example.com/")
        f.submitCode("123456")
        advanceUntilIdle()
        assertEquals(CodeError.UNAVAILABLE, (f.state.value as State.Code).error)
    }

    @Test fun codeMustBeSixDigits() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        f.submitCode("12345")
        f.submitCode("1234567")
        advanceUntilIdle()
        assertTrue(api.codes.isEmpty())
        api.codeResults += LoginVerifyResult.BadCode(4)
        f.submitCode("12 34-56")
        advanceUntilIdle()
        assertEquals(listOf(email to "123456"), api.codes)
    }

    @Test fun wrongCodeShowsAttemptsLeftThenNeedsNewCode() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        api.codeResults += LoginVerifyResult.BadCode(2)
        f.submitCode("111111")
        advanceUntilIdle()
        val s = f.state.value as State.Code
        assertEquals(CodeError.BAD_CODE, s.error)
        assertEquals(2, s.attemptsLeft)
        assertFalse(s.needsNewCode)
        api.codeResults += LoginVerifyResult.BadCode(0)
        f.submitCode("222222")
        advanceUntilIdle()
        assertTrue((f.state.value as State.Code).needsNewCode)
        f.submitCode("333333")
        advanceUntilIdle()
        assertEquals(2, api.codes.size)
    }

    /** Новый код — только после отсчёта; лимит Кабинета перезапускает отсчёт. */
    @Test fun newCodeAfterCooldown() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        f.requestNewCode()
        advanceUntilIdle()
        assertEquals(1, api.requests.size)
        now += 60_000
        f.requestNewCode()
        advanceUntilIdle()
        assertEquals(2, api.requests.size)
        assertEquals(State.Code(email, now + 60_000), f.state.value)
        now += 60_000
        api.requestResult = LoginRequestResult.TooManyRequests
        f.requestNewCode()
        advanceUntilIdle()
        val s = f.state.value as State.Code
        assertEquals(Error.TOO_MANY, s.sendError)
        assertEquals(now + 60_000, s.resendAt)
    }

    @Test fun expiredCodeAndOtherErrors() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        for ((r, e) in listOf(
            LoginVerifyResult.DeviceLimit to CodeError.DEVICE_LIMIT,
            LoginVerifyResult.TooManyRequests to CodeError.TOO_MANY,
            LoginVerifyResult.Unreachable("x") to CodeError.UNAVAILABLE,
            LoginVerifyResult.NotAvailable to CodeError.NOT_AVAILABLE,
            LoginVerifyResult.TryAgain to CodeError.TRY_AGAIN,
            LoginVerifyResult.Failed("HTTP 500") to CodeError.UNAVAILABLE,
        )) {
            api.codeResults += r
            f.submitCode("123456")
            advanceUntilIdle()
            val s = f.state.value as State.Code
            assertEquals(e, s.error)
            assertFalse("тот же код можно ввести снова", s.needsNewCode)
        }
        for ((r, e) in listOf(
            LoginVerifyResult.Used to CodeError.USED,
            LoginVerifyResult.Expired to CodeError.EXPIRED,
        )) {
            f.changeEmail()
            f.submit(email)
            advanceUntilIdle()
            api.codeResults += r
            f.submitCode("123456")
            advanceUntilIdle()
            assertEquals(e, (f.state.value as State.Code).error)
        }
        assertTrue((f.state.value as State.Code).needsNewCode)
    }

    @Test fun invalidEmailStaysLocal() = runTest {
        val api = FakeLoginApi()
        val f = form(api)
        f.submit("not-an-email")
        advanceUntilIdle()
        assertEquals(State.Editing(Error.INVALID_EMAIL), f.state.value)
        assertTrue(api.requests.isEmpty())
        f.editEmail()
        assertEquals(State.Editing(), f.state.value)
    }

    @Test fun requestErrorsKeepUserOnForm() = runTest {
        val api = FakeLoginApi()
        val f = form(api)
        api.requestResult = LoginRequestResult.TooManyRequests
        f.submit(email)
        advanceUntilIdle()
        assertEquals(State.Editing(Error.TOO_MANY), f.state.value)
        api.requestResult = LoginRequestResult.Unavailable("HTTP 500")
        f.submit(email)
        advanceUntilIdle()
        assertEquals(State.Editing(Error.UNAVAILABLE), f.state.value)
        // 422 bad_request — Кабинет не принял адрес: как локальная проверка.
        api.requestResult = LoginRequestResult.BadEmail
        f.submit(email)
        advanceUntilIdle()
        assertEquals(State.Editing(Error.INVALID_EMAIL), f.state.value)
        assertEquals(email, f.lastEmail)
    }

    /** «Указать другой адрес» — назад к вводу; проверка кода в пути отменяется и в сценарий ничего не уходит. */
    @Test fun changeEmailCancelsVerify() = runTest {
        val api = FakeLoginApi()
        val f = atCode(api)
        api.codeResults += LoginVerifyResult.Success("https://box.jet-net.net/connect#k=x")
        f.submitCode("123456")
        f.changeEmail()
        advanceUntilIdle()
        assertEquals(State.Editing(), f.state.value)
        assertTrue(connected.isEmpty())
    }

    @Test fun emailCheck() {
        assertTrue(EmailLoginForm.isValidEmail("ivan@fabrika-mfc.ru"))
        assertTrue(EmailLoginForm.isValidEmail("a.b+c@mrisg.ru"))
        assertFalse(EmailLoginForm.isValidEmail("ivan@fabrika"))
        assertFalse(EmailLoginForm.isValidEmail("ivan fabrika@mrisg.ru"))
        assertFalse(EmailLoginForm.isValidEmail(""))
    }
}
