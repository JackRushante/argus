package dev.argus.automation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeResourcesTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val english = File("src/main/res/values/strings.xml").readText()
    private val italian = File("src/main/res/values-it/strings.xml").readText()

    @Test
    fun `wake lock permission and the internal wait receiver are declared`() {
        assertTrue("android.permission.WAKE_LOCK" in manifest)
        assertTrue("dev.argus.automation.power.WaitWakeReceiver" in manifest)
    }

    @Test
    fun `the wait receiver is not exported and listens to no external action`() {
        val receiver = Regex(
            """<receiver\s+android:name="dev\.argus\.automation\.power\.WaitWakeReceiver"[^>]*/>""",
        ).find(manifest)?.value.orEmpty()

        assertTrue(receiver.isNotEmpty(), "receiver di ripresa non dichiarato")
        assertTrue("""android:exported="false"""" in receiver)
        assertFalse("intent-filter" in receiver)
    }

    @Test
    fun `runtime strings exist in english and italian with the same placeholders`() {
        fun strings(xml: String): Map<String, String> = STRING.findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }

        fun placeholders(text: String): List<String> =
            PLACEHOLDER.findAll(text).map { it.value }.toList()

        val en = strings(english)
        val translated = strings(italian)

        assertEquals(en.keys, translated.keys)
        en.forEach { (key, value) ->
            assertEquals(placeholders(value), placeholders(translated.getValue(key)), "segnaposto diversi: $key")
        }
        assertTrue("argus_foreground_denied_text" in en)
        assertTrue("argus_foreground_denied_title" in en)
    }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""")
        val PLACEHOLDER = Regex("""%(\d+\$)?[sd]""")
    }
}
