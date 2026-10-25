package net.jetnet.jetbox.engine

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState

/**
 * Тестовый движок: потоки — открытые `MutableStateFlow` (тест двигает состояние сам),
 * ответы `register`/`selectRoute`/`deselectRoute`/`needsVpnConsent` настраиваются, вызовы считаются.
 * Сеть — поток [online]; `isOnline()` отдаёт его текущее значение.
 */
class FakeEngine : EngineApi {
    override val state = MutableStateFlow(VpnDisplayState.DISCONNECTED)
    override val peers = MutableStateFlow<List<PeerSnapshot>>(emptyList())
    override val routes = MutableStateFlow<List<RouteInfo>>(emptyList())
    override val connectedSinceMillis = MutableStateFlow<Long?>(null)
    override val lastError = MutableStateFlow<String?>(null)
    override val clientState = MutableStateFlow<Long?>(null)
    override val online = MutableStateFlow(true)

    // --- настраиваемые ответы ---
    var registerResult: Result<Unit> = Result.success(Unit)
    var selectRouteResult: Result<Unit> = Result.success(Unit)
    var deselectRouteResult: Result<Unit> = Result.success(Unit)
    var consentIntent: Intent? = null
    var loginRequired: Boolean = false

    /** Работает ли ядро; `null` — как `state != DISCONNECTED`. */
    var running: Boolean? = null

    /** Если задан — `register` ждёт его завершения (тест держит регистрацию «в полёте»). */
    var registerGate: CompletableDeferred<Unit>? = null

    /** Что сделать при `start()` (например, сразу перевести state в CONNECTED). */
    var onStart: (FakeEngine) -> Unit = {}
    var onStop: (FakeEngine) -> Unit = {}

    // --- счётчики и аргументы ---
    var isOnlineCalls = 0; private set
    var registerCalls = 0; private set
    var lastRegisterUrl: String? = null; private set
    var lastRegisterKey: String? = null; private set
    var needsVpnConsentCalls = 0; private set
    var startCalls = 0; private set
    var stopCalls = 0; private set
    var selectRouteCalls = 0; private set
    val selectedRoutes = mutableListOf<String>()
    val deselectedRoutes = mutableListOf<String>()
    var refreshCalls = 0; private set
    var pollingActive = false; private set
    var setPollingCalls = 0; private set

    override fun isOnline(): Boolean {
        isOnlineCalls++
        return online.value
    }

    override suspend fun register(managementURL: String, setupKey: String): Result<Unit> {
        registerCalls++
        lastRegisterUrl = managementURL
        lastRegisterKey = setupKey
        registerGate?.await()
        return registerResult
    }

    override fun needsVpnConsent(): Intent? {
        needsVpnConsentCalls++
        return consentIntent
    }

    override fun isLoginRequired(): Boolean = loginRequired

    override fun isRunning(): Boolean = running ?: (state.value != VpnDisplayState.DISCONNECTED)

    override fun start() {
        startCalls++
        onStart(this)
    }

    override fun stop() {
        stopCalls++
        onStop(this)
    }

    override suspend fun selectRoute(name: String): Result<Unit> {
        selectRouteCalls++
        selectedRoutes += name
        return selectRouteResult
    }

    override suspend fun deselectRoute(name: String): Result<Unit> {
        deselectedRoutes += name
        return deselectRouteResult
    }

    override fun refresh() {
        refreshCalls++
    }

    override fun setPolling(active: Boolean) {
        setPollingCalls++
        pollingActive = active
    }

    /** Удобство для тестов: перевести состояние, как это сделал бы слушатель ядра. */
    fun emit(newState: VpnDisplayState, nowMillis: Long = 1_000L) {
        connectedSinceMillis.value = if (newState == VpnDisplayState.CONNECTED) {
            connectedSinceMillis.value ?: nowMillis
        } else {
            null
        }
        state.value = newState
    }
}
