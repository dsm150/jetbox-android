package net.jetnet.jetbox.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ссылки подключения — порт `JetBoxLinkTests.swift` (iOS) 1:1 + тесты Android-плана.
 * Три iOS-теста про `JetBoxBox.renamed(by:)` переезжают в тесты хранилища (задача 4).
 * Фикстура ключа — пример из LINK-CONTRACT.md, не настоящий ключ.
 */
class JetBoxLinkTest {
    private val key = "11111111-2222-4333-8444-555555555555"

    private fun parse(s: String) = JetBoxLink.parse(s)

    private fun payload(s: String): JetBoxLinkPayload {
        val r = parse(s)
        assertTrue("ожидали payload, получили $r", r is JetBoxLink.Outcome.Payload)
        return (r as JetBoxLink.Outcome.Payload).payload
    }

    // region Universal Link

    @Test fun universalLinkFullContract() {
        val p = payload("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=box-ivanov-office")
        assertEquals(key, p.setupKey)
        assertEquals("https://netbird.jet-net.net", p.managementURL)
        assertEquals("box-ivanov-office", p.exitNodeName)
        assertNull(p.boxName)
    }

    @Test fun universalLinkWithHumanName() {
        val encoded = "%D0%9E%D1%84%D0%B8%D1%81%20%D0%BD%D0%B0%20%D0%A2%D0%B2%D0%B5%D1%80%D1%81%D0%BA%D0%BE%D0%B9"
        val p = payload("https://box.jet-net.net/connect#k=$key&m=https://netbird.jet-net.net&e=box1&n=$encoded")
        assertEquals("Офис на Тверской", p.boxName)
        assertEquals("Офис на Тверской", p.displayName)
    }

    @Test fun universalLinkTrailingSlashAndSubpath() {
        assertNotNull(payload("https://box.jet-net.net/connect/#k=$key"))
        assertNotNull(payload("https://box.jet-net.net/connect/ios#k=$key"))
    }

    @Test fun hostAndSchemeAreCaseInsensitive() {
        assertNotNull(payload("HTTPS://BOX.JET-NET.NET/connect#k=$key"))
        assertNotNull(payload("JETBOX://CONNECT#k=$key"))
    }

