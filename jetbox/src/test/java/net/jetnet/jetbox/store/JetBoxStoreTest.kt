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
        url: String = "https://mgmt.example.net",
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
        val empty = JetBoxBox("https://mgmt.example.net", null, null, null, 0L)
        assertEquals(empty, JetBoxBox.fromJson(empty.toJson()))
    }

    @Test fun jsonHasNoSetupKey() {
        val p = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=one&n=Home")
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
        assertEquals("mgmt.example.net", box(name = null, exit = null).displayName)
        assertEquals("mgmt.example.net", box(name = "", exit = "").displayName)
    }

    @Test fun effectiveExitNodePrefersSelected() {
        assertEquals("two", box(exit = "one", selected = "two").effectiveExitNode)
        assertEquals("one", box(exit = "one", selected = null).effectiveExitNode)
        assertNull(box(exit = null, selected = null).effectiveExitNode)
    }

    @Test fun identityIsHostLowercasePlusExit() {
        assertEquals("mgmt.example.net|one", box(url = "https://MGMT.Example.net").identity)
        assertEquals("mgmt.example.net|", box(exit = null).identity)
    }

    @Test fun identityIgnoresSelectedExitNode() {
        assertEquals(box(selected = "one").identity, box(selected = "two").identity)
        assertEquals(box(selected = "one").identity, box(selected = null).identity)
    }

    @Test fun identityDiffersByHostAndExit() {
        assertNotEquals(box().identity, box(url = "https://other.example.net").identity)
        assertNotEquals(box().identity, box(exit = "two").identity)
    }

    @Test fun identityMatchesLinkBoxIdentity() {
        val p = payload("jetbox://connect#k=$key&m=https://Mgmt.Example.net&e=one&n=Home")
        val b = JetBoxBox(p.managementURL, p.exitNodeName, "two", p.boxName, 0L)
        assertEquals(p.boxIdentity, b.identity)
        val p2 = payload("jetbox://connect#k=$key&m=https://mgmt.example.net")
        assertEquals(p2.boxIdentity, JetBoxBox(p2.managementURL, null, null, null, 0L).identity)
    }

    // endregion

    // region renamed(by:) — iOS: testSameBoxLinkRenamesStoredBox и два соседних

    @Test fun sameBoxLinkRenamesStoredBox() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=one&n=%D0%9D%D0%BE%D0%B2%D1%8B%D0%B9")
        val renamed = box(name = "Старый").renamed(link)
        assertEquals("Новый", renamed?.boxName)
        assertEquals("остальное не трогаем", box(name = "Старый").copy(boxName = "Новый"), renamed)
    }

    @Test fun otherBoxLinkDoesNotRename() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=two&n=Other")
        assertNull(box(name = "Старый").renamed(link))
    }

    @Test fun linkWithoutNameOrWithSameNameChangesNothing() {
        assertNull(box(name = "Дом").renamed(payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=one")))
        assertNull(
            box(name = "Дом").renamed(
                payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=one&n=%D0%94%D0%BE%D0%BC")
            )
        )
    }

    @Test fun renameFromNullNameWorksAndKeepsSelectedExit() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=one&n=Home")
        val renamed = box(name = null, selected = "two").renamed(link)
        assertNotNull(renamed)
        assertEquals("Home", renamed!!.boxName)
        assertEquals("two", renamed.selectedExitNode)
    }

    // endregion
}
