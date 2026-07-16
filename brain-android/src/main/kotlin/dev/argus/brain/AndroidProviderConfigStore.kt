package dev.argus.brain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Store multi-provider su SharedPreferences cifrate (stesso file `argus_bridge_private`, stessa
 * chiave Keystore di [AndroidBridgeConfigurationStore] via [BridgeKeystore]). All'avvio migra la
 * config Hermes legacy verso le chiavi namespaced in un unico commit, senza cancellare gli originali
 * e senza re-encrypt (rollback-safe). I segreti restano cifrati a riposo e non entrano mai in
 * [ProviderConfig]: la chiave si legge on-demand via [apiKey].
 */
class AndroidProviderConfigStore(context: Context) : ProviderConfigStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        synchronized(lock) {
            val edits = ProviderPrefsSchema.legacyMigrationEdits(preferences.all)
            if (edits.isNotEmpty()) {
                preferences.edit().apply { edits.forEach { (key, value) -> putString(key, value) } }.commit()
            }
        }
    }

    override fun selectedProviderId(): ProviderId {
        val wire = preferences.getString(ProviderPrefsSchema.KEY_SELECTED_PROVIDER, null)
        // Prefs assenti/corrotte -> HERMES: fail-safe, mai crash.
        return wire?.let(ProviderId::fromWireName) ?: ProviderId.HERMES
    }

    override suspend fun selectProvider(id: ProviderId): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString(ProviderPrefsSchema.KEY_SELECTED_PROVIDER, id.wireName).commit()
    }

    override fun providerConfig(id: ProviderId): ProviderConfig {
        val spec = ProviderCatalog.spec(id)
        val stored = preferences.getString(ProviderPrefsSchema.baseUrlKey(id), null)
        val legacy = if (id == ProviderId.HERMES) {
            preferences.getString(ProviderPrefsSchema.LEGACY_KEY_BASE_URL, null)
        } else {
            null
        }
        val raw = stored ?: legacy ?: spec.defaultBaseUrl
        val baseUrl = raw
            ?.let { normalizeBridgeBaseUrl(it) ?: spec.defaultBaseUrl }
            ?: ""
        val model = preferences.getString(ProviderPrefsSchema.modelKey(id), null)
        return ProviderConfig(providerId = id, baseUrl = baseUrl, model = model)
    }

    override suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedBase = if (baseUrl != null) {
            normalizeBridgeBaseUrl(baseUrl) ?: return@withContext false
        } else {
            null
        }
        if (apiKey != null && !ProviderPrefsSchema.validApiKey(apiKey)) return@withContext false
        synchronized(lock) {
            runCatching {
                val editor = preferences.edit()
                if (normalizedBase != null) editor.putString(ProviderPrefsSchema.baseUrlKey(id), normalizedBase)
                if (model != null) editor.putString(ProviderPrefsSchema.modelKey(id), model)
                if (apiKey != null) {
                    val encoded = AesGcmTokenCodec.encrypt(apiKey, BridgeKeystore.encryptionKey())
                    editor.putString(ProviderPrefsSchema.apiKeyKey(id), encoded)
                }
                editor.commit()
            }.getOrDefault(false)
        }
    }

    override suspend fun apiKey(id: ProviderId): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val encoded = preferences.getString(ProviderPrefsSchema.apiKeyKey(id), null)
                ?: legacyBearerFor(id)
                ?: return@synchronized null
            runCatching { AesGcmTokenCodec.decrypt(encoded, BridgeKeystore.encryptionKey()) }
                .getOrNull()
                ?.takeIf(ProviderPrefsSchema::validApiKey)
        }
    }

    override suspend fun hasApiKey(id: ProviderId): Boolean = apiKey(id) != null

    override suspend fun clearApiKey(id: ProviderId): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val editor = preferences.edit().remove(ProviderPrefsSchema.apiKeyKey(id))
            // Per HERMES cancella anche il ciphertext legacy: altrimenti apiKey(HERMES) tornerebbe
            // a leggerlo dopo un clear esplicito (la migrazione lo lascia in place, rollback-safe).
            if (id == ProviderId.HERMES) editor.remove(ProviderPrefsSchema.LEGACY_KEY_BEARER)
            editor.commit()
        }
    }

    /** Solo HERMES eredita il ciphertext legacy `bearer_v1` (utenti migrati a metà). */
    private fun legacyBearerFor(id: ProviderId): String? =
        if (id == ProviderId.HERMES) preferences.getString(ProviderPrefsSchema.LEGACY_KEY_BEARER, null) else null

    private companion object {
        const val PREFERENCES_NAME = "argus_bridge_private"
    }
}
