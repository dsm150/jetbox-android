package net.jetnet.jetbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JetBoxModuleTest {
    @Test fun moduleName() = assertEquals("JetBox", JetBoxModule.PRODUCT_NAME)

    /**
     * Тесты идут в любом варианте (`-Pjetbox.store=apk|rustore|play`): вариант — один из трёх.
     * Что без параметра это `apk` и что зависит от варианта — `PlayStoreVariantTest`.
     */
    @Test fun storeIsKnown() =
        assertTrue("STORE = ${BuildConfig.STORE}", BuildConfig.STORE in setOf("apk", "rustore", "play"))
}
