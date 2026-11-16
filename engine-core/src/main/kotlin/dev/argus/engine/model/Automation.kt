@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.argus.engine.model
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class AutomationId(val value: String)
/**
 * Chi ha prodotto la regola. [IMPORT] = letta da un file di backup/condivisione (0.4.0): è sempre una
 * bozza da approvare, mai una regola già armata. Persistito col nome (Room, TEXT): nessuna migrazione.
 */
enum class CreatedBy { LLM, USER, IMPORT }
enum class AutomationStatus { PENDING_APPROVAL, ARMED, DISABLED, NEEDS_REVIEW }

const val AUTOMATION_SCHEMA_VERSION_V1 = 1
const val AUTOMATION_SCHEMA_VERSION_P4 = 2

/** Contratto indipendente dal materiale fingerprint e dal protocollo bridge. */
object AutomationSchema {
    val supportedVersions: Set<Int> = setOf(AUTOMATION_SCHEMA_VERSION_V1, AUTOMATION_SCHEMA_VERSION_P4)

    fun versionFor(draft: AutomationDraft): Int =
        if (requiresP4(draft.vars, draft.actions, draft.conditions, draft.exit)) AUTOMATION_SCHEMA_VERSION_P4
        else AUTOMATION_SCHEMA_VERSION_V1

    fun isCompatible(automation: Automation): Boolean {
        val needsP4 = requiresP4(automation)
        return when (automation.schemaVersion) {
            AUTOMATION_SCHEMA_VERSION_V1 -> !needsP4
            AUTOMATION_SCHEMA_VERSION_P4 -> needsP4
            else -> false
        }
    }

    fun isSupportedVersion(version: Int): Boolean = version in supportedVersions

    fun requiresP4(automation: Automation): Boolean =
        requiresP4(automation.vars, automation.actions, automation.conditions, automation.exit)

    private fun requiresP4(
        vars: List<VarBinding>,
        actions: List<Action>,
        conditions: Condition?,
        exit: ExitSpec? = null,
    ): Boolean {
        if (vars.isNotEmpty() || conditions?.containsP4Condition() == true) return true
        val pending = ArrayDeque<Action>()
        actions.forEach(pending::addLast)
        // 0.8 scaffold: le azioni di uscita di una regola-intervallo sono parte del programma.
        exit?.actions?.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.Wait -> return true
                is Action.If -> {
                    return true
                }
                is Action.While -> {
                    return true
                }
                is Action.RunShell -> if (action.captureAs != null) return true
                is Action.InvokeLlm -> if (action.captureAs != null) return true
                is Action.InvokeLlmV2 -> if (action.captureAs != null) return true
                // 0.8 scaffold: catturano o scrivono valori di programma.
                is Action.Ask -> return true
                is Action.SetStored -> return true
                is Action.HttpRequest -> if (action.captureAs != null) return true
                else -> Unit
            }
        }
        return false
    }

    private fun Condition.containsP4Condition(): Boolean {
        val pending = ArrayDeque<Condition>()
        pending.addLast(this)
        while (pending.isNotEmpty()) {
            when (val condition = pending.removeFirst()) {
                is Condition.VarCompare, is Condition.BooleanLiteral -> return true
                is Condition.And -> condition.all.forEach(pending::addLast)
                is Condition.Or -> condition.any.forEach(pending::addLast)
                is Condition.Not -> pending.addLast(condition.cond)
                else -> Unit
            }
        }
        return false
    }
}

@Serializable
data class Automation(
    val id: AutomationId,
    val name: String,
    val createdBy: CreatedBy,
    val status: AutomationStatus,
    val trigger: Trigger,
    val actions: List<Action>,
    /**
     * Variabili di programma dichiarate e approvate (P4 §2.2). @EncodeDefault(NEVER) + default
     * emptyList: una regola SENZA feature P4 serializza byte-per-byte come prima (vincolo
     * fingerprint non negoziabile), mentre le regole P4 la emettono e la includono nel fingerprint.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val vars: List<VarBinding> = emptyList(),
    val conditions: Condition? = null,
    val enabled: Boolean = true,
    /** Nello stesso batch: esecuzione in ordine CRESCENTE → il più prioritario scrive ultimo e vince (spec §5). */
    val priority: Int = 0,
    val cooldownMs: Long = 0,
    val schemaVersion: Int = AUTOMATION_SCHEMA_VERSION_V1,
    /**
     * 0.8 scaffold ([FeatureGate.CONFIRM]): prima di eseguire, chiedi all'utente. Prima dei requisiti
     * perché la loro derivazione lo legge; @EncodeDefault(NEVER) ⇒ assente dal wire quando null, così i
     * byte (e le impronte) delle regole esistenti non cambiano.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val confirm: ConfirmSpec? = null,
    /** 0.8 scaffold ([FeatureGate.EXIT]): fine della regola-intervallo. Stesse regole di [confirm]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val exit: ExitSpec? = null,
    /** Snapshot derivato e fingerprintato di trigger, condizioni, azioni e variabili richiesti a runtime. */
    val requiredCapabilities: Set<String> =
        CapabilityRequirements.derive(trigger, actions, conditions, vars, confirm, exit),
    /** Hash dei dati eseguibili approvati; ogni edit lo rende non più corrispondente. */
    val approvalFingerprint: ApprovalFingerprint? = null,
)

/**
 * 0.8 scaffold ([FeatureGate.CONFIRM]): prima di eseguire una regola scattata, il motore chiede
 * all'utente con una notifica (Esegui / Annulla). Nessuna risposta entro [timeoutMs] annulla.
 */
@Serializable
data class ConfirmSpec(val timeoutMs: Long = 300_000)

/**
 * 0.8 scaffold ([FeatureGate.EXIT]): la regola diventa un intervallo. Comincia col suo trigger e finisce
 * con [trigger]; con [restore] il motore rimette i valori che le azioni d'inizio avevano cambiato, poi
 * esegue [actions].
 */
@Serializable
data class ExitSpec(
    val trigger: Trigger,
    val restore: Boolean = true,
    val actions: List<Action> = emptyList(),
)

/** Bozza proposta dall'LLM: come Automation ma senza id/status (li assegna l'app all'approvazione). */
@Serializable
data class AutomationDraft(
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    /** Variabili dichiarate dal draft (P4 §2.2). @EncodeDefault(NEVER): un draft senza feature P4
     *  resta byte-identico a prima. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val vars: List<VarBinding> = emptyList(),
    val conditions: Condition? = null,
    val rationale: String = "",
    val cooldownMs: Long = 0,
    /** 0.8 scaffold: come [Automation.confirm]. @EncodeDefault(NEVER): un draft senza resta byte-identico. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val confirm: ConfirmSpec? = null,
    /** 0.8 scaffold: come [Automation.exit]. @EncodeDefault(NEVER): un draft senza resta byte-identico. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val exit: ExitSpec? = null,
)

/**
 * Bozza equivalente alla regola: l'UNICA copia tipata da [Automation] a [AutomationDraft]. Porta
 * tutto ciò che entra nel fingerprint (nome, trigger, azioni, variabili P4, condizioni) più il
 * cooldown; la prosa dell'LLM (`rationale`) non esiste sulla regola e resta vuota. Una copia
 * locale che dimentica `vars` trasforma una regola P4 in una bozza invalida: usare sempre questa.
 */
fun Automation.toDraft(): AutomationDraft = AutomationDraft(
    name = name,
    trigger = trigger,
    actions = actions,
    vars = vars,
    conditions = conditions,
    cooldownMs = cooldownMs,
    confirm = confirm,
    exit = exit,
)