    @Test fun foreignHostIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://example.com/connect#k=$key"))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://box.jet-net.net.evil.com/connect#k=$key"))
    }

    @Test fun foreignPathIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://box.jet-net.net/#k=$key"))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://box.jet-net.net/connected#k=$key"))
    }

    // endregion

    // region Своя схема

    @Test fun customSchemeMatchesUniversalLink() {
        val a = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=node")
        val b = payload("https://box.jet-net.net/connect#k=$key&m=https://mgmt.example.net&e=node")
        assertEquals(a, b)
    }

    @Test fun foreignSchemeIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("netbird://connect#k=$key"))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("jetbox://disconnect#k=$key"))
    }

    // endregion

    // region Параметры

    @Test fun managementDefaultsWhenMissingOrEmpty() {
        assertEquals(JetBoxConfig.DEFAULT_MANAGEMENT_URL, payload("jetbox://connect#k=$key").managementURL)
        assertEquals(JetBoxConfig.DEFAULT_MANAGEMENT_URL, payload("jetbox://connect#k=$key&m=").managementURL)
    }

    @Test fun managementNormalisation() {
        assertEquals(
            "https://mgmt.example.net",
            payload("jetbox://connect#k=$key&m=https://Mgmt.Example.NET/").managementURL,
        )
        assertEquals(
            "https://mgmt.example.net:33073",
            payload("jetbox://connect#k=$key&m=https://mgmt.example.net:33073").managementURL,
        )
    }

    @Test fun parameterOrderAndUnknownParametersDoNotMatter() {
        val p = payload("jetbox://connect#e=node&z=whatever&m=https://mgmt.example.net&k=$key")
        assertEquals("node", p.exitNodeName)
        assertEquals("https://mgmt.example.net", p.managementURL)
    }

    /** Кабинет добавляет `p=` («что подключаем»); по контракту §2.1 неизвестные параметры игнорируются. */
    @Test fun cabinetParameterPIsIgnored() {
        val withP = payload("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=box-ivanov-office&p=%D1%82%D0%B5%D0%BB%D0%B5%D1%84%D0%BE%D0%BD")
        val withoutP = payload("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=box-ivanov-office")
        assertEquals(withP, withoutP)
        assertEquals("box-ivanov-office", withP.exitNodeName)
        assertEquals(withP.boxIdentity, withoutP.boxIdentity)
    }

    @Test fun parameterNamesAreCaseInsensitive() {
        assertEquals("node", payload("jetbox://connect#K=$key&E=node").exitNodeName)
    }

    @Test fun exitNodeKeepsCaseAndSpaces() {
        assertEquals("Box Ivanov", payload("jetbox://connect#k=$key&e=Box%20Ivanov").exitNodeName)
        assertEquals("BoxIvanov", payload("jetbox://connect#k=$key&e=BoxIvanov").exitNodeName)
    }

    @Test fun plusIsNotASpaceInFragment() {
        assertEquals("a+b", payload("jetbox://connect#k=$key&e=a+b").exitNodeName)
    }

    @Test fun equalsSignInsideValueSurvives() {
        assertEquals("a=b", payload("jetbox://connect#k=$key&n=a=b").boxName)
    }

    // endregion

    // region Мусор

    @Test fun missingFragmentIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://box.jet-net.net/connect"))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://box.jet-net.net/connect#"))
    }

    @Test fun missingKeyIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://box.jet-net.net/connect#m=https://mgmt.example.net"))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("jetbox://connect#k="))
    }

    @Test fun malformedKeyIsBroken() {
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=not-a-uuid"))
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=${key}X"))
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=${key.dropLast(1)}"))
        // 36 символов, но не UUID
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=" + "Z".repeat(36)))
    }

    @Test fun nonHttpsManagementIsBroken() {
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=$key&m=http://mgmt.example.net"))
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=$key&m=mgmt.example.net"))
        assertEquals(JetBoxLink.Outcome.Broken, parse("jetbox://connect#k=$key&m=javascript:alert(1)"))
    }

    @Test fun garbageFragmentDoesNotCrash() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("jetbox://connect#&&&==="))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("jetbox://connect#k"))
    }

    // endregion

    // region Показ пользователю

    @Test fun keyIsNeverShownInFull() {
        val masked = payload("jetbox://connect#k=$key").maskedKey
        assertFalse(masked.contains(key))
        assertTrue(masked.endsWith(key.takeLast(4)))
        assertEquals(8, masked.count { it == '•' })
    }

    @Test fun displayNameFallsBackFromNameToExitToHost() {
        assertEquals("Дом", payload("jetbox://connect#k=$key&n=Дом&e=node").displayName)
        assertEquals("node", payload("jetbox://connect#k=$key&e=node").displayName)
        assertEquals("mgmt.example.net", payload("jetbox://connect#k=$key&m=https://mgmt.example.net").displayName)
    }

    @Test fun boxIdentityDistinguishesBoxes() {
        val a = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=one")
        val b = payload("jetbox://connect#k=$key&m=https://mgmt.example.net&e=two")
        val sameAsA = payload("jetbox://connect#k=$key&m=https://MGMT.example.net/&e=one")
        assertNotEquals(a.boxIdentity, b.boxIdentity)
        assertEquals(a.boxIdentity, sameAsA.boxIdentity)
    }

    // endregion

    // region Тесты Android-плана (в iOS-файле их нет)

    @Test fun cyrillicNameWithoutManagement() {
        val p = payload("https://box.jet-net.net/connect#k=$key&n=%D0%9E%D1%84%D0%B8%D1%81%20%D0%BD%D0%B0%20%D0%A2%D0%B2%D0%B5%D1%80%D1%81%D0%BA%D0%BE%D0%B9")
        assertEquals("Офис на Тверской", p.boxName)
    }

    @Test fun plusIsLiteralInName() {
        assertEquals("A+B", payload("jetbox://connect#k=$key&n=A+B").boxName)
    }

    @Test fun doubleEncodedDecodedOnce() {
        assertEquals("a%20b", payload("jetbox://connect#k=$key&n=a%2520b").boxName)
    }

    @Test fun brokenPercentEscapeKeepsRawValue() {
        assertEquals("a%zzb", payload("jetbox://connect#k=$key&n=a%zzb").boxName)
    }

    @Test fun invalidUtf8InValueKeepsRawValue() {
        assertEquals("%FF", payload("jetbox://connect#k=$key&n=%FF").boxName)
    }

    @Test fun danglingPercentKeepsRawValue() {
        assertEquals("a%", payload("jetbox://connect#k=$key&n=a%").boxName)
        assertEquals("a%4", payload("jetbox://connect#k=$key&n=a%4").boxName)
    }

    @Test fun validUtf8PercentSequenceIsDecoded() {
        assertEquals("О", payload("jetbox://connect#k=$key&n=%D0%9E").boxName)
    }

    @Test fun percentEncodedPathIsComparedDecoded() {
        assertNotNull(payload("https://box.jet-net.net/%63onnect#k=$key"))
    }

    @Test fun queryJunkDoesNotMakeLinkForeign() {
        assertNotNull(payload("https://box.jet-net.net/connect?x=a|b c#k=$key"))
        assertNotNull(payload("https://box.jet-net.net/connect?utm=1#k=$key"))
    }

    @Test fun noKeyAmongOtherParamsIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("jetbox://connect#m=x"))
    }

    @Test fun duplicateParameterFirstWins() {
        assertEquals("one", payload("jetbox://connect#k=$key&e=one&e=two").exitNodeName)
    }

    @Test fun lookalikeHostAndPlainHttpAreNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("https://evilbox.jet-net.net/connect#k=$key"))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("http://box.jet-net.net/connect#k=$key"))
    }

    @Test fun unparsableUrlIsNotOurs() {
        assertEquals(JetBoxLink.Outcome.NotOurs, parse(""))
        assertEquals(JetBoxLink.Outcome.NotOurs, parse("not a url at all"))
    }

    @Test fun emptyValuesAreAbsent() {
        val p = payload("jetbox://connect#k=$key&e=&n=")
        assertNull(p.exitNodeName)
        assertNull(p.boxName)
    }

    @Test fun whitespaceOnlyValueIsAbsent() {
        val p = payload("jetbox://connect#k=$key&e=%20&n=%20%20")
        assertNull(p.exitNodeName)
        assertNull(p.boxName)
    }

    @Test fun keyNeverLeaksThroughToString() {
        val link = "jetbox://connect#k=$key&m=https://mgmt.example.net&e=node"
        assertFalse(payload(link).toString().contains(key))
        assertFalse(JetBoxLink.parse(link).toString().contains(key))
    }

    @Test fun maskedKeyShowsLastFour() {
        assertEquals("••••••••…5555", payload("jetbox://connect#k=$key").maskedKey)
    }

    @Test fun maskedShortKeyHidesEverything() {
        assertEquals("••••", JetBoxLinkPayload("abcd", JetBoxConfig.DEFAULT_MANAGEMENT_URL, null, null).maskedKey)
    }

    @Test fun identityIsHostAndExitKeepsPortInUrl() {
        val p = payload("jetbox://connect#k=$key&m=https%3A%2F%2FNetBird.Jet-Net.net%3A443%2F&e=Ex")
        assertEquals("https://netbird.jet-net.net:443", p.managementURL)
        assertEquals("netbird.jet-net.net|Ex", p.boxIdentity)
    }

    @Test fun managementKeepsSubpathWithoutTrailingSlash() {
        assertEquals(
            "https://mgmt.example.net/api",
            payload("jetbox://connect#k=$key&m=https://mgmt.example.net/api/").managementURL,
        )
    }

    @Test fun setupKeyValidationIsStrict() {
        assertTrue(JetBoxLink.isValidSetupKey(key))
        assertTrue(JetBoxLink.isValidSetupKey("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        // Длина 36, но не UUID: «короткие» сегменты и «+» в начале сегмента (его UUID.fromString пропускает).
        assertFalse(JetBoxLink.isValidSetupKey("1-1-1-1-1" + "0".repeat(27)))
        assertFalse(JetBoxLink.isValidSetupKey("+1111111-2222-4333-8444-555555555555"))
        assertFalse(JetBoxLink.isValidSetupKey(key.replace('-', '_')))
    }

    // endregion
}
