package net.jetnet.jetbox.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import net.jetnet.jetbox.logic.DnsProbe
import java.net.InetAddress

/** Один замер «до дома»: мс до ответа DNS на JetBox или `null` — не ответил. */
fun interface HomeProbe {
    suspend fun measure(meshIp: String): Long?
}

/** Боевой замер: [DnsProbe] на `Dispatchers.IO`. Адрес — литерал IP, поиска имени нет. */
object DnsHomeProbe : HomeProbe {
    override suspend fun measure(meshIp: String): Long? = withContext(Dispatchers.IO) {
        runCatching { DnsProbe.measure(InetAddress.getByName(meshIp)) }.getOrNull()
    }
}

/**
 * «До дома» — порт связки `JetBoxLiveStats.probe` + `JetBoxHomeProbe` (iOS): пока экран виден и
 * туннель поднят, раз в [intervalMs] меряем DNS на JetBox по его mesh-IP.
 *
 * Когда мерить, решает вызывающий: [run] живёт, пока экран в `STARTED`, а поток адреса отдаёт
 * `null`, пока туннель не CONNECTED или JetBox не на связи. При `null` прежний замер стирается
 * (как `probedLatencyMs = nil` в iOS) — тогда на панели запасная цифра ядра или «—».
 */
class HomeProbeRunner(
    private val probe: HomeProbe = DnsHomeProbe,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val _latencyMs = MutableStateFlow<Long?>(null)

    /** Последний замер; `null` — не мерили или JetBox не ответил. */
    val latencyMs: StateFlow<Long?> = _latencyMs

    /** Меряет, пока не отменят; смена адреса начинает замеры заново. */
    suspend fun run(meshIp: Flow<String?>) {
        meshIp.distinctUntilChanged().collectLatest { ip ->
            if (ip == null) {
                _latencyMs.value = null
                return@collectLatest
            }
            while (true) {
                _latencyMs.value = probe.measure(ip)
                delay(intervalMs)
            }
        }
    }

    companion object {
        /** Как опрос расширения в iOS (раз в 10 с). */
        const val INTERVAL_MS = 10_000L
    }
}
