package net.jetnet.jetbox.logic

import net.jetnet.jetbox.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порт `JetBoxConnectionDefaultsTests` (iOS): «напрямую» ставится один раз, выбор клиента потом не трогаем. */
class ConnectionDefaultsTest {
    private val relayKey = "isConnectionForceRelayed"

    @Test fun firstRunTurnsForcedRelayOffAndSetsMarker() {
        val ours = MemoryStore()
        val upstream = MemoryStore().also { it.putBoolean(relayKey, true) }
        ConnectionDefaults.apply(ours, upstream)
        assertFalse(upstream.getBoolean(relayKey, true))
        assertTrue(ours.getBoolean(ConnectionDefaults.MARKER, false))
    }

    @Test fun clientsLaterChoiceSurvives() {
        val ours = MemoryStore()
        val upstream = MemoryStore().also { it.putBoolean(relayKey, true) }
        ConnectionDefaults.apply(ours, upstream)
        upstream.putBoolean(relayKey, true) // включил сам
        ConnectionDefaults.apply(ours, upstream)
        assertTrue(upstream.getBoolean(relayKey, false))
    }

    @Test fun markerKeyIsTheAgreedOne() {
        assertEquals("jetbox.directByDefaultApplied", ConnectionDefaults.MARKER)
    }
}
