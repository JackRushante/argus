package dev.argus.brain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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

    @Test fun `la temperatura del compile e' prevista solo per openrouter e custom`() {
        for (id in ProviderId.entries) {
            val expected = if (id == ProviderId.OPENROUTER || id == ProviderId.CUSTOM_OPENAI_COMPAT) 0.2 else null
            assertEquals(expected, ProviderCatalog.spec(id).quirks.compileTemperature, "compileTemperature per $id")
        }
    }

    @Test fun `la scelta del reasoning e' offerta solo per openrouter e custom`() {
        for (id in ProviderId.entries) {
            val expected = id == ProviderId.OPENROUTER || id == ProviderId.CUSTOM_OPENAI_COMPAT
            assertEquals(expected, ProviderCatalog.spec(id).reasoningEffortEditable, "reasoningEffortEditable per $id")
        }
    }

    @Test fun `ogni sforzo di reasoning sopravvive al valore salvato e default resta senza valore`() {
        for (effort in ReasoningEffort.entries) {
            assertEquals(effort, ReasoningEffort.fromWireValue(effort.wireValue), effort.name)
        }
        assertNull(ReasoningEffort.DEFAULT.wireValue, "DEFAULT omette il campo")
        assertEquals(ReasoningEffort.DEFAULT, ReasoningEffort.fromWireValue(null))
        assertEquals(ReasoningEffort.DEFAULT, ReasoningEffort.fromWireValue("sconosciuto"))
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
        // OpenRouter: server tool `openrouter:web_search` nei `tools` (0.5.1: il plugin `web` e il suffisso
        // `:online`, che rompeva i preset `@preset/...`, sono deprecati).
        assertEquals(WebSearchMechanism.OPENROUTER_SERVER_TOOL, ProviderCatalog.spec(ProviderId.OPENROUTER).quirks.webSearch)
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

    @Test fun `every suggested model of a cost tracked provider has a price`() {
        // Le voci dei modelli non più suggeriti restano nel listino (stima per chi li ha già configurati),
        // ma ogni modello che l'app suggerisce, o usa a campo vuoto, deve avere il suo prezzo.
        for (id in ProviderId.entries) {
            val spec = ProviderCatalog.spec(id)
            if (!spec.costTracked) continue
            for (model in spec.defaultModels) {
                assertTrue(model in spec.prices, "modello suggerito senza prezzo: $id/$model")
            }
        }
    }

    @Test fun `the first suggestion is the cheap base model the app uses when the field is empty`() {
        assertEquals(listOf("gpt-6-luna"), ProviderCatalog.spec(ProviderId.OPENAI).defaultModels)
        assertEquals(listOf("claude-haiku-5-5"), ProviderCatalog.spec(ProviderId.ANTHROPIC).defaultModels)
        assertEquals(listOf("gemini-3.8-flash"), ProviderCatalog.spec(ProviderId.GEMINI).defaultModels)
        assertTrue(ProviderCatalog.spec(ProviderId.HERMES).defaultModels.isEmpty())
        assertTrue(ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT).defaultModels.isEmpty())
    }

    @Test fun `openrouter suggests moving latest aliases and the first one is the default`() {
        val models = ProviderCatalog.spec(ProviderId.OPENROUTER).defaultModels
        assertEquals(
            listOf(
                "~deepseek/deepseek-flash-latest",
                "~google/gemini-flash-latest",
                "~moonshotai/kimi-latest",
                "~anthropic/claude-haiku-latest",
            ),
            models,
        )
        assertTrue(models.all { it.startsWith("~") && it.endsWith("-latest") }, models.toString())
    }

    @Test fun `new base models are priced from the official lists and old ones keep their estimate`() {
        val openai = ProviderCatalog.spec(ProviderId.OPENAI).prices
        assertEquals(ModelPrice(100_000, 500_000, cachedInputMicrosPerMTok = 10_000), openai["gpt-6-luna"])
        assertTrue("gpt-5.5" in openai && "gpt-5-mini" in openai)

        val anthropic = ProviderCatalog.spec(ProviderId.ANTHROPIC).prices
        assertEquals(ModelPrice(100_000, 500_000, cachedInputMicrosPerMTok = 10_000), anthropic["claude-haiku-5-5"])
        // Opus: il listino attuale e' $5/$25 (prima $15/$75).
        assertEquals(
            ModelPrice(5_000_000, 25_000_000, cachedInputMicrosPerMTok = 500_000),
            anthropic["claude-opus-4-8"],
        )
        assertTrue("claude-sonnet-4-5" in anthropic)

        val gemini = ProviderCatalog.spec(ProviderId.GEMINI).prices
        // Il listino di oggi; la variazione del 2027-01-01 e' coperta dal test dedicato.
        assertEquals(
            ModelPrice(750_000, 3_750_000, cachedInputMicrosPerMTok = 75_000),
            gemini.getValue("gemini-3.8-flash").effectiveAt(utc(2026, 10, 26)),
        )
        assertTrue("gemini-2.5-pro" in gemini && "gemini-2.5-flash" in gemini)
    }

    @Test fun `gemini 3 8 flash doubles from 2027-01-01 and keeps the old list before`() {
        val price = ProviderCatalog.spec(ProviderId.GEMINI).prices.getValue("gemini-3.8-flash")
        val old = ModelPrice(750_000, 3_750_000, cachedInputMicrosPerMTok = 75_000)
        val new = ModelPrice(1_500_000, 7_500_000, cachedInputMicrosPerMTok = 150_000)

        assertEquals(old, price.effectiveAt(utc(2026, 10, 26)))
        assertEquals(old, price.effectiveAt(utc(2026, 12, 31, 23, 59)))
        assertEquals(new, price.effectiveAt(utc(2027, 1, 1)))
        assertEquals(new, price.effectiveAt(utc(2027, 6, 1)))
    }

    @Test fun `a price without changes is the same at every date`() {
        val flat = ModelPrice(100_000, 500_000, cachedInputMicrosPerMTok = 10_000)
        assertTrue(flat.changes.isEmpty())
        assertEquals(flat, flat.effectiveAt(utc(2020, 1, 1)))
        assertEquals(flat, flat.effectiveAt(utc(2035, 1, 1)))
    }

    @Test fun `price changes start at midnight UTC of their day and chain in order`() {
        val first = ModelPrice(200_000, 1_000_000)
        val second = ModelPrice(300_000, 1_500_000, cachedInputMicrosPerMTok = 30_000)
        val price = ModelPrice(
            inputMicrosPerMTok = 100_000,
            outputMicrosPerMTok = 500_000,
            changes = listOf(
                PriceChange(LocalDate.of(2027, 1, 1), first),
                PriceChange(LocalDate.of(2027, 7, 1), second),
            ),
        )

        assertEquals(100_000L, price.effectiveAt(utc(2026, 12, 31, 23, 59)).inputMicrosPerMTok)
        assertEquals(first, price.effectiveAt(utc(2027, 1, 1)))
        assertEquals(first, price.effectiveAt(utc(2027, 6, 30, 23, 59)))
        assertEquals(second, price.effectiveAt(utc(2027, 7, 1)))
        assertEquals(second, price.effectiveAt(utc(2030, 1, 1)))
    }

    @Test fun `every catalog price change is in strictly ascending date order and not nested`() {
        for (id in ProviderId.entries) {
            for ((model, price) in ProviderCatalog.spec(id).prices) {
                val dates = price.changes.map { it.effectiveFrom }
                assertEquals(dates.sorted().distinct(), dates, "variazioni non ordinate o duplicate: $id/$model")
                for (change in price.changes) {
                    assertTrue(change.price.changes.isEmpty(), "variazione annidata: $id/$model")
                    assertTrue(change.price.inputMicrosPerMTok > 0, "input non positivo: $id/$model")
                    assertTrue(change.price.outputMicrosPerMTok > 0, "output non positivo: $id/$model")
                    change.price.cachedInputMicrosPerMTok?.let {
                        assertTrue(it > 0, "cached non positivo: $id/$model")
                    }
                }
            }
        }
    }

    @Test fun `only gemini 3 8 flash has a scheduled price change`() {
        val withChanges = ProviderId.entries.flatMap { id ->
            ProviderCatalog.spec(id).prices.filterValues { it.changes.isNotEmpty() }.keys.map { "$id/$it" }
        }
        assertEquals(listOf("GEMINI/gemini-3.8-flash"), withChanges)
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun `pricing version is the october 2026 list`() {
        assertEquals("2026-10", ProviderCatalog.PRICING_VERSION)
    }

    @Test fun `wire names round trip`() {
        for (id in ProviderId.entries) {
            assertEquals(id, ProviderId.fromWireName(id.wireName))
        }
        assertNull(ProviderId.fromWireName("zai"))
    }
}
