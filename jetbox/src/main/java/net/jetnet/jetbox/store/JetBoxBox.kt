package net.jetnet.jetbox.store

import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.link.boxIdentityOf
import net.jetnet.jetbox.link.hostOf
import net.jetnet.jetbox.link.legacyBoxIdentityOf
import org.json.JSONObject

/**
 * Коробка, к которой этот телефон уже привязан — порт `JetBoxBox` из `JetBoxStore.swift` (iOS).
 * Setup key здесь НЕ хранится: после регистрации он не нужен, лишняя копия секрета — лишний риск.
 */
data class JetBoxBox(
    val managementURL: String,
    /** Выход из ссылки. Часть удостоверения коробки. */
    val exitNodeName: String?,
    /** Выход, выбранный сейчас (мог смениться руками). На удостоверение коробки не влияет. */
    val selectedExitNode: String?,
    val boxName: String?,
    /** Момент регистрации, миллисекунды Unix-эпохи. */
    val registeredAtMillis: Long,
    /**
     * Отпечаток токена устройства из облачной ссылки (`t=`, `JetBoxLinkPayload.deviceTokenHash`):
     * часть удостоверения. Сам токен не хранится.
     */
    val deviceTokenHash: String? = null,
    /**
     * Отпечаток setup key, по которому телефон зарегистрирован (`JetBoxIdentity.fingerprint`), сам
     * ключ не хранится. `null` — запись сделана сборкой ≤ 1.0.2: сравнение по её формуле
     * ([isSameConnection]).
     */
    val setupKeyFingerprint: String? = null,
) {
    val displayName: String
        get() = boxName?.takeIf { it.isNotEmpty() }
            ?: exitNodeName?.takeIf { it.isNotEmpty() }
            ?: hostOf(managementURL)

    /** Что показывать как текущий выход, пока туннель не поднят. */
    val effectiveExitNode: String?
        get() = selectedExitNode ?: exitNodeName

    /** Та же формула, что у `JetBoxLinkPayload.boxIdentity` (одна функция на двоих). */
    val identity: String
        get() = boxIdentityOf(managementURL, exitNodeName, deviceTokenHash, boxName)

    /** Запись сборки ≤ 1.0.2: нет отпечатка ключа (и не было ни `t`, ни правила про имя). */
    val isLegacy: Boolean
        get() = setupKeyFingerprint == null

    /**
     * Ссылка ведёт на это же подключение — экрана «Другой JetBox» не будет. Запись ≤ 1.0.2 —
     * по её формуле `хост|(e ?: "")` (как iOS): своя облачная ссылка с `t` — тот же JetBox.
     */
    fun isSameConnection(payload: JetBoxLinkPayload): Boolean =
        if (isLegacy) legacyBoxIdentityOf(managementURL, exitNodeName) == payload.legacyBoxIdentity
        else identity == payload.boxIdentity

    /**
     * Имя дома из свежей ссылки того же JetBox. `null` — менять нечего
     * (другой JetBox, в ссылке нет имени или оно то же). Для ссылок без `e` и `t` имя входит в
     * удостоверение — новое имя там уже «Другой JetBox», сюда не доходит.
     */
    fun renamed(by: JetBoxLinkPayload): JetBoxBox? {
        if (!isSameConnection(by)) return null
        val name = by.boxName
        if (name.isNullOrEmpty() || name == boxName) return null
        return copy(boxName = name)
    }

    fun toJson(): String = JSONObject().apply {
        put(K_MANAGEMENT, managementURL)
        put(K_EXIT, exitNodeName ?: JSONObject.NULL)
        put(K_SELECTED, selectedExitNode ?: JSONObject.NULL)
        put(K_NAME, boxName ?: JSONObject.NULL)
        put(K_REGISTERED, registeredAtMillis)
        put(K_TOKEN_HASH, deviceTokenHash ?: JSONObject.NULL)
        put(K_FINGERPRINT, setupKeyFingerprint ?: JSONObject.NULL)
    }.toString()

    // Отпечатки в журнал — только началом.
    override fun toString(): String =
        "JetBoxBox(managementURL=$managementURL, exitNodeName=$exitNodeName, selectedExitNode=$selectedExitNode, " +
            "boxName=$boxName, registeredAtMillis=$registeredAtMillis, deviceTokenHash=${deviceTokenHash?.take(8)}, " +
            "setupKeyFingerprint=${setupKeyFingerprint?.take(8)})"

    companion object {
        private const val K_MANAGEMENT = "managementURL"
        private const val K_EXIT = "exitNodeName"
        private const val K_SELECTED = "selectedExitNode"
        private const val K_NAME = "boxName"
        private const val K_REGISTERED = "registeredAtMillis"
        private const val K_TOKEN_HASH = "deviceTokenHash"
        private const val K_FINGERPRINT = "keyFingerprint" // не «setupKey…»: на диске нет ничего, похожего на ключ

        /** Битый или неполный JSON — `null`. */
        fun fromJson(json: String): JetBoxBox? = runCatching {
            val o = JSONObject(json)
            JetBoxBox(
                managementURL = o.getString(K_MANAGEMENT),
                exitNodeName = o.optStringOrNull(K_EXIT),
                selectedExitNode = o.optStringOrNull(K_SELECTED),
                boxName = o.optStringOrNull(K_NAME),
                registeredAtMillis = o.getLong(K_REGISTERED),
                deviceTokenHash = o.optStringOrNull(K_TOKEN_HASH),
                setupKeyFingerprint = o.optStringOrNull(K_FINGERPRINT),
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)
    }
}
