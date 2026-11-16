package dev.argus.engine.model

import dev.argus.engine.model.ShellQuoting.Context
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Audit 0.7.2 (S2, S3): tabella di template ostili scritta indipendentemente dallo scanner. Ogni riga
 * usa `<x>` al posto del segnaposto di un dato esterno. Tre classi:
 *  - bloccati: il dato diventerebbe codice (nome del comando, comando che rilegge gli argomenti,
 *    costrutto non modellato) → mai inseribile;
 *  - numerici: la shell valuta il dato come espressione aritmetica → solo un intero, o bloccato;
 *  - ammessi: il dato resta un argomento inerte → il quoting basta e la regola deve funzionare.
 */
class ShellQuotingAuditTest {
    private fun context(template: String): Context {
        val real = template.replace("<x>", "\${x}")
        val references = InterpolationPolicy.references(real)
        check(references.isNotEmpty()) { "nessun segnaposto in: $template" }
        return ShellQuoting.contexts(real, references.map { it.range }).first()
    }

    private val blocked = setOf(Context.UNSAFE, Context.COMMAND_POSITION, Context.REEVALUATED)
    private val plain = setOf(Context.WORD, Context.SINGLE_QUOTED, Context.DOUBLE_QUOTED)

    private fun failures(templates: List<String>, ok: (Context) -> Boolean): List<String> =
        templates.mapNotNull { template ->
            val context = context(template)
            if (ok(context)) null else "$context  <-  ${template.replace("\n", "\\n")}"
        }

    @Test
    fun `data that would become code is always blocked`() {
        val templates = listOf(
            "sh -c \"echo <x>\"",
            "/system/bin/sh -c \"echo <x>\"",
            "\"sh\" -c \"echo <x>\"",
            "\\sh -c \"echo <x>\"",
            "su -c \"id <x>\"",
            "su 0 sh -c 'echo <x>'",
            "eval echo <x>",
            "eval \"echo <x>\"",
            "<x> --help",
            "echo hi; <x>",
            "echo hi | <x>",
            "echo hi && <x>",
            "echo hi || <x>",
            "echo a & <x>",
            "( <x> )",
            "{ <x>; }",
            "if <x>; then :; fi",
            "while <x>; do :; done",
            "! <x>",
            "echo a\n<x>",
            "FOO=bar <x>",
            "nohup <x> &",
            "env FOO=1 sh -c \"echo <x>\"",
            "timeout 5 sh -c \"ping -c1 <x>\"",
            "busybox sh -c \"echo <x>\"",
            "toybox sh -c \"echo <x>\"",
            "exec sh -c \"echo <x>\"",
            "command sh -c \"echo <x>\"",
            "setsid sh -c \"echo <x>\"",
            "nice -n 5 sh -c \"echo <x>\"",
            "mksh -c <x>",
            "echo <x> | sh",
            "echo <x> | bash -s",
            "echo <x> | su",
            "xargs -I{} echo <x>",
            "awk \"{print <x>}\" /sdcard/f",
            "sed -e \"s/a/<x>/\" /sdcard/f",
            ". <x>",
            "source <x>",
            "trap \"<x>\" EXIT",
            "find /sdcard -name a -exec sh -c \"echo <x>\" \\;",
            "n=<x>; sh -c \"echo \$n\"",
            "\$SHELL -c \"echo <x>\"",
            "s=sh; \$s -c \"echo <x>\"",
            "echo \$(( <x> + 1 ))",
            "echo \$(echo <x>)",
            "echo `echo <x>`",
            "[ <x>0 -gt 5 ]",
            "cat <<EOF\n<x>\nEOF",
            "echo \$<x>",
            "echo \\<x>",
        )
        val wrong = failures(templates) { it in blocked }
        assertTrue(wrong.isEmpty(), "non bloccati:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `data the shell evaluates as arithmetic is numeric or blocked`() {
        val templates = listOf(
            "[ <x> -gt 5 ]",
            "[ \"<x>\" -eq 5 ]",
            "[ 5 -lt '<x>' ]",
            "test <x> -ne 0 && echo hi",
            "test 5 -le <x>",
            "[[ <x> -ge 3 ]]",
            "[[ 3 -lt \"<x>\" ]]",
            "(( <x> > 3 ))",
            "((<x>))",
            "(( a = <x> ))",
            "let y=<x>+1",
            "let \"y = <x>\"",
            "typeset -i n=<x>",
            "integer n=<x>",
            "arr[<x>]=1",
            "n=<x>; [ \$n -gt 5 ]",
            "n=<x>; [ \"\$n\" -gt 5 ]",
            "export N=<x>; [ \"\$N\" -gt 2 ]",
            "if [ <x> -eq 1 ]; then echo a; fi",
            "while [ \"\$i\" -lt <x> ]; do i=1; done",
            "[ <x> -gt 5 -a 1 -eq 1 ]",
            "[ 1 -eq 1 -o <x> -lt 2 ]",
            "echo a; [ <x> -ne 2 ] && echo b",
            "n=<x>; (( n > 3 ))",
            "n=<x>; let m=n+1",
            "n=<x>; echo \$(( n + 1 ))",
        )
        val wrong = failures(templates) { it !in plain }
        assertTrue(wrong.isEmpty(), "trattati come semplice testo:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `an inert argument stays usable`() {
        val templates = listOf(
            "echo <x>",
            "echo \"<x>\"",
            "echo '<x>'",
            "echo \"msg: <x>\" >> /sdcard/log.txt",
            "log -t argus <x>",
            "cmd notification post -t Title tag <x>",
            "input text <x>",
            "am start -a android.intent.action.VIEW -d <x>",
            "am broadcast -a com.example.ACTION --es text <x>",
            "settings put system foo <x>",
            "[ <x> = 5 ]",
            "[ \"<x>\" = \"ok\" ] && echo yes",
            "[ -n <x> ]",
            "[[ <x> == foo ]]",
            "case <x> in a) echo a;; esac",
            "sleep 1; echo <x>",
            "echo a | grep <x>",
            "echo <x> | tee /sdcard/out.txt",
            "printf '%s\\n' <x>",
            "echo -- <x>",
            "f=<x>; rm \"\$f\"",
            "f=<x>; [[ \$f == a ]]",
            "f=<x>; case \$f in a) :;; esac",
            "f=<x>; g=\$f; echo \"\$g\"",
            "f=<x>; for w in \$f; do echo \"\$w\"; done",
        )
        val wrong = failures(templates) { it in plain }
        assertTrue(wrong.isEmpty(), "bloccati senza motivo:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `a stored value expanded without quotes is not an inert argument`() {
        // `$f` senza apici subisce divisione in parole e glob: non è più "un solo argomento".
        val wrong = failures(
            listOf(
                "f=<x>; rm \$f",
                "f=<x>; ls -l \$f /sdcard",
                "f=<x>; echo hi > \$f",
                "f=<x>; for w in \$f; do rm \$w; done",
            ),
        ) { it !in plain }
        assertTrue(wrong.isEmpty(), "riuso non quotato ammesso:\n" + wrong.joinToString("\n"))
    }
}
