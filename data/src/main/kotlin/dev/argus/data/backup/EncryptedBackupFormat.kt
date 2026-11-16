package dev.argus.data.backup

import java.security.GeneralSecurityException
import java.text.Normalizer
import java.nio.CharBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * File di backup cifrato (`.argusbackup`, 0.7.2).
 *
 * ```
 * "ARGUSBK1" (8 byte) | salt (16 byte) | IV (12 byte) | AES-256-GCM(testo del backup) + tag (16 byte)
 * ```
 *
 * Il testo cifrato è esattamente quello dell'export completo ([RulesBackupCodec.encode], UTF-8). Magic e
 * salt sono dati associati autenticati: cambiarne un byte rende il file illeggibile come una chiave errata.
 * La chiave viene da [BackupKeyDerivation] sulla passphrase e sul salt del file, quindi un file vecchio
 * resta leggibile con la passphrase di allora anche dopo un cambio. Puro JVM: nessuna classe Android.
 */
object EncryptedBackupFormat {
    private val MAGIC = "ARGUSBK1".toByteArray(Charsets.US_ASCII)
    const val MAGIC_BYTES = 8
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val HEADER_BYTES = MAGIC_BYTES + SALT_BYTES + IV_BYTES

    /** File più corto possibile: intestazione e tag di un testo vuoto. */
    const val MIN_FILE_BYTES = HEADER_BYTES + TAG_BYTES

    /** Byte aggiunti al testo del backup: un export al limite resta importabile anche cifrato. */
    const val OVERHEAD_BYTES = HEADER_BYTES + TAG_BYTES

    const val FILE_EXTENSION = "argusbackup"
    const val MIME_TYPE = "application/octet-stream"

    /** true se il file inizia con il magic: va aperto con una passphrase. */
    fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC_BYTES && (0 until MAGIC_BYTES).all { bytes[it] == MAGIC[it] }

    sealed interface Header {
        class Valid(val salt: ByteArray) : Header
        data object WrongMagic : Header
        data object Truncated : Header
    }

    /** Legge il salt per derivare la chiave, senza decifrare. */
    fun header(bytes: ByteArray): Header = when {
        !hasMagic(bytes) -> Header.WrongMagic
        bytes.size < MIN_FILE_BYTES -> Header.Truncated
        else -> Header.Valid(bytes.copyOfRange(MAGIC_BYTES, MAGIC_BYTES + SALT_BYTES))
    }

    /**
     * Cifra [plaintext]. [iv] deve essere nuovo per ogni file (12 byte casuali): con la stessa chiave
     * riusare un IV romperebbe GCM. Il chiamante lo genera con `SecureRandom`.
     */
    fun seal(plaintext: ByteArray, key: ByteArray, salt: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "chiave di lunghezza errata" }
        require(salt.size == SALT_BYTES) { "salt di lunghezza errata" }
        require(iv.size == IV_BYTES) { "IV di lunghezza errata" }
        val header = MAGIC + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header, 0, MAGIC_BYTES + SALT_BYTES)
        return header + cipher.doFinal(plaintext)
    }

    sealed interface Opened {
        class Plain(val bytes: ByteArray) : Opened
        data object WrongMagic : Opened
        data object Truncated : Opened

        /** Il tag non torna: passphrase sbagliata oppure file modificato (GCM non distingue i due casi). */
        data object WrongKeyOrDamaged : Opened
    }

    fun open(bytes: ByteArray, key: ByteArray): Opened {
        when (header(bytes)) {
            Header.WrongMagic -> return Opened.WrongMagic
            Header.Truncated -> return Opened.Truncated
            is Header.Valid -> Unit
        }
        if (key.size != KEY_BYTES) return Opened.WrongKeyOrDamaged
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, bytes, MAGIC_BYTES + SALT_BYTES, IV_BYTES),
            )
            cipher.updateAAD(bytes, 0, MAGIC_BYTES + SALT_BYTES)
            Opened.Plain(cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES))
        } catch (_: AEADBadTagException) {
            Opened.WrongKeyOrDamaged
        } catch (_: GeneralSecurityException) {
            Opened.WrongKeyOrDamaged
        }
    }
}

/**
 * Chiave del backup: PBKDF2-HMAC-SHA256(passphrase, salt, 600 000 iterazioni, 256 bit).
 *
 * Implementata qui sopra `Mac` invece che con `SecretKeyFactory`: i provider Android e JVM non trattano
 * allo stesso modo i caratteri non ASCII della passphrase, mentre il file deve aprirsi ovunque. La
 * passphrase è normalizzata NFC (la stessa parola scritta da tastiere diverse) e codificata UTF-8.
 */
object BackupKeyDerivation {
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE_LENGTH = 8

    /** Lunghezza in caratteri visibili (code point), quella che l'utente conta. */
    fun passphraseLength(passphrase: CharArray): Int =
        Character.codePointCount(passphrase, 0, passphrase.size)

    fun isAcceptable(passphrase: CharArray): Boolean =
        passphraseLength(passphrase) >= MIN_PASSPHRASE_LENGTH

    fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(iterations >= 1) { "iterazioni non valide" }
        val password = passwordBytes(passphrase)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            // Una chiave HMAC vuota non è ammessa da SecretKeySpec: la passphrase vuota non si usa comunque.
            require(password.isNotEmpty()) { "passphrase vuota" }
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            // Un solo blocco: 256 bit = l'uscita di SHA-256 (RFC 8018, F(P, S, c, 1)).
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val out = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
            }
            return out
        } finally {
            password.fill(0)
        }
    }

    private fun passwordBytes(passphrase: CharArray): ByteArray {
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC)
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(normalized))
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}

/**
 * Salt e chiave derivata di una passphrase. Tiene un segreto: [toString] non lo stampa mai, e chi la
 * usa la cancella con [wipe] quando ha finito.
 */
class BackupKey(salt: ByteArray, key: ByteArray) {
    init {
        require(salt.size == EncryptedBackupFormat.SALT_BYTES) { "salt di lunghezza errata" }
        require(key.size == EncryptedBackupFormat.KEY_BYTES) { "chiave di lunghezza errata" }
    }

    private val saltBytes = salt.copyOf()
    private val keyBytes = key.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()

    /** Copia della chiave: il chiamante la azzera dopo l'uso. */
    fun keyCopy(): ByteArray = keyBytes.copyOf()

    fun wipe() = keyBytes.fill(0)

    override fun toString(): String = "BackupKey(***)"
}
