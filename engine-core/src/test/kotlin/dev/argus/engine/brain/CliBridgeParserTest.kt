package dev.argus.engine.brain
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class CliBridgeParserTest {
    private val p = CliBridgeParser()
    @Test fun `extracts prose and draft`() {
        val raw = """
            Ho creato l'automazione per la suoneria.
            @@META@@ {"draft": {"name":"dnd sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},
            "actions":[{"type":"set_dnd","mode":"PRIORITY"}]}}
        """.trimIndent()
        val r = p.parseCompile(raw)
        assertEquals("Ho creato l'automazione per la suoneria.", r.reply)
        val d = assertNotNull(r.draft)
        assertEquals("dnd sera", d.name)
        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), d.trigger)
    }

    // --- 0.3.7 voce 4a: il cron alla Quartz diventa a 5 campi PRIMA della validazione ---

    private fun parsedTrigger(cron: String): Trigger {
        val raw = """ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"$cron","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]}}"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError, cron)
        return assertNotNull(r.draft, cron).trigger
    }

    @Test fun `a six field cron with zero seconds is shown to the user in the five field form`() {
        assertEquals(Trigger.Time(cron = "30 22 * * *", tz = "Europe/Rome"), parsedTrigger("0 30 22 * * *"))
        assertEquals(Trigger.Time(cron = "0 12 * * 1-5", tz = "Europe/Rome"), parsedTrigger("0 0 12 * * 1-5"))
        assertEquals(Trigger.Time(cron = "30 22 * * *", tz = "Europe/Rome"), parsedTrigger("00 30 22 * * *"))
    }

    @Test fun `any other cron stays as the model wrote it`() {
        // Secondi diversi da zero: resta e il validatore lo rifiuta.
        assertEquals(Trigger.Time(cron = "15 30 22 * * *", tz = "Europe/Rome"), parsedTrigger("15 30 22 * * *"))
        // Cinque campi: invariato.
        assertEquals(Trigger.Time(cron = "30 22 * * *", tz = "Europe/Rome"), parsedTrigger("30 22 * * *"))
        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), parsedTrigger("0 23 * * *"))
        // Uno 0 in testa seguito da cinque campi non validi: invariato.
        assertEquals(Trigger.Time(cron = "0 99 99 * * *", tz = "Europe/Rome"), parsedTrigger("0 99 99 * * *"))
    }

    @Test fun `only the cron of a time trigger is touched`() {
        val at = p.parseCompile("""ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","at":"2026-10-27T08:00","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]}}""")
        assertEquals(Trigger.Time(at = "2026-10-27T08:00", tz = "Europe/Rome"), assertNotNull(at.draft).trigger)
        val immediate = p.parseCompile("""ok @@META@@ {"draft":{"name":"x","trigger":{"type":"immediate"},"actions":[{"type":"set_wifi","on":true}]}}""")
        assertEquals(Trigger.Immediate, assertNotNull(immediate.draft).trigger)
    }

    @Test fun `the validator accepts the normalized cron and still rejects non zero seconds`() {
        val validator = dev.argus.engine.safety.DraftValidator(knownTools = emptySet())
        fun codes(cron: String): List<String> = validator.validate(
            assertNotNull(
                p.parseCompile(
                    """@@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"$cron","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]}}""",
                ).draft,
            ),
            emptySet(),
        ).map { it.code }

        assertFalse("cron_invalid" in codes("0 30 22 * * *"))
        assertTrue("cron_invalid" in codes("15 30 22 * * *"))
        assertTrue("cron_invalid" in codes("0 99 99 * * *"))
    }

    @Test fun `balanced extraction survives prose with braces after the meta`() {
        val raw = """ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]}} ricorda {questa} nota"""
        val r = p.parseCompile(raw)
        assertNotNull(r.draft)
        assertNull(r.metaError)
    }
    @Test fun `braces inside json strings do not break extraction`() {
        val raw = """@@META@@ {"draft":{"name":"tono {caldo}","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":false}]}}"""
        assertEquals("tono {caldo}", p.parseCompile(raw).draft?.name)
    }
    @Test fun `no meta yields reply only`() {
        val r = p.parseCompile("Non ho capito, puoi ripetere?")
        assertEquals("Non ho capito, puoi ripetere?", r.reply)
        assertNull(r.draft); assertNull(r.metaError)
    }
    @Test fun `meta without draft field is an explicit error`() {
        val r = p.parseCompile("""ok @@META@@ {"Draft": {"name":"typo"}}""")
        assertNull(r.draft)
        assertEquals("draft_missing", r.metaError)
    }
    @Test fun `malformed meta yields reply plus error, no crash`() {
        val r = p.parseCompile("ok @@META@@ {non json}")
        assertNull(r.draft)
        assertEquals("meta_parse_error", r.metaError)
    }
    @Test fun `missing json after sentinel yields a stable english code`() {
        val r = p.parseCompile("ok @@META@@ no-json-here")
        assertNull(r.draft)
        assertEquals("meta_json_missing", r.metaError)
    }

    // --- B2: error_code del meta (provider diretti) ---

    @Test fun `error_code with null draft is surfaced as the meta error`() {
        val r = p.parseCompile("""Quale contatto? @@META@@ {"draft":null,"error_code":"clarification_required"}""")
        assertNull(r.draft)
        assertEquals("clarification_required", r.metaError)
        assertEquals("Quale contatto?", r.reply)
    }

    @Test fun `unsupported_capability and limit_exceeded codes pass through`() {
        listOf("unsupported_capability", "limit_exceeded", "some_other_code").forEach { code ->
            val r = p.parseCompile("""no @@META@@ {"draft":null,"error_code":"$code"}""")
            assertNull(r.draft)
            assertEquals(code, r.metaError)
        }
    }

    @Test fun `invalid error_code is a meta parse error`() {
        listOf("Clarification", "1abc", "with space", "a".repeat(65), "").forEach { code ->
            val r = p.parseCompile("""no @@META@@ {"draft":null,"error_code":"$code"}""")
            assertNull(r.draft)
            assertEquals("meta_parse_error", r.metaError, "codice: '$code'")
        }
    }

    @Test fun `draft together with error_code is a meta parse error`() {
        val raw = """ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]},"error_code":"clarification_required"}"""
        val r = p.parseCompile(raw)
        assertNull(r.draft)
        assertEquals("meta_parse_error", r.metaError)
    }

    @Test fun `null error_code with null draft stays draft_missing`() {
        val r = p.parseCompile("""ok @@META@@ {"draft":null,"error_code":null}""")
        assertEquals("draft_missing", r.metaError)
    }

    // --- B7: parser tollerante ---

    private val goodDraft =
        """{"name":"x","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]"""

    @Test fun `unknown enum value is rejected, never coerced to the default`() {
        // Con coerceInputValues "SOMETIMES" diventerebbe in silenzio il default FLEXIBLE.
        val draft = """{"name":"x","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome",""" +
            """"precision":"SOMETIMES"},"actions":[{"type":"set_wifi","on":true}]}"""
        val r = p.parseCompile("""ok @@META@@ {"draft":$draft}""")
        assertNull(r.draft)
        assertEquals("meta_parse_error", r.metaError)
    }

    @Test fun `null rationale is coerced to the default`() {
        val r = p.parseCompile("""ok @@META@@ {"draft":$goodDraft,"rationale":null}}""")
        assertNull(r.metaError)
        assertEquals("", assertNotNull(r.draft).rationale)
    }

    @Test fun `trailing commas are accepted`() {
        val r = p.parseCompile("""ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome",},"actions":[{"type":"set_wifi","on":true},],},}""")
        assertNull(r.metaError)
        assertEquals("x", assertNotNull(r.draft).name)
    }

    @Test fun `line comments are accepted`() {
        val raw = """ok @@META@@ {
"draft": $goodDraft // niente {graffe} qui
}}"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError)
        assertNotNull(r.draft)
    }

    @Test fun `think block with a fake sentinel inside is ignored`() {
        val raw = """<think>Devo scrivere @@META@@ {"draft":null,"error_code":"fake_code"} alla fine</think>Fatto. @@META@@ {"draft":$goodDraft}}"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError)
        assertNotNull(r.draft)
        assertEquals("Fatto.", r.reply)
    }

    @Test fun `think block without opening tag is dropped up to the closing tag`() {
        val raw = """ragiono @@META@@ {"draft":null,"error_code":"fake"}</think>Fatto. @@META@@ {"draft":$goodDraft}}"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError)
        assertNotNull(r.draft)
    }

    @Test fun `unclosed think block hides everything after it`() {
        val r = p.parseCompile("""Risposta <think>pensiero @@META@@ {"draft":null,"error_code":"fake"}""")
        assertNull(r.draft)
        assertNull(r.metaError)
        assertEquals("Risposta", r.reply)
    }

    @Test fun `the last sentinel wins when there are two`() {
        val raw = """ho scritto @@META@@ nella prosa @@META@@ {"draft":$goodDraft}}"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError)
        assertNotNull(r.draft)
    }

    @Test fun `json fence with a draft key is used when the sentinel is missing`() {
        val raw = """Ecco la regola.
```json
{"draft":$goodDraft}}
```"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError)
        assertNotNull(r.draft)
        assertEquals("Ecco la regola.", r.reply)
    }

    @Test fun `json fence without a draft key is not treated as meta`() {
        val raw = """Esempio:
```json
{"altro":1}
```"""
        val r = p.parseCompile(raw)
        assertNull(r.draft)
        assertNull(r.metaError)
    }

    @Test fun `meta parse error carries a short detail without the input fragment`() {
        val r = p.parseCompile("""ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"segreto-123"}}}""")
        assertEquals("meta_parse_error", r.metaError)
        val detail = assertNotNull(r.detail)
        assertTrue(detail.length <= 200)
        assertFalse("segreto-123" in detail)
    }

    // --- I3: sentinel con il nome sbagliato ---

    private val wrongSentinelProse =
        "Imposto un'automazione immediata che disattiva la modalit\u00e0 Non disturbare non appena la attivi."
    private val wrongSentinelReply = wrongSentinelProse + "\n\n" +
        """@@AUTOMATION_DRAFT@@ {"draft":{"name":"Disattiva Non disturbare subito","trigger":{"type":"time","instant":true},""" +
        """"actions":[{"type":"set_dnd","mode":"OFF"}]},"error_code":null}"""

    @Test fun `a wrongly named sentinel with a draft is read as meta and reports the schema error`() {
        val r = p.parseCompile(wrongSentinelReply)
        assertNull(r.draft)
        assertEquals("meta_parse_error", r.metaError)
        assertEquals(wrongSentinelProse, r.reply)
        val detail = assertNotNull(r.detail)
        assertTrue("tz" in detail, detail)
        assertTrue("draft.trigger" in detail, detail)
        assertTrue(detail.length <= 200)
        // niente frammenti dell'input (nome della regola, tipo di azione)
        assertFalse("Disattiva" in detail, detail)
        assertFalse("set_dnd" in detail, detail)
    }

    @Test fun `a wrongly named sentinel with a valid draft yields the draft`() {
        val r = p.parseCompile("""Fatto. @@AUTOMATION_DRAFT@@ {"draft":$goodDraft}}""")
        assertNull(r.metaError)
        assertEquals("x", assertNotNull(r.draft).name)
        assertEquals("Fatto.", r.reply)
    }

    @Test fun `a wrongly named sentinel with an error_code yields the code`() {
        val r = p.parseCompile("""Non posso. @@RESULT@@ {"draft":null,"error_code":"unsupported_capability"}""")
        assertNull(r.draft)
        assertEquals("unsupported_capability", r.metaError)
        assertEquals("Non posso.", r.reply)
    }

    @Test fun `a wrongly named sentinel whose object has neither draft nor error_code stays prose`() {
        val raw = """Ecco. @@FOO@@ {"x":1}"""
        val r = p.parseCompile(raw)
        assertNull(r.draft)
        assertNull(r.metaError)
        assertEquals(raw, r.reply)
    }

    @Test fun `the correct sentinel wins over a differently named one`() {
        val after = p.parseCompile("""ok @@META@@ {"draft":$goodDraft}} poi @@FOO@@ {"draft":null,"error_code":"fake_code"}""")
        assertNull(after.metaError)
        assertNotNull(after.draft)
        val before = p.parseCompile("""ok @@FOO@@ {"draft":null,"error_code":"fake_code"} poi @@META@@ {"draft":$goodDraft}}""")
        assertNull(before.metaError)
        assertNotNull(before.draft)
    }

    @Test fun `the json fence still wins over a differently named sentinel`() {
        val raw = """Ecco.
