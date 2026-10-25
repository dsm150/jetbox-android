package net.jetnet.jetbox.ui

import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.CoreState
import net.jetnet.jetbox.engine.EngineApi
import net.jetnet.jetbox.engine.HomeProbeRunner
import net.jetnet.jetbox.engine.UpdateChecker
import net.jetnet.jetbox.logic.JetBoxPowerAction
import net.jetnet.jetbox.logic.LiveStats
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.PowerAction
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore

/** Строка под щитом — `JetBoxHomeView.statusText(for:online:)` (iOS). */
enum class HomeStatus(@StringRes val text: Int) {
    CONNECTED(R.string.jb_home_connected),
    OFFLINE(R.string.jb_home_offline),
    CONNECTING(R.string.jb_home_connecting),
    DISCONNECTING(R.string.jb_home_disconnecting),
    DISCONNECTED(R.string.jb_home_disconnected);

    companion object {
        fun of(state: VpnDisplayState, online: Boolean): HomeStatus = when (state) {
            VpnDisplayState.CONNECTED -> if (online) CONNECTED else OFFLINE
            VpnDisplayState.CONNECTING -> CONNECTING
            VpnDisplayState.DISCONNECTING -> DISCONNECTING
            VpnDisplayState.DISCONNECTED -> DISCONNECTED
        }
    }
}

/** Подпись под именем дома на панели — `JetBoxHomeDock.subtitle` (iOS). */
enum class DockSubtitle(@StringRes val text: Int) {
    ON(R.string.jb_dock_on),
    CONNECTING(R.string.jb_dock_connecting),
    DISCONNECTING(R.string.jb_home_disconnecting),
    OFF(R.string.jb_dock_off),
    OFFLINE(R.string.jb_dock_offline);

    companion object {
        fun of(phase: ShieldPhase): DockSubtitle = when (phase) {
            ShieldPhase.ON -> ON
            ShieldPhase.WORKING -> CONNECTING
            ShieldPhase.WORKING_REVERSE -> DISCONNECTING
            ShieldPhase.OFF -> OFF
            ShieldPhase.OFFLINE -> OFFLINE
        }
    }
}

/** Как идёт соединение с домом: на панели — короткое слово, в листе — полное. */
enum class RouteKind(@StringRes val dock: Int, @StringRes val details: Int) {
    DIRECT(R.string.jb_dock_direct, R.string.jb_dock_direct),
    RELAYED(R.string.jb_dock_relayed, R.string.jb_details_relayed),
}

/** Всё, что рисует главный экран, панель и лист. Чистые данные — без ресурсов и Compose. */
data class HomeUiState(
    val box: JetBoxBox? = null,
    val state: VpnDisplayState = VpnDisplayState.DISCONNECTED,
    val online: Boolean = true,
    /** Момент подключения (мс эпохи) — от него тикает «в сети». */
    val connectedSinceMillis: Long? = null,
    /** «До дома»: замер DNS, иначе цифра ядра (>0); `null` — «—». */
    val latencyMs: Long? = null,
    /** `null` — JetBox не виден среди пиров («—»). */
    val route: RouteKind? = null,
    val bytesRx: Long? = null,
    val bytesTx: Long? = null,
    /** Имена выходов по алфавиту и выбранный сейчас. */
    val exits: List<String> = emptyList(),
    val selectedExit: String? = null,
    /** Ядро сообщило «нет сети» (`ClientStateNoNetwork`). */
    val coreNoNetwork: Boolean = false,
    /** На сайте есть версия новее: строка на главном экране и в настройках. `null` — ничего не показывать. */
    val update: UpdateInfo? = null,
) {
    val hasBox: Boolean get() = box != null

    /**
     * Туннель включён, но сети нет: ядро стоит в «подключаюсь» с отметкой «нет сети». В iOS это
     * подключённый туннель без интернета — щит OFFLINE, «Нет интернета», нажатие выключает.
     * Здесь так же: иначе щит крутился бы «Подключаем…» весь авиарежим, и выключить было бы нечем.
     */
    val offlineWhileConnecting: Boolean get() = state == VpnDisplayState.CONNECTING && coreNoNetwork

    val phase: ShieldPhase get() = if (offlineWhileConnecting) ShieldPhase.OFFLINE else ShieldPhase.of(state, online)
    val status: HomeStatus get() = if (offlineWhileConnecting) HomeStatus.OFFLINE else HomeStatus.of(state, online)
    val power: PowerAction get() =
        if (offlineWhileConnecting && hasBox) PowerAction.DISCONNECT else JetBoxPowerAction.action(state, hasBox)
    val dockSubtitle: DockSubtitle get() = DockSubtitle.of(phase)
    val isConnected: Boolean get() = state == VpnDisplayState.CONNECTED

    /** Три плитки на панели — только под защитой и без интернета (как iOS). */
    val showsMetrics: Boolean get() = phase == ShieldPhase.ON || phase == ShieldPhase.OFFLINE

    /** Выбор выхода в листе — только если выходов больше одного (решение владельца 24.10.2026). */
    val showsExitChoice: Boolean get() = isConnected && exits.size > 1

    /** Время в сети на момент [nowMillis] (`LiveStats.uptime`), «—» — не подключено. */
    fun uptimeText(nowMillis: Long): String =
        connectedSinceMillis?.let { LiveStats.uptime((nowMillis - it) / 1000) } ?: DASH

    companion object {
        const val DASH = "—"
    }
}

