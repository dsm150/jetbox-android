package net.jetnet.jetbox.store

import android.content.SharedPreferences

/** Абстракция SharedPreferences для JVM-тестов. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** `null` = удалить ключ. */
    fun putString(key: String, value: String?)

    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Боевая реализация поверх SharedPreferences. Запись по умолчанию — `commit()`: синхронно, чтобы не
 * потерять при убийстве процесса. [async] — `apply()`: память обновляется сразу (чтение в этом же
 * процессе видит новое значение), диск — в фоне; для главного потока при старте процесса
 * (`ContentProvider.onCreate`), где синхронная запись — нарушение StrictMode.
 */
class SharedPrefsStore(private val prefs: SharedPreferences, private val async: Boolean = false) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        save(editor)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        save(prefs.edit().putBoolean(key, value))
    }

    private fun save(editor: SharedPreferences.Editor) {
        if (async) editor.apply() else editor.commit()
    }
}

/** Для тестов: HashMap. */
class MemoryStore : KeyValueStore {
    private val map = HashMap<String, Any>()

    override fun getString(key: String): String? = map[key] as? String

    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }
}
