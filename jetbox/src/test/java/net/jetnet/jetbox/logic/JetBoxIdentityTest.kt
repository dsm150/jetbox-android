package net.jetnet.jetbox.logic

import net.jetnet.jetbox.link.JetBoxLink
import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.logic.JetBoxIdentity.Plan
import net.jetnet.jetbox.store.JetBoxBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Новая ли личность устройства (ключ WireGuard) — порт `JetBoxIdentity.swift` (iOS). */
class JetBoxIdentityTest {
    private val keyA = "11111111-2222-4333-8444-555555555555"
    private val keyB = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private fun link(
        k: String = keyA,
        m: String = "https://nb.jet-net.net",
        e: String? = "home",
        n: String? = null,
        t: String? = null,
    ): JetBoxLinkPayload =
        (JetBoxLink.parse(
            "jetbox://connect#k=$k&m=$m" + (e?.let { "&e=$it" } ?: "") + (n?.let { "&n=$it" } ?: "") + (t?.let { "&t=$it" } ?: ""), signed = false
        ) as JetBoxLink.Outcome.Payload).payload

    private fun box(
        m: String = "https://nb.jet-net.net",
        e: String? = "home",
        fp: String? = JetBoxIdentity.fingerprint(keyA),
        n: String? = null,
    ) = JetBoxBox(m, e, e, n, 1L, setupKeyFingerprint = fp)

    @Test fun fingerprintIsLikeIos() {
        // SHA-256 ключа в нижнем регистре, первые 16 байт hex — вектор посчитан независимо (python hashlib).
        assertEquals("cf4c4732fd3b8f8a55b60871950a2f22", JetBoxIdentity.fingerprint(keyA))
        assertEquals(JetBoxIdentity.fingerprint(keyA), JetBoxIdentity.fingerprint(keyA.uppercase()))
        assertNotEquals(JetBoxIdentity.fingerprint(keyA), JetBoxIdentity.fingerprint(keyB))
        assertFalse(JetBoxIdentity.fingerprint(keyA).contains("1111"))
    }

    @Test fun noConfigReusesBecauseCoreCreatesKeyAnyway() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(null, link(), configExists = false))
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(), link(k = keyB), configExists = false))
    }

    @Test fun configWithoutStoredJetBoxIsFresh() {
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(null, link(), configExists = true))
    }

    @Test fun sameFingerprintReuses() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(), link(), configExists = true))
        assertEquals("отпечаток важнее удостоверения", Plan.REUSE, JetBoxIdentity.plan(box(e = "other"), link(), configExists = true))
    }

    @Test fun otherFingerprintIsFresh() {
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(), link(k = keyB), configExists = true))
    }

    @Test fun otherManagementHostIsFresh() {
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(m = "https://old.jet-net.net"), link(), configExists = true))
    }

    @Test fun recordWithoutFingerprintFollowsIdentity() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null), link(k = keyB), configExists = true))
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(fp = null, e = "other"), link(), configExists = true))
    }

    /**
     * Запись ≤ 1.0.2 без `e` + облачная ссылка без `e`: прежний ключ — только ссылке с `e`
     * (LINK-CONTRACT 1.3 §2.11), иначе чужая облачная ссылка осталась бы прежним пиром (баг 13.11.2026).
     * Экран «Другой JetBox» при том же `хост|n:<n>` не показывается, но ключ всё равно новый.
     */
    @Test fun legacyRecordAndCloudLinkIsFresh() {
        val legacy = JetBoxBox("https://netbird.jet-net.net", null, null, "Тариф", 1L)
        val cloud = (JetBoxLink.parse("jetbox://connect#k=$keyA&m=https://netbird.jet-net.net&n=Тариф&t=tok", signed = false)
            as JetBoxLink.Outcome.Payload).payload
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(legacy, cloud, configExists = true))
        val other = (JetBoxLink.parse("jetbox://connect#k=$keyB&m=https://netbird.jet-net.net&n=Тариф&t=tok2", signed = false)
            as JetBoxLink.Outcome.Payload).payload
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(legacy, other, configExists = true))
    }

    /** Запись ≤ 1.0.2 коробки + ссылка той же коробки (тот же хост, `e`, `n`) — прежний ключ, даже с другим setup key. */
    @Test fun legacyRecordAndSameBoxLinkReuses() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null), link(k = keyB), configExists = true))
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null), link(), configExists = true))
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null, n = "Dom"), link(k = keyB, n = "Dom", t = "x"), configExists = true))
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(fp = null, e = null), link(e = null), configExists = true))
    }

    /** Запись ≤ 1.0.2 той же коробки, но в ссылке другое имя: ред. 1.3 — «Другой JetBox» и новый ключ. */
    @Test fun legacyRecordAndRenamedBoxLinkIsFresh() {
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(fp = null, n = "Dom"), link(n = "Dacha"), configExists = true))
    }

    /** Запись ≤ 1.0.2 коробки без имени (или ссылка без n) — сравнение по хост|e: прежний ключ. */
    @Test fun legacyRecordWithoutNameAndSameBoxLinkWithNameReuses() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null, n = null), link(k = keyB, n = "Dom"), configExists = true))
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null, n = "Dom"), link(k = keyB), configExists = true))
    }

    /** Облачные абоненты делят `e=cl-exit`: запись ≤ 1.0.2 и ссылка другого абонента — новый ключ, своя — прежний. */
    @Test fun legacyCloudRecordSharedExitFollowsName() {
        val legacy = box(m = "https://netbird.jet-net.net", e = "cl-exit", fp = null, n = "Ivanov")
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(legacy, link(k = keyB, m = "https://netbird.jet-net.net", e = "cl-exit", n = "Petrov", t = "t2"), configExists = true))
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(legacy, link(k = keyB, m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov", t = "t9"), configExists = true))
        // Общий выход без имени с одной из сторон — абонента не различить: новый ключ.
        val unnamed = box(m = "https://netbird.jet-net.net", e = "cl-exit", fp = null, n = null)
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(unnamed, link(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov"), configExists = true))
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(legacy, link(m = "https://netbird.jet-net.net", e = "cl-exit"), configExists = true))
    }

    @Test fun tokenFingerprintKeepsCase() {
        assertNotEquals(net.jetnet.jetbox.link.secretFingerprint("Tok"), net.jetnet.jetbox.link.secretFingerprint("tok"))
        assertEquals(JetBoxIdentity.fingerprint(keyA), net.jetnet.jetbox.link.secretFingerprint(keyA.lowercase()))
    }
}
