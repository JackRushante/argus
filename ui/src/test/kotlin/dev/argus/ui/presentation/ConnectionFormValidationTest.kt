package dev.argus.ui.presentation

import dev.argus.ui.presentation.ConnectionFormValidation.SecretProblem
import dev.argus.ui.presentation.ConnectionFormValidation.UrlProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B48 (0.4.0): «Salva e verifica» chiudeva il dialogo prima di controllare i dati, e un indirizzo non
 * valido faceva perdere quello che l'utente aveva scritto. Ora il dialogo si chiude solo se i dati
 * passano questi controlli (gemelli di quelli del salvataggio); altrimenti resta aperto, col testo
 * com'e', e l'errore compare sotto il campo.
 */
class ConnectionFormValidationTest {
    private val goodToken = "a".repeat(20)

    // ---- indirizzo Hermes -------------------------------------------------------------------------

    @Test
    fun `a plain https address passes`() {
        listOf(
            "https://hermes.example",
            "https://hermes.example/",
            "  https://hermes.example/v1  ",
            "https://hermes.example:8443/api",
            "HTTPS://Hermes.Example",
            "https://192.168.1.10:9000",
            "https://[2001:db8::1]:8443/x",
        ).forEach { assertNull(ConnectionFormValidation.bridgeUrl(it), it) }
    }

    @Test
    fun `an address without https is explained, not accepted`() {
        assertEquals(UrlProblem.EMPTY, ConnectionFormValidation.bridgeUrl(""))
        assertEquals(UrlProblem.EMPTY, ConnectionFormValidation.bridgeUrl("   "))
        assertEquals(UrlProblem.NOT_HTTPS, ConnectionFormValidation.bridgeUrl("hermes.example"))
        assertEquals(UrlProblem.NOT_HTTPS, ConnectionFormValidation.bridgeUrl("http://hermes.example"))
        assertEquals(UrlProblem.NOT_HTTPS, ConnectionFormValidation.bridgeUrl("http://localhost:8080"))
        assertEquals(UrlProblem.NOT_HTTPS, ConnectionFormValidation.bridgeUrl("ftp://hermes.example"))
    }

    @Test
    fun `an address that cannot be a bridge is invalid`() {
        listOf(
            "https://",
            "https:///path",
            "https://user:secret@hermes.example",
            "https://hermes.example?token=1",
            "https://hermes.example/#top",
            "https://hermes.example:99999",
            "https://hermes.example:abc",
            "https://her mes.example",
            "https://[::1",
        ).forEach { assertEquals(UrlProblem.INVALID, ConnectionFormValidation.bridgeUrl(it), it) }
    }

    // ---- indirizzo del provider Custom ---------------------------------------------------------------

