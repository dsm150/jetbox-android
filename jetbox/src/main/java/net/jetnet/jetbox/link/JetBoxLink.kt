package net.jetnet.jetbox.link

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Разбор ссылки подключения из Кабинета — порт `JetBoxLink.swift` (iOS).
 * Контракт: LINK-CONTRACT.md. Чистый JVM, без `android.net.Uri`.
 *
 * Ключ и параметры живут во ФРАГМЕНТЕ (после `#`): он не уходит на сервер при открытии ссылки.
 */
data class JetBoxLinkPayload(
    /** Setup key: 36 символов, валидный UUID. Целиком нигде не показывается. */
    val setupKey: String,
    /** Адрес management-сервера, всегда https. */
    val managementURL: String,
    /** Network ID маршрута-выхода коробки, если прислан. */
    val exitNodeName: String?,
    /** Человекочитаемое имя коробки, если прислано. */
    val boxName: String?,
) {
    /** Маска ключа для экрана и логов: видны только последние 4 символа. */
    val maskedKey: String
        get() = maskSecret(setupKey)

    /** Что показать клиенту как имя его коробки. */
    val displayName: String
        get() = boxName?.takeIf { it.isNotEmpty() }
            ?: exitNodeName?.takeIf { it.isNotEmpty() }
            ?: hostOf(managementURL)

    /** Признак «та же коробка»: host(management) в нижнем регистре + выход. */
    val boxIdentity: String
        get() = boxIdentityOf(managementURL, exitNodeName)

    // Ключ не должен попасть в логи через toString().
    override fun toString(): String =
        "JetBoxLinkPayload(setupKey=$maskedKey, managementURL=$managementURL, exitNodeName=$exitNodeName, boxName=$boxName)"
}

/** Единая маска секрета для экрана и логов: видны только последние 4 символа. */
fun maskSecret(secret: String): String =
    if (secret.length <= 4) "••••" else "•".repeat(8) + "…" + secret.takeLast(4)

internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/** Единая формула «та же коробка» для ссылки и сохранённого JetBox: иначе своя ссылка спросит «переключить?». */
internal fun boxIdentityOf(managementURL: String, exitNodeName: String?): String =
    hostOf(managementURL).lowercase(Locale.ROOT) + "|" + (exitNodeName ?: "")

object JetBoxLink {
    sealed class Outcome {
        /** Ссылка наша и разобрана. */
        data class Payload(val payload: JetBoxLinkPayload) : Outcome()

        /** Ссылка наша, но испорчена (кривой ключ, кривой management). */
        data object Broken : Outcome()

        /** Не наша ссылка — отдаём дальше по цепочке обработчиков. */
        data object NotOurs : Outcome()
    }

    private val UUID_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun parse(url: String): Outcome {
        if (!isJetBoxUrl(url)) return Outcome.NotOurs

        // Фрагмент берём СЫРЫМ (URI.getFragment() декодирует — повторное декодирование съело бы %2520).
        val hash = url.indexOf('#')
        if (hash < 0 || hash == url.length - 1) return Outcome.NotOurs
        val params = parameters(url.substring(hash + 1))

        // Без ключа это не ссылка подключения: не пугаем клиента ошибкой.
        val key = params["k"]?.takeIf { it.isNotEmpty() } ?: return Outcome.NotOurs
        if (!isValidSetupKey(key)) return Outcome.Broken
        val management = normalizedManagement(params["m"]) ?: return Outcome.Broken

        return Outcome.Payload(
            JetBoxLinkPayload(
                setupKey = key,
                managementURL = management,
                exitNodeName = params["e"]?.takeIf { it.isNotEmpty() },
                boxName = params["n"]?.takeIf { it.isNotEmpty() },
            )
        )
    }

    /** Универсальная ссылка Кабинета либо своя схема. */
    fun isJetBoxUrl(url: String): Boolean {
        // Фрагмент и query отрезаем до разбора: хвосты мессенджеров в query (`?utm=a|b c`)
        // не должны делать нашу ссылку «чужой».
        val uri = runCatching { URI(url.substringBefore('#').substringBefore('?')) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (scheme == JetBoxConfig.CUSTOM_SCHEME) {
            return uri.host?.lowercase(Locale.ROOT) == "connect"
        }
        if (scheme == "https") {
            if (uri.host?.lowercase(Locale.ROOT) != JetBoxConfig.UNIVERSAL_LINK_HOST) return false
            // App Link матчит `/connect` и всё под ним — принимаем то же самое.
            // Путь сравниваем декодированным (`%63onnect` == `connect`), как iOS `url.path`.
            val path = (uri.path ?: "").lowercase(Locale.ROOT)
            return path == JetBoxConfig.UNIVERSAL_LINK_PATH ||
                path.startsWith(JetBoxConfig.UNIVERSAL_LINK_PATH + "/")
        }
        return false
    }

    /**
     * Разбор `k=…&m=…&e=…`. Имена регистронезависимы, значения percent-декодируются один раз,
     * `+` остаётся плюсом, неизвестные параметры игнорируются, первый повтор побеждает.
     */
    private fun parameters(fragment: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (pair in fragment.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val name = pair.substring(0, eq).lowercase(Locale.ROOT)
            if (name.isEmpty() || result.containsKey(name)) continue
            result[name] = percentDecode(pair.substring(eq + 1)).trim()
        }
        return result
    }

    /**
     * Percent-decoding без превращения «+» в пробел: `%XX` -> байты, затем СТРОГИЙ UTF-8.
     * Битый `%` или невалидный UTF-8 — возвращаем исходную строку (как iOS `removingPercentEncoding ?? raw`).
     */
    private fun percentDecode(raw: String): String {
        if (raw.indexOf('%') < 0) return raw
        val bytes = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                val hi = if (i + 2 < raw.length) hexValue(raw[i + 1]) else -1
                val lo = if (i + 2 < raw.length) hexValue(raw[i + 2]) else -1
                if (hi < 0 || lo < 0) return raw
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < raw.length) i + 2 else i + 1
                bytes.write(raw.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            raw
        }
    }

    /** Только ASCII-hex (`Character.digit` принял бы и чужие цифры Unicode). */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Формат NetBird: ровно 36 символов, строгий UUID (`UUID.fromString` и при длине 36 нестрог, напр. принимает «+»). */
    fun isValidSetupKey(key: String): Boolean = key.length == 36 && UUID_REGEX.matches(key)

    /** Только https, обязателен хост, хвостовой слэш срезаем; пусто/нет параметра — дефолт приложения. */
    private fun normalizedManagement(value: String?): String? {
        if (value.isNullOrEmpty()) return JetBoxConfig.DEFAULT_MANAGEMENT_URL
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val sb = StringBuilder("https://").append(host.lowercase(Locale.ROOT))
        if (uri.port != -1) sb.append(':').append(uri.port)
        val path = uri.rawPath ?: ""
        if (path.isNotEmpty() && path != "/") sb.append(path.removeSuffix("/"))
        return sb.toString()
    }
}
