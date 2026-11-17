package dev.argus.ui.model

// --- 0.8 · Sistema › Modalità (tavola «Modalità», approvata) ---

/**
 * Stato della schermata Modalità: la modalità attiva con i pulsanti per cambiarla, l'ultimo cambio, l'elenco
 * delle modalità con quante regole le usano come condizione e le variabili salvate. I testi dinamici (ultimo
 * cambio, dettaglio di una variabile) arrivano già scritti nella lingua dell'app dal ViewModel.
 */
data class ModesState(
    val loading: Boolean = true,
    val modes: List<ModeRowUi> = emptyList(),
    /** Modalità attiva; null = nessuna. */
    val activeMode: String? = null,
    /** «Cambiata oggi alle 18:42 dalla regola «Arrivo a casa».»; null se non è mai cambiata. */
    val lastChange: String? = null,
    val values: List<StoredValueRowUi> = emptyList(),
    /** false con 12 modalità: «Aggiungi modalità» resta visibile, disabilitato col motivo. */
    val canAddMode: Boolean = true,
)

data class ModeRowUi(
    val name: String,
    /** Regole che la usano come condizione (allo scatto o dentro un se/finché). */
    val conditionRules: Int,
    /** Regole che la nominano in qualunque punto: rinominarla o eliminarla le manda in «da rivedere». */
    val usedByRules: Int,
)

enum class StoredValueTypeUi { TEXT, NUMBER, BOOLEAN }

data class StoredValueRowUi(
    val key: String,
    val type: StoredValueTypeUi,
    /** Il valore da mostrare; vuoto se [masked]. */
    val value: String,
    /** Valore SECRET: si mostra mascherato e il campo di correzione parte vuoto. */
    val masked: Boolean,
    /** «Numero · aggiornata oggi 08:10 da «Conta i caffè»». */
    val detail: String,
)

interface ModesCallbacks {
    fun onBack()
    fun onSwitchMode(name: String)
    fun onAddMode(name: String)
    fun onRenameMode(from: String, to: String)
    fun onDeleteMode(name: String)
    fun onEditValue(key: String, value: String)
    fun onDeleteValue(key: String)
}
