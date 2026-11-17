package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8 [FeatureGate.HTTP_SECRETS] and plain http on the local network: the pure policy. */
class HttpSecrets08Test {
    private val bearer = SecretRef("Authorization", "ha_token", "Bearer ")

    private fun request(
        url: String = "https://ha.example.org/api/states",
        headers: Map<String, String>? = null,
        secretHeaders: List<SecretRef>? = listOf(bearer),
        secretQuery: List<SecretRef>? = null,
    ) = Action.HttpRequest(HttpMethod.GET, url, headers, secretHeaders = secretHeaders, secretQuery = secretQuery)

    // --- Vault names and values --------------------------------------------------------------------

    @Test
    fun `secret names look like variable names`() {
        listOf("a", "ha_token", "x1", "a".repeat(32)).forEach { assertTrue(SecretPolicy.isNameValid(it), it) }
        listOf("", "1a", "_a", "Ha", "ha-token", "ha token", "a".repeat(33), "\${a}").forEach {
            assertFalse(SecretPolicy.isNameValid(it), it)
        }
    }

    @Test
    fun `a secret value has 1 to 4096 characters and no control characters`() {
        assertTrue(SecretPolicy.isValueValid("x"))
        assertTrue(SecretPolicy.isValueValid("é".repeat(4_096)))
        listOf("", "   ", "a".repeat(4_097), "a\nb", "a\rb", "a\u0000").forEach {
            assertFalse(SecretPolicy.isValueValid(it), it.take(8))
        }
    }

    @Test
    fun `the names a rule uses are found in nested and exit actions`() {
        val draft = AutomationDraft(
            name = "r",
            trigger = Trigger.Manual,
            actions = listOf(
                Action.If(
                    Condition.BooleanLiteral(true),
                    then = listOf(request()),
                    orElse = listOf(request(secretHeaders = null, secretQuery = listOf(SecretRef("key", "meteo_api")))),
                ),
                Action.Vibrate(100),
            ),
            exit = ExitSpec(Trigger.Manual, actions = listOf(request(secretHeaders = listOf(SecretRef("X-K", "ntfy_casa"))))),
        )
        assertEquals(setOf("ha_token", "meteo_api", "ntfy_casa"), SecretPolicy.namesUsedBy(draft))
        assertEquals(emptySet(), SecretPolicy.namesUsedBy(listOf(Action.Vibrate(1))))
    }

    // --- Shape -------------------------------------------------------------------------------------

