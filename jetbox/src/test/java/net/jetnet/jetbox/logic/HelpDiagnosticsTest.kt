package net.jetnet.jetbox.logic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Помощь» и проверка DNS (спека VPN_Box 2026-11-28-app-help-and-dns-check): фильтр, канарейка, текст диагностики. */
class HelpDiagnosticsTest {

    // region фильтр (§2.3: без ключей, setup-key, токенов, подписей, адресов серверов, публичных IP)

    @Test fun scrubberHidesSecrets() {
        val key = "4E2BD0D4-0000-4000-8000-000000000001"
        val sig = "XY_KvXXY0YNGAxTwvJ_htjoL8Cm21W32NSuBWtG_CydTZeRFNku3RjxtLw7Todmn0WcstEA1Wjru0b24cCP3AA"
        val wg = "Kjerhl31r3QZt/B6YyV+4RUadcqQ10fXjzDuAjo4REw="
        val line = "link https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&s=$sig peer $wg " +
            "token jbd_AbCdEf0123456789xyz setup $key mail ivan.petrov@fabrika-mfc.ru"
        val out = DiagnosticsScrubber.scrub(line)
        for (secret in listOf(key, sig, wg, "jbd_AbCdEf0123456789xyz", "netbird.jet-net.net", "box.jet-net.net", "ivan.petrov")) {
            assertFalse("$secret в «$out»", out.contains(secret))
        }
        assertTrue(out, out.contains("iv***@fabrika-mfc.ru"))
    }

    @Test fun scrubberKeepsMeshAndPrivateButMasksPublicIps() {
        val out = DiagnosticsScrubber.scrub(
            "peer 100.64.203.110 lan 192.168.1.5 ula fd00:db8:3303:e7d9::1b exit 203.0.113.9 mts 198.51.100.94 " +
                "v6 2a00:1fa0:3e00:ff::1 mark 192.0.2.53",
        )
        assertTrue(out, out.contains("100.64.203.110"))
        assertTrue(out, out.contains("192.168.1.5"))
        assertTrue(out, out.contains("fd00:db8:3303:e7d9::1b"))
        assertTrue(out, out.contains("192.0.2.53"))
        assertFalse(out, out.contains("203.0.113.9"))
        assertFalse(out, out.contains("198.51.100.94"))
        assertFalse(out, out.contains("2a00:1fa0"))
    }

    @Test fun scrubberLeavesOrdinaryLogAlone() {
        val line = "01-02 10:11:12 I/JetBoxOnboarding: JetBox: device registered on the site"
        assertEquals(line, DiagnosticsScrubber.scrub(line))
        assertEquals("iv***@x.ru", DiagnosticsScrubber.maskEmail("ivan@x.ru"))
        assertEquals("a***@x.ru", DiagnosticsScrubber.maskEmail("a@x.ru"))
    }

    // endregion
    // region канарейка (§3)

    private fun canary(box: List<String>?, exact: List<String>? = box, sys: SystemAnswer, asked: MutableList<String> = mutableListOf()) =
        DnsCanary(
            name = "dnscheck.jet-net.net",
            boxQuery = { _, n -> asked += n; if (n.startsWith("c1.")) box else exact },
            systemResolve = { n -> asked += "sys:$n"; sys },
            nonce = { "c1" },
        )

    @Test fun canaryVerdicts() = runBlocking {
        val mark = listOf(DnsCanary.MARK)
        assertEquals(DnsCheck.THROUGH, canary(mark, sys = SystemAnswer.Addresses(mark)).check("100.64.0.7"))
        assertEquals(DnsCheck.BYPASS, canary(mark, sys = SystemAnswer.NotFound).check("100.64.0.7"))
        assertEquals(DnsCheck.BYPASS, canary(mark, sys = SystemAnswer.Addresses(listOf("1.2.3.4"))).check("100.64.0.7"))
        assertEquals(DnsCheck.NO_ANSWER, canary(mark, sys = SystemAnswer.NoAnswer).check("100.64.0.7"))
        // Коробка не знает канарейку — молчим, системный резолвер не спрашиваем.
        val asked = mutableListOf<String>()
        assertEquals(DnsCheck.BOX_UNAWARE, canary(null, sys = SystemAnswer.NotFound, asked = asked).check("100.64.0.7"))
        assertTrue(asked.none { it.startsWith("sys:") })
        assertEquals(DnsCheck.BOX_UNAWARE, canary(listOf("10.0.0.1"), sys = SystemAnswer.NotFound).check("100.64.0.7"))
    }

