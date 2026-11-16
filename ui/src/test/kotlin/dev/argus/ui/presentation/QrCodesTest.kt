package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodesTest {
    @Test
    fun `a short rule text becomes a square QR code with its finder patterns`() {
        val matrix = assertNotNull(QrCodes.encode("""{"format":"argus-rules","version":1,"rules":[]}"""))

        assertTrue(matrix.size >= 21 && (matrix.size - 17) % 4 == 0, "lato ${matrix.size}")
        // Angolo in alto a sinistra di un pattern di ricerca: scuro; il separatore subito fuori: chiaro.
        assertTrue(matrix.isDark(0, 0))
        assertTrue(matrix.isDark(6, 6))
        assertTrue(!matrix.isDark(7, 7))
        assertTrue(matrix.isDark(matrix.size - 1, 0))
        assertTrue(matrix.isDark(0, matrix.size - 1))
    }

    @Test
    fun `text in Italian is encoded as UTF-8`() {
        assertNotNull(QrCodes.encode("Notte tranquilla: «Non disturbare» alle 23:00 — più è"))
    }

    @Test
    fun `a text that does not fit at level M gives no code`() {
        // Oltre la capacità binaria della versione 40 a livello M (2331 byte).
        assertNull(QrCodes.encode("x".repeat(2_400)))
        assertNull(QrCodes.encode(""))
    }

    @Test
    fun `the same text gives the same code`() {
        val a = assertNotNull(QrCodes.encode("argus"))
        val b = assertNotNull(QrCodes.encode("argus"))
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a.isDark(x, y), b.isDark(x, y))
    }
}
