package net.jetnet.jetbox.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/** Проверка обновлений: раз в сутки, принудительно — по просьбе экрана; любая ошибка — молча. */
class UpdateCheckerTest {
    private val apk = "https://box.jet-net.net/android/JetBox.apk"
    private val v11 = """{"versionCode":11,"versionName":"1.1","url":"$apk"}"""
    private val v12 = """{"versionCode":12,"versionName":"1.2","url":"$apk"}"""
    private val day = UpdateChecker.INTERVAL_MS
    private val minute = UpdateChecker.FORCE_INTERVAL_MS

    /** Фейковый источник: отвечает очередным телом (null — ошибка сети/404) и считает запросы. */
    private class Source(vararg answers: String?) : UpdateSource {
        val queue = ArrayDeque(answers.toList())
        var calls = 0
        override suspend fun fetch(): String? {
            calls++
            return if (queue.isEmpty()) null else queue.removeFirst()
        }
    }

    private class Clock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke() = now
    }

    private fun checker(
        source: UpdateSource,
        kv: MemoryStore = MemoryStore(),
        current: Int = 10,
        enabled: Boolean = true,
        clock: Clock = Clock(),
    ) = UpdateChecker(source, kv, currentCode = current, enabled = enabled, clock = clock, io = EmptyCoroutineContext)

    @Test fun newerVersionIsShown() = runTest {
        val c = checker(Source(v11))
        c.check()
        assertEquals(UpdateInfo(11, "1.1", apk, null), c.available.value)
    }

    @Test fun sameOrOlderVersionIsNotShown() = runTest {
        val c = checker(Source(v11), current = 11)
        c.check()
        assertNull(c.available.value)
        val older = checker(Source(v11), current = 12)
        older.check()
        assertNull(older.available.value)
    }

    @Test fun notFoundAndGarbageAreSilent() = runTest {
        val src = Source(null, "<html>404</html>", """{"versionCode":11,"versionName":"1.1","url":"https://evil.example/x.apk"}""")
        val clock = Clock()
        val c = checker(src, clock = clock)
        c.check()
        assertNull(c.available.value)
        clock.now += minute
        c.check(force = true)
        assertNull(c.available.value)
        clock.now += minute
        c.check(force = true)
        assertNull(c.available.value)
        assertEquals(3, src.calls)
    }

    @Test fun atMostOncePerDayUnlessForced() = runTest {
        val src = Source(null, v11, v12)
        val clock = Clock()
        val c = checker(src, clock = clock)
        c.check()
        clock.now += day - 1
        c.check()
        assertEquals("в пределах суток второй запрос не идёт", 1, src.calls)
        assertNull(c.available.value)

        c.check(force = true)
        assertEquals(2, src.calls)
        assertEquals(11, c.available.value?.versionCode)

        clock.now += day
        c.check()
        assertEquals(3, src.calls)
        assertEquals(12, c.available.value?.versionCode)
    }

    @Test fun clockGoingBackwardsDoesNotBlockForever() = runTest {
        val src = Source(null, v11)
        val clock = Clock(5 * day)
        val c = checker(src, clock = clock)
        c.check()
        clock.now = day
        c.check()
        assertEquals(2, src.calls)
        assertEquals(11, c.available.value?.versionCode)
    }

    @Test fun resultSurvivesRestart() = runTest {
        val kv = MemoryStore()
        val clock = Clock()
        checker(Source(v11), kv, clock = clock).check()

        val again = Source(v12)
        val restarted = checker(again, kv, clock = clock)
        assertEquals("сохранённое видно сразу, без сети", 11, restarted.available.value?.versionCode)
        restarted.check()
        assertEquals("сутки не прошли — не спрашиваем", 0, again.calls)
    }

    @Test fun afterUpdatingTheAppSavedOfferDisappears() = runTest {
        val kv = MemoryStore()
        val clock = Clock()
        checker(Source(v11), kv, clock = clock).check()
        // Поставили 1.1: то же сохранённое предложение больше не новее.
        assertNull(checker(Source(), kv, current = 11, clock = clock).available.value)
    }

    @Test fun failedCheckKeepsPreviousOffer() = runTest {
        val src = Source(v11, null)
        val clock = Clock()
        val c = checker(src, clock = clock)
        c.check()
        clock.now += minute
        c.check(force = true)
        assertEquals(11, c.available.value?.versionCode)
    }

    @Test fun fileWithSameVersionClearsOffer() = runTest {
        val src = Source(v12, v11)
        val clock = Clock()
        val c = checker(src, current = 11, clock = clock)
        c.check()
        assertEquals(12, c.available.value?.versionCode)
        clock.now += minute
        c.check(force = true)
        assertNull("на сайте снова текущая — предложение снимается", c.available.value)
    }

    @Test fun disabledForStoreBuildNeverAsks() = runTest {
        val kv = MemoryStore()
        checker(Source(v11), kv).check()
        val src = Source(v12)
        val c = checker(src, kv, enabled = false)
        c.check(force = true)
        assertEquals(0, src.calls)
        assertNull("в сборке магазина строки нет, даже если что-то сохранено", c.available.value)
    }

    @Test fun concurrentChecksMakeOneRequest() = runTest {
        val gate = CompletableDeferred<String?>()
        var calls = 0
        val c = checker(UpdateSource { calls++; gate.await() })
        val a = async { c.check(force = true) }
        val b = async { c.check(force = true) }
        runCurrent()
        gate.complete(v11)
        a.await(); b.await()
        assertEquals(1, calls)
        assertEquals(11, c.available.value?.versionCode)
    }

    @Test fun sourceExceptionIsSilent() = runTest {
        val c = checker(UpdateSource { throw IllegalStateException("сеть") })
        c.check(force = true)
        assertNull(c.available.value)
    }

    /** Настройки открыли-закрыли-открыли: принудительная проверка — не чаще раза в минуту. */
    @Test fun forcedCheckAtMostOncePerMinute() = runTest {
        val src = Source(null, v11, v12)
        val clock = Clock()
        val c = checker(src, clock = clock)
        c.check(force = true)
        clock.now += minute - 1
        c.check(force = true)
        assertEquals("в пределах минуты второй запрос не идёт", 1, src.calls)
        clock.now += 1
        c.check(force = true)
        assertEquals(2, src.calls)
        assertEquals(11, c.available.value?.versionCode)
    }

    /** StrictMode: настройки в check() — не на главном потоке, по умолчанию Dispatchers.IO. */
    @Test fun prefsAreTouchedOffMainThread() {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val src = File(dir!!, "jetbox/src/main/java/net/jetnet/jetbox/engine/UpdateChecker.kt").readText()
        org.junit.Assert.assertTrue(src.contains("private val io: CoroutineContext = Dispatchers.IO"))
        val check = src.substringAfter("suspend fun check(").substringBefore("private fun saved()")
        val uses = Regex("kv\\.(getString|putString)\\(").findAll(check).toList()
        assertEquals(3, uses.size)
        uses.forEach { m ->
            val lineStart = check.lastIndexOf('\n', m.range.first)
            val line = check.substring(lineStart + 1, check.indexOf('\n', m.range.first))
            org.junit.Assert.assertTrue("вне withContext(io): $line", line.contains("withContext(io)"))
        }
    }
}
