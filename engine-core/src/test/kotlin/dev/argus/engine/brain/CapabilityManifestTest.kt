package dev.argus.engine.brain
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.StateQueryFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class CapabilityManifestTest {
    @Test fun `render lists device, contacts with ids, state keys and disabled tools`() {
        val m = CapabilityManifest(
            deviceModel = "OnePlus 15", androidVersion = 16, androidApi = 36,
            shizukuAvailable = true,
            grantedPermissions = listOf("notification_listener"),
            availableTools = listOf("screen.capture", "toggle.set", "whatsapp_reply"),
            unavailableTools = mapOf("vision.analyze" to "nessun provider multimodale"),
            whitelistedContacts = listOf(WhitelistedContact("Moglie", "jid:42")),
            stateReaders = StateReaderManifest(
                families = listOf(StateQueryFamily.SETTING, StateQueryFamily.DUMPSYS_FIELD),
            ),
        )
        val s = m.render()
        assertTrue(s.contains("OnePlus 15"))
        assertTrue(s.contains("Android 16"))
        assertTrue(s.contains("vision.analyze") && s.contains("nessun provider multimodale"))
        assertTrue(s.contains("Moglie") && s.contains("jid:42"))
        assertTrue(s.contains(StateKeys.RINGER) && s.contains("silent"))
        assertTrue(s.contains("setting") && s.contains("dumpsys_field"))
    }

    @Test fun `render does not claim shell actions are queued when shizuku is off`() {
        val m = CapabilityManifest(
            deviceModel = "Pixel", androidVersion = 16, androidApi = 36,
            shizukuAvailable = false,
            grantedPermissions = emptyList(),
            availableTools = emptyList(),
            unavailableTools = emptyMap(),
            whitelistedContacts = emptyList(),
        )
        val s = m.render()
        assertTrue(s.contains("NON attivo"))
        assertFalse(s.contains("in coda"))
    }

    @Test fun `reply language defaults to English and accepts only en or it`() {
        fun manifest(language: String? = null): CapabilityManifest {
            val base = CapabilityManifest(
                deviceModel = "Pixel", androidVersion = 16, androidApi = 36,
                shizukuAvailable = false,
                grantedPermissions = emptyList(),
                availableTools = emptyList(),
                unavailableTools = emptyMap(),
                whitelistedContacts = emptyList(),
            )
            return if (language == null) base else base.copy(replyLanguage = language)
        }
        assertEquals("en", manifest().replyLanguage)
        assertEquals("it", manifest("it").replyLanguage)
        assertEquals("en", manifest("en").replyLanguage)
        assertFailsWith<IllegalArgumentException> { manifest("fr") }
        assertFailsWith<IllegalArgumentException> { manifest("IT") }
        assertFailsWith<IllegalArgumentException> { manifest("") }
    }

    @Test fun `reader families must be unique and canonically ordered`() {
        assertFailsWith<IllegalArgumentException> {
            StateReaderManifest(
                families = listOf(StateQueryFamily.DUMPSYS_FIELD, StateQueryFamily.SETTING),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StateReaderManifest(
                families = listOf(StateQueryFamily.SETTING, StateQueryFamily.SETTING),
            )
        }
    }
}
