@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Direzione di [UiStep.Scroll]. Enum CHIUSO; wire maiuscolo, lettura tollerante al minuscolo. */
@Serializable(with = ScrollDirectionSerializer::class)
enum class ScrollDirection { UP, DOWN }

object ScrollDirectionSerializer : CaseInsensitiveEnumSerializer<ScrollDirection>(
    "dev.argus.engine.model.ScrollDirection",
    ScrollDirection.entries.toTypedArray(),
)

/**
 * Passo di una macro [Action.UiSteps] (0.5.1, computer-use via Accessibilità). Vocabolario CHIUSO,
 * discriminato da `op` (non da `type`, che è il discriminatore delle azioni). Nessun passo conosce
 * coordinate: si indica sempre COSA toccare (testo, id risorsa, descrizione), mai DOVE.
 */
@Serializable
@JsonClassDiscriminator("op")
sealed interface UiStep {
    /** Aspetta che compaia un elemento con questo testo (esatto o contenuto) e/o id risorsa. */
    @Serializable @SerialName("wait_for")
    data class WaitFor(
        val text: String? = null,
        val id: String? = null,
        val timeoutMs: Int = UiStepsPolicy.DEFAULT_WAIT_MS,
    ) : UiStep

    /** Clic sul primo elemento che corrisponde (o sull'`index`-esimo, da 0). Almeno un criterio. */
    @Serializable @SerialName("click")
    data class Click(
        val text: String? = null,
        val id: String? = null,
        val desc: String? = null,
        val index: Int? = null,
    ) : UiStep

    /** Scrive nel campo che ha il focus. Unico passo che ammette `${var}` (sink locale). */
    @Serializable @SerialName("type")
    data class Type(val text: String) : UiStep

    @Serializable @SerialName("scroll")
    data class Scroll(val dir: ScrollDirection) : UiStep

    @Serializable @SerialName("back")
    data object Back : UiStep

    @Serializable @SerialName("home")
    data object Home : UiStep

    /** Se il controllo fallisce la macro si ferma con `ui_check_failed`. */
    @Serializable @SerialName("check")
    data class Check(val text: String, val present: Boolean) : UiStep
}

/**
 * Limiti e regole condivise da validatore, interpolatore ed esecutore della macro `ui_steps`. Tutto
 * qui è chiuso: nessun valore arriva dall'AI senza passare da questi bound.
 */
object UiStepsPolicy {
    const val MAX_STEPS = 20

    /** `wait_for.timeoutMs`: da 100 ms a 15 s; se omesso 5 s. */
    const val MIN_WAIT_MS = 100
    const val MAX_WAIT_MS = 15_000
    const val DEFAULT_WAIT_MS = 5_000

    /** Tetto dell'intera macro, apertura dell'app compresa. L'esecutore la ferma a 60 s. */
    const val MAX_MACRO_MS = 60_000L

    /** Tempo che l'esecutore concede a un clic o a un controllo per trovare l'elemento (la UI sta ancora cambiando). */
    const val FIND_TIMEOUT_MS = 3_000L

    /** Pausa dopo un'azione sull'interfaccia, perché la schermata si assesti prima del passo successivo. */
    const val SETTLE_MS = 400L

    /** Attesa massima perché l'app indicata da `pkg` passi in primo piano. */
    const val OPEN_TIMEOUT_MS = 10_000L

    const val MAX_SELECTOR_CHARS = 200
    const val MAX_TYPE_CHARS = 1_000
    const val MAX_INDEX = 50

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * Package su cui una macro non può mai agire, né aprirli né trovarseli in primo piano a metà
     * macro: Argus stesso, le Impostazioni (le schermate di sicurezza e privacy non si distinguono con
     * affidabilità dal package, quindi tutte), l'installatore di pacchetti e i dialoghi dei permessi.
     * Una macro non può concedersi permessi, né confermare un'installazione, né toccare Argus.
     */
    val FORBIDDEN_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private const val ARGUS_PACKAGE = "dev.argus"

