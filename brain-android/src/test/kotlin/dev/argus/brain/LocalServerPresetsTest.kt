package dev.argus.brain

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.5.1 F4: preset per i server locali (Ollama, LM Studio, llama.cpp) nel provider Custom. Nessun prompt
 * compatto; il cleartext resta ammesso solo per un host loopback esatto e solo per il Custom.
 */
class LocalServerPresetsTest {
    private val secrets = ProviderSecrets { "test-token-0123456789abcdef" }
    private val custom = ProviderId.CUSTOM_OPENAI_COMPAT

    @Test
    fun `the three presets point to the loopback port of their server`() {
        assertEquals(
            mapOf(
                "ollama" to "http://127.0.0.1:11434/v1",
                "lmstudio" to "http://127.0.0.1:1234/v1",
                "llamacpp" to "http://127.0.0.1:8080/v1",
            ),
            LocalServerPresets.all.associate { it.id to it.baseUrl },
        )
        assertEquals(listOf("Ollama", "LM Studio", "llama.cpp"), LocalServerPresets.all.map { it.displayName })
        assertEquals(16_000, LocalServerPresets.MIN_CONTEXT_TOKENS)
    }

    @Test
    fun `forUrl finds a preset ignoring spaces and a trailing slash`() {
        assertEquals("ollama", LocalServerPresets.forUrl("http://127.0.0.1:11434/v1")?.id)
        assertEquals("lmstudio", LocalServerPresets.forUrl("  http://127.0.0.1:1234/v1/ ")?.id)
        assertNull(LocalServerPresets.forUrl("http://127.0.0.1:9999/v1"))
        assertNull(LocalServerPresets.forUrl(null))
        assertNull(LocalServerPresets.forUrl(""))
    }

    @Test
    fun `every preset is accepted for the custom provider and builds its transport`() {
        val factory = DefaultTransportFactory(secrets)
        LocalServerPresets.all.forEach { preset ->
            // Salvato tale e quale (senza slash finale).
            assertEquals(preset.baseUrl, normalizeProviderBaseUrl(preset.baseUrl, custom), preset.id)
            val transport = factory.create(ProviderConfig(custom, preset.baseUrl, "qwen-local"))
            assertTrue(transport is OpenAICompatTransport, preset.id)
        }
    }

    @Test
    fun `the cleartext rule is unchanged loopback only and custom only`() {
        listOf(
            "http://192.168.1.10:11434/v1",
            "http://10.0.0.5:8080/v1",
            "http://100.101.102.103:1234/v1",
            "http://studio.local:11434/v1",
            "http://localhost.example.com/v1",
            "http://127.0.0.1.example.com/v1",
        ).forEach { url ->
            assertNull(normalizeProviderBaseUrl(url, custom), url)
            assertFailsWith<IllegalArgumentException>(url) {
                OpenAICompatTransport(custom, ProviderCatalog.spec(custom), ProviderConfig(custom, url, "m"), { null })
            }
        }
        // Gli altri provider non ammettono http, nemmeno verso un preset sul loopback.
        listOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.OPENROUTER, ProviderId.ANTHROPIC).forEach { id ->
            LocalServerPresets.all.forEach { preset ->
                assertNull(normalizeProviderBaseUrl(preset.baseUrl, id), "$id ${preset.id}")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAICompatTransport(
                ProviderId.OPENROUTER,
                ProviderCatalog.spec(ProviderId.OPENROUTER),
                ProviderConfig(ProviderId.OPENROUTER, LocalServerPresets.all.first().baseUrl, "m"),
                { null },
            )
        }
    }

    @Test
    fun `a local server gets the long compile timeout and a client that does not cut it`() {
        val factory = DefaultTransportFactory(secrets)
        LocalServerPresets.all.forEach { preset ->
            assertEquals(180_000L, CompileTimeoutPolicy.timeoutMillis(custom, preset.baseUrl), preset.id)
            val client = factory.clientFor(ProviderConfig(custom, preset.baseUrl, "m"))
            // Il client di base scade a 125 s: il compile a 180 s non basterebbe. Ora scade dopo il compile.
            assertEquals(185_000, client.callTimeoutMillis, preset.id)
            assertTrue(client.readTimeoutMillis >= 185_000, preset.id)
            assertTrue(client.writeTimeoutMillis >= 185_000, preset.id)
        }
    }

    @Test
    fun `every other client stays the shared one and a missing timeout stays missing`() {
        val shared = CliBridgeTransport.defaultClient()
        val factory = DefaultTransportFactory(secrets, shared)
        listOf(
            ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", null),
            ProviderConfig(ProviderId.GEMINI, "https://generativelanguage.googleapis.com/v1beta/openai", null),
            ProviderConfig(ProviderId.OPENROUTER, "https://openrouter.ai/api/v1", null),
            ProviderConfig(custom, "https://api.example.com/v1", "m"),
        ).forEach { config ->
            assertSame(shared, factory.clientFor(config), config.providerId.name)
        }

        // Un client senza timeout di chiamata (0 = infinito) non viene limitato.
        val unlimited = OkHttpClient.Builder().callTimeout(0, TimeUnit.MILLISECONDS).build()
        val local = ProviderConfig(custom, LocalServerPresets.all.first().baseUrl, "m")
        assertSame(unlimited, DefaultTransportFactory(secrets, unlimited).clientFor(local))

        // Un client gia' piu' paziente di cosi' non viene accorciato.
        val patient = CliBridgeTransport.defaultClient(timeoutSeconds = 600)
        val kept = DefaultTransportFactory(secrets, patient).clientFor(local)
        assertSame(patient, kept)
        assertNotNull(kept)
    }
}
