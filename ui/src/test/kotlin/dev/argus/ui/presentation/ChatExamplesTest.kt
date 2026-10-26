package dev.argus.ui.presentation

import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateKeys
import dev.argus.ui.model.ShizukuCapabilityCatalog
import dev.argus.ui.model.ShizukuRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F10 (0.4.0): la galleria «Altri esempi». Ogni esempio deve essere fattibile con le funzioni attuali di
 * Argus: lo si verifica contro il vocabolario reale dell'engine e contro la classificazione Shizuku, non
 * contro il testo.
 */
class ChatExamplesTest {
    private val italian = ChatExamples.groups(RenderLanguage.IT)
    private val english = ChatExamples.groups(RenderLanguage.EN)
    private val all = italian.flatMap { it.examples }

    /** Trigger realmente offerti al compilatore (`AndroidCapabilityProbe.availableTriggers`). */
    private val knownTriggers = setOf(
        "time",
        "immediate",
        "notification",
        "geofence",
        "phone_state.sms",
        "phone_state.call",
        "connectivity.wifi",
        "connectivity.wifi.identity",
        "connectivity.bt",
        "connectivity.power",
    ) + setOf(
        "significant_motion",
        "stationary_detect",
        "motion_detect",
        "step_detector",
        "step_counter",
    ).map { "sensor.$it" }

    private val knownActions: Set<String> =
        ActionTypeIds::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { it.also { f -> f.isAccessible = true }.get(null) as? String }
            .toSet()

    private val knownTools = setOf(GenerativeContract.TOOL_WEB_SEARCH, GenerativeContract.TOOL_WHATSAPP_REPLY)

    @Test
    fun `there are about twelve examples in five or six groups`() {
        assertTrue(italian.size in 5..6, "gruppi: ${italian.size}")
        assertTrue(all.size in 10..14, "esempi: ${all.size}")
        assertTrue(italian.all { it.examples.size >= 2 }, "ogni gruppo ha almeno due esempi")
    }

    @Test
    fun `the six topics of the brief are all covered`() {
        assertEquals(
            listOf(
                "Orari e abitudini",
                "Luoghi",
                "Messaggi e chiamate",
                "Connessioni e batteria",
                "Impostazioni del telefono",
                "AI e web",
            ),
            italian.map { it.title },
        )
        assertEquals(
            listOf(
                "Times and routines",
                "Places",
                "Messages and calls",
                "Connections and battery",
                "Phone settings",
                "AI and web",
            ),
            english.map { it.title },
        )
    }

    @Test
    fun `every example exists in english and italian with the same shape`() {
        assertEquals(italian.size, english.size)
        italian.zip(english).forEach { (itGroup, enGroup) ->
            assertEquals(itGroup.examples.size, enGroup.examples.size, itGroup.title)
            itGroup.examples.zip(enGroup.examples).forEach { (itExample, enExample) ->
                assertTrue(itExample.text.isNotBlank() && enExample.text.isNotBlank())
                assertNotEquals(itExample.text, enExample.text, "testo non tradotto: ${itExample.text}")
                assertEquals(itExample.requiresShizuku, enExample.requiresShizuku)
                assertEquals(itExample.uses, enExample.uses)
            }
        }
    }

    @Test
    fun `examples are short, unique and read like a person wrote them`() {
        listOf(italian, english).forEach { groups ->
            val texts = groups.flatMap { it.examples }.map { it.text }
            assertEquals(texts.size, texts.toSet().size, "esempi duplicati")
            texts.forEach { text ->
                assertTrue(text.length <= 150, "troppo lungo (${text.length}): $text")
                assertTrue(text.endsWith("."), "manca il punto finale: $text")
            }
        }
    }

    @Test
    fun `every example only asks for things Argus can do today`() {
        all.forEach { example ->
            assertTrue(example.uses.isNotEmpty(), example.text)
            assertTrue(example.uses.any { it.startsWith("trigger:") }, "senza trigger: ${example.text}")
            example.uses.forEach { use ->
                val id = use.substringAfter(':')
                when (use.substringBefore(':')) {
                    "trigger" -> assertTrue(id in knownTriggers, "trigger sconosciuto: $use")
                    "action" -> assertTrue(id in knownActions, "azione sconosciuta: $use")
                    "state" -> assertTrue(id in StateKeys.ALL, "chiave di stato sconosciuta: $use")
                    "tool" -> assertTrue(id in knownTools, "tool sconosciuto: $use")
                    else -> error("prefisso sconosciuto: $use")
                }
            }
        }
    }

    @Test
    fun `the Shizuku label is true for every example and missing for none that needs it`() {
        val requirement = ShizukuCapabilityCatalog.rows(RenderLanguage.EN)
            .flatMap { row -> row.actionTypeIds.map { it to row.requirement } }
            .toMap()
        all.forEach { example ->
            val needsShizuku = example.uses.any { use ->
                when (use.substringBefore(':')) {
                    "action" -> requirement[use.substringAfter(':')] == ShizukuRequirement.REQUIRED
                    // I valori di stato (batteria, Wi-Fi, ...) si leggono solo con Shizuku autorizzato.
                    "state" -> true
                    else -> false
                }
            }
            assertEquals(needsShizuku, example.requiresShizuku, example.text)
        }
    }

    @Test
    fun `examples without Shizuku never lean on actions that only work in the foreground without it`() {
        val recommended = ShizukuCapabilityCatalog.rows(RenderLanguage.EN)
            .filter { it.requirement == ShizukuRequirement.RECOMMENDED }
            .flatMap { it.actionTypeIds }
            .toSet()
        all.filter { !it.requiresShizuku }.forEach { example ->
            val actions = example.uses.filter { it.startsWith("action:") }.map { it.substringAfter(':') }
            assertTrue(actions.none { it in recommended }, "da background serve Shizuku: ${example.text}")
        }
    }

    @Test
    fun `the phone settings group is the one that asks for Shizuku`() {
        val settings = italian.single { it.title == "Impostazioni del telefono" }

        assertTrue(settings.examples.all { it.requiresShizuku })
        assertTrue(italian.filter { it !== settings }.flatMap { it.examples }.none { it.requiresShizuku })
    }

    @Test
    fun `generative examples ask for the web search tool and a notification, never a reply`() {
        val ai = italian.single { it.title == "AI e web" }

        ai.examples.forEach { example ->
            assertTrue("action:${ActionTypeIds.INVOKE_LLM}" in example.uses, example.text)
            assertTrue("tool:${GenerativeContract.TOOL_WEB_SEARCH}" in example.uses, example.text)
            assertTrue("tool:${GenerativeContract.TOOL_WHATSAPP_REPLY}" !in example.uses, example.text)
        }
    }
}
