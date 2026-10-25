package net.jetnet.jetbox.logic

/** Что делает нажатие на щит / нижнюю кнопку. */
enum class PowerAction { CONNECT, DISCONNECT, NONE }

/**
 * Порт `JetBoxPowerAction.action(for:hasBox:)` (iOS). Щит и нижняя кнопка зовут одну
 * функцию, чтобы два элемента не могли разойтись.
 */
object JetBoxPowerAction {
    fun action(state: VpnDisplayState, hasBox: Boolean): PowerAction {
        if (!hasBox) return PowerAction.NONE
        return when (state) {
            VpnDisplayState.DISCONNECTED -> PowerAction.CONNECT
            VpnDisplayState.CONNECTED -> PowerAction.DISCONNECT
            VpnDisplayState.CONNECTING, VpnDisplayState.DISCONNECTING -> PowerAction.NONE
        }
    }
}
