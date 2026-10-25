package net.jetnet.jetbox.logic

import net.jetnet.jetbox.store.KeyValueStore

/**
 * Доступ к расширенным настройкам — порт `JetBoxAdvancedAccess.swift` (iOS): семь быстрых нажатий
 * по строке версии, обратный отсчёт с четвёртого. Обычный клиент туда случайно не попадает.
 *
 * Два состояния, а не одно:
 *  * «открыто» ([isUnlocked]) — в настройках виден пункт «Расширенные настройки»;
 *  * «включено» ([isAdvancedMode]) — приложение показывает служебные экраны NetBird.
 * [lock] закрывает оба: чтобы вернуться, нужны новые семь нажатий.
 *
 * Счёт нажатий живёт в памяти (как и в iOS — обнуляется при уходе с экрана); в хранилище — только
 * два флага.
 */
class JetBoxAdvancedAccess(
    private val kv: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed class TapResult {
        /** Первые нажатия молчат. */
        data object Silent : TapResult()

        /** «Ещё N нажатий…» */
        data class Hint(val remaining: Int) : TapResult()

        /** Открыли (флаг [isUnlocked] уже записан). */
        data object Unlocked : TapResult()

        /** Уже открыто — считать нечего. */
        data object AlreadyOpen : TapResult()
    }

    /** Виден ли пункт «Расширенные настройки». */
    val isUnlocked: Boolean
        get() = kv.getBoolean(UNLOCKED_KEY, false)

    /** Показываются ли служебные экраны NetBird. */
    var isAdvancedMode: Boolean
        get() = kv.getBoolean(MODE_KEY, false)
        set(value) = kv.putBoolean(MODE_KEY, value)

    private var taps = 0
    private var lastTapAt: Long? = null

    fun registerTap(): TapResult {
        if (isUnlocked) {
            resetCounter()
            return TapResult.AlreadyOpen
        }
        val t = now()
        val last = lastTapAt
        // Ровно 3000 мс ещё в окне (в iOS сравнение строгое «больше»).
        if (last != null && t - last > RESET_INTERVAL_MS) taps = 0
        lastTapAt = t
        taps += 1

        if (taps >= TAPS_REQUIRED) {
            resetCounter()
            // Открыть доступ — служебные экраны при этом ещё не включаются.
            kv.putBoolean(UNLOCKED_KEY, true)
            return TapResult.Unlocked
        }
        if (taps > HINT_AFTER_TAPS) return TapResult.Hint(TAPS_REQUIRED - taps)
        return TapResult.Silent
    }

    /** Выход: снять и режим, и доступ — иначе «выключил и снова случайно нажал» возвращает туда же. */
    fun lock() {
        kv.putBoolean(MODE_KEY, false)
        kv.putBoolean(UNLOCKED_KEY, false)
        resetCounter()
    }

    /** Сбросить счёт нажатий — экран настроек зовёт при уходе (как iOS `@State`). */
    fun resetCounter() {
        taps = 0
        lastTapAt = null
    }

    companion object {
        const val UNLOCKED_KEY = "jetbox.advancedUnlocked"
        const val MODE_KEY = "jetbox.advancedMode"
        const val TAPS_REQUIRED = 7
        const val HINT_AFTER_TAPS = 3
        const val RESET_INTERVAL_MS = 3_000L
    }
}
