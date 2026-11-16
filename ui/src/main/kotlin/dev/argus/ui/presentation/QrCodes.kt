package dev.argus.ui.presentation

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** Moduli di un codice QR, senza margine: `true` = modulo scuro. [size] moduli per lato. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    init {
        require(dark.size == size * size) { "matrice QR non quadrata" }
    }

    fun isDark(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * Codici QR di una regola condivisa (0.7.2), con l'encoder di ZXing core (solo codifica). Correzione degli
 * errori di livello M; testo in UTF-8. Se il testo non entra in un codice QR (versione 40 a livello M, circa
 * 2,3 KB) non c'è codice: la UI lo dice con una nota.
 */
object QrCodes {
    fun encode(text: String): QrMatrix? {
        if (text.isEmpty()) return null
        return try {
            val code = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
            val matrix = code.matrix ?: return null
            val size = matrix.width
            if (size != matrix.height) return null
            QrMatrix(size, BooleanArray(size * size) { index -> matrix.get(index % size, index / size).toInt() == 1 })
        } catch (_: WriterException) {
            null
        }
    }
}
