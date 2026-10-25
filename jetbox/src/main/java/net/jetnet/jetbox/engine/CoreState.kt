package net.jetnet.jetbox.engine

import net.jetnet.jetbox.logic.VpnDisplayState

/**
 * Коды `ConnectionListener.onStateChanged` ядра — `peer.ClientState` (Go, `iota`; в gomobile —
 * `io.netbird.gomobile.android.Android.ClientState*`). Значения — формат обмена: ядро только
 * дописывает новые в конец. Свои константы, потому что класс gomobile на JVM в тестах не грузится;
 * совпадение с Go сторожит `UpstreamContractsTest`.
 */
object CoreState {
    const val DISCONNECTED = 0L
    const val CONNECTED = 1L
    const val CONNECTING = 2L
    const val DISCONNECTING = 3L

    /** «Нет сети»: накладывается поверх CONNECTING, пока система не видит сети. */
    const val NO_NETWORK = 4L

    /**
     * Ядро работает и сообщает «подключаюсь» или «нет сети», а для нас туннель всё ещё «Отключено».
     * Так бывает, когда ядро стартовало без сети (авиарежим): старые колбэки (`onConnecting`) оно
     * в этом случае не шлёт, приходит только код состояния. Тогда поднимаем до CONNECTING — иначе
     * щит показал бы «Защита выключена» при запущенном ядре.
     */
    fun liftsToConnecting(code: Long, current: VpnDisplayState, running: Boolean): Boolean =
        (code == CONNECTING || code == NO_NETWORK) && current == VpnDisplayState.DISCONNECTED && running

    /** `onStarted` сервиса: ядро запущено — «Отключено» больше не правда (дальше решат колбэки ядра). */
    fun startedLiftsToConnecting(current: VpnDisplayState): Boolean = current == VpnDisplayState.DISCONNECTED
}
