package dev.argus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regressione richiesta insieme alla tabella deferred (P1-6): il database contiene ciphertext
 * di conversazioni e la protezione vale solo finché backup e device-transfer restano esclusi.
 */
class ManifestHardeningTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `backup and device transfer stay disabled`() {
        assertTrue("android:allowBackup=\"false\"" in manifest)
        assertTrue("android:fullBackupContent=\"false\"" in manifest)
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }

    @Test
    fun `battery exemption permission is declared for the user gesture cta`() {
        assertTrue(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in manifest,
            "la CTA package-specific richiede la permission nel manifest",
        )
    }

    @Test
    fun `data extraction rules exclude every domain`() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (domain in listOf("file", "database", "sharedpref")) {
            assertTrue("domain=\"$domain\"" in rules, "dominio $domain non escluso da backup/transfer")
        }
    }
}
