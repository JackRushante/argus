package dev.argus.brain

/**
 * Logica pura (JVM, senza Android) dello schema prefs multi-provider. Isola il naming su disco e
 * la migrazione legacy dalla shell Android [AndroidProviderConfigStore], così da poterla provare
 * senza Keystore né device. I formati delle chiavi sono contratto su disco: un rename è una migrazione.
 */
internal object ProviderPrefsSchema {
    const val KEY_SELECTED_PROVIDER = "selected_provider"
    const val KEY_SCHEMA_VERSION = "provider_schema_v"
    const val SCHEMA_VERSION = 1
    const val LEGACY_KEY_BASE_URL = "base_url" // BridgeConfigurationStore.kt: KEY_BASE_URL
    const val LEGACY_KEY_BEARER = "bearer_v1" // BridgeConfigurationStore.kt: KEY_BEARER

    fun baseUrlKey(id: ProviderId): String = "provider.${id.wireName}.base_url"
    fun modelKey(id: ProviderId): String = "provider.${id.wireName}.model"
    fun apiKeyKey(id: ProviderId): String = "provider.${id.wireName}.key_v1"

    /**
     * Edits (soli put) da applicare in un unico commit prefs. Mappa vuota = niente da fare.
     * Copia i valori legacy Hermes (ciphertext incluso, stessa chiave Keystore) nelle chiavi
     * namespaced SENZA cancellarli: un APK precedente in rollback continua a funzionare.
     * Idempotente: se il marker di schema è già presente, non riscrive nulla.
     */
    fun legacyMigrationEdits(snapshot: Map<String, *>): Map<String, String> {
        if (snapshot[KEY_SCHEMA_VERSION]?.toString() == SCHEMA_VERSION.toString()) return emptyMap()
        val edits = LinkedHashMap<String, String>()
        (snapshot[LEGACY_KEY_BASE_URL] as? String)?.let { edits[baseUrlKey(ProviderId.HERMES)] = it }
        (snapshot[LEGACY_KEY_BEARER] as? String)?.let { edits[apiKeyKey(ProviderId.HERMES)] = it }
        edits[KEY_SELECTED_PROVIDER] = ProviderId.HERMES.wireName
        edits[KEY_SCHEMA_VERSION] = SCHEMA_VERSION.toString()
        return edits
    }

    /**
     * Bound duri identici a `validBearer` (16..4096 char, ASCII stampabile 0x21..0x7e). I prefissi
     * del catalog ([ProviderSpec.apiKeyPrefixHint]) sono SOLO hint UI: mai validazione bloccante.
     */
    fun validApiKey(value: String): Boolean =
        value.length in 16..4_096 && value.all { it.code in 0x21..0x7e }
}
