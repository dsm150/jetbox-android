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
import net.jetnet.jetbox.logic.JetBoxIdentity
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

    private val otherKey = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private fun url(
        m: String = "https://nb.jet-net.net",
        e: String? = "home-exit",
        n: String? = "Дом",
        k: String = key,
        t: String? = null,
    ): String = buildString {
        append("jetbox://connect#k=").append(k).append("&m=").append(m)
        if (e != null) append("&e=").append(e)
        if (n != null) append("&n=").append(n)
        if (t != null) append("&t=").append(t)
    }

    private fun payload(s: String): JetBoxLinkPayload =
        (JetBoxLink.parse(s) as JetBoxLink.Outcome.Payload).payload

    private class Env(scope: TestScope) {
        val engine = FakeEngine()
        val kv = MemoryStore()
        val store = JetBoxStore(kv)
        val logs = mutableListOf<String>()
        init {
            // Остатки замены IdentityRegistration разбирает по отпечатку в хранилище сценария.
            engine.storedFingerprint = { store.currentBox?.setupKeyFingerprint }
        }
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

    private val oldBox = JetBoxBox("https://old.jet-net.net", "old-exit", "old-exit", "Старый дом", 1L)

    // region ссылка

    @Test fun brokenLinkFailsWithoutRegister() = runTest {
        val env = env()
        assertTrue(env.ob.handle("jetbox://connect#k=not-a-uuid&m=https://nb.jet-net.net"))
        assertEquals(Step.Failed(JetBoxFailure.BrokenLink), env.ob.step.value)
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls)
    }

    /**
     * Management вне маски `*.jet-net.net`: отказ «не на сервер JetBox» ДО вопроса «Другой JetBox» —
     * в сеть не идём, сохранённое подключение и туннель не трогаем.
     */
    @Test fun foreignServerFailsBeforeSwitchQuestion() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        assertTrue(env.ob.handle(url(m = "https://netbird.jet-net.net.evil.com")))
        assertEquals(Step.Failed(JetBoxFailure.ForeignServer), env.ob.step.value)
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.ForeignServer), env.ob.step.value)
        assertEquals(oldBox, env.store.currentBox)
        assertEquals(0, env.engine.isOnlineCalls)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(0, env.engine.startCalls)
        assertEquals(0, env.engine.stopCalls)
        // «Попробовать снова» нечего: повтор закрывает экран, а не идёт в сеть.
        env.ob.retry()
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls)
    }

    /** Чужая ссылка при работающем туннеле: туннель не трогаем, запись прежняя, в сеть не идём. */
    @Test fun foreignServerKeepsRunningTunnel() = runTest {
        val env = env()
        env.store.currentBox = oldBox
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.routes.value = listOf(RouteInfo("old-exit", "0.0.0.0/0, ::/0", selected = true))
        advanceUntilIdle()
        assertTrue(env.ob.handle(url(m = "https://evil.example.com")))
        assertEquals(Step.Failed(JetBoxFailure.ForeignServer), env.ob.step.value)
        advanceUntilIdle()
        env.ob.retry()
        env.ob.dismiss()
        advanceUntilIdle()
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
        assertEquals(oldBox, env.store.currentBox)
        assertEquals(0, env.engine.stopCalls)
        assertEquals(0, env.engine.startCalls)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(0, env.engine.isOnlineCalls)
        assertEquals(0, env.engine.selectRouteCalls)
        assertTrue(env.engine.deselectedRoutes.isEmpty())
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
        assertEquals("https://nb.jet-net.net", env.engine.lastRegisterUrl)
        assertEquals(key, env.engine.lastRegisterKey)
        assertEquals(1, env.engine.startCalls)
        assertEquals(listOf("home-exit"), env.engine.selectedRoutes)
        assertEquals(
            JetBoxBox(
                "https://nb.jet-net.net", "home-exit", "home-exit", "Дом", 7_000L,
                setupKeyFingerprint = JetBoxIdentity.fingerprint(key),
            ),
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

    /**
     * Ссылка той же коробки с новым именем: с LINK-CONTRACT 1.3 §2.11 имя — часть удостоверения,
     * поэтому это «Другой JetBox» (экран подтверждения замены), а не тихое переименование.
     * Хранилище до согласия не трогаем; ключ WireGuard решает отпечаток setup key.
     */
    @Test fun sameBoxLinkWithNewNameAsksToSwitch() = runTest {
        val env = env()
        val old = JetBoxBox("https://NB.jet-net.net", "home-exit", "other-exit", "Старое", 1L)
        env.store.currentBox = old
        assertTrue(env.ob.handle(url(n = "Дача")))
        assertEquals(Step.ConfirmSwitch(payload(url(n = "Дача")), "Старое"), env.ob.step.value)
        assertEquals("до согласия запись прежняя", old, env.store.currentBox)
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls)

        env.engine.routes.value = listOf(exit)
        env.engine.onStart = { it.emit(VpnDisplayState.CONNECTED) }
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(1, env.engine.registerCalls)
        assertEquals("Дача", env.store.currentBox?.boxName)
    }

    /** Запись без имени + ссылка той же коробки с именем — без вопроса, имя запоминается сразу (как iOS). */
    @Test fun sameBoxLinkGivesNameToUnnamedRecord() = runTest {
        val env = env()
        env.store.currentBox = JetBoxBox("https://NB.jet-net.net", "home-exit", "other-exit", null, 1L)
        assertTrue(env.ob.handle(url(n = "Дача")))
        assertEquals(Step.Ready(payload(url(n = "Дача"))), env.ob.step.value)
        assertEquals("Дача", env.store.currentBox?.boxName)
        assertEquals("other-exit", env.store.currentBox?.selectedExitNode)
        assertEquals(0, env.engine.registerCalls)
    }

    /** Та же коробка, то же имя (регистр хоста и токен не важны) — без вопроса. */
    @Test fun sameBoxSameNameGoesToReadyWithoutConfirm() = runTest {
        val env = env()
        env.store.currentBox = JetBoxBox("https://NB.jet-net.net", "home-exit", "other-exit", "Дом", 1L)
        assertTrue(env.ob.handle(url(t = "tok-9")))
        assertEquals(Step.Ready(payload(url(t = "tok-9"))), env.ob.step.value)
        assertEquals("other-exit", env.store.currentBox?.selectedExitNode)
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
        assertEquals("https://nb.jet-net.net", env.store.currentBox?.managementURL)
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
        assertEquals("https://nb.jet-net.net", env.store.currentBox?.managementURL)
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
        val other = url(m = "https://other.jet-net.net", n = "Дача")
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

    // region новая личность устройства (баг 13.11.2026: прежний ключ WireGuard — прежние группы)

    /** Телефон зарегистрирован по ссылке [link] (как после успешного сценария), туннель поднят. */
    private fun Env.registeredBy(link: String, running: Boolean = true) {
        store.remember(payload(link), 1L)
        engine.configFile.writeText("key=" + payload(link).setupKey)
        if (running) {
            engine.emit(VpnDisplayState.CONNECTED)
            engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }
        }
        tunnelComesUp(listOf(exit))
    }

    @Test fun sameLinkAgainKeepsDeviceKey() = runTest {
        val env = env()
        env.registeredBy(url())
        env.ob.handle(url())
        assertEquals(Step.Ready(payload(url())), env.ob.step.value)
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(listOf(false), env.engine.registerFreshIdentity)
        assertEquals("повтор своей ссылки туннель не гасит", 0, env.engine.stopCalls)
        assertEquals("ключ устройства прежний", "key=$key", env.engine.deviceKey())
    }

    @Test fun newKeyForSameConnectionRegistersFreshWithoutConfirm() = runTest {
        val env = env()
        env.registeredBy(url())
        env.ob.handle(url(k = otherKey))
        // То же подключение (identity) — без экрана «Другой JetBox», но вход с новым ключом WireGuard.
        assertEquals(Step.Ready(payload(url(k = otherKey))), env.ob.step.value)
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals("файлы профиля уходят из-под туннеля — он остановлен", 1, env.engine.stopCalls)
        assertEquals(JetBoxIdentity.fingerprint(otherKey), env.store.currentBox?.setupKeyFingerprint)
        assertEquals("ядро создало новый конфиг — новый ключ", "key=$otherKey", env.engine.deviceKey())
        assertFalse("остатков замены нет", env.engine.swap().hasLeftovers())
        assertTrue(env.logs.any { it.contains("новую личность устройства") })
        assertFalse("ключ в журнал не попадает", env.logs.any { it.contains(otherKey) })
    }

    @Test fun otherConnectionAsksToSwitchAndRegistersFresh() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit"))
        env.ob.handle(url(k = otherKey))
        assertTrue(env.ob.step.value is Step.ConfirmSwitch)
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals(1, env.engine.stopCalls)
    }

    @Test fun firstRegistrationWithoutConfigKeepsCoreDefault() = runTest {
        val env = env()
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals("конфига нет — ядро и так создаст новый ключ", listOf(false), env.engine.registerFreshIdentity)
        assertNotNull(env.store.currentBox?.setupKeyFingerprint)
    }

    @Test fun firstRegistrationOverForeignConfigIsFresh() = runTest {
        val env = env()
        env.engine.configExists = true // например, вход в расширенном режиме
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
    }

    @Test fun recordWithoutFingerprintFollowsConnectionIdentity() = runTest {
        // (Облачные ссылки без e — см. legacyRecordAndCloudLinkGetsNewKey, с общим cl-exit — legacyCloudRecordSharedExit.)
        // Запись сборки до 1.0.3: отпечатка нет. Тот же JetBox (хост|e|n) — прежний ключ (как iOS).
        val env = env()
        env.store.currentBox = JetBoxBox("https://nb.jet-net.net", "home-exit", "home-exit", "Дом", 1L)
        env.engine.configExists = true
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(listOf(false), env.engine.registerFreshIdentity)

        // Другой JetBox на том же management — новый ключ.
        val env2 = env()
        env2.store.currentBox = JetBoxBox("https://nb.jet-net.net", "other-exit", "other-exit", "Дача", 1L)
        env2.engine.configExists = true
        env2.tunnelComesUp(listOf(exit))
        env2.ob.handle(url())
        assertTrue(env2.ob.step.value is Step.ConfirmSwitch)
        env2.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(listOf(true), env2.engine.registerFreshIdentity)
    }

    @Test fun failedFreshSwitchRestoresFilesAndOldTunnel() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый"))
        val before = env.store.currentBox
        assertEquals("key=$key", env.engine.deviceKey())
        env.engine.registerResult = Result.failure(Exception("invalid setup key"))
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceUntilIdle()

        val step = env.ob.step.value
        assertTrue("$step", step is Step.Failed && step.failure is JetBoxFailure.KeyRejected)
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals("прежние файлы профиля возвращены", "key=$key", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
        assertEquals(1, env.engine.stopCalls)
        assertEquals("прежний туннель поднят снова", 1, env.engine.startCalls)
        assertEquals(before, env.store.currentBox)
    }

    @Test fun cloudLinksDifferByNameNotToken() = runTest {
        // Облачные абоненты делят e=cl-exit и различаются по имени (LINK-CONTRACT 1.3 §2.11); t не участвует.
        val ivanov = url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov", t = "tok-1")
        val petrov = url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Petrov", t = "tok-1", k = otherKey)
        val env = env()
        env.registeredBy(ivanov)
        env.ob.handle(url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov", t = "tok-2"))
        assertTrue("другой t — то же подключение", env.ob.step.value is Step.Ready)
        env.ob.handle(petrov)
        assertTrue("другое имя при том же cl-exit — другое подключение", env.ob.step.value is Step.ConfirmSwitch)
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertFalse("токен в журнал не попадает", env.logs.any { it.contains("tok-1") })
    }

    /** Перепривязка в Кабинете: новый t и новый setup key — без вопроса, но ключ WireGuard новый (отпечаток). */
    @Test fun reboundCloudLinkNoConfirmButFreshKey() = runTest {
        val first = url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov", t = "tok-1")
        val rebound = url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov", t = "tok-2", k = otherKey)
        val env = env()
        env.registeredBy(first)
        env.ob.handle(rebound)
        assertEquals(Step.Ready(payload(rebound)), env.ob.step.value)
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals("key=$otherKey", env.engine.deviceKey())
    }

    /**
     * Запись 1.0.2 (без отпечатка и токена) + облачная ссылка без `e` с тем же хостом и именем: экрана
     * «Другой JetBox» нет (`хост|n:<n>` совпадает), но ключ новый — прежний только ссылке с `e`.
     */
    @Test fun legacyRecordAndCloudLinkGetsNewKey() = runTest {
        val env = env()
        val cloud = url(m = "https://netbird.jet-net.net", e = null, n = "Тариф", t = "tok-1")
        env.store.currentBox = JetBoxBox("https://netbird.jet-net.net", null, null, "Тариф", 1L)
        env.engine.configFile.writeText("key=OLD")
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(cloud)
        assertEquals("без экрана «Другой JetBox»", Step.Ready(payload(cloud)), env.ob.step.value)
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals("ключ новый", "key=$key", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
        // Запись переписана по-новому: отпечаток ключа и токена, сам токен не хранится.
        val box = env.store.currentBox!!
        assertEquals(JetBoxIdentity.fingerprint(key), box.setupKeyFingerprint)
        assertEquals(payload(cloud).deviceTokenHash, box.deviceTokenHash)
        assertFalse(env.kv.getString("jetbox.currentBox")!!.contains("tok-1"))
    }

    /** Запись 1.0.2 облачного абонента с общим `e=cl-exit`: чужое имя — вопрос и новый ключ, своё — без вопроса, прежний ключ. */
    @Test fun legacyCloudRecordSharedExit() = runTest {
        val legacy = JetBoxBox("https://netbird.jet-net.net", "cl-exit", "cl-exit", "Ivanov", 1L)
        val env = env()
        env.store.currentBox = legacy
        env.engine.configFile.writeText("key=OLD")
        env.tunnelComesUp(listOf(exit))
        env.ob.handle(url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Petrov", t = "tok-p"))
        assertTrue(env.ob.step.value is Step.ConfirmSwitch)
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(listOf(true), env.engine.registerFreshIdentity)
        assertEquals("key=$key", env.engine.deviceKey())

        val env2 = env()
        env2.store.currentBox = legacy
        env2.engine.configFile.writeText("key=OLD")
        env2.tunnelComesUp(listOf(exit))
        val own = url(m = "https://netbird.jet-net.net", e = "cl-exit", n = "Ivanov", t = "tok-i")
        env2.ob.handle(own)
        assertEquals(Step.Ready(payload(own)), env2.ob.step.value)
        env2.ob.connect()
        advanceUntilIdle()
        assertEquals(listOf(false), env2.engine.registerFreshIdentity)
        assertEquals("ключ прежний", "key=OLD", env2.engine.deviceKey())
    }

    /** Отмена посреди входа с новой личностью: файлы прежние, прежний туннель поднят снова. */
    @Test fun cancelDuringFreshRegistrationRestoresAndRaisesOldTunnel() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый"))
        val before = env.store.currentBox
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        assertTrue(env.engine.gateClosed)
        assertEquals("ядро уже пишет новый конфиг", "key=$otherKey", env.engine.deviceKey())

        env.ob.dismiss()
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertEquals("прежняя личность", "key=$key", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
        assertFalse(env.engine.gateClosed)
        assertEquals(before, env.store.currentBox)
        assertEquals("прежний туннель поднят снова", 1, env.engine.startCalls)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
    }

    /** Прежние файлы не вернулись: отдельная ошибка, туннель не поднимаем; повтор начинает с возврата. */
    @Test fun restoreFailureShowsOwnErrorAndRetryRestoresFirst() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый"))
        env.engine.registerResult = Result.failure(Exception("invalid setup key"))
        // Состояния не было, ядро его создаст, а при откате удалить его «не получится».
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        val state = env.engine.stateFile
        env.engine.failDelete = { it == state }
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        state.writeText("core state") // ядро посреди входа создало состояние
        gate.complete(Unit)
        advanceUntilIdle()

        val step = env.ob.step.value
        assertTrue("$step", step is Step.Failed && step.failure is JetBoxFailure.Other)
        assertTrue((step as Step.Failed).failure.details!!.contains("прежнее подключение"))
        assertTrue(step.failure.canRetry)
        assertEquals("туннель не поднят", 0, env.engine.startCalls)
        assertTrue(env.engine.swap().hasLeftovers())

        env.engine.failDelete = { false }
        env.engine.registerResult = Result.success(Unit)
        env.ob.retry()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals("key=$otherKey", env.engine.deviceKey())
        assertFalse(state.exists())
        assertFalse(env.engine.swap().hasLeftovers())
    }

    /** Виджет, плитка, always-on во время замены: запуск ядра закрыт. */
    @Test fun startDuringFreshRegistrationIsRefused() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый"))
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        env.engine.start() // касание виджета посреди NewAuth
        assertEquals(1, env.engine.refusedStarts)
        assertEquals(0, env.engine.startCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(1, env.engine.startCalls)
    }

    /** Ядро ещё работает к моменту замены (не остановилось) — файлы не трогаем, прежний туннель жив. */
    @Test fun freshRegistrationRefusedWhileEngineRuns() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый"), running = false)
        env.engine.running = true
        env.engine.onStop = { } // остановка «не доходит»
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.TunnelFailed), env.ob.step.value)
        assertEquals("key=$key", env.engine.deviceKey())
    }

    /**
     * Остатки оборванной замены (старт процесса их не разобрал: ядро работало) + повтор своей ссылки:
     * прежний ключ, но сначала туннель гасится и остатки разбираются под закрытым запуском ядра.
     */
    @Test fun sameLinkWithLeftoversStopsTunnelAndRecoversFirst() = runTest {
        val env = env()
        env.registeredBy(url())
        env.engine.swap().stash(JetBoxIdentity.fingerprint(otherKey))
        env.engine.configFile.writeText("key=$otherKey") // оборванная попытка успела создать конфиг
        assertTrue(env.engine.hasUnfinishedSwap())
        env.ob.handle(url())
        env.ob.connect()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals(listOf(false), env.engine.registerFreshIdentity)
        assertEquals("туннель погашен до разбора остатков", 1, env.engine.stopCalls)
        assertEquals("прежняя личность возвращена", "key=$key", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
        assertFalse(env.engine.gateClosed)
    }

    /** Прежний ключ, остатки есть, а ядро не остановилось: файлы не трогаем. */
    @Test fun keepKeyWithLeftoversRefusedWhileEngineRuns() = runTest {
        val env = env()
        env.registeredBy(url(), running = false)
        env.engine.swap().stash(JetBoxIdentity.fingerprint(otherKey))
        env.engine.running = true
        val r = env.engine.register("https://nb.jet-net.net", key, freshIdentity = false) {}
        assertTrue("${r.exceptionOrNull()}", r.exceptionOrNull() is IdentityRegistration.EngineStillRunning)
        assertTrue("остатки не тронуты", env.engine.swap().hasLeftovers())
        assertFalse(env.engine.gateClosed)
    }

    /** Отмена посреди замены, а прежние файлы вернулись не до конца: туннель на полуразобранном диске не поднимаем. */
    @Test fun cancelWithFailedRestoreDoesNotRaiseOldTunnel() = runTest {
        val env = env()
        env.registeredBy(url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый"))
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        val pending = env.engine.swap().pendingFile
        env.engine.failDelete = { it == pending }

        env.ob.dismiss()
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertTrue(env.engine.swap().hasLeftovers())
        assertEquals("туннель не поднят", 0, env.engine.startCalls)
        assertTrue(env.logs.any { it.contains("не до конца") })

        // Новая попытка начинает с разбора остатков.
        env.engine.failDelete = { false }
        env.engine.registerGate = null
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.Protected("home-exit"), env.ob.step.value)
        assertEquals("key=$otherKey", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
    }

    // endregion

    // region обрыв замены подключения (решение 18.11.2026): туннель — как до сценария

    private val oldLink get() = url(m = "https://old.jet-net.net", e = "old-exit", n = "Старый")
    private val thirdLink get() = url(m = "https://third.jet-net.net", e = "third-exit", n = "Третий", k = otherKey)
    private val foreignLink get() = url(m = "https://evil.example.com")
    private val brokenLink = "jetbox://connect#k=not-a-uuid&m=https://nb.jet-net.net"

    /** Новая ссылка, пока прежний туннель ещё гаснет: поднимаем его снова — после остановки, один раз. */
    @Test fun newLinkWhileOldTunnelStopsRaisesItAgain() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val before = env.store.currentBox
        env.engine.onStop = { } // «Отключено» придёт позже
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceTimeBy(1_000)
        assertEquals(1, env.engine.stopCalls)

        env.ob.handle(thirdLink)
        assertEquals(Step.ConfirmSwitch(payload(thirdLink), "Старый"), env.ob.step.value)
        runCurrent()
        assertEquals("пока ядро не остановилось — не запускаем", 0, env.engine.startCalls)
        env.engine.emit(VpnDisplayState.DISCONNECTED)
        advanceUntilIdle()
        assertEquals("прежний туннель поднят снова", 1, env.engine.startCalls)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(before, env.store.currentBox)
        assertEquals("key=$key", env.engine.deviceKey())
        assertEquals(Step.ConfirmSwitch(payload(thirdLink), "Старый"), env.ob.step.value)
    }

    /** «Закрыть» (cancelFlow), пока прежний туннель гаснет: туннель поднят снова. */
    @Test fun dismissWhileOldTunnelStopsRaisesItAgain() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val before = env.store.currentBox
        env.engine.onStop = { it.emit(VpnDisplayState.DISCONNECTING) }
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceTimeBy(1_000)
        env.ob.dismiss()
        env.engine.emit(VpnDisplayState.DISCONNECTED)
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertEquals(1, env.engine.startCalls)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
        assertEquals(before, env.store.currentBox)
    }

    /** Обрыв, а прежнее ядро так и не остановилось: оно и так работает — второй раз не запускаем. */
    @Test fun abortWhileCoreNeverStopsDoesNotStartTwice() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        env.engine.onStop = { }
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceTimeBy(1_000)
        env.ob.dismiss()
        advanceUntilIdle()
        assertEquals(0, env.engine.startCalls)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
    }

    /** Новая ссылка посреди входа с новой личностью: сначала файлы прежние, потом туннель — один раз. */
    @Test fun newLinkDuringFreshRegistrationRestoresFilesThenTunnel() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val before = env.store.currentBox
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        assertTrue(env.engine.gateClosed)

        env.ob.handle(thirdLink)
        advanceUntilIdle()
        assertEquals(Step.ConfirmSwitch(payload(thirdLink), "Старый"), env.ob.step.value)
        assertEquals("прежняя личность", "key=$key", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
        assertEquals("запуск не раньше отката файлов", 0, env.engine.refusedStarts)
        assertEquals(1, env.engine.startCalls)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
        assertEquals(before, env.store.currentBox)
    }

    /** Туннель до сценария был выключен: обрыв его не включает. */
    @Test fun abortWithTunnelOffBeforeDoesNotStartIt() = runTest {
        val env = env()
        env.registeredBy(oldLink, running = false)
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        env.ob.handle(thirdLink)
        advanceUntilIdle()
        assertEquals(0, env.engine.stopCalls)
        assertEquals(0, env.engine.startCalls)
        assertEquals("key=$key", env.engine.deviceKey())
        assertEquals(VpnDisplayState.DISCONNECTED, env.engine.state.value)

        // Ошибка регистрации при выключенном туннеле — тоже не включаем.
        env.engine.registerGate = null
        env.engine.registerResult = Result.failure(Exception("invalid setup key"))
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertTrue(env.ob.step.value is Step.Failed)
        assertEquals(0, env.engine.startCalls)
    }

    /**
     * Чужая ссылка посреди замены ДО точки фиксации (регистрация идёт, прежний туннель погашен): откат —
     * сначала прежние файлы, потом прежний туннель, один раз; запись прежняя — «Подключение не изменено» правда.
     */
    @Test fun foreignLinkDuringRegisteringRollsBack() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val before = env.store.currentBox
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        assertTrue(env.ob.step.value is Step.Registering)

        assertTrue(env.ob.handle(foreignLink))
        assertEquals(Step.Failed(JetBoxFailure.ForeignServer), env.ob.step.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.ForeignServer), env.ob.step.value)
        assertEquals(before, env.store.currentBox)
        assertEquals("прежняя личность", "key=$key", env.engine.deviceKey())
        assertFalse(env.engine.swap().hasLeftovers())
        assertEquals(0, env.engine.refusedStarts)
        assertEquals(1, env.engine.stopCalls)
        assertEquals("прежний туннель поднят снова", 1, env.engine.startCalls)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
    }

    /** Битая ссылка, пока прежний туннель гаснет (до регистрации): туннель поднят после остановки, запись прежняя. */
    @Test fun brokenLinkWhileOldTunnelStopsRollsBack() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val before = env.store.currentBox
        env.engine.onStop = { }
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceTimeBy(1_000)
        assertTrue(env.ob.handle(brokenLink))
        assertEquals(Step.Failed(JetBoxFailure.BrokenLink), env.ob.step.value)
        runCurrent()
        assertEquals(0, env.engine.startCalls)
        env.engine.emit(VpnDisplayState.DISCONNECTED)
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.BrokenLink), env.ob.step.value)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(1, env.engine.startCalls)
        assertEquals(before, env.store.currentBox)
    }

    /**
     * Чужая ссылка, а прежние файлы вернулись не до конца: туннель не поднимаем, и «Подключение не
     * изменено» не показываем — вместо него отказ «не удалось вернуть прежнее подключение».
     */
    @Test fun foreignLinkWithFailedRestoreShowsRestoreError() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val gate = CompletableDeferred<Unit>()
        env.engine.registerGate = gate
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        val pending = env.engine.swap().pendingFile
        env.engine.failDelete = { it == pending }
        env.ob.handle(foreignLink)
        advanceUntilIdle()
        val step = env.ob.step.value
        assertTrue("$step", step is Step.Failed && step.failure is JetBoxFailure.Other)
        assertTrue((step as Step.Failed).failure.details!!.contains("прежнее подключение"))
        assertEquals(0, env.engine.startCalls)
        assertTrue(env.engine.swap().hasLeftovers())
    }

    /**
     * ПОСЛЕ точки фиксации (запись новая, ждём согласие VPN): чужая ссылка — обычный отказ, прежний
     * туннель не поднимаем (его уже нет), запись новая.
     */
    @Test fun foreignLinkAfterCommitDoesNotRaisePrevious() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        env.engine.consentIntent = Intent()
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertEquals(Step.NeedsVpnConsent, env.ob.step.value)
        env.ob.handle(foreignLink)
        env.ob.onVpnConsent(true)
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.ForeignServer), env.ob.step.value)
        assertEquals(0, env.engine.startCalls)
        assertEquals("https://nb.jet-net.net", env.store.currentBox?.managementURL)
        assertEquals("key=$otherKey", env.engine.deviceKey())
    }

    /** ПОСЛЕ точки фиксации, на подключении: битая ссылка обрывает ожидание; второго запуска нет, запись новая. */
    @Test fun brokenLinkWhileConnectingAfterCommitStartsOnce() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        env.engine.onStart = { } // туннель не поднимается сразу
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        assertTrue(env.ob.step.value is Step.Connecting)
        env.ob.handle(brokenLink)
        advanceUntilIdle()
        assertEquals(Step.Failed(JetBoxFailure.BrokenLink), env.ob.step.value)
        assertEquals(1, env.engine.startCalls)
        assertEquals("https://nb.jet-net.net", env.store.currentBox?.managementURL)
    }

    /**
     * Ошибка подключения после записи нового подключения (точка фиксации): прежнего уже нет, туннель
     * был включён — его и включали (start сценария); второго запуска нет, запись новая.
     */
    @Test fun connectErrorAfterSwapStartsOnce() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        env.engine.onStart = { it.lastError.value = "engine failed: boom" }
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        advanceUntilIdle()
        assertTrue(env.ob.step.value is Step.Failed)
        assertEquals(1, env.engine.stopCalls)
        assertEquals(1, env.engine.startCalls)
        assertEquals("https://nb.jet-net.net", env.store.currentBox?.managementURL)
    }

    /** Отмена пришла, когда хранилище уже новое: прежнего подключения нет — туннель включаем (новый), один раз. */
    @Test fun cancelAfterCommitRaisesTunnelOnce() = runTest {
        val env = env()
        env.registeredBy(oldLink)
        val after = CompletableDeferred<Unit>()
        env.engine.afterRegisteredGate = after
        env.ob.handle(url(k = otherKey))
        env.ob.confirmSwitch()
        runCurrent()
        assertEquals("хранилище уже новое", "https://nb.jet-net.net", env.store.currentBox?.managementURL)
        env.ob.dismiss()
        advanceUntilIdle()
        assertEquals(Step.Idle, env.ob.step.value)
        assertEquals(1, env.engine.startCalls)
        assertEquals("key=$otherKey", env.engine.deviceKey())
        assertTrue(env.logs.any { it.contains("прежнего уже нет") })
    }

    // endregion
}
