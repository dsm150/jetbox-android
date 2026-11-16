package net.jetnet.jetbox.logic

import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.link.hostOf
import net.jetnet.jetbox.link.secretFingerprint
import net.jetnet.jetbox.store.JetBoxBox
import java.util.Locale

/**
 * Под какой «личностью» телефон встаёт в сеть по ссылке — порт `JetBoxIdentity.swift` (iOS).
 *
 * Личность устройства в NetBird — его ключ WireGuard. Ядро берёт ключ из конфига, если тот уже
 * есть, а management узнаёт существующий пир по ключу и setup key из новой ссылки не применяет: ни
 * группы (`auto_groups`), ни лимит ключа. Баг 13.11.2026: телефон с подключением коробки открыл
 * ссылку облачного абонента, согласился на замену — и остался прежним пиром в прежних группах.
 * Поэтому при новой ссылке (другой setup key) ключ создаётся заново (`AndroidEngine.register`
 * с `freshIdentity = true`), а при повторе той же ссылки — сохраняется: одноразовый setup key уже
 * использован, а удалённый на сервере пир восстанавливается входом с тем же ключом.
 *
 * Экран «Другой JetBox» решает не это, а удостоверение подключения (`boxIdentity`).
 */
object JetBoxIdentity {

    enum class Plan {
        /** Тот же ключ WireGuard: повтор своей же ссылки или первый вход без конфига. */
        REUSE,

        /** Новый ключ: ссылка с другим setup key, на другой management или конфиг не от JetBox. */
        FRESH,
    }

    fun plan(current: JetBoxBox?, payload: JetBoxLinkPayload, configExists: Boolean): Plan {
        // Конфига нет — ядро и так создаст новый ключ.
        if (!configExists) return Plan.REUSE
        // Конфиг остался не от JetBox (например, вход в расширенном режиме).
        if (current == null) return Plan.FRESH
        if (managementHost(current.managementURL) != managementHost(payload.managementURL)) return Plan.FRESH
        val known = current.setupKeyFingerprint
        if (known != null) return if (known == fingerprint(payload.setupKey)) Plan.REUSE else Plan.FRESH
        // Запись сделана сборкой ≤ 1.0.2, отпечатка ключа в ней нет: тот же JetBox по формуле 1.0.2
        // (`хост|e`, как запасная ветка iOS) — прежний ключ, иначе новый.
        return if (current.isSameConnection(payload)) Plan.REUSE else Plan.FRESH
    }

    /**
     * Отпечаток setup key: по нему отличаем «та же ссылка» от «новая ссылка», не храня сам секрет.
     * Как iOS: SHA-256 ключа в нижнем регистре, первые 16 байт в hex (32 символа).
     */
    fun fingerprint(setupKey: String): String = secretFingerprint(setupKey.lowercase(Locale.ROOT))

    private fun managementHost(url: String): String = hostOf(url).lowercase(Locale.ROOT)
}
