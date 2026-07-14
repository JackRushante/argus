package dev.argus.automation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.argus.data.DeferredReplyStore
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifratura at-rest delle reply differite (E13): AES-GCM con chiave dedicata e non esportabile
 * di Android Keystore. Il plaintext esiste solo in memoria, mai in DB, log o backup.
 */
class DeferredReplyCipher(private val key: () -> SecretKey) {
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
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

    fun decrypt(encoded: String): String {
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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(StandardCharsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val TAG_BITS = 128
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
        private const val KEY_ALIAS = "argus_deferred_reply_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun withKeystoreKey(): DeferredReplyCipher = DeferredReplyCipher(::keystoreKey)

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

/**
 * Sink E13 di produzione: la lane può marcare DEFERRED soltanto se il testo è stato cifrato e
 * persistito davvero. Qualsiasi errore ritorna false e l'esecuzione resta un FAILED onesto.
 */
class PersistentDeferredReplySink(
    private val store: DeferredReplyStore,
    private val cipher: DeferredReplyCipher,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DeferredReplySink {
    init {
        require(ttlMillis > 0) { "Il TTL della reply differita deve essere positivo" }
    }

    override suspend fun defer(context: FireContext, text: String): Boolean = try {
        val notification = context.event as? TriggerEvent.NotificationPosted
        if (notification == null) {
            false
        } else {
            val now = nowMillis()
            store.save(
                DeferredReplyEntity(
                    executionId = context.executionId.value,
                    actionIndex = context.actionIndex,
                    packageName = notification.pkg,
                    createdAtMillis = now,
                    expiresAtMillis = now + ttlMillis,
                    consumedAtMillis = null,
                    payload = cipher.encrypt(text),
                ),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    companion object {
        /** TTL breve per scelta: una risposta vecchia più di un giorno non va più consegnata. */
        const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1_000
    }
}
