package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор `version.json` с сайта: всё сомнительное — `null`, приложение просто молчит. */
class UpdateInfoTest {
    private val apk = "https://box.jet-net.net/android/JetBox.apk"
    private val sha = "a".repeat(64)

    private fun json(
        code: String = "11",
        name: String = "\"1.1\"",
        url: String = "\"$apk\"",
        extra: String = "",
    ) = """{"versionCode":$code,"versionName":$name,"url":$url$extra}"""

    @Test fun parsesMinimal() {
        assertEquals(UpdateInfo(11, "1.1", apk, null), UpdateInfo.parse(json()))
    }

    @Test fun parsesSha256AndIgnoresExtraFields() {
        val info = UpdateInfo.parse(json(extra = ""","sha256":"${sha.uppercase()}","notes":"что-то","size":123"""))
        assertEquals(UpdateInfo(11, "1.1", apk, sha), info)
    }

    @Test fun nullSha256IsAbsent() {
        assertEquals(UpdateInfo(11, "1.1", apk, null), UpdateInfo.parse(json(extra = ""","sha256":null""")))
    }

    @Test fun badSha256RejectsWhole() {
        assertNull(UpdateInfo.parse(json(extra = ""","sha256":"abc"""")))
        assertNull(UpdateInfo.parse(json(extra = ""","sha256":"${"g".repeat(64)}"""")))
        assertNull(UpdateInfo.parse(json(extra = ""","sha256":42""")))
    }

    @Test fun brokenJson() {
        assertNull(UpdateInfo.parse(""))
        assertNull(UpdateInfo.parse("not json"))
        assertNull(UpdateInfo.parse("""{"versionCode":11,"""))
        assertNull(UpdateInfo.parse("[]"))
        assertNull(UpdateInfo.parse("<html>404 Not Found</html>"))
    }

    @Test fun versionCodeMustBePositiveInteger() {
        assertNull(UpdateInfo.parse(json(code = "0")))
        assertNull(UpdateInfo.parse(json(code = "-5")))
        assertNull(UpdateInfo.parse(json(code = "11.5")))
        assertNull(UpdateInfo.parse(json(code = "\"11\"")))
        assertNull(UpdateInfo.parse(json(code = "null")))
        assertNull(UpdateInfo.parse(json(code = "99999999999")))
        assertNull(UpdateInfo.parse("""{"versionName":"1.1","url":"$apk"}"""))
    }

    @Test fun versionNameMustBeShortAndPlain() {
        assertNull(UpdateInfo.parse(json(name = "\"\"")))
        assertNull(UpdateInfo.parse(json(name = "\"  \"")))
        assertNull(UpdateInfo.parse(json(name = "11")))
        assertNull(UpdateInfo.parse(json(name = "\"1.1\\nскачайте тут\"")))
        assertNull(UpdateInfo.parse(json(name = "\"${"1".repeat(40)}\"")))
        assertEquals("1.1.2-beta+3", UpdateInfo.parse(json(name = "\"1.1.2-beta+3\""))?.versionName)
    }

    @Test fun urlOnlyHttpsOnOurHost() {
        assertNotNull(UpdateInfo.parse(json(url = "\"https://box.jet-net.net/android/JetBox-1.1.apk\"")))
        assertNotNull(UpdateInfo.parse(json(url = "\"https://box.jet-net.net:443/android/JetBox.apk\"")))
        for (bad in listOf(
            "http://box.jet-net.net/android/JetBox.apk",
            "https://evil.example/android/JetBox.apk",
            "https://box.jet-net.net.evil.example/JetBox.apk",
            "https://evilbox.jet-net.net/JetBox.apk",
            "https://jet-net.net/android/JetBox.apk",
            "https://user@box.jet-net.net/android/JetBox.apk",
            "https://box.jet-net.net@evil.example/JetBox.apk",
            "https://box.jet-net.net:8443/android/JetBox.apk",
            "https://box.jet-net.net",
            "//box.jet-net.net/android/JetBox.apk",
            "file:///sdcard/JetBox.apk",
            "intent://box.jet-net.net/#Intent;end",
            "javascript:alert(1)",
            "https://box.jet-net.net/android/Jet Box.apk",
            "",
        )) assertNull("пропущен $bad", UpdateInfo.parse(json(url = "\"$bad\"")))
        assertNull(UpdateInfo.parse(json(url = "null")))
        assertNull(UpdateInfo.parse(json(url = "42")))
    }

    @Test fun isNewerOnlyForGreaterCode() {
        val info = UpdateInfo(11, "1.1", apk, null)
        assertTrue(info.isNewer(10))
        assertFalse(info.isNewer(11))
        assertFalse(info.isNewer(12))
    }

    @Test fun roundTripThroughJson() {
        val info = UpdateInfo(11, "1.1", apk, sha)
        assertEquals(info, UpdateInfo.parse(info.toJson()))
        val bare = UpdateInfo(12, "1.2", apk, null)
        assertEquals(bare, UpdateInfo.parse(bare.toJson()))
    }
}
