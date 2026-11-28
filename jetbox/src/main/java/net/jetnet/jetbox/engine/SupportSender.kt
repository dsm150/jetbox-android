package net.jetnet.jetbox.engine

import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.logic.JetBoxLog
import net.jetnet.jetbox.store.JetBoxStore

/** Чем кончилась отправка в «Помощи». */
sealed class HelpOutcome {
    /** В Кабинете; [promise] — обещание срока ответа из ответа Кабинета (`null` — показать встроенное). */
    data class Sent(val promise: String?) : HelpOutcome()

    /** `413` — слишком длинно. */
    data object TooLong : HelpOutcome()

    /** `429` — повторить через [retryAfterSec] (если Кабинет сказал). */
    data class RateLimited(val retryAfterSec: Int?) : HelpOutcome()

    /** Токена нет, `401`, сеть, иной ответ — письмом на [address] с темой [subject] и телом [body]. */
    data class NeedsMail(val address: String, val subject: String, val body: String) : HelpOutcome()
}

/**
 * «Помощь» (спека VPN_Box 2026-11-28-app-help-and-dns-check, §2.2): есть токен приложения (вход по почте) —
 * `POST support` в свой Кабинет, сообщение ложится в тред поддержки; токена нет или Кабинет не принял — письмо на адрес
 * поддержки (`support_email` из прошлых ответов Кабинета, иначе [JetBoxConfig.SUPPORT_EMAIL]). Повтора POST нет.
 */
class SupportSender(
    private val api: AppLoginApi?,
    private val store: JetBoxStore,
    private val version: String,
) {
    suspend fun send(message: String, diagnostics: String?, lang: String): HelpOutcome {
        val text = message.trim()
        val token = store.appToken
        if (token != null && api != null) {
            val r = api.support(token, text, diagnostics.orEmpty(), version, lang)
            r.supportEmail?.let { store.supportEmail = it }
            when (r) {
                is SupportResult.Sent -> {
                    JetBoxLog.i(TAG, "JetBox: help message sent to the Cabinet")
                    return HelpOutcome.Sent(r.promise)
                }
                is SupportResult.TooLong -> return HelpOutcome.TooLong
                is SupportResult.RateLimited -> return HelpOutcome.RateLimited(r.retryAfterSec)
                is SupportResult.Failed -> JetBoxLog.i(TAG, "JetBox: help via the Cabinet failed (${r.details}), falling back to email")
            }
        }
        return HelpOutcome.NeedsMail(address(), subject(), mailBody(text, diagnostics))
    }

    fun address(): String = store.supportEmail ?: JetBoxConfig.SUPPORT_EMAIL

    fun subject(): String = "JetBox: Android $version"

    companion object {
        private const val TAG = "JetBoxHelp"

        fun mailBody(message: String, diagnostics: String?): String =
            if (diagnostics.isNullOrBlank()) message else "$message\n\n— — —\n$diagnostics"
    }
}
