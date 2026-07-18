package dev.argus.ui.presentation

/**
 * Lingua di rendering per il testo utente prodotto FUORI da Compose (mapper JVM-puri, ViewModel,
 * notifiche). Le risorse Android non sono disponibili nei test JVM: i mapper usano tabelle di
 * template per lingua e ricevono la lingua come ultimo parametro opzionale — produzione =
 * automatico dal locale di sistema, test = esplicito e deterministico.
 *
 * Inglese = default; italiano quando il sistema è in italiano. Nessun toggle in-app.
 */
enum class RenderLanguage {
    EN,
    IT,
    ;

    /**
     * Riga della tabella di template: EN e IT restano affiancati al call-site, così una stringa
     * nuova non può dimenticare una delle due lingue.
     */
    fun pick(en: String, it: String): String = if (this == IT) it else en

    companion object {
        /** Automatico dal locale di sistema: "it" → IT, tutto il resto → EN (default). */
        fun system(): RenderLanguage =
            if (java.util.Locale.getDefault().language == "it") IT else EN
    }
}
