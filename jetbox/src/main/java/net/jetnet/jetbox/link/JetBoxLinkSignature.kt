package net.jetnet.jetbox.link

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * JetBox safety — подпись ссылки подключения (спека VPN_Box
 * `docs/superpowers/specs/2026-11-23-link-signature-design.md`, порт iOS `JetBoxLinkSignature`, сборка 25).
 *
 * `s` = base64url без «=» от Ed25519 по сообщению `"JetBox safety v1\n"` + `"имя=значение\n"` для каждого
 * параметра фрагмента, кроме `s`, по возрастанию имени (байты UTF-8), значение — после percent-decoding.
 * Повтор любого имени (без учёта регистра, как разбор ссылки) — ссылка недействительна. Хост и путь ссылки
 * в подпись не входят. Шифрования нет — только проверка подлинности.
 *
 * Ed25519 — BouncyCastle: minSdk 26, а в платформе Android Ed25519 только с API 33.
 */
object JetBoxLinkSignature {
    /**
     * JetBox safety: открытые части ключа подписи ссылок (Ed25519, сырые 32 байта, base64). Подпись верна,
     * если сходится с любой. Закрытые — только на наших серверах; резервная — на случай утечки основной.
     * Тестовый ключ тест-векторов сюда не попадает: тесты передают свои ключи параметром.
     */
    val PUBLIC_KEYS: List<String> = listOf(
        "prLMY2riAVq0e1nuydlSO3FOFedlsNvxjKXsHUl8/Tg=", // JetBox safety: основная
        "Lr8e8586oEZnBZqS678ipTemL050ha5OUIG6mqGyLu0=", // JetBox safety: резервная
    )

    const val MESSAGE_PREFIX = "JetBox safety v1\n"

    class Signed(val message: ByteArray, val signature: ByteArray)

    /**
     * Сообщение и подпись из сырого фрагмента (без «#»). `null` — подписи нет, она не 64 байта, есть повтор
     * имени, пара без «=» или с пустым именем.
     */
    fun signedMessage(fragment: String): Signed? {
        val seen = HashSet<String>()
        val fields = ArrayList<Pair<String, String>>()
        var signature: ByteArray? = null
        for (pair in fragment.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) return null
            val name = pair.substring(0, eq)
            val lower = name.lowercase(Locale.ROOT)
            if (!seen.add(lower)) return null
            val value = JetBoxLink.percentDecode(pair.substring(eq + 1))
            if (lower == "s") {
                signature = base64UrlDecode(value)?.takeIf { it.size == SIGNATURE_SIZE } ?: return null
            } else {
                fields += name to value
            }
        }
        val sig = signature ?: return null
        fields.sortWith { a, b -> compareUtf8(a.first, b.first) }
        val message = ByteArrayOutputStream()
        message.write(MESSAGE_PREFIX.toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) message.write("$name=$value\n".toByteArray(Charsets.UTF_8))
        return Signed(message.toByteArray(), sig)
    }

    /** Подпись сходится с одной из [keys] (по умолчанию — открытые части JetBox safety). */
    fun isValid(fragment: String, keys: List<String> = PUBLIC_KEYS): Boolean {
        val signed = signedMessage(fragment) ?: return false
        return keys.any { verify(it, signed) }
    }

    private fun verify(publicKeyBase64: String, signed: Signed): Boolean {
        val raw = runCatching { Base64.getDecoder().decode(publicKeyBase64) }.getOrNull()
            ?.takeIf { it.size == Ed25519PublicKeyParameters.KEY_SIZE } ?: return false
        return runCatching {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(raw, 0))
            signer.update(signed.message, 0, signed.message.size)
            signer.verifySignature(signed.signature)
        }.getOrDefault(false)
    }

    /** base64url без «=»: только алфавит `A–Z a–z 0–9 - _`. */
    fun base64UrlDecode(text: String): ByteArray? {
        if (text.isEmpty() || !text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            return null
        }
        return runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
    }

    /** Сравнение по байтам UTF-8 (без знака), как iOS `Array(name.utf8).lexicographicallyPrecedes`. */
    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private const val SIGNATURE_SIZE = 64
}
