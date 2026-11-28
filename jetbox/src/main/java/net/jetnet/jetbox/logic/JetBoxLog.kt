package net.jetnet.jetbox.logic

import android.util.Log

/**
 * Журнал JetBox: строка идёт в logcat и в кольцевой буфер последних [CAPACITY] строк — их (после
 * [DiagnosticsScrubber]) прикладывает «Помощь» (спека VPN_Box 2026-11-28-app-help-and-dns-check, §2.3). Строки ядра
 * (GoLog) сюда не попадают. На JVM-тестах logcat-заглушка молчит, буфер работает.
 */
object JetBoxLog {
    const val CAPACITY = 200

    private val lines = ArrayDeque<String>()
    private val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)

    fun i(tag: String, message: String) {
        add("I", tag, message)
        runCatching { Log.i(tag, message) }
    }

    fun d(tag: String, message: String) {
        add("D", tag, message)
        runCatching { Log.d(tag, message) }
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        add("W", tag, message + (error?.let { ": ${it.javaClass.simpleName}" } ?: ""))
        runCatching { if (error != null) Log.w(tag, message, error) else Log.w(tag, message) }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        add("E", tag, message + (error?.let { ": ${it.javaClass.simpleName}" } ?: ""))
        runCatching { if (error != null) Log.e(tag, message, error) else Log.e(tag, message) }
    }

    /** Последние [count] строк, старые первыми — как есть, без фильтра (фильтр — у потребителя). */
    fun last(count: Int): List<String> = synchronized(lines) { lines.toList().takeLast(count) }

    internal fun clear() = synchronized(lines) { lines.clear() }

    private fun add(level: String, tag: String, message: String) {
        val stamp = synchronized(time) { time.format(java.util.Date()) }
        synchronized(lines) {
            lines.addLast("$stamp $level/$tag: $message")
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }
}
