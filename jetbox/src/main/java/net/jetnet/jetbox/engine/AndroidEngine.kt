package net.jetnet.jetbox.engine

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.netbird.client.tool.MDMBridge
import io.netbird.client.tool.ProfileManagerWrapper
import io.netbird.client.tool.RouteChangeListener
import io.netbird.client.tool.ServiceStateListener
import io.netbird.client.tool.VPNService
import io.netbird.gomobile.android.Android
import io.netbird.gomobile.android.ConnectionListener
import io.netbird.gomobile.android.ErrListener
import io.netbird.gomobile.android.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jetnet.jetbox.link.maskSecret
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.widget.JetBoxWidget
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Мост к туннелю NetBird через `VPNService.MyLocalBinder` апстрима (модуль :tool).
 *
 * * Привязка к сервису — сразу при создании, на всё время жизни процесса (application context).
 *   Действия до привязки ставятся в очередь и выполняются в `onServiceConnected`.
 * * Туннель стартует только `runEngine(null, false)`: без URLOpener ядро идёт в RunWithoutLogin
 *   и никогда не открывает браузер NetBird.
 * * Основной `ConnectionListener` ядра один на всех: его перехватывает `MainActivity` (расширенный
 *   режим) и снимает при выходе. Мы его не трогаем: подписываемся наблюдателем
 *   (`addConnectionObserver`, врезка JetBox в апстрим) — он переживает смену основного слушателя.
 *   Начальное состояние: при остановленном ядре — DISCONNECTED; при работающем один раз ставится
 *   пустой основной слушатель, и ядро повторяет последнее состояние наблюдателям.
 */
