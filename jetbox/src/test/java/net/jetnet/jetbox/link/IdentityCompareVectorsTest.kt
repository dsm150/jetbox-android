package net.jetnet.jetbox.link

import net.jetnet.jetbox.store.JetBoxBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Раздел `compare` общих векторов `identity.json` (LINK-CONTRACT 1.3 §2.11, копия iOS d47f61b): сохранённая
 * запись (m, e, n; null — поля нет) против ссылки → `same` (без вопроса) или `ask` («Другой JetBox»).
 * Отпечаток setup key решает раньше и сюда не входит.
 */
@RunWith(Parameterized::class)
class IdentityCompareVectorsTest(
    private val stored: JSONObject,
    private val link: String,
    private val expect: String,
    private val about: String,
) {
    companion object {
        private val vectors: JSONObject by lazy {
            val text = IdentityCompareVectorsTest::class.java.classLoader!!.getResourceAsStream("identity.json")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(text)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {3}")
        fun cases(): List<Array<Any>> {
            val arr = vectors.getJSONArray("compare")
            return (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                arrayOf(c.getJSONObject("stored"), c.getString("link"), c.getString("expect"), c.getString("about"))
            }
        }

        private fun JSONObject.field(name: String): String? = if (isNull(name)) null else optString(name)
    }

    @Test fun compareVector() {
        assertTrue(about, expect == "same" || expect == "ask")
        val r = JetBoxLink.parse(link)
        assertTrue("$about: ожидали payload, получили $r", r is JetBoxLink.Outcome.Payload)
        val p = (r as JetBoxLink.Outcome.Payload).payload
        val e = stored.field("e")
        val box = JetBoxBox(
            stored.field("m") ?: JetBoxConfig.DEFAULT_MANAGEMENT_URL, e, e, stored.field("n"), 1L,
            setupKeyFingerprint = "0".repeat(32),
        )
        assertEquals(about, expect == "same", box.isSameConnection(p))
    }
}
