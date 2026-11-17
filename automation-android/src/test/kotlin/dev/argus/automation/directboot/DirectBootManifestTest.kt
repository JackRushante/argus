package dev.argus.automation.directboot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Before the first unlock only the two locked receivers may run: they are the only direct-boot-aware components of
 * every module, they live in their own process and they are not exported.
 */
class DirectBootManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val allManifests = listOf("../app", "../automation-android", "../brain-android", "../core-shizuku", "../data", "../device-tools", "../ui")
        .map { File(it, "src/main/AndroidManifest.xml") }
        .filter { it.isFile }
        .associate { it.path to it.readText() }

    private fun receiver(name: String): String {
        val open = Regex("""<receiver\s+[^>]*android:name="${Regex.escape(name)}"[^>]*?(/>|>)""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest) ?: error("receiver $name not declared")
        if (open.groupValues[1] == "/>") return open.value
        val end = manifest.indexOf("</receiver>", open.range.last)
        return manifest.substring(open.range.first, end + "</receiver>".length)
    }

    @Test
    fun `the locked receivers are direct-boot-aware, private and in their own process`() {
        listOf(
            "dev.argus.automation.directboot.LockedBootReceiver",
            "dev.argus.automation.directboot.LockedAlarmReceiver",
        ).forEach { name ->
            val block = receiver(name)
            assertTrue("""android:directBootAware="true"""" in block, name)
            assertTrue("""android:exported="false"""" in block, name)
            assertTrue("""android:process="${LockedProcess.SUFFIX}"""" in block, name)
        }
        assertTrue("android.intent.action.LOCKED_BOOT_COMPLETED" in receiver("dev.argus.automation.directboot.LockedBootReceiver"))
        assertFalse("intent-filter" in receiver("dev.argus.automation.directboot.LockedAlarmReceiver"))
    }

    @Test
    fun `no other component of any module is direct-boot-aware or runs in the locked process`() {
        assertTrue(allManifests.size >= 5, "manifests: ${allManifests.keys}")
        val aware = allManifests.values.sumOf { Regex("""android:directBootAware="true"""").findAll(it).count() }
        assertEquals(2, aware)
        val inLocked = allManifests.values.sumOf { Regex("""android:process="${LockedProcess.SUFFIX}"""").findAll(it).count() }
        assertEquals(2, inLocked)
        allManifests.forEach { (path, text) ->
            assertFalse(Regex("""<application[^>]*directBootAware""").containsMatchIn(text), path)
        }
        assertEquals(1, Regex("LOCKED_BOOT_COMPLETED").findAll(manifest).count())
    }

    @Test
    fun `the locked path builds nothing of the app graph`() {
        val sources = File("src/main/kotlin/dev/argus/automation/directboot")
        val locked = listOf("LockedBootReceivers.kt", "LockedSession.kt", "LockedActionRunner.kt", "DirectBootStorage.kt")
            .associateWith { File(sources, it).readText() }
        val bannedImports = listOf(
            "dagger.", "javax.inject.", "dev.argus.automation.di.", "dev.argus.data.", "androidx.room.",
            "rikka.shizuku.", "dev.argus.shizuku.", "okhttp3.", "java.security.KeyStore", "android.security.keystore.",
            "dev.argus.brain.", "dev.argus.automation.secrets.",
        )
        locked.forEach { (name, text) ->
            val imports = text.lines().filter { it.startsWith("import ") }
            bannedImports.forEach { banned ->
                assertFalse(imports.any { banned in it }, "$name imports $banned")
            }
            assertFalse("getSharedPreferences" in text, "$name reads preferences")
            assertFalse("ArgusDatabase" in text, "$name opens the database")
        }
        // Only device-encrypted storage.
        assertTrue("createDeviceProtectedStorageContext()" in locked.getValue("DirectBootStorage.kt"))
        assertFalse("filesDir" in locked.getValue("LockedBootReceivers.kt"))
    }
}
