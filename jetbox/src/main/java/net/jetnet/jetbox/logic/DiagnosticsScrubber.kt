package net.jetnet.jetbox.logic

/**
 * Фильтр строк диагностики «Помощи» (спека VPN_Box 2026-11-28-app-help-and-dns-check, §2.3): из журнала и полей
 * уходят ключи, setup-key, токены, подписи `s`, адреса серверов, публичные IP и почта. Остаются адреса меша
 * (100.64/10, fd00::/8), частные и служебные — без них разбор теряет смысл. Лишнее лучше замаскировать, чем пропустить.
 */
object DiagnosticsScrubber {
    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // Параметры ссылки подключения: ключ, подпись, токены, сервер.
        Regex("""(?<=[#&?])(k|s|t|m|token|app_token)=[^&\s]+""", RegexOption.IGNORE_CASE) to { m -> "${m.groupValues[1]}=<hidden>" },
        // Адреса (management, Кабинет, любые URL) — целиком.
        Regex("""\b(?:https?|rels?|wss?)://\S+""", RegexOption.IGNORE_CASE) to { _ -> "<url>" },
        // Токен приложения Кабинета.
        Regex("""\bjbd_[A-Za-z0-9_\-]+""") to { _ -> "jbd_<hidden>" },
        // setup-key и прочие UUID.
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""") to { _ -> "<uuid>" },
        // Ключи WireGuard (base64 из 32 байт) и прочий длинный base64 / base64url (подписи, токены).
        Regex("""[A-Za-z0-9+/]{42,43}={0,2}(?![A-Za-z0-9+/=])""") to { _ -> "<key>" },
        Regex("""(?<![A-Za-z0-9_\-])[A-Za-z0-9_\-]{32,}(?![A-Za-z0-9_\-])""") to { _ -> "<secret>" },
        // Почта — маской ab***@домен.
        Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""") to { m -> maskEmail(m.value) },
        // IPv4: публичные — маской, служебные и меш — как есть.
        Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""") to { m -> if (isPublicV4(m)) "<ip>" else m.value },
        // IPv6 глобальные (2000::/3) — маской; fd00::/8 (меш), fe80:: и ::1 — как есть.
        Regex("""(?<![0-9A-Fa-f:])[23][0-9A-Fa-f]{0,3}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Fa-f:])""") to { _ -> "<ip6>" },
    )

    fun scrub(line: String): String = rules.fold(line) { acc, (re, repl) -> re.replace(acc, repl) }

    fun scrub(lines: List<String>): List<String> = lines.map(::scrub)

    /** `ivan.petrov@fabrika-mfc.ru` → `iv***@fabrika-mfc.ru`. */
    fun maskEmail(email: String): String {
        val at = email.lastIndexOf('@')
        if (at <= 0) return "***"
        val local = email.substring(0, at)
        return local.take(2) + "***" + email.substring(at)
    }

    private fun isPublicV4(m: MatchResult): Boolean {
        val o = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (o.any { it > 255 }) return false
        val (a, b, c) = o
        return !(
            a == 10 || a == 127 || a == 0 ||
                (a == 100 && b in 64..127) || // CGNAT, меш NetBird
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 192 && b == 0 && c == 2) || // TEST-NET-1: метка канарейки 192.0.2.53
                a >= 224
            )
    }
}
