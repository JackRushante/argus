package dev.argus.ui.model

import dev.argus.engine.safety.Severity
import dev.argus.engine.runtime.AuditKind

// =============================================================================
// Contratti di stato UI (handoff-frontend.md §6). Kotlin UI-layer: stringhe
// pronte, nessuna logica nel Composable. `Severity` e `AuditKind` sono i tipi
// di engine-core. Copiati verbatim dall'handoff §6 (unico adattamento: import).
// =============================================================================

// --- Tipi condivisi (handoff §6) ---

/** Rendering DETERMINISTICO della regola (direttiva §5.1) — costruito dall'app dai tipi, mai dall'LLM. */
data class RuleRender(
    val triggerLine: String,          // "Quando: notifica WhatsApp da Moglie (chat 1:1)"
    val triggerIconKey: String,       // "notification" | "time" | "geofence" | "phone" | "connectivity"
    val conditionLines: List<String>, // ["Solo tra le 18:00 e le 22:00 (Europe/Rome)"] — albero appiattito con indentazione
    val actions: List<ActionRow>,
    val isGenerative: Boolean,
    val privacyNote: String?,         // valorizzato se generativa: "Il testo delle notifiche verrà inviato a Hermes…"
)

data class ActionRow(
    val iconKey: String,
    val label: String,                // "Disattiva Wi-Fi" / "Rispondi con l'AI"
    val detail: String?,              // per InvokeLlm: goal + tool consentiti; per ShowNotification: il testo
    val isShell: Boolean,             // => shellCommand da mostrare monospace integrale (§5.3)
    val shellCommand: String?,
    val isGenerative: Boolean,
    val requiresLiveConfirm: Boolean, // catalogo sempre-conferma (spec §10.2)
)

data class UiWarning(val severity: Severity, val code: String, val text: String)  // da ValidationIssue/ConflictWarning

enum class StatusBadge { ARMED, PENDING_APPROVAL, DISABLED, NEEDS_REVIEW }

/** Salute del sistema, mostrata come banner in Lista e Sistema. */
enum class EngineBanner { NONE, SHIZUKU_DOWN, SHIZUKU_DEGRADED_AFTER_REBOOT, BATTERY_NOT_EXEMPT, BRAIN_UNREACHABLE }

enum class ShizukuStatus { NOT_INSTALLED, INSTALLED_NOT_RUNNING, RUNNING_NOT_AUTHORIZED, AUTHORIZED, DEGRADED_AFTER_REBOOT }

// --- 6.1 Chat ---

data class ChatState(
    val items: List<ChatItem>,
    val input: String,
    val sending: Boolean,             // true durante la one-shot (10-30 s): input disabilitato, LatencyIndicator visibile
    val sendingElapsedSec: Int?,      // per "Argus sta pensando… (12 s)"
    val brainReachable: Boolean?,     // null = mai verificato; false = banner "Hermes irraggiungibile" + retry
    val error: ChatError?,
)

sealed interface ChatItem {
    data class UserMessage(val text: String, val timeLabel: String) : ChatItem
    data class AssistantMessage(val text: String, val timeLabel: String) : ChatItem
    /** Card regola proposta: riassunto compatto di RuleRender + CTA. L'arm NON avviene qui (§3). */
    data class DraftCard(
        val draftId: String,
        val rule: RuleRender,
        val issues: List<UiWarning>,          // ERROR ⇒ la card mostra "non armabile: <motivo>"
        val status: DraftCardStatus,          // PROPOSED | APPROVED | REJECTED | SUPERSEDED
    ) : ChatItem
    data class SystemNotice(val text: String, val kind: NoticeKind) : ChatItem  // INFO | ERROR (es. "compile fallita")
}

enum class DraftCardStatus { PROPOSED, APPROVED, REJECTED, SUPERSEDED }
enum class NoticeKind { INFO, ERROR }

sealed interface ChatError {
    data object Timeout : ChatError                    // "Hermes non ha risposto entro 60 s. Riprova."
    data object BridgeUnreachable : ChatError
    data class MalformedReply(val detail: String) : ChatError  // metaError dal parser: mostrare "risposta senza regola valida" + dettaglio espandibile
}

interface ChatCallbacks {
    fun onInputChange(text: String); fun onSend(); fun onCancelPending()
    fun onOpenDraft(draftId: String)   // push al Dettaglio §6.3
    fun onRetry()
    // Menu overflow dell'header (prima era solo decorativo: feedback Lorenzo 2026-07-14).
    fun onClearConversation()
    fun onCheckConnection()
}

// --- 6.2 Automazioni · lista ---

data class AutomationListState(
    val rows: List<AutomationRow>,
    val filter: StatusFilter,               // ALL | ARMED | PENDING | DISABLED | NEEDS_REVIEW (chips)
    val banner: EngineBanner,
    val loading: Boolean,
    val pendingCount: Int = rows.count { it.status == StatusBadge.PENDING_APPROVAL },
    val needsReviewCount: Int = rows.count { it.status == StatusBadge.NEEDS_REVIEW },
)

