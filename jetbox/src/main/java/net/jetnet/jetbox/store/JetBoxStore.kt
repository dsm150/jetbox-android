package net.jetnet.jetbox.store

import net.jetnet.jetbox.link.JetBoxLinkPayload

/**
 * Что приложение помнит между запусками: какой JetBox прописан на этом телефоне —
 * порт `JetBoxStore.swift` (iOS). Setup key здесь не хранится.
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
            selectedExitNode = payload.exitNodeName,
            boxName = payload.boxName,
            registeredAtMillis = nowMillis,
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
