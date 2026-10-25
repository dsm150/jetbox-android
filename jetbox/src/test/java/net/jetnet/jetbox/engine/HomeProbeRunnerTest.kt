package net.jetnet.jetbox.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeProbeRunnerTest {
    private class Probe(var answer: Long?) : HomeProbe {
        val asked = mutableListOf<String>()
        override suspend fun measure(meshIp: String): Long? {
            asked += meshIp
            return answer
        }
    }

    @Test fun measuresEveryTenSecondsWhileTargetPresent() = runTest {
        val probe = Probe(24)
        val runner = HomeProbeRunner(probe)
        val target = MutableStateFlow<String?>("100.64.0.10")
        val job = launch { runner.run(target) }
        runCurrent()
        assertEquals(1, probe.asked.size)
        assertEquals(24L, runner.latencyMs.value)
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, probe.asked.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, probe.asked.size)
        assertEquals("100.64.0.10", probe.asked.last())
        job.cancel()
    }

    @Test fun nullTargetStopsAndClears() = runTest {
        val probe = Probe(31)
        val runner = HomeProbeRunner(probe)
        val target = MutableStateFlow<String?>("100.64.0.1")
        val job = launch { runner.run(target) }
        runCurrent()
        assertEquals(31L, runner.latencyMs.value)
        target.value = null
        runCurrent()
        assertNull(runner.latencyMs.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, probe.asked.size)
        job.cancel()
    }

    @Test fun cancellationStopsMeasuring() = runTest {
        val probe = Probe(10)
        val runner = HomeProbeRunner(probe)
        val job = launch { runner.run(MutableStateFlow("100.64.0.1")) }
        runCurrent()
        job.cancel()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, probe.asked.size)
    }

    @Test fun noAnswerGivesNull() = runTest {
        val runner = HomeProbeRunner(Probe(null))
        val job = launch { runner.run(MutableStateFlow("100.64.0.1")) }
        runCurrent()
        assertNull(runner.latencyMs.value)
        job.cancel()
    }
}
