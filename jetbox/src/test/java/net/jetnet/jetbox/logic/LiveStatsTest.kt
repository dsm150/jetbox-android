package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun noExitRouteMeansNoBox() {
        assertNull(LiveStats.homePeer(listOf(peer("laptop", routes = listOf("10.0.0.0/8")))))
        assertNull(LiveStats.homePeer(emptyList()))
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
        assertEquals(HomeStats(null, 0, 0, null, null), LiveStats.stats(listOf(peer("laptop"))))
        assertEquals(HomeStats(null, 0, 0, null, null), LiveStats.stats(emptyList()))
    }

    @Test fun meshIpWithoutMask() {
        assertEquals("100.64.0.10", LiveStats.meshIp("100.64.0.10/16"))
        assertEquals("100.64.0.10", LiveStats.meshIp("100.64.0.10"))
    }
}
