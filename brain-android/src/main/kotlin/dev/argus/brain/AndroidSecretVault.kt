package dev.argus.brain

import android.content.Context
import android.content.SharedPreferences
import dev.argus.engine.model.SecretPolicy
import dev.argus.engine.secrets.SecretEntry
import dev.argus.engine.secrets.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Key-value storage of the vault: SharedPreferences on the phone, a map in tests. */
interface SecretVaultStorage {
    fun all(): Map<String, String>
    fun get(key: String): String?

    /** Applies [puts] and [removes] in one commit; false if the write fails. */
    fun commit(puts: Map<String, String>, removes: Set<String>): Boolean
}

/** Encryption of a value at rest. */
interface SecretCipher {
    fun encrypt(value: String): String
    fun decrypt(encoded: String): String
}

/**
 * The secrets vault (0.8). Values are encrypted with the non-exportable AndroidKeyStore AES-GCM key the API
 * keys use ([BridgeKeystore], [AesGcmTokenCodec]) and stored in their own private preferences file, which the
 * backup rules exclude like every other shared preference. Write-only for the UI: only [valueFor] decrypts,
 * and it is meant for the HTTP executor at fire time. No method puts a value in a log, an exception message
 * or a returned object other than [valueFor]'s result.
 */
class SecretVaultCore(
    private val storage: SecretVaultStorage,
    private val cipher: SecretCipher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SecretVault {
    private val lock = Any()
    private val entries = MutableStateFlow(readEntries())

    override fun observe(): Flow<List<SecretEntry>> = entries.asStateFlow()

    override suspend fun entries(): List<SecretEntry> = withContext(Dispatchers.IO) {
        synchronized(lock) { readEntries().also { entries.value = it } }
    }

    override suspend fun save(name: String, value: String): Boolean = withContext(Dispatchers.IO) {
        if (!SecretPolicy.isNameValid(name) || !SecretPolicy.isValueValid(value)) return@withContext false
        synchronized(lock) {
            val encoded = runCatching { cipher.encrypt(value) }.getOrNull() ?: return@synchronized false
            // Replacing a value is a new secret for the "last used" line: it has not been used yet.
            storage.commit(mapOf(valueKey(name) to encoded), setOf(usedKey(name)))
                .also { entries.value = readEntries() }
        }
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        if (!SecretPolicy.isNameValid(name)) return@withContext false
        synchronized(lock) {
            if (storage.get(valueKey(name)) == null) return@synchronized false
            storage.commit(emptyMap(), setOf(valueKey(name), usedKey(name)))
                .also { entries.value = readEntries() }
        }
    }

    override suspend fun valueFor(name: String): String? = withContext(Dispatchers.IO) {
        if (!SecretPolicy.isNameValid(name)) return@withContext null
        synchronized(lock) {
            val encoded = storage.get(valueKey(name)) ?: return@synchronized null
            // A value that no longer decrypts (key lost after a restore, tampering) is a missing secret.
            val value = runCatching { cipher.decrypt(encoded) }.getOrNull()
                ?.takeIf(SecretPolicy::isValueValid)
                ?: return@synchronized null
            storage.commit(mapOf(usedKey(name) to nowMillis().toString()), emptySet())
            entries.value = readEntries()
            value
        }
    }

    private fun readEntries(): List<SecretEntry> {
        val all = runCatching { storage.all() }.getOrDefault(emptyMap())
        return all.keys
            .filter { it.startsWith(VALUE_PREFIX) }
            .map { it.removePrefix(VALUE_PREFIX) }
            .filter(SecretPolicy::isNameValid)
            .sorted()
            .map { name -> SecretEntry(name, all[usedKey(name)]?.toLongOrNull()) }
    }

    companion object {
        private const val VALUE_PREFIX = "secret_v1."
        private const val USED_PREFIX = "secret_used_v1."

        fun valueKey(name: String) = VALUE_PREFIX + name
        fun usedKey(name: String) = USED_PREFIX + name
    }
}

/** The vault on the phone: its own private preferences file, the AndroidKeyStore key of the API keys. */
class AndroidSecretVault private constructor(core: SecretVaultCore) : SecretVault by core {
    constructor(context: Context) : this(
        SecretVaultCore(
            PreferencesSecretStorage(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            ),
            KeystoreSecretCipher,
        ),
    )

    private companion object {
        const val PREFERENCES_NAME = "argus_secrets_private"
    }
}

private class PreferencesSecretStorage(private val preferences: SharedPreferences) : SecretVaultStorage {
    override fun all(): Map<String, String> =
        preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun commit(puts: Map<String, String>, removes: Set<String>): Boolean = runCatching {
        val editor = preferences.edit()
        removes.forEach(editor::remove)
        puts.forEach { (key, value) -> editor.putString(key, value) }
        editor.commit()
    }.getOrDefault(false)
}

private object KeystoreSecretCipher : SecretCipher {
    override fun encrypt(value: String): String = AesGcmTokenCodec.encrypt(value, BridgeKeystore.encryptionKey())
    override fun decrypt(encoded: String): String = AesGcmTokenCodec.decrypt(encoded, BridgeKeystore.encryptionKey())
}
