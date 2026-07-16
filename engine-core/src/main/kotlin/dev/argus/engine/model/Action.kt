package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DndMode { OFF, PRIORITY, TOTAL }
enum class ActionTier { DETERMINISTIC, GENERATIVE }

/** Marker chiuso per impedire che la lane accetti azioni deterministiche per errore. */
sealed interface GenerativeAction

/** Discriminatori wire stabili condivisi da JSON, manifest capability e journal. */
object ActionTypeIds {
    const val SET_WIFI = "set_wifi"
    const val SET_BLUETOOTH = "set_bluetooth"
    const val SET_DND = "set_dnd"
    const val SET_RINGER = "set_ringer"
    const val LAUNCH_APP = "launch_app"
    const val OPEN_URL = "open_url"
    const val SHOW_NOTIFICATION = "show_notification"
    const val TAP = "tap"
    const val INPUT_TEXT = "input_text"
    const val WHATSAPP_REPLY = "whatsapp_reply"
    const val RUN_SHELL = "run_shell"
    const val COPY_TO_CLIPBOARD = "copy_to_clipboard"
    const val INVOKE_LLM = "invoke_llm"
    const val INVOKE_LLM_V2 = "invoke_llm_v2"
}

/**
 * Profilo P1 dell'azione generativa: l'unico contratto InvokeLlm che la lane esegue davvero.
 * Validator, derivazione capability e lane devono restare allineati a queste costanti.
 */
object GenerativeContract {
    const val CONTEXT_NOTIFICATION = "notification"
    const val CONTEXT_STATE = "state"
    /** Tool wire di reply: coincide con ActionTypeIds.WHATSAPP_REPLY. */
    const val TOOL_WHATSAPP_REPLY = ActionTypeIds.WHATSAPP_REPLY
    /** Tool raw richiesto a runtime quando il contesto include lo stato device. */
    const val TOOL_STATE_READ = "state.read"
    val CONTEXT_SOURCES: Set<String> = setOf(CONTEXT_NOTIFICATION, CONTEXT_STATE)
    val ALLOWED_TOOLS: List<String> = listOf(TOOL_WHATSAPP_REPLY)
}

@Serializable
sealed interface Action {
    val tier: ActionTier
        get() = when (this) {
            is InvokeLlm,
            is InvokeLlmV2,
            -> ActionTier.GENERATIVE
            is SetWifi,
            is SetBluetooth,
            is SetDnd,
            is SetRinger,
            is LaunchApp,
            is OpenUrl,
            is ShowNotification,
            is Tap,
            is InputText,
            is WhatsAppReply,
            is RunShell,
            is CopyToClipboard,
            -> ActionTier.DETERMINISTIC
        }

    @Serializable @SerialName(ActionTypeIds.SET_WIFI) data class SetWifi(val on: Boolean) : Action
    @Serializable @SerialName(ActionTypeIds.SET_BLUETOOTH) data class SetBluetooth(val on: Boolean) : Action
    @Serializable @SerialName(ActionTypeIds.SET_DND) data class SetDnd(val mode: DndMode) : Action
    @Serializable @SerialName(ActionTypeIds.SET_RINGER) data class SetRinger(val mode: String) : Action
    @Serializable @SerialName(ActionTypeIds.LAUNCH_APP) data class LaunchApp(val pkg: String) : Action
    @Serializable @SerialName(ActionTypeIds.OPEN_URL) data class OpenUrl(val url: String) : Action
    @Serializable @SerialName(ActionTypeIds.SHOW_NOTIFICATION) data class ShowNotification(val title: String, val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.TAP) data class Tap(val x: Int, val y: Int) : Action
    @Serializable @SerialName(ActionTypeIds.INPUT_TEXT) data class InputText(val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.WHATSAPP_REPLY) data class WhatsAppReply(val text: String) : Action
    @Serializable @SerialName(ActionTypeIds.RUN_SHELL) data class RunShell(val cmd: String) : Action

    /** Copia negli appunti il payload testuale del trigger (SMS o notifica), opzionalmente
     *  ridotto al primo capture group di una regex RE2 lineare (P2-3, OTP). Estrazione
     *  DETERMINISTICA: il testo non lascia mai il telefono. */
    @Serializable @SerialName(ActionTypeIds.COPY_TO_CLIPBOARD)
    data class CopyToClipboard(val extractionRegex: String? = null) : Action

    @Serializable @SerialName(ActionTypeIds.INVOKE_LLM)
    data class InvokeLlm(
        val goal: String,
        val contextSources: List<String>,
        val allowedTools: List<String>,   // MAI shell.run / automation.* (DraftValidator, spec §7)
        val replyTargetSender: Boolean,   // spec §10.4: destinatario vincolato al trigger.sender
        val timeoutMs: Long = 60_000,
    ) : Action, GenerativeAction

    /**
     * Profilo P3 con stato minimo: a differenza di [InvokeLlm] non autorizza uno snapshot
     * generico. Ogni reader, tipo e classificazione entra nel fingerprint approvato.
     *
     * Tutti i campi wire sono obbligatori: una regola v1 non viene mai migrata aggiungendo
     * default o riscrivendone il fingerprint in silenzio.
     */
    @Serializable @SerialName(ActionTypeIds.INVOKE_LLM_V2)
    data class InvokeLlmV2(
        val goal: String,
        val stateContext: List<ApprovedStateContext>,
        val allowedTools: List<String>,
        val replyTargetSender: Boolean,
        val timeoutMs: Long,
    ) : Action, GenerativeAction
}

enum class IntegrityLabel { CLEAN, TAINTED }
enum class ConfidentialityLabel { PUBLIC, PRIVATE, SECRET }

/** Metadati approvati per un singolo valore locale inviato al Brain configurato. */
@Serializable
data class ApprovedStateContext(
    val query: StateQuery,
    val valueType: StateValueType,
    val policyVersion: Int,
    val integrity: IntegrityLabel,
    val confidentiality: ConfidentialityLabel,
)

/** Classificazione minima fail-closed: il compilatore può alzarla, mai abbassarla. */
object StateContextClassification {
    const val MAX_QUERIES = 16

    fun minimumConfidentiality(query: StateQuery): ConfidentialityLabel = when (query) {
        is StateQuery.Builtin -> ConfidentialityLabel.PRIVATE
        is StateQuery.Setting,
        is StateQuery.SystemProperty,
        is StateQuery.Sysfs,
        is StateQuery.DumpsysField,
        -> ConfidentialityLabel.SECRET
    }

    fun covers(actual: ConfidentialityLabel, minimum: ConfidentialityLabel): Boolean =
        actual.ordinal >= minimum.ordinal

    fun validValueType(query: StateQuery, valueType: StateValueType): Boolean =
        (query as? StateQuery.Builtin)?.let { builtin ->
            when (builtin.key) {
                StateKeys.BATTERY -> valueType == StateValueType.NUMBER
                StateKeys.CHARGING -> valueType == StateValueType.BOOLEAN
                else -> valueType == StateValueType.TEXT
            }
        } ?: true
}
