package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Трафик в листе — как `ByteCountFormatter(.file)` iOS (эталон: «48,2 МБ / 6,1 МБ»). */
class ByteCountFormatTest {
    private val ru = Locale.forLanguageTag("ru-RU")
    private val en = Locale.US

    @Test fun iosReferenceRu() {
        assertEquals("48,2 МБ", ByteCountFormat.format(48_200_000, ru))
        assertEquals("6,1 МБ", ByteCountFormat.format(6_100_000, ru))
    }

    @Test fun iosReferenceEn() {
        assertEquals("48.2 MB", ByteCountFormat.format(48_200_000, en))
        assertEquals("6.1 MB", ByteCountFormat.format(6_100_000, en))
    }

    @Test fun unitsAndDigits() {
        assertEquals("12 КБ", ByteCountFormat.format(12_345, ru))
        assertEquals("6 МБ", ByteCountFormat.format(6_000_000, ru))
        assertEquals("1,23 ГБ", ByteCountFormat.format(1_234_567_890, ru))
        assertEquals("1.23 GB", ByteCountFormat.format(1_234_567_890, en))
        assertEquals("2.5 TB", ByteCountFormat.format(2_500_000_000_000, en))
    }

    @Test fun roundingBumpsUnit() {
        assertEquals("1 МБ", ByteCountFormat.format(999_960, ru))
        assertEquals("1 GB", ByteCountFormat.format(999_960_000, en))
    }

    @Test fun smallValues() {
        assertEquals("Ноль КБ", ByteCountFormat.format(0, ru))
        assertEquals("Zero KB", ByteCountFormat.format(0, en))
        assertEquals("512 bytes", ByteCountFormat.format(512, en))
        assertEquals("1 byte", ByteCountFormat.format(1, en))
        assertEquals("1 байт", ByteCountFormat.format(1, ru))
        assertEquals("3 байта", ByteCountFormat.format(3, ru))
        assertEquals("512 байт", ByteCountFormat.format(512, ru))
    }
}