    /** Пусто на поддомене → точное имя; системный резолвер спрашивает то же имя, что ответило у коробки. */
    @Test fun canaryFallsBackToExactName() = runBlocking {
        val asked = mutableListOf<String>()
        val c = canary(emptyList(), exact = listOf(DnsCanary.MARK), sys = SystemAnswer.Addresses(listOf(DnsCanary.MARK)), asked = asked)
        assertEquals(DnsCheck.THROUGH, c.check("100.64.0.7"))
        assertEquals(listOf("c1.dnscheck.jet-net.net", "dnscheck.jet-net.net", "sys:dnscheck.jet-net.net"), asked)
        val unaware = canary(emptyList(), exact = emptyList(), sys = SystemAnswer.NotFound)
        assertEquals(DnsCheck.BOX_UNAWARE, unaware.check("100.64.0.7"))
    }

    @Test fun parseAnswerWithCompression() {
        val q = DnsProbe.buildQuery(0x1234, "c1.dnscheck.jet-net.net")
        // Ответ: заголовок (QR, ANCOUNT=1), вопрос как в запросе, ответ с указателем на имя (0xC00C), A 192.0.2.53.
        val header = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val answer = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 53)
        val resp = header + q.copyOfRange(12, q.size) + answer
        assertEquals(listOf("192.0.2.53"), DnsCanary.parseA(resp, resp.size, 0x1234))
        assertNull("чужой id", DnsCanary.parseA(resp, resp.size, 0x1235))
        val nx = header.copyOf().also { it[3] = 0x83.toByte(); it[7] = 0 } + q.copyOfRange(12, q.size)
        assertEquals(emptyList<String>(), DnsCanary.parseA(nx, nx.size, 0x1234))
    }

    // endregion
    // region текст диагностики

    @Test fun diagnosticsTextIsScrubbedAndBounded() {
        val log = (1..80).map { "line $it from 203.0.113.9 key 4E2BD0D4-0000-4000-8000-000000000001" }
        val text = Diagnostics.build(
            DiagnosticsInput(
                appVersion = "1.0.7", appBuild = 22, store = "play", osVersion = "9", device = "Xiaomi Mi Note 3",
                language = "ru", signIn = "email", emailMasked = "te***@fabrika-mfc.ru", hasAppToken = true,
                tunnel = "connected", homeAnswers = true, latencyMs = 21, route = "direct", uptime = "0:39",
                exit = "sv-fabrika-exit", exitOff = false, dnsCheck = DnsCheck.BYPASS,
                privateDns = "1dot1dot1dot1.cloudflare-dns.com", log = log,
            ),
        )
        assertTrue(text, text.startsWith("JetBox Android 1.0.7 (22), play"))
        assertTrue(text.contains("DNS check: bypasses JetBox, private DNS: 1dot1dot1dot1.cloudflare-dns.com"))
        assertTrue(text.contains("Log (last 50):"))
        assertFalse(text.contains("line 30 "))
        assertTrue(text.contains("line 80 "))
        assertFalse(text.contains("4E2BD0D4"))
        assertFalse(text.contains("203.0.113.9"))
    }

    @Test fun logBufferKeepsLastLines() {
        JetBoxLog.clear()
        repeat(JetBoxLog.CAPACITY + 5) { JetBoxLog.i("T", "m$it") }
        val all = JetBoxLog.last(1000)
        assertEquals(JetBoxLog.CAPACITY, all.size)
        assertTrue(all.last().endsWith("I/T: m${JetBoxLog.CAPACITY + 4}"))
        assertEquals(3, JetBoxLog.last(3).size)
    }

    // endregion
}
