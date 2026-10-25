package net.jetnet.jetbox.logic

import net.jetnet.jetbox.logic.JetBoxAdvancedAccess.TapResult
import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Семь нажатий» и хранение состояния доступа к расширенным настройкам —
 * порт `JetBoxAdvancedAccessTests.swift` (iOS, 7 + 5 тестов). Тест про App Group и миграцию
 * флага из `UserDefaults.standard` в Android неприменим: вместо него — проверка ключей хранилища
 * и того, что состояние переживает пересоздание объекта.
 */
class JetBoxAdvancedAccessTest {
    private val t0 = 1_000_000L
    private var clock = t0
    private val kv = MemoryStore()

    private fun access(store: MemoryStore = kv) = JetBoxAdvancedAccess(store) { clock }

    /** Быстрая серия нажатий: каждое на 300 мс позже предыдущего. */
    private fun fastTaps(a: JetBoxAdvancedAccess, count: Int): List<TapResult> {
        val out = mutableListOf<TapResult>()
        for (i in 0 until count) {
            clock = t0 + i * 300L
            out += a.registerTap()
        }
        return out
    }

    // region счётчик

    @Test fun firstThreeTapsAreSilent() {
        assertEquals(listOf(TapResult.Silent, TapResult.Silent, TapResult.Silent), fastTaps(access(), 3))
    }

    @Test fun countdownStartsOnFourthTap() {
        val out = fastTaps(access(), 6)
        assertEquals(listOf(TapResult.Hint(3), TapResult.Hint(2), TapResult.Hint(1)), out.takeLast(3))
    }

    @Test fun seventhTapUnlocks() {
        val out = fastTaps(access(), 7)
        assertEquals(TapResult.Unlocked, out.last())
        assertEquals(1, out.count { it == TapResult.Unlocked })
    }

    @Test fun counterResetsAfterUnlock() {
        // После открытия счёт начинается с нуля: следующее нажатие — не восьмое.
        val a = access()
        fastTaps(a, 7)
        a.lock()
        clock = t0 + 10_000
        assertEquals(TapResult.Silent, a.registerTap())
    }

    @Test fun resetCounterStartsCountFromZero() {
        // Экран настроек сбрасывает счёт при уходе (как iOS @State).
        val a = access()
        fastTaps(a, 6)
        a.resetCounter()
        clock = t0 + 2_000
        assertEquals(TapResult.Silent, a.registerTap())
        assertFalse(a.isUnlocked)
    }

    @Test fun pauseResetsTheCount() {
        val a = access()
        fastTaps(a, 6)
        // Пауза больше окна — считаем заново, седьмое нажатие после паузы уже не открывает.
        clock = t0 + 60_000
        assertEquals(TapResult.Silent, a.registerTap())
        assertFalse(a.isUnlocked)
        // и счёт действительно пошёл с единицы: ещё три тихих нажатия, затем подсказка
        clock += 300; assertEquals(TapResult.Silent, a.registerTap())
        clock += 300; assertEquals(TapResult.Silent, a.registerTap())
        clock += 300; assertEquals(TapResult.Hint(3), a.registerTap())
    }

    @Test fun tapsExactlyAtResetIntervalStillCount() {
        val a = access()
        var last: TapResult = TapResult.Silent
        for (i in 0 until 7) {
            clock = t0 + i * 3_000L
            last = a.registerTap()
        }
        assertEquals(TapResult.Unlocked, last)
    }

    @Test fun alreadyUnlockedIsReportedAndCountsNothing() {
        val a = access()
        fastTaps(a, 7)
        clock = t0 + 10_000
        assertEquals(TapResult.AlreadyOpen, a.registerTap())
        assertEquals(TapResult.AlreadyOpen, a.registerTap())
    }

    // endregion

    // region хранение

    @Test fun defaultIsLocked() {
        val a = access()
        assertFalse(a.isUnlocked)
        assertFalse(a.isAdvancedMode)
    }

    @Test fun stateLivesInStoreUnderTheAgreedKeys() {
        fastTaps(access(), 7)
        access().isAdvancedMode = true
        assertTrue(kv.getBoolean("jetbox.advancedUnlocked", false))
        assertTrue(kv.getBoolean("jetbox.advancedMode", false))
        // новый объект поверх того же хранилища видит то же состояние (переживает перезапуск)
        val again = access()
        assertTrue(again.isUnlocked)
        assertTrue(again.isAdvancedMode)
    }

    @Test fun unlockDoesNotTurnTheModeOn() {
        // Семь нажатий только показывают пункт меню; служебные экраны включает сам клиент отдельно.
        val a = access()
        fastTaps(a, 7)
        assertTrue(a.isUnlocked)
        assertFalse(a.isAdvancedMode)
    }

    @Test fun lockClosesBothModeAndAccess() {
        val a = access()
        fastTaps(a, 7)
        a.isAdvancedMode = true

        a.lock()

        assertFalse(a.isAdvancedMode)
        assertFalse("после выключения пункт должен исчезнуть — иначе туда возвращаются одним нажатием", a.isUnlocked)
    }

    @Test fun afterLockSevenNewTapsAreNeeded() {
        val a = access()
        fastTaps(a, 7)
        a.lock()
        val out = fastTaps(a, 7)
        assertEquals(TapResult.Silent, out.first())
        assertEquals(TapResult.Unlocked, out.last())
    }

    // endregion
}
