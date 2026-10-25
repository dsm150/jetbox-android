package net.jetnet.jetbox.logic

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Размер трафика так же, как `ByteCountFormatter` (iOS) со стилем `.file` в листе подробностей:
 * десятичные единицы (1 КБ = 1000 байт), КБ — без дробной части, МБ — один знак, ГБ и выше — два,
 * лишние нули не дописываются; разделитель дроби — по языку («48,2 МБ» / «48.2 MB»).
 *
 * Системный `Formatter.formatShortFileSize` округляет иначе («48 МБ»), поэтому — своё.
 * Названия единиц — ru и en (остальные языки — как en): у приложения только эти два каталога.
 */
object ByteCountFormat {
    private val UNITS_EN = listOf("KB", "MB", "GB", "TB", "PB")
    private val UNITS_RU = listOf("КБ", "МБ", "ГБ", "ТБ", "ПБ")
    private val DIGITS = intArrayOf(0, 1, 2, 2, 2)

    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val ru = locale.language == "ru"
        if (bytes == 0L) return if (ru) "Ноль КБ" else "Zero KB"
        if (bytes < 1000) return "$bytes ${byteWord(bytes, ru)}"
        val units = if (ru) UNITS_RU else UNITS_EN
        var value = bytes / 1000.0
        var i = 0
        // Единицу выбираем после округления: 999 960 байт — «1 МБ», а не «1 000 КБ».
        while (i < units.lastIndex && round(value, DIGITS[i]) >= 1000.0) {
            value /= 1000.0
            i++
        }
        val df = DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).apply {
            maximumFractionDigits = DIGITS[i]
            minimumFractionDigits = 0
            roundingMode = RoundingMode.HALF_EVEN
            isGroupingUsed = false
        }
        return "${df.format(value)} ${units[i]}"
    }

    private fun round(v: Double, digits: Int): Double =
        java.math.BigDecimal(v).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    private fun byteWord(n: Long, ru: Boolean): String {
        if (!ru) return if (n == 1L) "byte" else "bytes"
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> "байт"
            m10 in 2..4 && m100 !in 12..14 -> "байта"
            else -> "байт"
        }
    }
}
