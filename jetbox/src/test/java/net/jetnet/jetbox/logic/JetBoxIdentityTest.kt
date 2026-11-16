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

    private fun link(k: String = keyA, m: String = "https://nb.example.net", e: String? = "home"): JetBoxLinkPayload =
        (JetBoxLink.parse("jetbox://connect#k=$k&m=$m" + (e?.let { "&e=$it" } ?: "")) as JetBoxLink.Outcome.Payload).payload

    private fun box(m: String = "https://nb.example.net", e: String? = "home", fp: String? = JetBoxIdentity.fingerprint(keyA)) =
        JetBoxBox(m, e, e, null, 1L, setupKeyFingerprint = fp)

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
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(m = "https://old.example.net"), link(), configExists = true))
    }

    @Test fun recordWithoutFingerprintFollowsIdentity() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null), link(k = keyB), configExists = true))
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(fp = null, e = "other"), link(), configExists = true))
    }

    /**
     * Запись ≤ 1.0.2 без `e` + облачная ссылка (без `e`): `t` в старой записи не хранился, и по
     * `хост|e` любые две облачные ссылки одного management — «тот же» JetBox. Ключ — новый, иначе
     * чужая облачная ссылка осталась бы прежним пиром (баг 13.11.2026).
     */
    @Test fun legacyRecordAndCloudLinkIsFresh() {
        val legacy = JetBoxBox("https://netbird.jet-net.net", null, null, "Тариф", 1L)
        val cloud = (JetBoxLink.parse("jetbox://connect#k=$keyA&m=https://netbird.jet-net.net&n=Тариф&t=tok")
            as JetBoxLink.Outcome.Payload).payload
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(legacy, cloud, configExists = true))
        val other = (JetBoxLink.parse("jetbox://connect#k=$keyB&m=https://netbird.jet-net.net&n=Тариф&t=tok2")
            as JetBoxLink.Outcome.Payload).payload
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(legacy, other, configExists = true))
    }

    /** Запись ≤ 1.0.2 коробки + ссылка той же коробки (тот же `e`) — прежний ключ, даже с другим setup key. */
    @Test fun legacyRecordAndSameBoxLinkReuses() {
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null), link(k = keyB), configExists = true))
        assertEquals(Plan.REUSE, JetBoxIdentity.plan(box(fp = null), link(), configExists = true))
        assertEquals(Plan.FRESH, JetBoxIdentity.plan(box(fp = null, e = null), link(e = null), configExists = true))
    }

    @Test fun tokenFingerprintKeepsCase() {
        assertNotEquals(net.jetnet.jetbox.link.secretFingerprint("Tok"), net.jetnet.jetbox.link.secretFingerprint("tok"))
        assertEquals(JetBoxIdentity.fingerprint(keyA), net.jetnet.jetbox.link.secretFingerprint(keyA.lowercase()))
    }
}
