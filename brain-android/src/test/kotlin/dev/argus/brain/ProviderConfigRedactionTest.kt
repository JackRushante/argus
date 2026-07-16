package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prova a livello di tipo e di comportamento che nessun segreto trapela: né dai config UI/factory,
 * né dai messaggi/`toString()` degli errori di transport, né dal ciphertext AES a riposo.
 * Le chiavi nelle fixture sono sintetiche apposta perché il test ne dimostri la non-uscita.
 */
class ProviderConfigRedactionTest {

    private val manifest = CapabilityManifest(
        deviceModel = "Pixel-TEST-9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.INTERNET"),
        availableTools = listOf("set_dnd"),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )

    @Test
    fun `provider config exposes no secret field even via toString and copy`() {
        val config = ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", "gpt-x")

        val text = config.toString().lowercase()
        assertFalse(text.contains("token"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("sk-"))

        // Garanzia strutturale: nessun campo il cui NOME suggerisca un segreto (via reflection Java,
        // niente kotlin-reflect a runtime). Il segreto passa SOLO da ProviderSecrets.apiKey().
        val fieldNames = ProviderConfig::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { it.contains("key") || it.contains("token") || it.contains("secret") })
    }

    @Test
    fun `transport auth failures never embed the token`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val secret = "sk-test-super-secret-0123456789"
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            val transport = CliBridgeTransport(
                baseUrl = server.url("/").toString(),
                authProvider = BridgeAuthProvider { secret },
                client = CliBridgeTransport.defaultClient(),
                allowCleartextForTests = true,
                requestIdFactory = { "req-redaction-1" },
            )

            val error = assertFailsWith<TransportException> {
                transport.compile("ciao", manifest, DeviceState())
            }
            assertEquals("Bridge HTTP 401", error.message)
            assertFalse(error.toString().contains(secret))
            assertFalse(error.toString().contains("sk-"))

            val unconfigured = CliBridgeTransport(
                baseUrl = server.url("/").toString(),
                authProvider = BridgeAuthProvider { null },
                client = CliBridgeTransport.defaultClient(),
                allowCleartextForTests = true,
                requestIdFactory = { "req-redaction-2" },
            )
            val configError = assertFailsWith<TransportException> {
                unconfigured.compile("ciao", manifest, DeviceState())
            }
            assertEquals("token bridge non configurato", configError.message)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `aes payloads never contain the plaintext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "sk-plaintext-super-secret-0123456789"

        val encoded = AesGcmTokenCodec.encrypt(secret, key)
        val rawBytes = Base64.getDecoder().decode(encoded)

        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        assertFalse(rawBytes.containsSubarray(secretBytes))
    }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            var match = true
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
