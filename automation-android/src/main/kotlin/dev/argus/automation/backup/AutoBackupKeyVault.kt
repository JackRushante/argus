package dev.argus.automation.backup

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.argus.data.backup.BackupKey
import dev.argus.data.backup.EncryptedBackupFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La chiave derivata del backup automatico, conservata cifrata. La passphrase non si salva mai. */
interface AutoBackupKeys {
    /** true se una chiave è salvata (non dice se si legge ancora: lo dice [load]). */
    val configured: StateFlow<Boolean>

    suspend fun save(key: BackupKey): Boolean

    /** La chiave, o null se manca o non si decifra più. Chi la riceve la cancella con [BackupKey.wipe]. */
    suspend fun load(): BackupKey?
}

/**
 * Salt e chiave derivata in SharedPreferences, la chiave avvolta (AES-GCM) da una chiave non esportabile
 * di Android Keystore, come le chiavi API in `brain-android` (`AesGcmTokenCodec`, `BridgeKeystore`), ma
 * con un alias suo. Il salt è dato associato: chiave e salt non si possono scambiare con altri. Nessuna
 * autenticazione utente sulla chiave: il backup deve partire di notte senza di lui. Android backup è
 * spento per l'app, quindi niente di questo lascia il telefono.
 */
class AndroidAutoBackupKeys internal constructor(
    private val preferences: SharedPreferences,
    private val wrappingKey: () -> SecretKey,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoBackupKeys {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        { keystoreKey() },
    )

    private val lock = Any()
    private val mutableConfigured = MutableStateFlow(preferences.contains(KEY_WRAPPED))
    override val configured: StateFlow<Boolean> = mutableConfigured.asStateFlow()

    override suspend fun save(key: BackupKey): Boolean = withContext(ioDispatcher) {
        synchronized(lock) {
            val salt = key.salt
            val raw = key.keyCopy()
            try {
                val wrapped = wrap(raw, salt)
                preferences.edit()
                    .putString(KEY_SALT, ENCODER.encodeToString(salt))
                    .putString(KEY_WRAPPED, ENCODER.encodeToString(wrapped))
                    .commit()
            } catch (_: Exception) {
                false
            } finally {
                raw.fill(0)
            }
        }.also { saved -> if (saved) mutableConfigured.value = true }
    }

    override suspend fun load(): BackupKey? = withContext(ioDispatcher) {
        synchronized(lock) {
            val salt = preferences.getString(KEY_SALT, null) ?: return@synchronized null
            val wrapped = preferences.getString(KEY_WRAPPED, null) ?: return@synchronized null
            try {
                val saltBytes = DECODER.decode(salt)
                val raw = unwrap(DECODER.decode(wrapped), saltBytes)
                try {
                    BackupKey(saltBytes, raw)
                } finally {
                    raw.fill(0)
                }
            } catch (_: Exception) {
                // Chiave del Keystore persa o dato rovinato: l'utente imposta di nuovo la passphrase.
                null
            }
        }
    }

    private fun wrap(raw: ByteArray, salt: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(salt)
        val sealed = cipher.doFinal(raw)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "IV AES-GCM non valido" }
        return ByteBuffer.allocate(1 + iv.size + sealed.size).put(VERSION).put(iv).put(sealed).array()
    }

    private fun unwrap(payload: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == EncryptedBackupFormat.SALT_BYTES) { "salt non valido" }
        require(payload.size == 1 + IV_BYTES + EncryptedBackupFormat.KEY_BYTES + TAG_BITS / 8) { "chiave avvolta non valida" }
        require(payload[0] == VERSION) { "versione non supportata" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, payload, 1, IV_BYTES))
        cipher.updateAAD(salt)
        return cipher.doFinal(payload, 1 + IV_BYTES, payload.size - 1 - IV_BYTES)
    }

    companion object {
        private const val PREFERENCES_NAME = "argus_auto_backup_private"
        private const val KEY_SALT = "salt_v1"
        private const val KEY_WRAPPED = "key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_ALIAS = "argus_auto_backup_wrap_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val ENCODER = Base64.getEncoder()
        private val DECODER = Base64.getDecoder()

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
    }
}
