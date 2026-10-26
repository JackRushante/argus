package dev.argus.data.backup

import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.Trigger
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** L'esportazione senza dati personali toglie i valori delle intestazioni di `http_request`. */
class PersonalDataStripperHttp041Test {
    private val validator = DraftValidator(setOf("whatsapp_reply"))
    private val secret = "Bearer sk-live-1234567890abcdef"

    private fun request(headers: Map<String, String>?) = Action.HttpRequest(
        method = HttpMethod.POST,
        url = "https://api.example.org/v1/notify",
        headers = headers,
        body = "{\"text\":\"ciao\"}",
        contentType = "application/json",
    )

    private fun draft(vararg actions: Action) =
        AutomationDraft(name = "http", trigger = Trigger.Immediate, actions = actions.toList())

    private fun errorCodes(draft: AutomationDraft) =
        validator.validate(draft, emptySet()).filter { it.severity == Severity.ERROR }.map { it.code }

    @Test
    fun `header values are removed and the names are kept`() {
        val stripped = PersonalDataStripper.strip(
            draft(request(mapOf("Authorization" to secret, "X-Api-Key" to "abc123", "Accept" to "application/json"))),
        ).actions.single() as Action.HttpRequest

        assertEquals(setOf("Authorization", "X-Api-Key", "Accept"), stripped.headers?.keys)
        assertTrue(stripped.headers.orEmpty().values.all { it == PersonalDataStripper.REMOVED_HEADER_VALUE })
    }

    @Test
    fun `no secret survives in the serialized rule`() {
        val stripped = PersonalDataStripper.strip(draft(request(mapOf("Authorization" to secret))))
        val json = ArgusJson.encodeToString(AutomationDraft.serializer(), stripped)
        assertFalse("sk-live-1234567890abcdef" in json)
        assertFalse("Bearer" in json)
    }

    @Test
    fun `url method body and content type are left as they are`() {
        val original = request(mapOf("Authorization" to secret))
        val stripped = PersonalDataStripper.strip(draft(original)).actions.single() as Action.HttpRequest

        assertEquals(original.method, stripped.method)
        assertEquals(original.url, stripped.url)
        assertEquals(original.body, stripped.body)
        assertEquals(original.contentType, stripped.contentType)
    }

    @Test
    fun `a request without headers is left exactly as it is`() {
        val plain = draft(request(null))
        assertEquals(plain, PersonalDataStripper.strip(plain))
    }

    @Test
    fun `headers are stripped inside if and while too`() {
        val nested = draft(
            Action.While(
                condition = dev.argus.engine.model.Condition.BooleanLiteral(true),
                body = listOf(request(mapOf("Authorization" to secret))),
                maxIterations = 2,
            ),
        )
        val stripped = PersonalDataStripper.strip(nested)
        assertFalse("sk-live" in ArgusJson.encodeToString(AutomationDraft.serializer(), stripped))
    }

    @Test
    fun `a stripped request is flagged by the validator so it cannot be armed with empty credentials`() {
        val original = draft(request(mapOf("Authorization" to secret)))
        assertEquals(emptyList(), errorCodes(original))

        val stripped = PersonalDataStripper.strip(original)

        assertTrue("http_header_invalid" in errorCodes(stripped))
    }
}
