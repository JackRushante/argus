@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.engine.model
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class CmpOp { EQ, NEQ, GT, LT, CONTAINS, IS_EVEN, IS_ODD }

/** 0.8 scaffold: giorno della settimana di [Condition.Weekday]. Vocabolario chiuso, da lunedì. */
@Serializable(with = DaySerializer::class)
enum class Day { MON, TUE, WED, THU, FRI, SAT, SUN }

object DaySerializer : CaseInsensitiveEnumSerializer<Day>("dev.argus.engine.model.Day", Day.entries.toTypedArray())

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

    // ---------------------------------------------------------------------------------------------
    // 0.8 scaffold: condizioni valutabili al momento del trigger, SPENTE finché il loro [FeatureGate]
    // è off (il validator le rifiuta con `not_available_yet`; il ConditionEvaluator dà UNKNOWN).
    // ---------------------------------------------------------------------------------------------

    /** Oggi è uno dei [days] nel fuso [tz] (id IANA) ([FeatureGate.CONDITION_WEEKDAY]). */
    @Serializable @SerialName("weekday")
    data class Weekday(val days: Set<Day>, val tz: String) : Condition

    /** Il telefono è connesso al Wi-Fi [ssid]; null = a una rete qualunque ([FeatureGate.CONDITION_WIFI_CONNECTED]). */
    @Serializable @SerialName("wifi_connected")
    data class WifiConnected(val ssid: String? = null) : Condition

    /**
     * Un dispositivo Bluetooth è connesso: [device] è un nome o un indirizzo MAC; null = uno qualunque
     * ([FeatureGate.CONDITION_BLUETOOTH_CONNECTED]).
     */
    @Serializable @SerialName("bluetooth_connected")
    data class BluetoothConnected(val device: String? = null) : Condition

    /** Cuffie o auricolari collegati, del tipo [kind] ([FeatureGate.CONDITION_HEADSET_CONNECTED]). */
    @Serializable @SerialName("headset_connected")
    data class HeadsetConnected(val kind: AudioOutputKind = AudioOutputKind.ANY) : Condition

    /**
     * Un evento del calendario è in corso adesso ([FeatureGate.CONDITION_CALENDAR_BUSY]); [titleMatch]
     * filtra sul titolo (contiene, senza maiuscole), [calendar] sul nome del calendario; null = qualunque.
     */
    @Serializable @SerialName("calendar_busy")
    data class CalendarBusy(
        val titleMatch: String? = null,
        val calendar: String? = null,
    ) : Condition

    /**
     * Confronto sul valore conservato [key] ([FeatureGate.CONDITION_STORED_COMPARE]), letto come
     * [valueType] e confrontato con il letterale [expected]. Un valore assente è UNKNOWN.
     */
    @Serializable @SerialName("stored_compare")
    data class StoredCompare(
        val key: String,
        val valueType: StateValueType,
        val op: CmpOp,
        val expected: String,
    ) : Condition

    /** La modalità utente corrente è [mode] ([FeatureGate.CONDITION_USER_MODE_IS]). */
    @Serializable @SerialName("user_mode_is")
    data class UserModeIs(val mode: String) : Condition
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
    is Condition.Weekday -> emptyList()
    is Condition.WifiConnected -> emptyList()
    is Condition.BluetoothConnected -> emptyList()
    is Condition.HeadsetConnected -> emptyList()
    is Condition.CalendarBusy -> emptyList()
    is Condition.StoredCompare -> emptyList()
    is Condition.UserModeIs -> emptyList()
}
