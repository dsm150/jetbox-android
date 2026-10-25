package net.jetnet.jetbox.logic

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

/**
 * Что лежит в `https://box.jet-net.net/android/version.json` — свежая версия APK для сборки «файл с
 * сайта» (в сборке для магазина обновляет магазин):
 * `{"versionCode":11,"versionName":"1.1","url":"https://box.jet-net.net/android/JetBox.apk","sha256":"…"}`.
 *
 * Разбор строгий: файл приходит из сети, а [url] потом откроет браузер. Всё сомнительное — `null`,
 * и приложение просто ничего не показывает. Лишние поля не мешают (на будущее).
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** Только `https://box.jet-net.net/…`. */
    val url: String,
    /** SHA-256 файла, 64 hex в нижнем регистре; на будущее — приложение само файл не качает. */
    val sha256: String?,
) {
    /** Новее ли установленной сборки [currentCode]. */
    fun isNewer(currentCode: Int): Boolean = versionCode > currentCode

    fun toJson(): String = JSONObject().apply {
        put(K_CODE, versionCode)
        put(K_NAME, versionName)
        put(K_URL, url)
        if (sha256 != null) put(K_SHA, sha256)
    }.toString()

    companion object {
        const val HOST = "box.jet-net.net"

        private const val K_CODE = "versionCode"
        private const val K_NAME = "versionName"
        private const val K_URL = "url"
        private const val K_SHA = "sha256"

        /** «1.1», «1.2.3-beta+4» — коротко и без управляющих символов: строка идёт в интерфейс. */
        private val NAME = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,23}")
        private val SHA = Regex("[0-9a-fA-F]{64}")

        fun parse(text: String): UpdateInfo? {
            val o = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
            val code = when (val v = o.opt(K_CODE)) {
                is Int -> v
                else -> return null
            }
            if (code <= 0) return null
            val name = (o.opt(K_NAME) as? String)?.takeIf { NAME.matches(it) } ?: return null
            val url = (o.opt(K_URL) as? String)?.takeIf { isOurHttpsUrl(it) } ?: return null
            val sha = when (val v = o.opt(K_SHA)) {
                null, JSONObject.NULL -> null
                is String -> v.takeIf { SHA.matches(it) }?.lowercase() ?: return null
                else -> return null
            }
            return UpdateInfo(code, name, url, sha)
        }

        /**
         * `https://box.jet-net.net/<путь>`: ровно наш хост (не поддомен и не «box.jet-net.net.чужой»),
         * без логина в адресе, порт — только стандартный, путь непустой.
         */
        internal fun isOurHttpsUrl(raw: String): Boolean {
            val uri = try {
                URI(raw)
            } catch (e: URISyntaxException) {
                return false
            }
            if (!uri.isAbsolute || uri.isOpaque) return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.rawUserInfo != null) return false
            if (!uri.host.equals(HOST, ignoreCase = true)) return false
            if (uri.port != -1 && uri.port != 443) return false
            val path = uri.rawPath
            return !path.isNullOrEmpty() && path != "/"
        }
    }
}
