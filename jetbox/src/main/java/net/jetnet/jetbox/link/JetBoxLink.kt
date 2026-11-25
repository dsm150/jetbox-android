package net.jetnet.jetbox.link

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
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
    /**
     * Токен устройства из облачной ссылки Кабинета (`t=`, LINK-CONTRACT 1.2): уникален на устройство,
     * «Новая ссылка» в Кабинете выдаёт новый. В удостоверение не входит (ред. 1.3 §2.11); в хранилище —
     * только отпечаток ([deviceTokenHash]); в журнал — только маска.
     */
    val deviceToken: String? = null,
    /**
     * `x=0` (LINK-CONTRACT §2.10, как iOS `enablesDefaultExit`): после подключения выход в интернет НЕ
     * включать — выбор выхода пропускается целиком. Любое другое значение или нет `x` — `true`. В
     * удостоверение не входит.
     */
    val enablesDefaultExit: Boolean = true,
    /**
     * Токен приложения из ответа `login/verify` (`app_token`): в ссылке его нет никогда — его кладёт вход по рабочей
     * почте. Нужен «Выйти», чтобы Кабинет отозвал это устройство и освободил место. Хранится с профилем
     * ([net.jetnet.jetbox.store.JetBoxStore.appToken]); в журнал — только маска.
     */
    val appToken: String? = null,
) {
    /** Маска ключа для экрана и логов: видны только последние 4 символа. */
    val maskedKey: String
        get() = maskSecret(setupKey)

    /** Что показать клиенту как имя его коробки. */
    val displayName: String
        get() = boxName?.takeIf { it.isNotEmpty() }
            ?: exitNodeName?.takeIf { it.isNotEmpty() }
            ?: hostOf(managementURL)

    /** Отпечаток токена устройства ([secretFingerprint]) — сам токен не хранится. */
    val deviceTokenHash: String?
        get() = deviceToken?.let(::secretFingerprint)

    /** Удостоверение подключения — см. [boxIdentityOf]; сравнивать через `JetBoxBox.isSameConnection` ([sameConnection]). */
    val boxIdentity: String
        get() = boxIdentityOf(managementURL, exitNodeName, boxName)

    // Ключ и токен не должны попасть в логи через toString().
    override fun toString(): String =
        "JetBoxLinkPayload(setupKey=$maskedKey, managementURL=$managementURL, exitNodeName=$exitNodeName, " +
            "boxName=$boxName, deviceToken=${deviceToken?.let(::maskSecret)}, enablesDefaultExit=$enablesDefaultExit, " +
            "appToken=${appToken?.let(::maskSecret)})"
}

/** Единая маска секрета для экрана и логов: видны только последние 4 символа. */
fun maskSecret(secret: String): String =
    if (secret.length <= 4) "••••" else "•".repeat(8) + "…" + secret.takeLast(4)

/**
 * Отпечаток секрета (setup key, токен устройства): SHA-256, первые 16 байт hex (32 символа) — как
 * `JetBoxIdentity.fingerprint(of:)` iOS. По нему сравниваем, не храня сам секрет. Регистр не меняет:
 * setup key приводит к нижнему регистру вызывающий (`JetBoxIdentity.fingerprint`), токен — как есть.
 */
