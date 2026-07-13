package dev.argus.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface BridgeConfigurationStore : BridgeAuthProvider {
    fun baseUrl(): String
    suspend fun saveBaseUrl(value: String): Boolean
    suspend fun saveBearerToken(value: String): Boolean
    suspend fun clearBearerToken(): Boolean
}

/**
 * Configurazione locale del bridge. L'URL non è segreto; il bearer è cifrato AES-GCM con una
 * chiave non esportabile di Android Keystore. Il file è comunque escluso da backup/trasferimento.
 */
class AndroidBridgeConfigurationStore(context: Context) : BridgeConfigurationStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun baseUrl(): String = normalizeBridgeBaseUrl(
        preferences.getString(KEY_BASE_URL, null).orEmpty(),
    ) ?: DEFAULT_BASE_URL

    override suspend fun saveBaseUrl(value: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeBridgeBaseUrl(value) ?: return@withContext false
        preferences.edit().putString(KEY_BASE_URL, normalized).commit()
    }

    override suspend fun saveBearerToken(value: String): Boolean = withContext(Dispatchers.IO) {
        if (!validBearer(value)) return@withContext false
        synchronized(lock) {
            runCatching {
                val encoded = AesGcmTokenCodec.encrypt(value, encryptionKey())
                preferences.edit().putString(KEY_BEARER, encoded).commit()
            }.getOrDefault(false)
        }
    }

    override suspend fun clearBearerToken(): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().remove(KEY_BEARER).commit()
    }

    override suspend fun bearerToken(): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val encoded = preferences.getString(KEY_BEARER, null) ?: return@synchronized null
            runCatching { AesGcmTokenCodec.decrypt(encoded, encryptionKey()) }
                .getOrNull()
                ?.takeIf(::validBearer)
        }
    }

    private fun encryptionKey(): SecretKey {
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

    companion object {
        const val DEFAULT_BASE_URL = "https://hermes.tail04462d.ts.net"
        private const val PREFERENCES_NAME = "argus_bridge_private"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_BEARER = "bearer_v1"
        private const val KEY_ALIAS = "argus_bridge_bearer_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

internal fun normalizeBridgeBaseUrl(raw: String): String? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null
    return url.toString().trimEnd('/')
}

private fun validBearer(value: String): Boolean =
    value.length in 16..4_096 && value.all { it.code in 0x21..0x7e }

internal object AesGcmTokenCodec {
    private const val VERSION: Byte = 1
    private const val TAG_BITS = 128
    private const val MIN_IV_BYTES = 12
    private const val MAX_IV_BYTES = 32

    fun encrypt(value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        require(iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "IV AES-GCM non valido" }
        val payload = ByteBuffer.allocate(2 + iv.size + encrypted.size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(encoded: String, key: SecretKey): String {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size >= 2 + MIN_IV_BYTES + TAG_BITS / 8) { "Payload cifrato troncato" }
        val buffer = ByteBuffer.wrap(payload)
        require(buffer.get() == VERSION) { "Versione payload cifrato incompatibile" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES && buffer.remaining() > ivSize) {
            "IV payload cifrato non valido"
        }
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
    }
}
