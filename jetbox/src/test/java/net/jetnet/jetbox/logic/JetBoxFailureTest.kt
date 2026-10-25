package net.jetnet.jetbox.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сопоставление ошибок ядра — правила дословно из `JetBoxOnboarding.failure(from:)` (iOS). */
class JetBoxFailureTest {

    private fun c(error: String?, online: Boolean = true) = JetBoxFailure.classify(error, online)

    @Test
    fun offlineWithoutTextOrWithNetworkText() {
        assertEquals(JetBoxFailure.Offline, c(null, online = false))
        assertEquals(JetBoxFailure.Offline, c("", online = false))
        assertEquals(
            JetBoxFailure.Offline,
            c("failed to create auth client: create connection: dial context: context deadline exceeded", online = false),
        )
    }

    @Test
    fun keyTextBeatsOfflineFlag() {
        // Флаг VALIDATED на Android бывает ложным (РФ): ключ — это не «нет интернета».
        val t = "setup key is invalid"
        assertEquals(JetBoxFailure.KeyRejected(t), c(t, online = false))
    }

    @Test
    fun realCoreTexts() {
        val key = "login failed: rpc error: code = PermissionDenied desc = couldn't add peer: setup key is invalid"
        assertEquals(JetBoxFailure.KeyRejected(key), c(key))
        val dial = "failed to create auth client: create connection: dial context: context deadline exceeded"
        assertEquals(JetBoxFailure.ManagementUnreachable(dial), c(dial))
        val noConn = "login failed: no connection to management"
        assertEquals(JetBoxFailure.ManagementUnreachable(noConn), c(noConn))
        val cert = "rpc error: code = Unavailable desc = connection error: x509: certificate has expired or is not yet valid"
        assertEquals(JetBoxFailure.ManagementUnreachable(cert), c(cert))
    }

    @Test
    fun permissionDeniedAndNotRegisteredAreKeyRejected() {
        val t = "rpc error: code = PermissionDenied desc = peer is not registered"
        assertEquals(JetBoxFailure.KeyRejected(t), c(t))
        // сертификат по-прежнему раньше ключа
        val cert = "x509: certificate signed by unknown authority: PermissionDenied"
        assertEquals(JetBoxFailure.ManagementUnreachable(cert), c(cert))
    }

    @Test
    fun otherNetworkPhrasesAreManagementUnreachable() {
        for (t in listOf(
            "management service public key: rpc failed",
            "dial tcp: network is unreachable",
            "read tcp: i/o timeout",
        )) assertEquals(t, JetBoxFailure.ManagementUnreachable(t), c(t))
    }

    @Test
    fun setupKeyAndExpiredAreKeyRejected() {
        assertEquals(JetBoxFailure.KeyRejected("setup key is invalid"), c("setup key is invalid"))
        assertEquals(JetBoxFailure.KeyRejected("Setup Key is expired"), c("Setup Key is expired"))
        assertEquals(JetBoxFailure.KeyRejected("setup key usage limit reached"), c("setup key usage limit reached"))
        assertEquals(JetBoxFailure.KeyRejected("token expired"), c("token expired"))
    }

    @Test
    fun networkErrorsAreManagementUnreachable() {
        for (t in listOf(
            "rpc error: context deadline exceeded",
            "dial tcp 1.2.3.4:443: connection refused",
            "lookup x: no such host",
            "rpc error: code = Unavailable desc = down",
        )) {
            assertEquals(t, JetBoxFailure.ManagementUnreachable(t), c(t))
        }
    }

    @Test
    fun keyRuleBeatsNetworkRule() {
        // Ключ проверяется раньше сети (кроме сертификата — он раньше всех).
        val t = "setup key rejected: deadline exceeded"
        assertEquals(JetBoxFailure.KeyRejected(t), c(t))
    }

    @Test
    fun unknownTextIsOther() {
        assertEquals(JetBoxFailure.Other("something odd"), c("something odd"))
        assertEquals(JetBoxFailure.Other(""), c(null))
        assertEquals(JetBoxFailure.Other(""), c(""))
    }

    @Test
    fun detailsAndRetry() {
        assertNull(JetBoxFailure.BrokenLink.details)
        assertNull(JetBoxFailure.Offline.details)
        assertNull(JetBoxFailure.TunnelFailed.details)
        assertNull(JetBoxFailure.VpnPermissionDenied.details)
        assertEquals("d", JetBoxFailure.ManagementUnreachable("d").details)
        assertEquals("d", JetBoxFailure.KeyRejected("d").details)
        assertEquals("d", JetBoxFailure.Other("d").details)
        assertFalse(JetBoxFailure.BrokenLink.canRetry)
        for (f in listOf(
            JetBoxFailure.Offline, JetBoxFailure.TunnelFailed, JetBoxFailure.VpnPermissionDenied,
            JetBoxFailure.ManagementUnreachable(null), JetBoxFailure.KeyRejected(null), JetBoxFailure.Other("x"),
        )) assertTrue("$f", f.canRetry)
    }
}
