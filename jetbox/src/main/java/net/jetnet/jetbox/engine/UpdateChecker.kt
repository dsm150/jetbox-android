package net.jetnet.jetbox.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.store.KeyValueStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/** Откуда берётся `version.json`. Тело ответа или `null` — нет файла, нет сети, что угодно ещё. */
fun interface UpdateSource {
    suspend fun fetch(): String?
}

/**
 * `GET` по [url] обычным HttpURLConnection: таймауты 10 с, без кэша и cookie, без переходов по
 * редиректам, только код 200 и не больше [MAX_BYTES]. О пользователе в запросе — ничего, кроме версии
 * приложения в `User-Agent`.
 */
class HttpUpdateSource(private val url: URL, private val userAgent: String) : UpdateSource {

    override suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext null
        }
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            if (conn.contentLength > MAX_BYTES) return@withContext null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@withContext null
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // SecurityException без INTERNET, IllegalArgumentException на странном адресе — тоже молча.
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000
        /** `version.json` — сотня байт; всё, что больше, — не он. */
        const val MAX_BYTES = 16 * 1024
    }
}

/**
 * Проверка обновлений для сборки с адресом `version.json` [updateUrl] (`BuildConfig.UPDATE_URL`):
 * пустой адрес — магазинные сборки (`STORE` = `rustore`/`play`, JETBOX-BUILD.md §6.1) — проверка
 * выключена и в сеть не ходит; иначе — [sourceFor] по этому адресу ([HttpUpdateSource]).
 */
fun updateCheckerFor(
    updateUrl: String,
    kv: KeyValueStore,
    currentCode: Int,
    userAgent: String,
    io: CoroutineContext = Dispatchers.IO,
    sourceFor: (URL) -> UpdateSource = { HttpUpdateSource(it, userAgent) },
): UpdateChecker = UpdateChecker(
    source = if (updateUrl.isEmpty()) UpdateSource { null } else sourceFor(URL(updateUrl)),
    kv = kv,
    currentCode = currentCode,
    enabled = updateUrl.isNotEmpty(),
    io = io,
)

/**
 * Проверка обновлений для сборки «файл с сайта» (`BuildConfig.STORE == "apk"`, адрес — `BuildConfig.UPDATE_URL`);
 * в сборке для магазина ([enabled] = false) ничего не спрашивает и ничего не показывает — обновляет магазин.
 *
 * Сама по себе — не чаще раза в сутки (метка последней попытки в настройках JetBox, удачной или нет:
 * пока файла на сайте нет, приложение не стучится туда при каждом открытии). По просьбе экрана
 * (открыли настройки) — сразу, но не чаще раза в [FORCE_INTERVAL_MS]: настройки открывают и закрывают
 * подряд. Ошибки — молча: строки просто нет. Последний ответ хранится, чтобы
 * строка была видна сразу после запуска, без сети.
 *
 * [available] — только версия новее установленной ([currentCode]); после обновления приложения
 * сохранённое предложение само перестаёт показываться.
 *
 * Настройки (SharedPreferences) в [check] читаются и пишутся в [io]: `apply()` с главного потока
 * StrictMode считает записью на диск. В тестах — [kotlin.coroutines.EmptyCoroutineContext].
 */
class UpdateChecker(
    private val source: UpdateSource,
    private val kv: KeyValueStore,
    private val currentCode: Int,
    private val enabled: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val _available = MutableStateFlow(if (enabled) saved()?.takeIf { it.isNewer(currentCode) } else null)
    val available: StateFlow<UpdateInfo?> = _available

    private val running = Mutex()

    /**
     * Спросить сайт: [force] — сейчас (если с прошлой попытки прошла минута), иначе — если прошли
     * сутки.
     */
    suspend fun check(force: Boolean = false) {
        if (!enabled) return
        // Уже спрашиваем — второй запрос не нужен, ответ придёт в [available].
        if (!running.tryLock()) return
        try {
            val now = clock()
            val last = withContext(io) { kv.getString(K_CHECKED_AT)?.toLongOrNull() }
            // Часы ушли назад (last в будущем) — не ждём «завтра» неделями.
            val wait = if (force) FORCE_INTERVAL_MS else INTERVAL_MS
            if (last != null && now >= last && now - last < wait) return
            withContext(io) { kv.putString(K_CHECKED_AT, now.toString()) }
            val body = try {
                source.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val info = body?.let { UpdateInfo.parse(it) }
            if (info == null) {
                Log.i(TAG, "JetBox: обновлений не узнали — молчим")
                return
            }
            withContext(io) { kv.putString(K_INFO, info.toJson()) }
            _available.value = info.takeIf { it.isNewer(currentCode) }
        } finally {
            running.unlock()
        }
    }

    private fun saved(): UpdateInfo? = kv.getString(K_INFO)?.let { UpdateInfo.parse(it) }

    companion object {
        const val INTERVAL_MS = 24 * 60 * 60 * 1000L
        /** Принудительная проверка (открыли настройки) — не чаще раза в минуту. */
        const val FORCE_INTERVAL_MS = 60 * 1000L
        private const val K_CHECKED_AT = "jetbox.update.checkedAt"
        private const val K_INFO = "jetbox.update.info"
        private const val TAG = "JetBox"
    }
}
