package net.jetnet.jetbox.logic

/**
 * Фаза щита — порт `JetBoxShieldPhase` (iOS). Не совпадает с [VpnDisplayState]: «нет интернета» —
 * своя фаза, а экран по ссылке ставит щит в работу на своих шагах. `WORKING_REVERSE` — отключение
 * (анимация идёт в обратную сторону).
 */
enum class ShieldPhase {
    OFF, WORKING, WORKING_REVERSE, ON, OFFLINE;

    val isWorking: Boolean get() = this == WORKING || this == WORKING_REVERSE

    companion object {
        fun of(state: VpnDisplayState, online: Boolean): ShieldPhase = when (state) {
            VpnDisplayState.DISCONNECTED -> OFF
            VpnDisplayState.CONNECTING -> WORKING
            VpnDisplayState.DISCONNECTING -> WORKING_REVERSE
            VpnDisplayState.CONNECTED -> if (online) ON else OFFLINE
        }
    }
}
