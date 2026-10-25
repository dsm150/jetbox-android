package net.jetnet.jetbox.logic

import net.jetnet.jetbox.store.KeyValueStore

/**
 * Порт `JetBoxConnectionDefaults` (iOS). JetBox ведёт через дом весь интернет телефона; принудительный
 * ретранслятор апстрима (в апстриме `isConnectionForceRelayed` по умолчанию true) гнал бы всё видео
 * через наш сервер. Решение владельца 24.10.2026: по умолчанию — напрямую; NetBird сам уйдёт на
 * ретранслятор, если сеть не пускает. Ставим один раз: если клиент потом включит ретранслятор
 * в расширенных настройках, его выбор не трогаем.
 *
 * `upstream` — апстримовые SharedPreferences "netbird"; маркер «уже применили» — в нашем хранилище.
 */
object ConnectionDefaults {
    const val MARKER = "jetbox.directByDefaultApplied"
    const val UPSTREAM_FORCE_RELAY_KEY = "isConnectionForceRelayed"

    fun apply(ours: KeyValueStore, upstream: KeyValueStore) {
        if (ours.getBoolean(MARKER, false)) return
        upstream.putBoolean(UPSTREAM_FORCE_RELAY_KEY, false)
        ours.putBoolean(MARKER, true)
    }
}
