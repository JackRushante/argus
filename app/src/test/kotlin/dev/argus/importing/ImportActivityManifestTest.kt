package dev.argus.importing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.7.2: l'Activity che riceve regole da fuori è separata da MainActivity, ha solo i filtri di condivisione e
 * apertura del file di backup, e MainActivity resta senza filtri che portino dati.
 */
class ImportActivityManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun activityBlock(name: String): String {
        val start = manifest.indexOf("android:name=\"$name\"")
        assertTrue(start >= 0, "$name non dichiarata")
        val end = manifest.indexOf("</activity>", start)
        return manifest.substring(start, end)
    }

    @Test
    fun `the import activity receives shared text and opened backup files, nothing else`() {
        val block = activityBlock(".importing.ImportRuleActivity")
        val actions = Regex("""<action android:name="([^"]+)"""").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.intent.action.SEND", "android.intent.action.VIEW"), actions)
        val types = Regex("""android:mimeType="([^"]+)"""").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("text/plain", "application/json"), types)
        assertFalse("LAUNCHER" in block)
        assertFalse("BROWSABLE" in block)
        assertTrue("android:scheme=\"content\"" in block)
        assertFalse("android:scheme=\"file\"" in block)
    }

    @Test
    fun `MainActivity still has only the launcher filter`() {
        val block = activityBlock(".MainActivity")
        assertFalse("android:mimeType" in block)
        assertFalse("android.intent.action.SEND" in block)
        assertFalse("android.intent.action.VIEW" in block)
    }
}
