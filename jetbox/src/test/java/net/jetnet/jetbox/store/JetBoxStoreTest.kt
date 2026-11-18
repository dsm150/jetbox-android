package net.jetnet.jetbox.store

import net.jetnet.jetbox.link.JetBoxLink
import net.jetnet.jetbox.link.JetBoxLinkPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Хранилище текущего JetBox — порт `JetBoxStore.swift` (iOS) + 3 iOS-теста про `renamed(by:)`
 * из `JetBoxLinkTests.swift`. Фикстура ключа — пример из LINK-CONTRACT.md.
 */
class JetBoxStoreTest {
    private val key = "11111111-2222-4333-8444-555555555555"

    private fun payload(s: String): JetBoxLinkPayload {
        val r = JetBoxLink.parse(s)
        assertTrue("ожидали payload, получили $r", r is JetBoxLink.Outcome.Payload)
        return (r as JetBoxLink.Outcome.Payload).payload
    }

    private fun box(
        url: String = "https://mgmt.jet-net.net",
        exit: String? = "one",
        selected: String? = "one",
        name: String? = "Дом",
        at: Long = 0L,
    ) = JetBoxBox(url, exit, selected, name, at)

    // region хранилище

    @Test fun emptyStoreHasNoBox() {
        assertNull(JetBoxStore(MemoryStore()).currentBox)
    }

    @Test fun saveAndReadBox() {
        val store = JetBoxStore(MemoryStore())
        val b = box(at = 1234567890123L)
        store.currentBox = b
        assertEquals(b, store.currentBox)
    }

    @Test fun nullRemovesBox() {
        val kv = MemoryStore()
        val store = JetBoxStore(kv)
        store.currentBox = box()
        store.currentBox = null
        assertNull(store.currentBox)
        assertNull(kv.getString("jetbox.currentBox"))
    }

    @Test fun boxSurvivesNewStoreOverSameKv() {
        val kv = MemoryStore()
        JetBoxStore(kv).currentBox = box()
        assertEquals(box(), JetBoxStore(kv).currentBox)
    }

    @Test fun brokenJsonGivesNull() {
        val kv = MemoryStore()
        kv.putString("jetbox.currentBox", "{not json")
        assertNull(JetBoxStore(kv).currentBox)
        assertNull(JetBoxBox.fromJson("{not json"))
        assertNull(JetBoxBox.fromJson(""))
        assertNull(JetBoxBox.fromJson("{}"))
    }

    @Test fun memoryStoreBooleans() {
        val kv = MemoryStore()
        assertTrue(kv.getBoolean("x", true))
        kv.putBoolean("x", false)
        assertFalse(kv.getBoolean("x", true))
    }

    // endregion

    // region JSON

    @Test fun jsonRoundTripWithCyrillicAndNulls() {
        val full = box(name = "Дом на Западной", at = 1789000000000L)
        assertEquals(full, JetBoxBox.fromJson(full.toJson()))
        val empty = JetBoxBox("https://mgmt.jet-net.net", null, null, null, 0L)
        assertEquals(empty, JetBoxBox.fromJson(empty.toJson()))
    }

    @Test fun jsonHasNoSetupKey() {
        val p = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=Home")
        val b = JetBoxBox(p.managementURL, p.exitNodeName, p.exitNodeName, p.boxName, 1L)
        val json = b.toJson()
        assertFalse("ключ не должен попасть на диск", json.contains(key))
        assertFalse(json.lowercase().contains("setupkey"))
        assertFalse(JSONObject(json).has("setupKey"))
    }

    // endregion

    // region displayName / effectiveExitNode / identity

    @Test fun displayNameFallsBackFromNameToExitToHost() {
        assertEquals("Дом", box(name = "Дом").displayName)
        assertEquals("one", box(name = null).displayName)
        assertEquals("one", box(name = "").displayName)
        assertEquals("mgmt.jet-net.net", box(name = null, exit = null).displayName)
        assertEquals("mgmt.jet-net.net", box(name = "", exit = "").displayName)
    }

    @Test fun effectiveExitNodePrefersSelected() {
        assertEquals("two", box(exit = "one", selected = "two").effectiveExitNode)
        assertEquals("one", box(exit = "one", selected = null).effectiveExitNode)
        assertNull(box(exit = null, selected = null).effectiveExitNode)
    }

    @Test fun identityIsHostLowercasePlusExit() {
        assertEquals("mgmt.jet-net.net|one", box(url = "https://MGMT.Jet-Net.net").identity)
        // Без выхода и токена (старая ссылка) — хост + имя (LINK-CONTRACT 1.2).
        assertEquals("mgmt.jet-net.net|n:Дом", box(exit = null).identity)
    }

    @Test fun identityIgnoresSelectedExitNode() {
        assertEquals(box(selected = "one").identity, box(selected = "two").identity)
        assertEquals(box(selected = "one").identity, box(selected = null).identity)
    }

    @Test fun identityDiffersByHostAndExit() {
        assertNotEquals(box().identity, box(url = "https://other.jet-net.net").identity)
        assertNotEquals(box().identity, box(exit = "two").identity)
    }

    @Test fun identityMatchesLinkBoxIdentity() {
        val p = payload("jetbox://connect#k=$key&m=https://Mgmt.Jet-Net.net&e=one&n=Home")
        val b = JetBoxBox(p.managementURL, p.exitNodeName, "two", p.boxName, 0L)
        assertEquals(p.boxIdentity, b.identity)
        val p2 = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net")
        assertEquals(p2.boxIdentity, JetBoxBox(p2.managementURL, null, null, null, 0L).identity)
    }

