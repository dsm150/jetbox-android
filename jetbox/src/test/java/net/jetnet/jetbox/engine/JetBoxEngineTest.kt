package net.jetnet.jetbox.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.logic.VpnDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JetBoxEngineTest {
    @Test
    fun fakeEngineCountsCallsAndReturnsConfiguredResults() = runTest {
        val e = FakeEngine().apply {
            registerResult = Result.failure(Exception("invalid setup key"))
            onStart = { it.emit(VpnDisplayState.CONNECTED, nowMillis = 42L) }
        }
        val r = e.register("https://m.example", "KEY")
        assertEquals("invalid setup key", r.exceptionOrNull()?.message)
        e.start()
        assertEquals(1, e.startCalls)
        assertEquals(VpnDisplayState.CONNECTED, e.state.value)
        assertEquals(42L, e.connectedSinceMillis.value)
        e.emit(VpnDisplayState.DISCONNECTED)
        assertEquals(null, e.connectedSinceMillis.value)
        assertFalse(e.selectRoute("exit").isFailure)
        assertEquals(listOf("exit"), e.selectedRoutes)
    }

    @Test
    fun fakeRegisterWaitsForGate() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = FakeEngine().apply { registerGate = gate; loginRequired = true }
        var done = false
        val job = launch { e.register("https://m.example", "KEY"); done = true }
        testScheduler.advanceUntilIdle()
        assertFalse(done)
        gate.complete(Unit)
        job.join()
        assertEquals(true, done)
        assertEquals(true, e.isLoginRequired())
        assertEquals(null, e.clientState.value)
    }
}
