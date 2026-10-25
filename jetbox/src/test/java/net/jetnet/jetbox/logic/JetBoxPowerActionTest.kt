package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Щит и нижняя кнопка главного экрана зовут одну функцию — проверяем её на всех
 * сочетаниях. Порт `JetBoxPowerActionTests.swift` (iOS).
 */
class JetBoxPowerActionTest {

    @Test
    fun disconnectedWithBoxConnects() {
        assertEquals(PowerAction.CONNECT, JetBoxPowerAction.action(VpnDisplayState.DISCONNECTED, hasBox = true))
    }

    @Test
    fun connectedWithBoxDisconnects() {
        assertEquals(PowerAction.DISCONNECT, JetBoxPowerAction.action(VpnDisplayState.CONNECTED, hasBox = true))
    }

    @Test
    fun transitionsIgnoreTaps() {
        assertEquals(PowerAction.NONE, JetBoxPowerAction.action(VpnDisplayState.CONNECTING, hasBox = true))
        assertEquals(PowerAction.NONE, JetBoxPowerAction.action(VpnDisplayState.DISCONNECTING, hasBox = true))
    }

    @Test
    fun withoutBoxNothingHappens() {
        for (state in VpnDisplayState.values()) {
            assertEquals("$state", PowerAction.NONE, JetBoxPowerAction.action(state, hasBox = false))
        }
    }

    @Test
    fun fullMatrix() {
        val expected = mapOf(
            VpnDisplayState.DISCONNECTED to PowerAction.CONNECT,
            VpnDisplayState.CONNECTING to PowerAction.NONE,
            VpnDisplayState.CONNECTED to PowerAction.DISCONNECT,
            VpnDisplayState.DISCONNECTING to PowerAction.NONE,
        )
        for ((state, action) in expected) {
            assertEquals("$state/hasBox", action, JetBoxPowerAction.action(state, hasBox = true))
            assertEquals("$state/noBox", PowerAction.NONE, JetBoxPowerAction.action(state, hasBox = false))
        }
    }

    @Test
    fun shieldPhaseFollowsTunnel() {
        assertEquals(ShieldPhase.OFF, ShieldPhase.of(VpnDisplayState.DISCONNECTED, online = true))
        assertEquals(ShieldPhase.WORKING, ShieldPhase.of(VpnDisplayState.CONNECTING, online = true))
        assertEquals(ShieldPhase.WORKING_REVERSE, ShieldPhase.of(VpnDisplayState.DISCONNECTING, online = true))
        assertEquals(ShieldPhase.ON, ShieldPhase.of(VpnDisplayState.CONNECTED, online = true))
        assertEquals(ShieldPhase.OFFLINE, ShieldPhase.of(VpnDisplayState.CONNECTED, online = false))
    }
}