    // endregion

    // region renamed(by:) — iOS: testSameBoxLinkRenamesStoredBox и два соседних

    @Test fun sameBoxLinkRenamesStoredBox() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=%D0%9D%D0%BE%D0%B2%D1%8B%D0%B9")
        val renamed = box(name = "Старый").renamed(link)
        assertEquals("Новый", renamed?.boxName)
        assertEquals("остальное не трогаем", box(name = "Старый").copy(boxName = "Новый"), renamed)
    }

    @Test fun otherBoxLinkDoesNotRename() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=two&n=Other")
        assertNull(box(name = "Старый").renamed(link))
    }

    @Test fun linkWithoutNameOrWithSameNameChangesNothing() {
        assertNull(box(name = "Дом").renamed(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one")))
        assertNull(
            box(name = "Дом").renamed(
                payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=%D0%94%D0%BE%D0%BC")
            )
        )
    }

    @Test fun renameFromNullNameWorksAndKeepsSelectedExit() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=Home")
        val renamed = box(name = null, selected = "two").renamed(link)
        assertNotNull(renamed)
        assertEquals("Home", renamed!!.boxName)
        assertEquals("two", renamed.selectedExitNode)
    }

    // endregion

    // region токен устройства и отпечаток ключа (LINK-CONTRACT 1.2, баг 13.11.2026)

    @Test fun rememberStoresTokenAndFingerprintButNotKey() {
        val store = JetBoxStore(MemoryStore())
        store.remember(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Tariff&t=dev-token-1"), 5L)
        val b = store.currentBox!!
        // Токен — только отпечатком: SHA-256 «dev-token-1», первые 16 байт hex (python hashlib).
        assertEquals("0c4f7aa2e2cc6e49b1ce7d8c9acb511d", b.deviceTokenHash)
        assertEquals("cf4c4732fd3b8f8a55b60871950a2f22", b.setupKeyFingerprint)
        assertFalse(b.toJson().contains(key))
        assertFalse("токен на диске не хранится", b.toJson().contains("dev-token-1"))
        assertFalse(b.toString().contains("dev-token-1"))
    }

    @Test fun jsonRoundTripWithTokenAndFingerprint() {
        val b = box().copy(deviceTokenHash = "cd".repeat(16), setupKeyFingerprint = "ab".repeat(16))
        assertEquals(b, JetBoxBox.fromJson(b.toJson()))
    }

    @Test fun recordOfOlderBuildHasNoTokenOrFingerprint() {
        val old = """{"managementURL":"https://mgmt.jet-net.net","exitNodeName":"one","selectedExitNode":"one","boxName":"Дом","registeredAtMillis":1}"""
        val b = JetBoxBox.fromJson(old)!!
        assertNull(b.deviceTokenHash)
        assertNull(b.setupKeyFingerprint)
        assertTrue(b.isLegacy)
        assertEquals(box(at = 1L), b)
    }

    @Test fun identityRuleOfContract12() {
        val e = box(exit = "one").copy(deviceTokenHash = "t1")
        assertEquals("выход важнее токена", "mgmt.jet-net.net|one", e.identity)
        val t1 = box(exit = null).copy(deviceTokenHash = "t1")
        assertEquals(t1.identity, t1.copy(boxName = "Другой тариф").identity)
        assertNotEquals(t1.identity, t1.copy(deviceTokenHash = "t2").identity)
        assertNotEquals(t1.identity, box(exit = null).identity)
        val legacy = box(exit = null)
        assertEquals(legacy.identity, box(exit = null, selected = null).identity)
        assertNotEquals(legacy.identity, box(exit = null, name = "Дача").identity)
        assertNotEquals(legacy.identity, box(exit = null, url = "https://other.jet-net.net").identity)
    }

    @Test fun storedTokenMatchesLinkIdentity() {
        val p = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Tariff&t=tok&p=android")
        val store = JetBoxStore(MemoryStore())
        store.remember(p, 1L)
        assertEquals(p.boxIdentity, store.currentBox!!.identity)
        assertTrue(store.currentBox!!.isSameConnection(p))
        assertFalse("в удостоверении — отпечаток, не токен", p.boxIdentity.contains("tok"))
    }

    /** Запись ≤ 1.0.2 сравнивается по формуле 1.0.2 `хост|(e ?: "")`, как запасная ветка iOS. */
    @Test fun legacyRecordUsesFormulaOf102() {
        val legacy = JetBoxBox("https://netbird.jet-net.net", null, null, "Тариф", 1L)
        assertTrue(legacy.isLegacy)
        val ownCloud = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Тариф&t=tok")
        assertTrue("своя облачная ссылка с t — тот же JetBox", legacy.isSameConnection(ownCloud))
        val renamed = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Другое")
        assertTrue("1.0.2 имени не сравнивала", legacy.isSameConnection(renamed))
        assertEquals("Другое", legacy.renamed(renamed)?.boxName)
        assertFalse(legacy.isSameConnection(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=exit")))
        assertFalse(legacy.isSameConnection(payload("jetbox://connect#k=$key&m=https://other.jet-net.net")))

        // Новая запись без e и t: имя — часть удостоверения, renamed() не действует.
        val modern = legacy.copy(setupKeyFingerprint = "ab".repeat(16))
        assertFalse(modern.isSameConnection(renamed))
        assertNull(modern.renamed(renamed))
    }

    // endregion
}
