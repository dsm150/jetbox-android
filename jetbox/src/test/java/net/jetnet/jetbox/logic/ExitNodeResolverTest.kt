package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порядок выбора выхода — как `resolveExitNode` в iOS: имя из ссылки → единственный → спросить. */
class ExitNodeResolverTest {

    private fun exit(name: String, net: String = "0.0.0.0/0") = RouteInfo(name, net, false)

    @Test
    fun isExitNodeByNetwork() {
        assertTrue(exit("a").isExitNode)
        assertTrue(exit("a", "::/0").isExitNode)
        assertFalse(RouteInfo("lan", "10.0.0.0/8", false).isExitNode)
    }

    @Test
    fun wantedFoundIsSelected() {
        val r = listOf(exit("Frankfurt"), exit("Warsaw"))
        assertEquals(ExitOutcome.Selected("Warsaw"), ExitNodeResolver.resolve("Warsaw", r))
    }

    @Test
    fun wantedMatchIsExactAndCaseSensitive() {
        val r = listOf(exit("Frankfurt"), exit("Warsaw"))
        assertEquals(ExitOutcome.Ambiguous(listOf("Frankfurt", "Warsaw")), ExitNodeResolver.resolve("warsaw", r))
    }

    @Test
    fun wantedMissingWithSingleExitSelectsIt() {
        assertEquals(ExitOutcome.Selected("Only"), ExitNodeResolver.resolve("Other", listOf(exit("Only"))))
        assertEquals(ExitOutcome.Selected("Only"), ExitNodeResolver.resolve(null, listOf(exit("Only"))))
    }

    @Test
    fun noExitsIsNone() {
        assertEquals(ExitOutcome.None, ExitNodeResolver.resolve("x", emptyList()))
        assertEquals(ExitOutcome.None, ExitNodeResolver.resolve(null, listOf(RouteInfo("lan", "10.0.0.0/8", false))))
    }

    @Test
    fun severalExitsWithoutMatchIsAmbiguousSortedLikeIos() {
        val r = listOf(exit("B"), exit("A"), exit("C"))
        assertEquals(ExitOutcome.Ambiguous(listOf("A", "B", "C")), ExitNodeResolver.resolve(null, r))
    }

    @Test
    fun nonExitRoutesAreIgnored() {
        val r = listOf(RouteInfo("lan", "10.0.0.0/8", false), exit("Only"))
        assertEquals(ExitOutcome.Selected("Only"), ExitNodeResolver.resolve(null, r))
        // имя обычного маршрута не считается выходом
        assertEquals(ExitOutcome.Selected("Only"), ExitNodeResolver.resolve("lan", r))
    }

    @Test
    fun ipv6TwinCountsAsOneExit() {
        // Ядро Android склеивает пару в одну строку (client.go): "0.0.0.0/0, ::/0".
        val pair = "0.0.0.0/0, ::/0"
        assertEquals(ExitOutcome.Selected("Warsaw"), ExitNodeResolver.resolve(null, listOf(RouteInfo("Warsaw", pair, false))))
        val r2 = listOf(exit("A", pair), exit("B", pair))
        assertEquals(ExitOutcome.Ambiguous(listOf("A", "B")), ExitNodeResolver.resolve(null, r2))
    }

    @Test
    fun joinedNetworksWithoutDefaultRouteAreNotExit() {
        assertFalse(RouteInfo("lan", "10.0.0.0/8, fd00::/8", false).isExitNode)
        assertEquals(ExitOutcome.None, ExitNodeResolver.resolve(null, listOf(RouteInfo("lan", "10.0.0.0/8, fd00::/8", false))))
    }

    @Test
    fun joinedPairIsExitInAnyOrderAndSpacing() {
        assertTrue(RouteInfo("x", "::/0,0.0.0.0/0", false).isExitNode)
        assertTrue(RouteInfo("x", "10.0.0.0/8, 0.0.0.0/0", false).isExitNode)
    }
}
