package dev.argus.nfc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.8: il trampolino dei tag NFC è esportato SOLO per le scoperte NFC, e l'NFC resta facoltativo. */
class NfcManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private val activity: String = Regex(
        """<activity\s+android:name="\.nfc\.NfcTagActivity"[\s\S]*?</activity>""",
    ).find(manifest)?.value ?: error("NfcTagActivity non dichiarata")

    @Test
    fun `nfc is a normal permission and optional hardware`() {
        assertTrue("""<uses-permission android:name="android.permission.NFC" />""" in manifest)
        assertTrue(
            Regex("""<uses-feature\s+android:name="android.hardware.nfc"\s+android:required="false"\s*/>""")
                .containsMatchIn(manifest),
        )
    }

    @Test
    fun `the trampoline listens only to nfc discoveries, shows nothing and keeps no history`() {
        val actions = Regex("""<action android:name="([^"]+)"""").findAll(activity).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.nfc.action.TECH_DISCOVERED", "android.nfc.action.TAG_DISCOVERED"), actions)
        assertTrue("""android:exported="true"""" in activity)
        assertTrue("""android:noHistory="true"""" in activity)
        assertTrue("""android:excludeFromRecents="true"""" in activity)
        assertTrue("""android:theme="@style/Theme.Argus.Trampoline"""" in activity)
        assertTrue("@xml/nfc_tech_filter" in activity)
        assertTrue("android.nfc.tech.NfcA" in File("src/main/res/xml/nfc_tech_filter.xml").readText())
    }
}
