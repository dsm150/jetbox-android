package net.jetnet.jetbox.logic

/**
 * Отказы сценария «одной кнопки» — порт `JetBoxFailure` (iOS). Заголовки и тексты
 * живут в ресурсах UI; здесь только тип, подробности для поддержки и перевод
 * ошибок ядра. Ключа в подробностях быть не может: в текст ошибки ядра он не попадает.
 */
sealed class JetBoxFailure {
    data object BrokenLink : JetBoxFailure() { override val details: String? = null }
    data object Offline : JetBoxFailure() { override val details: String? = null }
    data class ManagementUnreachable(override val details: String?) : JetBoxFailure()
    data class KeyRejected(override val details: String?) : JetBoxFailure()
    data object TunnelFailed : JetBoxFailure() { override val details: String? = null }

    /** Android: отказ в системном диалоге VPN (в iOS это часть текста tunnelFailed). */
    data object VpnPermissionDenied : JetBoxFailure() { override val details: String? = null }
    data class Other(override val details: String) : JetBoxFailure()

    /** Технические подробности для поддержки (у отказов без текста ядра — null). */
    abstract val details: String?

    /** Повтор имеет смысл не всегда: испорченную ссылку повторять нечем. */
    val canRetry: Boolean get() = this !is BrokenLink

    companion object {
        private val CERT_MARKERS = listOf("x509", "certificate")
        // gRPC-код «PermissionDenied» (в lowercase — без пробела) и «peer is not registered» — отказ по ключу.
        private val KEY_MARKERS = listOf("setup key", "setup-key", "expired", "not registered", "permissiondenied")
        private val NETWORK_MARKERS = listOf(
            "deadline exceeded", "connection refused", "no such host", "unavailable", "dial",
            "no connection to management", "management service public key",
            "failed to create auth client", "network is unreachable", "i/o timeout",
        )

        /**
         * Перевод ошибки ядра (английский, техничный) в тип отказа; тексты — реальные строки ядра
         * Android. Порядок по нижнему регистру: 1) сертификат (`x509`/`certificate`) — это сервер
         * управления, даже если в тексте есть «expired»; 2) ключ (`setup key`/`setup-key`/`expired`);
         * 3) сетевые признаки — ManagementUnreachable; иначе Other.
         *
         * Offline — только если нет интернета и текста нет либо он сетевой: флаг VALIDATED на Android
         * бывает ложным (РФ), поэтому текст про ключ при `!online` остаётся KeyRejected.
         */
        fun classify(error: String?, online: Boolean): JetBoxFailure {
            val text = error
            val lowered = text?.lowercase().orEmpty()
            val cert = CERT_MARKERS.any { it in lowered }
            val key = !cert && KEY_MARKERS.any { it in lowered }
            val network = NETWORK_MARKERS.any { it in lowered }
            if (!online && (text.isNullOrEmpty() || cert || (!key && network))) return Offline
            if (text == null) return Other("")
            if (cert) return ManagementUnreachable(text)
            if (key) return KeyRejected(text)
            if (network) return ManagementUnreachable(text)
            return Other(text)
        }
    }
}
