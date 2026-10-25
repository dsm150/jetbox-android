package net.jetnet.jetbox.store

import android.content.SharedPreferences
import net.jetnet.jetbox.logic.ConnectionDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запись `SharedPrefsStore`: по умолчанию `commit()`, с `async` — `apply()`. Старт процесса
 * (`JetBoxProcess.startup` из `ContentProvider.onCreate`, главный поток) пишет «напрямую по
 * умолчанию» через `apply()` — без синхронной записи на диск — и остаётся идемпотентным.
 */
class SharedPrefsStoreTest {
    /** SharedPreferences в памяти: считает `commit`/`apply`; значения видны сразу (как у Android). */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        var commits = 0
        var applies = 0

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String): SharedPreferences.Editor {
                removed += key
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                removed += map.keys
                return this
            }
            private fun flush() {
                removed.forEach { map.remove(it) }
                map.putAll(pending)
            }
            override fun commit(): Boolean {
                commits++
                flush()
                return true
            }
            override fun apply() {
                applies++
                flush()
            }
        }
    }

    @Test fun defaultIsCommit() {
        val p = FakePrefs()
        SharedPrefsStore(p).apply {
            putBoolean("a", true)
            putString("b", "x")
            putString("b", null)
        }
        assertEquals(3, p.commits)
        assertEquals(0, p.applies)
        assertFalse(p.map.containsKey("b"))
    }

    @Test fun asyncIsApply() {
        val p = FakePrefs()
        val s = SharedPrefsStore(p, async = true)
        s.putBoolean("a", true)
        s.putString("b", "x")
        assertEquals(0, p.commits)
        assertEquals(2, p.applies)
        assertTrue(s.getBoolean("a", false))
        assertEquals("x", s.getString("b"))
    }

    /** Старт процесса: «напрямую» — через apply(), повторный старт ничего не пишет. */
    @Test fun connectionDefaultsAtStartupUseApplyAndStayIdempotent() {
        val ours = FakePrefs()
        val upstream = FakePrefs().also { it.map[ConnectionDefaults.UPSTREAM_FORCE_RELAY_KEY] = true }
        ConnectionDefaults.apply(SharedPrefsStore(ours, async = true), SharedPrefsStore(upstream, async = true))
        assertEquals(false, upstream.map[ConnectionDefaults.UPSTREAM_FORCE_RELAY_KEY])
        assertEquals(true, ours.map[ConnectionDefaults.MARKER])
        assertEquals(0, ours.commits + upstream.commits)

        upstream.map[ConnectionDefaults.UPSTREAM_FORCE_RELAY_KEY] = true // клиент включил ретранслятор сам
        val writes = ours.applies + upstream.applies
        ConnectionDefaults.apply(SharedPrefsStore(ours, async = true), SharedPrefsStore(upstream, async = true))
        assertEquals(writes, ours.applies + upstream.applies)
        assertEquals(true, upstream.map[ConnectionDefaults.UPSTREAM_FORCE_RELAY_KEY])
    }
}
