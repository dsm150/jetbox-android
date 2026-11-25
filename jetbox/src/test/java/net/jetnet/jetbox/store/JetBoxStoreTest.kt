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
        val r = JetBoxLink.parse(s, signed = false)
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
        assertEquals("mgmt.jet-net.net|one|Дом", box(url = "https://MGMT.Jet-Net.net").identity)
        // LINK-CONTRACT 1.3 §2.11: хост|e|n; без e — хост|n:<n>; без обоих — хост|.
        assertEquals("mgmt.jet-net.net|n:Дом", box(exit = null).identity)
        assertEquals("mgmt.jet-net.net|one|", box(name = null).identity)
        assertEquals("mgmt.jet-net.net|", box(exit = null, name = null).identity)
    }

    @Test fun identityIgnoresSelectedExitNode() {
        assertEquals(box(selected = "one").identity, box(selected = "two").identity)
        assertEquals(box(selected = "one").identity, box(selected = null).identity)
    }

    @Test fun identityDiffersByHostAndExit() {
        assertNotEquals(box().identity, box(url = "https://other.jet-net.net").identity)
        assertNotEquals(box().identity, box(exit = "two").identity)
        assertNotEquals("имя — часть удостоверения и при e", box().identity, box(name = "Дача").identity)
    }

    @Test fun identityMatchesLinkBoxIdentity() {
        val p = payload("jetbox://connect#k=$key&m=https://Mgmt.Jet-Net.net&e=one&n=Home")
        val b = JetBoxBox(p.managementURL, p.exitNodeName, "two", p.boxName, 0L)
        assertEquals(p.boxIdentity, b.identity)
        val p2 = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net")
        assertEquals(p2.boxIdentity, JetBoxBox(p2.managementURL, null, null, null, 0L).identity)
    }

    // endregion

    // region renamed(by:) — iOS: testSameBoxLinkRenamesStoredBox и два соседних.
    // С ред. 1.3 (§2.11) имя входит в удостоверение: другое имя — уже «Другой JetBox»; renamed() лишь
    // запоминает имя, которого у записи не было (как iOS named(by:)).

    @Test fun newNameIsOtherConnectionNotRename() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=%D0%9D%D0%BE%D0%B2%D1%8B%D0%B9")
        assertFalse("переименование дома — «Другой JetBox»", box(name = "Старый").isSameConnection(link))
        assertNull(box(name = "Старый").renamed(link))
    }

    @Test fun otherBoxLinkDoesNotRename() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=two&n=Other")
        assertNull(box(name = "Старый").renamed(link))
    }

    @Test fun linkWithoutNameOrWithSameNameChangesNothing() {
        val noName = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one")
        assertTrue("ссылка без n — сравнение по хост|e", box(name = "Дом").isSameConnection(noName))
        assertNull(box(name = "Дом").renamed(noName))
        val sameName = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=%D0%94%D0%BE%D0%BC")
        assertTrue(box(name = "Дом").isSameConnection(sameName))
        assertNull(box(name = "Дом").renamed(sameName))
    }

    @Test fun renameFromNullNameWorksAndKeepsSelectedExit() {
        val link = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one&n=Home")
        assertTrue("запись без имени — сравнение по хост|e", box(name = null, selected = "two").isSameConnection(link))
        val renamed = box(name = null, selected = "two").renamed(link)
        assertNotNull(renamed)
        assertEquals("Home", renamed!!.boxName)
        assertEquals("two", renamed.selectedExitNode)
        val noName = payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one")
        assertTrue("без имени у обоих — то же подключение", box(name = null, selected = "two").isSameConnection(noName))
        assertNull(box(name = null).renamed(noName))
    }

    // endregion

    // region токен устройства и отпечаток ключа (LINK-CONTRACT 1.2/1.3, баг 13.11.2026)

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

    /** Маска management касается только новых ссылок: запись 1.0.3 с адресом вне маски читается как есть. */
    @Test fun recordOutsideMaskFromOlderBuildIsRead() {
        val kv = MemoryStore()
        kv.putString(
            "jetbox.currentBox",
            """{"managementURL":"https://netbird.example.net","exitNodeName":"one","selectedExitNode":"one","boxName":"Дом","registeredAtMillis":1}""",
        )
        val b = JetBoxStore(kv).currentBox
        assertEquals(box(url = "https://netbird.example.net", at = 1L), b)
        assertFalse(JetBoxLink.isAllowedManagement(b!!.managementURL))
        assertEquals("netbird.example.net|one|Дом", b.identity)
    }

    /** LINK-CONTRACT 1.3 §2.11: `хост|e|n` / `хост|n:<n>`, токен не участвует. */
    @Test fun identityRuleOfContract13() {
        val e = box(exit = "one").copy(deviceTokenHash = "t1")
        assertEquals("токен не участвует", "mgmt.jet-net.net|one|Дом", e.identity)
        assertEquals(e.identity, e.copy(deviceTokenHash = "t2").identity)
        assertEquals(e.identity, e.copy(deviceTokenHash = null).identity)
        val t1 = box(exit = null).copy(deviceTokenHash = "t1")
        assertNotEquals("имя участвует", t1.identity, t1.copy(boxName = "Другой тариф").identity)
        assertEquals(t1.identity, t1.copy(deviceTokenHash = "t2").identity)
        assertEquals(t1.identity, box(exit = null).identity)
        val noExit = box(exit = null)
        assertEquals(noExit.identity, box(exit = null, selected = null).identity)
        assertNotEquals(noExit.identity, box(exit = null, name = "Дача").identity)
        assertNotEquals(noExit.identity, box(exit = null, url = "https://other.jet-net.net").identity)
        // Облачные абоненты делят e=cl-exit — различаются по имени.
        assertNotEquals(box(exit = "cl-exit", name = "Ivanov").identity, box(exit = "cl-exit", name = "Petrov").identity)
    }

    @Test fun storedTokenMatchesLinkIdentity() {
        val p = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Tariff&t=tok&p=android")
        val store = JetBoxStore(MemoryStore())
        store.remember(p, 1L)
        assertEquals(p.boxIdentity, store.currentBox!!.identity)
        assertTrue(store.currentBox!!.isSameConnection(p))
        assertFalse("токена в удостоверении нет", p.boxIdentity.contains("tok"))
        assertFalse(p.boxIdentity.contains(store.currentBox!!.deviceTokenHash!!))
        val rebound = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Tariff&t=tok2&p=android")
        assertTrue("перепривязка (новый t) — то же подключение", store.currentBox!!.isSameConnection(rebound))
    }

    /** Запись ≤ 1.0.2 сравнивается ТЕМ ЖЕ правилом (ред. 1.3 §2.11, отдельной legacy-формулы нет). */
    @Test fun legacyRecordUsesSameFormula() {
        val legacy = JetBoxBox("https://netbird.jet-net.net", null, null, "Тариф", 1L)
        assertTrue(legacy.isLegacy)
        val ownCloud = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Тариф&t=tok")
        assertTrue("своя облачная ссылка с t — тот же JetBox", legacy.isSameConnection(ownCloud))
        val renamed = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&n=Другое")
        assertFalse("другое имя — «Другой JetBox»", legacy.isSameConnection(renamed))
        assertNull(legacy.renamed(renamed))
        assertFalse(legacy.isSameConnection(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=exit&n=Тариф")))
        assertFalse(legacy.isSameConnection(payload("jetbox://connect#k=$key&m=https://other.jet-net.net&n=Тариф")))

        // Облачная запись ≤ 1.0.2 с общим e=cl-exit: другой абонент — «Другой JetBox», тот же — нет.
        val legacyCloud = JetBoxBox("https://netbird.jet-net.net", "cl-exit", "cl-exit", "Ivanov", 1L)
        assertFalse(legacyCloud.isSameConnection(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=Petrov&t=x")))
        assertTrue(legacyCloud.isSameConnection(payload("jetbox://connect#k=$key&m=https://NetBird.jet-net.net/&e=cl-exit&n=Ivanov&t=x")))

        // Новая запись (с отпечатком) — то же правило.
        val modern = legacy.copy(setupKeyFingerprint = "ab".repeat(16))
        assertEquals(legacy.isSameConnection(ownCloud), modern.isSameConnection(ownCloud))
        assertEquals(legacy.isSameConnection(renamed), modern.isSameConnection(renamed))
    }

    // region сравнение без имени (LINK-CONTRACT 1.3 §2.11): по хост|e, кроме общего выхода облака cl-…

    @Test fun recordWithoutNameAndBoxLinkWithNameIsSame() {
        val link = payload("jetbox://connect#k=$key&m=https://MGMT.jet-net.net/&e=one&n=Дом&t=x")
        assertTrue(box(name = null).isSameConnection(link))
        assertTrue(box(name = "").isSameConnection(link))
        assertFalse("другой выход — другое подключение", box(exit = "two", name = null).isSameConnection(link))
        assertFalse("другой хост", box(url = "https://other.jet-net.net", name = null).isSameConnection(link))
    }

    @Test fun recordWithNameAndBoxLinkWithoutNameIsSame() {
        assertTrue(box(name = "Дом").isSameConnection(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one")))
        assertFalse(box(name = "Дом").isSameConnection(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=two")))
    }

    @Test fun sharedCloudExitWithoutNameOnEitherSideAsks() {
        val named = box(url = "https://netbird.jet-net.net", exit = "cl-exit", name = "JetBox · ivan")
        val unnamed = box(url = "https://netbird.jet-net.net", exit = "cl-exit", name = null)
        val linkNoName = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&t=x")
        val linkNamed = payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=JetBox%20%C2%B7%20ivan&t=x")
        assertFalse("запись с именем, ссылка без n", named.isSameConnection(linkNoName))
        assertFalse("запись без имени, ссылка с n", unnamed.isSameConnection(linkNamed))
        assertFalse("любой cl-… — общий выход", box(exit = "cl-eu", name = null).isSameConnection(
            payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=cl-eu&n=A")))
        assertTrue("тот же абонент", named.isSameConnection(linkNamed))
        // Без имени с обеих сторон строки удостоверения совпадают (`хост|cl-exit|`) — как iOS, вопроса нет.
        assertTrue(unnamed.isSameConnection(linkNoName))
    }

    @Test fun differentNamesOnSharedCloudExitAsk() {
        val ivan = box(url = "https://netbird.jet-net.net", exit = "cl-exit", name = "JetBox · ivan")
        assertFalse(ivan.isSameConnection(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=JetBox%20%C2%B7%20petr")))
    }

    @Test fun withoutExitComparedByStringOnly() {
        val named = box(exit = null, name = "Дом")
        assertFalse("без e имя не отбрасываем", named.isSameConnection(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net")))
        assertFalse(box(exit = null, name = null).isSameConnection(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&n=Дом")))
        assertTrue(box(exit = null, name = null).isSameConnection(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&t=x")))
        assertFalse("у записи нет e, у ссылки есть", box(exit = null, name = null).isSameConnection(payload("jetbox://connect#k=$key&m=https://mgmt.jet-net.net&e=one")))
    }

    // endregion

    // region x=0 (LINK-CONTRACT §2.10): выход из ссылки только разрешён, не включён

    @Test fun rememberWithXZeroDoesNotSelectExit() {
        val store = JetBoxStore(MemoryStore())
        store.remember(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=Tariff&x=0"), 5L)
        val b = store.currentBox!!
        assertEquals("удостоверение — из ссылки", "cl-exit", b.exitNodeName)
        assertNull("выход не включён", b.selectedExitNode)

        store.remember(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=Tariff"), 6L)
        assertEquals("без x — как раньше", "cl-exit", store.currentBox!!.selectedExitNode)
    }

    @Test fun rememberWithXZeroKeepsManualExitOfSameConnection() {
        val store = JetBoxStore(MemoryStore())
        val link = "jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=Tariff&x=0"
        store.remember(payload(link), 5L)
        store.rememberExitNode("cl-exit")
        store.remember(payload(link), 6L)
        assertEquals("то же подключение — выбор руками сохранён", "cl-exit", store.currentBox!!.selectedExitNode)
        store.remember(payload("jetbox://connect#k=$key&m=https://netbird.jet-net.net&e=cl-exit&n=Other&x=0"), 7L)
        assertNull("другое подключение — не переносится", store.currentBox!!.selectedExitNode)
    }

    // endregion
}
