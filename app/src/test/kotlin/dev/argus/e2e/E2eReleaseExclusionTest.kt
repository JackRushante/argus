package dev.argus.e2e

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L'aggancio degli E2E (0.6.0) vive SOLO nel source set `debug`: la release non ne deve avere traccia,
 * né nel manifest né nelle classi. Il test guarda tre cose:
 *
 *  1. i sorgenti: manifest principale e codice principale non nominano l'aggancio, e l'aggancio sta
 *     soltanto sotto `src/debug`;
 *  2. i risultati di una build di release, SE presenti in `build/` (merged manifest, classi compilate,
 *     APK/AAB, dex): nessuno contiene l'aggancio. Per averli: compilare la release prima di questo test,
 *     ad esempio `:app:processReleaseManifest :app:compileReleaseKotlin :app:testDebugUnitTest`;
 *  3. il manifest di debug dichiara il provider e nient'altro di esportato oltre a quello.
 */
class E2eReleaseExclusionTest {
    private val module = File(".").absoluteFile
    private val authority = "dev.argus.e2e"
    private val providerClass = "dev.argus.e2e.E2eHookProvider"

    private fun file(path: String) = File(module, path)

    private fun kotlinFiles(root: File): List<File> =
        root.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()

    @Test
    fun `the main manifest does not declare the hook`() {
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertFalse(authority in manifest, "l'authority dell'aggancio è nel manifest principale")
        assertFalse("E2eHookProvider" in manifest, "il provider dell'aggancio è nel manifest principale")
        assertFalse("<provider" in manifest, "il manifest principale non dichiara content provider")
    }

    @Test
    fun `no main or release source mentions the hook`() {
        val offenders = listOf("src/main", "src/release", "src/test")
            .flatMap { kotlinFiles(file(it)) }
            .filterNot { it.name == "E2eReleaseExclusionTest.kt" }
            .filter { source ->
                val text = source.readText()
                "dev.argus.e2e" in text || "E2eHookProvider" in text || "E2eOperations" in text
            }
        assertEquals(emptyList(), offenders.map { it.path }, "sorgenti non debug che nominano l'aggancio")
    }

    @Test
    fun `the hook sources exist only under the debug source set`() {
        val hookDirs = file("src").walkTopDown()
            .filter { it.isDirectory && it.name == "e2e" && it.parentFile?.name == "argus" }
            .map { it.relativeTo(file("src")).path.replace('\\', '/') }
            .toList()
        assertTrue(hookDirs.isNotEmpty(), "l'aggancio di debug non si trova")
        hookDirs.forEach { dir ->
            assertTrue(
                dir.startsWith("debug/") || dir.startsWith("testDebug/") || dir.startsWith("test/"),
                "cartella dell'aggancio fuori dai source set di debug/test: $dir",
            )
        }
        assertTrue(hookDirs.any { it.startsWith("debug/") }, "manca l'aggancio in src/debug")
        assertFalse(hookDirs.any { it.startsWith("main/") || it.startsWith("release/") })
    }

    @Test
    fun `the debug manifest declares the provider exported`() {
        val manifest = file("src/debug/AndroidManifest.xml").readText()
        assertTrue(providerClass in manifest)
        assertTrue("android:authorities=\"$authority\"" in manifest)
        assertTrue("<provider" in manifest)
    }

    @Test
    fun `release build outputs contain no trace of the hook`() {
        val build = file("build")
        if (!build.isDirectory) return
        val releaseFiles = build.walkTopDown().filter { it.isFile && inRelease(it) }.toList()
        val problems = mutableListOf<String>()
        for (candidate in releaseFiles) {
            val path = candidate.path.replace('\\', '/')
            when {
                "/dev/argus/e2e/" in path -> problems += "classe/risorsa dell'aggancio nella release: $path"
                candidate.name == "AndroidManifest.xml" -> {
                    val text = candidate.readText()
                    if (authority in text || "E2eHookProvider" in text) problems += "manifest di release con l'aggancio: $path"
                }
                candidate.extension == "apk" || candidate.extension == "aab" -> problems += archiveProblems(candidate)
                candidate.extension == "dex" && containsAscii(candidate.readBytes(), "Ldev/argus/e2e/") ->
                    problems += "dex di release con l'aggancio: $path"
            }
        }
        assertEquals(emptyList(), problems)
    }

    /** Un file di una build di release: il segmento `release` (o `Release`) è nel percorso sotto build/. */
    private fun inRelease(candidate: File): Boolean {
        val relative = candidate.relativeTo(file("build")).path.replace('\\', '/')
        if (relative.startsWith("intermediates/") || relative.startsWith("tmp/") || relative.startsWith("outputs/")) {
            val segments = relative.split('/')
            // Le classi dei test (anche di questo) hanno lo stesso package e non fanno parte della release.
            if (segments.any { it.contains("UnitTest", ignoreCase = true) || it.contains("AndroidTest", ignoreCase = true) }) {
                return false
            }
            return segments.any { it == "release" || it.contains("Release") }
        }
        return false
    }

    private fun archiveProblems(archive: File): List<String> {
        val problems = mutableListOf<String>()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                if (name.contains("dev/argus/e2e/")) problems += "${archive.name}: voce $name"
                if (name == "AndroidManifest.xml") {
                    val bytes = zip.getInputStream(entry).readBytes()
                    if (containsUtf16(bytes, "E2eHookProvider") || containsUtf16(bytes, authority)) {
                        problems += "${archive.name}: il manifest compilato nomina l'aggancio"
                    }
                }
                if (name.endsWith(".dex") && containsAscii(zip.getInputStream(entry).readBytes(), "Ldev/argus/e2e/")) {
                    problems += "${archive.name}: $name contiene classi dell'aggancio"
                }
            }
        }
        return problems
    }

    private fun containsAscii(bytes: ByteArray, needle: String): Boolean = indexOf(bytes, needle.toByteArray(Charsets.US_ASCII))

    private fun containsUtf16(bytes: ByteArray, needle: String): Boolean = indexOf(bytes, needle.toByteArray(Charsets.UTF_16LE))

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
