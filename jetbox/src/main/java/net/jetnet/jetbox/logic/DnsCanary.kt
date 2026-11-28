package net.jetnet.jetbox.logic

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Random

/** Итог проверки «DNS мимо JetBox» (спека VPN_Box 2026-11-28-app-help-and-dns-check, §3). */
enum class DnsCheck {
    /** Не проверяли: не «Под защитой» или дом не на связи. */
    NOT_CHECKED,

    /** Системный резолвер получил метку коробки — DNS идёт через JetBox. */
    THROUGH,

    /** Системный резолвер ответил иначе (в т.ч. NXDOMAIN) — DNS мимо JetBox: капсула и «Как исправить». */
    BYPASS,

    /** Системный резолвер не ответил — ничего не показываем, в диагностику — «нет ответа». */
    NO_ANSWER,

    /** Коробка канарейку не знает (старая) — проверку не делаем, ничего не показываем. */
    BOX_UNAWARE,
}

/** Ответ системного резолвера: адреса, «имени нет» (NXDOMAIN и т.п.) или тишина. */
sealed class SystemAnswer {
    data class Addresses(val ips: List<String>) : SystemAnswer()
    data object NotFound : SystemAnswer()
    data object NoAnswer : SystemAnswer()
}

/**
 * Проба канарейкой. Коробка отвечает на [name] и его поддомены меткой [MARK] (A, TEST-NET-1); публичных записей нет —
 * любой не-коробочный резолвер даст NXDOMAIN или иное. Два запроса: (а) прямо в DNS дома по mesh-адресу —
 * [boxQuery]; (б) системным резолвером, как браузер — [systemResolve]. Имя сначала случайное `<nonce>.<name>` (обход
 * кэша DNS ОС), при пустом ответе коробки — точное.
 */
class DnsCanary(
    private val name: String,
    private val boxQuery: suspend (meshIp: String, name: String) -> List<String>?,
    private val systemResolve: suspend (name: String) -> SystemAnswer,
    private val nonce: () -> String = { "c" + java.lang.Long.toHexString(Random().nextLong() and Long.MAX_VALUE).take(10) },
) {
    suspend fun check(meshIp: String): DnsCheck {
        var asked = "${nonce()}.$name"
        val first = boxQuery(meshIp, asked) ?: return DnsCheck.BOX_UNAWARE
        if (MARK !in first) {
            // Пусто на поддомене — коробка, может быть, знает только точное имя.
            if (first.isNotEmpty()) return DnsCheck.BOX_UNAWARE
            asked = name
            val exact = boxQuery(meshIp, asked) ?: return DnsCheck.BOX_UNAWARE
            if (MARK !in exact) return DnsCheck.BOX_UNAWARE
        }
        return when (val sys = systemResolve(asked)) {
            is SystemAnswer.Addresses -> if (MARK in sys.ips) DnsCheck.THROUGH else DnsCheck.BYPASS
            SystemAnswer.NotFound -> DnsCheck.BYPASS
            SystemAnswer.NoAnswer -> DnsCheck.NO_ANSWER
        }
    }

    companion object {
        /** Адрес-метка коробки. */
        const val MARK = "192.0.2.53"
        private const val PORT = 53
        private val random = Random()

        /**
         * A-записи из ответа на наш запрос [id]: список адресов (пустой — NXDOMAIN или без A), `null` — не ответ на
         * наш вопрос или битый пакет.
         */
        fun parseA(response: ByteArray, length: Int, id: Int): List<String>? {
            if (length < 12) return null
            fun u8(i: Int) = response[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            if (u16(0) != id || (u8(2) and 0x80) == 0) return null
            val qd = u16(4)
            val an = u16(6)
            var p = 12
            fun skipName(): Boolean {
                while (p < length) {
                    val len = u8(p)
                    when {
                        len == 0 -> { p += 1; return true }
                        len and 0xC0 == 0xC0 -> { p += 2; return true }
                        else -> p += 1 + len
                    }
                }
                return false
            }
            repeat(qd) { if (!skipName()) return null; p += 4 }
            val out = mutableListOf<String>()
            repeat(an) {
                if (!skipName() || p + 10 > length) return out
                val type = u16(p)
                val rdLen = u16(p + 8)
                p += 10
                if (p + rdLen > length) return out
                if (type == 1 && rdLen == 4) out += "${u8(p)}.${u8(p + 1)}.${u8(p + 2)}.${u8(p + 3)}"
                p += rdLen
            }
            return out
        }

        /** Один A-запрос [name] прямо к DNS [server] (UDP 53). Блокирующий — звать на фоновом потоке. */
        fun queryA(
            server: InetAddress,
            name: String,
            timeoutMs: Int = 2000,
            socketFactory: () -> DatagramSocket = { DatagramSocket() },
        ): List<String>? = runCatching {
            socketFactory().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(server, PORT))
                val id = 1 + random.nextInt(0xFFFF)
                val query = DnsProbe.buildQuery(id, name)
                sock.send(DatagramPacket(query, query.size))
                val buf = ByteArray(1500)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                while (true) {
                    val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (left <= 0) return@use null
                    sock.soTimeout = left
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        return@use null
                    }
                    parseA(buf, packet.length, id)?.let { return@use it }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        }.getOrNull()
    }
}
