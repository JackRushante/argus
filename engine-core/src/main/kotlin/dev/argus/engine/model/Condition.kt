@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.engine.model
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class CmpOp { EQ, NEQ, GT, LT, CONTAINS, IS_EVEN, IS_ODD }

@Serializable
sealed interface Condition {
    @Serializable @SerialName("time_window")
    data class TimeWindow(val startLocal: String, val endLocal: String, val tz: String) : Condition

    @Serializable @SerialName("state_equals")
    data class StateEquals(val key: String, val op: CmpOp, val value: String) : Condition

    @Serializable @SerialName("state_compare")
    data class StateCompare(
        val query: StateQuery,
        val valueType: StateValueType,
        val op: CmpOp,
        val expected: String,
        /** Fingerprinta i limiti e la semantica del reader, non il sample letto al probe. */
        val policyVersion: Int,
    ) : Condition {
        /** Ergonomia locale; sul wire policyVersion resta obbligatorio e strict. */
        constructor(
            query: StateQuery,
            valueType: StateValueType,
            op: CmpOp,
            expected: String,
        ) : this(query, valueType, op, expected, StateQueryPolicy.VERSION)
    }

    @Serializable @SerialName("app_in_foreground")
    data class AppInForeground(val pkg: String) : Condition

    @Serializable @SerialName("location_in")
    data class LocationIn(val lat: Double, val lng: Double, val radiusM: Double) : Condition

    @Serializable @SerialName("and")
    data class And(val all: List<Condition>) : Condition

    @Serializable @SerialName("or")
    data class Or(val any: List<Condition>) : Condition

    @Serializable @SerialName("not")
    data class Not(val cond: Condition) : Condition

    /** Costante chiusa per il control-flow, ad esempio while(true) bounded. */
    @Serializable @SerialName("boolean_literal")
    data class BooleanLiteral(val value: Boolean) : Condition

    /**
     * Confronto su VARIABILI di programma (P4 §2.3): valutato dall'interprete deterministico su
     * uno scope per-esecuzione. [varName] e l'eventuale [expectedVar] sono NOMI di variabili
     * dichiarate; [expected] è il letterale di confronto quando non si confronta un'altra var.
     * Esattamente uno fra [expected] ed [expectedVar] deve essere presente (validator).
     * Ammesso solo dentro il control-flow (if/while): il [dev.argus.engine.safety.DraftValidator]
     * rifiuta un VarCompare fra le condizioni trigger-time (le var non esistono ancora lì).
     */
    @Serializable @SerialName("var_compare")
    data class VarCompare(
        val varName: String,
        val op: CmpOp,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val expected: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val expectedVar: String? = null,
    ) : Condition
}

/**
 * Condizione del control-flow (if/while). È esattamente [Condition] — inclusa [Condition.VarCompare]
 * — ma il nome documenta che qui vivono anche i confronti su variabili di programma (P4 §2.3).
 */
typealias FlowCondition = Condition

fun Condition.stateComparisons(): List<Condition.StateCompare> = when (this) {
    is Condition.StateCompare -> listOf(this)
    is Condition.And -> all.flatMap(Condition::stateComparisons)
    is Condition.Or -> any.flatMap(Condition::stateComparisons)
    is Condition.Not -> cond.stateComparisons()
    is Condition.BooleanLiteral,
    is Condition.TimeWindow,
    is Condition.StateEquals,
    is Condition.AppInForeground,
    is Condition.LocationIn,
    is Condition.VarCompare,
    -> emptyList()
}
