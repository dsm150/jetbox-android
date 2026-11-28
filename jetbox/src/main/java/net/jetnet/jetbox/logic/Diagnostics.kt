package net.jetnet.jetbox.logic

/**
 * Диагностика «Помощи» (спека VPN_Box 2026-11-28-app-help-and-dns-check, §2.3) — ровно то, что уйдёт в Кабинет или в
 * письмо, и ровно то, что показывает «Что входит в диагностику». Без ключей WireGuard, setup-key, `app_token`, полной
 * ссылки подключения и списка пиров; журнал — последние [LOG_LINES] строк после [DiagnosticsScrubber].
 */
data class DiagnosticsInput(
    val appVersion: String,
    val appBuild: Int,
    val store: String,
    val osVersion: String,
    val device: String,
    val language: String,
    /** «email» (есть токен приложения), «link» (ссылка или QR), «none» (не подключён). */
    val signIn: String,
    /** Почта входа — уже маской `ab***@домен` или `null`. */
    val emailMasked: String?,
    val hasAppToken: Boolean,
    val tunnel: String,
    val homeAnswers: Boolean,
    val latencyMs: Long?,
    /** «direct» / «relayed» / `null` — не на связи. */
    val route: String?,
    val uptime: String?,
    val exit: String?,
    /** На связи без выбранного выхода (`x=0` или выход выключен); `null` — туннель не поднят, не судим. */
    val exitOff: Boolean?,
    val dnsCheck: DnsCheck,
    val privateDns: String?,
    val log: List<String>,
)

object Diagnostics {
    const val LOG_LINES = 50

    fun build(d: DiagnosticsInput): String = buildString {
        appendLine("JetBox Android ${d.appVersion} (${d.appBuild}), ${d.store}")
        appendLine("Android ${d.osVersion}, ${d.device}, lang ${d.language}")
        appendLine("Sign-in: ${d.signIn}" + (d.emailMasked?.let { ", $it" } ?: "") + ", app token: ${yes(d.hasAppToken)}")
        appendLine(
            "Tunnel: ${d.tunnel}, home answers: ${yes(d.homeAnswers)}" +
                (d.latencyMs?.let { ", $it ms" } ?: "") +
                (d.route?.let { ", $it" } ?: "") +
                (d.uptime?.let { ", online $it" } ?: ""),
        )
        appendLine("Exit: ${d.exit ?: "none"}, x=0: ${d.exitOff?.let(::yes) ?: "unknown"}")
        appendLine("DNS check: ${dnsText(d.dnsCheck)}, private DNS: ${d.privateDns ?: "off"}")
        val log = DiagnosticsScrubber.scrub(d.log.takeLast(LOG_LINES))
        appendLine("Log (last ${log.size}):")
        log.forEach { appendLine(it) }
    }.trimEnd()

    fun dnsText(check: DnsCheck): String = when (check) {
        DnsCheck.NOT_CHECKED -> "not checked"
        DnsCheck.THROUGH -> "through JetBox"
        DnsCheck.BYPASS -> "bypasses JetBox"
        DnsCheck.NO_ANSWER -> "no answer"
        DnsCheck.BOX_UNAWARE -> "box without canary"
    }

    private fun yes(v: Boolean) = if (v) "yes" else "no"
}
