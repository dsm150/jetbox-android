package net.jetnet.jetbox.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkIntentTest {
    private val link = "jetbox://connect#k=11111111-2222-4333-8444-555555555555&m=https%3A%2F%2Fnetbird.jet-net.net"

    @Test fun viewLinkIsTakenWhole() =
        // Фрагмент (#…) — часть ссылки: ключ живёт там.
        assertEquals(link, LinkIntent.linkFrom(LinkIntent.ACTION_VIEW, link, launchedFromHistory = false))

    @Test fun launcherStartHasNoLink() =
        assertNull(LinkIntent.linkFrom("android.intent.action.MAIN", null, launchedFromHistory = false))

    @Test fun otherActionIgnored() =
        assertNull(LinkIntent.linkFrom("android.intent.action.SEND", link, launchedFromHistory = false))

    @Test fun emptyDataIgnored() {
        assertNull(LinkIntent.linkFrom(LinkIntent.ACTION_VIEW, null, launchedFromHistory = false))
        assertNull(LinkIntent.linkFrom(LinkIntent.ACTION_VIEW, "", launchedFromHistory = false))
    }

    @Test fun recentsDoNotReplayLink() =
        // Запуск из «Недавних» повторяет исходный интент — ссылку второй раз не разбираем.
        assertNull(LinkIntent.linkFrom(LinkIntent.ACTION_VIEW, link, launchedFromHistory = true))

    @Test fun launcherAndNotificationArePlainOpen() {
        // Ярлык — MAIN; уведомление и плитка открывают окно по имени класса, без действия.
        assertTrue(LinkIntent.isPlainOpen(LinkIntent.ACTION_MAIN, null))
        assertTrue(LinkIntent.isPlainOpen(null, null))
        assertTrue(LinkIntent.isPlainOpen(null, ""))
    }

    @Test fun linkOrOtherActionIsNotPlainOpen() {
        assertFalse(LinkIntent.isPlainOpen(LinkIntent.ACTION_VIEW, link))
        assertFalse(LinkIntent.isPlainOpen(LinkIntent.ACTION_VIEW, null))
        assertFalse(LinkIntent.isPlainOpen(LinkIntent.ACTION_MAIN, link))
        // «Продлить сессию» из уведомления — дело MainActivity, не повод открывать её заново.
        assertFalse(LinkIntent.isPlainOpen("io.netbird.client.intent.action.EXTEND_SESSION", null))
    }
}
