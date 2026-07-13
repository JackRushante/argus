package dev.argus.engine.brain
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
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
        assertNotNull(r.metaError)
    }
    @Test fun `malformed meta yields reply plus error, no crash`() {
        val r = p.parseCompile("ok @@META@@ {non json}")
        assertNull(r.draft)
        assertNotNull(r.metaError)
    }
}
