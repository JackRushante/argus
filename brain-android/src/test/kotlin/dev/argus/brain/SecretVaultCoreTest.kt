package dev.argus.brain

import dev.argus.engine.secrets.SecretEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretVaultCoreTest {
    private class MapStorage : SecretVaultStorage {
        val map = linkedMapOf<String, String>()
        var failWrites = false
        override fun all(): Map<String, String> = map.toMap()
        override fun get(key: String): String? = map[key]
        override fun commit(puts: Map<String, String>, removes: Set<String>): Boolean {
            if (failWrites) return false
            removes.forEach(map::remove)
            map.putAll(puts)
            return true
        }
    }

    /** Reversible stand-in for AES-GCM: enough to prove nothing is stored in clear. */
    private object ReverseCipher : SecretCipher {
        override fun encrypt(value: String) = "enc:" + value.reversed()
        override fun decrypt(encoded: String): String {
            require(encoded.startsWith("enc:"))
            return encoded.removePrefix("enc:").reversed()
        }
    }

    private val storage = MapStorage()
    private var now = 1_000L
    private val vault = SecretVaultCore(storage, ReverseCipher) { now }

    @Test
    fun `a saved value is stored encrypted and listed by name only`() = runBlocking {
        assertTrue(vault.save("ha_token", "abc123"))
        assertFalse(storage.map.values.any { "abc123" in it })
        assertEquals(listOf(SecretEntry("ha_token", null)), vault.entries())
        assertEquals(setOf("ha_token"), vault.names())
        assertFalse("abc123" in vault.entries().toString())
        assertEquals(listOf(SecretEntry("ha_token", null)), vault.observe().first())
    }

    @Test
    fun `an invalid name or value is refused`() = runBlocking {
        assertFalse(vault.save("Ha-Token", "x"))
        assertFalse(vault.save("ok", ""))
        assertFalse(vault.save("ok", "a\nb"))
        assertFalse(vault.save("ok", "a".repeat(4_097)))
        assertTrue(storage.map.isEmpty())
    }

    @Test
    fun `reading a value at fire time records the last use`() = runBlocking {
        vault.save("ha_token", "abc123")
        now = 5_000L
        assertEquals("abc123", vault.valueFor("ha_token"))
        assertEquals(listOf(SecretEntry("ha_token", 5_000L)), vault.entries())
        assertNull(vault.valueFor("missing"))
        assertNull(vault.valueFor("Bad Name"))
    }

    @Test
    fun `replacing a value resets the last use and deleting removes it`() = runBlocking {
        vault.save("ha_token", "old")
        vault.valueFor("ha_token")
        assertTrue(vault.save("ha_token", "new"))
        assertEquals(listOf(SecretEntry("ha_token", null)), vault.entries())
        assertEquals("new", vault.valueFor("ha_token"))
        assertTrue(vault.delete("ha_token"))
        assertFalse(vault.delete("ha_token"))
        assertTrue(storage.map.isEmpty())
        assertNull(vault.valueFor("ha_token"))
    }

    @Test
    fun `a value that no longer decrypts is a missing secret`() = runBlocking {
        storage.map[SecretVaultCore.valueKey("ha_token")] = "garbage"
        assertNull(vault.valueFor("ha_token"))
    }

    @Test
    fun `a failed write reports false`() = runBlocking {
        storage.failWrites = true
        assertFalse(vault.save("ha_token", "abc"))
        assertEquals(emptyList(), vault.entries())
    }
}
