package net.jetnet.jetbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Порт `JetBoxWordingTests.swift`. Правило владельца: в клиентских текстах нет слова «коробка»
 * (только JetBox, объект, устройство) и нет лексики «обход блокировок». Проверяется по значениям
 * `strings.xml` (ru и en), а не по именам ресурсов — имена клиент не видит.
 */
class WordingTest {
    private val forbiddenRu = listOf("коробк", "обход", "заблокирован")
    private val forbiddenEn = listOf("bypass", "blocked", "censorship")

    /** Имя ресурса -> (количество -> текст); у обычной строки количество "" . */
    private fun load(dir: String): Map<String, Map<String, String>> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("не найден ${file.path}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name")
            assertFalse("дубль ресурса $name в $dir", result.containsKey(name))
            when (node.tagName) {
                "string" -> result[name] = mapOf("" to unescape(node.textContent))
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    result[name] = (0 until items.length).associate {
                        val item = items.item(it) as org.w3c.dom.Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
        return result
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

    private val ru by lazy { load("values-ru") }
    private val en by lazy { load("values") }

    private fun texts(table: Map<String, Map<String, String>>) =
        table.flatMap { (name, forms) -> forms.values.map { name to it } }

    /** Множество плейсхолдеров строки (`%1$s`, `%2$s`, `%1$d`, `%s` …). */
    private fun placeholders(s: String) = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()

    @Test fun catalogsAreNotTrivial() {
        assertTrue("ru подозрительно мал: ${ru.size}", ru.size > 30)
        assertTrue("en подозрительно мал: ${en.size}", en.size > 30)
    }

    @Test fun russianStringsAvoidForbiddenWords() {
        for ((name, value) in texts(ru)) for (w in forbiddenRu)
            assertFalse("«$w» в клиентской строке $name: $value", value.lowercase().contains(w))
    }

    @Test fun englishStringsAvoidForbiddenWords() {
        for ((name, value) in texts(en)) for (w in forbiddenEn)
            assertFalse("«$w» в клиентской строке $name: $value", value.lowercase().contains(w))
    }

    @Test fun englishStringsDoNotCallItABox() {
        // «box» отдельным словом — тот же запрет, что и «коробка»; «JetBox» допустим.
        val pattern = Regex("\\bboxe?s?\\b", RegexOption.IGNORE_CASE)
        for ((name, value) in texts(en))
            assertFalse("«box» отдельным словом в $name: $value", pattern.containsMatchIn(value))
    }

    @Test fun everyKeyHasBothLanguages() {
        assertEquals("ключи ru и en расходятся", en.keys, ru.keys)
        for (key in en.keys) {
            assertEquals("разный тип (string/plurals) у $key", en[key]!!.keys == setOf(""), ru[key]!!.keys == setOf(""))
            assertTrue("пустой текст ru у $key", ru[key]!!.values.all { it.isNotBlank() })
            assertTrue("пустой текст en у $key", en[key]!!.values.all { it.isNotBlank() })
        }
    }

    @Test fun formatPlaceholdersMatchBetweenLanguages() {
        for (key in en.keys) {
            // Объединение по формам (plurals): «one» может опускать число, но набор ключа совпадает.
            val enSet = en[key]!!.values.flatMapTo(mutableSetOf()) { placeholders(it) }
            val ruSet = ru[key]!!.values.flatMapTo(mutableSetOf()) { placeholders(it) }
            assertEquals("плейсхолдеры у $key (en=$enSet, ru=$ruSet)", enSet, ruSet)
        }
    }

    @Test fun russianPluralHasAllForms() {
        val hint = ru["jb_settings_advanced_hint"]
        assertNotNull(hint)
        assertEquals(setOf("one", "few", "many", "other"), hint!!.keys)
        assertEquals(setOf("one", "other"), en["jb_settings_advanced_hint"]!!.keys)
    }

    @Test fun connectScreenPromisesTheHomeNetwork() {
        // Легенда продукта — доступ к своей сети, а не «весь трафик через коробку».
        assertEquals("Одно нажатие — и телефон окажется в вашей домашней сети.", ru["jb_ready_hint"]!![""])
    }

    @Test fun updateRowsAreShortAndCarryTheVersion() {
        assertEquals("Доступна новая версия %1\$s", ru["jb_update_available"]!![""])
        assertEquals("New version %1\$s available", en["jb_update_available"]!![""])
        assertEquals("Обновить до %1\$s", ru["jb_update_install"]!![""])
        assertEquals("Update to %1\$s", en["jb_update_install"]!![""])
    }

    /** Решение руководителя 13.11.2026: подключение при замене не «сбрасывается», а «заменяется». */
    @Test fun switchMessageSaysPreviousConnectionIsReplaced() {
        assertTrue(ru.getValue("jb_switch_message").getValue("").endsWith("Переключить? Прежнее подключение будет заменено."))
        assertTrue(en.getValue("jb_switch_message").getValue("").endsWith("Switch? The previous connection will be replaced."))
    }

    /** Стиль «простое» (iOS 20.11.2026): тексты те же, что на iOS. */
    @Test fun simpleStyleStringsMatchIos() {
        val expectedRu = mapOf(
            "jb_style_title" to "Оформление",
            "jb_style_simple" to "Простое",
            "jb_style_techno" to "Техно",
            "jb_simple_tap_to_connect" to "Нажмите на круг, чтобы подключиться",
            "jb_simple_wait" to "Пару секунд",
            "jb_simple_details" to "Подробности подключения",
            "jb_simple_about_title" to "Что такое JetBox",
        )
        for ((key, value) in expectedRu) assertEquals("ru $key", value, ru[key]?.get(""))
        assertEquals("Appearance", en["jb_style_title"]?.get(""))
        assertEquals("Simple", en["jb_style_simple"]?.get(""))
        assertEquals("Techno", en["jb_style_techno"]?.get(""))
        for (key in simpleKeys) assertNotNull("нет en $key", en[key])
    }

    private val simpleKeys = listOf(
        "jb_style_title", "jb_style_simple", "jb_style_techno",
        "jb_simple_tap_to_connect", "jb_simple_wait", "jb_simple_offline_hint", "jb_simple_details",
        "jb_simple_about_title", "jb_simple_about_router", "jb_simple_about_guard",
        "jb_simple_about_app", "jb_simple_about_privacy",
    )

    /**
     * Легенда «простого» стиля (макет 20.11.2026): роутер дома и ключ обратно в свою сеть, а не
     * «VPN куда-то». В его текстах нет VPN, анонимности, локаций и «разблокировать».
     */
    @Test fun simpleStyleStringsAvoidVpnWording() {
        val ruWords = listOf("vpn", "впн", "аноним", "локаци", "разблок")
        val enWords = listOf("vpn", "anonym", "location", "unblock")
        for (key in simpleKeys) {
            val r = ru.getValue(key).getValue("").lowercase()
            val e = en.getValue(key).getValue("").lowercase()
            for (w in ruWords) assertFalse("«$w» в ru $key: $r", r.contains(w))
            for (w in enWords) assertFalse("«$w» в en $key: $e", e.contains(w))
        }
    }
}
