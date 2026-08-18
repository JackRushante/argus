package dev.argus.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShizukuDownloadTargetTest {
    @Test
    fun `download fallback is store neutral HTTPS`() {
        assertTrue(SHIZUKU_DOWNLOAD_URL.startsWith("https://"))
        assertFalse(SHIZUKU_DOWNLOAD_URL.startsWith("market://"))
        assertFalse("play.google.com" in SHIZUKU_DOWNLOAD_URL)
    }
}
