package dev.argus.data.backup

import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I modelli della galleria (0.7.2), letti dagli asset veri del modulo. Ogni modello deve decodificarsi come un
 * file importato e passare il validatore a parte i suoi campi «da completare»: con quei campi completati non
 * resta nessun errore; senza, restano SOLO gli errori di quei campi, così il modello non si arma finché chi lo
 * usa non li completa.
 */
class RuleTemplateCatalogTest {
    private val catalog = RuleTemplateCatalog { path -> File(ASSETS, path).inputStream() }

    /** Lo stesso catalogo dei tool del telefono che il validatore riceve in produzione, per ciò che i modelli usano. */
    private val validator = DraftValidator(knownTools = setOf("whatsapp_reply", "web.search"))

    private val templates = catalog.templates()

    private fun eachRule(block: (RuleTemplate, Boolean, AutomationDraft) -> Unit) {
        templates.forEach { template ->
            listOf(false, true).forEach { italian ->
                val draft = assertNotNull(catalog.draft(template.id, italian), "${template.id} ${lang(italian)}")
                block(template, italian, draft)
            }
        }
    }

    @Test
    fun `the gallery ships about ten templates, with unique ids, in every category`() {
        assertTrue(templates.size in 10..14, "modelli: ${templates.size}")
        assertEquals(templates.size, templates.map { it.id }.toSet().size)
        assertEquals(TemplateCategory.entries.toSet(), templates.map { it.category }.toSet())
        // Nessuna voce dell'indice è stata scartata in lettura.
        val listed = Regex("\"id\"").findAll(File(ASSETS, RuleTemplateCatalog.INDEX_PATH).readText()).count()
        assertEquals(listed, templates.size)
    }

    @Test
    fun `titles and descriptions are written in both languages`() {
        templates.forEach { template ->
            assertNotEquals(template.title.en, template.title.it, template.id)
            assertNotEquals(template.description.en, template.description.it, template.id)
            assertTrue(template.description.en.length in 20..400, template.id)
            assertTrue(template.description.it.length in 20..400, template.id)
        }
    }

    @Test
    fun `every template has its rule in both languages, decoded strictly like an imported file`() {
        eachRule { template, italian, draft ->
            assertEquals(template.title.pick(italian), draft.name, "${template.id} ${lang(italian)}")
            val text = File(ASSETS, "templates/${template.id}.${lang(italian)}.json").readText()
            val entries = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(text)).entries
            assertIs<DecodedEntry.Rule>(entries.single())
        }
    }

    @Test
    fun `every template passes the validator apart from its fields to complete`() {
        eachRule { template, italian, draft ->
            val label = "${template.id} ${lang(italian)}"
            val errors = validator.validate(draft, emptySet()).filter { it.severity == Severity.ERROR }
            assertTrue(errors.all { it.code in FIELD_TO_COMPLETE_CODES }, "$label: ${errors.map { it.code }}")

            // Un campo da completare blocca l'arm; senza campi da completare non c'è nessun errore.
            val blanks = FieldsToComplete.of(draft) - FieldToComplete.CURRENT_LOCATION
            assertEquals(blanks.isNotEmpty(), errors.isNotEmpty(), "$label: $blanks / ${errors.map { it.code }}")

            val completed = validator.validate(complete(draft), setOf(FILLED_CHAT)).filter { it.severity == Severity.ERROR }
            assertEquals(emptyList(), completed.map { it.code }, label)
        }
    }

    @Test
    fun `templates that need the user's own data carry the values the stripper leaves`() {
        assertEquals(setOf(FieldToComplete.NETWORK), FieldsToComplete.of(draft("home-wifi-ringer")))
        assertEquals(setOf(FieldToComplete.CHAT), FieldsToComplete.of(draft("messages-ai-busy-reply")))
        assertEquals(setOf(FieldToComplete.CURRENT_LOCATION), FieldsToComplete.of(draft("home-leave-wifi-off")))
        assertEquals(emptySet(), FieldsToComplete.of(draft("battery-low-saver")))
    }

    @Test
    fun `the bytes to import put the time in the phone's zone and nothing else changes`() {
        val zone = ZoneId.of("America/New_York")
        val bytes = assertNotNull(catalog.ruleBytes("home-night-dnd", italian = true, zone = zone))

        val entries = assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(bytes.toString(Charsets.UTF_8))).entries
        val imported = assertIs<DecodedEntry.Rule>(entries.single()).rule
        val original = draft("home-night-dnd", italian = true)
        assertEquals(false, imported.enabled)
        assertEquals(original.copy(trigger = (original.trigger as Trigger.Time).copy(tz = zone.id)), imported.draft)

        val geofence = assertNotNull(catalog.ruleBytes("home-leave-wifi-off", italian = false, zone = zone))
        val geofenceRule = assertIs<DecodedEntry.Rule>(
            assertIs<DecodedBackup.Entries>(RulesBackupCodec.decode(geofence.toString(Charsets.UTF_8))).entries.single(),
        ).rule
        assertEquals(draft("home-leave-wifi-off"), geofenceRule.draft)
    }

    @Test
    fun `unknown or unsafe ids give nothing`() {
        assertNull(catalog.draft("does-not-exist", italian = false))
        assertNull(catalog.draft("../index", italian = false))
        assertNull(catalog.ruleBytes("templates/../../x", italian = true, zone = ZoneId.of("UTC")))
    }

    @Test
    fun `a malformed or missing index gives an empty gallery`() {
        assertEquals(emptyList(), RuleTemplateCatalog { ByteArrayInputStream("{}".toByteArray()) }.templates())
        assertEquals(emptyList(), RuleTemplateCatalog { ByteArrayInputStream("[[[".toByteArray()) }.templates())
        assertEquals(emptyList(), RuleTemplateCatalog { throw FileNotFoundException(it) }.templates())
        val wrongVersion = """{"format":"argus-templates","version":2,"templates":[]}"""
        assertEquals(emptyList(), RuleTemplateCatalog { ByteArrayInputStream(wrongVersion.toByteArray()) }.templates())
    }

    // --- aiuti ---------------------------------------------------------------------------------

    private fun draft(id: String, italian: Boolean = false): AutomationDraft =
        assertNotNull(catalog.draft(id, italian), id)

    private fun lang(italian: Boolean) = if (italian) "it" else "en"

    /** Ciò che farebbe chi importa il modello: completa ogni campo lasciato vuoto. */
    private fun complete(draft: AutomationDraft): AutomationDraft = draft.copy(
        trigger = when (val trigger = draft.trigger) {
            is Trigger.Notification -> trigger.copy(
                conversationId = trigger.conversationId?.ifEmpty { FILLED_CHAT },
                sender = trigger.sender?.ifEmpty { "Anna" },
                titleMatch = trigger.titleMatch?.ifEmpty { "Anna" },
            )
            is Trigger.PhoneState -> trigger.copy(number = trigger.number?.ifEmpty { "+15550100" })
            is Trigger.Connectivity -> trigger.copy(match = trigger.match?.ifEmpty { "Casa" })
            else -> trigger
        },
    )

    private companion object {
        val ASSETS = File("src/main/assets")
        const val FILLED_CHAT = "wa::template::1"

        /** Gli errori dei soli campi che [PersonalDataStripper] lascia da completare. */
        val FIELD_TO_COMPLETE_CODES = setOf(
            "conversation_id_invalid",
            "reply_needs_conversation_id",
            "sender_invalid",
            "title_match_invalid",
            "number_invalid",
            "match_invalid",
            "location_invalid",
            "var_literal_invalid",
            "http_header_invalid",
        )
    }
}
