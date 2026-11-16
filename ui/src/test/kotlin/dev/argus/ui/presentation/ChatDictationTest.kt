package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatDictationTest {
    @Test fun `il primo candidato vince, senza spazi ai bordi`() {
        assertEquals("accendi il wifi", ChatDictation.bestResult(listOf("  accendi il wifi ", "accendi il WiFi")))
    }

    @Test fun `nessun candidato utile vale null`() {
        assertNull(ChatDictation.bestResult(null))
        assertNull(ChatDictation.bestResult(emptyList()))
        assertNull(ChatDictation.bestResult(listOf("   ")))
        assertNull(ChatDictation.bestResult(listOf(null)))
    }

    @Test fun `campo vuoto prende il testo dettato`() {
        assertEquals("spegni il bluetooth", ChatDictation.merge("", "spegni il bluetooth"))
        assertEquals("spegni il bluetooth", ChatDictation.merge("  ", " spegni il bluetooth"))
    }

    @Test fun `il testo dettato si accoda a quello scritto`() {
        assertEquals("Ogni sera alle 22 metti il silenzioso", ChatDictation.merge("Ogni sera alle 22", "metti il silenzioso"))
        assertEquals("Ogni sera alle 22 metti il silenzioso", ChatDictation.merge("Ogni sera alle 22 ", "metti il silenzioso"))
    }

    @Test fun `niente riconosciuto lascia il campo com'era`() {
        assertEquals("bozza ", ChatDictation.merge("bozza ", null))
        assertEquals("bozza ", ChatDictation.merge("bozza ", "  "))
    }
}
