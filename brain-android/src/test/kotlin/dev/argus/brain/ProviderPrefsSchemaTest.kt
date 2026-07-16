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
}
