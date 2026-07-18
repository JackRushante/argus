package dev.argus.brain

import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class BridgeConfigurationStoreTest {
    @Test
    fun `AES GCM token payload round trips and rejects tampering`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encoded = AesGcmTokenCodec.encrypt("0123456789abcdef-test-token", key)

        assertEquals("0123456789abcdef-test-token", AesGcmTokenCodec.decrypt(encoded, key))

        val tampered = Base64.getDecoder().decode(encoded).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        assertFails {
            AesGcmTokenCodec.decrypt(Base64.getEncoder().encodeToString(tampered), key)
        }
    }

    @Test
    fun `bridge URL accepts only credential-free HTTPS endpoints`() {
        assertEquals(
            "https://hermes.example",
            normalizeBridgeBaseUrl(" https://hermes.example/ "),
        )
        assertNull(normalizeBridgeBaseUrl("http://hermes.example"))
        assertNull(normalizeBridgeBaseUrl("https://token@hermes.example"))
        assertNull(normalizeBridgeBaseUrl("https://hermes.example?token=secret"))
    }
}
