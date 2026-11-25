package net.jetnet.jetbox.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jetnet.jetbox.link.JetBoxConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Вход по рабочей почте с кодом — API приложения в Кабинете `box.jet-net.net` (контракт — VPN_Box
 * `docs/product/06-cabinet.md`, «API приложения: вход по email»; саморегистрация по домену почты — спека
 * 2026-11-25-corporate-vpn-fabrika-design):
 *
 * * `POST login/request {"email","lang","platform"}` → всегда `202` (есть адрес или нет — ответ один); `429` — лимит;
 * * `POST login/verify {"email","code","device_name","platform"}` → `200 {setup_key, management_url, exit_node,
 *   site_name, connect_url, …}`; `401 {"error":"bad_code","attempts_left":N}`; `410` — код истёк или использован;
 *   `409 {"error":"device_limit"}` — на человеке уже предельное число устройств; `429` — лимит.
 *
 * Только вход по коду: ссылки из письма, токена приложения и удаления аккаунта в RU-приложении нет (слово
 * владельца 25.11.2026: «взять только часть саморегистрации из приложения»).
 *
 * Код и ключ — секреты: ни в журнал, ни в подробности отказа (там только код ответа).
 */
interface AppLoginApi {
    suspend fun request(email: String, lang: String): LoginRequestResult
    suspend fun verifyCode(email: String, code: String): LoginVerifyResult
}

sealed class LoginRequestResult {
    /** Принято: письмо ушло, если адрес известен Кабинету или подходит для саморегистрации (ответ одинаковый). */
    data object Accepted : LoginRequestResult()
    data object TooManyRequests : LoginRequestResult()
    /** Нет сети, Кабинет не ответил или ответил ошибкой. */
    data class Unavailable(val details: String) : LoginRequestResult()
}

sealed class LoginVerifyResult {
    /** Ссылка подключения `/connect#k=…` — дальше тот же путь, что у ссылки из Кабинета. */
    data class Success(val connectUrl: String) : LoginVerifyResult() {
        override fun toString(): String = "Success(…)"
    }
    /** `410`: код устарел, погашен или уже использован — нужен новый. */
    data object Expired : LoginVerifyResult()
    /** `409 device_limit`: на человеке уже предельное число устройств. */
    data object DeviceLimit : LoginVerifyResult()
    data object TooManyRequests : LoginVerifyResult()
    /** `401 bad_code`: неверный код; [attemptsLeft] — сколько попыток осталось (`null` — Кабинет не сказал). */
    data class BadCode(val attemptsLeft: Int?) : LoginVerifyResult()
    /** Сеть не дошла до Кабинета. */
    data class Unreachable(val details: String) : LoginVerifyResult()
    /** Кабинет ответил, но не так, как ждём (5xx, `409 not_available`, кривой ответ). */
    data class Failed(val details: String) : LoginVerifyResult()
}

/**
 * Ссылка подключения из ответа `login/verify`: `connect_url`, если Кабинет его прислал, иначе — собранная
 * из полей (`k`, `m`, `e`, `n`) ссылка того же вида, что даёт Кабинет. `null` — ключа нет ни там, ни там.
 * Проверку (формат ключа, маска management `*.jet-net.net`) делает дальше `JetBoxLink.parse` — общий путь со ссылкой.
 */
internal fun connectUrlFrom(json: JSONObject): String? {
    json.optString("connect_url").trim().takeIf { it.isNotEmpty() }?.let { return it }
    val key = json.optString("setup_key").trim().takeIf { it.isNotEmpty() } ?: return null
    val params = linkedMapOf("k" to key)
    json.optString("management_url").trim().takeIf { it.isNotEmpty() }?.let { params["m"] = it }
    json.optString("exit_node").trim().takeIf { it.isNotEmpty() }?.let { params["e"] = it }
    json.optString("site_name").trim().takeIf { it.isNotEmpty() }?.let { params["n"] = it }
    return "https://${JetBoxConfig.UNIVERSAL_LINK_HOST}${JetBoxConfig.UNIVERSAL_LINK_PATH}#" +
        params.entries.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
}

