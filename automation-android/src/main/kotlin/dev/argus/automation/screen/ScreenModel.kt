package dev.argus.automation.screen

/**
 * Un nodo dell'albero dello schermo, come lo vede l'esecutore delle macro (0.5.1). È un'astrazione
 * sottile su `AccessibilityNodeInfo`, così l'esecutore e il confronto dei criteri si provano su
 * alberi finti. Il contenuto dei nodi (testi, descrizioni) vive solo in memoria per la durata di
 * un passo: non viene mai scritto nel Log, nell'audit né in un file.
 */
interface ScreenNode {
    val text: String?
    val contentDescription: String?

    /** `viewIdResourceName` (es. `com.example:id/send`). */
    val viewId: String?
    val isClickable: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isVisibleToUser: Boolean
    val children: List<ScreenNode>
    val parent: ScreenNode?

    fun click(): Boolean

    /** Sostituisce il contenuto del campo (`ACTION_SET_TEXT`). */
    fun setText(text: String): Boolean

    fun scroll(forward: Boolean): Boolean
}

/**
 * La finestra attiva e i gesti globali. Implementata dal servizio di Accessibilità; un'implementazione
 * finta serve ai test.
 */
interface ScreenSource {
    /** Il servizio di Accessibilità di Argus è collegato e può leggere lo schermo. */
    val available: Boolean

    /** Package della finestra attiva, o null se non si legge. */
    fun activePackage(): String?

    fun root(): ScreenNode?

    /** Il campo di testo che ha il focus di input, se c'è. */
    fun focusedInput(): ScreenNode?

    fun back(): Boolean

    fun home(): Boolean
}

/**
 * Cosa cercare sullo schermo. Tutti i criteri presenti devono valere insieme. [textAlsoDescription]
 * fa valere il [text] anche contro la descrizione del contenuto (controlli e attese su un'etichetta
 * che l'app mostra solo come descrizione).
 */
data class ScreenQuery(
    val text: String? = null,
    val id: String? = null,
    val desc: String? = null,
    val textAlsoDescription: Boolean = false,
)

/** Esecuzione di una macro: il tipo di esito che l'esecutore restituisce al chiamante. */
internal object ScreenCodes {
    const val SERVICE_UNAVAILABLE = "ui_service_unavailable"
    const val BUSY = "ui_busy"
    const val FORBIDDEN_SCREEN = "ui_forbidden_screen"
    const val OPEN_FAILED = "ui_open_failed"
    const val ELEMENT_NOT_FOUND = "ui_element_not_found"
    const val CHECK_FAILED = "ui_check_failed"
    const val TIMEOUT = "ui_timeout"
    const val MACRO_TIMEOUT = "ui_macro_timeout"
    const val STOPPED = "ui_stopped"
    const val CLICK_FAILED = "ui_click_failed"
    const val INPUT_FAILED = "ui_input_failed"
    const val SCROLL_FAILED = "ui_scroll_failed"
    const val ACTION_FAILED = "ui_action_failed"
    const val NOTIFICATION_UNAVAILABLE = "ui_notification_unavailable"
}
