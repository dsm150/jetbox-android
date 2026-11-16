package net.jetnet.jetbox.engine

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import net.jetnet.jetbox.logic.JetBoxIdentity
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import java.io.File
import java.nio.file.Files

/**
 * Тестовый движок: потоки — открытые `MutableStateFlow` (тест двигает состояние сам),
 * ответы `register`/`selectRoute`/`deselectRoute`/`needsVpnConsent` настраиваются, вызовы считаются.
 * Сеть — поток [online]; `isOnline()` отдаёт его текущее значение.
 *
 * Регистрация с новой личностью идёт через настоящий [IdentityRegistration] и [ProfileFilesSwap]
 * на файлах профиля во временной папке [profileDir]: «ядро» при входе пишет в [configFile] ключ
 * `key=<setup key>`, если конфига нет, — как `NewAuth`. Чья личность на диске — [deviceKey].
 * Запуск ядра на время замены закрыт ([gateClosed]): [start] тогда отклоняется.
 */
class FakeEngine(
    val profileDir: File = Files.createTempDirectory("jetbox-profile").toFile().apply { deleteOnExit() },
) : EngineApi {
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

    val configFile = File(profileDir, "netbird.cfg")
    val stateFile = File(profileDir, "state.json")

    /** Хранилище сценария: откуда [IdentityRegistration] берёт сохранённый отпечаток ключа. */
    var storedFingerprint: () -> String? = { null }

    /** Есть ли конфиг ядра (личность устройства) — ответ [hasDeviceConfig]; `true` — создаёт конфиг. */
    var configExists: Boolean
        get() = swap().hasConfig()
        set(value) {
            if (value) { if (!configFile.exists()) configFile.writeText("key=PREVIOUS") } else configFile.delete()
        }

    /** Чей ключ в конфиге: `key=<setup key>`; `null` — конфига нет. */
    fun deviceKey(): String? = configFile.takeIf { it.exists() }?.readText()

    /** Для тестов: удаление файла профиля «не удаётся». */
    var failDelete: (File) -> Boolean = { false }

    fun swap() = ProfileFilesSwap(listOf(configFile, stateFile)) { f -> if (failDelete(f)) false else f.delete() }

    /** Закрыт ли запуск ядра (идёт замена личности). */
    var gateClosed = false; private set
    var refusedStarts = 0; private set

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
    /** `freshIdentity` каждого вызова [register], по порядку. */
    val registerFreshIdentity = mutableListOf<Boolean>()
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

    override suspend fun register(
        managementURL: String,
        setupKey: String,
        freshIdentity: Boolean,
        onRegistered: () -> Unit,
    ): Result<Unit> {
        registerCalls++
        lastRegisterUrl = managementURL
        lastRegisterKey = setupKey
        registerFreshIdentity += freshIdentity
        if (!freshIdentity) {
            val error = login(setupKey)
            if (error != null) return Result.failure(error)
            onRegistered()
            return Result.success(Unit)
        }
        val gate = object : IdentityRegistration.TunnelGate {
            override fun close() { gateClosed = true }
            override fun open() { gateClosed = false }
            override fun isEngineRunning() = isRunning()
        }
        return IdentityRegistration(swap(), gate).register(
            JetBoxIdentity.fingerprint(setupKey), storedFingerprint(), { login(setupKey) }, onRegistered,
        )
    }

    /** «Ядро»: `NewAuth` создаёт конфиг с новым ключом, если его нет; потом вход ([registerGate], [registerResult]). */
    private suspend fun login(setupKey: String): Exception? {
        if (!configFile.exists()) configFile.writeText("key=$setupKey")
        registerGate?.await()
        return registerResult.exceptionOrNull() as Exception?
    }

    override suspend fun hasDeviceConfig(): Boolean = configExists

    override fun needsVpnConsent(): Intent? {
        needsVpnConsentCalls++
        return consentIntent
    }

    override fun isLoginRequired(): Boolean = loginRequired

    override fun isRunning(): Boolean = running ?: (state.value != VpnDisplayState.DISCONNECTED)

    override fun start() {
        if (gateClosed) { refusedStarts++; return }
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
