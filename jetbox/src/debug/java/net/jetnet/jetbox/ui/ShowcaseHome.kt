package net.jetnet.jetbox.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.jetnet.jetbox.BuildConfig
import net.jetnet.jetbox.engine.EngineApi
import net.jetnet.jetbox.engine.HomeProbe
import net.jetnet.jetbox.engine.HomeProbeRunner
import net.jetnet.jetbox.engine.UpdateChecker
import net.jetnet.jetbox.engine.UpdateSource
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore

/** Начальное состояние витрины главного экрана. */
enum class ShowcaseHomeState { OFF, CONNECTING, ON, OFFLINE, NOBOX }

/**
 * Движок витрины (только debug), как `JetBoxShieldDemoView` iOS: туннеля нет, фазы отыгрываются
 * таймером; пир-дом и цифры — примерные (24 мс, напрямую, 48,2 МБ / 6,1 МБ, в сети с 12:34).
 */
class ShowcaseEngine(initial: ShowcaseHomeState) : EngineApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val home = PeerSnapshot(
        ip = "100.64.0.10/16", fqdn = "home-ivanov.netbird.cloud", connected = true, relayed = false,
        latencyMs = 0, bytesRx = 48_200_000, bytesTx = 6_100_000, routes = listOf("0.0.0.0/0"),
    )
    private val connected = initial == ShowcaseHomeState.ON || initial == ShowcaseHomeState.OFFLINE

    override val state = MutableStateFlow(
        when (initial) {
            ShowcaseHomeState.CONNECTING -> VpnDisplayState.CONNECTING
            ShowcaseHomeState.ON, ShowcaseHomeState.OFFLINE -> VpnDisplayState.CONNECTED
            else -> VpnDisplayState.DISCONNECTED
        },
    )
    override val peers = MutableStateFlow(if (connected) listOf(home) else emptyList())
    override val routes = MutableStateFlow(listOf(RouteInfo("home-ivanov", "0.0.0.0/0, ::/0", selected = connected)))
    override val connectedSinceMillis = MutableStateFlow(if (connected) System.currentTimeMillis() - 754_000L else null)
    override val lastError = MutableStateFlow<String?>(null)
    override val clientState = MutableStateFlow<Long?>(null)
    override val online = MutableStateFlow(initial != ShowcaseHomeState.OFFLINE)

    override fun isOnline() = online.value
    override suspend fun register(managementURL: String, setupKey: String) = Result.success(Unit)
    override fun needsVpnConsent(): Intent? = null
    override fun isLoginRequired() = false
    override fun isRunning() = state.value != VpnDisplayState.DISCONNECTED
    override suspend fun selectRoute(name: String) = Result.success(Unit)
    override suspend fun deselectRoute(name: String) = Result.success(Unit)
    override fun refresh() = Unit
    override fun setPolling(active: Boolean) = Unit

    override fun start() {
        state.value = VpnDisplayState.CONNECTING
        scope.launch {
            delay(2_600)
            peers.value = listOf(home)
            connectedSinceMillis.value = System.currentTimeMillis()
            state.value = VpnDisplayState.CONNECTED
        }
    }

    override fun stop() {
        state.value = VpnDisplayState.DISCONNECTING
        scope.launch {
            delay(900)
            peers.value = emptyList()
            connectedSinceMillis.value = null
            state.value = VpnDisplayState.DISCONNECTED
        }
    }
}

/** «Сайт» витрины: на нём версия на единицу новее установленной (без сети). */
val ShowcaseUpdateSource = UpdateSource {
    """{"versionCode":${BuildConfig.APP_VERSION_CODE + 1},"versionName":"1.1","url":"https://box.jet-net.net/android/JetBox.apk"}"""
}

/**
 * Витрина главного экрана: настоящие [JetBoxHomeScreen] и [HomeViewModel] на [ShowcaseEngine];
 * [update] — настоящая проверка обновлений на [ShowcaseUpdateSource] (капсула над панелью).
 */
@Composable
fun ShowcaseHome(initial: ShowcaseHomeState, update: Boolean = false) {
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val store = JetBoxStore(MemoryStore())
                if (initial != ShowcaseHomeState.NOBOX) {
                    store.currentBox = JetBoxBox(
                        managementURL = "https://netbird.jet-net.net",
                        exitNodeName = "home-ivanov",
                        selectedExitNode = null,
                        boxName = "Дом на Озёрной",
                        registeredAtMillis = System.currentTimeMillis(),
                    )
                }
                val updates = if (update) {
                    UpdateChecker(ShowcaseUpdateSource, MemoryStore(), currentCode = BuildConfig.APP_VERSION_CODE, enabled = true)
                } else {
                    null
                }
                HomeViewModel(ShowcaseEngine(initial), store, HomeProbeRunner(HomeProbe { 24L }), updates = updates)
            }
        },
    )
    JetBoxHomeScreen(vm, presenting = false, onOpenSettings = {})
}
