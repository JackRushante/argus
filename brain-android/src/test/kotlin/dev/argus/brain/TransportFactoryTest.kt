package dev.argus.brain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportFactoryTest {

    private val fixedSecret = ProviderSecrets { "test-token-0123456789abcdef" }

    @Test
    fun `hermes config produces a cli bridge transport`() {
        val factory = DefaultTransportFactory(fixedSecret)

        val transport = factory.create(
            ProviderConfig(ProviderId.HERMES, "https://bridge.example", model = null),
        )

        assertTrue(transport is CliBridgeTransport)
        assertEquals(ProviderId.HERMES, transport.providerId)
    }

    @Test
    fun `the four openai-compat providers produce an OpenAICompatTransport`() {
        val factory = DefaultTransportFactory(fixedSecret)
        val compat = listOf(
            ProviderId.OPENAI,
            ProviderId.GEMINI,
            ProviderId.OPENROUTER,
            ProviderId.CUSTOM_OPENAI_COMPAT,
        )

        compat.forEach { id ->
            val baseUrl = ProviderCatalog.spec(id).defaultBaseUrl ?: "https://custom.example"
            val transport = factory.create(ProviderConfig(id, baseUrl, model = null))
            assertTrue(transport is OpenAICompatTransport, "provider $id")
            assertEquals(id, transport.providerId)
        }
    }

    @Test
    fun `anthropic config produces an AnthropicMessagesTransport`() {
        val factory = DefaultTransportFactory(fixedSecret)
        val id = ProviderId.ANTHROPIC
        val baseUrl = ProviderCatalog.spec(id).defaultBaseUrl ?: "https://custom.example"

        val transport = factory.create(ProviderConfig(id, baseUrl, model = null))

        assertTrue(transport is AnthropicMessagesTransport)
        assertEquals(id, transport.providerId)
    }

    @Test
    fun `anthropic factory never reads the api key at creation time`() = runBlocking {
        var reads = 0
        val countingSecrets = ProviderSecrets {
            reads += 1
            "test-token-0123456789abcdef"
        }
        val factory = DefaultTransportFactory(countingSecrets)

        factory.create(ProviderConfig(ProviderId.ANTHROPIC, "https://api.anthropic.com", model = null))

        assertEquals(0, reads)
    }

    @Test
    fun `openai-compat factory never reads the api key at creation time`() = runBlocking {
        var reads = 0
        val countingSecrets = ProviderSecrets {
            reads += 1
            "test-token-0123456789abcdef"
        }
        val factory = DefaultTransportFactory(countingSecrets)

        factory.create(ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", model = null))

        assertEquals(0, reads)
    }

    @Test
    fun `factory never reads the api key at creation time`() = runBlocking {
        var reads = 0
        val countingSecrets = ProviderSecrets {
            reads += 1
            "test-token-0123456789abcdef"
        }
        val factory = DefaultTransportFactory(countingSecrets)

        factory.create(ProviderConfig(ProviderId.HERMES, "https://bridge.example", model = null))

        // La chiave si legge per-richiesta, non alla costruzione: tiene vivo l'hot-swap del token.
        assertEquals(0, reads)
    }
}
