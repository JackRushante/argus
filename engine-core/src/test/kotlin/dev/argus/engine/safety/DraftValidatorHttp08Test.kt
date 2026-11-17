package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8: response capture, secrets from the vault and plain http on the local network. */
class DraftValidatorHttp08Test {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun codes(issues: List<ValidationIssue>, severity: Severity) =
        issues.filter { it.severity == severity }.map { it.code }

    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
    private fun draft(vararg actions: Action, vars: List<VarBinding> = emptyList()) =
        AutomationDraft(name = "r", trigger = time, actions = actions.toList(), vars = vars)

    private fun errors(vararg actions: Action, vars: List<VarBinding> = emptyList()) =
        codes(v.validate(draft(*actions, vars = vars), emptySet()), Severity.ERROR)

    private fun warnings(vararg actions: Action, vars: List<VarBinding> = emptyList()) =
        codes(v.validate(draft(*actions, vars = vars), emptySet()), Severity.WARNING)

    private val bearer = SecretRef("Authorization", "ha_token", "Bearer ")

    private fun http(
        url: String = "https://ha.example.org/api/states/sensor.temp",
        method: HttpMethod = HttpMethod.GET,
        headers: Map<String, String>? = null,
        body: String? = null,
        captureAs: String? = null,
        extract: String? = null,
        secretHeaders: List<SecretRef>? = null,
        secretQuery: List<SecretRef>? = null,
    ) = Action.HttpRequest(
        method, url, headers, body,
        captureAs = captureAs, extract = extract, secretHeaders = secretHeaders, secretQuery = secretQuery,
    )

    // --- Capture -----------------------------------------------------------------------------------

    @Test
    fun `a capture with or without a pointer is valid and armable`() {
        assertEquals(emptyList(), errors(http(captureAs = "temp")))
        assertEquals(emptyList(), errors(http(captureAs = "temp", extract = "/state")))
        assertEquals(
            emptyList(),
            errors(http(captureAs = "temp", extract = "/state"), Action.ShowNotification("Casa", "Ci sono \${temp} gradi")),
        )
    }

    @Test
    fun `extract without captureAs or with a bad pointer is an error`() {
        assertTrue("http_extract_without_capture" in errors(http(extract = "/state")))
        assertTrue("http_extract_invalid" in errors(http(captureAs = "t", extract = "state")))
        assertTrue("http_extract_invalid" in errors(http(captureAs = "t", extract = "/a~2")))
        assertTrue("http_extract_invalid" in errors(http(captureAs = "t", extract = "/\${x}")))
    }

    @Test
    fun `the capture name follows the variable rules`() {
        assertTrue("capture_name_invalid" in errors(http(captureAs = "Temp")))
        assertTrue(
            "capture_name_duplicate" in errors(http(captureAs = "t"), http(captureAs = "t")),
        )
    }

    @Test
    fun `a response is private unless the request carried a secret`() {
        val forward = http(url = "https://hook.example.org/x", method = HttpMethod.POST, body = "\${resp}")
        // PRIVATE: forwarding it to another address is not a secret disclosure.
        assertFalse("http_body_secret_disclosure" in warnings(http(captureAs = "resp"), forward))
        // With a value from the vault the response is SECRET.
        assertTrue(
            "http_body_secret_disclosure" in
                warnings(http(captureAs = "resp", secretHeaders = listOf(bearer)), forward),
        )
        // A SECRET variable in the body makes the response SECRET too.
        val secretVar = VarBinding.Literal("pin", "1234", VarType.TEXT, ConfidentialityLabel.SECRET)
        val withPin = http(method = HttpMethod.POST, body = "\${pin}", captureAs = "resp")
        assertTrue("http_body_secret_disclosure" in warnings(withPin, forward, vars = listOf(secretVar)))
    }

    // --- Secrets -----------------------------------------------------------------------------------

