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

    /** После успешной регистрации: новый текущий JetBox из ссылки (без ключа). */
    fun remember(payload: JetBoxLinkPayload, nowMillis: Long) {
        currentBox = JetBoxBox(
            managementURL = payload.managementURL,
            exitNodeName = payload.exitNodeName,
            // `x=0`: выход из ссылки только разрешён, не включён (LINK-CONTRACT §2.10, iOS ba59d14).
            selectedExitNode = if (payload.enablesDefaultExit) payload.exitNodeName else null,
            boxName = payload.boxName,
            registeredAtMillis = nowMillis,
            deviceTokenHash = payload.deviceTokenHash,
            setupKeyFingerprint = JetBoxIdentity.fingerprint(payload.setupKey),
        )
    }

    /** Выбранный выход важнее того, что был в ссылке, но удостоверение JetBox не трогает. */
    fun rememberExitNode(name: String?) {
        val box = currentBox ?: return
        currentBox = box.copy(selectedExitNode = name)
    }

    companion object {
        const val PREFS_NAME = "jetbox"
        private const val BOX_KEY = "jetbox.currentBox"
    }
}
