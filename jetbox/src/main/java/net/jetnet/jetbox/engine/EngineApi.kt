package net.jetnet.jetbox.engine

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState

/**
 * Всё, что сценарию и экранам JetBox нужно от туннеля NetBird. Реализация — [AndroidEngine]
 * (поверх `VPNService.MyLocalBinder` апстрима), в тестах — `FakeEngine`.
 *
 * Потоки обновляются из колбэков ядра (не главный поток) — `StateFlow` это допускает.
 */
interface EngineApi {
    val state: StateFlow<VpnDisplayState>
    val peers: StateFlow<List<PeerSnapshot>>
    val routes: StateFlow<List<RouteInfo>>

    /**
     * Момент первого подключения текущего запуска ядра (System.currentTimeMillis), `null`, когда не
     * подключено. Смена сети (CONNECTING посреди работы) его не сбрасывает — как `connectedSince` iOS.
     */
    val connectedSinceMillis: StateFlow<Long?>

    /** Последняя ошибка сервиса (`ServiceStateListener.onError`), сбрасывается при старте. */
    val lastError: StateFlow<String?>

    /**
     * Сырой код состояния ядра (`ConnectionListener.onStateChanged`, константы
     * `io.netbird.gomobile.android.Android.ClientState*`), `null` — ещё не приходил.
     * Нужен для «нет сети» (`ClientStateNoNetwork`) на щите.
     */
    val clientState: StateFlow<Long?>

    /** Есть ли сеть с INTERNET помимо VPN (ConnectivityManager), разовая проверка — для онбординга. */
    fun isOnline(): Boolean

    /**
     * То же, что [isOnline], но потоком: обновляется колбэками ConnectivityManager (без опроса),
     * начальное значение — разовая проверка. Для главного экрана.
     */
    val online: StateFlow<Boolean>

    /**
     * Регистрация устройства по setup-key: сохраняет адрес управления и конфиг ядра.
     * Ошибка — `Result.failure`, текст исключения — сообщение ядра (для `JetBoxFailure.classify`).
     * Отмена корутины пробрасывается как `CancellationException`, но логин в Go не отменяется:
     * пир может зарегистрироваться на сервере уже после отмены.
     */
    suspend fun register(managementURL: String, setupKey: String): Result<Unit>

    /** Интент системного согласия на VPN (`VpnService.prepare`), `null` — согласие уже есть. */
    fun needsVpnConsent(): Intent?

    /** Нужен ли интерактивный вход (сессия SSO истекла): setup-key-пир сюда не попадает, но проверяем. */
    fun isLoginRequired(): Boolean

    /**
     * Поднять туннель без логина в браузере; если сервис ещё не привязан — после привязки.
     * Вызывать после `needsVpnConsent() == null`; без согласия — `lastError = VPN_PERMISSION_REQUIRED`
     * и возврат; из фона на Android 12+ — `lastError = FOREGROUND_START_NOT_ALLOWED`. Звать из
     * видимого окна (касание, ответ системного диалога) или после касания виджета/плитки.
     * Ошибки наружу не бросаются: `lastError` + состояние DISCONNECTED.
     */
    fun start()

    /** Остановить туннель. Ошибки наружу не бросаются (см. [start]). */
    fun stop()

    /**
     * Работает ли ядро прямо сейчас (`binder.isRunning`). Шире, чем `state != DISCONNECTED`:
     * ядро может быть запущено, а слушатель ещё не успел сообщить состояние.
     */
    fun isRunning(): Boolean

    /** Выбрать маршрут (exit-ноду) по имени. */
    suspend fun selectRoute(name: String): Result<Unit>

    /** Снять выбор маршрута (exit-ноды) по имени — «Выход не выбран» (iOS `deselectRoutes`). */
    suspend fun deselectRoute(name: String): Result<Unit>

    /** Разово перечитать peers/routes. */
    fun refresh()

    /** Опрос peers/routes раз в 2 с, пока экран открыт. */
    fun setPolling(active: Boolean)

    companion object {
        /** Значение `lastError`, когда `start()` отказал из-за отсутствия системного согласия на VPN. */
        const val VPN_PERMISSION_REQUIRED = "vpn permission required"

        /**
         * Значение `lastError`, когда `start()` не смог запустить сервис туннеля: Android 12+ не даёт
         * запускать foreground-сервис из фона (окно свёрнуто). Не ошибка ядра — повтор из окна поможет.
         */
        const val FOREGROUND_START_NOT_ALLOWED = "foreground service start not allowed"
    }
}
