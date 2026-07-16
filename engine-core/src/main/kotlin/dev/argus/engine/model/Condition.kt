package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class CmpOp { EQ, NEQ, GT, LT, CONTAINS }

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
}

fun Condition.stateComparisons(): List<Condition.StateCompare> = when (this) {
    is Condition.StateCompare -> listOf(this)
    is Condition.And -> all.flatMap(Condition::stateComparisons)
    is Condition.Or -> any.flatMap(Condition::stateComparisons)
    is Condition.Not -> cond.stateComparisons()
    is Condition.TimeWindow,
    is Condition.StateEquals,
    is Condition.AppInForeground,
    is Condition.LocationIn,
    -> emptyList()
}