class AndroidEngine private constructor(context: Context) : EngineApi {

    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VpnDisplayState.DISCONNECTED)
    private val _peers = MutableStateFlow<List<PeerSnapshot>>(emptyList())
    private val _routes = MutableStateFlow<List<RouteInfo>>(emptyList())
    private val _connectedSince = MutableStateFlow<Long?>(null)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _clientState = MutableStateFlow<Long?>(null)
    private val _online = MutableStateFlow(true)

    override val state: StateFlow<VpnDisplayState> = _state
    override val peers: StateFlow<List<PeerSnapshot>> = _peers
    override val routes: StateFlow<List<RouteInfo>> = _routes
    override val connectedSinceMillis: StateFlow<Long?> = _connectedSince
    override val lastError: StateFlow<String?> = _lastError
    override val clientState: StateFlow<Long?> = _clientState
    override val online: StateFlow<Boolean> = _online

    private val registerMutex = Mutex()
    private val lock = Any()
    @Volatile private var binder: VPNService.MyLocalBinder? = null
    private var bindRequested = false                                   // под lock
    private val pending = ArrayList<(VPNService.MyLocalBinder) -> Unit>() // под lock
    private var pollJob: Job? = null                                      // под lock
    private val stateLock = Any() // state + connectedSince обновляются только под ним
    private val refreshRunning = AtomicBoolean(false)
    private val refreshAgain = AtomicBoolean(false)
    /** Ещё не было ни одной привязки к сервису в этом процессе. */
    private val firstBind = AtomicBoolean(true)

    // --- слушатели ядра (вызываются не в главном потоке) ---

    private val connectionListener = object : ConnectionListener {
        override fun onConnecting() = setState(VpnDisplayState.CONNECTING)
        override fun onConnected() {
            setState(VpnDisplayState.CONNECTED)
            refresh()
        }
        override fun onDisconnecting() = setState(VpnDisplayState.DISCONNECTING)
        override fun onDisconnected() {
            setState(VpnDisplayState.DISCONNECTED)
            _clientState.value = null
            clearTunnelData()
        }
        override fun onStateChanged(state: Long) {
            _clientState.value = state
            // Старт без сети: ядро шлёт только код (Connecting/NoNetwork), без onConnecting.
            synchronized(stateLock) {
                if (CoreState.liftsToConnecting(state, _state.value, binder?.isRunning == true)) {
                    setState(VpnDisplayState.CONNECTING)
                }
            }
        }
        override fun onAddressChanged(fqdn: String?, ip: String?) {}
        override fun onPeersListChanged(count: Long) = refresh()
    }

    private val serviceStateListener = object : ServiceStateListener {
        override fun onStarted() {
            _lastError.value = null
            // Ядро запущено: до первого колбэка фазы — «Подключаем…», а не «Отключено». Сервис
            // зовёт onStarted и сразу при подписке на работающее ядро (переподключение к сервису).
            synchronized(stateLock) {
                if (CoreState.startedLiftsToConnecting(_state.value)) setState(VpnDisplayState.CONNECTING)
            }
        }
        override fun onStopped() {
            setState(VpnDisplayState.DISCONNECTED)
            _clientState.value = null
            clearTunnelData()
        }
        override fun onError(msg: String?) {
            Log.w(TAG, "engine error: $msg")
            _lastError.value = msg ?: ""
        }
    }

    private val routeChangeListener = RouteChangeListener { refresh() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as? VPNService.MyLocalBinder ?: return
            Log.d(TAG, "service connected")
            binder = b
            b.addServiceStateListener(serviceStateListener)
            b.addRouteChangeListener(routeChangeListener)
            // Наблюдатель, а не основной слушатель: MainActivity.onStop снимает основной.
            b.addConnectionObserver(connectionListener)
            syncConnectedSince()
            // Первая привязка процесса — до неё основной слушатель мог поставить только апстрим.
            val fresh = firstBind.compareAndSet(true, false)
            if (b.isRunning) {
                // Ядро при установке слушателя повторяет последнее состояние — наблюдателям тоже.
                // Только при первой привязке: после разрыва и новой привязки основной слушатель мог
                // уже поставить MainActivity (расширенный режим) — null снял бы его. Тогда фаза —
                // «Подключаем…» от onStarted (сервис зовёт его при подписке), а если ядро в этом
                // запуске уже подключалось — «Подключено»; дальше поправят колбэки ядра.
                if (fresh) {
                    b.setConnectionStateListener(null)
                } else if (coreConnectedAt() != null) {
                    synchronized(stateLock) {
                        if (_state.value != VpnDisplayState.CONNECTED) setState(VpnDisplayState.CONNECTED)
                    }
                }
            } else {
                setState(VpnDisplayState.DISCONNECTED)
            }
            val queued = synchronized(lock) { ArrayList(pending).also { pending.clear() } }
            queued.forEach { it(b) } // каждое действие уже обёрнуто в guarded()
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Привязка остаётся: система пришлёт onServiceConnected, когда сервис поднимется снова.
            Log.d(TAG, "service disconnected")
            binder = null
            setState(VpnDisplayState.DISCONNECTED)
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "binding died, rebinding")
            binder = null
            synchronized(lock) { bindRequested = false }
            runCatching { app.unbindService(this) }
            ensureBound()
        }
    }

    init {
        ensureBound()
        watchNetwork()
        // Виджет на рабочем столе — по каждой смене состояния (и сразу: при создании движка
        // туннель этого процесса ещё не поднят, сохранённое от прошлого процесса устарело).
        scope.launch { _state.collect { JetBoxWidget.updateAll(app, it) } }
    }

    /**
     * «Есть интернет помимо VPN» — колбэками системы, без опроса. Запрос INTERNET + NOT_VPN видит
     * базовые сети и под поднятым туннелем (как [isOnline]); онлайн — пока жива хоть одна.
     * Сразу после регистрации система присылает onAvailable по каждой подходящей сети.
     * Колбэки приходят в потоке ConnectivityManager, не в главном.
     */
    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val networks = ConcurrentHashMap.newKeySet<Network>()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        _online.value = isOnline()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networks.add(network)
                    _online.value = true
                }
                // Перестала подходить под запрос (пропал INTERNET и т. п.) — тоже onLost.
                override fun onLost(network: Network) {
                    networks.remove(network)
                    _online.value = networks.isNotEmpty()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "network callback failed: ${e.message}")
        }
    }

    private fun ensureBound() {
        synchronized(lock) {
            if (bindRequested) return
            bindRequested = app.bindService(
                Intent(app, VPNService::class.java), connection, Context.BIND_AUTO_CREATE,
            )
            if (!bindRequested) Log.e(TAG, "bindService failed")
        }
    }

    /**
     * Выполнить с привязанным сервисом: сразу или после `onServiceConnected`. Ошибка действия
     * не уходит вызывающему (а при очереди — в колбэк системы): [guarded].
     */
    private fun withBinder(what: String, action: (VPNService.MyLocalBinder) -> Unit) {
        val guardedAction: (VPNService.MyLocalBinder) -> Unit = { b -> guarded(what) { action(b) } }
        // binder присваивается до разбора очереди в onServiceConnected, поэтому действие
        // либо увидит binder, либо попадёт в очередь до её разбора.
        val ready = synchronized(lock) { binder ?: run { pending.add(guardedAction); null } }
        if (ready != null) guardedAction(ready) else ensureBound()
    }

    /** Ошибка действия: в `lastError`, состояние DISCONNECTED; наружу не бросаем. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
            _lastError.value = e.message ?: e.toString()
            setState(VpnDisplayState.DISCONNECTED)
        }
    }

    /**
     * Состояние и время подключения меняются под одним замком, в таком порядке, чтобы подписчик
     * не видел CONNECTED без времени (время ставим до состояния) и не-CONNECTED с чужим временем
     * (состояние ставим до сброса).
     */
    private fun setState(new: VpnDisplayState) {
        synchronized(stateLock) {
            val core = coreConnectedAt()
            when (new) {
                VpnDisplayState.CONNECTED -> {
                    // Время — от первого подключения запуска ядра (врезка в EngineRunner); без него —
                    // прежнее или сейчас. Повтор onConnected время не сбрасывает.
                    _connectedSince.value = core ?: _connectedSince.value ?: System.currentTimeMillis()
                    _state.value = new
                }
                VpnDisplayState.CONNECTING -> {
                    // Смена сети посреди работы ядра: время запуска сохраняется.
                    _state.value = new
                    _connectedSince.value = core
                }
                else -> {
                    _state.value = new
                    _connectedSince.value = null
                }
            }
        }
    }

    /** Момент подключения из ядра; `null` — ядро не подключалось в этом запуске или не привязано. */
    private fun coreConnectedAt(): Long? = binder?.connectedAtMillis()?.takeIf { it > 0 }

    /** Под защитой — подтянуть время подключения из ядра (привязка к уже работающему туннелю). */
    private fun syncConnectedSince() {
        synchronized(stateLock) {
            if (_state.value != VpnDisplayState.CONNECTED) return
            coreConnectedAt()?.let { if (_connectedSince.value != it) _connectedSince.value = it }
        }
    }

    /** Пиры и маршруты прошлого туннеля не должны жить после его остановки. */
    private fun clearTunnelData() {
        _peers.value = emptyList()
        _routes.value = emptyList()
    }

    // --- EngineApi ---

    override fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    override suspend fun register(managementURL: String, setupKey: String): Result<Unit> =
        registerMutex.withLock {
            withContext(Dispatchers.IO) {
                Log.i(TAG, "register: url=$managementURL key=${maskSecret(setupKey)}")
                var previousURL: String? = null
                var ok = false
                try {
                    // Порядок — как FirstInstallFragment.applyServer() апстрима.
                    val configPath = ProfileManagerWrapper(app).activeConfigPath
                    // Прежний адрес — только из существующего конфига: иначе ядро отдаст адрес
                    // по умолчанию, и «откат» записал бы его вместо пустоты.
                    if (File(configPath).exists()) {
                        previousURL = runCatching {
                            MDMBridge.openPreferences(app, configPath).managementURL
                        }.getOrNull()
                    }
                    MDMBridge.openPreferences(app, configPath).apply {
                        setManagementURL(managementURL)
                        commit()
                    }
                    val auth = MDMBridge.newAuth(app, configPath, managementURL)
                    val error = suspendCancellableCoroutine<Exception?> { cont ->
                        auth.loginWithSetupKeyAndSaveConfig(object : ErrListener {
                            override fun onSuccess() {
                                if (cont.isActive) cont.resume(null)
                            }
                            override fun onError(e: Exception?) {
                                if (cont.isActive) cont.resume(e ?: Exception(""))
                            }
                        }, setupKey, Build.MODEL) // модель телефона — как апстрим applyServer; видна в панели
                    }
                    if (error != null) {
                        Log.w(TAG, "register failed: ${error.message}")
                        return@withContext Result.failure(asCoreError(error))
                    }
                    // Флаг апстримового PreferenceUI: MainActivity не покажет экран первого запуска.
                    app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(FIRST_LAUNCH_KEY, false).commit()
                    Log.i(TAG, "register ok")
                    ok = true
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "register failed: ${e.message}")
                    Result.failure(asCoreError(e))
                } finally {
                    // Неудача (в том числе отмена) не должна оставлять в конфиге адрес нового
                    // сервера: прежний JetBox после этого поднимется уже не к своему management.
                    if (!ok && previousURL != null && previousURL != managementURL) {
                        try {
                            MDMBridge.openPreferences(app, ProfileManagerWrapper(app).activeConfigPath).apply {
                                setManagementURL(previousURL)
                                commit()
                            }
                            Log.i(TAG, "register: адрес сервера возвращён на прежний")
                        } catch (e: Exception) {
                            Log.w(TAG, "register: не удалось вернуть прежний адрес сервера: ${e.message}")
                        }
                    }
                }
            }
        }

    override fun needsVpnConsent(): Intent? = VpnService.prepare(app)

    override fun isLoginRequired(): Boolean = binder?.isLoginRequired == true

    override fun isRunning(): Boolean = binder?.isRunning == true

    override fun start() {
        guarded("start") {
            if (VpnService.prepare(app) != null) {
                _lastError.value = EngineApi.VPN_PERMISSION_REQUIRED
                return
            }
            _lastError.value = null
            clearTunnelData() // пиры и маршруты прошлого JetBox
            // Как плитка апстрима: сервис в foreground до runEngine.
            try {
                app.startForegroundService(
                    Intent(app, VPNService::class.java).setAction(VPNService.INTENT_ACTION_START),
                )
            } catch (e: IllegalStateException) {
                // Android 12+: из фона (окно свёрнуто) запуск FGS запрещён. Это не поломка ядра —
                // своя метка, сценарий покажет «Не удалось включить защиту» с повтором.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "start: foreground service not allowed from background")
                    _lastError.value = EngineApi.FOREGROUND_START_NOT_ALLOWED
                    setState(VpnDisplayState.DISCONNECTED)
                    return
                }
                throw e
            }
            withBinder("start/runEngine") { b ->
                if (b.isRunning) {
                    Log.d(TAG, "start: engine already running")
                } else {
                    Log.i(TAG, "start: runEngine")
                    b.runEngine(null, false) // без URLOpener → RunWithoutLogin, браузер не открывается
                }
            }
        }
    }

    override fun stop() {
        guarded("stop") {
            withBinder("stop/stopEngine") { b ->
                Log.i(TAG, "stop: stopEngine")
                b.stopEngine()
            }
        }
    }

    override suspend fun selectRoute(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val b = binder ?: return@withContext Result.failure(IllegalStateException("VPN service is not bound"))
        try {
            b.selectRoute(name)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "selectRoute($name) failed: ${e.message}")
            Result.failure(asCoreError(e))
        } finally {
            refresh()
        }
    }

    override suspend fun deselectRoute(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val b = binder ?: return@withContext Result.failure(IllegalStateException("VPN service is not bound"))
        try {
            b.deselectRoute(name)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "deselectRoute($name) failed: ${e.message}")
            Result.failure(asCoreError(e))
        } finally {
            refresh()
        }
    }

    /**
     * Вызовы сливаются: одно чтение за раз, а запрос во время чтения даёт ровно один повтор.
     * Так устаревший снимок не перезапишет новый.
     */
    override fun refresh() {
        refreshAgain.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (refreshAgain.getAndSet(false)) {
                    val b = binder ?: break
                    runCatchingLogged("refresh") {
                        if (b.isRunning) {
                            syncConnectedSince()
                            _peers.value = readPeers(b)
                            _routes.value = readRoutes(b)
                        } else {
                            // Слушатель мог не успеть: остановленное ядро — не «подключено».
                            clearTunnelData()
                            if (_state.value != VpnDisplayState.DISCONNECTED) setState(VpnDisplayState.DISCONNECTED)
                        }
                    }
                }
            } finally {
                refreshRunning.set(false)
                if (refreshAgain.get()) refresh() // запросили между последней проверкой и сбросом флага
            }
        }
    }

    override fun setPolling(active: Boolean) {
        synchronized(lock) {
            if (!active) {
                pollJob?.cancel()
                pollJob = null
                return
            }
            if (pollJob?.isActive == true) return
            pollJob = scope.launch {
                while (isActive) {
                    refresh()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    // --- чтение ядра (только не в главном потоке) ---

    private fun readPeers(b: VPNService.MyLocalBinder): List<PeerSnapshot> {
        val arr = b.peersInfo() ?: return emptyList()
        return (0 until arr.size()).mapNotNull { i -> arr.get(i)?.let(::toSnapshot) }
    }

    private fun toSnapshot(p: PeerInfo): PeerSnapshot {
        val routes = p.peerRoutes?.let { r ->
            (0 until r.size()).mapNotNull { i -> runCatching { r.get(i) }.getOrNull() }
        } ?: emptyList()
        return PeerSnapshot(
            ip = p.ip ?: "",
            fqdn = p.fqdn ?: "",
            connected = p.connStatus == Android.ConnStatusConnected,
            relayed = p.relayed,
            latencyMs = p.latencyMs,
            bytesRx = p.bytesRx,
            bytesTx = p.bytesTx,
            routes = routes,
        )
    }

    private fun readRoutes(b: VPNService.MyLocalBinder): List<RouteInfo> {
        val arr = b.networks() ?: return emptyList()
        return (0 until arr.size()).mapNotNull { i ->
            arr.get(i)?.let { n -> RouteInfo(n.name ?: "", n.network ?: "", n.isSelected) }
        }
    }

    private inline fun runCatchingLogged(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "JetBoxEngine"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val UI_PREFS = "ui_prefs"
        private const val FIRST_LAUNCH_KEY = "first_launch"

        @Volatile private var instance: AndroidEngine? = null

        fun get(context: Context): AndroidEngine =
            instance ?: synchronized(this) {
                instance ?: AndroidEngine(context).also { instance = it }
            }

        /** Исключение, текст которого — сообщение ядра (пустое сообщение заменяем описанием). */
        private fun asCoreError(e: Exception): Exception =
            if (e.message.isNullOrEmpty()) Exception(e.toString(), e) else e
    }
}
