package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.MediaCommand
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Risoluzione dei segnaposto nelle azioni 0.4.1: speak (sink locale) e body di http_request. */
class TaintAwareInterpolatorActions041Test {
    private val interpolator = TaintAwareInterpolator()

    private fun scope(vararg values: Pair<String, VarValue>) = VarScope(mapOf(*values))

    private fun tainted(text: String, provenance: Set<ValueProvenance> = setOf(ValueProvenance.SMS)) = VarValue(
        text, VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, provenance,
    )

    private fun clean(text: String, type: VarType = VarType.TEXT) = VarValue(
        text, type, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, setOf(ValueProvenance.LITERAL),
    )

    private fun http(
        body: String?,
        contentType: String? = "application/json",
        method: HttpMethod = HttpMethod.POST,
        url: String = "https://example.org/hook",
        headers: Map<String, String>? = null,
    ) = Action.HttpRequest(method, url, headers, body, contentType)

    private fun resolveHttp(action: Action.HttpRequest, scope: VarScope): ActionResolution =
        interpolator.resolve(action, scope)

    private fun bodyOf(resolution: ActionResolution): String =
        assertIs<Action.HttpRequest>(assertIs<ActionResolution.Resolved>(resolution).value.action).body.orEmpty()

    private fun blockedCode(resolution: ActionResolution): String =
        assertIs<ActionResolution.Blocked>(resolution).code

    // --- speak ---------------------------------------------------------------------------------

    @Test
    fun `speak takes a tainted value like a notification`() {
        val resolved = assertIs<ActionResolution.Resolved>(
            interpolator.resolve(Action.Speak("Nuovo SMS: \${sms}"), scope("sms" to tainted("ciao <b>mondo</b>"))),
        ).value
        assertEquals("Nuovo SMS: ciao <b>mondo</b>", assertIs<Action.Speak>(resolved.action).text)
        assertEquals(IntegrityLabel.TAINTED, resolved.inputIntegrity)
        assertTrue(resolved.runtimeData.isEmpty())
    }

    @Test
    fun `speak blocks an empty or too long resolved text`() {
        assertEquals(
            "resolved_action_invalid",
            blockedCode(interpolator.resolve(Action.Speak("\${x}"), scope("x" to clean("   ")))),
        )
        assertEquals(
            "interpolation_too_long",
            blockedCode(interpolator.resolve(Action.Speak("\${x}"), scope("x" to clean("a".repeat(4_001))))),
        )
        assertEquals(
            "variable_unavailable",
            blockedCode(interpolator.resolve(Action.Speak("\${missing}"), scope())),
        )
    }

    // --- Azioni senza campi di testo -----------------------------------------------------------

    @Test
    fun `media rotation and brightness pass through unchanged`() {
        listOf<Action>(
            Action.MediaControl(MediaCommand.NEXT),
            Action.SetRotation(true),
            Action.SetBrightness(level = 20),
            Action.SetBrightness(auto = false),
        ).forEach { action ->
            val resolved = assertIs<ActionResolution.Resolved>(interpolator.resolve(action, scope())).value
            assertEquals(action, resolved.action)
        }
    }

    @Test
    fun `a malformed brightness is blocked after resolution`() {
        assertEquals(
            "resolved_action_invalid",
            blockedCode(interpolator.resolve(Action.SetBrightness(level = 5, auto = true), scope())),
        )
        assertEquals(
            "resolved_action_invalid",
            blockedCode(interpolator.resolve(Action.SetBrightness(level = 101), scope())),
        )
    }

    // --- http_request: body JSON ---------------------------------------------------------------

