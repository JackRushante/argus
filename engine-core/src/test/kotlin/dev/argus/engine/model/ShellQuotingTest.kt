package dev.argus.engine.model

import dev.argus.engine.model.ShellQuoting.Context
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.VarScope
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D1: quoting per contesto dei dati esterni nei comandi shell. */
class ShellQuotingTest {
    private fun contexts(template: String): List<Context> =
        ShellQuoting.contexts(template, InterpolationPolicy.references(template).map { it.range })

    // --- quote() ---------------------------------------------------------------------------------

    @Test
    fun `quote wraps in single quotes and rewrites embedded single quotes`() {
        assertEquals("'a'\\''b; rm -rf /'", ShellQuoting.quote("a'b; rm -rf /"))
        assertEquals("''", ShellQuoting.quote(""))
        assertEquals("''\\'''\\'''", ShellQuoting.quote("''"))
        assertEquals("'\$(id) `id` \"x\" \\ \n'", ShellQuoting.quote("\$(id) `id` \"x\" \\ \n"))
    }

    @Test
    fun `double quote escaping neutralizes exactly the characters that stay active`() {
        assertEquals("a\\\"b\\\$c\\\\d\\`e", ShellQuoting.escapeForDoubleQuotes("a\"b\$c\\d`e"))
        assertEquals("plain 'single' text", ShellQuoting.escapeForDoubleQuotes("plain 'single' text"))
    }

    @Test
    fun `single quote escaping closes escapes and reopens`() {
        assertEquals("a'\\''b", ShellQuoting.escapeForSingleQuotes("a'b"))
    }

    // --- contesto del segnaposto -----------------------------------------------------------------

    @Test
    fun `placeholder context follows quoting state`() {
        assertEquals(listOf(Context.WORD), contexts("echo \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo pre\${x}post"))
        assertEquals(listOf(Context.DOUBLE_QUOTED), contexts("echo \"\${x}\""))
        assertEquals(listOf(Context.DOUBLE_QUOTED), contexts("echo \"a \${x} b\""))
        assertEquals(listOf(Context.SINGLE_QUOTED), contexts("echo '\${x}'"))
        assertEquals(listOf(Context.SINGLE_QUOTED), contexts("echo 'a \${x} b'"))
        // Dopo la chiusura degli apici si torna fuori dagli apici.
        assertEquals(listOf(Context.WORD), contexts("echo \"a\" 'b' \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo \"a'b\" \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo 'a\"b' \${x}"))
        // Apice non chiuso: il segnaposto è comunque dentro il suo contesto.
        assertEquals(listOf(Context.DOUBLE_QUOTED), contexts("echo \"\${x}"))
    }

    @Test
    fun `each placeholder gets its own context in order`() {
        assertEquals(
            listOf(Context.WORD, Context.DOUBLE_QUOTED, Context.SINGLE_QUOTED, Context.WORD),
            contexts("echo \${a} \"\${b}\" '\${c}' \${d}"),
        )
    }

    @Test
    fun `escapes make the placeholder unsafe except inside single quotes where the backslash is literal`() {
        assertEquals(listOf(Context.UNSAFE), contexts("echo \\\${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo \"\\\${x}\""))
        // Backslash escapato a sua volta: il segnaposto non è escapato.
        assertEquals(listOf(Context.WORD), contexts("echo \\\\\${x}"))
        // Un backslash prima di un apice lo rende letterale e non cambia lo stato.
        assertEquals(listOf(Context.WORD), contexts("echo \\' \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo \\\" \${x}"))
        assertEquals(listOf(Context.SINGLE_QUOTED), contexts("echo '\\\${x}'"))
    }

    @Test
    fun `a dollar glued to the placeholder is unsafe outside single quotes`() {
        // `$` + 'valore' diventerebbe $'…' (ANSI-C), `$` + (…) una sostituzione di comando.
        assertEquals(listOf(Context.UNSAFE), contexts("echo \$\${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo \"\$\${x}\""))
        assertEquals(listOf(Context.SINGLE_QUOTED), contexts("echo '\$\${x}'"))
    }

