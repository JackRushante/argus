package dev.argus.engine.brain

import dev.argus.engine.model.Action
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.5.1 F3b: l'output strutturato nativo è UN oggetto `{reply, draft, error_code}`. Il parser lo legge con la
 * stessa decodifica del protocollo `@@META@@` e, quando il testo non è un envelope, torna `null` così il
 * chiamante ripiega sul protocollo base.
 */
class CliBridgeParserStructuredTest {
    private val parser = CliBridgeParser()

    private val draftJson =
        """{"name":"dnd sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_dnd","mode":"PRIORITY"}]}"""

    @Test
    fun `a valid envelope gives the reply and the draft`() {
        val result = assertNotNull(
            parser.parseStructuredCompile("""{"reply":"Ho creato la regola.","draft":$draftJson,"error_code":null}"""),
        )

        assertNull(result.metaError)
        assertEquals("Ho creato la regola.", result.reply)
        val draft = assertNotNull(result.draft)
        assertEquals("dnd sera", draft.name)
        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), draft.trigger)
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), draft.actions)
    }

    @Test
    fun `an envelope without a draft carries the error code and the question`() {
        val result = assertNotNull(
            parser.parseStructuredCompile(
                """{"reply":"A che ora?","draft":null,"error_code":"clarification_required"}""",
            ),
        )

        assertNull(result.draft)
        assertEquals("clarification_required", result.metaError)
        assertEquals("A che ora?", result.reply)
    }

    @Test
    fun `an envelope with neither draft nor error code is a missing draft`() {
        val result = assertNotNull(parser.parseStructuredCompile("""{"reply":"Boh","draft":null,"error_code":null}"""))

        assertNull(result.draft)
        assertEquals("draft_missing", result.metaError)
    }

    @Test
    fun `a draft together with an error code is a format error like in the base protocol`() {
        val result = assertNotNull(
            parser.parseStructuredCompile("""{"reply":"x","draft":$draftJson,"error_code":"limit_exceeded"}"""),
        )

        assertNull(result.draft)
        assertEquals("meta_parse_error", result.metaError)
    }

    @Test
    fun `fences and reasoning around the object are tolerated`() {
        val fenced = "```json\n{\"reply\":\"Fatto.\",\"draft\":$draftJson,\"error_code\":null}\n```"
        assertEquals("dnd sera", assertNotNull(assertNotNull(parser.parseStructuredCompile(fenced)).draft).name)

        val reasoned = "<think>Il sentinel @@META@@ non serve qui.</think>\n" +
            "{\"reply\":\"Fatto.\",\"draft\":$draftJson,\"error_code\":null}"
        assertEquals("dnd sera", assertNotNull(assertNotNull(parser.parseStructuredCompile(reasoned)).draft).name)
    }

    @Test
    fun `trailing commas and comments are tolerated like in the base protocol`() {
        val raw = """{"reply":"Fatto.", // commento
            "draft":$draftJson,"error_code":null,}"""

        assertEquals("dnd sera", assertNotNull(assertNotNull(parser.parseStructuredCompile(raw)).draft).name)
    }

    @Test
    fun `a draft serialized inside a string is opened`() {
        val escaped = draftJson.replace("\\", "\\\\").replace("\"", "\\\"")
        val result = assertNotNull(
            parser.parseStructuredCompile("""{"reply":"Fatto.","draft":"$escaped","error_code":null}"""),
        )

        assertNull(result.metaError)
        assertEquals("dnd sera", assertNotNull(result.draft).name)
        assertEquals("Fatto.", result.reply)
    }

    @Test
    fun `a string draft that is not an object stays a format error`() {
        val result = assertNotNull(
            parser.parseStructuredCompile("""{"reply":"x","draft":"niente da vedere","error_code":null}"""),
        )

        assertNull(result.draft)
        assertEquals("meta_parse_error", result.metaError)
    }

    @Test
    fun `the same normalizations of the base protocol apply`() {
        val quartz = draftJson.replace("0 23 * * *", "0 0 23 * * *")
        val result = assertNotNull(
            parser.parseStructuredCompile("""{"reply":"Fatto.","draft":$quartz,"error_code":null}"""),
        )

        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), assertNotNull(result.draft).trigger)
    }

    @Test
    fun `a malformed draft shape is a format error with a safe detail`() {
        val result = assertNotNull(
            parser.parseStructuredCompile("""{"reply":"x","draft":{"name":"senza trigger"},"error_code":null}"""),
        )

        assertNull(result.draft)
        assertEquals("meta_parse_error", result.metaError)
        assertTrue(assertNotNull(result.detail).isNotBlank())
        assertTrue("senza trigger" !in result.detail.orEmpty(), "il dettaglio non cita l'input")
    }

    @Test
    fun `an envelope cut short is a missing json and invalid json is a parse error`() {
        val cut = assertNotNull(parser.parseStructuredCompile("""{"reply":"Fatto.","draft":{"name":"dnd","trigger":{"type":"imm"""))
        assertEquals("meta_json_missing", cut.metaError)
        assertNull(cut.draft)

        val broken = assertNotNull(parser.parseStructuredCompile("""{"reply":"x","draft":oops,"error_code":null}"""))
        assertEquals("meta_parse_error", broken.metaError)
        assertNull(broken.draft)
    }

    @Test
    fun `anything that is not an envelope falls back to the base protocol`() {
        // Il protocollo base: la riga sentinel vince sempre, anche se compare un oggetto JSON.
        assertNull(parser.parseStructuredCompile("Fatto.\n@@META@@ {\"draft\":$draftJson,\"error_code\":null}"))
        // Prosa semplice, vuoto, oggetto qualunque senza le chiavi dell'envelope.
        assertNull(parser.parseStructuredCompile("Ciao! Come posso aiutarti?"))
        assertNull(parser.parseStructuredCompile(""))
        assertNull(parser.parseStructuredCompile("""{"foo":1,"bar":{"baz":2}}"""))
        // Un'apertura senza le chiavi dell'envelope non è un envelope troncato.
        assertNull(parser.parseStructuredCompile("""Ecco: {"foo":"""))
    }
}
