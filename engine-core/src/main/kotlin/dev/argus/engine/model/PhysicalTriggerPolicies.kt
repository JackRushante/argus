package dev.argus.engine.model

/**
 * Identificativo di un tag NFC ([Trigger.NfcTag]): i byte dell'UID in esadecimale maiuscolo separati da
 * due punti (`04:A2:19:B3`). È l'unica forma che l'app scrive (lettura all'arm e scansioni); il confronto
 * accetta anche la forma compatta (`04a219b3`), perché conta solo la sequenza di byte.
 */
object NfcTagPolicy {
    /** Gli UID reali hanno 4, 7 o 10 byte (ISO 14443) o 8 (FeliCa, ISO 15693): margine abbondante. */
    const val MAX_BYTES = 32

    private val COLON_FORM = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2})*$")
    private val COMPACT_FORM = Regex("^([0-9A-Fa-f]{2})+$")

    /** La forma canonica dei byte letti dal telefono; `null` se vuoti o troppo lunghi. */
    fun format(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** La forma canonica di un id scritto (con o senza due punti); `null` se non è un id valido. */
    fun normalize(value: String): String? {
        val hex = when {
            COLON_FORM.matches(value) -> value.replace(":", "")
            COMPACT_FORM.matches(value) -> value
            else -> return null
        }
        val bytes = hex.length / 2
        if (bytes !in 1..MAX_BYTES) return null
        return hex.uppercase().chunked(2).joinToString(":")
    }

    /** Stesso tag: stessa sequenza di byte. Un id vuoto o malformato non combacia mai. */
    fun sameTag(a: String, b: String): Boolean {
        val left = normalize(a) ?: return false
        return left == normalize(b)
    }
}

/** Limiti del trigger `next_alarm`: quanti minuti prima della prossima sveglia. */
object NextAlarmTriggerPolicy {
    const val MIN_OFFSET_MINUTES = 0
    const val MAX_OFFSET_MINUTES = 180

    fun validOffset(minutes: Int): Boolean = minutes in MIN_OFFSET_MINUTES..MAX_OFFSET_MINUTES
}

/** Limiti del trigger `app_usage`: soglia di tempo in primo piano di oggi, in minuti. */
object AppUsageTriggerPolicy {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 24 * 60

    fun validMinutes(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES
}

/**
 * Geofence `DWELL`: dopo un ingresso verificato si ricontrolla dopo [Trigger.Geofence.loiteringDelayMs] e si
 * scatta solo se il telefono è ancora dentro. ENTER ed EXIT non hanno attesa (`loiteringDelayMs` = 0).
 */
object GeofenceDwellPolicy {
    const val MIN_DELAY_MS = 60_000L
    const val MAX_DELAY_MS = 120 * 60_000L

    fun validDelay(delayMs: Long): Boolean = delayMs in MIN_DELAY_MS..MAX_DELAY_MS
}
