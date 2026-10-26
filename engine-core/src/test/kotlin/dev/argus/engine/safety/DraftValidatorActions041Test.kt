package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validazione delle azioni 0.4.1: speak, media_control, set_brightness, set_rotation, http_request. */
class DraftValidatorActions041Test {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun codes(issues: List<ValidationIssue>, severity: Severity) =
        issues.filter { it.severity == severity }.map { it.code }
    private fun errors(issues: List<ValidationIssue>) = codes(issues, Severity.ERROR)
    private fun warnings(issues: List<ValidationIssue>) = codes(issues, Severity.WARNING)

    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
    private fun draft(
        vararg actions: Action,
        vars: List<VarBinding> = emptyList(),
        trigger: Trigger = time,
    ) = AutomationDraft(name = "r", trigger = trigger, actions = actions.toList(), vars = vars)

    private fun http(
        url: String = "https://example.org/hook",
        method: HttpMethod = HttpMethod.POST,
        headers: Map<String, String>? = null,
        body: String? = null,
        contentType: String? = null,
    ) = Action.HttpRequest(method, url, headers, body, contentType)

    private fun smsPayload(name: String = "sms", confidentiality: ConfidentialityLabel = ConfidentialityLabel.PRIVATE) =
        VarBinding.TriggerPayload(name, TriggerField.TEXT, null, confidentiality)

