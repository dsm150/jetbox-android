package net.jetnet.jetbox.logic

/**
 * Маршрут из ядра в простом виде (из gomobile Network). Ядро Android склеивает IPv4/IPv6-пару
 * выхода в одну строку `"0.0.0.0/0, ::/0"` (client.go), поэтому [network] — список через запятую.
 */
data class RouteInfo(val name: String, val network: String, val selected: Boolean) {
    val isExitNode: Boolean
        get() = network.split(',').any { val n = it.trim(); n == "0.0.0.0/0" || n == "::/0" }
}

sealed class ExitOutcome {
    data class Selected(val name: String) : ExitOutcome()
    data class Ambiguous(val options: List<String>) : ExitOutcome()
    data object None : ExitOutcome()
}

/**
 * Порядок выбора выхода — как `resolveExitNode` (iOS): политики на management только
 * разрешают маршрут, выбирает его клиент. 1) имя из ссылки (`e=`, точное сравнение);
 * 2) единственный выход; 3) иначе спросить человека (0 выходов — None).
 */
object ExitNodeResolver {
    fun resolve(wanted: String?, routes: List<RouteInfo>): ExitOutcome {
        // Имена сетей в ядре уникальны (ключи map), пара v4/v6 приходит одной строкой.
        // Порядок вариантов — по алфавиту, как в iOS.
        val names = routes.filter { it.isExitNode }.map { it.name }.sorted()
        if (names.isEmpty()) return ExitOutcome.None
        if (wanted != null && wanted in names) return ExitOutcome.Selected(wanted)
        if (names.size == 1) return ExitOutcome.Selected(names.first())
        return ExitOutcome.Ambiguous(names)
    }
}