    @Test
    fun `secret headers and query parameters are valid and need no value in the rule`() {
        assertEquals(emptyList(), errors(http(secretHeaders = listOf(bearer))))
        assertEquals(emptyList(), errors(http(secretQuery = listOf(SecretRef("api_key", "meteo_api")))))
        assertEquals(
            emptyList(),
            errors(http(captureAs = "temp", extract = "/state", secretHeaders = listOf(bearer))),
        )
    }

    @Test
    fun `a bad secret name or reference is an error`() {
        assertTrue("secret_name_invalid" in errors(http(secretHeaders = listOf(bearer.copy(secret = "HA-token")))))
        assertTrue("http_secret_ref_invalid" in errors(http(secretHeaders = listOf(bearer.copy(name = "Host")))))
        assertTrue("http_secret_ref_invalid" in errors(http(secretHeaders = listOf(bearer.copy(name = "Content-Type")))))
        assertTrue("http_secret_ref_invalid" in errors(http(secretHeaders = listOf(bearer.copy(prefix = "Bearer \${x}")))))
        assertTrue("http_secret_ref_invalid" in errors(http(secretQuery = listOf(SecretRef("a b", "k")))))
        assertTrue(
            "http_secret_ref_invalid" in
                errors(http(secretQuery = listOf(SecretRef("k", "a"), SecretRef("k", "b")))),
        )
    }

    @Test
    fun `a secret header and a literal header with the same name are an error`() {
        assertTrue(
            "http_secret_header_conflict" in
                errors(http(headers = mapOf("authorization" to "Bearer x"), secretHeaders = listOf(bearer))),
        )
        assertTrue(
            "http_secret_header_conflict" in errors(http(secretHeaders = listOf(bearer, bearer.copy(secret = "other")))),
        )
    }

    @Test
    fun `literal and secret headers share the limit of ten`() {
        val nine = (1..9).associate { "X-H$it" to "v" }
        assertEquals(emptyList(), errors(http(headers = nine, secretHeaders = listOf(bearer))))
        assertTrue(
            "http_secrets_too_many" in
                errors(http(headers = nine, secretHeaders = listOf(bearer, bearer.copy(name = "X-Other")))),
        )
    }

    // --- Plain http on the local network ----------------------------------------------------------

    @Test
    fun `http on the local network is allowed with a warning`() {
        listOf("http://192.168.1.20:8123/api", "http://homeassistant.local:8123/api", "http://10.0.0.5/x").forEach { url ->
            assertEquals(emptyList(), errors(http(url = url)), url)
            assertTrue("http_cleartext_local" in warnings(http(url = url)), url)
        }
        // The loopback stays as before: no warning.
        assertFalse("http_cleartext_local" in warnings(http(url = "http://127.0.0.1:8123/api")))
        assertFalse("http_cleartext_local" in warnings(http(url = "https://192.168.1.20/api")))
        // A public host still needs https.
        assertTrue("http_url_invalid" in errors(http(url = "http://example.org/api")))
        assertTrue("http_url_invalid" in errors(http(url = "http://8.8.8.8/api")))
    }

    @Test
    fun `a secret over plain http is an error unless the host is the loopback`() {
        val lan = http(url = "http://192.168.1.20:8123/api", secretHeaders = listOf(bearer))
        assertTrue("http_cleartext_secret" in errors(lan))
        assertFalse("http_cleartext_local" in warnings(lan))
        assertTrue(
            "http_cleartext_secret" in
                errors(http(url = "http://ha.local/api", secretQuery = listOf(SecretRef("token", "ha_token")))),
        )
        assertEquals(emptyList(), errors(http(url = "http://127.0.0.1:8123/api", secretHeaders = listOf(bearer))))
        assertEquals(emptyList(), errors(http(url = "https://192.168.1.20/api", secretHeaders = listOf(bearer))))
    }

    @Test
    fun `the switched on fields never report not available yet`() {
        val issues = v.validate(
            draft(http(captureAs = "t", extract = "/a", secretHeaders = listOf(bearer), secretQuery = listOf(SecretRef("k", "x")))),
            emptySet(),
        )
        assertFalse(issues.any { it.code == FeatureGates.NOT_AVAILABLE_YET }, issues.toString())
    }
}
