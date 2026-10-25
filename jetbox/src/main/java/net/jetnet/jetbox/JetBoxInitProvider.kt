package net.jetnet.jetbox

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Старт процесса JetBox — `JetBoxStartup.run()` (iOS) при запуске приложения. Провайдеры создаются до
 * `Application.onCreate` и до любой активности, поэтому разовые настройки применяются, даже если
 * туннель включат плиткой быстрых настроек раньше, чем клиент откроет окно. Ничего не отдаёт.
 */
class JetBoxInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { JetBoxProcess.startup(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
