package dev.argus.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppInstallInfoTest {
    @Test
    fun `every known f-droid client is recognised and labelled`() {
        listOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
            "eu.bubu1.fdroidclassic",
        ).forEach { installer ->
            val info = AppInstallInfo("0.3.6", installer, sdkInt = 34)
            assertTrue(info.fromFDroid, installer)
            assertEquals("F-Droid", info.sourceLabel, installer)
        }
    }

    @Test
    fun `any other installer or none is labelled APK`() {
        listOf(null, "", "com.android.vending", "com.google.android.packageinstaller", "org.fdroid.fdroid.evil")
            .forEach { installer ->
                val info = AppInstallInfo("0.3.6", installer, sdkInt = 34)
                assertFalse(info.fromFDroid, "installer=$installer")
                assertEquals("APK", info.sourceLabel, "installer=$installer")
            }
    }

    @Test
    fun `restricted settings may apply only to apk installs on android 13 and later`() {
        assertTrue(AppInstallInfo("0.3.6", null, sdkInt = 33).mayHaveRestrictedSettings)
        assertTrue(AppInstallInfo("0.3.6", "com.google.android.packageinstaller", sdkInt = 36).mayHaveRestrictedSettings)
        assertFalse(AppInstallInfo("0.3.6", null, sdkInt = 32).mayHaveRestrictedSettings)
        assertFalse(AppInstallInfo("0.3.6", "org.fdroid.fdroid", sdkInt = 36).mayHaveRestrictedSettings)
        assertFalse(AppInstallInfo("0.3.6", "com.android.vending", sdkInt = 36).mayHaveRestrictedSettings)
    }
}