/** Что делать экрану после нажатия на щит. */
sealed interface PressResult {
    data object Done : PressResult

    /** Согласия на VPN нет: показать системный диалог и вернуть ответ в [HomeViewModel.onVpnConsent]. */
    data class NeedsVpnConsent(val intent: Intent) : PressResult
}

/**
 * Главный экран — данные `JetBoxHomeView` + `JetBoxLiveStats` (iOS) поверх [EngineApi] и [JetBoxStore].
 * Без Android-UI: тестируется на JVM с `FakeEngine`.
 *
 * Пока экран виден, UI держит [whileVisible] (`repeatOnLifecycle(STARTED)`): опрос движка и замер
 * «до дома» идут только в это время. Сеть — поток движка (колбэки системы), без опроса.
 *
 * Нажатие на щит отвечает сразу, как iOS (`close()` тут же показывает «Отключаем…`): до первой смены
 * состояния ядра (или [PENDING_TIMEOUT_MS]) показывается ожидаемая фаза, повторные нажатия не идут.
 *
 * Обновления ([updates], только сборка «файл с сайта»): раз в сутки, пока экран виден, и сразу — при
 * открытии настроек ([checkUpdatesNow]).
 *
 * @param scope для тестов; по умолчанию — `viewModelScope`.
 */
