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
        assertNull(ShellQuoting.render("x", Context.COMMAND_POSITION))
        assertNull(ShellQuoting.render("x", Context.REEVALUATED))
    }

    // --- S2: posizioni in cui la shell valuta un numero ---------------------------------------------

    @Test
    fun `operands the shell evaluates as arithmetic are numeric whether bare or quoted`() {
        val numeric = listOf(
            "[ \${x} -eq 1 ]",
            "[ \"\${x}\" -gt 1 ]",
            "[ 1 -lt '\${x}' ]",
            "test \${x} -le 5",
            "[[ \${x} -ne 0 ]] && echo hi",
            "if [ \${x} -ge 3 ]; then echo hi; fi",
            "(( \${x} > 1 ))",
            "(( n = \"\${x}\" + 1 ))",
            "while (( \${x} )); do break; done",
            "let n=\${x}",
            "let \${x}",
            "typeset -i n=\${x}",
            "integer n=\${x}",
            "local -i n=\${x}",
            "a[\${x}]=1",
            "RANDOM=\${x}",
            "shift \${x}",
            "ulimit -t \${x}",
        )
        numeric.forEach { assertEquals(listOf(Context.NUMERIC), contexts(it), it) }
    }

    @Test
    fun `a numeric position with other text glued to the value or an unclassifiable neighbour is unsafe`() {
        listOf(
            "[ x\${x} -eq 1 ]",
            "[ \"\${x}1\" -eq 1 ]",
            "(( a\${x} > 1 ))",
            "let n=a\${x}",
            "[[ -v \${x} ]]",
            "typeset \${x}",
            "unset \${x}",
            "read \${x}",
        ).forEach { assertEquals(listOf(Context.UNSAFE), contexts(it), it) }
        // L'operatore stesso viene dal dato: l'altro operando potrebbe diventare aritmetico.
        assertEquals(listOf(Context.UNSAFE, Context.UNSAFE), contexts("[ \${x} \${y} 1 ]"))
        assertEquals(listOf(Context.UNSAFE), contexts("[ \"\$a\" \${x} 1 ]"))
    }

    @Test
    fun `a value stored in a shell variable is numeric where that variable is evaluated as a number`() {
        assertEquals(listOf(Context.NUMERIC), contexts("n=\${x}; [ \"\$n\" -gt 3 ] && echo big"))
        assertEquals(listOf(Context.NUMERIC), contexts("n=\${x}; (( n > 3 ))"))
        assertEquals(listOf(Context.NUMERIC), contexts("typeset -i n; n=\${x}"))
        assertEquals(listOf(Context.NUMERIC), contexts("for v in \${x}; do [ \$v -eq 1 ]; done"))
        // Riletto dopo un costrutto che lo scanner non modella: non si sa come viene usato.
        assertEquals(listOf(Context.UNSAFE), contexts("n=\${x}; echo \$((n + 1))"))
        // Usato come testo resta un argomento quotato.
        assertEquals(listOf(Context.WORD), contexts("n=\${x}; echo \"\$n\""))
    }

    @Test
    fun `text comparisons and ordinary arguments keep their quoting context`() {
        assertEquals(listOf(Context.DOUBLE_QUOTED), contexts("[ \"\${x}\" = yes ]"))
        assertEquals(listOf(Context.WORD, Context.WORD), contexts("[ \${x} != \${y} ]"))
        assertEquals(listOf(Context.WORD), contexts("[ -n \${x} ]"))
        assertEquals(listOf(Context.WORD), contexts("echo \${x} -eq"))
        assertEquals(listOf(Context.WORD), contexts("case \${x} in yes) echo y;; esac"))
    }

    // --- S3: posizione del comando e comandi che rileggono gli argomenti ----------------------------

    @Test
    fun `a placeholder in the command word is command position`() {
        listOf(
            "\${x}",
            "\${x} --help",
            "\"\${x}\" arg",
            "/system/bin/\${x}",
            "echo hi; \${x}",
            "echo hi\n\${x}",
            "true && \${x}",
            "false || \${x}",
            "echo hi | \${x}",
            "sleep 1 & \${x}",
            "(\${x})",
            "{ \${x}; }",
            "if \${x}; then echo y; fi",
            "if true; then \${x}; else \${x}; fi".substringBefore("; else"),
            "while true; do \${x}; done",
            "! \${x}",
            "FOO=1 \${x}",
            "env \${x}",
            "env -i FOO=1 \${x}",
            "nohup \${x} &",
            "timeout 5 \${x}",
            "timeout -s KILL 5 \${x}",
            "nice -n 5 \${x}",
            "busybox \${x} -h",
            "command -v \${x}",
            "exec \${x}",
            ">/dev/null \${x}",
        ).forEach { assertEquals(Context.COMMAND_POSITION, contexts(it).first(), it) }
    }

    @Test
    fun `every placeholder of a command that reads its arguments again is reevaluated`() {
        listOf(
            "sh -c \"echo \${x}\"",
            "sh -c 'echo \${x}'",
            "/system/bin/sh -c \${x}",
            "bash -c \"\${x}\"",
            "su -c \"echo \${x}\"",
            "su 0 sh -c \${x}",
            "eval echo \${x}",
            "eval \"echo \${x}\"",
            ". \${x}",
            "trap \${x} EXIT",
            "xargs echo \${x}",
            "find /sdcard -name \${x} -exec rm {} ';'",
            "awk \"BEGIN{print \\\"\${x}\\\"}\"",
            "sed \"s/a/\${x}/\" /tmp/f",
            "python3 -c \${x}",
            "sqlite3 /data/db \"select \${x}\"",
            "busybox sh -c \${x}",
            "toybox xargs \${x}",
            "env FOO=1 sh -c \${x}",
            "nohup sh -c \${x}",
            "timeout 5 sh -c \${x}",
            "env -S \${x}",
            "X=\${x} sh -c 'echo \$X'",
            "\$CMD \${x}",
        ).forEach { assertEquals(listOf(Context.REEVALUATED), contexts(it), it) }
    }

    @Test
    fun `data piped into an interpreter or stored and re-read as code is reevaluated`() {
        listOf(
            "echo \${x} | sh",
            "echo \"\${x}\" | xargs rm",
            "printf %s \${x} | su",
            "echo \${x} | grep a | sh",
            "echo \${x} | while read l; do eval \"\$l\"; done",
            "while read l; do echo \$l; done < /dev/null; echo \${x} | (sh)",
            "msg=\${x}; sh -c \"echo \$msg\"",
            "msg=\${x}; sh -c 'echo \$msg'",
            "msg=\${x}; \$msg",
        ).forEach { assertTrue(contexts(it).first() in setOf(Context.REEVALUATED, Context.COMMAND_POSITION), it) }
        // Una pipe verso un comando che legge dati, non codice, non cambia nulla.
        assertEquals(listOf(Context.WORD), contexts("echo \${x} | grep -c a"))
        // Un comando successivo, non in pipe, non riceve il dato.
        assertEquals(listOf(Context.WORD), contexts("echo \${x} > /tmp/f; sh /tmp/script"))
    }

    @Test
    fun `a value that starts an argument without a preceding double dash is option like`() {
        fun optionLike(template: String) = ShellQuoting.placements(
            template, InterpolationPolicy.references(template).map { it.range },
        ).map { it.optionLike }
        assertEquals(listOf(true), optionLike("rm \${x}"))
        assertEquals(listOf(true), optionLike("rm \"\${x}\""))
        assertEquals(listOf(false), optionLike("rm -- \${x}"))
        assertEquals(listOf(false), optionLike("rm /sdcard/\${x}"))
        assertEquals(listOf(false), optionLike("am broadcast --es text=\${x}"))
        assertEquals(listOf(true, false), optionLike("cp \${x} -- \${y}"))
        // Le posizioni bloccate o numeriche non portano questo avviso.
        assertEquals(listOf(false), optionLike("\${x}"))
        assertEquals(listOf(false), optionLike("[ \${x} -eq 1 ]"))
    }

    @Test
    fun `numeric placeholders accept only a plain integer from external data`() {
        val template = "[ \${x} -gt 5 ] && echo big"
        assertEquals("[ 7 -gt 5 ] && echo big", resolvedCommand(template, tainted("7")))
        assertEquals("[ -3 -gt 5 ] && echo big", resolvedCommand(template, tainted("-3")))
        listOf(
            "a[\$(id)]", "1.5", "0x10", "1e3", " 7", "7 ", "+7", "", "1234567890123456789", "1+1", "x",
        ).forEach { value ->
            val resolution = TaintAwareInterpolator().resolve(
                Action.RunShell(template), VarScope(mapOf("x" to tainted(value))),
            )
            assertEquals("shell_numeric_context", assertIs<ActionResolution.Blocked>(resolution, value).code, value)
        }
        // Un valore fidato (letterale approvato) entra com'è, come in ogni altro contesto.
        val clean = VarValue("2", VarType.NUMBER, IntegrityLabel.CLEAN, ConfidentialityLabel.PUBLIC, setOf(ValueProvenance.LITERAL))
        assertEquals("[ 2 -gt 5 ] && echo big", resolvedCommand(template, clean))
    }

    @Test
    fun `external data in command position or in a reevaluating command is blocked at run time`() {
        listOf("\${x}", "sh -c \"echo \${x}\"", "echo \${x} | sh", "eval echo \${x}").forEach { template ->
            val resolution = TaintAwareInterpolator().resolve(
                Action.RunShell(template), VarScope(mapOf("x" to tainted("ls"))),
            )
            assertEquals("shell_quote_context", assertIs<ActionResolution.Blocked>(resolution, template).code, template)
        }
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