    fun isForbiddenPackage(pkg: String): Boolean =
        pkg == ARGUS_PACKAGE || pkg.startsWith("$ARGUS_PACKAGE.") || pkg in FORBIDDEN_PACKAGES

    fun isPackageName(pkg: String): Boolean = pkg.length <= 255 && PACKAGE_NAME.matches(pkg)

    /** Selettore di un passo: almeno uno dei criteri, e tutti non vuoti e limitati. */
    fun selectorShapeValid(vararg criteria: String?): Boolean {
        val present = criteria.filterNotNull()
        return present.isNotEmpty() && present.all {
            it.isNotBlank() && it.length <= MAX_SELECTOR_CHARS && it.none(Char::isISOControl)
        }
    }

    /**
     * Stima prudente del tempo massimo: l'apertura dell'app, poi ogni passo col suo tetto. È il numero
     * che il validatore somma al budget della regola; l'esecutore ferma comunque la macro a [MAX_MACRO_MS].
     */
    fun budgetMs(action: Action.UiSteps): Long {
        var total = if (action.pkg != null) OPEN_TIMEOUT_MS else 0L
        for (step in action.steps) {
            total += when (step) {
                is UiStep.WaitFor -> step.timeoutMs.coerceIn(0, MAX_WAIT_MS).toLong()
                is UiStep.Click, is UiStep.Check -> FIND_TIMEOUT_MS + SETTLE_MS
                is UiStep.Type, is UiStep.Scroll, UiStep.Back, UiStep.Home -> SETTLE_MS
            }
        }
        return total.coerceAtMost(MAX_MACRO_MS)
    }

    /** Vero se ogni campo di testo del passo che non sia `type.text` è privo di segnaposto `${…}`. */
    fun literalFieldsOnly(step: UiStep): Boolean = when (step) {
        is UiStep.WaitFor -> listOfNotNull(step.text, step.id).none(InterpolationPolicy::containsInterpolation)
        is UiStep.Click ->
            listOfNotNull(step.text, step.id, step.desc).none(InterpolationPolicy::containsInterpolation)
        is UiStep.Check -> !InterpolationPolicy.containsInterpolation(step.text)
        is UiStep.Type, is UiStep.Scroll, UiStep.Back, UiStep.Home -> true
    }

    /**
     * Forma di un passo già risolto (dopo l'interpolazione) o letterale: limiti rispettati, criteri
     * presenti. Usata dal validatore (con i `${…}` ancora nel testo di `type`) e, dopo la risoluzione,
     * dall'interpolatore. Ritorna il codice del problema o null.
     */
    fun stepProblem(step: UiStep): String? = when (step) {
        is UiStep.WaitFor -> when {
            !selectorShapeValid(step.text, step.id) -> "selector"
            step.timeoutMs !in MIN_WAIT_MS..MAX_WAIT_MS -> "timeout"
            else -> null
        }
        is UiStep.Click -> when {
            !selectorShapeValid(step.text, step.id, step.desc) -> "selector"
            step.index != null && step.index !in 0..MAX_INDEX -> "index"
            else -> null
        }
        is UiStep.Type -> when {
            step.text.isEmpty() || step.text.length > MAX_TYPE_CHARS -> "text"
            else -> null
        }
        is UiStep.Check -> if (selectorShapeValid(step.text)) null else "selector"
        is UiStep.Scroll, UiStep.Back, UiStep.Home -> null
    }

    /**
     * Forma dell'azione intera, a letterali o già risolta: da 1 a [MAX_STEPS] passi, `pkg` assente o un
     * package valido e non vietato, ogni passo entro i suoi limiti, nessun segnaposto fuori da
     * `type.text`. Il validatore la applica alla bozza, l'interpolatore alla macro risolta.
     */
    fun isResolvedShapeValid(action: Action.UiSteps): Boolean =
        action.steps.size in 1..MAX_STEPS &&
            (action.pkg == null || (isPackageName(action.pkg) && !isForbiddenPackage(action.pkg))) &&
            action.steps.all { stepProblem(it) == null && literalFieldsOnly(it) }
}
