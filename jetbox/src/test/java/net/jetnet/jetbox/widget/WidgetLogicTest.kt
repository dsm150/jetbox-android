package net.jetnet.jetbox.widget

import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.logic.VpnDisplayState.CONNECTED
import net.jetnet.jetbox.logic.VpnDisplayState.CONNECTING
import net.jetnet.jetbox.logic.VpnDisplayState.DISCONNECTED
import net.jetnet.jetbox.logic.VpnDisplayState.DISCONNECTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetLogicTest {

    @Test fun noBoxOpensAppInEveryState() {
        VpnDisplayState.entries.forEach { s ->
            assertEquals(WidgetTap.OPEN_APP, widgetTap(hasBox = false, consentGranted = true, state = s))
            assertEquals(WidgetTap.OPEN_APP, widgetTap(hasBox = false, consentGranted = false, state = s))
        }
    }

    @Test fun noVpnConsentOpensApp() {
        VpnDisplayState.entries.forEach { s ->
            assertEquals(WidgetTap.OPEN_APP, widgetTap(hasBox = true, consentGranted = false, state = s))
        }
    }

    @Test fun toggles() {
        assertEquals(WidgetTap.START, widgetTap(true, true, DISCONNECTED))
        assertEquals(WidgetTap.STOP, widgetTap(true, true, CONNECTED))
        assertEquals(WidgetTap.STOP, widgetTap(true, true, CONNECTING)) // отменить зависшее подключение
        assertEquals(WidgetTap.NONE, widgetTap(true, true, DISCONNECTING))
    }

    /** Ядро запущено, но без сети молчит: «Отключено» на виджете — касание выключает, а не стартует. */
    @Test fun runningEngineWithoutEventsIsStopped() {
        assertEquals(WidgetTap.STOP, widgetTap(true, true, DISCONNECTED, engineRunning = true))
        assertEquals(WidgetTap.STOP, widgetTap(true, true, CONNECTED, engineRunning = true))
        assertEquals(WidgetTap.NONE, widgetTap(true, true, DISCONNECTING, engineRunning = true))
        assertEquals(WidgetTap.OPEN_APP, widgetTap(false, true, DISCONNECTED, engineRunning = true))
    }

    /**
     * Процесс умер вместе с туннелем, виджет остался «включённым». Касание — только перерисовать
     * (как iOS: stop без туннеля + reloadAllTimelines), не включать: клиент касался «вкл», чтобы выключить.
     */
    @Test fun staleOnStateOnlyRefreshes() {
        listOf(CONNECTED, CONNECTING, DISCONNECTING).forEach { s ->
            assertTrue("$s", widgetStateIsStale(s, engineRunning = false, serviceForeground = false))
            assertEquals("$s", WidgetTap.REFRESH, widgetTap(true, true, s, stale = true))
        }
    }

    /** Туннель жив — ядро работает или сервис в foreground (свежий процесс, движок не привязан): не устарело. */
    @Test fun liveTunnelIsNotStale() {
        listOf(CONNECTED, CONNECTING, DISCONNECTING).forEach { s ->
            assertFalse("$s", widgetStateIsStale(s, engineRunning = true, serviceForeground = false))
            assertFalse("$s", widgetStateIsStale(s, engineRunning = false, serviceForeground = true))
        }
        assertEquals(WidgetTap.STOP, widgetTap(true, true, CONNECTED, stale = widgetStateIsStale(CONNECTED, false, true)))
    }

    /** «Отключено» устаревшим не бывает: касание включает, как и раньше. */
    @Test fun disconnectedIsNeverStale() {
        assertFalse(widgetStateIsStale(DISCONNECTED, engineRunning = false, serviceForeground = false))
        assertEquals(WidgetTap.START, widgetTap(true, true, DISCONNECTED, stale = false))
    }

    /** Без JetBox или согласия VPN — окно, даже если сохранённое устарело. */
    @Test fun staleStillOpensAppWithoutBoxOrConsent() {
        assertEquals(WidgetTap.OPEN_APP, widgetTap(false, true, CONNECTED, stale = true))
        assertEquals(WidgetTap.OPEN_APP, widgetTap(true, false, CONNECTED, stale = true))
    }

    @Test fun layoutBySize() {
        assertEquals(WidgetLayout.COMPACT, widgetLayoutFor(110f, 40f))   // 2×1
        assertEquals(WidgetLayout.COMPACT, widgetLayoutFor(300f, 90f))   // 4×1
        assertEquals(WidgetLayout.SQUARE, widgetLayoutFor(150f, 160f))   // 2×2
        assertEquals(WidgetLayout.WIDE, widgetLayoutFor(320f, 160f))     // 4×2
        assertEquals(WidgetLayout.COMPACT, widgetLayoutFor(50f, 50f))    // меньше минимума
    }

    @Test fun stateCodecRoundTripsAndDefaultsToDisconnected() {
        VpnDisplayState.entries.forEach { assertEquals(it, WidgetStateCodec.decode(WidgetStateCodec.encode(it))) }
        assertEquals(DISCONNECTED, WidgetStateCodec.decode(null))
        assertEquals(DISCONNECTED, WidgetStateCodec.decode("garbage"))
        assertEquals("jetbox.lastState", WidgetStateCodec.KEY)
    }

    /** Манифест: провайдер принимает обновления системы, приёмник касания снаружи недоступен. */
    @Test fun manifestDeclaresWidget() {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val manifest = File(checkNotNull(dir), "jetbox/src/main/AndroidManifest.xml").readText()
        fun receiver(name: String): String =
            Regex("<receiver\\b[^>]*android:name=\"[^\"]*$name\"[^>]*?(/>|>[\\s\\S]*?</receiver>)").find(manifest)!!.value
        val provider = receiver("widget\\.JetBoxWidget")
        assertTrue(provider.contains("android:exported=\"true\""))
        assertTrue(provider.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        assertTrue(provider.contains("@xml/jb_widget_info"))
        val toggle = receiver("widget\\.WidgetToggleReceiver")
        assertTrue(toggle.contains("android:exported=\"false\""))
        assertFalse(toggle.contains("<intent-filter"))
    }
}
