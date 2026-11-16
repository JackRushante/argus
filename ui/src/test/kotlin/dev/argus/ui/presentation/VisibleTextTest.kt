package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S8: il testo eseguibile in approvazione non può contenere caratteri invisibili o che riordinano il
 * testo: ognuno diventa un marcatore `⟨U+XXXX⟩` (Cf, Zl, Zp, Cc tranne a capo e tab, surrogati spaiati).
 */
class VisibleTextTest {

    @Test
    fun `a right-to-left override cannot reorder a shell command`() {
        val cmd = "rm -rf /sdcard/‮txt.exe"
        assertEquals(
            listOf(
                VisibleText.Part("rm -rf /sdcard/"),
                VisibleText.Part("⟨U+202E⟩", isMarker = true),
                VisibleText.Part("txt.exe"),
            ),
            VisibleText.parts(cmd),
        )
        assertEquals("rm -rf /sdcard/⟨U+202E⟩txt.exe", VisibleText.reveal(cmd))
    }

    @Test
    fun `every hidden category becomes a marker`() {
        val hidden = mapOf(
            '​' to "⟨U+200B⟩", // zero width space (Cf)
            '﻿' to "⟨U+FEFF⟩", // BOM (Cf)
            '⁦' to "⟨U+2066⟩", // left-to-right isolate (Cf)
            '­' to "⟨U+00AD⟩", // soft hyphen (Cf)
            ' ' to "⟨U+2028⟩", // line separator (Zl)
            ' ' to "⟨U+2029⟩", // paragraph separator (Zp)
            '\u0000' to "⟨U+0000⟩", // NUL (Cc)
            '\u0007' to "⟨U+0007⟩", // bell (Cc)
            '\r' to "⟨U+000D⟩", // carriage return (Cc)
            '\u001B' to "⟨U+001B⟩", // escape (Cc)
            '\u0085' to "⟨U+0085⟩", // next line (Cc)
        )
        hidden.forEach { (char, marker) ->
            assertEquals("a${marker}b", VisibleText.reveal("a${char}b"), "U+%04X".format(char.code))
        }
    }

    @Test
    fun `newline and tab stay as they are and ordinary text is untouched`() {
        val text = "echo ciao\n\tcd /sdcard && ls -la 'Città' · \${otp}"
        assertEquals(listOf(VisibleText.Part(text)), VisibleText.parts(text))
        assertEquals(text, VisibleText.reveal(text))
    }

    @Test
    fun `characters outside the basic plane are handled as whole code points`() {
        // Un'emoji (due char, simbolo) resta; un tag Unicode (Cf, fuori dal piano base) si marca intero.
        assertEquals("ok 👍", VisibleText.reveal("ok 👍"))
        assertEquals("a⟨U+E0041⟩b", VisibleText.reveal("a󠁁b"))
        // Un surrogato spaiato non si disegna: si marca.
        assertEquals("a⟨U+D800⟩b", VisibleText.reveal("a\uD800b"))
    }

    @Test
    fun `consecutive hidden characters each get their own marker`() {
        val parts = VisibleText.parts("‪‬")
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.isMarker })
        assertFalse(VisibleText.isHidden('a'.code))
        assertFalse(VisibleText.isHidden('\n'.code))
        assertFalse(VisibleText.isHidden('\t'.code))
        assertTrue(VisibleText.isHidden(0x202E))
    }

    @Test
    fun `single line turns every kind of newline into a visible mark`() {
        assertEquals("riga 1⏎riga 2⏎riga 3⏎fine", VisibleText.singleLine("riga 1\nriga 2\r\nriga 3\rfine"))
        assertEquals("senza a capo", VisibleText.singleLine("senza a capo"))
    }
}