    @Test
    fun `constructions the scanner does not model make later placeholders unsafe`() {
        assertEquals(listOf(Context.WORD, Context.UNSAFE), contexts("echo \${a} \$(date) \${b}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo \"\$(date)\" \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo \"\$(date) \${x}\""))
        assertEquals(listOf(Context.UNSAFE), contexts("echo `date` \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo \"`date`\" \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo \$'a\\nb' \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("cat <<EOF \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("cat <<< \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo hi # \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("echo hi;# \${x}"))
        assertEquals(listOf(Context.UNSAFE), contexts("#\${x}"))
        // Un `${` non valido (il validator lo rifiuta come malformato) non si interpreta.
        assertEquals(listOf(Context.UNSAFE), contexts("echo \${OTHER} \${x}"))
    }

    @Test
    fun `constructions that are inert inside single quotes or mid-word do not poison the template`() {
        assertEquals(listOf(Context.WORD), contexts("echo '\$(date) `x` <<' \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo a#b \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo \$# \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo \$HOME \${x}"))
        assertEquals(listOf(Context.WORD), contexts("echo 1 < \${x}"))
    }

    @Test
    fun `render uses the context specific escaping and refuses unsafe contexts`() {
        assertEquals("'a'\\''b'", ShellQuoting.render("a'b", Context.WORD))
        assertEquals("a'\\''b", ShellQuoting.render("a'b", Context.SINGLE_QUOTED))
        assertEquals("a\\\"b", ShellQuoting.render("a\"b", Context.DOUBLE_QUOTED))
        assertNull(ShellQuoting.render("x", Context.UNSAFE))
    }

    // --- prova con una shell vera ------------------------------------------------------------------

    private val hostile = listOf(
        "plain", "with space", "a'b; rm -rf /", "it's", "'", "''", "'\"'", "\\", "\\'", "a\\", "\\\\",
        "\$(echo pwned)", "`echo pwned`", "\"; echo pwned; \"", "\$HOME", "\${HOME}", "\$'x'", "\$((1+1))",
        "line1\nline2", "tab\there", "cr\rhere", "*", "?", "[a-z]", "~", "~root", "!", "!!", "#hash", "-n", "-e",
        "a;b&c|d>e<f", "x && echo pwned", "x || echo pwned", "\n; echo pwned", "<<EOF", "{a,b}", "è€é",
        " leading and trailing ", "a\"b", "a\$b", "a`b", "\\n", "%s %d", "",
    )

    private fun shellAvailable(): Boolean = File("/bin/sh").canExecute()

    /** Lo script va su stdin in UTF-8: l'argv di un processo dipende dal locale del builder. */
    private fun runShell(command: String): String {
        val process = ProcessBuilder("/bin/sh")
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write((command + '\n').toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "la shell non termina: $command")
        return output
    }

    private fun tainted(text: String) = VarValue(
        text, VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.SMS),
    )

    private fun resolvedCommand(template: String, value: VarValue): String {
        val resolution = TaintAwareInterpolator().resolve(Action.RunShell(template), VarScope(mapOf("x" to value)))
        return assertIs<Action.RunShell>(assertIs<ActionResolution.Resolved>(resolution).value.action).cmd
    }

    @Test
    fun `a real shell sees every hostile value as inert data in every supported context`() {
        assumeTrue(shellAvailable(), "serve /bin/sh")
        // (template, parte prima, parte dopo): l'output atteso è prima + valore + dopo.
        val templates = listOf(
            Triple("printf %s pre\${x}post", "pre", "post"),
            Triple("printf %s \${x}", "", ""),
            Triple("printf %s \"pre \${x} post\"", "pre ", " post"),
            Triple("printf %s \"\${x}\"", "", ""),
            Triple("printf %s 'pre \${x} post'", "pre ", " post"),
            Triple("printf %s '\${x}'", "", ""),
        )
        val failures = mutableListOf<String>()
        for ((template, before, after) in templates) {
            for (value in hostile) {
                val command = resolvedCommand(template, tainted(value))
                val output = runShell(command)
                if (output != before + value + after) {
                    failures += "template=$template value=${value.replace("\n", "\\n")} -> «$output» da «$command»"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `a hostile value never changes how many commands run`() {
        assumeTrue(shellAvailable(), "serve /bin/sh")
        val marker = File.createTempFile("argus-shell-quoting", ".marker").also { it.delete() }
        val path = marker.absolutePath
        try {
            val payloads = listOf(
                "'; touch $path; echo '",
                "\"; touch $path; echo \"",
                "; touch $path",
                "\$(touch $path)",
                "`touch $path`",
                "\n touch $path \n",
                "x && touch $path",
            )
            val templates = listOf("echo \${x}", "echo \"\${x}\"", "echo '\${x}'", "echo pre\${x}post")
            for (template in templates) {
                for (payload in payloads) {
                    runShell(resolvedCommand(template, tainted(payload)))
                    assertTrue(!marker.exists(), "il valore esterno ha eseguito un comando: $template con «$payload»")
                }
            }
            // Controllo del test: lo stesso payload GREZZO (valore CLEAN) e' davvero un'iniezione.
            val clean = VarValue(
                "; touch $path", VarType.TEXT, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC,
                setOf(ValueProvenance.LITERAL),
            )
            runShell(resolvedCommand("echo \${x}", clean))
            assertTrue(marker.exists(), "il payload di controllo deve essere davvero eseguibile")
        } finally {
            marker.delete()
        }
    }

    @Test
    fun `unsafe contexts are blocked instead of inserting the tainted value`() {
        val scope = VarScope(mapOf("x" to tainted("\$(rm -rf /)")))
        for (template in listOf("echo \$(date) \${x}", "echo \\\${x}", "echo `date` \${x}", "cat <<EOF \${x}", "echo \$\${x}")) {
            val resolution = TaintAwareInterpolator().resolve(Action.RunShell(template), scope)
            assertEquals(
                "shell_quote_context",
                assertIs<ActionResolution.Blocked>(resolution, template).code,
                template,
            )
        }
    }

    @Test
    fun `clean values in unsafe contexts are still inserted as authored`() {
        val clean = VarValue("date +%s", VarType.TEXT, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, setOf(ValueProvenance.LITERAL))
        assertEquals("echo \$(date +%s)", resolvedCommand("echo \$(\${x})", clean))
    }
}
