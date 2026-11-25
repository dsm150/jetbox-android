package net.jetnet.jetbox.link

import net.jetnet.jetbox.store.JetBoxBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Удостоверение подключения (LINK-CONTRACT 1.3 §2.11) по общим векторам `src/test/resources/identity.json` —
 * байт в байт копия `JetBox_iOS/jetbox-ios/NetBirdTests/identity.json` (коммит JetBox_iOS d47f61b):
 * файл не правим, а синхронизируем с iOS. Ссылка целиком — через [JetBoxLink.parse] → `payload.boxIdentity`;
 * запись хранилища из той же ссылки даёт ту же строку. Сравнение «без имени» — в JetBoxStoreTest.
 */
@RunWith(Parameterized::class)
class IdentityVectorsTest(
    private val link: String,
    private val identity: String,
    private val about: String,
) {
    companion object {
        private val vectors: JSONObject by lazy {
            val text = IdentityVectorsTest::class.java.classLoader!!.getResourceAsStream("identity.json")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(text)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {2}")
        fun cases(): List<Array<Any>> {
            val arr = vectors.getJSONArray("cases")
            return (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                arrayOf(c.getString("link"), c.getString("identity"), c.getString("about"))
            }
        }
    }

    private fun payload(): JetBoxLinkPayload {
        val r = JetBoxLink.parse(link, signed = false)
        assertTrue("$about: ожидали payload, получили $r", r is JetBoxLink.Outcome.Payload)
        return (r as JetBoxLink.Outcome.Payload).payload
    }

    @Test fun vectorsFileIsComplete() {
        assertEquals(15, vectors.getJSONArray("cases").length())
    }

    @Test fun linkIdentity() {
        assertEquals(about, identity, payload().boxIdentity)
    }

    @Test fun storedRecordHasSameIdentity() {
        val p = payload()
        val box = JetBoxBox(p.managementURL, p.exitNodeName, p.exitNodeName, p.boxName, 1L, deviceTokenHash = p.deviceTokenHash)
        assertEquals(about, identity, box.identity)
        assertTrue(about, box.isSameConnection(p))
    }
}
