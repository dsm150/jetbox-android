package net.jetnet.jetbox

import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.logic.VpnDisplayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JetBoxNotificationsTest {
    @Test fun asksOnceOnAndroid13AndLater() {
        assertTrue(JetBoxNotifications.shouldAsk(sdkInt = 33, granted = false, asked = false))
        assertFalse("уже спрашивали", JetBoxNotifications.shouldAsk(sdkInt = 36, granted = false, asked = true))
        assertFalse("уже разрешено", JetBoxNotifications.shouldAsk(sdkInt = 36, granted = true, asked = false))
        assertFalse("до Android 13 разрешение не нужно", JetBoxNotifications.shouldAsk(sdkInt = 32, granted = false, asked = false))
    }

    @Test fun triggeredByConnectedOrProtected() {
        assertTrue(JetBoxNotifications.isTrigger(VpnDisplayState.CONNECTED, Step.Idle))
        assertTrue(JetBoxNotifications.isTrigger(VpnDisplayState.CONNECTING, Step.Protected(null)))
        assertFalse(JetBoxNotifications.isTrigger(VpnDisplayState.CONNECTING, Step.Idle))
        assertFalse(JetBoxNotifications.isTrigger(VpnDisplayState.DISCONNECTED, Step.NeedsVpnConsent))
    }
}
