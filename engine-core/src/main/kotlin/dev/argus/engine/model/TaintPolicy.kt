package dev.argus.engine.model

/**
 * Punto di policy centralizzato per l'anti-injection basata sull'asse INTEGRITY (taint).
 *
 * Posture corrente: **AGGRESSIVO**. Un valore TAINTED (trigger_payload o output catturato)
 * può riempire anche i campi di [InterpolationPolicy.FieldClass.AUTHORITY] — comando shell,
 * url, package, input text, ringer mode, write setting… Riportare la posture a "chirurgico"
 * (tainted ammesso solo nei SINK) è il cambio di un **singolo flag** qui: nessun altro sito
 * conosce la decisione. I due enforcement (statico in [dev.argus.engine.safety.DraftValidator],
 * dinamico in [dev.argus.engine.runtime.TaintAwareInterpolator]) si limitano a interrogare
 * questa policy.
 *
 * Restano INVARIATI e **indipendenti** da questa policy — sono i denti che teniamo:
 *  - lo shell-gating sui mittenti ([dev.argus.engine.safety.StaticShellSafety]): chi può
 *    innescare una shell approvata resta ristretto ai contatti WhatsApp 1:1 whitelistati;
 *  - il floor di confidenzialità SECRET sulle capture (asse CONFIDENTIALITY): l'output
 *    shell/model resta TAINTED + SECRET;
 *  - la separazione SYSTEM/DATA dei dati runtime verso il Brain (marker runtime_data).
 *
 * Rilassiamo il **blocco**, non le **label**: un tainted che entra in AUTHORITY propaga
 * comunque la sua integrità TAINTED al risultato ([joinIntegrity] resta monotono).
 */
object TaintPolicy {
    /**
     * true quando i valori TAINTED sono ammessi nei campi AUTHORITY (posture AGGRESSIVO).
     * Portare a false per tornare alla posture "chirurgico" (tainted solo nei SINK).
     */
    private const val AGGRESSIVE_ALLOW_TAINTED_IN_AUTHORITY = true

    /**
     * @return true se un valore TAINTED può essere interpolato in un campo di autorità.
     * I parametri restano volutamente assenti per ora: se in futuro servisse una granularità
     * per-azione o per-campo, si aggiungono qui senza toccare i call site.
     */
    fun allowTaintedInAuthority(): Boolean = AGGRESSIVE_ALLOW_TAINTED_IN_AUTHORITY
}