/** Всё, кроме unreserved RFC 3986, — `%XX` (пробел — `%20`: в нашей ссылке «+» остаётся плюсом). */
internal fun percentEncode(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') append(c)
        else append('%').append("%02X".format(b.toInt() and 0xFF))
    }
}

/**
 * Обычный HttpURLConnection: таймауты 15 с, без кэша, cookie и редиректов, ответ не больше [MAX_BYTES].
 * [deviceName] — имя телефона для списка устройств в Кабинете (`Производитель Модель`).
 */
class HttpAppLoginApi(
    private val baseUrl: String = JetBoxConfig.APP_API_BASE,
    private val userAgent: String,
    private val deviceName: () -> String,
) : AppLoginApi {

    override suspend fun request(email: String, lang: String): LoginRequestResult {
        val body = JSONObject().put("email", email).put("lang", lang).put("platform", PLATFORM)
        return when (val r = post("login/request", body)) {
            is Reply.NoNetwork -> LoginRequestResult.Unavailable(r.details)
            is Reply.Status -> when {
                r.code in 200..299 -> LoginRequestResult.Accepted
                r.code == 429 -> LoginRequestResult.TooManyRequests
                else -> LoginRequestResult.Unavailable("HTTP ${r.code}")
            }
        }
    }

    override suspend fun verifyCode(email: String, code: String): LoginVerifyResult {
        val body = JSONObject().put("email", email).put("code", code)
            .put("device_name", deviceName()).put("platform", PLATFORM)
        return when (val r = post("login/verify", body)) {
            is Reply.NoNetwork -> LoginVerifyResult.Unreachable(r.details)
            is Reply.Status -> when (r.code) {
                200 -> {
                    val json = try {
                        r.body?.let(::JSONObject)
                    } catch (e: JSONException) {
                        null
                    }
                    json?.let(::connectUrlFrom)?.let { LoginVerifyResult.Success(it) }
                        ?: LoginVerifyResult.Failed("HTTP 200: no connection data")
                }
                401 -> LoginVerifyResult.BadCode(
                    jsonOf(r.body)?.takeIf { it.has("attempts_left") }
                        ?.optInt("attempts_left", -1)?.takeIf { it >= 0 },
                )
                410 -> LoginVerifyResult.Expired
                // 409 бывает двух видов: предел устройств и «коробка перестала быть боевой» (not_available).
                409 -> if (jsonOf(r.body)?.optString("error") == ERROR_NOT_AVAILABLE) {
                    LoginVerifyResult.Failed("HTTP 409 $ERROR_NOT_AVAILABLE")
                } else {
                    LoginVerifyResult.DeviceLimit
                }
                429 -> LoginVerifyResult.TooManyRequests
                else -> LoginVerifyResult.Failed("HTTP ${r.code}")
            }
        }
    }

    private fun jsonOf(body: String?): JSONObject? = body?.let { runCatching { JSONObject(it) }.getOrNull() }

    private sealed class Reply {
        class Status(val code: Int, val body: String?) : Reply()
        class NoNetwork(val details: String) : Reply()
    }

    private suspend fun post(path: String, json: JSONObject): Reply = withContext(Dispatchers.IO) {
        val conn = try {
            URL("${baseUrl.trimEnd('/')}/$path").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext Reply.NoNetwork(e.javaClass.simpleName)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            // Без setFixedLengthStreamingMode: в потоковом режиме JDK при 401 не отдаёт тело (attempts_left).
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            // Тело нужно только у 200 (подключение), 401 (остаток попыток) и 409 (вид отказа).
            val body = when (code) {
                200 -> conn.inputStream.use(::readLimited)
                401, 409 -> conn.errorStream?.use(::readLimited)
                else -> null
            }
            Reply.Status(code, body)
        } catch (e: IOException) {
            // Текст исключения может содержать адрес, но не код (он только в теле запроса).
            Reply.NoNetwork(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } catch (e: RuntimeException) {
            Reply.NoNetwork(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val PLATFORM = "android"
        const val ERROR_NOT_AVAILABLE = "not_available"
        const val TIMEOUT_MS = 15_000
        const val MAX_BYTES = 64 * 1024
    }
}
