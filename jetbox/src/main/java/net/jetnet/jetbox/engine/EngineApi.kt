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
     * Отмена корутины пробрасывается как `CancellationException` (файлы уже возвращены), но логин в
     * Go не отменяется: пир может зарегистрироваться на сервере уже после отмены.
     *
     * [onRegistered] — запись хранилища (`store.remember`): после успешного входа, под замком
     * регистрации, до фиксации файлов профиля, ровно один раз и без отмены. Не вызван — регистрации
     * не было. Его исключение — неудача регистрации (с откатом).
     *
     * [freshIdentity] — регистрация с новой личностью устройства (новый ключ WireGuard),
     * [IdentityRegistration]: запуск ядра закрыт на время замены (работает — отказ
     * [IdentityRegistration.EngineStillRunning]), прежние файлы профиля откладываются; успех —
     * [onRegistered], потом удаление копий; ошибка или отмена — файлы как были; не вернулись —
     * [IdentityRegistration.RestoreFailed]. Иначе ключ сохраняется, а при неудаче откатывается
     * только адрес сервера. Кто решает — `JetBoxIdentity.plan`.
     */
    suspend fun register(
        managementURL: String,
        setupKey: String,
        freshIdentity: Boolean,
        onRegistered: () -> Unit,
    ): Result<Unit>

    /**
     * Есть ли у активного профиля личность устройства: конфиг ядра на месте или отложен оборванной
     * заменой (тогда регистрация сначала вернёт прежний).
     */
    suspend fun hasDeviceConfig(): Boolean

    /**
     * Остались ли у активного профиля следы оборванной замены личности (метка, копии, маркеры):
     * их разберёт следующий [register] — под закрытым запуском ядра и только при остановленном ядре.
     * Сценарий по этому признаку гасит туннель и не поднимает прежний на полуразобранном диске.
     */
    suspend fun hasUnfinishedSwap(): Boolean

    /**
     * «Выйти»: стереть личность устройства на телефоне — конфиг ядра (ключ WireGuard, адрес
     * управления), состояние и остатки замены. Ядро должно быть остановлено: работает — отказ
     * [IdentityRegistration.EngineStillRunning], файлы не тронуты. Сервер о выходе не узнаёт.
     */
    suspend fun forgetDevice(): Result<Unit>

    /** Интент системного согласия на VPN (`VpnService.prepare`), `null` — согласие уже есть. */
    fun needsVpnConsent(): Intent?

    /** Нужен ли интерактивный вход (сессия SSO истекла): setup-key-пир сюда не попадает, но проверяем. */
    fun isLoginRequired(): Boolean

    /**
     * Поднять туннель без логина в браузере; если сервис ещё не привязан — после привязки.
     * Вызывать после `needsVpnConsent() == null`; без согласия — `lastError = VPN_PERMISSION_REQUIRED`
     * и возврат; из фона на Android 12+ — `lastError = FOREGROUND_START_NOT_ALLOWED`. Во время
     * замены личности ([register] с `freshIdentity`) запуск отклоняется молча. Звать из
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
