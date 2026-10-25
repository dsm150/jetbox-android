package net.jetnet.jetbox.engine

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Настоящий HttpURLConnection против локального сервера на сокете: заголовки, 404, редирект, большой
 * ответ. Файла `version.json` на сайте пока нет — 404 должен проходить молча.
 */
class HttpUpdateSourceTest {
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    /** Заголовки запросов (имя в нижнем регистре -> значение). */
    private val requests = mutableListOf<Map<String, String>>()

    @After fun stop() = server.close()

    private fun serve(status: String, body: ByteArray, extra: String = "") {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    reader.readLine() // строка запроса
                    val h = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    synchronized(requests) { requests += h }
                    val head = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n"
                    s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
                }
            }
        }
    }

    private fun source(port: Int = server.localPort) = HttpUpdateSource(
        URL("http://127.0.0.1:$port/android/version.json"),
        userAgent = "JetBox-Android/1.0",
    )

    @Test fun okReturnsBodyWithPlainHeaders() = runTest {
        serve("200 OK", """{"versionCode":11}""".toByteArray())
        assertEquals("""{"versionCode":11}""", source().fetch())
        val h = synchronized(requests) { requests.single() }
        assertEquals("JetBox-Android/1.0", h["user-agent"])
        assertEquals("no-cache", h["cache-control"])
        assertNull("никаких cookie", h["cookie"])
        assertNull("никаких идентификаторов", h["authorization"])
        assertTrue("только служебные заголовки: ${h.keys}", h.keys.all { it in ALLOWED })
    }

    @Test fun notFoundIsNull() = runTest {
        serve("404 Not Found", "<html>404 Not Found</html>".toByteArray())
        assertNull(source().fetch())
    }

    @Test fun serverErrorIsNull() = runTest {
        serve("500 Internal Server Error", ByteArray(0))
        assertNull(source().fetch())
    }

    @Test fun redirectIsNotFollowed() = runTest {
        serve("302 Found", ByteArray(0), "Location: http://127.0.0.1:1/elsewhere\r\n")
        assertNull(source().fetch())
    }

    @Test fun oversizedBodyIsNull() = runTest {
        serve("200 OK", ByteArray(HttpUpdateSource.MAX_BYTES + 1) { 'a'.code.toByte() })
        assertNull(source().fetch())
    }

    @Test fun refusedConnectionIsNull() = runTest {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(source(port).fetch())
    }

    private companion object {
        val ALLOWED = setOf("user-agent", "cache-control", "pragma", "accept", "host", "connection", "accept-encoding")
    }
}
