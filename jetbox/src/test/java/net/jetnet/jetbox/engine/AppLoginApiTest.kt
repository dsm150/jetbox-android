package net.jetnet.jetbox.engine

import kotlinx.coroutines.runBlocking
import net.jetnet.jetbox.link.JetBoxLink
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Клиент входа по рабочей почте с кодом против локального сервера на сокете (RU-Кабинет ещё не задеплоен — мок). */
class AppLoginApiTest {
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val paths = mutableListOf<String>()
    private val bodies = mutableListOf<JSONObject>()
    private val auth = mutableListOf<String?>()
    private val key = "11111111-2222-4333-8444-555555555555"

    @After fun stop() = server.close()

    private fun serve(status: String, body: String = "") {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine()
                    var length = 0
                    var authorization: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                        if (line.lowercase().startsWith("authorization:")) authorization = line.substringAfter(':').trim()
                    }
                    synchronized(paths) { auth += authorization }
                    val buf = CharArray(length)
                    var read = 0
                    while (read < length) read += input.read(buf, read, length - read)
                    synchronized(paths) {
                        paths += requestLine.split(' ')[0] + " " + requestLine.split(' ')[1]
                        bodies += JSONObject(String(buf))
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    private fun api() = HttpAppLoginApi(
        baseUrl = "http://127.0.0.1:${server.localPort}/api/app/v1/",
        userAgent = "JetBox-Android/test",
        deviceName = { "Google Pixel 8" },
    )

    @Test fun requestSendsEmailLangPlatform() = runBlocking {
        serve("202 Accepted", """{"ok":true}""")
        assertEquals(LoginRequestResult.Accepted, api().request("user@example.com", "ru"))
        assertEquals("POST /api/app/v1/login/request", paths.single())
        val b = bodies.single()
        assertEquals("user@example.com", b.getString("email"))
        assertEquals("ru", b.getString("lang"))
        assertEquals("android", b.getString("platform"))
    }

    @Test fun requestLimit() = runBlocking {
        serve("429 Too Many Requests")
        assertEquals(LoginRequestResult.TooManyRequests, api().request("user@example.com", "ru"))
    }

    @Test fun requestServerError() = runBlocking {
        serve("500 Internal Server Error")
        assertEquals(LoginRequestResult.Unavailable("HTTP 500"), api().request("user@example.com", "ru"))
    }

    @Test fun requestBadEmail() = runBlocking {
        serve("422 Unprocessable Entity", """{"error":"bad_request"}""")
        assertEquals(LoginRequestResult.BadEmail, api().request("user@example", "ru"))
    }

    @Test fun requestNoServer() = runBlocking {
        server.close()
        assertTrue(api().request("user@example.com", "ru") is LoginRequestResult.Unavailable)
    }

    /** Ответ verify RU-Кабинета (06-cabinet.md §2а): connect_url с `p`, `b` и подписью `s` — берётся как есть. */
    @Test fun verifyCodeReturnsConnectUrl() = runBlocking {
        val link = "https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=sv-fabrika-exit" +
            "&n=Fabrika&p=android&b=100.64.0.7&s=AAAA"
        serve(
            "200 OK",
            JSONObject().put("setup_key", key).put("management_url", "https://netbird.jet-net.net")
                .put("exit_node", "sv-fabrika-exit").put("site_name", "Fabrika").put("device_name", "Google Pixel 8")
                .put("app_token", "jbd_secret").put("connect_url", link).put("default_exit_off", false)
                .put("box_ip", "100.64.0.7").toString(),
        )
        val r = api().verifyCode("user@fabrika-mfc.ru", "012345")
        assertEquals(LoginVerifyResult.Success(link), r)
        assertFalse("ключ не в toString", r.toString().contains(key))
        assertEquals("POST /api/app/v1/login/verify", paths.single())
        val b = bodies.single()
        assertEquals("user@fabrika-mfc.ru", b.getString("email"))
        assertEquals("012345", b.getString("code"))
        assertFalse(b.has("token"))
        assertEquals("Google Pixel 8", b.getString("device_name"))
        assertEquals("android", b.getString("platform"))
    }

    @Test fun verifyCodeStatuses() = runBlocking {
        val email = "user@fabrika-mfc.ru"
        serve("401 Unauthorized", """{"error":"bad_code","attempts_left":3}""")
        assertEquals(LoginVerifyResult.BadCode(3), api().verifyCode(email, "000000"))
        serve("401 Unauthorized", """{"error":"bad_code"}""")
        assertEquals(LoginVerifyResult.BadCode(null), api().verifyCode(email, "000000"))
        serve("401 Unauthorized")
        assertEquals(LoginVerifyResult.BadCode(null), api().verifyCode(email, "000000"))
        serve("410 Gone", """{"error":"expired"}""")
        assertEquals(LoginVerifyResult.Expired, api().verifyCode(email, "000000"))
        serve("410 Gone", """{"error":"expired","reason":"used"}""")
        assertEquals(LoginVerifyResult.Used, api().verifyCode(email, "000000"))
        serve("410 Gone")
        assertEquals(LoginVerifyResult.Expired, api().verifyCode(email, "000000"))
        serve("409 Conflict", """{"error":"device_limit"}""")
        assertEquals(LoginVerifyResult.DeviceLimit, api().verifyCode(email, "000000"))
        serve("409 Conflict", """{"error":"not_available"}""")
        assertEquals(LoginVerifyResult.NotAvailable, api().verifyCode(email, "000000"))
        serve("429 Too Many Requests", """{"error":"rate_limited","retry_after":60}""")
        assertEquals(LoginVerifyResult.TooManyRequests, api().verifyCode(email, "000000"))
        serve("502 Bad Gateway", """{"error":"unavailable"}""")
        assertEquals(LoginVerifyResult.TryAgain, api().verifyCode(email, "000000"))
        serve("500 Internal Server Error")
        assertEquals(LoginVerifyResult.Failed("HTTP 500"), api().verifyCode(email, "000000"))
        serve("200 OK", "not json")
        assertTrue(api().verifyCode(email, "000000") is LoginVerifyResult.Failed)
        serve("200 OK", "{}")
        assertTrue(api().verifyCode(email, "000000") is LoginVerifyResult.Failed)
    }

    @Test fun verifyCodeNoServer() = runBlocking {
        server.close()
        assertTrue(api().verifyCode("user@fabrika-mfc.ru", "000000") is LoginVerifyResult.Unreachable)
    }

    /** Без connect_url — ссылка собирается из полей и разбирается, как ссылка Кабинета (маска *.jet-net.net). */
    @Test fun connectUrlBuiltFromFieldsParsesLikeCabinetLink() {
        val url = connectUrlFrom(
            JSONObject()
                .put("setup_key", key)
                .put("management_url", "https://netbird.jet-net.net")
                .put("exit_node", "sv-fabrika-exit")
                .put("site_name", "Fabrika & Co + 1"),
        )!!
        val p = (JetBoxLink.parse(url, signed = false) as JetBoxLink.Outcome.Payload).payload
        assertEquals(key, p.setupKey)
        assertEquals("https://netbird.jet-net.net", p.managementURL)
        assertEquals("sv-fabrika-exit", p.exitNodeName)
        assertEquals("Fabrika & Co + 1", p.boxName)
        assertTrue(p.enablesDefaultExit)
    }

    @Test fun connectUrlPreferredAndMissingKey() {
        assertEquals("https://box.jet-net.net/connect#k=x", connectUrlFrom(JSONObject().put("connect_url", "https://box.jet-net.net/connect#k=x").put("setup_key", key)))
        assertNull(connectUrlFrom(JSONObject().put("management_url", "https://netbird.jet-net.net")))
    }

    @Test fun percentEncodeKeepsUnreserved() {
        assertEquals("a-b_c.d~e%20%2B%26%D0%94", percentEncode("a-b_c.d~e +&Д"))
    }

    /** Адрес API RU-сборки — один, Кабинет box.jet-net.net, без discover. */
    @Test fun apiBaseIsRuCabinet() {
        assertEquals("https://box.jet-net.net/api/app/v1", net.jetnet.jetbox.link.JetBoxConfig.APP_API_BASE)
    }
}
