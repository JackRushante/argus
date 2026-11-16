package dev.argus.engine.model

import kotlinx.serialization.Serializable

/**
 * Un extra di [Action.SendIntent] (0.8 scaffold). [name] è la chiave dell'extra, [type] il tipo in cui
 * [value] si converte allo scatto. [name] è letterale; [value] è letterale per i tipi numerici e
 * booleani, ed è un template AUTHORITY (`${'$'}{var}` ammesso secondo [TaintPolicy]) solo per STRING.
 */
@Serializable
data class IntentExtra(
    val name: String,
    val type: IntentExtraType,
    val value: String,
)

/**
 * Un'intestazione o un parametro di query di [Action.HttpRequest] il cui valore viene dalla cassaforte
 * dei segreti del telefono (0.8 scaffold). [name] è il nome dell'intestazione o del parametro, [secret]
 * il nome del segreto nella cassaforte, [prefix] il testo letterale messo davanti al valore (per esempio
 * `Bearer `). Il valore del segreto non entra mai in una variabile, nel journal o nella regola: la regola
 * porta solo i nomi.
 */
@Serializable
data class SecretRef(
    val name: String,
    val secret: String,
    val prefix: String = "",
)

/**
 * Forma vincolata dell'uscita di [Action.InvokeLlm] (0.8 scaffold). [kind] CHOICE = una delle [choices]
 * letterali; NUMBER = un numero intero, fra [min] e [max] se presenti; BOOLEAN = `true` o `false`.
 * Un'uscita che non rispetta la forma fallisce chiusa invece di diventare testo libero.
 */
@Serializable
data class OutputSpec(
    val kind: OutputKind,
    val choices: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
)
