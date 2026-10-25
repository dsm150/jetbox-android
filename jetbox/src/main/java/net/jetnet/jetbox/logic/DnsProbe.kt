package net.jetnet.jetbox.logic

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Random

/**
 * Порт `JetBoxHomeProbe` (iOS): «до дома» — время ответа DNS на самом JetBox через туннель.
 *
 * Почему не цифра от ядра: NetBird меряет задержку один раз, при установке прямого соединения,
 * и никогда — через ретранслятор. Почему DNS, а не пинг: DNS JetBox клиенту открыт всегда
 * (через него работает блокировка рекламы), а ICMP от устройства к JetBox политики не пропускают.
 * Берём минимум из трёх запросов подряд: первый может уйти к внешнему резолверу, повторы
 * отвечаются из кэша — это и есть дорога до дома.
 */
object DnsProbe {
    const val PROBE_NAME = "jet-net.net"
    private const val PORT = 53
    private val random = Random()

    /** Рекурсивный запрос A-записи — стандартный DNS-вопрос. */
    fun buildQuery(id: Int, name: String = PROBE_NAME): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF)
        out.write(id and 0xFF)
        // флаги 0x0100 (RD=1), QDCOUNT=1, остальные счётчики 0
        for (b in intArrayOf(0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) out.write(b)
        for (label in name.split('.').filter { it.isNotEmpty() }) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        // конец имени, QTYPE=1 (A), QCLASS=1 (IN)
        for (b in intArrayOf(0x00, 0x00, 0x01, 0x00, 0x01)) out.write(b)
        return out.toByteArray()
    }

    /**
     * Ответ на наш вопрос: тот же ID и бит QR. Код ответа 0..5 — любой годится: нам нужно время,
     * а не запись (сервер жив). Длина — сколько байт реально получено.
     */
    fun isAnswerFor(response: ByteArray, length: Int, id: Int): Boolean {
        if (length < 12 || response.size < 12) return false
        val b0 = response[0].toInt() and 0xFF
        val b1 = response[1].toInt() and 0xFF
        val flags1 = response[2].toInt() and 0xFF
        val rcode = response[3].toInt() and 0x0F
        return b0 == (id shr 8 and 0xFF) && b1 == (id and 0xFF) && (flags1 and 0x80) != 0 && rcode in 0..5
    }

    /**
     * Наименьшее время ответа (мс, не меньше 1) из [tries] запросов либо null, если не ответил ни один.
     * Блокирующий вызов — звать на фоновом потоке. Не ответивший за [timeoutMs] запрос пропускается;
     * пакеты с чужим id (запоздалые ответы) отбрасываются и попытку не съедают. Любая ошибка сокета
     * (создание, connect, send, receive) прекращает замер и возвращает лучшее на тот момент — не бросает.
     * [nanoTime] — часы (подменяются в тестах).
     */
    fun measure(
        server: InetAddress,
        tries: Int = 3,
        timeoutMs: Int = 1500,
        socketFactory: () -> DatagramSocket = { DatagramSocket() },
        nanoTime: () -> Long = System::nanoTime,
    ): Long? {
        var best: Long? = null
        var socket: DatagramSocket? = null
        try {
            val sock = socketFactory()
            socket = sock
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(server, PORT))
            val buf = ByteArray(512)
            repeat(tries) {
                val id = 1 + random.nextInt(0xFFFF)
                val query = buildQuery(id)
                val started = nanoTime()
                val deadline = started + timeoutMs * 1_000_000L
                sock.send(DatagramPacket(query, query.size))
                try {
                    while (true) {
                        val remainingMs = (deadline - nanoTime() + 999_999L) / 1_000_000L
                        if (remainingMs <= 0) break
                        sock.soTimeout = remainingMs.toInt()
                        val reply = DatagramPacket(buf, buf.size)
                        sock.receive(reply)
                        if (isAnswerFor(buf, reply.length, id)) {
                            val ms = maxOf(1L, Math.round((nanoTime() - started) / 1_000_000.0))
                            best = minOf(best ?: ms, ms)
                            break
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // этот запрос без ответа — пробуем следующий
                }
            }
        } catch (e: IOException) {
            // SocketException — тоже IOException: возвращаем, что успели намерить
        } finally {
            socket?.close()
        }
        return best
    }
}
