package net.jetnet.jetbox.engine

import net.jetnet.jetbox.logic.VpnDisplayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Когда `AndroidEngine` поднимает «Отключено» до «Подключаем…» по коду состояния ядра. */
class CoreStateTest {
    @Test fun startWithoutNetworkLiftsToConnecting() {
        // Авиарежим: ядро запущено, шлёт только NoNetwork/Connecting — onConnecting не приходит.
        assertTrue(CoreState.liftsToConnecting(CoreState.NO_NETWORK, VpnDisplayState.DISCONNECTED, running = true))
        assertTrue(CoreState.liftsToConnecting(CoreState.CONNECTING, VpnDisplayState.DISCONNECTED, running = true))
    }

    @Test fun stoppedCoreDoesNotLift() {
        // Поздний код от остановленного ядра не должен оживлять щит.
        assertFalse(CoreState.liftsToConnecting(CoreState.NO_NETWORK, VpnDisplayState.DISCONNECTED, running = false))
        assertFalse(CoreState.liftsToConnecting(CoreState.CONNECTING, VpnDisplayState.DISCONNECTED, running = false))
    }

    @Test fun onlyFromDisconnected() {
        // Под защитой «нет сети» — это OFFLINE на щите, а не откат в «Подключаем…».
        listOf(VpnDisplayState.CONNECTED, VpnDisplayState.CONNECTING, VpnDisplayState.DISCONNECTING).forEach {
            assertFalse("$it", CoreState.liftsToConnecting(CoreState.NO_NETWORK, it, running = true))
        }
    }

    @Test fun otherCodesDoNotLift() {
        listOf(CoreState.DISCONNECTED, CoreState.CONNECTED, CoreState.DISCONNECTING).forEach {
            assertFalse("$it", CoreState.liftsToConnecting(it, VpnDisplayState.DISCONNECTED, running = true))
        }
    }

    @Test fun serviceStartedLiftsOnlyDisconnected() {
        assertTrue(CoreState.startedLiftsToConnecting(VpnDisplayState.DISCONNECTED))
        assertFalse(CoreState.startedLiftsToConnecting(VpnDisplayState.CONNECTED))
        assertFalse(CoreState.startedLiftsToConnecting(VpnDisplayState.CONNECTING))
        assertFalse(CoreState.startedLiftsToConnecting(VpnDisplayState.DISCONNECTING))
    }
}
