package dev.argus.device

/**
 * Stato e attivazione del servizio di Accessibilità di Argus (0.5.1). Logica pura sulle due
 * impostazioni di sistema coinvolte, `enabled_accessibility_services` (elenco `pacchetto/classe`
 * separato da `:`) e `accessibility_enabled` (interruttore generale), così si prova senza Android.
 *
 * Regola d'oro: Argus AGGIUNGE il proprio servizio all'elenco, non lo sovrascrive mai. Chi ha già
 * altri servizi di Accessibilità (un lettore di schermo, per esempio) non deve perderli.
 */
object AccessibilityEnablement {
    /** Classe del servizio, dichiarata nel manifest di `automation-android`. */
    const val SERVICE_CLASS = "dev.argus.automation.screen.ArgusAccessibilityService"

    const val SETTING_SERVICES = "enabled_accessibility_services"
    const val SETTING_ENABLED = "accessibility_enabled"

    /** Il nome completo del componente, come lo scrive la schermata di sistema. */
    fun component(packageName: String): String = "$packageName/$SERVICE_CLASS"

    /** Voci dell'elenco, nella forma lunga (`pacchetto/.Classe` diventa `pacchetto/pacchetto.Classe`). */
    fun entries(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return emptyList()
        return raw.split(':').map(String::trim).filter(String::isNotEmpty).map { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) {
                entry
            } else {
                val pkg = entry.substring(0, slash)
                val cls = entry.substring(slash + 1)
                if (cls.startsWith(".")) "$pkg/$pkg$cls" else entry
            }
        }
    }

    /** Il servizio di Argus è nell'elenco E l'interruttore generale è acceso. */
    fun isEnabled(servicesValue: String?, accessibilityEnabled: Int?, packageName: String): Boolean =
        accessibilityEnabled == 1 && component(packageName) in entries(servicesValue)

    /**
     * Il nuovo valore dell'elenco con il servizio di Argus aggiunto in coda. Se c'è già, l'elenco
     * resta identico; le altre voci restano, nel loro ordine e nella loro forma.
     */
    fun withService(servicesValue: String?, packageName: String): String {
        val component = component(packageName)
        val current = servicesValue?.trim().orEmpty().takeUnless { it == "null" }.orEmpty().trimEnd(':')
        if (component in entries(current)) return current
        return if (current.isEmpty()) component else "$current:$component"
    }

    /**
     * Un elenco che si può passare a `settings put` come unico argomento: solo caratteri di nomi di
     * componente e il separatore. Qualsiasi altro carattere (a capo, spazi, virgolette) lo rifiuta.
     */
    fun isSafeValue(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_VALUE_CHARS &&
        value.all { it.isLetterOrDigit() || it in "_./:$-" }

    private const val MAX_VALUE_CHARS = 2_048
}