@@FOO@@ {"draft":null,"error_code":"fake_code"}
```json
{"draft":$goodDraft}}
```"""
        val r = p.parseCompile(raw)
        assertNull(r.metaError)
        assertNotNull(r.draft)
    }

    @Test fun `the last differently named sentinel followed by an object is the one that counts`() {
        val r = p.parseCompile(
            """a @@ONE_X@@ {"draft":null,"error_code":"first_code"} b @@TWO_X@@ {"draft":null,"error_code":"second_code"}""",
        )
        assertEquals("second_code", r.metaError)
        val notMeta = p.parseCompile("""a @@ONE_X@@ {"draft":null,"error_code":"first_code"} b @@TWO_X@@ {"x":1}""")
        assertNull(notMeta.draft)
        assertNull(notMeta.metaError)
    }

    @Test fun `markers that are not well formed or not followed by an object are not sentinels`() {
        listOf(
            """@@x@@ {"draft":null,"error_code":"fake_code"}""",
            """@@A@@ {"draft":null,"error_code":"fake_code"}""",
            """@@A_B_@ {"draft":null,"error_code":"fake_code"}""",
            """@@FOO@@ senza oggetto""",
            """@@FOO@@ testo {"draft":null,"error_code":"fake_code"}""",
        ).forEach { raw ->
            val r = p.parseCompile(raw)
            assertNull(r.draft, raw)
            assertNull(r.metaError, raw)
        }
    }

    @Test fun `a differently named sentinel inside a think block is ignored`() {
        val r = p.parseCompile("""<think>@@FOO@@ {"draft":null,"error_code":"fake_code"}</think>Solo testo.""")
        assertNull(r.metaError)
        assertEquals("Solo testo.", r.reply)
    }
}
