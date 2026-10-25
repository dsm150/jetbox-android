package net.jetnet.jetbox.link

/**
 * Разбор интента `JetBoxActivity` без Android-классов — проверяется на JVM.
 * Аналог `onOpenURL`/`onContinueUserActivity` iOS: Android приносит ссылку в `Intent.dataString`.
 */
object LinkIntent {
    /** `Intent.ACTION_VIEW`. */
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** `Intent.ACTION_MAIN`. */
    const val ACTION_MAIN = "android.intent.action.MAIN"

    /**
     * Ссылка из интента — **целиком**, с фрагментом (`#k=…`): ключ живёт во фрагменте, поэтому
     * `dataString`, а не `data.path`/`query`. `null` — ссылки нет (ярлык, «Недавние»).
     *
     * [launchedFromHistory] — `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`: «Недавние» повторяют исходный
     * интент, и ссылка, уже открытая однажды, иначе разбиралась бы снова.
     */
    fun linkFrom(action: String?, dataString: String?, launchedFromHistory: Boolean): String? {
        if (launchedFromHistory || action != ACTION_VIEW) return null
        return dataString?.takeIf { it.isNotEmpty() }
    }

    /**
     * Приложение открыли просто так — ярлык (`MAIN`) или уведомление и плитка (без действия), без
     * ссылки. В расширенном режиме такое открытие ведёт в экраны NetBird, как в iOS.
     */
    fun isPlainOpen(action: String?, dataString: String?): Boolean =
        (action == null || action == ACTION_MAIN) && dataString.isNullOrEmpty()
}
