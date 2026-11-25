package net.jetnet.jetbox.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
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

/** «Выйти» в настройках: туннель гаснет, личность устройства и JetBox забываются только на телефоне. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutTest {
    private val box = JetBoxBox("https://netbird.jet-net.net", "home-exit", null, "Home", 1L)

    private class Env(scope: TestScope) {
        val engine = FakeEngine()
        val store = JetBoxStore(MemoryStore())
        val logs = mutableListOf<String>()
        val ob = JetBoxOnboarding(
            engine, store, CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            stopTimeoutMs = 5_000,
            log = { logs += it },
        )
    }

    private fun Env.signedIn(box: JetBoxBox) {
        store.currentBox = box
        engine.configExists = true
        engine.stateFile.writeText("{}")
    }

    @Test fun connectedPhoneStopsTunnelAndForgetsEverything() = runTest {
        val env = Env(this)
        env.signedIn(box)
        env.engine.emit(VpnDisplayState.CONNECTED)
        env.engine.onStop = { it.emit(VpnDisplayState.DISCONNECTED) }

        val result = async { env.ob.signOut() }
        advanceUntilIdle()

        assertTrue(result.await().isSuccess)
        assertEquals(1, env.engine.stopCalls)
        assertEquals(1, env.engine.forgetCalls)
        assertFalse("ключ устройства стёрт", env.engine.configFile.exists())
        assertFalse(env.engine.stateFile.exists())
        assertNull("JetBox забыт", env.store.currentBox)
        assertFalse("запуск ядра снова открыт", env.engine.gateClosed)
        assertEquals(Step.Idle, env.ob.step.value)
    }

    @Test fun disconnectedPhoneDoesNotStopAgain() = runTest {
        val env = Env(this)
        env.signedIn(box)
        val result = async { env.ob.signOut() }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)
        assertEquals(0, env.engine.stopCalls)
        assertNull(env.store.currentBox)
    }

    @Test fun tunnelThatWillNotStopKeepsEverything() = runTest {
        val env = Env(this)
        env.signedIn(box)
        env.engine.emit(VpnDisplayState.CONNECTED)
        val result = async { env.ob.signOut() }
        advanceUntilIdle()

        assertTrue(result.await().exceptionOrNull() is IdentityRegistration.EngineStillRunning)
        assertEquals(0, env.engine.forgetCalls)
        assertTrue(env.engine.configFile.exists())
        assertEquals(box, env.store.currentBox)
    }

    @Test fun filesThatWillNotDeleteKeepTheBox() = runTest {
        val env = Env(this)
        env.signedIn(box)
        env.engine.failDelete = { true }
        val result = async { env.ob.signOut() }
        advanceUntilIdle()

        assertNotNull(result.await().exceptionOrNull())
        assertEquals("хранилище не тронуто — повтор возможен", box, env.store.currentBox)
        assertFalse(env.engine.gateClosed)
    }

    @Test fun signOutClosesFlowOnScreen() = runTest {
        val env = Env(this)
        env.signedIn(box)
        env.ob.handle("https://box.jet-net.net/connect#k=11111111-2222-4333-8444-555555555555&m=https://netbird.jet-net.net&e=home-exit&n=Home")
        advanceUntilIdle()
        assertTrue(env.ob.isPresenting)

        val result = async { env.ob.signOut() }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)
        assertEquals(Step.Idle, env.ob.step.value)
        assertNull(env.store.currentBox)
    }

    @Test fun nextLinkRegistersFromScratch() = runTest {
        val env = Env(this)
        env.signedIn(box)
        val result = async { env.ob.signOut() }
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)
        // Конфига нет — ядро создаст новый ключ само (план REUSE без конфига), прежний не вернётся.
        assertFalse(env.engine.configExists)
        assertTrue(env.logs.none { "11111111-2222" in it })
    }
}
