package net.jetnet.jetbox.link

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JetBox safety — общие тест-векторы подписи ссылки (VPN_Box `docs/superpowers/specs/2026-11-23-link-signature-vectors.json`,
 * копия в `src/test/resources`; RU-приложение — слово владельца 25.11.2026, как iOS). Тестовый ключ (закрытая часть —
 * байты 0x00..0x1f) живёт только здесь: в приложении — две боевые открытые части ([JetBoxLinkSignature.PUBLIC_KEYS]),
 * тесты передают свой ключ параметром.
 */
class LinkSignatureVectorsTest {
    private val vectors = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("link-signature-vectors.json")!!.bufferedReader().readText(),
    )
    private val testKey = vectors.getString("test_public_key_b64")
    private val cases = vectors.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun fragment(link: String) = link.substringAfter('#')

    @Test fun vectorsGiveExpectedValidity() {
        assertEquals(9, cases.size)
        for (c in cases) {
            val name = c.getString("name")
            assertEquals(name, c.getBoolean("valid"), JetBoxLinkSignature.isValid(fragment(c.getString("link")), listOf(testKey)))
        }
    }

    @Test fun messageMatchesVectorsByteForByte() {
        var checked = 0
        for (c in cases.filter { it.has("message_b64") }) {
            val signed = JetBoxLinkSignature.signedMessage(fragment(c.getString("link")))
            assertNotNull(c.getString("name"), signed)
            assertArrayEquals(c.getString("name"), Base64.getDecoder().decode(c.getString("message_b64")), signed!!.message)
            checked++
        }
        assertEquals(2, checked)
    }

    /** Подписанная ссылка — разбор с любым хостом и сервером, неподписанная — [JetBoxLink.Outcome.Unsigned]. */
    @Test fun parseFollowsVectors() {
        for (c in cases) {
            val outcome = JetBoxLink.parse(c.getString("link"), signatureKeys = listOf(testKey))
            if (c.getBoolean("valid")) {
                assertTrue(c.getString("name"), outcome is JetBoxLink.Outcome.Payload)
            } else {
                assertEquals(c.getString("name"), JetBoxLink.Outcome.Unsigned, outcome)
            }
        }
        val full = JetBoxLink.parse(cases[0].getString("link"), signatureKeys = listOf(testKey)) as JetBoxLink.Outcome.Payload
        assertEquals("https://net.jetbox.example", full.payload.managementURL)
        assertEquals("Café Home+1", full.payload.boxName)
    }

    /** Боевые ключи тестовую подпись не принимают; тестового ключа среди них нет. */
    @Test fun productionKeysRejectTestSignatures() {
        assertEquals(
            listOf("prLMY2riAVq0e1nuydlSO3FOFedlsNvxjKXsHUl8/Tg=", "Lr8e8586oEZnBZqS678ipTemL050ha5OUIG6mqGyLu0="),
            JetBoxLinkSignature.PUBLIC_KEYS,
        )
        assertFalse(testKey in JetBoxLinkSignature.PUBLIC_KEYS)
        for (c in cases) assertFalse(c.getString("name"), JetBoxLinkSignature.isValid(fragment(c.getString("link"))))
        assertEquals(JetBoxLink.Outcome.Unsigned, JetBoxLink.parse(cases[0].getString("link")))
    }

    /**
     * Старая ссылка Кабинета без `s` — отказ; та же из ответа verify (trusted) — принята; прежние правила (`signed = false`,
     * только тесты) подпись не смотрят.
     */
    @Test fun oldUnsignedLinkRefusedTrustedAccepted() {
        val old = "https://box.jet-net.net/connect#k=4E2BD0D4-0000-4000-8000-000000000001&m=https%3A%2F%2Fnetbird.jet-net.net&e=home-exit"
        assertEquals(JetBoxLink.Outcome.Unsigned, JetBoxLink.parse(old))
        assertEquals(JetBoxLink.Outcome.Unsigned, JetBoxLink.parse(old.replace("https://box.jet-net.net/connect", "jetbox://connect")))
        assertTrue(JetBoxLink.parse(old, trusted = true) is JetBoxLink.Outcome.Payload)
        assertTrue(JetBoxLink.parse(old, signed = false) is JetBoxLink.Outcome.Payload)
        // Без ключа — не ссылка подключения вовсе: не пугаем отказом.
        assertEquals(JetBoxLink.Outcome.NotOurs, JetBoxLink.parse("https://box.jet-net.net/connect#m=x"))
    }

    /** Повтор имени в другом регистре, пара без «=», кривая или короткая подпись — недействительна. */
    @Test fun malformedSignedFragmentsAreRejected() {
        val good = fragment(cases[0].getString("link"))
        assertTrue(JetBoxLinkSignature.isValid(good, listOf(testKey)))
        val s = good.substringAfter("&s=")
        for (bad in listOf(
            "$good&K=4E2BD0D4-0000-4000-8000-000000000001",
            "$good&S=$s",
            "$good&flag",
            "$good&=x",
            good.replace("&s=$s", "&s=$s="),
            good.replace("&s=$s", "&s=${s.dropLast(4)}"),
            good.replace("&s=$s", "&s=${s.replace('_', '/')}"),
        )) {
            assertFalse(bad, JetBoxLinkSignature.isValid(bad, listOf(testKey)))
        }
        // Пустые пары (`&&`) пропускаются, как в iOS.
        assertTrue(JetBoxLinkSignature.isValid(good.replace("&e=", "&&e="), listOf(testKey)))
        assertNull(JetBoxLinkSignature.base64UrlDecode(""))
    }
}
