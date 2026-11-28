package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Живые показатели нижней панели — порт `JetBoxLiveStatsTests.swift` (iOS: выбор пира-дома,
 * разбор пинга из строки Go, время в сети). Вход адаптирован к `PeerSnapshot`, где задержка
 * уже числом, поэтому разбор строки Go вынесен в `LiveStats.parseLatencyMs`.
 */
class LiveStatsTest {
    private fun peer(
        name: String,
        connected: Boolean = true,
        routes: List<String> = emptyList(),
        latencyMs: Long = 0,
        relayed: Boolean = false,
        ip: String = "100.64.0.1/16",
        rx: Long = 0,
        tx: Long = 0,
    ) = PeerSnapshot(ip, name, connected, relayed, latencyMs, rx, tx, routes)

    @Test fun boxIsThePeerThatCarriesTheExitRoute() {
        val peers = listOf(peer("laptop"), peer("box", routes = listOf("192.168.1.0/24", "0.0.0.0/0")), peer("nas"))
        assertEquals("box", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun ipv6ExitRouteCountsToo() {
        assertEquals("box6", LiveStats.homePeer(listOf(peer("box6", routes = listOf("::/0"))))?.fqdn)
    }

    @Test fun connectedExitWinsOverIdleOne() {
        val peers = listOf(
            peer("old", connected = false, routes = listOf("0.0.0.0/0")),
            peer("live", connected = true, routes = listOf("0.0.0.0/0")),
        )
        assertEquals("live", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun idleExitIsStillTheBoxIfNothingBetter() {
        val peers = listOf(peer("laptop"), peer("old", connected = false, routes = listOf("0.0.0.0/0")))
        assertEquals("old", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun withoutExitThePeerWithHomeNetworkIsTheBox() {
        // x=0: выход выключен, коробка раздаёт только домашнюю сеть.
        val peers = listOf(peer("laptop"), peer("box", routes = listOf("192.168.60.0/24")), peer("phone"))
        assertEquals("box", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun exitBeatsHomeNetworkRoute() {
        val peers = listOf(peer("lan", routes = listOf("192.168.60.0/24")), peer("exit", routes = listOf("0.0.0.0/0")))
        assertEquals("exit", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun connectedRoutedPeerWinsOverIdleOne() {
        val peers = listOf(
            peer("old", connected = false, routes = listOf("192.168.60.0/24")),
            peer("live", connected = true, routes = listOf("192.168.60.0/24")),
        )
        assertEquals("live", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun withoutRoutesTheOnlyConnectedPeerIsTheBox() {
        val peers = listOf(peer("box", connected = true), peer("gone", connected = false))
        assertEquals("box", LiveStats.homePeer(peers)?.fqdn)
    }

    @Test fun withoutRoutesSeveralConnectedPeersMeanNoBox() {
        assertNull(LiveStats.homePeer(listOf(peer("a"), peer("b"))))
        assertNull(LiveStats.homePeer(listOf(peer("idle", connected = false))))
        assertNull(LiveStats.homePeer(emptyList()))
    }

    @Test fun statsComeFromTheHomeNetworkPeer() {
        val s = LiveStats.stats(
            listOf(peer("box", routes = listOf("192.168.60.0/24"), latencyMs = 42, relayed = false, ip = "100.64.1.2/16", rx = 10, tx = 20)),
        )
        assertEquals(HomeStats(relayed = false, bytesRx = 10, bytesTx = 20, coreLatencyMs = 42, homeIp = "100.64.1.2"), s)
    }

    @Test fun latencyFromGoDuration() {
        assertEquals(12L, LiveStats.parseLatencyMs("12.345ms"))
        assertEquals(25L, LiveStats.parseLatencyMs("24.6ms"))
        assertEquals(1200L, LiveStats.parseLatencyMs("1.2s"))
        assertEquals(1L, LiveStats.parseLatencyMs("850µs"))
        assertEquals(1L, LiveStats.parseLatencyMs("850us"))
    }

    @Test fun unknownLatencyIsNull() {
        assertNull(LiveStats.parseLatencyMs("0s"))
        assertNull(LiveStats.parseLatencyMs(""))
        assertNull(LiveStats.parseLatencyMs("1m2.5s"))
        assertNull(LiveStats.parseLatencyMs("n/a"))
    }

    @Test fun uptimeFormatting() {
        assertEquals("0:00", LiveStats.uptime(0))
        assertEquals("1:05", LiveStats.uptime(65))
        assertEquals("1:02:05", LiveStats.uptime(3_725))
        assertEquals("0:00", LiveStats.uptime(-5))
    }

    @Test fun statsTakeTheHomePeerValues() {
        val peers = listOf(
            peer("laptop", rx = 1, tx = 1),
            peer("box", routes = listOf("0.0.0.0/0"), latencyMs = 23, relayed = true, ip = "100.64.0.10/16", rx = 500, tx = 70),
        )
        assertEquals(HomeStats(true, 500, 70, 23L, "100.64.0.10"), LiveStats.stats(peers))
    }

    @Test fun unknownCoreLatencyIsNullNotZero() {
        val s = LiveStats.stats(listOf(peer("box", routes = listOf("0.0.0.0/0"), latencyMs = 0)))
        assertNull(s.coreLatencyMs)
        assertEquals(false, s.relayed)
    }

    @Test fun noHomePeerGivesEmptyStats() {
        assertEquals(HomeStats(null, 0, 0, null, null), LiveStats.stats(listOf(peer("laptop"), peer("tablet"))))
        assertEquals(HomeStats(null, 0, 0, null, null), LiveStats.stats(emptyList()))
    }

    @Test fun meshIpWithoutMask() {
        assertEquals("100.64.0.10", LiveStats.meshIp("100.64.0.10/16"))
        assertEquals("100.64.0.10", LiveStats.meshIp("100.64.0.10"))
    }

    // --- дом ответил (AppDev 22.11.2026) ---

    private fun box(connected: Boolean = true, rx: Long = 100, routes: List<String> = listOf("0.0.0.0/0")) =
        PeerSnapshot("100.64.0.10/16", "box", connected, false, 0, rx, 0, routes)

    @Test fun homeAnsweredNeedsConnectionAndPackets() {
        assertTrue(LiveStats.homeAnswered(listOf(box())))
        assertFalse("пакетов от дома не было", LiveStats.homeAnswered(listOf(box(rx = 0))))
        assertFalse("не на связи", LiveStats.homeAnswered(listOf(box(connected = false))))
        assertFalse("пиров ещё нет", LiveStats.homeAnswered(emptyList()))
    }

    @Test fun homeAnsweredLooksAtHomePeerNotOthers() {
        val other = PeerSnapshot("100.64.0.20/16", "laptop", true, false, 0, 5_000, 0, emptyList())
        assertFalse("ответил чужой пир, коробка молчит", LiveStats.homeAnswered(listOf(box(rx = 0), other)))
    }

    @Test fun homeAnsweredWithoutRecognizableHomeTakesAnyAnsweredPeer() {
        val a = PeerSnapshot("100.64.0.20/16", "a", true, false, 0, 5_000, 0, emptyList())
        val b = PeerSnapshot("100.64.0.21/16", "b", true, false, 0, 0, 0, emptyList())
        assertTrue(LiveStats.homeAnswered(listOf(a, b)))
        assertFalse(LiveStats.homeAnswered(listOf(b, b.copy(ip = "100.64.0.22/16"))))
    }
}
