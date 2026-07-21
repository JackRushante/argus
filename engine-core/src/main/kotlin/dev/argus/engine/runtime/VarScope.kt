package dev.argus.engine.runtime

import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarValue

/** Scope mutabile confinato a una sola esecuzione P4. Non è serializzato né condiviso. */
class VarScope(initial: Map<String, VarValue> = emptyMap()) {
    private val values = LinkedHashMap<String, VarValue>()

    init {
        initial.forEach(::assign)
    }

    operator fun get(name: String): VarValue? = values[name]

    /**
     * Assegna o aggiorna un valore. L'update è necessario per un captureAs dentro un while: la
     * dichiarazione è unica nel programma, ma la stessa azione può produrre un valore a ogni giro.
     */
    fun assign(name: String, value: VarValue) {
        require(VarBinding.NAME_REGEX.matches(name)) { "Nome variabile runtime non valido" }
        require(value.text.length <= MAX_RUNTIME_VALUE_CHARS) { "Valore variabile runtime troppo lungo" }
        values[name] = value
    }

    /** Copia immutabile per test/decisioni; contiene valori sensibili e non va mai loggata. */
    fun snapshot(): Map<String, VarValue> = values.toMap()

    companion object {
        /** Bound difensivo allineato all'ordine di grandezza dell'output reader/shell consentito. */
        const val MAX_RUNTIME_VALUE_CHARS = 64 * 1_024
    }
}