data class AutomationRow(
    val id: String,
    val name: String,
    val triggerIconKey: String,             // icona trigger (§6.2): "notification" | "time" | "geofence" | ... (iconFor)
    val triggerSummary: String,             // "Notifica WhatsApp · Moglie" / "Ogni giorno 23:00"
    val status: StatusBadge,
    val enabled: Boolean,                   // toggle inline (solo per ARMED/DISABLED)
    val isGenerative: Boolean,
    val hasWarnings: Boolean,               // pallino warning (conflitti/validator)
    val lastFiredLabel: String?,            // "2 h fa" / null = mai
    val nextFireLabel: String?,             // solo trigger Time: "stasera 23:00"
)

enum class StatusFilter { ALL, ARMED, PENDING, DISABLED, NEEDS_REVIEW }

interface AutomationListCallbacks {
    fun onOpen(id: String); fun onToggleEnabled(id: String, enabled: Boolean); fun onFilter(f: StatusFilter)
    fun onEmptyCta() {}     // CTA empty-state "Vai in chat"; default no-op, la nav reale è dell'host (NavHost)
    fun onBannerTap() {}    // tap sul banner salute → Sistema; default no-op, host-owned
}

// --- 6.3 Automazione · dettaglio / approvazione ---

data class AutomationDetailState(
    val id: String,
    val name: String,
    val status: StatusBadge,
    val rule: RuleRender,                   // §5.1: SEMPRE la fonte di verità visiva
    val rationale: String?,                 // prosa LLM, resa come commento subordinato (§5.1)
    val warnings: List<UiWarning>,          // validator + conflitti + privacy, sopra la fold
    val canArm: Boolean,
    val armBlockedReason: String?,          // es. "Tool 'shell.run' vietato nelle regole generative"
    val estimatedLlmCallsPerDay: String?,   // solo generative: "stimate ~5 chiamate/giorno" (spec §10.5)
    val recentRuns: List<LogRow>,           // ultime 3-5 esecuzioni (stesso tipo del Log §6.4)
    val geofencePreviewLabel: String?,      // "Posizione: attuale al momento dell'attivazione" (resolveCurrentLocation)
    /** P0-B non espone un percorso manuale sicuro: il controllo resta fail-closed finché non esiste. */
    val canRunNow: Boolean = false,
    val runNowBlockedReason: String? = "Esecuzione manuale non disponibile in questa fase",
    /** Probe positivi redatti: solo famiglia/tipo, mai il valore campione. */
    val verifiedStateReaders: List<String> = emptyList(),
)

interface AutomationDetailCallbacks {
    fun onArm(); fun onReject()             // footer in stato PENDING
    fun onSetEnabled(enabled: Boolean); fun onDelete()   // footer ARMED/DISABLED (delete con conferma, §5.6)
    fun onAskEdit()                          // torna in chat con contesto ("modifica questa regola…")
    fun onRunNow()                           // test manuale: esegue le azioni ORA saltando trigger/condizioni (conferma prima)
    fun onOpenFullLog()
    fun onBack() {}                          // affordance back nell'header; default no-op, la nav reale è dell'host (NavHost)
}

// --- 6.4 Log esecuzioni ---

data class ExecutionLogState(
    val entries: List<LogRow>,
    val filterAutomationName: String?,      // null = tutte; settato quando si arriva dal dettaglio
    val loading: Boolean,
)

data class LogRow(
    val id: String,
    val timeLabel: String,                  // "oggi 23:00"
    val automationName: String,
    val kind: AuditKind,
    val outcome: LogOutcome,                // solo per FIRED
    val summary: String,                    // "2/2 azioni ok" / "soppressa (cooldown)" / "risposta inviata a Moglie"
    val expandedDetail: List<String>?,      // righe per-azione al tap (incl. esito lane generativa, anche DEFERRED E13)
    /** Id dominio distinto da [id], che identifica invece la riga audit. Null se la regola non esiste più. */
    val automationId: String? = null,
    /** La riga appartiene a un'esecuzione con almeno un'azione generativa/cloud. */
    val isGenerative: Boolean = false,
)

enum class LogOutcome { SUCCESS, PARTIAL, FAILED, SUBMITTED, DEFERRED }
// SUBMITTED = generativa in corso; DEFERRED = "risposta pronta, consegna manuale" (spec E13)

interface ExecutionLogCallbacks {
    fun onExpand(id: String); fun onClearFilter(); fun onOpenAutomation(id: String)
    // E13: "Invia ora" su una riga DEFERRED. Riceve l'id della RIGA di log (non un id
    // automazione): P0-B risolve la reply differita dalla entry, NON naviga. Default no-op.
    fun onSendNow(logId: String) {}
}

// --- 6.5 Sistema (settings) ---

