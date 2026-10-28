package dev.argus.ui.presentation

import dev.argus.engine.model.AppEventKind
import dev.argus.engine.model.Trigger

/**
 * Nome leggibile di un'app installata, dato dal telefono («YouTube» per `com.google.android.youtube`).
 * La UI è logica JVM pura e non vede il `PackageManager`: l'app installa qui all'avvio un risolutore
 * ([install]) e il render lo consulta SOLO per aggiungere il nome accanto al package. Senza risolutore,
 * o se il telefono non conosce l'app, il render mostra il package da solo, come prima.
 */
object AppLabels {
    @Volatile
    private var resolver: ((String) -> String?)? = null

    /** Installa il risolutore del telefono; `null` lo toglie (usato dai test). */
    fun install(resolver: ((String) -> String?)?) {
        this.resolver = resolver
    }

    /** Il nome dell'app, o `null` se non è noto o coincide col package. Non lancia mai. */
    fun labelOf(pkg: String): String? {
        val lookup = resolver ?: return null
        val label = runCatching { lookup(pkg) }.getOrNull()?.trim()
        return label?.takeIf { it.isNotEmpty() && it != pkg }
    }
}

/**
 * Testo di approvazione del trigger «app aperta/chiusa» (0.5.1). Generato dai tipi (invariante 4), EN/IT.
 * Il package compare SEMPRE per intero, anche quando c'è il nome leggibile: è il criterio reale del
 * confronto, e il nome è solo un aiuto per chi approva. «Chiusa» vuol dire che l'app lascia il primo
 * piano (passa a un'altra app o alla Home): la riga lo dice, perché l'app può continuare a girare.
 */
internal object AppEventTriggerRender {
    fun iconKey(t: Trigger): String = "app_event"

    fun line(t: Trigger, l: RenderLanguage): String {
        val trigger = t as? Trigger.AppEvent ?: return ""
        val app = appName(trigger.pkg)
        val prefix = RuleRenderMapper.whenPrefix(l)
        return prefix + when (trigger.event) {
            AppEventKind.OPENED -> l.pick(
                "the app $app is opened (it comes to the foreground)",
                "l'app $app viene aperta (passa in primo piano)",
            )
            AppEventKind.CLOSED -> l.pick(
                "the app $app is closed (it leaves the foreground)",
                "l'app $app viene chiusa (lascia il primo piano)",
            )
        }
    }

    private fun appName(pkg: String): String = AppLabels.labelOf(pkg)?.let { "$it ($pkg)" } ?: pkg
}
