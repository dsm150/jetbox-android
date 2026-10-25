package net.jetnet.jetbox.ui

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.FakeEngine
import net.jetnet.jetbox.engine.HomeProbe
import net.jetnet.jetbox.engine.HomeProbeRunner
import net.jetnet.jetbox.engine.UpdateChecker
import net.jetnet.jetbox.engine.UpdateSource
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.logic.PeerSnapshot
import net.jetnet.jetbox.logic.PowerAction
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Главный экран — данные `JetBoxHomeView`/`JetBoxHomeDock`/`JetBoxConnectionSheet` (iOS) на FakeEngine.
 * Время виртуальное; «до дома» — подменный замер.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val box = JetBoxBox("https://netbird.jet-net.net", "home-ivanov", null, "Дом на Озёрной", 1L)

    private fun peer(
        relayed: Boolean = false,
        latencyMs: Long = 0,
        connected: Boolean = true,
        ip: String = "100.64.0.10/16",
    ) = PeerSnapshot(ip, "home.netbird.cloud", connected, relayed, latencyMs, 48_200_000, 6_100_000, listOf("0.0.0.0/0"))

    private class Probe(var answer: Long?) : HomeProbe {
        val asked = mutableListOf<String>()
        override suspend fun measure(meshIp: String): Long? {
            asked += meshIp
            return answer
        }
    }

    private class Env(val engine: FakeEngine, val store: JetBoxStore, val probe: Probe, val vm: HomeViewModel)

    private fun TestScope.env(withBox: Boolean = true, probeAnswer: Long? = 24, setup: FakeEngine.() -> Unit = {}): Env {
        val engine = FakeEngine().apply(setup)
        val store = JetBoxStore(MemoryStore()).apply { if (withBox) currentBox = box }
        val probe = Probe(probeAnswer)
        val vm = HomeViewModel(engine, store, HomeProbeRunner(probe), backgroundScope as CoroutineScope)
        runCurrent()
        return Env(engine, store, probe, vm)
    }

    private fun FakeEngine.connect(vararg p: PeerSnapshot) {
        peers.value = p.toList()
        emit(VpnDisplayState.CONNECTED, nowMillis = 1_000L)
    }

    // --- фаза, статус, подписи панели ---

    @Test fun offWithBox() = runTest {
        val ui = env().vm.ui.value
        assertEquals(ShieldPhase.OFF, ui.phase)
        assertEquals(R.string.jb_home_disconnected, ui.status.text)
        assertEquals(R.string.jb_dock_off, ui.dockSubtitle.text)
        assertEquals(PowerAction.CONNECT, ui.power)
        assertFalse(ui.showsMetrics)
        assertEquals("Дом на Озёрной", ui.box?.displayName)
    }

    @Test fun connecting() = runTest {
        val e = env()
        e.engine.emit(VpnDisplayState.CONNECTING)
        runCurrent()
        val ui = e.vm.ui.value
        assertEquals(ShieldPhase.WORKING, ui.phase)
        assertEquals(R.string.jb_home_connecting, ui.status.text)
        assertEquals(R.string.jb_dock_connecting, ui.dockSubtitle.text)
        assertEquals(PowerAction.NONE, ui.power)
        assertFalse(ui.showsMetrics)
    }

    @Test fun disconnecting() = runTest {
        val e = env()
        e.engine.emit(VpnDisplayState.DISCONNECTING)
        runCurrent()
        val ui = e.vm.ui.value
        assertEquals(ShieldPhase.WORKING_REVERSE, ui.phase)
        assertEquals(R.string.jb_home_disconnecting, ui.status.text)
        assertEquals(R.string.jb_home_disconnecting, ui.dockSubtitle.text)
    }

    @Test fun protectedState() = runTest {
        val e = env()
        e.engine.connect(peer())
        runCurrent()
        val ui = e.vm.ui.value
        assertEquals(ShieldPhase.ON, ui.phase)
        assertEquals(R.string.jb_home_connected, ui.status.text)
        assertEquals(R.string.jb_dock_on, ui.dockSubtitle.text)
        assertEquals(PowerAction.DISCONNECT, ui.power)
        assertTrue(ui.showsMetrics)
        assertEquals(48_200_000L, ui.bytesRx)
        assertEquals(6_100_000L, ui.bytesTx)
    }

    @Test fun noInternetFromSystemFlow() = runTest {
        val e = env()
        e.engine.connect(peer())
        runCurrent()
        assertEquals(ShieldPhase.ON, e.vm.ui.value.phase)
        e.engine.online.value = false
        runCurrent()
        val ui = e.vm.ui.value
        assertEquals(ShieldPhase.OFFLINE, ui.phase)
        assertEquals(R.string.jb_home_offline, ui.status.text)
        assertEquals(R.string.jb_dock_offline, ui.dockSubtitle.text)
        assertTrue(ui.showsMetrics)
        e.engine.online.value = true
        runCurrent()
        assertEquals(ShieldPhase.ON, e.vm.ui.value.phase)
    }

    @Test fun noInternetAtStart() = runTest {
        val e = env { online.value = false }
        e.engine.connect(peer())
        runCurrent()
        assertEquals(ShieldPhase.OFFLINE, e.vm.ui.value.phase)
    }

    @Test fun coreNoNetworkIsOffline() = runTest {
        val e = env()
        e.engine.connect(peer())
        e.engine.clientState.value = HomeViewModel.CLIENT_STATE_NO_NETWORK
        runCurrent()
        assertEquals(ShieldPhase.OFFLINE, e.vm.ui.value.phase)
        assertEquals(R.string.jb_home_offline, e.vm.ui.value.status.text)
        e.engine.clientState.value = 3L
        runCurrent()
        assertEquals(ShieldPhase.ON, e.vm.ui.value.phase)
    }

    /**
     * Ядро запущено без сети (авиарежим): CONNECTING + «нет сети». Как iOS (туннель есть, интернета
     * нет): щит OFFLINE, «Нет интернета», нажатие выключает.
     */
    @Test fun connectingWithoutNetworkIsOfflineAndCanBeTurnedOff() = runTest {
        val e = env()
        e.engine.emit(VpnDisplayState.CONNECTING)
        e.engine.clientState.value = HomeViewModel.CLIENT_STATE_NO_NETWORK
        runCurrent()
        val ui = e.vm.ui.value
        assertTrue(ui.offlineWhileConnecting)
        assertEquals(ShieldPhase.OFFLINE, ui.phase)
        assertEquals(R.string.jb_home_offline, ui.status.text)
        assertEquals(R.string.jb_dock_offline, ui.dockSubtitle.text)
        assertEquals(PowerAction.DISCONNECT, ui.power)
        assertTrue(ui.showsMetrics)

        assertEquals(PressResult.Done, e.vm.press())
        runCurrent()
        assertEquals(1, e.engine.stopCalls)
        assertEquals(0, e.engine.startCalls)
        // До ответа ядра — «Отключаем…», а не снова «Нет интернета».
        assertEquals(ShieldPhase.WORKING_REVERSE, e.vm.ui.value.phase)
        assertEquals(R.string.jb_home_disconnecting, e.vm.ui.value.status.text)
    }

    /** Сеть вернулась: ядро снимает «нет сети» — обычное «Подключаем…», нажатие снова ничего не делает. */
    @Test fun networkBackWhileConnectingIsWorkingAgain() = runTest {
        val e = env()
        e.engine.emit(VpnDisplayState.CONNECTING)
        e.engine.clientState.value = HomeViewModel.CLIENT_STATE_NO_NETWORK
        runCurrent()
        e.engine.clientState.value = 2L // ClientStateConnecting
        runCurrent()
        val ui = e.vm.ui.value
        assertFalse(ui.offlineWhileConnecting)
        assertEquals(ShieldPhase.WORKING, ui.phase)
        assertEquals(R.string.jb_home_connecting, ui.status.text)
        assertEquals(PowerAction.NONE, ui.power)
    }

    /** «Нет сети» без JetBox на телефоне ничего не включает и не выключает. */
    @Test fun offlineWhileConnectingWithoutBoxDoesNothing() = runTest {
        val e = env(withBox = false)
        e.engine.emit(VpnDisplayState.CONNECTING)
        e.engine.clientState.value = HomeViewModel.CLIENT_STATE_NO_NETWORK
        runCurrent()
        assertEquals(PowerAction.NONE, e.vm.ui.value.power)
    }

    /** Сеть — поток движка: ни разовых проверок по таймеру, ни подписки после смерти модели. */
    @Test fun onlineIsNotPolled() = runTest {
        val engine = FakeEngine()
        val store = JetBoxStore(MemoryStore()).apply { currentBox = box }
        val vmScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val vm = HomeViewModel(engine, store, HomeProbeRunner(Probe(24)), vmScope)
        val job = launch { vm.whileVisible() }
        runCurrent()
        assertTrue(engine.online.subscriptionCount.value > 0)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, engine.isOnlineCalls)
        job.cancel()
        vmScope.cancel()
        runCurrent()
        assertEquals(0, engine.online.subscriptionCount.value)
    }

    @Test fun noBox() = runTest {
        val e = env(withBox = false)
        val ui = e.vm.ui.value
        assertFalse(ui.hasBox)
        assertEquals(PowerAction.NONE, ui.power)
        assertEquals(PressResult.Done, e.vm.press())
        assertEquals(0, e.engine.startCalls)
        assertEquals(0, e.engine.needsVpnConsentCalls)
    }

    // --- «до дома», соединение, время в сети ---

    @Test fun latencyFromDnsProbe() = runTest {
        val e = env(probeAnswer = 24) { }
        e.engine.connect(peer(latencyMs = 80))
        val job = launch { e.vm.whileVisible() }
        runCurrent()
        assertEquals(24L, e.vm.ui.value.latencyMs)
        assertEquals(listOf("100.64.0.10"), e.probe.asked)
        job.cancel()
    }

    @Test fun latencyFallsBackToCore() = runTest {
        val e = env(probeAnswer = null)
        e.engine.connect(peer(latencyMs = 31))
        val job = launch { e.vm.whileVisible() }
        runCurrent()
        assertEquals(31L, e.vm.ui.value.latencyMs)
        job.cancel()
    }

    @Test fun latencyDashWhenCoreZero() = runTest {
        val e = env(probeAnswer = null)
        e.engine.connect(peer(latencyMs = 0))
        val job = launch { e.vm.whileVisible() }
        runCurrent()
        assertNull(e.vm.ui.value.latencyMs)
        job.cancel()
    }

    @Test fun probeOnlyWhenConnected() = runTest {
        val e = env()
        val job = launch { e.vm.whileVisible() }
        e.engine.peers.value = listOf(peer())
        e.engine.emit(VpnDisplayState.CONNECTING)
        advanceTimeBy(30_000); runCurrent()
        assertTrue(e.probe.asked.isEmpty())
        e.engine.emit(VpnDisplayState.CONNECTED)
        runCurrent()
        assertEquals(1, e.probe.asked.size)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, e.probe.asked.size)
        e.engine.emit(VpnDisplayState.DISCONNECTED)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, e.probe.asked.size)
        assertNull(e.vm.ui.value.latencyMs)
        job.cancel()
    }

    @Test fun probeStopsWhenScreenStops() = runTest {
        val e = env()
        e.engine.connect(peer())
        val job = launch { e.vm.whileVisible() }
        runCurrent()
        assertTrue(e.engine.pollingActive)
        job.cancel()
        runCurrent()
        assertFalse(e.engine.pollingActive)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, e.probe.asked.size)
    }

    @Test fun routeDirectOrRelayed() = runTest {
        val e = env()
        e.engine.connect(peer(relayed = false))
        runCurrent()
        assertEquals(RouteKind.DIRECT, e.vm.ui.value.route)
        assertEquals(R.string.jb_dock_direct, e.vm.ui.value.route!!.dock)
        e.engine.peers.value = listOf(peer(relayed = true))
        runCurrent()
        val route = e.vm.ui.value.route!!
        assertEquals(RouteKind.RELAYED, route)
        assertEquals(R.string.jb_dock_relayed, route.dock)
        assertEquals(R.string.jb_details_relayed, route.details)
    }

    @Test fun routeDashWithoutHomePeer() = runTest {
        val e = env()
        e.engine.emit(VpnDisplayState.CONNECTED)
        runCurrent()
        assertNull(e.vm.ui.value.route)
        assertNull(e.vm.ui.value.latencyMs)
        assertNull(e.vm.ui.value.bytesRx)
    }

    @Test fun uptimeLikeIos() = runTest {
        val e = env()
        e.engine.connect(peer())
        runCurrent()
        val ui = e.vm.ui.value
        assertEquals("12:34", ui.uptimeText(1_000L + 754_000L))
        assertEquals("1:04:05", ui.uptimeText(1_000L + 3_845_000L))
        assertEquals(HomeUiState.DASH, HomeUiState().uptimeText(5_000L))
    }

    // --- выбор выхода ---

    @Test fun exitChoiceOnlyWithMoreThanOne() = runTest {
        val e = env()
        e.engine.routes.value = listOf(RouteInfo("home-ivanov", "0.0.0.0/0, ::/0", true), RouteInfo("lan", "192.168.1.0/24", false))
        e.engine.connect(peer())
        runCurrent()
        assertFalse(e.vm.ui.value.showsExitChoice)
        e.engine.routes.value = e.engine.routes.value + RouteInfo("dacha", "0.0.0.0/0", false)
        runCurrent()
        val ui = e.vm.ui.value
        assertTrue(ui.showsExitChoice)
        assertEquals(listOf("dacha", "home-ivanov"), ui.exits)
        assertEquals("home-ivanov", ui.selectedExit)
        e.engine.emit(VpnDisplayState.DISCONNECTED)
        runCurrent()
        assertFalse(e.vm.ui.value.showsExitChoice)
    }

    @Test fun selectExitRemembers() = runTest {
        val e = env()
        e.vm.selectExit("dacha")
        runCurrent()
        assertEquals(listOf("dacha"), e.engine.selectedRoutes)
        assertEquals("dacha", e.store.currentBox?.selectedExitNode)
        assertEquals("dacha", e.vm.ui.value.box?.selectedExitNode)
        // Перечитывает сам движок (finally в selectRoute), модель второй раз не дёргает.
        assertEquals(0, e.engine.refreshCalls)
    }

    @Test fun selectExitFailureKeepsBox() = runTest {
        val e = env { selectRouteResult = Result.failure(Exception("route not found")) }
        val before = e.vm.ui.value.box
        e.vm.selectExit("dacha")
        runCurrent()
        assertEquals(listOf("dacha"), e.engine.selectedRoutes)
        assertNull(e.store.currentBox?.selectedExitNode)
        assertEquals(before, e.vm.ui.value.box)
    }

    @Test fun clearExitDeselectsAndForgets() = runTest {
        val e = env()
        e.store.rememberExitNode("home-ivanov")
        e.engine.routes.value = listOf(
            RouteInfo("dacha", "0.0.0.0/0", false),
            RouteInfo("home-ivanov", "0.0.0.0/0, ::/0", true),
            RouteInfo("lan", "192.168.1.0/24", true),
        )
        runCurrent()
        e.vm.clearExit()
        runCurrent()
        assertEquals(listOf("home-ivanov"), e.engine.deselectedRoutes)
        assertNull(e.store.currentBox?.selectedExitNode)
        assertNull(e.vm.ui.value.box?.selectedExitNode)
    }

    @Test fun clearExitFailureKeepsRemembered() = runTest {
        val e = env { deselectRouteResult = Result.failure(Exception("not selected")) }
        e.store.rememberExitNode("home-ivanov")
        e.engine.routes.value = listOf(RouteInfo("home-ivanov", "0.0.0.0/0", true))
        runCurrent()
        e.vm.clearExit()
        runCurrent()
        assertEquals(listOf("home-ivanov"), e.engine.deselectedRoutes)
        assertEquals("home-ivanov", e.store.currentBox?.selectedExitNode)
    }

    // --- щит ---

    @Test fun pressConnectStarts() = runTest {
        val e = env()
        assertEquals(PressResult.Done, e.vm.press())
        assertEquals(1, e.engine.startCalls)
    }

    @Test fun pressConnectAsksConsentFirst() = runTest {
        val intent = Intent()
        val e = env { consentIntent = intent }
        val r = e.vm.press()
        assertTrue(r is PressResult.NeedsVpnConsent)
        assertEquals(0, e.engine.startCalls)
        e.engine.consentIntent = null
        e.vm.onVpnConsent(true)
        assertEquals(1, e.engine.startCalls)
        assertFalse(e.vm.vpnDenied.value)
    }

    @Test fun consentDenied() = runTest {
        val e = env { consentIntent = Intent() }
        e.vm.press()
        e.vm.onVpnConsent(false)
        assertEquals(0, e.engine.startCalls)
        assertTrue(e.vm.vpnDenied.value)
        e.vm.dismissVpnDenied()
        assertFalse(e.vm.vpnDenied.value)
    }

    @Test fun pressDisconnectStops() = runTest {
        val e = env()
        e.engine.connect(peer())
        runCurrent()
        e.vm.press()
        assertEquals(1, e.engine.stopCalls)
        assertEquals(0, e.engine.startCalls)
    }

    @Test fun pressWhileConnectingDoesNothing() = runTest {
        val e = env()
        e.engine.emit(VpnDisplayState.CONNECTING)
        runCurrent()
        e.vm.press()
        assertEquals(0, e.engine.startCalls)
        assertEquals(0, e.engine.stopCalls)
    }

    // --- мгновенный отклик щита ---

    @Test fun disconnectShowsDisconnectingAtOnce() = runTest {
        val e = env()
        e.engine.connect(peer())
        runCurrent()
        e.vm.press()
        runCurrent()
        assertEquals(1, e.engine.stopCalls)
        assertEquals(VpnDisplayState.DISCONNECTING, e.vm.ui.value.state)
        assertEquals(R.string.jb_home_disconnecting, e.vm.ui.value.status.text)
        assertFalse(e.vm.ui.value.showsMetrics)
        e.vm.press() // повтор, пока ядро не ответило, — мимо
        assertEquals(1, e.engine.stopCalls)
        e.engine.emit(VpnDisplayState.DISCONNECTED)
        runCurrent()
        assertEquals(VpnDisplayState.DISCONNECTED, e.vm.ui.value.state)
        assertEquals(PowerAction.CONNECT, e.vm.ui.value.power)
    }

    @Test fun connectBlocksRepeatUntilCoreMoves() = runTest {
        val e = env()
        e.vm.press()
        runCurrent()
        assertEquals(VpnDisplayState.CONNECTING, e.vm.ui.value.state)
        assertEquals(PowerAction.NONE, e.vm.ui.value.power)
        e.vm.press()
        assertEquals(1, e.engine.startCalls)
        e.engine.emit(VpnDisplayState.CONNECTING)
        runCurrent()
        e.engine.emit(VpnDisplayState.CONNECTED)
        runCurrent()
        assertEquals(VpnDisplayState.CONNECTED, e.vm.ui.value.state)
    }

    @Test fun pendingPhaseExpires() = runTest {
        val e = env()
        e.vm.press()
        runCurrent()
        assertEquals(VpnDisplayState.CONNECTING, e.vm.ui.value.state)
        advanceTimeBy(HomeViewModel.PENDING_TIMEOUT_MS + 1); runCurrent()
        assertEquals(VpnDisplayState.DISCONNECTED, e.vm.ui.value.state)
        e.vm.press()
        assertEquals(2, e.engine.startCalls)
    }

    @Test fun pendingClearedByEngineError() = runTest {
        val e = env()
        e.vm.press()
        runCurrent()
        e.engine.lastError.value = "boom"
        runCurrent()
        assertEquals(VpnDisplayState.DISCONNECTED, e.vm.ui.value.state)
        assertEquals(PowerAction.CONNECT, e.vm.ui.value.power)
    }

    @Test fun pollingFollowsVisibility() = runTest {
        val e = env()
        val job = launch { e.vm.whileVisible() }
        runCurrent()
        assertTrue(e.engine.pollingActive)
        job.cancel()
        runCurrent()
        assertFalse(e.engine.pollingActive)
    }

    // --- «Доступна новая версия»: строка на главном и в настройках ---

    private val apk = "https://box.jet-net.net/android/JetBox.apk"

    private class Site(var body: String?) : UpdateSource {
        var calls = 0
        override suspend fun fetch(): String? {
            calls++
            return body
        }
    }

    private fun TestScope.vmWithUpdates(
        site: Site, current: Int = 10, enabled: Boolean = true, clock: () -> Long = { 1_000L },
    ): HomeViewModel {
        val updates = UpdateChecker(site, MemoryStore(), currentCode = current, enabled = enabled, clock = clock,
            io = kotlin.coroutines.EmptyCoroutineContext)
        val vm = HomeViewModel(FakeEngine(), JetBoxStore(MemoryStore()).apply { currentBox = box },
            HomeProbeRunner(Probe(24)), backgroundScope as CoroutineScope, updates)
        runCurrent()
        return vm
    }

    @Test fun noUpdateRowByDefault() = runTest {
        assertNull(env().vm.ui.value.update)
    }

    @Test fun homeVisibleChecksAndShowsNewerVersion() = runTest {
        val site = Site("""{"versionCode":11,"versionName":"1.1","url":"$apk"}""")
        val vm = vmWithUpdates(site)
        assertNull("до показа экрана сеть не трогаем", vm.ui.value.update)
        assertEquals(0, site.calls)
        val job = launch { vm.whileVisible() }
        runCurrent()
        assertEquals(UpdateInfo(11, "1.1", apk, null), vm.ui.value.update)
        job.cancel()
        // Экран снова виден в те же сутки — второго запроса нет.
        val again = launch { vm.whileVisible() }
        runCurrent()
        assertEquals(1, site.calls)
        again.cancel()
    }

    @Test fun sameVersionShowsNothing() = runTest {
        val vm = vmWithUpdates(Site("""{"versionCode":10,"versionName":"1.0","url":"$apk"}"""))
        val job = launch { vm.whileVisible() }
        runCurrent()
        assertNull(vm.ui.value.update)
        job.cancel()
    }

    @Test fun missingFileShowsNothing() = runTest {
        val vm = vmWithUpdates(Site(null))
        val job = launch { vm.whileVisible() }
        runCurrent()
        assertNull(vm.ui.value.update)
        job.cancel()
    }

    @Test fun settingsOpenForcesCheck() = runTest {
        val site = Site(null)
        var now = 1_000L
        val vm = vmWithUpdates(site, clock = { now })
        val job = launch { vm.whileVisible() }
        runCurrent()
        job.cancel()
        now += UpdateChecker.FORCE_INTERVAL_MS
        site.body = """{"versionCode":12,"versionName":"1.2","url":"$apk"}"""
        vm.checkUpdatesNow()
        runCurrent()
        assertEquals(2, site.calls)
        assertEquals("1.2", vm.ui.value.update?.versionName)
    }

    @Test fun storeBuildShowsNothing() = runTest {
        val site = Site("""{"versionCode":11,"versionName":"1.1","url":"$apk"}""")
        val vm = vmWithUpdates(site, enabled = false)
        val job = launch { vm.whileVisible() }
        runCurrent()
        vm.checkUpdatesNow()
        runCurrent()
        assertNull(vm.ui.value.update)
        assertEquals(0, site.calls)
        job.cancel()
    }
}