data class SettingsState(
    val transport: TransportUi,
    val shizuku: ShizukuStatus,
    val batteryExempt: Boolean,
    /** Permesso di PUBBLICARE notifiche (esiti/avvisi Argus). */
    val notificationsGranted: Boolean,
    /** Accesso notification listener: lettura notifiche WhatsApp e canale di reply (P1). */
    val notificationListenerGranted: Boolean,
    val backgroundLocation: BgLocationState,   // GRANTED | WHILE_IN_USE | DENIED | NOT_NEEDED (nessuna regola geofence)
    /** Trigger telefonia (P2-2): opt-in permanente, righe sempre azionabili in Sistema. */
    val smsTriggerGranted: Boolean = false,
    val callTriggerGranted: Boolean = false,
    /** Trigger ACL Bluetooth (P2-3): runtime grant NEARBY_DEVICES su Android 12+. */
    val bluetoothTriggerGranted: Boolean = false,
    /** Visibile solo mentre il monitor condiviso Wi-Fi/alimentazione è realmente attivo. */
    val connectivitySentinelActive: Boolean = false,
    val whitelist: List<ContactRow>,
    /** Conversazioni 1:1 osservate (solo WhatsApp) proposte dal picker whitelist. */
    val observedCandidates: List<ContactRow> = emptyList(),
    val budget: BudgetUi,
    val privacyAccepted: Boolean,
    val appVersionLabel: String,
)

sealed interface TransportUi {
    data class CliBridge(
        val url: String,
        val reachable: Boolean?,
        val lastLatencyLabel: String?,
        /** Indica solo la presenza nel Keystore; il bearer non entra mai nello stato UI. */
        val tokenConfigured: Boolean = false,
    ) : TransportUi
    data class OpenAICompat(val baseUrl: String, val model: String, val authState: AuthState) : TransportUi  // P3, mostrare come "in arrivo"
}
enum class AuthState { OK, EXPIRED, NOT_CONFIGURED }
enum class BgLocationState { GRANTED, WHILE_IN_USE, DENIED, NOT_NEEDED }

data class ContactRow(val displayName: String, val conversationId: String)   // id in monospace, azione rimuovi
data class BudgetUi(val maxCallsPerHour: Int, val usedThisHourLabel: String) // "3/20 quest'ora"

interface SettingsCallbacks {
    fun onEditBridgeUrl(url: String); fun onTestConnection()
    /** Token null/vuoto = conserva quello già configurato; non viene mai precompilato dalla UI. */
    fun onSaveBridge(url: String, bearerToken: String?) { onEditBridgeUrl(url) }
    fun onOpenShizukuFix()      // deep-link allo step onboarding giusto per lo stato corrente
    fun onOpenBatteryFix(); fun onOpenNotificationAccessFix(); fun onOpenLocationFix()
    /** Apre le impostazioni di sistema per l'accesso notification listener (lettura, P1). */
    fun onOpenNotificationListenerFix() {}
    /** Richieste runtime dei trigger telefonia (P2-2): RECEIVE_SMS e READ_PHONE_STATE(+CALL_LOG). */
    fun onRequestSmsPermission() {}
    fun onRequestCallPermissions() {}
    fun onRequestBluetoothPermission() {}
    fun onRemoveContact(conversationId: String); fun onAddContact()   // picker → risoluzione conversationId
    /** Selezione dal picker delle conversazioni 1:1 osservate. */
    fun onAddObservedContact(contact: ContactRow) {}
    fun onBudgetChange(maxPerHour: Int)
    fun onRevokePrivacy()
    fun onRerunOnboarding()
}

// --- 6.6 Onboarding / permessi ---

data class OnboardingState(
    val steps: List<OnboardingStepState>,
    val currentIndex: Int,
    val canFinish: Boolean,     // WELCOME_PRIVACY e BRAIN_CONFIG obbligatori; il resto skippabile (degradato)
    val bridgeUrl: String? = null,
    val bridgeTokenConfigured: Boolean = false,
)

data class OnboardingStepState(
    val kind: StepKind,
    val status: StepStatus,     // TODO | IN_PROGRESS | DONE | SKIPPED | BLOCKED
    val title: String,
    val body: String,           // testo guida specifico per lo stato (vedi microcopy §9)
    val ctaLabel: String?,      // "Apri Shizuku" / "Concedi" / null
    val blockedReason: String?,
)

enum class StepKind { WELCOME_PRIVACY, BRAIN_CONFIG, SHIZUKU, NOTIFICATION_ACCESS, BATTERY_OEM, BACKGROUND_LOCATION }
enum class StepStatus { TODO, IN_PROGRESS, DONE, SKIPPED, BLOCKED }

interface OnboardingCallbacks {
    fun onStepCta(kind: StepKind); fun onSkip(kind: StepKind); fun onNext(); fun onBack(); fun onFinish()
    fun onSaveBridge(url: String, bearerToken: String?) {}
}