class HomeViewModel(
    private val engine: EngineApi,
    private val store: JetBoxStore,
    private val probe: HomeProbeRunner = HomeProbeRunner(),
    scope: CoroutineScope? = null,
    private val updates: UpdateChecker? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope
    private val box = MutableStateFlow(store.currentBox)

    /** Ожидаемое состояние после нажатия, пока ядро ещё в [from]. */
    private data class Pending(val state: VpnDisplayState, val from: VpnDisplayState)
    private val pending = MutableStateFlow<Pending?>(null)
    private var pendingTimeout: Job? = null
    private val _vpnDenied = MutableStateFlow(false)

    /** Пользователь отказал в системном согласии на VPN — показать объяснение. */
    val vpnDenied: StateFlow<Boolean> = _vpnDenied

    private data class Tunnel(
        val state: VpnDisplayState,
        val peers: List<PeerSnapshot>,
        val routes: List<RouteInfo>,
        val since: Long?,
        val clientState: Long?,
    )

    private val tunnel = combine(
        engine.state, engine.peers, engine.routes, engine.connectedSinceMillis, engine.clientState,
    ) { s, p, r, since, cs -> Tunnel(s, p, r, since, cs) }

    private val update: StateFlow<UpdateInfo?> = updates?.available ?: MutableStateFlow(null)

    val ui: StateFlow<HomeUiState> = combine(
        combine(tunnel, box, engine.online, probe.latencyMs, pending) { t, b, on, probed, p -> build(t, b, on, probed, p) },
        update,
    ) { s, u -> s.copy(update = u) }.stateIn(this.scope, SharingStarted.Eagerly, build(
        Tunnel(engine.state.value, engine.peers.value, engine.routes.value,
            engine.connectedSinceMillis.value, engine.clientState.value),
        box.value, engine.online.value, null, null,
    ).copy(update = update.value))

    init {
        // Ядро сдвинулось (или сообщило ошибку) — ожидаемая фаза больше не нужна.
        this.scope.launch {
            engine.state.collect { s -> if (pending.value?.from != s) clearPending() }
        }
        this.scope.launch {
            engine.lastError.collect { if (it != null) clearPending() }
        }
    }

    private fun build(t: Tunnel, b: JetBoxBox?, on: Boolean, probed: Long?, p: Pending?): HomeUiState {
        val peer = LiveStats.homePeer(t.peers)
        val stats = LiveStats.stats(t.peers)
        val exits = t.routes.filter { it.isExitNode }
        // Ожидаемая фаза — только пока ядро в том же состоянии, что при нажатии.
        val state = p?.takeIf { it.from == t.state }?.state ?: t.state
        return HomeUiState(
            box = b,
            state = state,
            // «Нет сети» ядра бывает лишь поверх подключения; под защитой решает система.
            online = on && t.clientState != CLIENT_STATE_NO_NETWORK,
            coreNoNetwork = t.clientState == CLIENT_STATE_NO_NETWORK,
            connectedSinceMillis = if (state == VpnDisplayState.CONNECTED) t.since else null,
            // Как iOS: замер DNS важнее, цифра ядра — запасная (ядро меряет только прямое соединение).
            latencyMs = if (peer == null) null else probed ?: stats.coreLatencyMs,
            route = stats.relayed?.let { if (it) RouteKind.RELAYED else RouteKind.DIRECT },
            bytesRx = peer?.bytesRx,
            bytesTx = peer?.bytesTx,
            exits = exits.map { it.name }.sorted(),
            selectedExit = exits.firstOrNull { it.selected }?.name,
        )
    }

    /** Адрес JetBox для замера: только при CONNECTED и подключённом пире-доме, без маски. */
    private val probeTarget = tunnel.map { t ->
        if (t.state != VpnDisplayState.CONNECTED) return@map null
        LiveStats.homePeer(t.peers)?.takeIf { it.connected }?.let { LiveStats.meshIp(it.ip) }
    }

    /**
     * Перечитать текущий JetBox из хранилища: сценарий по ссылке мог прописать новый (или
     * переименовать прежний), пока главный экран был под ним (iOS читает `JetBoxStore.currentBox`
     * при каждой отрисовке).
     */
    fun reloadBox() {
        box.value = store.currentBox
    }

    /**
     * Держать, пока экран в `STARTED`: опрос движка и «до дома», заодно — проверка обновлений (раз в
     * сутки). Отмена — всё выключает.
     */
    suspend fun whileVisible() = coroutineScope {
        reloadBox()
        if (updates != null) launch { updates.check() }
        engine.setPolling(true)
        try {
            probe.run(probeTarget)
        } finally {
            engine.setPolling(false)
        }
    }

    /** Открыли настройки — спросить сайт сразу, не дожидаясь суток. */
    fun checkUpdatesNow() {
        val u = updates ?: return
        scope.launch { u.check(force = true) }
    }

    /**
     * Нажатие на щит — `JetBoxPowerAction` (iOS); подключение без согласия VPN — через диалог.
     * Пока ждём ответа ядра на прошлое нажатие — ничего не делаем.
     */
    fun press(): PressResult {
        if (pending.value != null) return PressResult.Done
        when (ui.value.power) {
            PowerAction.CONNECT -> {
                val consent = engine.needsVpnConsent()
                if (consent != null) return PressResult.NeedsVpnConsent(consent)
                startTunnel()
            }
            PowerAction.DISCONNECT -> {
                expect(VpnDisplayState.DISCONNECTING)
                engine.stop()
            }
            PowerAction.NONE -> Unit
        }
        return PressResult.Done
    }

    /** Ответ системного диалога VPN. */
    fun onVpnConsent(granted: Boolean) {
        if (granted && engine.needsVpnConsent() == null) startTunnel() else _vpnDenied.value = true
    }

    private fun startTunnel() {
        expect(VpnDisplayState.CONNECTING)
        engine.start()
    }

    /** Показать [state] сразу, до ответа ядра; не дольше [PENDING_TIMEOUT_MS]. */
    private fun expect(state: VpnDisplayState) {
        pending.value = Pending(state, engine.state.value)
        pendingTimeout?.cancel()
        pendingTimeout = scope.launch {
            delay(PENDING_TIMEOUT_MS)
            pending.value = null
        }
    }

    private fun clearPending() {
        if (pending.value == null) return
        pending.value = null
        pendingTimeout?.cancel()
        pendingTimeout = null
    }

    fun dismissVpnDenied() {
        _vpnDenied.value = false
    }

    /** Смена выхода из листа: в ядре и в памяти приложения (как `JetBoxExitSheet`). Перечитывает движок. */
    fun selectExit(name: String) {
        scope.launch {
            engine.selectRoute(name).onSuccess {
                store.rememberExitNode(name)
                box.value = store.currentBox
            }
        }
    }

    /**
     * «Выход не выбран» (iOS `setExitNode(nil)` + `rememberExitNode(nil)`): снять выбранный выход
     * в ядре и забыть его. Ошибка ядра — выход остаётся и не забывается.
     */
    fun clearExit() {
        val current = engine.routes.value.firstOrNull { it.isExitNode && it.selected }?.name
        scope.launch {
            if (current != null && engine.deselectRoute(current).isFailure) return@launch
            store.rememberExitNode(null)
            box.value = store.currentBox
        }
    }

    /** Лист открылся — перечитать маршруты (iOS: `routes.getRoutes()` в `onAppear`). */
    fun refreshRoutes() {
        if (engine.state.value == VpnDisplayState.CONNECTED) engine.refresh()
    }

    companion object {
        /** `ClientStateNoNetwork` ядра (`peer.ClientState`, iota = 4); класс gomobile на JVM не грузится. */
        const val CLIENT_STATE_NO_NETWORK = CoreState.NO_NETWORK
        /** Сколько ждать ответа ядра на нажатие, прежде чем вернуть щит к его состоянию. */
        const val PENDING_TIMEOUT_MS = 5_000L
    }
}
