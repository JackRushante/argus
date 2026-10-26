package dev.argus.ui.presentation

/** Controlli puramente informativi sul campo «modello» della configurazione provider. */
object ModelFieldHints {
    private const val MISTYPED_PREFIX = "@presets/"

    /**
     * true se [model] è scritto `@presets/…` (con la s) invece di `@preset/<nome>`: è l'errore tipico
     * dei preset OpenRouter. Serve solo ad avvisare: il valore non si corregge da solo.
     */
    fun isMistypedPreset(model: String?): Boolean =
        model?.trim()?.startsWith(MISTYPED_PREFIX, ignoreCase = true) == true
}
