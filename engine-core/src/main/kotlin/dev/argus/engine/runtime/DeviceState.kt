// DeviceState.kt
package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.Condition
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateKeys

data class GeoPoint(val lat: Double, val lng: Double)

data class DeviceState(
    val values: Map<String, String> = emptyMap(),   // chiavi da StateKeys, es. "ringer" -> "normal"
    val foregroundApp: String? = null,
    val location: GeoPoint? = null,
    /** Valori parametrici indicizzati solo dall'ID opaco della query approvata. */
    val queryValues: Map<String, String> = emptyMap(),
)

/** Richiesta esplicita e minima allo snapshot provider per un singolo batch Engine. */
data class StateReadRequest(
    val keys: Set<String> = emptySet(),
    val includeForegroundApp: Boolean = false,
    val includeLocation: Boolean = false,
    val queries: Set<StateQuery> = emptySet(),
) {
    init {
        require(keys.all(StateKeys.ALL::containsKey)) { "Chiave di stato fuori registry" }
    }

    val isEmpty: Boolean
        get() = keys.isEmpty() && !includeForegroundApp && !includeLocation && queries.isEmpty()

    operator fun plus(other: StateReadRequest): StateReadRequest = StateReadRequest(
        keys = keys + other.keys,
        includeForegroundApp = includeForegroundApp || other.includeForegroundApp,
        includeLocation = includeLocation || other.includeLocation,
        queries = queries + other.queries,
    )

    fun missingFrom(known: StateReadRequest): StateReadRequest = StateReadRequest(
        keys = keys - known.keys,
        includeForegroundApp = includeForegroundApp && !known.includeForegroundApp,
        includeLocation = includeLocation && !known.includeLocation,
        queries = queries - known.queries,
    )

    fun project(state: DeviceState): DeviceState = DeviceState(
        values = state.values.filterKeys(keys::contains).toMap(),
        foregroundApp = state.foregroundApp.takeIf { includeForegroundApp },
        location = state.location.takeIf { includeLocation },
        queryValues = state.queryValues.filterKeys { id -> queries.any { it.canonicalId == id } }
            .toMap(),
    )

    companion object {
        val EMPTY = StateReadRequest()

        /** Compatibilità InvokeLlm v1: equivale al vecchio snapshot globale, reso esplicito. */
        val LEGACY_GENERATIVE = StateReadRequest(
            keys = StateKeys.ALL.keys.toSet(),
            includeForegroundApp = true,
        )
    }
}

/** Visitor esaustivo: aggiungere un subtype obbliga a dichiararne il fabbisogno di stato. */
object StateReadPlanner {
    fun forCondition(condition: Condition?): StateReadRequest = when (condition) {
        null,
        is Condition.TimeWindow,
        is Condition.BooleanLiteral,
        // VarCompare confronta variabili di scope, non stato del device: nessuna lettura pianificata.
        is Condition.VarCompare,
        -> StateReadRequest.EMPTY
        is Condition.StateEquals -> StateReadRequest(keys = setOf(condition.key))
        is Condition.StateCompare -> StateReadRequest(queries = setOf(condition.query))
        is Condition.AppInForeground -> StateReadRequest(includeForegroundApp = true)
        is Condition.LocationIn -> StateReadRequest(includeLocation = true)
        is Condition.And -> condition.all.fold(StateReadRequest.EMPTY) { request, child ->
            request + forCondition(child)
        }
        is Condition.Or -> condition.any.fold(StateReadRequest.EMPTY) { request, child ->
            request + forCondition(child)
        }
        is Condition.Not -> forCondition(condition.cond)
    }

    fun forAction(action: Action): StateReadRequest = when (action) {
        is Action.InvokeLlm -> if (
            GenerativeContract.CONTEXT_STATE in action.contextSources
        ) {
            StateReadRequest.LEGACY_GENERATIVE
        } else {
            StateReadRequest.EMPTY
        }
        is Action.InvokeLlmV2 -> StateReadRequest(
            queries = action.stateContext.mapTo(linkedSetOf()) { it.query },
        )
        is Action.SetWifi,
        is Action.SetBluetooth,
        is Action.SetMobileData,
        is Action.SetDnd,
        is Action.SetRinger,
        is Action.LaunchApp,
        is Action.OpenUrl,
        is Action.ShowNotification,
        is Action.Tap,
        is Action.InputText,
        is Action.WhatsAppReply,
        is Action.RunShell,
        is Action.CopyToClipboard,
        is Action.CopyText,
        is Action.SetAlarm,
        is Action.SetTimer,
        is Action.SetVolume,
        is Action.SetFlashlight,
        // set_dark_mode non legge stato: la modalità è un enum approvato.
        is Action.SetDarkMode,
        is Action.OpenSettingsScreen,
        is Action.Vibrate,
        is Action.Wait,
        // write_setting non legge stato: namespace/key/value sono letterali approvati.
        is Action.WriteSetting,
        -> StateReadRequest.EMPTY
        // Contenitori control-flow: uniscono le letture della condizione di flusso e di tutte le
        // azioni annidate, così il planner prepara lo stato che l'interprete P4-B leggerà davvero.
        is Action.If -> (action.then + action.orElse).fold(forCondition(action.condition)) { req, child ->
            req + forAction(child)
        }
        is Action.While -> action.body.fold(forCondition(action.condition)) { req, child ->
            req + forAction(child)
        }
    }
}
