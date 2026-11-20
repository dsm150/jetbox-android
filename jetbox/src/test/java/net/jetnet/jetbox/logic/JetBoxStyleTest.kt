package net.jetnet.jetbox.logic

import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** Стиль оформления: по умолчанию «техно», хранение — тем же ключом и значениями, что на iOS. */
class JetBoxStyleTest {
    @Test fun defaultIsTechno() {
        assertEquals(JetBoxStyle.TECHNO, JetBoxStyle.FALLBACK)
        assertEquals(JetBoxStyle.TECHNO, JetBoxStyle.read(MemoryStore()))
    }

    @Test fun storedStyleIsReadBack() {
        val kv = MemoryStore()
        JetBoxStyle.write(kv, JetBoxStyle.SIMPLE)
        assertEquals(JetBoxStyle.SIMPLE, JetBoxStyle.read(kv))
        JetBoxStyle.write(kv, JetBoxStyle.TECHNO)
        assertEquals(JetBoxStyle.TECHNO, JetBoxStyle.read(kv))
    }

    @Test fun keyAndValuesMatchIos() {
        val kv = MemoryStore()
        JetBoxStyle.write(kv, JetBoxStyle.SIMPLE)
        assertEquals("jetbox.style", JetBoxStyle.KEY)
        assertEquals("simple", kv.getString("jetbox.style"))
        JetBoxStyle.write(kv, JetBoxStyle.TECHNO)
        assertEquals("techno", kv.getString("jetbox.style"))
    }

    @Test fun unknownValueFallsBackToTechno() {
        val kv = MemoryStore()
        kv.putString(JetBoxStyle.KEY, "neon")
        assertEquals(JetBoxStyle.TECHNO, JetBoxStyle.read(kv))
        assertEquals(JetBoxStyle.TECHNO, JetBoxStyle.of(null))
        assertEquals(JetBoxStyle.TECHNO, JetBoxStyle.of(""))
    }
}