    @Test
    fun `a tainted value in a json string is escaped and cannot add keys`() {
        val attack = "x\", \"admin\": true, \"y\": \"\\"
        val resolved = resolveHttp(
            http("{\"text\":\"\${sms}\",\"from\":\"phone\"}"),
            scope("sms" to tainted(attack)),
        )
        val body = bodyOf(resolved)
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("text", "from"), parsed.keys)
        assertEquals(attack, (parsed["text"] as JsonPrimitive).content)
        assertEquals(
            IntegrityLabel.TAINTED,
            assertIs<ActionResolution.Resolved>(resolved).value.inputIntegrity,
        )
    }

    @Test
    fun `newlines and control characters in a tainted value stay valid json`() {
        val body = bodyOf(
            resolveHttp(http("{\"t\":\"\${sms}\"}"), scope("sms" to tainted("riga1\nriga2\t\u0001"))),
        )
        val parsed = Json.parseToJsonElement(body) as JsonObject
        assertEquals("riga1\nriga2\t\u0001", (parsed["t"] as JsonPrimitive).content)
    }

    @Test
    fun `a clean value in a json string is escaped too so the json stays valid`() {
        val body = bodyOf(resolveHttp(http("{\"t\":\"\${v}\"}"), scope("v" to clean("say \"hi\""))))
        assertEquals("{\"t\":\"say \\\"hi\\\"\"}", body)
    }

    @Test
    fun `a tainted value outside a json string is blocked`() {
        assertEquals(
            "json_quote_context",
            blockedCode(resolveHttp(http("{\"n\":\${sms}}"), scope("sms" to tainted("1, \"admin\": true")))),
        )
        assertEquals(
            "json_quote_context",
            blockedCode(resolveHttp(http("[\${sms}]"), scope("sms" to tainted("1,2")))),
        )
    }

    @Test
    fun `a clean number outside a json string enters as is`() {
        assertEquals(
            "{\"n\":42}",
            bodyOf(resolveHttp(http("{\"n\":\${count}}"), scope("count" to clean("42", VarType.NUMBER)))),
        )
    }

    @Test
    fun `an escaped quote in the template keeps the placeholder inside the string`() {
        val body = bodyOf(
            resolveHttp(http("{\"t\":\"he said \\\"\${sms}\\\"\"}"), scope("sms" to tainted("a\"b"))),
        )
        assertEquals("{\"t\":\"he said \\\"a\\\"b\\\"\"}", body)
        Json.parseToJsonElement(body)
    }

    @Test
    fun `the json content type can come from the header`() {
        val action = http(
            body = "{\"t\":\"\${sms}\"}",
            contentType = null,
            headers = mapOf("Content-Type" to "application/json"),
        )
        val body = bodyOf(resolveHttp(action, scope("sms" to tainted("\""))))
        assertEquals("{\"t\":\"\\\"\"}", body)
    }

    // --- http_request: body form e testo -------------------------------------------------------

    @Test
    fun `a tainted value in a form body is percent encoded`() {
        val body = bodyOf(
            resolveHttp(
                http("msg=\${sms}&src=argus", contentType = "application/x-www-form-urlencoded"),
                scope("sms" to tainted("a&admin=true b")),
            ),
        )
        assertEquals("msg=a%26admin%3Dtrue+b&src=argus", body)
    }

    @Test
    fun `a plain text body takes the value as is`() {
        val body = bodyOf(
            resolveHttp(http("SMS: \${sms}", contentType = "text/plain"), scope("sms" to tainted("a \"b\" & c"))),
        )
        assertEquals("SMS: a \"b\" & c", body)
    }

    // --- http_request: limiti e uscita ---------------------------------------------------------

    @Test
    fun `a body that grows past 16 KB after resolution is blocked`() {
        val big = "x".repeat(16 * 1024)
        assertEquals(
            "interpolation_too_long",
            blockedCode(resolveHttp(http("\${v}1", contentType = "text/plain"), scope("v" to clean(big)))),
        )
        // In byte: 9000 caratteri a due byte sono 18000 byte.
        assertEquals(
            "resolved_action_invalid",
            blockedCode(
                resolveHttp(http("\${v}", contentType = "text/plain"), scope("v" to clean("è".repeat(9_000)))),
            ),
        )
    }

    @Test
    fun `a value with credential provenance cannot leave the phone in a body`() {
        val credential = VarValue(
            "hunter2",
            VarType.TEXT,
            IntegrityLabel.CLEAN,
            ConfidentialityLabel.SECRET,
            setOf(ValueProvenance.CREDENTIAL),
        )
        assertEquals(
            "remote_egress_blocked",
            blockedCode(resolveHttp(http("{\"p\":\"\${c}\"}"), scope("c" to credential))),
        )
    }

    @Test
    fun `a secret value without credential provenance may be sent to the approved address`() {
        val secret = VarValue(
            "captured",
            VarType.TEXT,
            IntegrityLabel.TAINTED,
            ConfidentialityLabel.SECRET,
            setOf(ValueProvenance.SHELL),
        )
        assertEquals(
            "{\"o\":\"captured\"}",
            bodyOf(resolveHttp(http("{\"o\":\"\${c}\"}"), scope("c" to secret))),
        )
    }

    @Test
    fun `url and headers are never interpolated and a placeholder there blocks the action`() {
        val values = scope("sms" to tainted("evil.example"))
        assertEquals(
            "resolved_action_invalid",
            blockedCode(resolveHttp(http(body = null, url = "https://\${sms}/x"), values)),
        )
        assertEquals(
            "resolved_action_invalid",
            blockedCode(resolveHttp(http(body = null, headers = mapOf("X-Tok" to "\${sms}")), values)),
        )
    }

    @Test
    fun `a body free of placeholders and a request without body resolve untouched`() {
        val plain = http("{\"a\":1}")
        assertEquals(plain, assertIs<ActionResolution.Resolved>(resolveHttp(plain, scope())).value.action)
        val get = http(body = null, method = HttpMethod.GET, contentType = null)
        assertEquals(get, assertIs<ActionResolution.Resolved>(resolveHttp(get, scope())).value.action)
    }

    @Test
    fun `the resolved http request never shows the values in its string form`() {
        val resolved = assertIs<ActionResolution.Resolved>(
            resolveHttp(http("{\"t\":\"\${sms}\"}"), scope("sms" to tainted("SECRETTEXT"))),
        ).value
        assertTrue("SECRETTEXT" !in resolved.toString())
    }
}
