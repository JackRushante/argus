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
}
