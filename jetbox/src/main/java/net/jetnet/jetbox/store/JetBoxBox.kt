package net.jetnet.jetbox.store

import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.link.boxIdentityOf
import net.jetnet.jetbox.link.hostOf
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
        get() = boxIdentityOf(managementURL, exitNodeName)

    /**
     * Имя дома из свежей ссылки того же JetBox. `null` — менять нечего
     * (другой JetBox, в ссылке нет имени или оно то же).
     */
    fun renamed(by: JetBoxLinkPayload): JetBoxBox? {
        if (by.boxIdentity != identity) return null
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
    }.toString()

    companion object {
        private const val K_MANAGEMENT = "managementURL"
        private const val K_EXIT = "exitNodeName"
        private const val K_SELECTED = "selectedExitNode"
        private const val K_NAME = "boxName"
        private const val K_REGISTERED = "registeredAtMillis"

        /** Битый или неполный JSON — `null`. */
        fun fromJson(json: String): JetBoxBox? = runCatching {
            val o = JSONObject(json)
            JetBoxBox(
                managementURL = o.getString(K_MANAGEMENT),
                exitNodeName = o.optStringOrNull(K_EXIT),
                selectedExitNode = o.optStringOrNull(K_SELECTED),
                boxName = o.optStringOrNull(K_NAME),
                registeredAtMillis = o.getLong(K_REGISTERED),
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else getString(key)
    }
}
