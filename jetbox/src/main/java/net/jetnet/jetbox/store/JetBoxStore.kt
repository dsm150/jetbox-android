package net.jetnet.jetbox.store

import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.logic.JetBoxIdentity

/**
 * Что приложение помнит между запусками: какой JetBox прописан на этом телефоне —
 * порт `JetBoxStore.swift` (iOS). Setup key и токен устройства здесь не хранятся — только отпечатки.
 */
class JetBoxStore(private val kv: KeyValueStore) {

    var currentBox: JetBoxBox?
        get() = kv.getString(BOX_KEY)?.let { JetBoxBox.fromJson(it) }
        set(value) = kv.putString(BOX_KEY, value?.toJson())

    /**
     * После успешной регистрации: новый текущий JetBox из ссылки (без ключа). `x=0` — выход из ссылки не
     * включён; у того же подключения выбранный руками выход сохраняется (повтор ссылки его не обнуляет).
     */
    fun remember(payload: JetBoxLinkPayload, nowMillis: Long) {
        val same = currentBox?.isSameConnection(payload) == true
        // Токен приложения — от входа по рабочей почте. Ссылка того же JetBox из Кабинета его не несёт — прежний
        // остаётся; другой JetBox — прежний токен чужой, забываем.
        appToken = payload.appToken ?: appToken.takeIf { same }
        val kept = currentBox?.takeIf { it.isSameConnection(payload) }?.selectedExitNode
        currentBox = JetBoxBox(
            managementURL = payload.managementURL,
            exitNodeName = payload.exitNodeName,
            // `x=0`: выход из ссылки только разрешён, не включён (LINK-CONTRACT §2.10, iOS ba59d14).
            selectedExitNode = if (payload.enablesDefaultExit) payload.exitNodeName else kept,
            boxName = payload.boxName,
            registeredAtMillis = nowMillis,
            deviceTokenHash = payload.deviceTokenHash,
            setupKeyFingerprint = JetBoxIdentity.fingerprint(payload.setupKey),
        )
    }

    /** Выбранный выход важнее того, что был в ссылке, но удостоверение JetBox не трогает. */
    /**
     * Токен приложения (`app_token` из `login/verify`) — для «Выйти» с отзывом устройства в Кабинете. Хранится как есть
     * (его отправляют), только в приватных настройках приложения (без бэкапа и переноса — data_extraction_rules), как и
     * ключ WireGuard ядра; в журнал не пишется.
     */
    var appToken: String?
        get() = kv.getString(APP_TOKEN_KEY)
        set(value) = kv.putString(APP_TOKEN_KEY, value)

    /** «Выйти»: забыть JetBox и токен приложения. */
    fun forget() {
        currentBox = null
        appToken = null
        loginEmailMasked = null
    }

    /** Почта входа — только маской `ab***@домен`, для диагностики «Помощи»; полная почта не хранится. */
    var loginEmailMasked: String?
        get() = kv.getString(LOGIN_EMAIL_KEY)
        set(value) = kv.putString(LOGIN_EMAIL_KEY, value)

    /** Адрес поддержки из ответа Кабинета (`support_email`); пока его нет — [net.jetnet.jetbox.link.JetBoxConfig.SUPPORT_EMAIL]. */
    var supportEmail: String?
        get() = kv.getString(SUPPORT_EMAIL_KEY)
        set(value) = kv.putString(SUPPORT_EMAIL_KEY, value)

    fun rememberExitNode(name: String?) {
        val box = currentBox ?: return
        currentBox = box.copy(selectedExitNode = name)
    }

    companion object {
        const val PREFS_NAME = "jetbox"
        private const val BOX_KEY = "jetbox.currentBox"
        private const val APP_TOKEN_KEY = "jetbox.appToken"
        private const val LOGIN_EMAIL_KEY = "jetbox.loginEmailMasked"
        private const val SUPPORT_EMAIL_KEY = "jetbox.supportEmail"
    }
}
