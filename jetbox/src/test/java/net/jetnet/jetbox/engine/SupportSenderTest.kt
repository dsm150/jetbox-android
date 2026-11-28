package net.jetnet.jetbox.engine

import kotlinx.coroutines.runBlocking
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Помощь»: канал отправки по §2.2 спеки 2026-11-28-app-help-and-dns-check. */
class SupportSenderTest {
    private class Api(var result: SupportResult) : AppLoginApi {
        val calls = mutableListOf<List<String>>()
        override suspend fun request(email: String, lang: String) = LoginRequestResult.Accepted
        override suspend fun verifyCode(email: String, code: String): LoginVerifyResult = LoginVerifyResult.Failed("x")
        override suspend fun support(appToken: String, message: String, diagnostics: String, version: String, lang: String): SupportResult {
            calls += listOf(appToken, message, diagnostics, version, lang)
            return result
        }
    }

    private fun store(token: String?) = JetBoxStore(MemoryStore()).apply { appToken = token }

    @Test fun withTokenGoesToCabinetAndRemembersSupportEmail() = runBlocking {
        val api = Api(SupportResult.Sent("Ответим в течение часа", "support@jet-net.net"))
        val s = store("jbd_t")
        val out = SupportSender(api, s, "1.0.7 (22)").send("  нет интернета  ", "diag", "ru")
        assertEquals(HelpOutcome.Sent("Ответим в течение часа"), out)
        assertEquals(listOf("jbd_t", "нет интернета", "diag", "1.0.7 (22)", "ru"), api.calls.single())
        assertEquals("support@jet-net.net", s.supportEmail)
    }

    @Test fun withoutTokenGoesToMailWithBuiltInAddress() = runBlocking {
        val api = Api(SupportResult.Sent(null, null))
        val out = SupportSender(api, store(null), "1.0.7 (22)").send("help", "diag", "ru") as HelpOutcome.NeedsMail
        assertTrue(api.calls.isEmpty())
        assertEquals("help@jet-net.net", out.address)
        assertEquals("JetBox: Android 1.0.7 (22)", out.subject)
        assertTrue(out.body.startsWith("help\n\n"))
        assertTrue(out.body.endsWith("diag"))
    }

    /** 401, 5xx, сеть — письмо; адрес — последний, что прислал Кабинет. */
    @Test fun cabinetFailureFallsBackToRememberedAddress() = runBlocking {
        val s = store("jbd_t").apply { supportEmail = "help-ru@jet-net.net" }
        for (r in listOf(SupportResult.Failed("HTTP 401", null), SupportResult.Failed("HTTP 502", null), SupportResult.Failed("UnknownHostException", null))) {
            val out = SupportSender(Api(r), s, "1.0.7 (22)").send("m", null, "ru")
            assertEquals(HelpOutcome.NeedsMail("help-ru@jet-net.net", "JetBox: Android 1.0.7 (22)", "m"), out)
        }
    }

    @Test fun tooLongAndRateLimitedStayInApp() = runBlocking {
        val s = store("jbd_t")
        assertEquals(HelpOutcome.TooLong, SupportSender(Api(SupportResult.TooLong(null)), s, "v").send("m", "d", "ru"))
        assertEquals(HelpOutcome.RateLimited(60), SupportSender(Api(SupportResult.RateLimited(60, "x@jet-net.net")), s, "v").send("m", "d", "ru"))
        assertEquals("x@jet-net.net", s.supportEmail)
    }
}
