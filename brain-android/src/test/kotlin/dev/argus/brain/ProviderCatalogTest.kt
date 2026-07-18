package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCatalogTest {
    @Test fun `every provider id has a complete spec`() {
        for (id in ProviderId.entries) {
            val spec = ProviderCatalog.spec(id)
            assertEquals(id, spec.id, "spec key/id disallineati per $id")
            assertTrue(spec.displayName.isNotBlank(), "displayName vuoto per $id")
        }
        // La mappa non contiene entry orfane oltre i ProviderId noti.
        assertEquals(ProviderId.entries.toSet(), ProviderCatalog.specs.keys)
    }

    @Test fun `default base urls are https normalized and credential free`() {
        for (id in ProviderId.entries) {
            val url = ProviderCatalog.spec(id).defaultBaseUrl ?: continue
            assertEquals(url, normalizeBridgeBaseUrl(url), "defaultBaseUrl non normalizzato per $id")
        }
    }

    @Test fun `self hosted providers do not embed a developer endpoint`() {
        assertEquals("", AndroidBridgeConfigurationStore.DEFAULT_BASE_URL)
        assertNull(ProviderCatalog.spec(ProviderId.HERMES).defaultBaseUrl)
    }

    @Test fun `custom provider has no default url and hermes has no prices`() {
        assertNull(ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT).defaultBaseUrl)
        assertTrue(ProviderCatalog.spec(ProviderId.HERMES).prices.isEmpty())
        assertTrue(ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT).prices.isEmpty())
    }

    @Test fun `costTracked solo per i provider a prezzo noto`() {
        val tracked = setOf(ProviderId.OPENAI, ProviderId.ANTHROPIC, ProviderId.GEMINI)
        for (id in ProviderId.entries) {
            assertEquals(
                id in tracked,
                ProviderCatalog.spec(id).costTracked,
                "costTracked incoerente per $id",
            )
        }
    }

    @Test fun `solo i provider costTracked hanno un listino prezzi`() {
        for (id in ProviderId.entries) {
            val spec = ProviderCatalog.spec(id)
            if (spec.costTracked) {
                assertTrue(spec.prices.isNotEmpty(), "$id costTracked ma senza modelli prezzati")
            } else {
                // TOKEN-ONLY (Hermes/OpenRouter/Custom): nessuna stima dollari da listino statico.
                assertTrue(spec.prices.isEmpty(), "$id token-only con prezzi: ${spec.prices.keys}")
            }
        }
    }

    @Test fun `quirks follow the finding table`() {
        val openai = ProviderCatalog.spec(ProviderId.OPENAI)
        assertEquals(OutputCapParam.MAX_COMPLETION_TOKENS, openai.quirks.outputCapParam)
        assertEquals(AuthStyle.BEARER, openai.authStyle)

        val anthropic = ProviderCatalog.spec(ProviderId.ANTHROPIC)
        assertEquals(AuthStyle.X_API_KEY, anthropic.authStyle)
        assertEquals(OutputCapParam.MAX_TOKENS, anthropic.quirks.outputCapParam)
        assertEquals("2023-06-01", anthropic.quirks.extraHeaders["anthropic-version"])

        assertEquals(AuthStyle.BEARER, ProviderCatalog.spec(ProviderId.GEMINI).authStyle)
        assertEquals(AuthStyle.BEARER, ProviderCatalog.spec(ProviderId.OPENROUTER).authStyle)
    }

    @Test fun `web search mechanism follows the verified per-provider table`() {
        // Anthropic: server tool web_search_20250305 nell'array tools (loop interno lato Anthropic).
        assertEquals(WebSearchMechanism.ANTHROPIC_TOOL, ProviderCatalog.spec(ProviderId.ANTHROPIC).quirks.webSearch)
        // OpenRouter: slug modello con suffisso `:online`.
        assertEquals(WebSearchMechanism.OPENROUTER_ONLINE, ProviderCatalog.spec(ProviderId.OPENROUTER).quirks.webSearch)
        // Gemini: GEMINI_NATIVE — grounding `google_search` via API nativa generateContent (validato
        // live 2026-07-17). Non passa dallo shim OpenAI-compat /chat/completions.
        assertEquals(WebSearchMechanism.GEMINI_NATIVE, ProviderCatalog.spec(ProviderId.GEMINI).quirks.webSearch)
        // OpenAI: OPENAI_RESPONSES — tool `web_search` via Responses API /responses (validato live
        // 2026-07-17). Non passa da /chat/completions (che richiederebbe un modello `-search-preview`).
        assertEquals(WebSearchMechanism.OPENAI_RESPONSES, ProviderCatalog.spec(ProviderId.OPENAI).quirks.webSearch)
        // Custom e Hermes non attivano il web da questo path (Hermes lo fa nel bridge) → NONE.
        assertEquals(WebSearchMechanism.NONE, ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT).quirks.webSearch)
        assertEquals(WebSearchMechanism.NONE, ProviderCatalog.spec(ProviderId.HERMES).quirks.webSearch)
    }

    @Test fun `auth headers never live in extraHeaders`() {
        val forbidden = setOf("authorization", "x-api-key")
        for (id in ProviderId.entries) {
            val keys = ProviderCatalog.spec(id).quirks.extraHeaders.keys.map { it.lowercase() }
            for (k in keys) {
                assertFalse(k in forbidden, "header di auth in extraHeaders per $id: $k")
            }
        }
    }

    @Test fun `catalog carries no secret material`() {
        val realKey = Regex("sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}")
        val dump = ProviderCatalog.specs.toString()
        assertFalse(realKey.containsMatchIn(dump), "il catalogo contiene materiale che sembra una chiave")
    }

    @Test fun `prices are strictly positive and pricing version is a year-month stamp`() {
        assertTrue(Regex("""\d{4}-\d{2}""").matches(ProviderCatalog.PRICING_VERSION))
        for (id in ProviderId.entries) {
            for ((model, price) in ProviderCatalog.spec(id).prices) {
                assertTrue(price.inputMicrosPerMTok > 0, "prezzo input non positivo: $id/$model")
                assertTrue(price.outputMicrosPerMTok > 0, "prezzo output non positivo: $id/$model")
                price.cachedInputMicrosPerMTok?.let {
                    assertTrue(it > 0, "prezzo cached non positivo: $id/$model")
                }
            }
        }
        for (id in listOf(ProviderId.OPENAI, ProviderId.ANTHROPIC, ProviderId.GEMINI)) {
            assertTrue(ProviderCatalog.spec(id).prices.isNotEmpty(), "$id senza modelli prezzati")
        }
    }

    @Test fun `priced models are listed among default models`() {
        for (id in ProviderId.entries) {
            val spec = ProviderCatalog.spec(id)
            for (model in spec.prices.keys) {
                assertTrue(model in spec.defaultModels, "modello prezzato non a catalogo: $id/$model")
            }
        }
    }

    @Test fun `wire names round trip`() {
        for (id in ProviderId.entries) {
            assertEquals(id, ProviderId.fromWireName(id.wireName))
        }
        assertNull(ProviderId.fromWireName("zai"))
    }
}
