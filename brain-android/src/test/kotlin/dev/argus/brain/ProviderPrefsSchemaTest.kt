package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Logica pura dello schema prefs multi-provider: naming stabile su disco, migrazione legacy
 * additiva e idempotente, validazione chiave a bound duri. Nessun Android, nessun Keystore.
 */
class ProviderPrefsSchemaTest {

    @Test
    fun `legacy hermes config migrates to namespaced keys without deleting the originals`() {
        val snapshot = mapOf(
            "base_url" to "https://x.example",
            "bearer_v1" to "opaque-ciphertext-blob-not-a-real-token",
        )

        val edits = ProviderPrefsSchema.legacyMigrationEdits(snapshot)

        assertEquals("https://x.example", edits["provider.hermes.base_url"])
        // Ciphertext copiato carattere per carattere: stessa chiave Keystore, nessun re-encrypt.
        assertEquals("opaque-ciphertext-blob-not-a-real-token", edits["provider.hermes.key_v1"])
        assertEquals("hermes", edits["selected_provider"])
        assertEquals("1", edits["provider_schema_v"])
        // edits è un set di soli put: le chiavi legacy non compaiono (rollback-safe).
        assertFalse(edits.containsKey("base_url"))
        assertFalse(edits.containsKey("bearer_v1"))
    }

    @Test
    fun `migration is idempotent`() {
        val snapshot = mapOf(
            "provider_schema_v" to 1,
            "base_url" to "https://x.example",
            "bearer_v1" to "opaque",
        )

        assertTrue(ProviderPrefsSchema.legacyMigrationEdits(snapshot).isEmpty())
    }

    @Test
    fun `bearer-only legacy config migrates the ciphertext and leaves base url to the default`() {
        val snapshot = mapOf("bearer_v1" to "opaque-ciphertext")

        val edits = ProviderPrefsSchema.legacyMigrationEdits(snapshot)

        assertEquals("opaque-ciphertext", edits["provider.hermes.key_v1"])
        assertFalse(edits.containsKey("provider.hermes.base_url"))
        assertEquals("hermes", edits["selected_provider"])
        assertEquals("1", edits["provider_schema_v"])
    }

    @Test
    fun `fresh install produces only the schema marker`() {
        val edits = ProviderPrefsSchema.legacyMigrationEdits(emptyMap<String, Any?>())

        assertEquals(
            mapOf("selected_provider" to "hermes", "provider_schema_v" to "1"),
            edits,
        )
    }

    @Test
    fun `api key validation keeps hard bounds but ignores catalog hints`() {
        // Sotto il minimo di 16 caratteri.
        assertFalse(ProviderPrefsSchema.validApiKey("A".repeat(15)))
        // Contiene spazi (0x20) e carattere non-ASCII: rifiutata a prescindere dalla lunghezza.
        assertFalse(ProviderPrefsSchema.validApiKey("chiave con spazi non valida…"))
        // Chiave senza prefisso "sk-": l'hint del catalog è soft, non blocca.
        assertTrue(ProviderPrefsSchema.validApiKey("nonprefixed-0123456789abcdef"))
    }

    @Test
    fun `custom endpoints accept a one character key, other providers keep the 16 character floor`() {
        val custom = ProviderId.CUSTOM_OPENAI_COMPAT
        assertTrue(ProviderPrefsSchema.validApiKey(custom, "x"))
        assertTrue(ProviderPrefsSchema.validApiKey(custom, "local"))
        assertTrue(ProviderPrefsSchema.validApiKey(custom, "A".repeat(4_096)))
        // sempre ASCII stampabile, senza spazi né controlli, e dentro il tetto
        assertFalse(ProviderPrefsSchema.validApiKey(custom, ""))
        assertFalse(ProviderPrefsSchema.validApiKey(custom, "con spazio"))
        assertFalse(ProviderPrefsSchema.validApiKey(custom, "tab	qui"))
        assertFalse(ProviderPrefsSchema.validApiKey(custom, "chiave-è"))
        assertFalse(ProviderPrefsSchema.validApiKey(custom, "A".repeat(4_097)))
        for (id in ProviderId.entries.filter { it != custom }) {
            assertFalse(ProviderPrefsSchema.validApiKey(id, "short"), id.name)
            assertTrue(ProviderPrefsSchema.validApiKey(id, "nonprefixed-0123456789abcdef"), id.name)
        }
    }