    @Test
    fun `a valid secret header and query parameter pass the resolved shape check`() {
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(secretQuery = listOf(SecretRef("api_key", "meteo_api")))))
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(secretHeaders = emptyList())))
    }

    @Test
    fun `a secret header with the name of another header is refused`() {
        assertTrue(HttpRequestPolicy.secretHeaderConflicts(request(headers = mapOf("authorization" to "x"))))
        assertTrue(HttpRequestPolicy.secretHeaderConflicts(request(secretHeaders = listOf(bearer, bearer.copy(name = "AUTHORIZATION")))))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(headers = mapOf("Authorization" to "Bearer x"))))
    }

    @Test
    fun `a secret header follows the header name rules and never sets the content type`() {
        listOf("Host", "Content-Length", "Content-Type", "Bad Name", "", "X:Y").forEach { name ->
            assertFalse(HttpRequestPolicy.isSecretHeaderValid(bearer.copy(name = name)), name)
            assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(secretHeaders = listOf(bearer.copy(name = name)))), name)
        }
        assertFalse(HttpRequestPolicy.isSecretHeaderValid(bearer.copy(secret = "Bad-Name")))
        assertFalse(HttpRequestPolicy.isSecretHeaderValid(bearer.copy(prefix = "Bearer \${x}")))
        assertFalse(HttpRequestPolicy.isSecretHeaderValid(bearer.copy(prefix = "a\r\nX-Evil: 1")))
        assertFalse(HttpRequestPolicy.isSecretHeaderValid(bearer.copy(prefix = "p".repeat(65))))
    }

    @Test
    fun `a secret query parameter has an unreserved name and appears once`() {
        assertTrue(HttpRequestPolicy.isSecretQueryValid(SecretRef("api_key", "k")))
        assertTrue(HttpRequestPolicy.isSecretQueryValid(SecretRef("auth[token]", "k")))
        listOf("", "a b", "a&b", "a=b", "a#", "x".repeat(65)).forEach {
            assertFalse(HttpRequestPolicy.isSecretQueryValid(SecretRef(it, "k")), it)
        }
        val twice = request(secretHeaders = null, secretQuery = listOf(SecretRef("k", "a"), SecretRef("k", "b")))
        assertTrue(HttpRequestPolicy.secretQueryDuplicates(twice))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(twice))
    }

    @Test
    fun `literal and secret headers share the limit of ten`() {
        val nine = (1..9).associate { "X-H$it" to "v" }
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(headers = nine)))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(headers = nine + ("X-H10" to "v"))))
        val elevenQuery = (1..11).map { SecretRef("q$it", "k") }
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(secretHeaders = null, secretQuery = elevenQuery)))
    }

    // --- Cleartext and egress ----------------------------------------------------------------------

    @Test
    fun `cleartext scope comes from the host text only`() {
        assertNull(HttpRequestPolicy.cleartextScope("https://192.168.1.2/x"))
        assertEquals(LocalNetworkPolicy.HostScope.LOOPBACK, HttpRequestPolicy.cleartextScope("http://127.0.0.1:8123/x"))
        assertEquals(LocalNetworkPolicy.HostScope.LOCAL_NETWORK, HttpRequestPolicy.cleartextScope("http://192.168.1.2/x"))
        assertEquals(LocalNetworkPolicy.HostScope.PUBLIC, HttpRequestPolicy.cleartextScope("http://example.org/x"))
        assertTrue(HttpRequestPolicy.isCleartextOnLocalNetwork("http://homeassistant.local:8123/api"))
        assertFalse(HttpRequestPolicy.isCleartextOnLocalNetwork("http://localhost:8123/api"))
    }

    @Test
    fun `a secret travels only encrypted or to this phone`() {
        assertTrue(HttpRequestPolicy.secretsAllowedTo("https://ha.example.org/api"))
        assertTrue(HttpRequestPolicy.secretsAllowedTo("http://127.0.0.1:8123/api"))
        assertTrue(HttpRequestPolicy.secretsAllowedTo("http://localhost/api"))
        assertFalse(HttpRequestPolicy.secretsAllowedTo("http://192.168.1.2/api"))
        assertFalse(HttpRequestPolicy.secretsAllowedTo("http://ha.local/api"))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(url = "http://192.168.1.2:8123/api")))
        assertFalse(HttpRequestPolicy.isResolvedShapeValid(request(url = "http://192.168.1.2/api", secretHeaders = null, secretQuery = listOf(SecretRef("k", "a")))))
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(url = "http://127.0.0.1:8123/api")))
        // Without secrets the local network is fine.
        assertTrue(HttpRequestPolicy.isResolvedShapeValid(request(url = "http://192.168.1.2:8123/api", secretHeaders = null)))
    }

    @Test
    fun `a redirect stays on the host and never downgrades`() {
        assertTrue(HttpRequestPolicy.isRedirectAllowed("https://a.example/x", "https://a.example/y", carriesSecrets = false))
        assertTrue(HttpRequestPolicy.isRedirectAllowed("https://a.example/x", "https://a.example:8443/y", carriesSecrets = false))
        assertFalse(HttpRequestPolicy.isRedirectAllowed("https://a.example/x", "https://b.example/y", carriesSecrets = false))
        assertFalse(HttpRequestPolicy.isRedirectAllowed("https://192.168.1.2/x", "http://192.168.1.2/y", carriesSecrets = false))
        assertTrue(HttpRequestPolicy.isRedirectAllowed("http://192.168.1.2/x", "http://192.168.1.2/y", carriesSecrets = false))
        assertTrue(HttpRequestPolicy.isRedirectAllowed("http://192.168.1.2/x", "https://192.168.1.2/y", carriesSecrets = false))
        // As before 0.8: on the loopback https -> http is still allowed.
        assertTrue(HttpRequestPolicy.isRedirectAllowed("https://127.0.0.1/x", "http://127.0.0.1/y", carriesSecrets = false))
    }

    @Test
    fun `with a secret a redirect keeps scheme host and port`() {
        assertTrue(HttpRequestPolicy.isRedirectAllowed("https://a.example/x", "https://a.example:443/y", carriesSecrets = true))
        assertFalse(HttpRequestPolicy.isRedirectAllowed("https://a.example/x", "https://a.example:8443/y", carriesSecrets = true))
        assertFalse(HttpRequestPolicy.isRedirectAllowed("https://a.example/x", "https://evil.example/y", carriesSecrets = true))
        assertFalse(HttpRequestPolicy.isRedirectAllowed("https://127.0.0.1/x", "http://127.0.0.1/y", carriesSecrets = true))
        assertTrue(HttpRequestPolicy.isRedirectAllowed("http://127.0.0.1:8/x", "http://127.0.0.1:8/y", carriesSecrets = true))
    }

    // --- Capture shape -----------------------------------------------------------------------------

    @Test
    fun `extract needs captureAs and a valid pointer`() {
        fun shape(captureAs: String?, extract: String?) = HttpRequestPolicy.isResolvedShapeValid(
            Action.HttpRequest(HttpMethod.GET, "https://example.org", captureAs = captureAs, extract = extract),
        )
        assertTrue(shape("temp", null))
        assertTrue(shape("temp", "/current/temp"))
        assertFalse(shape(null, "/current/temp"))
        assertFalse(shape("temp", "current"))
        assertFalse(shape("Temp", null))
    }
}
