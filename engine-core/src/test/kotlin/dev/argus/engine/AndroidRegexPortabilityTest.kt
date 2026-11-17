package dev.argus.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unit tests run on the JVM, the app on Android: Android's regex engine (ICU) rejects some constructs the
 * JVM accepts, and a pattern in a companion or object then crashes the process at class initialisation. This
 * test reads the production sources of every module and refuses the constructs known to differ.
 */
class AndroidRegexPortabilityTest {
    private val forbidden = listOf(
        "(?U)" to "the UNICODE_CHARACTER_CLASS flag does not exist on Android",
        "UNICODE_CHARACTER_CLASS" to "the UNICODE_CHARACTER_CLASS flag does not exist on Android",
    )

    @Test
    fun `production sources use no regex construct that Android rejects`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = root.listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { main -> main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(sources.size > 100, "production sources not found from ${root.path}")
        val hits = sources.flatMap { file ->
            val text = file.readText()
            forbidden.filter { (construct, _) -> construct in text }
                .map { (construct, why) -> "${file.relativeTo(root).invariantSeparatorsPath}: $construct ($why)" }
        }
        assertEquals(emptyList(), hits)
    }
}