    @Test
    fun `prefs key naming is stable per provider`() {
        assertEquals("provider.openai.base_url", ProviderPrefsSchema.baseUrlKey(ProviderId.OPENAI))
        assertEquals("provider.openai.model", ProviderPrefsSchema.modelKey(ProviderId.OPENAI))
        assertEquals("provider.openai.key_v1", ProviderPrefsSchema.apiKeyKey(ProviderId.OPENAI))
        assertEquals("provider.anthropic.key_v1", ProviderPrefsSchema.apiKeyKey(ProviderId.ANTHROPIC))
        assertEquals("provider.hermes.key_v1", ProviderPrefsSchema.apiKeyKey(ProviderId.HERMES))
        assertEquals(
            "provider.custom_openai_compat.key_v1",
            ProviderPrefsSchema.apiKeyKey(ProviderId.CUSTOM_OPENAI_COMPAT),
        )
    }

    // --- S6: la chiave vale per la sua origine ---

    @Test
    fun `same origin means same scheme host and effective port`() {
        assertTrue(ProviderPrefsSchema.sameOrigin("https://api.example.com/v1", "https://api.example.com/v2"))
        assertTrue(ProviderPrefsSchema.sameOrigin("https://API.example.com/v1", "https://api.example.com:443/v1"))
        assertFalse(ProviderPrefsSchema.sameOrigin("https://api.example.com/v1", "http://api.example.com/v1"))
        assertFalse(ProviderPrefsSchema.sameOrigin("https://api.example.com/v1", "https://api.example.com:8443/v1"))
        assertFalse(ProviderPrefsSchema.sameOrigin("https://api.example.com/v1", "http://127.0.0.1:11434/v1"))
        assertFalse(ProviderPrefsSchema.sameOrigin("https://api.example.com/v1", "https://evil.example.com/v1"))
        assertFalse(ProviderPrefsSchema.sameOrigin("not a url", "https://api.example.com/v1"))
    }

    /** Store in memoria che applica la stessa regola dell'implementazione Android. */
    private class MemoryStore(var base: String, var key: String?) : ProviderConfigStore {
        override fun selectedProviderId() = ProviderId.CUSTOM_OPENAI_COMPAT
        override suspend fun selectProvider(id: ProviderId) = true
        override fun providerConfig(id: ProviderId) = ProviderConfig(id, base, "m")
        override suspend fun saveProviderConfig(
            id: ProviderId,
            baseUrl: String?,
            model: String?,
            apiKey: String?,
            reasoningEffort: ReasoningEffort?,
        ): Boolean {
            if (baseUrl != null && apiKey == null && baseUrlChangesOrigin(id, baseUrl)) key = null
            if (baseUrl != null) base = normalizeProviderBaseUrl(baseUrl, id) ?: return false
            if (apiKey != null) key = apiKey
            return true
        }
        override suspend fun apiKey(id: ProviderId) = key
        override suspend fun hasApiKey(id: ProviderId) = key != null
        override suspend fun clearApiKey(id: ProviderId) = true.also { key = null }
    }

    @Test
    fun `a new server address is a change of origin only when scheme host or port change`() {
        val store = MemoryStore("https://api.example.com/v1", "sk-cloud-1234567890abcdef")
        val custom = ProviderId.CUSTOM_OPENAI_COMPAT

        assertFalse(store.baseUrlChangesOrigin(custom, "https://api.example.com/v2"))
        assertTrue(store.baseUrlChangesOrigin(custom, "http://127.0.0.1:11434/v1"))
        assertTrue(store.baseUrlChangesOrigin(custom, "http://192.168.1.20:8080/v1"))
        // Un indirizzo che il salvataggio rifiuta non cambia nulla.
        assertFalse(store.baseUrlChangesOrigin(custom, "http://public.example.com/v1"))
    }

    @Test
    fun `the legacy hermes save refuses a new server without a new bearer`() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore("https://bridge.example", "bearer-0123456789abcdef")

        assertTrue(store.saveConfiguration("https://bridge.example/", bearerToken = null))
        assertFalse(store.saveConfiguration("https://other-bridge.example", bearerToken = null))
        assertTrue(store.saveConfiguration("https://other-bridge.example", bearerToken = "bearer-new-0123456789ab"))
    }
}
