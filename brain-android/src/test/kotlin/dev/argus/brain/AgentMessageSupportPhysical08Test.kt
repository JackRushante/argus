package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: il compilatore conosce i trigger fisici, e solo nella forma che l'app sa armare. */
class AgentMessageSupportPhysical08Test {
    private val prompt = AgentMessageSupport.compileSystemText()
    private val flat = prompt.replace(Regex("\\s+"), " ")

    @Test
    fun `the schema has the physical triggers`() {
        listOf("\"type\":\"nfc_tag\"", "\"type\":\"next_alarm\"", "\"type\":\"app_usage\"", "\"shake\"", "\"flip_face_down\"")
            .forEach { assertTrue(it in prompt, "manca $it") }
        // Il tag non passa mai dal modello: si legge sul telefono all'attivazione.
        assertTrue("{\"type\":\"nfc_tag\", \"tagId\":\"\", \"resolveTag\":true}" in prompt)
        assertTrue("never write a tag id" in flat)
        assertTrue("\"nfc_tag\", \"next_alarm\" and \"app_usage\" are usable only if listed" in flat)
    }

    @Test
    fun `dwell is described with its bounds instead of being forbidden`() {
        assertTrue("\"transition\":\"ENTER\"|\"EXIT\"|\"DWELL\"" in prompt)
        assertTrue("60000..7200000" in flat)
        assertFalse("do not propose DWELL" in flat)
    }

    @Test
    fun `shell may follow an nfc tag but never the next alarm or the app usage`() {
        assertTrue("connectivity, sensor or nfc_tag triggers" in flat)
        assertTrue("Not with run_shell or ui_steps (any app can set an alarm)" in flat)
    }
}
