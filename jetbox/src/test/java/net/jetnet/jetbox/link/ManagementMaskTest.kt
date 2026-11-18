package net.jetnet.jetbox.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Маска адреса management (решение владельца 18.11.2026, LINK-CONTRACT §2.3).
 * Векторы `src/test/resources/mgmt-mask.json` — байт в байт копия
 * `JetBox_Desktop/core/jetboxlink/testdata/mgmt-mask.json` (коммит JetBox_Desktop 0d3bfe4), общие для
 * службы, iOS и Android: файл не правим, а синхронизируем с эталоном.
 */
class ManagementMaskTest {
    private val key = "11111111-2222-4333-8444-555555555555"

    private class Case(val m: String, val ok: Boolean, val why: String)

    private val vectors: JSONObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("mgmt-mask.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        JSONObject(text)
    }

    private val cases: List<Case> by lazy {
        val arr = vectors.getJSONArray("cases")
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Case(it.getString("m"), it.getBoolean("ok"), it.getString("why")) }
        }
    }

    /** m в ссылку — целиком percent-encoded, чтобы разбор ссылки вернул ровно его (вместе с `#`, `%`, `&`). */
    private fun linkWith(m: String) =
        "jetbox://connect#k=$key&m=" + URLEncoder.encode(m, "UTF-8").replace("+", "%20")

    @Test fun vectorsFileIsComplete() {
        assertEquals(JetBoxConfig.MANAGEMENT_SUFFIX, vectors.getString("suffix"))
        assertEquals(36, cases.size)
    }

    @Test fun allVectorsThroughMask() {
        val failed = cases.filter { JetBoxLink.isAllowedManagement(it.m) != it.ok }
            .map { "«${it.m}» (${it.why}): ждали ${it.ok}" }
        assertTrue("не совпали с эталоном:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    /**
     * Те же векторы через разбор ссылки с валидным k: ok — Payload (и адрес снова внутри маски),
     * иначе — ForeignServer. Два случая расходятся с маской по контракту ссылки, а не по маске:
     * пустой m — дефолт приложения (LINK-CONTRACT §2.2), пробелы вокруг значения срезает разбор
     * параметров (как эталонный `Parse`).
     */
    @Test fun allVectorsThroughParse() {
        val byContract = mapOf(
            "" to JetBoxConfig.DEFAULT_MANAGEMENT_URL,
            " https://netbird.jet-net.net" to "https://netbird.jet-net.net",
        )
        val failed = mutableListOf<String>()
        for (c in cases) {
            val r = JetBoxLink.parse(linkWith(c.m))
            val expectedUrl = byContract[c.m]
            val good = when {
                expectedUrl != null -> r == JetBoxLink.Outcome.Payload(
                    JetBoxLinkPayload(key, expectedUrl, null, null),
                )
                c.ok -> r is JetBoxLink.Outcome.Payload &&
                    JetBoxLink.isAllowedManagement(r.payload.managementURL) &&
                    !r.payload.managementURL.endsWith("/")
                else -> r == JetBoxLink.Outcome.ForeignServer
            }
            if (!good) failed += "«${c.m}» (${c.why}): ok=${c.ok}, получили $r"
        }
        assertTrue("не совпали:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun defaultManagementIsInsideMask() {
        assertTrue(JetBoxLink.isAllowedManagement(JetBoxConfig.DEFAULT_MANAGEMENT_URL))
    }

    @Test fun normalisationKeepsFormat() {
        fun url(m: String) = (JetBoxLink.parse(linkWith(m)) as JetBoxLink.Outcome.Payload).payload.managementURL
        assertEquals("https://netbird.jet-net.net", url("https://NETBIRD.Jet-Net.NET/"))
        assertEquals("https://netbird.jet-net.net:443", url("https://netbird.jet-net.net:443"))
        assertEquals("https://netbird.jet-net.net:443", url("https://netbird.jet-net.net:0443"))
        assertEquals("https://netbird.jet-net.net", url("HTTPS://netbird.jet-net.net"))
    }

    /** Места, где `java.net.URI` нестрог или где легко ошибиться, — сверх общих векторов. */
    @Test fun lenientUriCasesAreRejected() {
        for (m in listOf(
            "https://net_bird.jet-net.net", // URI: host == null, «_» уходит в authority
            "https://127.1", "https://0x7f.1", "https://0177.0.0.1", "https://1.2.3.4.", // IPv4 в разных записях
            "https://[::ffff:1.2.3.4]", "https://[fe80::1%25en0]",
            "https://netbird.jet-net.net:", // пустой порт
            "https://netbird.jet-net.net:+443", "https://netbird.jet-net.net:443:443", "https://netbird.jet-net.net:65536",
            "https://netbird.jet-net.net?", "https://netbird.jet-net.net/?", "https://netbird.jet-net.net//",
            "https://netbird.jet-net.net/%2F", "https:netbird.jet-net.net", "https:/netbird.jet-net.net",
            "https://netbird.jet-net.net..", "https://netbird.jet-net.net\t", "https://netbird.jet-net.net ",
            "https://netbirK.jet-net.net", // знак Кельвина: lowercase дал бы ASCII «k»
            "https://jet-net.net.", "https://-.jet-net.net.evil",
        )) assertFalse(m, JetBoxLink.isAllowedManagement(m))
        assertTrue(JetBoxLink.isAllowedManagement("https://netbird.jet-net.net:65535"))
        assertTrue(JetBoxLink.isAllowedManagement("https://netbird.jet-net.net:1"))
    }

    @Test fun universalLinkWithForeignServer() {
        assertEquals(
            JetBoxLink.Outcome.ForeignServer,
            JetBoxLink.parse("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fevil.com&e=x"),
        )
    }
}