    private val smsTrigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)

    // --- speak ---------------------------------------------------------------------------------

    @Test
    fun `speak needs a non blank text of at most 1000 characters`() {
        assertEquals(emptyList(), errors(v.validate(draft(Action.Speak("Ciao")), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.Speak("a".repeat(1_000))), emptySet())))
        assertTrue("speak_text_invalid" in errors(v.validate(draft(Action.Speak("")), emptySet())))
        assertTrue("speak_text_invalid" in errors(v.validate(draft(Action.Speak("  ")), emptySet())))
        assertTrue("speak_text_invalid" in errors(v.validate(draft(Action.Speak("a".repeat(1_001))), emptySet())))
    }

    @Test
    fun `speak accepts a tainted variable as a local sink`() {
        val d = draft(Action.Speak("Messaggio: \${sms}"), vars = listOf(smsPayload()), trigger = smsTrigger)
        assertEquals(emptyList(), errors(v.validate(d, emptySet())))
        assertEquals(emptyList(), warnings(v.validate(d, emptySet())))
    }

    @Test
    fun `speak with an undeclared variable is an error like any other sink`() {
        assertTrue(
            "interpolation_undeclared_var" in errors(v.validate(draft(Action.Speak("\${nope}")), emptySet())),
        )
    }

    // --- media_control -------------------------------------------------------------------------

    @Test
    fun `every media command is valid`() {
        MediaCommand.entries.forEach { command ->
            assertEquals(emptyList(), errors(v.validate(draft(Action.MediaControl(command)), emptySet())), command.name)
        }
    }

    // --- set_brightness / set_rotation ---------------------------------------------------------

    @Test
    fun `brightness needs exactly one of level and auto and a level from 0 to 100`() {
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetBrightness(level = 0)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetBrightness(level = 100)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetBrightness(auto = true)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetBrightness(auto = false)), emptySet())))

        assertTrue("brightness_spec" in errors(v.validate(draft(Action.SetBrightness()), emptySet())))
        assertTrue(
            "brightness_spec" in errors(v.validate(draft(Action.SetBrightness(level = 5, auto = true)), emptySet())),
        )
        assertTrue("brightness_level_invalid" in errors(v.validate(draft(Action.SetBrightness(level = -1)), emptySet())))
        assertTrue("brightness_level_invalid" in errors(v.validate(draft(Action.SetBrightness(level = 101)), emptySet())))
    }

    @Test
    fun `rotation is a plain boolean`() {
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetRotation(true)), emptySet())))
        assertEquals(emptyList(), errors(v.validate(draft(Action.SetRotation(false)), emptySet())))
    }

    // --- http_request: URL ---------------------------------------------------------------------

    @Test
    fun `a plain https request is valid`() {
        assertEquals(
            emptyList(),
            errors(
                v.validate(
                    draft(
                        http(
                            headers = mapOf("Authorization" to "Bearer abcdefghijkl"),
                            body = "{\"a\":1}",
                            contentType = "application/json",
                        ),
                    ),
                    emptySet(),
                ),
            ),
        )
        assertEquals(emptyList(), errors(v.validate(draft(http(method = HttpMethod.GET)), emptySet())))
    }

    @Test
    fun `the url must be https or loopback http without userinfo`() {
        fun urlErrors(url: String) = errors(v.validate(draft(http(url = url)), emptySet()))
        assertEquals(emptyList(), urlErrors("http://localhost:8123/api"))
        assertEquals(emptyList(), urlErrors("http://127.0.0.1/api"))
        assertEquals(emptyList(), urlErrors("http://[::1]:8123/api"))
        listOf(
            "http://example.org/hook",
            "ftp://example.org/x",
            "https://user:pass@example.org/",
            "https://example.org/a b",
            "not a url",
            "",
        ).forEach { url -> assertTrue("http_url_invalid" in urlErrors(url), url) }
    }

    @Test
    fun `an interpolation in the url is its own error even with a declared variable`() {
        val d = draft(
            http(url = "https://example.org/\${sms}"),
            vars = listOf(smsPayload()),
            trigger = smsTrigger,
        )
        val errors = errors(v.validate(d, emptySet()))
        assertTrue("http_url_interpolated" in errors, errors.toString())
        assertFalse("http_url_invalid" in errors)
    }

    // --- http_request: intestazioni ------------------------------------------------------------

    @Test
    fun `headers cannot interpolate`() {
        val d = draft(
            http(headers = mapOf("X-Note" to "\${sms}")),
            vars = listOf(smsPayload()),
            trigger = smsTrigger,
        )
        assertTrue("http_header_interpolated" in errors(v.validate(d, emptySet())))
        val inName = draft(
            http(headers = mapOf("X-\${sms}" to "a")),
            vars = listOf(smsPayload()),
            trigger = smsTrigger,
        )
        assertTrue("http_header_interpolated" in errors(v.validate(inName, emptySet())))
    }

    @Test
    fun `at most ten headers with valid names and none of the four transport headers`() {
        fun headerErrors(headers: Map<String, String>) = errors(v.validate(draft(http(headers = headers)), emptySet()))
        assertEquals(emptyList(), headerErrors((1..10).associate { "X-H$it" to "v" }))
        assertTrue("http_headers_too_many" in headerErrors((1..11).associate { "X-H$it" to "v" }))
        listOf("Host", "Content-Length", "Transfer-Encoding", "Connection").forEach { name ->
            assertTrue("http_header_invalid" in headerErrors(mapOf(name to "x")), name)
        }
        assertTrue("http_header_invalid" in headerErrors(mapOf("Bad Name" to "x")))
        assertTrue("http_header_invalid" in headerErrors(mapOf("X-A" to "line\r\nX-B: 1")))
        assertTrue("http_header_invalid" in headerErrors(mapOf("Accept" to "a", "accept" to "b")))
        // Un valore svuotato (esportazione senza dati personali) non si arma.
        assertTrue("http_header_invalid" in headerErrors(mapOf("Authorization" to "")))
    }

    @Test
    fun `content type is validated and may not contradict the header`() {
        assertTrue(
            "http_content_type_invalid" in
                errors(v.validate(draft(http(body = "x", contentType = "json")), emptySet())),
        )
        assertTrue(
            "http_content_type_invalid" in errors(
                v.validate(
                    draft(
                        http(
                            body = "x",
                            contentType = "application/json",
                            headers = mapOf("Content-Type" to "text/plain"),
                        ),
                    ),
                    emptySet(),
                ),
            ),
        )
        assertEquals(
            emptyList(),
            errors(
                v.validate(
                    draft(http(body = "x", headers = mapOf("Content-Type" to "application/json"))),
                    emptySet(),
                ),
            ),
        )
    }

    // --- http_request: body --------------------------------------------------------------------

    @Test
    fun `a GET has no body and the body is capped at 16 KB`() {
        assertTrue(
            "http_body_not_allowed" in errors(v.validate(draft(http(method = HttpMethod.GET, body = "x")), emptySet())),
        )
        assertEquals(
            emptyList(),
            errors(v.validate(draft(http(method = HttpMethod.PUT, body = "x".repeat(16 * 1024))), emptySet())),
        )
        assertTrue(
            "http_body_too_large" in
                errors(v.validate(draft(http(body = "x".repeat(16 * 1024 + 1))), emptySet())),
        )
        // Il limite è in byte, non in caratteri.
        assertTrue(
            "http_body_too_large" in errors(v.validate(draft(http(body = "è".repeat(9_000))), emptySet())),
        )
    }

    @Test
    fun `external data may fill the body even tainted`() {
        val d = draft(
            http(body = "{\"text\":\"\${sms}\"}", contentType = "application/json"),
            vars = listOf(smsPayload()),
            trigger = smsTrigger,
        )
        val issues = v.validate(d, emptySet())
        assertEquals(emptyList(), errors(issues))
        assertEquals(emptyList(), warnings(issues))
    }

    @Test
    fun `an external value outside a json string is flagged before approval`() {
        val d = draft(
            http(body = "{\"n\":\${sms}}", contentType = "application/json"),
            vars = listOf(smsPayload()),
            trigger = smsTrigger,
        )
        val issues = v.validate(d, emptySet())
        assertEquals(emptyList(), errors(issues))
        assertTrue("interpolation_json_context" in warnings(issues))

        // Un valore fidato fuori da una stringa non preoccupa.
        val trusted = draft(
            http(body = "{\"n\":\${count}}", contentType = "application/json"),
            vars = listOf(VarBinding.Literal("count", "3", VarType.NUMBER, ConfidentialityLabel.PUBLIC)),
        )
        assertEquals(emptyList(), warnings(v.validate(trusted, emptySet())))
    }

    @Test
    fun `a secret variable in the body warns that it leaves the phone`() {
        val d = draft(
            http(body = "{\"k\":\"\${sms}\"}", contentType = "application/json"),
            vars = listOf(smsPayload(confidentiality = ConfidentialityLabel.SECRET)),
            trigger = smsTrigger,
        )
        assertTrue("http_body_secret_disclosure" in warnings(v.validate(d, emptySet())))
        val privateOnly = d.copy(vars = listOf(smsPayload(confidentiality = ConfidentialityLabel.PRIVATE)))
        assertFalse("http_body_secret_disclosure" in warnings(v.validate(privateOnly, emptySet())))
    }

    @Test
    fun `an undeclared variable in the body is an error`() {
        assertTrue(
            "interpolation_undeclared_var" in
                errors(v.validate(draft(http(body = "\${nope}")), emptySet())),
        )
    }

    // --- Budget --------------------------------------------------------------------------------

    @Test
    fun `slow actions weigh in the worst case time budget of a loop`() {
        fun loop(vararg body: Action, iterations: Int = 1_000) = draft(
            Action.While(
                condition = Condition.BooleanLiteral(true),
                body = body.toList(),
                maxIterations = iterations,
                delayBetweenMs = 0,
            ),
        )
        // 1000 giri da 15 s = 4 h 10 min: sotto le 6 ore.
        assertFalse("time_budget_exceeded" in errors(v.validate(loop(http()), emptySet())))
        // 1000 giri da 60 s di parlato = 16 h 40 min: oltre.
        assertTrue("time_budget_exceeded" in errors(v.validate(loop(Action.Speak("ciao")), emptySet())))
    }
}
