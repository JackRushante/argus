package dev.argus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.8, rules before the first unlock: in the `:locked` process the Application returns before the Hilt injection
 * (done by the generated superclass in super.onCreate), so nothing in credential-encrypted storage is opened there.
 */
class BeforeUnlockStartupTest {
    private val source = File("src/main/kotlin/dev/argus/ArgusApplication.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the locked process returns before super onCreate and before any injected field is used`() {
        val onCreate = source.substringAfter("override fun onCreate() {")
        val guard = onCreate.indexOf("if (LockedProcess.isCurrent(this)) return")
        val superCall = onCreate.indexOf("super<Application>.onCreate()")
        assertTrue(guard >= 0, "missing guard")
        assertTrue(superCall > guard, "the guard must come first")
        // Nothing before the guard.
        assertTrue(onCreate.substring(0, guard).lines().all { it.isBlank() || it.trim().startsWith("//") })
    }

    @Test
    fun `configuration changes in the locked process touch no injected field`() {
        val change = source.substringAfter("override fun onConfigurationChanged(").substringBefore("\n    }")
        assertTrue("if (!::renderLanguage.isInitialized) return" in change)
        assertTrue(change.indexOf("isInitialized") < change.indexOf("renderLanguage.refresh()"))
    }

    @Test
    fun `the application and the app components are not direct-boot-aware`() {
        assertFalse("directBootAware" in manifest)
        assertFalse("android:process" in manifest)
    }
}
