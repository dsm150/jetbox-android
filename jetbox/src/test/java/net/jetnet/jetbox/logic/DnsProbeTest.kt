package net.jetnet.jetbox.logic

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порт `JetBoxHomeProbeTests` (iOS) + замер на фейковом сокете (без реальной сети). */
class DnsProbeTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun queryIsAStandardRecursiveAQuestion() {
        val q = DnsProbe.buildQuery(0xBEEF)
        assertArrayEquals(bytes(0xBE, 0xEF, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0), q.copyOfRange(0, 12))
        // 07 'jet-net' 03 'net' 00
        assertArrayEquals(
            bytes(0x07, 0x6A, 0x65, 0x74, 0x2D, 0x6E, 0x65, 0x74, 0x03, 0x6E, 0x65, 0x74, 0x00),
            q.copyOfRange(12, 25),
        )
        // QTYPE=1 (A), QCLASS=1 (IN)
        assertArrayEquals(bytes(0x00, 0x01, 0x00, 0x01), q.copyOfRange(q.size - 4, q.size))
        assertEquals(29, q.size)
    }

    @Test fun queryIdIsFirstTwoBytes() {
        val q = DnsProbe.buildQuery(0x1234)
        assertEquals(0x12.toByte(), q[0])
        assertEquals(0x34.toByte(), q[1])
    }

    @Test fun onlyAResponseWithOurIdCounts() {
        val reply = DnsProbe.buildQuery(0x1234).also { it[2] = (it[2].toInt() or 0x80).toByte() } // QR: ответ
        assertTrue(DnsProbe.isAnswerFor(reply, reply.size, 0x1234))
        assertFalse(DnsProbe.isAnswerFor(reply, reply.size, 0x1235))
        val question = DnsProbe.buildQuery(0x1234)
        assertFalse(DnsProbe.isAnswerFor(question, question.size, 0x1234)) // QR=0
        assertFalse(DnsProbe.isAnswerFor(bytes(0x12, 0x34), 2, 0x1234)) // короче заголовка
    }

    @Test fun lengthLimitsTheResponse() {
        // Буфер приёма больше полученного: смотрим только первые `length` байт.
        val buf = ByteArray(512)
        val reply = DnsProbe.buildQuery(0x1234).also { it[2] = (it[2].toInt() or 0x80).toByte() }
        reply.copyInto(buf)
        assertTrue(DnsProbe.isAnswerFor(buf, reply.size, 0x1234))
        assertFalse(DnsProbe.isAnswerFor(buf, 8, 0x1234))
    }

    @Test fun anyRcodeFrom0To5CountsAsAliveServer() {
        for (rcode in 0..5) {
            val r = DnsProbe.buildQuery(0x0042).also { it[2] = 0x81.toByte(); it[3] = rcode.toByte() }
            assertTrue("rcode $rcode", DnsProbe.isAnswerFor(r, r.size, 0x0042))
        }
        val bad = DnsProbe.buildQuery(0x0042).also { it[2] = 0x81.toByte(); it[3] = 9 }
        assertFalse(DnsProbe.isAnswerFor(bad, bad.size, 0x0042))
    }

    // region measure

    /** Управляемые часы: время идёт только когда фейковый сокет «ждёт» ответ. */
    private class Clock { var nanos = 0L }

    /**
     * Фейковый сокет. Сценарий — по одному шагу на каждый receive: задержка мс (ответ на последний
     * запрос), null (таймаут), либо [Stale] — пакет с чужим id, [Boom] — IOException.
     */
    private class FakeSocket(private val clock: Clock, private val script: List<Any?>) :
        DatagramSocket(null as SocketAddress?) {
        object Stale
        object Boom

        var sent = 0
        var receives = 0
        var timeoutSet = -1
        var connectedTo: SocketAddress? = null
        var closed = false
        private var lastQuery: ByteArray? = null

        override fun connect(addr: SocketAddress?) { connectedTo = addr }
        override fun setSoTimeout(timeout: Int) { timeoutSet = timeout }
        override fun send(p: DatagramPacket) {
            sent++
            lastQuery = p.data.copyOfRange(p.offset, p.offset + p.length)
        }
        override fun receive(p: DatagramPacket) {
            when (val step = script[receives++]) {
                null -> {
                    clock.nanos += timeoutSet * 1_000_000L
                    throw SocketTimeoutException("fake timeout")
                }
                Boom -> throw IOException("fake io")
                Stale -> {
                    clock.nanos += 10_000_000L
                    val reply = lastQuery!!.copyOf().also {
                        it[0] = (it[0].toInt() xor 0x55).toByte()
                        it[2] = (it[2].toInt() or 0x80).toByte()
                    }
                    reply.copyInto(p.data)
                    p.length = reply.size
                }
                else -> {
                    clock.nanos += (step as Long) * 1_000_000L
                    val reply = lastQuery!!.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
                    reply.copyInto(p.data)
                    p.length = reply.size
                }
            }
        }
        override fun close() { closed = true }
    }

    private val server: InetAddress = InetAddress.getByAddress(byteArrayOf(100, 79, 1, 1))

    private fun measure(fake: FakeSocket, clock: Clock) =
        DnsProbe.measure(server, socketFactory = { fake }, nanoTime = { clock.nanos })

    @Test fun measureReturnsMinimumOfThreeAnswers() {
        val clock = Clock()
        val fake = FakeSocket(clock, listOf(30L, 12L, 20L))
        assertEquals(12L, measure(fake, clock))
        assertEquals(3, fake.sent)
        assertEquals(1500, fake.timeoutSet)
        assertEquals(InetSocketAddress(server, 53), fake.connectedTo)
        assertTrue(fake.closed)
    }

    @Test fun measureAllTimeoutsIsNull() {
        val clock = Clock()
        val fake = FakeSocket(clock, listOf(null, null, null))
        assertNull(measure(fake, clock))
        assertEquals(3, fake.sent)
        assertTrue(fake.closed)
    }

    @Test fun measureIgnoresTimedOutTries() {
        val clock = Clock()
        assertEquals(15L, measure(FakeSocket(clock, listOf(null, 15L, null)), clock))
    }

    @Test fun lateReplyWithForeignIdDoesNotEatTheTry() {
        // Запоздалый ответ на прошлый запрос приходит раньше нашего: пропускаем и ждём дальше.
        val clock = Clock()
        val fake = FakeSocket(clock, listOf(FakeSocket.Stale, 40L, 10L, 10L))
        // первая попытка: Stale (10 мс) + свой ответ (40 мс) = 50 мс; остальные по 10
        assertEquals(10L, measure(fake, clock))
        assertEquals(3, fake.sent)
    }

    @Test fun onlyStalePacketsUntilTimeoutGiveNull() {
        val clock = Clock()
        // Чужие пакеты, затем таймаут попытки: попытка пропускается, ответа нет.
        val fake = FakeSocket(clock, listOf(FakeSocket.Stale, null, FakeSocket.Stale, null, FakeSocket.Stale, null))
        assertNull(measure(fake, clock))
        assertEquals(3, fake.sent)
    }

    @Test fun connectThrowingSocketExceptionIsNull() {
        val clock = Clock()
        val socket = object : DatagramSocket(null as SocketAddress?) {
            override fun connect(addr: SocketAddress?) { throw SocketException("fake connect") }
            var closed = false
            override fun close() { closed = true }
        }
        assertNull(DnsProbe.measure(server, socketFactory = { socket }, nanoTime = { clock.nanos }))
        assertTrue(socket.closed)
    }

    @Test fun socketFactoryThrowingIsNull() {
        assertNull(DnsProbe.measure(server, socketFactory = { throw SocketException("no socket") }))
    }

    @Test fun ioErrorMidwayReturnsBestSoFar() {
        val clock = Clock()
        assertEquals(25L, measure(FakeSocket(clock, listOf(25L, FakeSocket.Boom)), clock))
    }

    // endregion
}
