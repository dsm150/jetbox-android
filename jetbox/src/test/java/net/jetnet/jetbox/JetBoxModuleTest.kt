package net.jetnet.jetbox

import org.junit.Assert.assertEquals
import org.junit.Test

class JetBoxModuleTest {
    @Test fun moduleName() = assertEquals("JetBox", JetBoxModule.PRODUCT_NAME)

    /** Без -Pjetbox.store сборка — для сайта (своя проверка обновлений). */
    @Test fun storeDefaultsToApk() = assertEquals("apk", BuildConfig.STORE)
}
