package net.jetnet.jetbox.widget

import net.jetnet.jetbox.logic.VpnDisplayState

/**
 * Что делает касание виджета — ветки `WidgetActionButton` (iOS). [REFRESH] — только перерисовать:
 * виджет показывал устаревшее «включено».
 */
enum class WidgetTap { OPEN_APP, STOP, START, NONE, REFRESH }

/**
 * Сохранённое состояние виджета устарело: виджет рисует не «Отключено», а туннеля нет — ни ядро
 * не работает ([engineRunning]), ни сервис туннеля не в foreground ([serviceForeground]). Так бывает,
 * когда процесс умер вместе с туннелем (система, «Остановить» в настройках): новый процесс читает
 * запись прошлого, а движок ещё не привязан и `isRunning` молчит — поэтому и сервис.
 */
fun widgetStateIsStale(saved: VpnDisplayState, engineRunning: Boolean, serviceForeground: Boolean): Boolean =
    saved != VpnDisplayState.DISCONNECTED && !engineRunning && !serviceForeground

/**
 * Решение по касанию (чистая логика, без Android):
 * * JetBox на телефоне не прописан или нет согласия VPN — открыть приложение (iOS: `needsAppSetup` →
 *   `netbird://connect`): без окна туннель в этих случаях не включить;
 * * туннель работает или поднимается — выключить (iOS в «Подключаем…» касание глотает; у нас оно
 *   отменяет зависшее подключение, иначе виджет бесполезен, пока ядро не сдастся);
 * * выключается — ничего (iOS: переходное состояние);
 * * выключен — включить. Но если ядро уже запущено ([engineRunning]) — выключить: без сети ядро
 *   стоит на паузе и не шлёт «Подключаем…», состояние остаётся «Отключено», а повторный старт
 *   ничего бы не сделал.
 * * состояние устарело ([stale], см. [widgetStateIsStale]) — только перерисовать в «Отключено».
 *   Как iOS: `ToggleVPNIntent` на «выключить» при уже выключенном туннеле зовёт пустой stop и
 *   перерисовывает виджет. Включать нельзя: клиент касался «включённого» переключателя, чтобы выключить.
 */
fun widgetTap(
    hasBox: Boolean,
    consentGranted: Boolean,
    state: VpnDisplayState,
    engineRunning: Boolean = false,
    stale: Boolean = false,
): WidgetTap = when {
    !hasBox || !consentGranted -> WidgetTap.OPEN_APP
    stale -> WidgetTap.REFRESH
    state == VpnDisplayState.CONNECTED || state == VpnDisplayState.CONNECTING -> WidgetTap.STOP
    state == VpnDisplayState.DISCONNECTED && engineRunning -> WidgetTap.STOP
    state == VpnDisplayState.DISCONNECTING -> WidgetTap.NONE
    else -> WidgetTap.START
}

/**
 * Разметка по размеру виджета (dp). Пороги — те же, что ключи карты размеров на API 31+
 * ([JetBoxWidget]): система берёт из подходящих по размеру вариант с наибольшей площадью.
 * * [SQUARE] — малый виджет iOS (`SmallWidgetView`): знак, «JetBox», состояние, переключатель;
 * * [WIDE] — средний iOS (`MediumWidgetView`): знак слева, текст, переключатель справа;
 * * [COMPACT] — 2×1 и 4×1, которых у iOS нет: знак и текст в строку, без переключателя.
 */
enum class WidgetLayout(val minWidthDp: Float, val minHeightDp: Float) {
    COMPACT(110f, 40f),
    SQUARE(110f, 140f),
    WIDE(250f, 100f),
}

fun widgetLayoutFor(widthDp: Float, heightDp: Float): WidgetLayout =
    WidgetLayout.entries
        .filter { widthDp >= it.minWidthDp && heightDp >= it.minHeightDp }
        .maxByOrNull { it.minWidthDp * it.minHeightDp }
        ?: WidgetLayout.COMPACT

/** Последнее состояние туннеля в prefs `jetbox` (`jetbox.lastState`): имя константы. */
object WidgetStateCodec {
    const val KEY = "jetbox.lastState"

    fun encode(state: VpnDisplayState): String = state.name

    fun decode(value: String?): VpnDisplayState =
        VpnDisplayState.entries.firstOrNull { it.name == value } ?: VpnDisplayState.DISCONNECTED
}
