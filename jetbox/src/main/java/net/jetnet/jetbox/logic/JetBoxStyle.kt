package net.jetnet.jetbox.logic

import net.jetnet.jetbox.store.KeyValueStore

/**
 * Стиль оформления — порт `JetBoxStyle` (iOS, 20.11.2026): «техно» (стекло, синий щит — вид до
 * 20.11.2026) и «простое» (белый фон, большой круг, фиолетовый акцент — макет Алексея). Меняется
 * только вид: подключение, замена подключения, выходы, виджет и плитка работают одинаково.
 *
 * Хранится в настройках JetBox (`JetBoxStore.PREFS_NAME`, ключ [KEY] — тот же, что на iOS): его
 * читают и приложение, и виджет. Значения — как на iOS (`simple` / `techno`).
 */
enum class JetBoxStyle(val raw: String) {
    SIMPLE("simple"),
    TECHNO("techno");

    companion object {
        const val KEY = "jetbox.style"

        /** По умолчанию — прежний вид; простой включается в настройках (решение владельца 20.11.2026). */
        val FALLBACK = TECHNO

        /** Неизвестное или пустое значение — [FALLBACK]. */
        fun of(raw: String?): JetBoxStyle = entries.firstOrNull { it.raw == raw } ?: FALLBACK

        fun read(kv: KeyValueStore): JetBoxStyle = of(kv.getString(KEY))

        fun write(kv: KeyValueStore, style: JetBoxStyle) = kv.putString(KEY, style.raw)
    }
}
