package dev.argus.data.backup

import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Formato `.argusbackup` e derivazione della chiave: puro JVM, nessuna classe Android. */
class EncryptedBackupFormatTest {
    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(12) { (0x40 + it).toByte() }
    private val text = """{"format":"argus-rules","version":1,"rules":[]}""".toByteArray()

    // Poche iterazioni nei test della forma del file: la costante vera è provata a parte.
    private fun key(passphrase: String, salt: ByteArray = this.salt) =
        BackupKeyDerivation.derive(passphrase.toCharArray(), salt, iterations = 1_000)

    @Test
    fun `a sealed file opens with the same passphrase and gives back the exact text`() {
        val sealed = EncryptedBackupFormat.seal(text, key("cavallo-batteria"), salt, iv)

        val opened = assertIs<EncryptedBackupFormat.Opened.Plain>(
            EncryptedBackupFormat.open(sealed, key("cavallo-batteria")),
        )
        assertContentEquals(text, opened.bytes)
    }

    @Test
    fun `the file is magic, salt, iv, then ciphertext with a 16 byte tag`() {
        val sealed = EncryptedBackupFormat.seal(text, key("cavallo-batteria"), salt, iv)

        assertEquals("ARGUSBK1", String(sealed.copyOfRange(0, 8), Charsets.US_ASCII))
        assertContentEquals(salt, sealed.copyOfRange(8, 24))
        assertContentEquals(iv, sealed.copyOfRange(24, 36))
        assertEquals(36 + text.size + 16, sealed.size)
        assertEquals(EncryptedBackupFormat.OVERHEAD_BYTES, sealed.size - text.size)
        // Il testo non compare in chiaro.
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("argus-rules"))
        assertTrue(EncryptedBackupFormat.hasMagic(sealed))
        val header = assertIs<EncryptedBackupFormat.Header.Valid>(EncryptedBackupFormat.header(sealed))
        assertContentEquals(salt, header.salt)
    }

    @Test
    fun `a wrong passphrase is told apart from a damaged header`() {
        val sealed = EncryptedBackupFormat.seal(text, key("cavallo-batteria"), salt, iv)

        assertSame(
            EncryptedBackupFormat.Opened.WrongKeyOrDamaged,
            EncryptedBackupFormat.open(sealed, key("cavallo-batteriA")),
        )
    }

    @Test
    fun `a truncated file is reported as such, and a cut in the ciphertext fails the tag`() {
        val sealed = EncryptedBackupFormat.seal(text, key("cavallo-batteria"), salt, iv)
        val k = key("cavallo-batteria")

        assertSame(EncryptedBackupFormat.Opened.Truncated, EncryptedBackupFormat.open(sealed.copyOf(30), k))
        assertSame(EncryptedBackupFormat.Header.Truncated, EncryptedBackupFormat.header(sealed.copyOf(51)))
        assertSame(EncryptedBackupFormat.Opened.Truncated, EncryptedBackupFormat.open(sealed.copyOf(8), k))
        assertSame(
            EncryptedBackupFormat.Opened.WrongKeyOrDamaged,
            EncryptedBackupFormat.open(sealed.copyOf(sealed.size - 1), k),
        )
    }

    @Test
    fun `a file with a wrong magic is not opened`() {
        val sealed = EncryptedBackupFormat.seal(text, key("cavallo-batteria"), salt, iv)
        val wrong = sealed.copyOf().also { it[7] = '2'.code.toByte() }

        assertFalse(EncryptedBackupFormat.hasMagic(wrong))
        assertSame(EncryptedBackupFormat.Opened.WrongMagic, EncryptedBackupFormat.open(wrong, key("cavallo-batteria")))
        assertSame(EncryptedBackupFormat.Header.WrongMagic, EncryptedBackupFormat.header(text))
        assertFalse(EncryptedBackupFormat.hasMagic(ByteArray(0)))
        assertFalse(EncryptedBackupFormat.hasMagic("ARGUS".toByteArray()))
    }

    @Test
    fun `magic and salt are authenticated, so changing the salt in the file breaks it`() {
        val k = key("cavallo-batteria")
        val sealed = EncryptedBackupFormat.seal(text, k, salt, iv)
        val otherSalt = sealed.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val otherBody = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }

        assertSame(EncryptedBackupFormat.Opened.WrongKeyOrDamaged, EncryptedBackupFormat.open(otherSalt, k))
        assertSame(EncryptedBackupFormat.Opened.WrongKeyOrDamaged, EncryptedBackupFormat.open(otherBody, k))
    }

    @Test
    fun `two files of the same text differ when the iv differs`() {
        val k = key("cavallo-batteria")
        val first = EncryptedBackupFormat.seal(text, k, salt, iv)
        val second = EncryptedBackupFormat.seal(text, k, salt, ByteArray(12) { 7 })

        assertNotEquals(first.toList(), second.toList())
    }

    // --- derivazione ------------------------------------------------------------------------------

    @Test
    fun `the derivation is PBKDF2-HMAC-SHA256 with 256 bits and 600 000 iterations by default`() {
        assertEquals(600_000, BackupKeyDerivation.ITERATIONS)
        listOf(1, 2, 1_000).forEach { iterations ->
            val ours = BackupKeyDerivation.derive("password-ascii".toCharArray(), salt, iterations)
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("password-ascii".toCharArray(), salt, iterations, 256))
                .encoded
            assertContentEquals(jdk, ours, "iterazioni $iterations")
            assertEquals(32, ours.size)
        }
    }

    @Test
    fun `the real iteration count gives the same key twice and depends on the salt`() {
        val first = BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), salt)
        val again = BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), salt)
        val otherSalt = BackupKeyDerivation.derive("cavallo-batteria".toCharArray(), ByteArray(16))

        assertContentEquals(first, again)
        assertNotEquals(first.toList(), otherSalt.toList())
        assertNotEquals(first.toList(), key("cavallo-batteria").toList())
    }

    @Test
    fun `the same passphrase typed in two unicode forms gives the same key`() {
        val composed = "perché-così"
        val decomposed = "perché-così"

        assertContentEquals(key(composed), key(decomposed))
    }

    @Test
    fun `a passphrase needs at least 8 characters`() {
        assertEquals(8, BackupKeyDerivation.MIN_PASSPHRASE_LENGTH)
        assertFalse(BackupKeyDerivation.isAcceptable("1234567".toCharArray()))
        assertTrue(BackupKeyDerivation.isAcceptable("12345678".toCharArray()))
        // Le emoji contano come un carattere ciascuna, come le vede l'utente.
        assertFalse(BackupKeyDerivation.isAcceptable("🔒🔒🔒🔒".toCharArray()))
    }

    @Test
    fun `a backup key never prints its secret`() {
        val backupKey = BackupKey(salt, key("cavallo-batteria"))

        assertEquals("BackupKey(***)", backupKey.toString())
        assertContentEquals(salt, backupKey.salt)
        val copy = backupKey.keyCopy()
        backupKey.wipe()
        assertTrue(backupKey.keyCopy().all { it == 0.toByte() })
        assertFalse(copy.all { it == 0.toByte() })
    }
}
