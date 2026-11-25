package net.jetnet.jetbox.engine

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.link.JetBoxLinkSignature
import net.jetnet.jetbox.logic.JetBoxFailure
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.MemoryStore
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JetBox safety в сценарии RU-приложения (слово владельца 25.11.2026, как iOS): подписанная ссылка — с любым хостом и
 * сервером; без подписи или с неверной — «Эта ссылка не от JetBox» до любого действия. Ссылки подписываются здесь же
 * тестовым ключом векторов (закрытая часть — байты 0x00..0x1f), в приложении его нет.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignedLinkOnboardingTest {
    private val key = "11111111-2222-4333-8444-555555555555"
    private val testPrivate = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val testPublic = Base64.getEncoder().encodeToString(testPrivate.generatePublicKey().encoded)

    /** Подпись фрагмента по спеке: сообщение — как в приложении ([JetBoxLinkSignature.signedMessage]). */
    private fun signed(base: String, fragment: String): String {
        val message = JetBoxLinkSignature.signedMessage("$fragment&s=" + "A".repeat(86))!!.message
        val signer = Ed25519Signer().apply { init(true, testPrivate); update(message, 0, message.size) }
        val s = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
        return "$base#$fragment&s=$s"
    }

    private class Env(scope: TestScope, keys: List<String>) {
        val engine = FakeEngine()
        val store = JetBoxStore(MemoryStore())
        val ob = JetBoxOnboarding(
            engine, store, CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            log = {}, clock = { 7_000L }, linkKeys = keys,
        )
        init {
            engine.storedFingerprint = { store.currentBox?.setupKeyFingerprint }
        }
    }

    @Test fun signedLinkAnyHostAnyServerConnects() = runTest {
        val env = Env(this, listOf(testPublic))
        val link = signed("https://setup.anywhere.example/connect", "k=$key&m=https%3A%2F%2Fmgmt.other.example%3A33073&e=x-exit&n=Office")
        assertTrue(env.ob.handle(link))
        val ready = env.ob.step.value as Step.Ready
        assertEquals("https://mgmt.other.example:33073", ready.payload.managementURL)
        env.engine.onStart = {
            it.emit(VpnDisplayState.CONNECTED)
            it.routes.value = listOf(RouteInfo("x-exit", "0.0.0.0/0", false))
        }
        env.ob.connect()
        advanceUntilIdle()
        assertEquals("https://mgmt.other.example:33073", env.engine.lastRegisterUrl)
        assertTrue(env.ob.step.value is Step.Protected)
    }

    /** Старая ссылка Кабинета без `s` (как выдаёт RU-Кабинет сейчас) — отказ; подключение и запись не тронуты. */
    @Test fun oldLinkWithoutSignatureRefusedBeforeAnything() = runTest {
        val env = Env(this, listOf(testPublic))
        val old = JetBoxBox("https://netbird.jet-net.net", "home-exit", "home-exit", "Дом", 1L)
        env.store.currentBox = old
        env.engine.emit(VpnDisplayState.CONNECTED)
        assertTrue(env.ob.handle("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=other-exit&n=Офис"))
        assertEquals(Step.Failed(JetBoxFailure.NotFromJetBox), env.ob.step.value)
        assertTrue(!JetBoxFailure.NotFromJetBox.canRetry)
        advanceUntilIdle()
        assertEquals(0, env.engine.isOnlineCalls)
        assertEquals(0, env.engine.registerCalls)
        assertEquals(0, env.engine.stopCalls)
        assertEquals(old, env.store.currentBox)
        assertEquals(VpnDisplayState.CONNECTED, env.engine.state.value)
    }

    /** Подпись есть, но параметр дописан после подписи или ключ подписи чужой — тот же отказ. */
    @Test fun tamperedOrForeignSignatureRefused() = runTest {
        val env = Env(this, listOf(testPublic))
        val good = signed("jetbox://connect", "k=$key&m=https%3A%2F%2Fnetbird.jet-net.net&e=home-exit")
        assertTrue(env.ob.handle("$good&x=0"))
        assertEquals(Step.Failed(JetBoxFailure.NotFromJetBox), env.ob.step.value)
        // Боевые ключи тестовую подпись не принимают.
        val prod = Env(this, JetBoxLinkSignature.PUBLIC_KEYS)
        assertTrue(prod.ob.handle(good))
        assertEquals(Step.Failed(JetBoxFailure.NotFromJetBox), prod.ob.step.value)
        advanceUntilIdle()
        assertEquals(0, env.engine.registerCalls + prod.engine.registerCalls)
    }

    /** Подписанная ссылка RU-Кабинета: `k` в верхнем регистре, без `e`, с `b` и `p` — принимается. */
    @Test fun signedCabinetLinkUpperKeyNoExit() = runTest {
        val env = Env(this, listOf(testPublic))
        val link = signed(
            "https://box.jet-net.net/connect",
            "k=${key.uppercase()}&m=https%3A%2F%2Fnetbird.jet-net.net&n=Fabrika&b=100.64.0.7&p=android",
        )
        assertTrue(env.ob.handle(link))
        val ready = env.ob.step.value as Step.Ready
        assertEquals(key.uppercase(), ready.payload.setupKey)
        assertEquals(null, ready.payload.exitNodeName)
    }

    /** Ответ verify (вход по рабочей почте, TLS) — доверенный, подпись не нужна. */
    @Test fun trustedVerifyLinkAccepted() = runTest {
        val env = Env(this, listOf(testPublic))
        assertTrue(env.ob.handle("https://box.jet-net.net/connect#k=$key&m=https%3A%2F%2Fnetbird.jet-net.net", trusted = true))
        assertTrue(env.ob.step.value is Step.Ready)
    }
}
