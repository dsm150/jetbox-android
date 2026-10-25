package net.jetnet.jetbox.engine

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.link.JetBoxLink
import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.logic.JetBoxFailure
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сценарий «одной кнопки» — порт `JetBoxOnboarding.swift` (iOS). В iOS сценарных тестов нет
 * (в NetBirdTests — разбор ссылки, слова, статистика), поэтому сценарии — из плана и из
 * поведения Swift-кода. Время виртуальное (`runTest`), движок — `FakeEngine`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JetBoxOnboardingTest {
    private val key = "11111111-2222-4333-8444-555555555555"
    private val exit = RouteInfo("home-exit", "0.0.0.0/0, ::/0", selected = false)

    private fun url(
        m: String = "https://nb.example.net",
        e: String? = "home-exit",
        n: String? = "Дом",
    ): String = buildString {
        append("jetbox://connect#k=").append(key).append("&m=").append(m)
        if (e != null) append("&e=").append(e)
        if (n != null) append("&n=").append(n)
    }

    private fun payload(s: String): JetBoxLinkPayload =
        (JetBoxLink.parse(s) as JetBoxLink.Outcome.Payload).payload

    private class Env(scope: TestScope) {
        val engine = FakeEngine()
        val kv = MemoryStore()
        val store = JetBoxStore(kv)
        val logs = mutableListOf<String>()
        val ob = JetBoxOnboarding(
            // Не backgroundScope: его работу advanceUntilIdle не крутит. Своя SupervisorJob — чтобы
            // runTest не ждал сценарий, оставленный висеть (например, на согласии VPN).
            engine, store, CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            log = { logs += it },
            clock = { 7_000L },
        )

        /** Туннель поднимается сразу после start, маршруты приходят вместе с ним. */
        fun tunnelComesUp(routes: List<RouteInfo>) {
            engine.onStart = {
                it.emit(VpnDisplayState.CONNECTED)
                it.routes.value = routes
            }
        }
    }

    private fun TestScope.env() = Env(this)

    private val oldBox = JetBoxBox("https://old.example.net", "old-exit", "old-exit", "Старый дом", 1L)

    // region ссылка

    @Test fun brokenLinkFailsWithoutRegister() = runTest {
        val env = env()
        assertTrue(env.ob.handle("jetbox://connect#k=not-a-uuid&m=https://nb.example.net"))
        assertEquals(Step.Failed(JetBoxFailure.BrokenLink), env.ob.step.value)
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls)
    }

    @Test fun notOursReturnsFalseAndKeepsStep() = runTest {
        val env = env()
        assertFalse(env.ob.handle("https://example.com/connect#k=$key"))
        assertEquals(Step.Idle, env.ob.step.value)
        env.ob.handle(url())
        val ready = env.ob.step.value
        assertFalse(env.ob.handle("https://example.com/"))
        assertEquals(ready, env.ob.step.value)
    }

    @Test fun firstLinkGivesReady() = runTest {
        val env = env()
        assertTrue(env.ob.handle(url()))
        assertEquals(Step.Ready(payload(url())), env.ob.step.value)
        assertEquals(0, env.engine.registerCalls)
        assertNull(env.store.currentBox)
    }

    // endregion
    // region полный путь

    @Test fun connectGoesAllTheWayToProtected() = runTest {
        val env = env()
        env.tunnelComesUp(listOf(exit, RouteInfo("lan", "192.168.0.0/24", false)))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()

        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(1, env.engine.registerCalls)
        assertEquals("https://nb.example.net", env.engine.lastRegisterUrl)
        assertEquals(key, env.engine.lastRegisterKey)
        assertEquals(1, env.engine.startCalls)
        assertEquals(listOf("home-exit"), env.engine.selectedRoutes)
        assertEquals(
            JetBoxBox("https://nb.example.net", "home-exit", "home-exit", "Дом", 7_000L),
            env.store.currentBox,
        )
        // Ключ не хранится и не логируется (только маска).
        assertFalse(env.kv.getString("jetbox.currentBox")!!.contains(key, ignoreCase = true))
        assertTrue(env.logs.isNotEmpty())
        assertTrue(env.logs.none { it.contains(key, ignoreCase = true) })
        assertTrue(env.logs.any { it.contains(key.takeLast(4)) })
    }

    @Test fun stepsGoThroughRegisteringConnectingSelectingExit() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url())
        env.ob.connect()
        runCurrent()
        assertEquals(Step.Registering(payload(url())), env.ob.step.value)

        gate.complete(Unit)
        runCurrent()
        assertEquals(Step.Connecting(payload(url())), env.ob.step.value)

        env.engine.emit(VpnDisplayState.CONNECTED)
        runCurrent()
        assertEquals(Step.SelectingExit(payload(url())), env.ob.step.value)

        env.engine.routes.value = listOf(exit)
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
    }

    @Test fun alreadySelectedExitIsNotSelectedAgain() = runTest {
        val env = env()
        env.tunnelComesUp(listOf(exit.copy(selected = true)))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(0, env.engine.selectRouteCalls)
        assertEquals("home-exit", env.store.currentBox?.selectedExitNode)
    }

    // endregion
    // region отказы

    @Test fun rejectedKeyFailsWithKeyRejected() = runTest {
        val env = env()
        env.engine.registerResult =
            Result.failure(Exception("rpc error: code = PermissionDenied desc = invalid setup-key"))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        val step = env.ob.step.value
        assertTrue("$step", step is Step.Failed && step.failure is JetBoxFailure.KeyRejected)
        assertNull(env.store.currentBox)
        assertEquals(0, env.engine.startCalls)
    }

    @Test fun offlineFailsBeforeAnyNetworkCall() = runTest {
        val env = env()
        env.engine.online.value = false
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.Offline), env.ob.step.value)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(0, env.engine.startCalls)
    }

    @Test fun tunnelNotUpIn45sFailsWithTunnelFailed() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(44_000)
        assertEquals(Step.Connecting(payload(url())), env.ob.step.value)
        advanceTimeBy(1_500)
        assertEquals(Step.Failed(JetBoxFailure.TunnelFailed), env.ob.step.value)
        assertEquals(1, env.engine.startCalls)
    }

    @Test fun engineErrorWhileConnectingIsClassified() = runTest {
        val env = env()
        env.engine.onStart = { it.lastError.value = "failed to create auth client: connection refused" }
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(1_000)
        assertEquals(
            Step.Failed(JetBoxFailure.ManagementUnreachable("failed to create auth client: connection refused")),
            env.ob.step.value,
        )
    }

    @Test fun engineErrorArrivingLaterWhileConnectingFails() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(5_000)
        env.engine.lastError.value = "something odd"
        runCurrent()
        assertEquals(Step.Failed(JetBoxFailure.Other("something odd")), env.ob.step.value)
    }

    @Test fun staleErrorFromBeforeStartIsIgnored() = runTest {
        val env = env()
        env.engine.lastError.value = "old error"
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(3_000)
        assertEquals(Step.Connecting(payload(url())), env.ob.step.value)
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.routes.value = listOf(exit)
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
    }

    @Test fun loginRequiredWhileConnectingIsKeyRejected() = runTest {
        val env = env()
        env.engine.onStart = { it.loginRequired = true }
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(1_000)
        assertEquals(Step.Failed(JetBoxFailure.KeyRejected(null)), env.ob.step.value)
    }

    @Test fun errorAfterConnectIsNotTunnelFailure() = runTest {
        val env = env()
        env.engine.selectRouteResult = Result.failure(Exception("boom"))
        env.engine.onStart = {
            it.emit(VpnDisplayState.CONNECTED)
            it.routes.value = listOf(exit)
            it.lastError.value = "route select failed"
        }
        // Ошибка появилась вместе с CONNECTED: CONNECTED важнее; дальше lastError не смотрим.
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
    }

    @Test fun retryRepeatsLastLinkAndDismissResets() = runTest {
        val env = env()
        env.engine.registerResult = Result.failure(Exception("no such host"))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertTrue(env.ob.step.value is Step.Failed)

        env.engine.registerResult = Result.success(Unit)
        env.tunnelComesUp(listOf(exit))
        env.ob.retry()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(2, env.engine.registerCalls)

        env.ob.dismiss()
        assertEquals(Step.Idle, env.ob.step.value)
        env.ob.retry() // нечего повторять — остаёмся в Idle
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertEquals(2, env.engine.registerCalls)
    }

    @Test fun dismissCancelsRunningFlow() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.connect()
        runCurrent()
        assertEquals(Step.Connecting(payload(url())), env.ob.step.value)
        env.ob.dismiss()
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
    }

    // endregion
    // region выход

    @Test fun twoExitsWithoutEAskToChoose() = runTest {
        val env = env()
        env.tunnelComesUp(listOf(RouteInfo("z-exit", "0.0.0.0/0", false), RouteInfo("a-exit", "::/0", false)))
        env.ob.handle(url(e = null))
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.ChooseExit(payload(url(e = null)), listOf("a-exit", "z-exit")), env.ob.step.value)
        assertEquals(0, env.engine.selectRouteCalls)

        env.ob.chooseExit("z-exit")
        advanceUntilIdle()
        assertEquals(Step.Protected("z-exit"), env.ob.step.value)
        assertEquals(listOf("z-exit"), env.engine.selectedRoutes)
        assertEquals("z-exit", env.store.currentBox?.selectedExitNode)
    }

    @Test fun chooseExitNullMeansNotNow() = runTest {
        val env = env()
        env.tunnelComesUp(listOf(RouteInfo("a", "0.0.0.0/0", false), RouteInfo("b", "0.0.0.0/0", false)))
        env.ob.handle(url(e = null))
        env.ob.connect()
        advanceUntilIdle()
        env.ob.chooseExit(null)
        advanceUntilIdle()
        assertEquals(Step.Protected(null), env.ob.step.value)
        assertEquals(0, env.engine.selectRouteCalls)
    }

    @Test fun chooseExitOutsideChooseStepIsIgnored() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.chooseExit("x")
        advanceUntilIdle()
        assertEquals(Step.Ready(payload(url())), env.ob.step.value)
    }

    @Test fun unknownExitInLinkWithSingleExitSelectsTheOnlyOne() = runTest {
        val env = env()
        env.tunnelComesUp(listOf(RouteInfo("only-exit", "0.0.0.0/0", false)))
        env.ob.handle(url(e = "missing"))
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("only-exit"), env.ob.step.value)
        assertEquals(listOf("only-exit"), env.engine.selectedRoutes)
        val box = env.store.currentBox!!
        assertEquals("missing", box.exitNodeName) // удостоверение коробки — из ссылки
        assertEquals("only-exit", box.selectedExitNode)
    }

    @Test fun noExitWithin15sGivesProtectedWithoutExit() = runTest {
        val env = env()
        env.engine.onStart = { it.emit(VpnDisplayState.CONNECTED) }
        env.ob.handle(url())
        env.ob.connect()
        runCurrent()
        assertEquals(Step.SelectingExit(payload(url())), env.ob.step.value)
        advanceTimeBy(14_000)
        assertEquals(Step.SelectingExit(payload(url())), env.ob.step.value)
        advanceUntilIdle()
        assertEquals(Step.Protected(null), env.ob.step.value)
        assertTrue(env.engine.refreshCalls > 1)
        assertEquals(0, env.engine.selectRouteCalls)
    }

    // endregion
    // region повторная ссылка и переключение

    @Test fun sameBoxLinkRenamesImmediatelyAndGoesToReady() = runTest {
        val env = env()
        env.store.currentBox = JetBoxBox("https://NB.example.net", "home-exit", "other-exit", "Старое", 1L)
        assertTrue(env.ob.handle(url(n = "Дача")))
        // Как iOS: тот же JetBox — Ready без «переключить?», имя обновлено сразу, до нажатия.
        assertEquals(Step.Ready(payload(url(n = "Дача"))), env.ob.step.value)
        assertEquals("Дача", env.store.currentBox?.boxName)
        assertEquals("other-exit", env.store.currentBox?.selectedExitNode)
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls)

        // Кнопка — тот же путь, что и в первый раз (как iOS): регистрация ключом из ссылки
        // безопасна и чинит удалённый на сервере пир. Туннель не останавливаем.
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.routes.value = listOf(exit)
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(1, env.engine.registerCalls)
        assertEquals(0, env.engine.stopCalls)
    }

    @Test fun sameLinkDuringFlowIsIgnored() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.connect()
        runCurrent()
        assertEquals(Step.Connecting(payload(url())), env.ob.step.value)
        assertTrue(env.ob.handle(url()))
        runCurrent()
        assertEquals(Step.Connecting(payload(url())), env.ob.step.value)
        assertEquals(1, env.engine.registerCalls)
    }

    @Test fun otherBoxAsksToSwitchAndConfirmStopsThenRegisters() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }
        env.tunnelComesUp(listOf(exit))

        assertTrue(env.ob.handle(url()))
        assertEquals(Step.ConfirmSwitch(payload(url()), "Старый дом"), env.ob.step.value)
        assertEquals(oldBox, env.store.currentBox)

        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.confirmSwitch()
        runCurrent()
        val stateAtRegister = env.engine.state.value
        assertEquals(1, env.engine.registerCalls)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(VpnDisplayState.DISCONNECTED, stateAtRegister)
        assertEquals(1, env.engine.stopCalls)
        assertEquals(1, env.engine.registerCalls)
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals("https://nb.example.net", env.store.currentBox?.managementURL)
    }

    @Test fun switchRegistersOnlyAfterDisconnected() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceTimeBy(2_000)
        assertEquals(1, env.engine.stopCalls)
        assertEquals(0, env.engine.registerCalls)
        env.engine.emit(VpnDisplayState.DISCONNECTED)
        runCurrent()
        assertEquals(1, env.engine.registerCalls)
    }

    @Test fun switchStopTimeoutFailsWithoutRegister() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.TunnelFailed), env.ob.step.value)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(oldBox, env.store.currentBox)
    }

    @Test fun failedSwitchKeepsOldBoxAndRetrySwitches() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.registerResult = Result.failure(Exception("invalid setup key"))
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertTrue(env.ob.step.value is Step.Failed)
        assertEquals(0, env.engine.stopCalls) // туннель и так был выключен
        assertEquals(oldBox, env.store.currentBox)

        // «Попробовать снова» повторяет именно переключение.
        env.engine.registerResult = Result.success(Unit)
        env.tunnelComesUp(listOf(exit))
        env.ob.retry()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals("https://nb.example.net", env.store.currentBox?.managementURL)
    }

    @Test fun confirmSwitchOutsideConfirmStepIsIgnored() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.Ready(payload(url())), env.ob.step.value)
        assertEquals(0, env.engine.registerCalls)
    }

    // endregion
    // region VPN-согласие

    @Test fun vpnConsentDeniedFails() = runTest {
        val env = env()
        env.engine.consentIntent = Intent()
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.NeedsVpnConsent, env.ob.step.value)
        assertNotNull(env.store.currentBox) // регистрация уже прошла
        env.ob.onVpnConsent(false)
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.VpnPermissionDenied), env.ob.step.value)
        assertEquals(0, env.engine.startCalls)
    }

    @Test fun vpnConsentGrantedContinues() = runTest {
        val env = env()
        env.engine.consentIntent = Intent()
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.NeedsVpnConsent, env.ob.step.value)
        // Та же ссылка, пока ждём системный диалог, сценарий не сбрасывает.
        env.ob.handle(url())
        assertEquals(Step.NeedsVpnConsent, env.ob.step.value)

        env.engine.consentIntent = null
        env.ob.onVpnConsent(true)
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(1, env.engine.startCalls)
    }

    @Test fun vpnConsentOutsideConsentStepIsIgnored() = runTest {
        val env = env()
        env.ob.handle(url())
        env.ob.onVpnConsent(false)
        assertEquals(Step.Ready(payload(url())), env.ob.step.value)
    }

    // endregion

    // region правки по ревью

    @Test fun loginRequiredLatchedBeforeConnectDoesNotBlockProtected() = runTest {
        val env = env()
        env.engine.loginRequired = true // ядро держит NeedsLogin с прошлой сессии
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
    }

    @Test fun loginRequiredLatchedBeforeConnectStillRejectsOnRealError() = runTest {
        val env = env()
        env.engine.loginRequired = true
        val error = "rpc error: code = PermissionDenied desc = peer is not registered"
        env.engine.onStart = { it.lastError.value = error }
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(1_000)
        assertEquals(Step.Failed(JetBoxFailure.KeyRejected(error)), env.ob.step.value)
    }

    @Test fun failedSwitchOfRunningTunnelRestoresOldBox() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }
        env.engine.registerResult = Result.failure(Exception("invalid setup key"))
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceUntilIdle()

        val step = env.ob.step.value
        assertTrue("$step", step is Step.Failed && step.failure is JetBoxFailure.KeyRejected)
        assertEquals(1, env.engine.stopCalls)
        assertEquals(1, env.engine.startCalls)
        assertEquals(oldBox, env.store.currentBox)
        assertTrue(env.logs.any { it.contains("возвращаем прежний JetBox") })
    }

    @Test fun failedSwitchDoesNotRestartOldTunnelWithoutVpnConsent() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }
        env.engine.consentIntent = Intent()
        env.engine.registerResult = Result.failure(Exception("invalid setup key"))
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertTrue(env.ob.step.value is Step.Failed)
        assertEquals(1, env.engine.stopCalls)
        assertEquals(0, env.engine.startCalls)
    }

    @Test fun lateRegisterResultAfterDismissDoesNothing() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url())
        env.ob.connect()
        runCurrent()
        assertEquals(Step.Registering(payload(url())), env.ob.step.value)
        env.ob.dismiss()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertNull(env.store.currentBox)
        assertEquals(0, env.engine.startCalls)
    }

    @Test fun lateRegisterResultAfterOtherLinkDoesNotOverwriteStep() = runTest {
        val env = env()
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url())
        env.ob.connect()
        runCurrent()
        val other = url(m = "https://other.example.net", n = "Дача")
        env.ob.handle(other)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Step.Ready(payload(other)), env.ob.step.value)
        assertNull(env.store.currentBox)
        assertEquals(0, env.engine.startCalls)
    }

    @Test fun vpnConsentAfterDismissDoesNothing() = runTest {
        val env = env()
        env.engine.consentIntent = Intent()
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.NeedsVpnConsent, env.ob.step.value)
        env.ob.dismiss()
        env.ob.onVpnConsent(true)
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertEquals(0, env.engine.startCalls)
    }

    /**
     * Ядро запущено, а состояние «Отключено» (старт без сети, слушатель молчит): прежний туннель
     * гасим и регистрируем новый JetBox только после того, как ядро действительно остановилось.
     */
    @Test fun runningCoreWithDisconnectedStateIsStoppedOnSwitch() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.running = true // ядро запущено, слушатель состояния ещё молчит
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceTimeBy(3_000)
        assertEquals(1, env.engine.stopCalls)
        assertEquals("регистрация при работающем ядре", 0, env.engine.registerCalls)
        assertTrue(env.ob.step.value is Step.Registering)

        env.engine.running = false // ядро вышло из цикла запуска
        advanceTimeBy(300)
        assertEquals(1, env.engine.registerCalls)
    }

    /** «Отключено» пришло, но ядро так и не вышло из цикла за отведённое время — отказ, без регистрации. */
    @Test fun coreThatNeverStopsFailsSwitchWithoutRegistering() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.running = true
        env.ob.handle(url())
        env.ob.confirmSwitch()
        advanceTimeBy(11_000)
        assertEquals(Step.Failed(JetBoxFailure.TunnelFailed), env.ob.step.value)
        assertEquals(0, env.engine.registerCalls)
    }

    /**
     * Окно свернули во время регистрации («Назад»), и Android 12+ не дал запустить сервис туннеля из
     * фона: «Не удалось включить защиту» с повтором, а не «Что-то пошло не так».
     */
    @Test fun foregroundStartRefusedFromBackgroundIsTunnelFailedWithRetry() = runTest {
        val env = env()
        env.engine.onStart = { it.lastError.value = EngineApi.FOREGROUND_START_NOT_ALLOWED }
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(1_000)
        val step = env.ob.step.value
        assertEquals(Step.Failed(JetBoxFailure.TunnelFailed), step)
        assertTrue((step as Step.Failed).failure.canRetry)

        // Повтор из открытого окна поднимает туннель.
        env.tunnelComesUp(listOf(exit))
        env.engine.lastError.value = null
        env.ob.retry()
        advanceUntilIdle()
        assertTrue(env.ob.step.value is Step.Protected)
    }

    /** Та же метка от прошлого запуска — не «старая ошибка»: start() ставит её заново. */
    @Test fun repeatedForegroundRefusalIsNotTreatedAsStale() = runTest {
        val env = env()
        env.engine.lastError.value = EngineApi.FOREGROUND_START_NOT_ALLOWED
        env.engine.onStart = { it.lastError.value = EngineApi.FOREGROUND_START_NOT_ALLOWED }
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(1_000)
        assertEquals(Step.Failed(JetBoxFailure.TunnelFailed), env.ob.step.value)
    }

    @Test fun vpnPermissionRequiredErrorFromStartIsVpnPermissionDenied() = runTest {
        val env = env()
        env.engine.onStart = { it.lastError.value = EngineApi.VPN_PERMISSION_REQUIRED }
        env.ob.handle(url())
        env.ob.connect()
        advanceTimeBy(1_000)
        assertEquals(Step.Failed(JetBoxFailure.VpnPermissionDenied), env.ob.step.value)
    }

    // endregion
}
