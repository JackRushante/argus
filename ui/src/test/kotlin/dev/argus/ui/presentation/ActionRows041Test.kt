package dev.argus.ui.presentation

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.model.Trigger
import dev.argus.ui.model.ActionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Approvazione e dettaglio delle azioni 0.4.1: testi dai tipi, indirizzo intero, segreti mascherati. */
class ActionRows041Test {

    private fun rowOf(action: Action, language: RenderLanguage): ActionRow {
        val automation = Automation(
            id = AutomationId("a"),
            name = "a",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Immediate,
            actions = listOf(action),
        )
        return RuleRenderMapper.map(automation, language = language).actions.single()
    }

    private val secret = "Bearer sk-live-0123456789abcdef"

    private fun http(headers: Map<String, String>? = null, body: String? = null, contentType: String? = null) =
        Action.HttpRequest(
            method = HttpMethod.POST,
            url = "https://api.example.org/v1/notify?channel=alerts",
            headers = headers,
            body = body,
            contentType = contentType,
        )

    @Test
    fun `the http request shows the method and the whole url in both languages`() {
        for (language in RenderLanguage.entries) {
            val row = rowOf(http(), language)
            assertEquals(language.pick("HTTP request", "Richiesta HTTP"), row.label)
            assertEquals("POST https://api.example.org/v1/notify?channel=alerts", row.detail)
        }
    }

    @Test
    fun `a long url is never shortened`() {
        val url = "https://example.org/" + "segment/".repeat(60) + "end?token=visible"
        val row = rowOf(Action.HttpRequest(HttpMethod.GET, url), RenderLanguage.EN)
        assertTrue(url in row.detail.orEmpty())
    }

    @Test
    fun `a sensitive header value is masked and the secret never appears`() {
        for (language in RenderLanguage.entries) {
            val row = rowOf(
                http(headers = mapOf("Authorization" to secret, "X-Api-Key" to "key-abcdefghijkl", "Accept" to "application/json")),
                language,
            )
            val detail = row.detail.orEmpty()
            assertTrue("Authorization: Bearer ••••cdef" in detail, detail)
            assertTrue("X-Api-Key: ••••ijkl" in detail, detail)
            assertTrue("Accept: application/json" in detail, detail)
            assertFalse("sk-live-0123456789abcdef" in detail)
            assertFalse("key-abcdefghijkl" in detail)
        }
    }

    @Test
    fun `token and secret header names are masked too and a short secret shows nothing of itself`() {
        val row = rowOf(
            http(headers = mapOf("X-Auth-Token" to "tok-1234567890", "Client-Secret" to "abc")),
            RenderLanguage.EN,
        )
        val detail = row.detail.orEmpty()
        assertTrue("X-Auth-Token: ••••7890" in detail, detail)
        assertTrue("Client-Secret: ••••" in detail && "abc" !in detail, detail)
    }

    @Test
    fun `the body keeps its placeholders and the content type is listed once`() {
        val row = rowOf(
            http(body = "{\"text\":\"\${sms}\"}", contentType = "application/json"),
            RenderLanguage.EN,
        )
        val detail = row.detail.orEmpty()
        assertTrue("Body: {\"text\":\"\${sms}\"}" in detail, detail)
        assertEquals(1, Regex("Content-Type: application/json").findAll(detail).count())

        val viaHeader = rowOf(
            http(headers = mapOf("Content-Type" to "application/json"), body = "{}"),
            RenderLanguage.IT,
        )
        assertEquals(1, Regex("Content-Type: application/json").findAll(viaHeader.detail.orEmpty()).count())
        assertTrue("Corpo: {}" in viaHeader.detail.orEmpty())
    }

    @Test
    fun `the http row is flagged neither as shell nor as generative`() {
        val row = rowOf(http(body = "x"), RenderLanguage.EN)
        assertFalse(row.isShell)
        assertFalse(row.isGenerative)
        assertEquals(null, row.shellCommand)
    }

    @Test
    fun `speak shows the whole text`() {
        for (language in RenderLanguage.entries) {
            val row = rowOf(Action.Speak("Letto: \${sms}"), language)
            assertEquals(language.pick("Speak aloud", "Leggi ad alta voce"), row.label)
            assertEquals("Letto: \${sms}", row.detail)
        }
    }

    @Test
    fun `each media command has its own text`() {
        val english = MediaCommand.entries.map { ActionRows041.mediaCommandLabel(it, RenderLanguage.EN) }
        val italian = MediaCommand.entries.map { ActionRows041.mediaCommandLabel(it, RenderLanguage.IT) }
        assertEquals(MediaCommand.entries.size, english.toSet().size)
        assertEquals(MediaCommand.entries.size, italian.toSet().size)
        assertEquals("next track", ActionRows041.mediaCommandLabel(MediaCommand.NEXT, RenderLanguage.EN))
        assertEquals("traccia successiva", ActionRows041.mediaCommandLabel(MediaCommand.NEXT, RenderLanguage.IT))
    }

    @Test
    fun `brightness says whether it is a manual level or automatic`() {
        assertEquals("40% (manual)", rowOf(Action.SetBrightness(level = 40), RenderLanguage.EN).detail)
        assertEquals("40% (manuale)", rowOf(Action.SetBrightness(level = 40), RenderLanguage.IT).detail)
        assertEquals("automatic", rowOf(Action.SetBrightness(auto = true), RenderLanguage.EN).detail)
        assertEquals("automatica", rowOf(Action.SetBrightness(auto = true), RenderLanguage.IT).detail)
        assertTrue("manual" in rowOf(Action.SetBrightness(auto = false), RenderLanguage.EN).detail.orEmpty())
    }

    @Test
    fun `rotation says on or off`() {
        assertEquals("on", rowOf(Action.SetRotation(true), RenderLanguage.EN).detail)
        assertEquals("disattivata", rowOf(Action.SetRotation(false), RenderLanguage.IT).detail)
    }
}