    @Test
    fun `custom provider accepts http only towards this phone or the local network`() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080",
            "http://[::1]:8080/v1",
            "HTTP://LocalHost:1234",
            "https://llm.example/v1",
            // #19: the user's own network.
            "http://192.168.0.10:47832/v1",
            "http://10.0.0.5:8080/v1",
            "http://172.16.4.2/v1",
            "http://100.101.102.103:11434/v1",
            "http://[fd00::1]:8080/v1",
            "http://[fe80::1]/v1",
            "http://studio.local:11434/v1",
            "http://nas.home.arpa/v1",
            "http://gpu-box:8080/v1",
            "Http://192.168.1.5:11434",
        ).forEach { assertNull(ConnectionFormValidation.providerUrl(it, cleartextLocalAllowed = true), it) }

        listOf(
            "http://llm.example/v1",
            "http://localhost.evil.example",
            "http://8.8.8.8:11434/v1",
            "http://192.168.0.10.evil.com/v1",
            "http://192.168.0.10./v1",
            "http://nas./v1",
            "http://010.0.0.1/v1",
            "http://3232235530/v1",
            "http://[2001:db8::1]/v1",
        ).forEach {
            assertEquals(
                UrlProblem.NOT_HTTPS_OR_LOCAL,
                ConnectionFormValidation.providerUrl(it, cleartextLocalAllowed = true),
                it,
            )
        }
        // User info is never accepted: the host would be evil.com.
        assertEquals(
            UrlProblem.INVALID,
            ConnectionFormValidation.providerUrl("http://10.0.0.1@evil.com/v1", cleartextLocalAllowed = true),
        )
    }

    @Test
    fun `only an accepted http address off this phone gets the unencrypted warning`() {
        listOf(
            "http://192.168.0.10:47832/v1",
            " http://studio.local:11434/v1 ",
            "HTTP://[fd00::1]:8080/v1",
            "http://gpu-box/v1",
        ).forEach { assertTrue(ConnectionFormValidation.unencryptedLocalNetwork(it), it) }
        listOf(
            "http://127.0.0.1:11434/v1",
            "http://localhost:1234/v1",
            "https://192.168.0.10/v1",
            "https://llm.example/v1",
            "http://llm.example/v1",
            "http://10.0.0.1@evil.com/v1",
            "",
        ).forEach { assertFalse(ConnectionFormValidation.unencryptedLocalNetwork(it), it) }
    }

    @Test
    fun `without the custom exception http towards localhost is not accepted`() {
        assertEquals(
            UrlProblem.NOT_HTTPS,
            ConnectionFormValidation.providerUrl("http://localhost:11434", cleartextLocalAllowed = false),
        )
    }

    // ---- token e chiavi ---------------------------------------------------------------------------------

    @Test
    fun `a bearer needs 16 visible characters`() {
        assertNull(ConnectionFormValidation.secret("a".repeat(16), required = true, minLength = 16))
        assertEquals(
            SecretProblem.TOO_SHORT,
            ConnectionFormValidation.secret("a".repeat(15), required = true, minLength = 16),
        )
        assertEquals(
            SecretProblem.INVALID_CHARACTERS,
            ConnectionFormValidation.secret("token con spazi nel mezzo", required = true, minLength = 16),
        )
        assertEquals(
            SecretProblem.INVALID_CHARACTERS,
            ConnectionFormValidation.secret("tokén".padEnd(20, 'x'), required = true, minLength = 16),
        )
        assertEquals(
            SecretProblem.TOO_LONG,
            ConnectionFormValidation.secret("a".repeat(4_097), required = true, minLength = 16),
        )
    }

    @Test
    fun `an empty secret is missing only when none is saved yet`() {
        assertEquals(SecretProblem.MISSING, ConnectionFormValidation.secret("", required = true, minLength = 16))
        assertEquals(SecretProblem.MISSING, ConnectionFormValidation.secret("   ", required = true, minLength = 16))
        assertNull(ConnectionFormValidation.secret("", required = false, minLength = 16))
    }

    @Test
    fun `a local custom key can be a single character`() {
        assertNull(
            ConnectionFormValidation.secret(
                "x",
                required = true,
                minLength = ConnectionFormValidation.MIN_CUSTOM_SECRET_LENGTH,
            ),
        )
    }

    // ---- il dialogo si chiude solo se i dati passano -------------------------------------------------

    @Test
    fun `the bridge dialog stays open on an invalid address and reports it`() {
        val problems = ConnectionFormValidation.bridgeForm(
            url = "http://hermes.example",
            token = goodToken,
            tokenConfigured = false,
        )

        assertFalse(problems.accepted)
        assertEquals(UrlProblem.NOT_HTTPS, problems.url)
        assertNull(problems.token)
    }

    @Test
    fun `the bridge dialog keeps the saved token when the field is left empty`() {
        val problems = ConnectionFormValidation.bridgeForm(
            url = "https://hermes.example",
            token = "",
            tokenConfigured = true,
        )

        assertTrue(problems.accepted)
    }

    @Test
    fun `the bridge dialog asks for a token when none is saved`() {
        val problems = ConnectionFormValidation.bridgeForm(
            url = "https://hermes.example",
            token = "",
            tokenConfigured = false,
        )

        assertFalse(problems.accepted)
        assertEquals(SecretProblem.MISSING, problems.token)
        assertNull(problems.url)
    }

    @Test
    fun `the provider dialog validates the address only when it can be edited`() {
        val fixed = ConnectionFormValidation.providerForm(
            baseUrl = "not even an address",
            model = "gpt-model",
            apiKey = goodToken,
            baseUrlEditable = false,
            cleartextLocalAllowed = false,
            keyRequired = true,
            keyConfigured = false,
            minKeyLength = ConnectionFormValidation.MIN_SECRET_LENGTH,
        )
        assertTrue(fixed.accepted)

        val custom = ConnectionFormValidation.providerForm(
            baseUrl = "http://llm.example:11434",
            model = "llama",
            apiKey = "",
            baseUrlEditable = true,
            cleartextLocalAllowed = true,
            keyRequired = false,
            keyConfigured = false,
            minKeyLength = ConnectionFormValidation.MIN_CUSTOM_SECRET_LENGTH,
        )
        assertFalse(custom.accepted)
        assertEquals(UrlProblem.NOT_HTTPS_OR_LOCAL, custom.url)
        assertNull(custom.key)
    }

    @Test
    fun `the provider dialog needs a model and, when required and not saved, a key`() {
        val problems = ConnectionFormValidation.providerForm(
            baseUrl = "",
            model = "   ",
            apiKey = "",
            baseUrlEditable = false,
            cleartextLocalAllowed = false,
            keyRequired = true,
            keyConfigured = false,
            minKeyLength = ConnectionFormValidation.MIN_SECRET_LENGTH,
        )

        assertFalse(problems.accepted)
        assertTrue(problems.modelMissing)
        assertEquals(SecretProblem.MISSING, problems.key)
    }

    @Test
    fun `a saved key makes the key field optional but a typed one is still checked`() {
        fun form(key: String) = ConnectionFormValidation.providerForm(
            baseUrl = "",
            model = "gpt-model",
            apiKey = key,
            baseUrlEditable = false,
            cleartextLocalAllowed = false,
            keyRequired = true,
            keyConfigured = true,
            minKeyLength = ConnectionFormValidation.MIN_SECRET_LENGTH,
        )

        assertTrue(form("").accepted)
        assertEquals(SecretProblem.TOO_SHORT, form("short").key)
    }
}