fun secretFingerprint(secret: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/**
 * Удостоверение подключения — одно правило для ссылки и сохранённого JetBox (LINK-CONTRACT 1.3,
 * §2.11, как iOS `JetBoxLinkIdentity.make`):
 *
 * * есть выход `e` — `хост(m)|e|n` (пустое `n` — пустая третья часть, без префикса);
 * * иначе есть имя `n` — `хост(m)|n:<n>`;
 * * иначе — `хост(m)|`.
 *
 * Хост — в нижнем регистре, без порта и пути. `n` — после percent-decoding и обрезки пробелов, с
 * учётом регистра. `t`, `x`, `p`, `k` не входят: `t` меняется при перепривязке. `n` входит, потому
 * что облачные абоненты делят один выход `e=cl-exit`. Решает только, показывать ли экран «Другой
 * JetBox»; новая ли личность устройства (ключ WireGuard) — решает отпечаток setup key,
 * `JetBoxIdentity.plan`. Сравнение — не равенство строк, а [sameConnection].
 */
internal fun boxIdentityOf(
    managementURL: String,
    exitNodeName: String?,
    boxName: String?,
): String {
    val host = hostOf(managementURL).lowercase(Locale.ROOT)
    val name = boxName ?: ""
    return when {
        !exitNodeName.isNullOrEmpty() -> "$host|$exitNodeName|$name"
        name.isNotEmpty() -> "$host|n:$name"
        else -> "$host|"
    }
}

/** Общий выход облака (пространство `cl-` в NetBird, сейчас только `cl-exit`): без `n` абонента не различает. */
internal fun isSharedExit(exitNodeName: String): Boolean = exitNodeName.startsWith("cl-")

/**
 * «То же подключение» (LINK-CONTRACT 1.3, §2.11, как iOS `JetBoxBox.isSameConnection`): совпали
 * удостоверения [boxIdentityOf] — да. Иначе, если имени нет у одной из сторон (старые ссылки без `n`),
 * а выход `e` есть, — сравнение по `хост|e` без имени, кроме общего выхода облака ([isSharedExit]):
 * там без имени абонента не различить — «не то же» (вопрос о замене). Без `e` — только по строке.
 * Сторона A — сохранённая запись, B — ссылка (правило симметрично).
 */
internal fun sameConnection(
    managementA: String, exitA: String?, nameA: String?,
    managementB: String, exitB: String?, nameB: String?,
): Boolean {
    if (boxIdentityOf(managementA, exitA, nameA) == boxIdentityOf(managementB, exitB, nameB)) return true
    val nameMissing = nameA.isNullOrEmpty() || nameB.isNullOrEmpty()
    if (!nameMissing || exitA.isNullOrEmpty() || isSharedExit(exitA)) return false
    return boxIdentityOf(managementA, exitA, null) == boxIdentityOf(managementB, exitB, null)
}

object JetBoxLink {
    sealed class Outcome {
        /** Ссылка наша и разобрана. */
        data class Payload(val payload: JetBoxLinkPayload) : Outcome()

        /** Ссылка наша, но испорчена (кривой ключ). */
        data object Broken : Outcome()

        /**
         * Ссылка наша, ключ в порядке, но адрес management вне маски [isAllowedManagement]
         * (решение владельца 18.11.2026, LINK-CONTRACT §2.3): «Ссылка ведёт не на сервер JetBox».
         * В сеть не идём, текущее подключение не трогаем, вопрос «Другой JetBox» не задаём.
         */
        data object ForeignServer : Outcome()

        /** Не наша ссылка — отдаём дальше по цепочке обработчиков. */
        data object NotOurs : Outcome()

        /**
         * JetBox safety: нет подписи `s`, она неверна или в ссылке повтор параметра ([JetBoxLinkSignature]).
         * Отказ до любого действия: в сеть не идём, подключение не трогаем.
         */
        data object Unsigned : Outcome()
    }

    private val UUID_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * [signed] — правила JetBox safety ([JetBoxConfig.LINK_SIGNED], как iOS): любой хост и любой сервер `m`, но только
     * с верной подписью; `false` — прежние правила (свой хост, маска `*.jet-net.net`). [trusted] — ссылка из ответа
     * `login/verify` (TLS, наш Кабинет): подпись не нужна. [signatureKeys] — для тестов (тест-векторы на тестовом ключе).
     */
    fun parse(
        url: String,
        signed: Boolean = JetBoxConfig.LINK_SIGNED,
        trusted: Boolean = false,
        signatureKeys: List<String> = JetBoxLinkSignature.PUBLIC_KEYS,
    ): Outcome {
        if (!isJetBoxUrl(url, signed)) return Outcome.NotOurs

        // Фрагмент берём СЫРЫМ (URI.getFragment() декодирует — повторное декодирование съело бы %2520).
        val hash = url.indexOf('#')
        if (hash < 0 || hash == url.length - 1) return Outcome.NotOurs
        val fragment = url.substring(hash + 1)
        val params = parameters(fragment)

        // Без ключа это не ссылка подключения: не пугаем клиента ошибкой.
        val key = params["k"]?.takeIf { it.isNotEmpty() } ?: return Outcome.NotOurs
        // JetBox safety (как iOS 25): до проверки ключа и сервера — подпись всего фрагмента.
        if (signed && !trusted && !JetBoxLinkSignature.isValid(fragment, signatureKeys)) return Outcome.Unsigned
        if (!isValidSetupKey(key)) return Outcome.Broken
        // Подписанная (или доверенная) ссылка — любой сервер; негодный по формату адрес — битая ссылка. Прежние правила:
        // эталон (JetBox_Desktop core/jetboxlink, AllowedManagement) «битый» m от «чужого» не отличает — чужой сервер.
        val management = if (signed) {
            openManagement(params["m"]) ?: return Outcome.Broken
        } else {
            normalizedManagement(params["m"]) ?: return Outcome.ForeignServer
        }

        return Outcome.Payload(
            JetBoxLinkPayload(
                setupKey = key,
                managementURL = management,
                exitNodeName = params["e"]?.takeIf { it.isNotEmpty() },
                boxName = params["n"]?.takeIf { it.isNotEmpty() },
                deviceToken = params["t"]?.takeIf { it.isNotEmpty() },
                enablesDefaultExit = params["x"] != "0",
            )
        )
    }

    /**
     * Универсальная ссылка Кабинета либо своя схема. [signed] (JetBox safety) — без фильтра хоста: своя схема с любым
     * хостом, https и http с любого хоста и пути; ссылкой подключения её делает подписанный фрагмент с ключом.
     */
    fun isJetBoxUrl(url: String, signed: Boolean = JetBoxConfig.LINK_SIGNED): Boolean {
        // Фрагмент и query отрезаем до разбора: хвосты мессенджеров в query (`?utm=a|b c`)
        // не должны делать нашу ссылку «чужой».
        val uri = runCatching { URI(url.substringBefore('#').substringBefore('?')) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (signed) return scheme == JetBoxConfig.CUSTOM_SCHEME || scheme == "https" || scheme == "http"
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
    internal fun percentDecode(raw: String): String {
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

    /**
     * Маска адреса management (решение владельца 18.11.2026; одно правило на все платформы —
     * LINK-CONTRACT §2.3, эталон `AllowedManagement` в JetBox_Desktop `core/jetboxlink/mask.go`,
     * общие векторы `mgmt-mask.json`). [m] — значение параметра после percent-decoding. Разрешено, только если:
     * схема https; без логина, без пути (кроме «/»), без query и фрагмента; хост — ASCII-имя
     * (IDN — только punycode), не IP; в нижнем регистре без одной завершающей точки, без пустых меток
     * оканчивается на [JetBoxConfig.MANAGEMENT_SUFFIX] (сам `jet-net.net` — нет); порт, если есть, — 1–65535.
     */
    fun isAllowedManagement(m: String): Boolean = managementAddress(m) != null

    /**
     * Хост разрешённого адреса — в нижнем регистре, без одной завершающей точки (она срезается до маски
     * и удостоверения: `netbird.jet-net.net.` — тот же сервер; решение 18.11.2026, iOS d47f61b) — и порт.
     */
    private class ManagementAddress(val host: String, val port: Int?)

    /**
     * Разбор authority вручную: `java.net.URI` здесь нестрог (хост с «_» уходит в authority при
     * `host == null`, IPv6 в `[]`, десятичные и сокращённые IPv4 вроде `2130706433`, `127.1`).
     */
    private fun managementAddress(m: String): ManagementAddress? {
        val prefix = "https://"
        // Только ASCII: `regionMatches(ignoreCase = true)` сводит «ſ» (U+017F) к «s» и принял бы `httpſ://`.
        if (m.length < prefix.length || !prefix.indices.all { asciiLower(m[it]) == prefix[it] }) return null
        val rest = m.substring(prefix.length)
        if (rest.any { it == '?' || it == '#' }) return null
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash)
        if (path.isNotEmpty() && path != "/") return null
        if ('@' in authority) return null

        val colon = authority.indexOf(':')
        val rawHost = if (colon < 0) authority else authority.substring(0, colon)
        val port = if (colon < 0) null else parsePort(authority.substring(colon + 1)) ?: return null

        // Только ASCII-буквы, цифры, «-» и «.»: заодно отсекает IPv6 (`[`, `:`), логин, `%`, `\`, `_`, Unicode.
        if (rawHost.isEmpty() || !rawHost.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' }) {
            return null
        }
        val bare = rawHost.lowercase(Locale.ROOT).removeSuffix(".")
        val labels = bare.split('.')
        if (labels.any { it.isEmpty() }) return null
        // Числовая последняя метка — IP в любой записи (`1.2.3.4`, `2130706433`, `127.1`).
        if (labels.last().all { it in '0'..'9' }) return null
        val suffix = JetBoxConfig.MANAGEMENT_SUFFIX
        if (!bare.endsWith(suffix) || bare.length <= suffix.length) return null
        return ManagementAddress(bare, port)
    }

    /** Нижний регистр только для ASCII-букв; прочие символы как есть. */
    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

    /** Порт — только ASCII-цифры, 1–65535 (ведущие нули допустимы, как в эталоне); пустой — нет. */
    private fun parsePort(raw: String): Int? {
        if (raw.isEmpty() || !raw.all { it in '0'..'9' }) return null
        val digits = raw.trimStart('0')
        if (digits.isEmpty() || digits.length > 5) return null
        return digits.toInt().takeIf { it in 1..65535 }
    }

    /**
     * Пусто/нет параметра — дефолт приложения; иначе только адрес внутри маски [isAllowedManagement]
     * (`null` — вне маски). Формат прежний: `https://хост[:порт]` без хвостового слэша и без завершающей точки хоста.
     */
    /**
     * JetBox safety: адрес management без маски — любой хост (имя, IPv4, IPv6 в `[]`), https или http, порт и путь по
     * желанию. Отсекается только то, что адресом сервера не является: другая схема, пустой хост, логин в адресе, query и
     * фрагмент. Пусто/нет параметра — сервер по умолчанию. Схема и хост — в нижнем регистре, без завершающей точки хоста
     * и хвостового «/».
     */
    private fun openManagement(value: String?): String? {
        if (value.isNullOrEmpty()) return JetBoxConfig.DEFAULT_MANAGEMENT_URL
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "https" || it == "http" } ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port == -1) "" else if (uri.port in 1..65535) ":${uri.port}" else return null
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }

    private fun normalizedManagement(value: String?): String? {
        if (value.isNullOrEmpty()) return JetBoxConfig.DEFAULT_MANAGEMENT_URL
        val address = managementAddress(value) ?: return null
        return "https://" + address.host + (address.port?.let { ":$it" } ?: "")
    }
}
