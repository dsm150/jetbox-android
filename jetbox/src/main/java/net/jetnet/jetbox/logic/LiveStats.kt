package net.jetnet.jetbox.logic

/** Снимок пира от ядра (адаптер над `binder.peersInfo()`, см. задачу про движок). */
data class PeerSnapshot(
    val ip: String,
    val fqdn: String,
    val connected: Boolean,
    val relayed: Boolean,
    val latencyMs: Long,
    val bytesRx: Long,
    val bytesTx: Long,
    val routes: List<String>,
)

/** Живые цифры дома для нижней панели. `null`/0 — «ещё неизвестно» (показываем прочерк). */
data class HomeStats(
    val relayed: Boolean?,
    val bytesRx: Long,
    val bytesTx: Long,
    val coreLatencyMs: Long?,
    val homeIp: String?,
)

/**
 * Порт чистой логики `JetBoxLiveStats.swift` (iOS): какой пир — JetBox, как читается пинг ядра,
 * как пишется время в сети. Часть с публикацией состояния (`ingest`/`probe`) переедет в движок.
 */
object LiveStats {
    private val EXIT_ROUTES = setOf("0.0.0.0/0", "::/0")

    /**
     * Какой пир — JetBox, по убыванию уверенности:
     * 1. раздаёт выход в интернет (`0.0.0.0/0`, `::/0`);
     * 2. иначе — раздаёт любой маршрут: домашнюю сеть при выключенном выходе (`x=0`, демо 22.11.2026 —
     *    маршрут `192.168.60.0/24` через коробку, выхода нет);
     * 3. иначе — единственный подключённый пир (у телефона кроме коробки никого).
     * Внутри ступени подключённый важнее простаивающего: при смене коробки старый пир какое-то время
     * ещё виден. Маршруты у пира — только активные (выбранные на телефоне), так что ступень 1 бывает
     * лишь при включённом выходе.
     */
    fun homePeer(peers: List<PeerSnapshot>): PeerSnapshot? {
        fun pick(candidates: List<PeerSnapshot>) = candidates.firstOrNull { it.connected } ?: candidates.firstOrNull()
        return pick(peers.filter { p -> p.routes.any { it in EXIT_ROUTES } })
            ?: pick(peers.filter { it.routes.isNotEmpty() })
            ?: peers.filter { it.connected }.singleOrNull()
    }

    /**
     * Дом ответил: пир-JetBox на связи и от него пришёл хоть один пакет (ответ на рукопожатие
     * WireGuard — тоже пакет). Статус ядра «подключено» приходит, как только есть management, а пир
     * коробки при этом может минуту-две висеть в установке соединения (находка AppDev 22.11.2026).
     * Коробку не опознать (несколько пиров без маршрутов) — годится любой ответивший пир; пиров ещё
     * нет — не ответил.
     */
    fun homeAnswered(peers: List<PeerSnapshot>): Boolean {
        fun answered(p: PeerSnapshot) = p.connected && p.bytesRx > 0
        return homePeer(peers)?.let(::answered) ?: peers.any(::answered)
    }

    fun stats(peers: List<PeerSnapshot>): HomeStats {
        val p = homePeer(peers) ?: return HomeStats(null, 0, 0, null, null)
        // Ядро меряет задержку лишь на прямом соединении; 0 — «ещё не мерило», а не ноль.
        return HomeStats(p.relayed, p.bytesRx, p.bytesTx, p.latencyMs.takeIf { it > 0 }, meshIp(p.ip))
    }

    /** Адрес пира бывает с маской: "100.64.0.10/16" → "100.64.0.10". */
    fun meshIp(ipWithMask: String): String = ipWithMask.substringBefore('/')

    /**
     * Пинг из строки длительности Go («12.3ms», «850µs», «1.2s») в мс. «0s» — ядро ещё не мерило:
     * null (прочерк), а не ноль. Составные формы вроде «1m2.5s» не разбираем — null, как в iOS.
     *
     * Оставлено для переноса iOS-тестов; движок берёт числовой LatencyMs из ядра.
     */
    fun parseLatencyMs(raw: String): Long? {
        val text = raw.trim()
        val units = listOf("ns" to 1e-6, "µs" to 1e-3, "us" to 1e-3, "ms" to 1.0, "s" to 1000.0)
        for ((suffix, toMs) in units) {
            if (text.endsWith(suffix)) {
                val value = text.dropLast(suffix.length).toDoubleOrNull() ?: return null
                val ms = value * toMs
                return if (ms > 0) maxOf(1L, Math.round(ms)) else null
            }
        }
        return null
    }

    /** «4:05» до часа, «1:04:05» после. */
    fun uptime(seconds: Long): String {
        val s = maxOf(0L, seconds)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
    }
}
