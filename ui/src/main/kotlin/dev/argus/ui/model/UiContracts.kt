package dev.argus.ui.model

import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.safety.Severity
import dev.argus.ui.presentation.RenderLanguage

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
    val privacyNote: String?,         // valorizzato se generativa: disclosure del servizio AI configurato
    val vars: List<VarRow> = emptyList(), // P4: variabili approvate (vuoto per le regole v1 flat)
    val program: List<ProgramNode> = emptyList(), // P4: albero azioni ricorsivo (if/while/wait/foglie)
)

/**
 * Nodo dell'albero del programma approvato, reso per la review ricorsiva (P4-E). Ogni stringa è
 * pre-costruita dai tipi dal mapper (§5.1): il Composable si limita a mostrarla e a indentare i
 * figli, così NESSUNA foglia annidata può restare nascosta. `Wait` è una foglia (usa ActionRow).
 */
sealed interface ProgramNode {
    data class Leaf(val row: ActionRow) : ProgramNode

    data class IfNode(
        val title: String,                    // "If:" / "Se:"
        val conditionLines: List<String>,     // condizione resa (albero appiattito indentato)
        val thenTitle: String,                // "Then:" / "Allora:"
        val then: List<ProgramNode>,
        val elseTitle: String?,               // "Otherwise:" / "Altrimenti:" — null se nessun else
        val orElse: List<ProgramNode>,
    ) : ProgramNode

    data class WhileNode(
        val title: String,                    // "Repeat up to N times · Y ms between" / IT
        val conditionLines: List<String>,     // condizione del loop resa
        val body: List<ProgramNode>,
    ) : ProgramNode
}

/**
 * Una variabile del programma approvato (P4), resa per la review. I valori RUNTIME non compaiono
 * MAI qui: si mostra solo la DEFINIZIONE (nome, tipo, integrità, riservatezza, provenienza) così
 * l'utente vede da dove arriva ogni dato — e se è esterno/non fidato — prima di armare.
 */
data class VarRow(
    val name: String,                 // "otp"
    val typeLabel: String,            // "text" | "number" | "boolean"
    val integrityLabel: String,       // "trusted" | "external" (CLEAN/TAINTED)
    val confidentialityLabel: String, // "public" | "private" | "secret"
    val provenanceLabel: String,      // "SMS" | "fixed value" | "device state" | ...
    /**
     * Valore integrale approvato: valorizzato SOLO per i letterali ([dev.argus.engine.model.VarBinding.Literal]),
     * `null` per ogni altro binding (i valori runtime non compaiono mai in review). Si rende in
     * monospace, a capo, mai troncato (§5.3).
     */
    val valueText: String? = null,
    /**
     * Da dove nasce il valore, reso dai tipi: campo del trigger (+ estrazione), query di stato col
     * tipo, intervallo del numero casuale o "valore fisso". Vuoto solo per righe costruite a mano.
     */
    val sourceText: String = "",
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

enum class ShizukuStatus {
    NOT_INSTALLED,
    INSTALLED_NOT_RUNNING,
    RUNNING_NOT_AUTHORIZED,
    AUTHORIZED,
    DEGRADED_AFTER_REBOOT,

    /** Shizuku in esecuzione ma troppo vecchio per Argus: va aggiornato, non riavviato. */
    UNSUPPORTED,
}

// --- 6.1 Chat ---

data class ChatState(
    val items: List<ChatItem>,
    val input: String,
    val sending: Boolean,             // true durante la one-shot (10-30 s): input disabilitato, LatencyIndicator visibile
    val sendingElapsedSec: Int?,      // per "Argus sta pensando… (12 s)"
    val brainReachable: Boolean?,     // null = mai verificato; false = banner "Hermes irraggiungibile" + retry
    val error: ChatError?,
    /**
     * 0.3.6 (F5): testo grezzo restituito dal modello nell'ULTIMO turno (max 16 000 caratteri), per le
     * segnalazioni. Solo in memoria: si azzera con nuovo invio, nuova conversazione o uscita dalla
     * modifica; mai nel DB, nei log, nell'audit o nella bozza. null = niente da mostrare.
     */
    val rawResponse: String? = null,
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
    data object Timeout : ChatError                    // Il servizio AI non ha risposto in tempo.
    data object BridgeUnreachable : ChatError          // SOLO rete assente/irraggiungibile.
    data class MalformedReply(val detail: String) : ChatError  // metaError dal parser: mostrare "risposta senza regola valida" + dettaglio espandibile

    // 0.3.5 (B11): errori spiegati col loro rimedio; ognuno ha testo EN/IT e un'azione utile.
    /** Chiave API rifiutata (401/403). Azione: apri Sistema. */
    data object Auth : ChatError
    /** Limite di spesa dell'app o credito del provider esaurito. Azione: apri Sistema. */
    data object Budget : ChatError
    /** Provider sovraccarico (429). */
    data object RateLimit : ChatError
    /** Risposta del modello tagliata dal limite di lunghezza. */
    data object OutputTruncated : ChatError
    /** Il server ha tagliato le istruzioni (contesto troppo piccolo, tipico di Ollama). */
    data object ContextTruncated : ChatError
    /**
     * 4xx diverso da 401/402/403/429. Con [providerMessage] (0.3.7: messaggio del provider già ripulito,
     * es. «x is not a valid model ID») la chat dice COSA ha rifiutato il server; senza, resta il consiglio
     * generico («spesso il contesto del modello è troppo piccolo»). Solo in memoria: mai nel DB né nei log.
     */
    data class ServerRejected(val status: Int, val providerMessage: String? = null) : ChatError
    /** 5xx: servizio non disponibile. */
    data class ServiceUnavailable(val status: Int) : ChatError
    /** Prompt composto oltre il tetto: errore locale, nessuna chiamata di rete. */
    data class TooLong(val chars: Int, val max: Int) : ChatError
    /** Provider/chiave/informativa privacy non configurati: NON è un servizio irraggiungibile. Azione: apri Sistema. */
    data object NotConfigured : ChatError
    /**
     * 0.3.6 (B34b): errore LOCALE dell'app (eccezione imprevista), non del modello né della rete.
     * [code] è il nome semplice della classe d'eccezione in snake_case, mai il suo messaggio.
     */
    data class Internal(val code: String) : ChatError
}

interface ChatCallbacks {
    fun onInputChange(text: String); fun onSend(); fun onCancelPending()
    fun onOpenDraft(draftId: String)   // push al Dettaglio §6.3
    fun onRetry()
    // Menu overflow dell'header (prima era solo decorativo: feedback Lorenzo 2026-07-14).
    fun onClearConversation()
    fun onCheckConnection()
    /** Azione degli errori Auth/Budget/NotConfigured: porta alla scheda Sistema. Default no-op per i vecchi host. */
    fun onOpenSystem() {}
    /**
     * «Copia per la segnalazione» nel dialogo «Risposta grezza»: la copia (versione, provider, modello,
     * codice d'errore e testo grezzo) la compone il ViewModel e la scrive il copiatore appunti esistente.
     * Default no-op per i vecchi host: il NavHost deve cablarla su `ChatViewModel.onCopyRawResponse()`.
     */
    fun onCopyRawResponse() {}
}

// --- 6.2 Automazioni · lista ---

data class AutomationListState(
    val rows: List<AutomationRow>,
    val filter: StatusFilter,               // ALL | ARMED | PENDING | DISABLED | NEEDS_REVIEW (chips)
    val banner: EngineBanner,
    val loading: Boolean,
    val pendingCount: Int = rows.count { it.status == StatusBadge.PENDING_APPROVAL || it.pendingEditDraftId != null },
    val needsReviewCount: Int = rows.count { it.status == StatusBadge.NEEDS_REVIEW },
    /** 0.4.1 (F21): «Pausa automazioni» attiva. Mostra il banner «Automazioni in pausa · Riprendi». */
    val paused: Boolean = false,
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
    /**
     * D5: id della bozza di modifica in attesa su questa regola armata/disattivata (null = nessuna).
     * La regola resta attiva finché la modifica non viene armata: la riga mostra un badge
     * «modifica in attesa» e il tocco sul badge apre la bozza.
     */
    val pendingEditDraftId: String? = null,
)

enum class StatusFilter { ALL, ARMED, PENDING, DISABLED, NEEDS_REVIEW }

interface AutomationListCallbacks {
    fun onOpen(id: String); fun onToggleEnabled(id: String, enabled: Boolean); fun onFilter(f: StatusFilter)
    fun onEmptyCta() {}     // CTA empty-state "Vai in chat"; default no-op, la nav reale è dell'host (NavHost)
    fun onBannerTap() {}    // tap sul banner salute → Sistema; default no-op, host-owned
    fun onResumeAutomations() {}   // «Riprendi» sul banner della pausa (F21); default no-op
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
    val runNowBlockedReason: String? = null,
    /** Probe positivi redatti: solo famiglia/tipo, mai il valore campione. */
    val verifiedStateReaders: List<String> = emptyList(),
    /**
     * B26: un'azione del footer (arma, rifiuta, elimina) è in corso. Il footer resta disabilitato
     * finché è vero, così un secondo tocco non può né duplicare l'azione né emettere un secondo Close.
     */
    val actionInProgress: Boolean = false,
    /** 0.3.6 (F2): «Perché non è scattata?». Null finché la sezione non la chiede. */
    val diagnosis: RuleDiagnosisUi? = null,
    /** Vero solo per una regola salvata: una bozza non ha nulla da diagnosticare. */
    val diagnosisAvailable: Boolean = false,
    /**
     * 0.4.0 (F10): scatti, riuscite e fallite negli ultimi 7 e 30 giorni, dal journal delle esecuzioni.
     * Solo per una regola salvata (armata o disattivata); null per una bozza o finché non è letto.
     */
    val stats: RuleStatsUi? = null,
    /** 0.4.1 (F8): ingressi dell'avvio manuale. Non nullo solo per una regola `manual` armata. */
    val manual: ManualEntryUi? = null,
)

/**
 * 0.4.1 (F8): ciò che il Dettaglio offre per lanciare a mano una regola `manual` armata. «Esegui ora»
 * è il pulsante del footer ([AutomationDetailState.canRunNow]); qui stanno le altre porte d'ingresso.
 */
data class ManualEntryUi(
    /** Il launcher del telefono accetta le scorciatoie fissate: senza, «Aggiungi alla Home» sparisce. */
    val canPinShortcut: Boolean = true,
    /** Questa regola è quella scelta per il riquadro delle impostazioni rapide «Argus: esegui regola». */
    val isTileRule: Boolean = false,
    /** «Pausa automazioni» attiva: anche l'avvio manuale è saltato, e l'utente lo deve sapere. */
    val paused: Boolean = false,
)

/**
 * Statistiche di una regola. [longWindowDays] è la finestra lunga REALMENTE coperta: 30, oppure meno se
 * l'utente ha scelto di conservare il Log per meno giorni (il journal non ricorda oltre). La UI lo dice
 * invece di mostrare uno zero che non sarebbe vero.
 */
data class RuleStatsUi(
    val last7Days: RuleStatsWindow,
    val last30Days: RuleStatsWindow,
    val longWindowDays: Int = 30,
) {
    /** Nessuno scatto in tutta la finestra lunga: si mostra la frase sola, senza tabella di zeri. */
    val isEmpty: Boolean get() = last30Days.fires == 0
}

/**
 * Esecuzioni di una finestra: [fires] = scatti che hanno davvero eseguito (le soppressioni da cooldown,
 * budget o regola non idonea non contano), [succeeded] = chiuse con successo, [failed] = fallite o
 * parziali o interrotte. Le esecuzioni ancora in corso o da consegnare a mano contano solo in [fires].
 */
data class RuleStatsWindow(val fires: Int, val succeeded: Int, val failed: Int)

/** Esito di una diagnosi una tantum: righe in ordine di lettura e ora del controllo. */
data class RuleDiagnosisUi(val rows: List<DiagnosisRow>, val checkedAtLabel: String)

data class DiagnosisRow(
    val level: DiagnosisLevel,
    val text: String,
    /** Azione offerta accanto alla riga (oggi solo «Apri Sistema»). */
    val fix: DiagnosisFix? = null,
)

enum class DiagnosisLevel { OK, WARN, INFO }

enum class DiagnosisFix { OPEN_SYSTEM }

interface AutomationDetailCallbacks {
    fun onArm(); fun onReject()             // footer in stato PENDING
    fun onSetEnabled(enabled: Boolean); fun onDelete()   // footer ARMED/DISABLED (delete con conferma, §5.6)
    fun onAskEdit()                          // torna in chat con contesto ("modifica questa regola…")
    fun onRunNow()                           // test manuale: esegue le azioni ORA saltando trigger/condizioni (conferma prima)
    fun onOpenFullLog()
    fun onDiagnose() {}                      // apre «Perché non è scattata?»: calcola la diagnosi una volta
    fun onOpenSystem() {}                    // porta alla scheda Sistema, come la barra in basso
    fun onBack() {}                          // affordance back nell'header; default no-op, la nav reale è dell'host (NavHost)
    fun onAddToHome() {}                     // F8: propone al launcher la scorciatoia della regola manuale
    fun onUseInTile() {}                     // F8: sceglie questa regola per il riquadro delle impostazioni rapide
}

// --- 6.4 Log esecuzioni ---

data class ExecutionLogState(
    val entries: List<LogRow>,
    val filterAutomationName: String?,      // null = tutte; settato quando si arriva dal dettaglio
    val loading: Boolean,
    /** Giorni di conservazione del Log (7, 30 o 90), scelti dal menu in alto. */
    val retentionDays: Int = LogRetentionOptions.DEFAULT_DAYS,
)

/** Opzioni di conservazione del Log mostrate nel menu: 7, 30 o 90 giorni. */
object LogRetentionOptions {
    val DAYS: List<Int> = listOf(7, 30, 90)
    const val DEFAULT_DAYS: Int = 30
}

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
    /**
     * Istante dell'evento (epoch ms). Il Log raggruppa per giorno locale da qui, non dal testo di
     * [timeLabel]. Null solo per righe costruite a mano (anteprime, fixture): ricadono sul testo.
     */
    val atMillis: Long? = null,
)

enum class LogOutcome { SUCCESS, PARTIAL, FAILED, SUBMITTED, DEFERRED }
// SUBMITTED = generativa in corso; DEFERRED = "risposta pronta, consegna manuale" (spec E13)

interface ExecutionLogCallbacks {
    fun onExpand(id: String); fun onClearFilter(); fun onOpenAutomation(id: String)
    // E13: «Copia e apri WhatsApp» su una riga DEFERRED (non consuma la risposta: v. onMarkSent). Riceve l'id della RIGA di log (non un id
    // automazione): P0-B risolve la reply differita dalla entry, NON naviga. Default no-op.
    fun onSendNow(logId: String) {}
    /**
     * «Segna come inviata» su una riga DEFERRED: l'utente conferma di aver inviato il messaggio
     * (il copia-e-apri non lo segna in anticipo). Riceve l'id della riga di log. Default no-op.
     */
    fun onMarkSent(logId: String) {}

    /** Menu in alto: sceglie la conservazione del Log (uno dei valori di [LogRetentionOptions.DAYS]). Default no-op. */
    fun onSetRetention(days: Int) {}

    /** Menu in alto: «Svuota il log». Arriva solo dopo la conferma dentro la schermata. Default no-op. */
    fun onClearLog() {}

    /** Menu in alto: «Esporta (senza dati personali)». Default no-op. */
    fun onExport() {}
}

// --- 6.5 Sistema (settings) ---

data class SettingsState(
    val transport: TransportUi,
    val shizuku: ShizukuStatus,
    val batteryExempt: Boolean,
    /**
     * 0.3.6: Android concede ad Argus le sveglie esatte (`SCHEDULE_EXACT_ALARM`). Da Android 14 e' negato
     * di default e solo l'utente lo concede, da «Sveglie e promemoria»: senza, le regole a orario
     * possono partire fino a un'ora in ritardo e le attese lunghe a schermo spento slittare.
     * Default `true`: il permesso non esiste sotto Android 12 e un fixture non deve avvisare per caso.
     */
    val exactAlarmsGranted: Boolean = true,
    /**
     * 0.4.1: Android concede ad Argus «Modifica impostazioni di sistema» (`WRITE_SETTINGS`), il permesso
     * speciale che serve alle azioni luminosità e rotazione dello schermo. Si concede solo dalle
     * impostazioni di sistema. Default `true`: un fixture non deve avvisare per caso.
     */
    val writeSettingsGranted: Boolean = true,
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
    /**
     * 0.5.1: «Accesso ai dati di utilizzo» concesso (`PACKAGE_USAGE_STATS`): serve alle regole «quando
     * apro/chiudo un'app». Si concede dalle impostazioni di sistema, oppure con Shizuku o root da
     * «Concedi i permessi permanenti». Default `true`: un fixture non deve avvisare per caso.
     */
    val usageAccessGranted: Boolean = true,
    /** Visibile solo mentre il monitor condiviso Wi-Fi/alimentazione è realmente attivo. */
    val connectivitySentinelActive: Boolean = false,
    val whitelist: List<ContactRow>,
    /** Conversazioni 1:1 osservate (solo WhatsApp) proposte dal picker whitelist. */
    val observedCandidates: List<ContactRow> = emptyList(),
    val budget: BudgetUi,
    val privacyAccepted: Boolean,
    val appVersionLabel: String,
    val providerChoices: List<ProviderChoiceUi> = emptyList(),
    /**
     * 0.3.6 (F11): true quando l'accesso alle notifiche NON è concesso e l'app è installata da APK su
     * Android 13+ (Android può bloccarlo con «Impostazione con restrizioni»): la riga mostra la guida.
     */
    val notificationListenerRestrictedHint: Boolean = false,
    /**
     * 0.3.6 (D10): Shizuku gira come root (uid 0), quindi i comandi delle regole girano come root.
     * `null` = non noto: il servizio privilegiato non è ancora collegato.
     */
    val shizukuRunsAsRoot: Boolean? = null,
    /** 0.4.0 (F1): backup delle regole su file (esporta/importa). */
    val backup: RulesBackupUi = RulesBackupUi(),
    /** 0.4.1 (F21): interruttore globale «Pausa automazioni». */
    val automationsPaused: Boolean = false,
    /** 0.5.0: backend privilegiato, root `su` e permessi permanenti. */
    val privileged: PrivilegedUi = PrivilegedUi(),
)

/** Operazione del backup delle regole. */
enum class RulesBackupKind { EXPORT, IMPORT }

/** Stato dell'ultima operazione di backup. I testi sono già nella lingua dell'utente (li scrive il VM). */
sealed interface RulesBackupStatus {
    data object Idle : RulesBackupStatus

    /** In corso: i due pulsanti sono disabilitati. */
    data class Working(val kind: RulesBackupKind) : RulesBackupStatus

    /** Riuscito, con il riepilogo («N regole importate da approvare; M scartate (motivo)»). */
    data class Done(
        val kind: RulesBackupKind,
        val summary: String,
        /** Bozze create dall'import: se > 0 la sezione offre di andare a vederle. */
        val importedCount: Int = 0,
    ) : RulesBackupStatus

    data class Failed(val kind: RulesBackupKind, val message: String) : RulesBackupStatus
}

data class RulesBackupUi(
    val status: RulesBackupStatus = RulesBackupStatus.Idle,
    /** Regole che finirebbero nel file: con 0 «Esporta» è disabilitato. */
    val exportableRules: Int = 0,
)

sealed interface TransportUi {
    data class CliBridge(
        val url: String,
        val reachable: Boolean?,
        val lastLatencyLabel: String?,
        /** Indica solo la presenza nel Keystore; il bearer non entra mai nello stato UI. */
        val tokenConfigured: Boolean = false,
    ) : TransportUi

    /** Provider diretto BYOK. NESSUN campo chiave: solo [AuthState] (§3 design). */
    data class DirectProvider(
        val providerId: String,          // ProviderId.wireName, es. "openai"
        val providerLabel: String,       // ProviderCatalog.spec(id).displayName
        val baseUrl: String,
        val model: String?,
        val authState: AuthState,        // OK | NOT_CONFIGURED (EXPIRED non emesso in S9)
        val reachable: Boolean?,
        val lastLatencyLabel: String?,
        val defaultModels: List<String> = emptyList(),   // suggerimenti dal catalog
        val baseUrlEditable: Boolean = false,            // true solo per CUSTOM_OPENAI_COMPAT
        val apiKeyPrefixHint: String? = null,            // hint soft, MAI bloccante
        val apiKeyRequired: Boolean = true,
        /** Nome enum UI: default/none/low/medium/high. */
        val reasoningEffort: String = "default",
        val reasoningEffortEditable: Boolean = false,
        /**
         * Custom verso un host loopback (localhost, 127.0.0.1, ::1) senza alcuna chiave salvata: un'altra
         * app sul telefono potrebbe ascoltare su quella porta e fingersi il modello. Solo avviso.
         */
        val unauthenticatedLoopback: Boolean = false,
        /** true per OpenRouter: il campo modello mostra il suggerimento sui preset `@preset/<nome>`. */
        val modelPresetHint: Boolean = false,
    ) : TransportUi
}
enum class AuthState { OK, EXPIRED, NOT_CONFIGURED }

/** Voce del selettore provider (il VM la costruisce dal ProviderCatalog). */
data class ProviderChoiceUi(val id: String, val label: String, val selected: Boolean)
enum class BgLocationState { GRANTED, WHILE_IN_USE, DENIED, NOT_NEEDED }

data class ContactRow(val displayName: String, val conversationId: String)   // id in monospace, azione rimuovi

/**
 * Riga per-provider del breakdown budget. Metrica primaria: TOKEN in/out per finestra (null = "n/d",
 * distinto da 0). Il costo in dollari esiste SOLO per i provider [costTracked] (OpenAI/Anthropic/
 * Gemini): per i TOKEN-ONLY (Hermes/OpenRouter/Custom) resta null e la UI non mostra dollari.
 */
data class ProviderUsageUi(
    val providerId: String,        // ProviderId.wireName
    val providerLabel: String,     // dal ProviderCatalog, mappato nel VM
    val callsHour: Long,
    val callsDay: Long,
    val costMonthMicros: Long?,    // null = n/d; sempre null per i provider non costTracked
    val costTracked: Boolean = false,
    val tokensInHour: Long? = null,
    val tokensOutHour: Long? = null,
    val tokensInDay: Long? = null,
    val tokensOutDay: Long? = null,
    val tokensInMonth: Long? = null,
    val tokensOutMonth: Long? = null,
    val reasoningTokensMonth: Long? = null,
    val lastFinishReason: String? = null,
)

/**
 * Budget LLM aggregato cross-regola (§5.5). Limiti null = illimitato (la convenzione 0 = illimitato
 * vive solo al confine UI→VM). Costo in micro-USD; null = nessun dato costo (distinto da 0).
 * [tokensInMonth]/[tokensOutMonth] sommano i soli provider TOKEN-ONLY, la stessa base su cui il
 * [tokenLimitMonth] (tetto TOKEN mensile globale) viene applicato dalla policy.
 */
data class BudgetUi(
    val usedHour: Long = 0,
    val limitHour: Int? = null,          // null = illimitato
    val usedDay: Long = 0,
    val limitDay: Int? = null,
    val costMonthMicros: Long? = null,   // null = nessun dato costo nel mese (solo provider priced)
    val costLimitMicros: Long? = null,
    val tokensInMonth: Long? = null,     // null = n/d; solo provider token-only
    val tokensOutMonth: Long? = null,
    val tokenLimitMonth: Long? = null,   // tetto TOKEN mensile globale (token-only); null = illimitato
    val perProvider: List<ProviderUsageUi> = emptyList(),
    val softWarningActive: Boolean = false,
)

interface SettingsCallbacks {
    fun onEditBridgeUrl(url: String); fun onTestConnection()
    /** Token null/vuoto = conserva quello già configurato; non viene mai precompilato dalla UI. */
    fun onSaveBridge(url: String, bearerToken: String?) { onEditBridgeUrl(url) }
    fun onOpenShizukuFix()      // deep-link allo step onboarding giusto per lo stato corrente
    fun onOpenBatteryFix(); fun onOpenNotificationAccessFix(); fun onOpenLocationFix()
    /** Apre «Sveglie e promemoria» per concedere le sveglie esatte (0.3.6). Default no-op. */
    fun onOpenExactAlarmFix() {}
    /** Apre «Modifica impostazioni di sistema» per Argus (0.4.1: luminosità e rotazione). Default no-op. */
    fun onOpenWriteSettingsFix() {}
    /** Apre «Accesso ai dati di utilizzo» per Argus (0.5.1: regole «app aperta/chiusa»). Default no-op. */
    fun onOpenUsageAccessFix() {}
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
    /** Tetto chiamate/giorno globale. 0 = illimitato. Default no-op per implementatori esistenti. */
    fun onBudgetDayChange(maxPerDay: Int) {}
    /** Tetto costo mensile globale in micro-USD. 0 = illimitato. Default no-op. */
    fun onBudgetMonthlyCostChange(maxCostMonthMicros: Long) {}
    /** Tetto TOKEN mensile globale (provider token-only). 0 = illimitato. Default no-op. */
    fun onBudgetMonthlyTokensChange(maxTokensPerMonth: Long) {}
    /**
     * Salva insieme i quattro tetti globali (0 = illimitato): una sola scrittura, un solo messaggio.
     * È l'unica callback che il dialog dei limiti invoca.
     */
    fun onSaveBudgetLimits(
        maxPerHour: Int,
        maxPerDay: Int,
        maxCostMonthMicros: Long,
        maxTokensMonth: Long,
    ) {}
    fun onRevokePrivacy()
    fun onRerunOnboarding()
    /** «Pausa automazioni» (F21): true = nessuna regola scatta finché non si riprende. Default no-op. */
    fun onSetAutomationsPaused(paused: Boolean) {}
    /** Selettore provider: id = ProviderId.wireName. Id sconosciuti = no-op lato VM. */
    fun onSelectProvider(providerId: String) {}
    /** apiKey null = conserva la chiave esistente (pattern onSaveBridge). La chiave NON torna mai nello stato. */
    fun onSaveProviderConfig(
        providerId: String,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
        reasoningEffort: String? = null,
    ) {}

    /**
     * Backup (0.4.0): «Esporta». [stripPersonalData] = opzione «Togli posizioni e contatti (per
     * condividere)». Il file lo sceglie il chiamante con il selettore di documenti. Default no-op.
     */
    fun onExportRules(stripPersonalData: Boolean) {}

    /** Backup (0.4.0): «Importa». Il file lo sceglie il chiamante con il selettore di documenti. Default no-op. */
    fun onImportRules() {}

    /** Chiude il riepilogo (o l'errore) dell'ultima operazione di backup. Default no-op. */
    fun onDismissBackupResult() {}

    /** Dopo un import: porta all'elenco delle automazioni, dove le bozze aspettano l'approvazione. Default no-op. */
    fun onOpenImportedRules() {}

    /** 0.5.0: interruttore «Usa root (su)». Acceso, il VM verifica `su -c id`. Default no-op. */
    fun onSetRootEnabled(enabled: Boolean) {}

    /** 0.5.0: «Concedi i permessi permanenti» con le voci spuntate nel dialogo. Default no-op. */
    fun onGrantPermanent(grants: Set<PermanentGrantUi>) {}
}

// --- 6.6 Onboarding / permessi ---

data class OnboardingState(
    val steps: List<OnboardingStepState>,
    val currentIndex: Int,
    val canFinish: Boolean,     // WELCOME_PRIVACY e BRAIN_CONFIG obbligatori; il resto skippabile (degradato)
    val bridgeUrl: String? = null,               // legacy, resta per il ramo Hermes
    val bridgeTokenConfigured: Boolean = false,  // legacy, resta
    val providerChoices: List<ProviderChoiceUi> = emptyList(),
    /** Ramo transport del provider selezionato: guida quale dialog apre la CTA BRAIN_CONFIG. */
    val transport: TransportUi? = null,
    /**
     * Lista onesta di cosa dipende da Shizuku, mostrata nello step SHIZUKU: l'utente sa in anticipo
     * cosa NON potrà fare senza concederlo (task #54). Statica, derivata da ActionPrivileges.
     */
    val shizukuCapabilities: List<ShizukuCapabilityRow> = emptyList(),
    /**
     * Cervello configurato ma con connessione non (ancora) verificata. Non blocca il setup: lo step
     * Brain mostra solo l'avviso e la verifica si rifà da Sistema.
     */
    val brainUnverified: Boolean = false,
)

/**
 * Grado di dipendenza da Shizuku di una capacità (task #54). Derivato da
 * [dev.argus.engine.model.ActionPrivileges] (BASE vs PRIVILEGED) e dal comportamento reale delle
 * azioni activity-launch da automazione in background (fix #53).
 */
enum class ShizukuRequirement {
    /** Impossibile senza Shizuku: azioni PRIVILEGED (toggle radio, shell, write_setting) e lettori privilegiati. */
    REQUIRED,
    /** Degradato senza Shizuku: BASE activity-launch affidabili da background solo con `am start`. */
    RECOMMENDED,
    /** Sempre disponibile: BASE via API Android normali, nessuno Shizuku. */
    NOT_REQUIRED,
}

/**
 * Riga della lista "Cosa richiede Shizuku". [actionTypeIds] traccia i tipi azione coperti (wire name
 * da [dev.argus.engine.model.ActionTypeIds]): è la fonte di verità per i test, così la categoria
 * resta agganciata alla classificazione reale e non a stringhe libere nella UI.
 */
data class ShizukuCapabilityRow(
    val title: String,
    val requirement: ShizukuRequirement,
    val note: String? = null,
    val actionTypeIds: List<String> = emptyList(),
)

/**
 * Lista statica e onesta di cosa dipende da Shizuku, coerente con `ActionPrivileges` e
 * `AndroidCapabilityProbe`. Invariante: ogni [ActionTypeIds] compare in UNA sola riga, così la
 * categoria di un'azione è univoca (verificato a host).
 */
object ShizukuCapabilityCatalog {
    fun rows(
        language: RenderLanguage = RenderLanguage.system(),
    ): List<ShizukuCapabilityRow> = listOf(
        // --- Richiede Shizuku (PRIVILEGED): senza, impossibile ---
        ShizukuCapabilityRow(
            title = language.pick(
                "Turn Wi-Fi, Bluetooth, and mobile data on or off",
                "Attivare o disattivare Wi-Fi, Bluetooth e dati mobili",
            ),
            requirement = ShizukuRequirement.REQUIRED,
            note = language.pick(
                "Radio switches use Shizuku's privileged shell.",
                "Gli interruttori radio passano dallo shell privilegiato di Shizuku.",
            ),
            actionTypeIds = listOf(
                ActionTypeIds.SET_WIFI,
                ActionTypeIds.SET_BLUETOOTH,
                ActionTypeIds.SET_MOBILE_DATA,
            ),
        ),
        ShizukuCapabilityRow(
            title = language.pick("Write system settings", "Scrivere impostazioni di sistema"),
            requirement = ShizukuRequirement.REQUIRED,
            note = language.pick(
                "Changes to secure/global settings (settings put) are unavailable to a normal app.",
                "Modifiche a impostazioni secure/global (settings put): nessuna via da app normale.",
            ),
            actionTypeIds = listOf(ActionTypeIds.WRITE_SETTING),
        ),
        ShizukuCapabilityRow(
            title = language.pick("Switch dark/light theme", "Cambiare tema scuro/chiaro"),
            requirement = ShizukuRequirement.REQUIRED,
            note = language.pick(
                "Setting the night mode (cmd uimode night) needs Shizuku's privileged shell.",
                "Impostare la modalità notte (cmd uimode night) richiede lo shell privilegiato di Shizuku.",
            ),
            actionTypeIds = listOf(ActionTypeIds.SET_DARK_MODE),
        ),
        ShizukuCapabilityRow(
            title = language.pick("Run shell commands", "Eseguire comandi shell"),
            requirement = ShizukuRequirement.REQUIRED,
            note = language.pick(
                "adb-style commands are available only through Shizuku.",
                "Comandi in stile adb: disponibili solo tramite Shizuku.",
            ),
            actionTypeIds = listOf(ActionTypeIds.RUN_SHELL),
        ),
        ShizukuCapabilityRow(
            title = language.pick(
                "Read privileged system state",
                "Leggere stato di sistema privilegiato",
            ),
            requirement = ShizukuRequirement.REQUIRED,
            note = language.pick(
                "Foreground app, setting values, and other readers required by some rules.",
                "App in primo piano, valori delle impostazioni e altri lettori che servono a certe regole.",
            ),
        ),
        // --- Meglio con Shizuku (BASE, degrada senza) ---
        ShizukuCapabilityRow(
            title = language.pick(
                "Alarm, timer, app, URL, and Settings launches from a rule",
                "Sveglia, timer, apri app o URL e schermate impostazioni da una regola",
            ),
            requirement = ShizukuRequirement.RECOMMENDED,
            note = language.pick(
                "From a background automation these are reliable only with Shizuku. Without it, they launch only while Argus is open in the foreground.",
                "Da un'automazione in background sono affidabili solo con Shizuku. Senza, partono " +
                    "solo mentre Argus è aperto in primo piano.",
            ),
            actionTypeIds = listOf(
                ActionTypeIds.SET_ALARM,
                ActionTypeIds.SET_TIMER,
                ActionTypeIds.LAUNCH_APP,
                ActionTypeIds.OPEN_URL,
                ActionTypeIds.OPEN_SETTINGS_SCREEN,
            ),
        ),
        // --- Non richiede Shizuku (BASE, sempre disponibile) ---
        ShizukuCapabilityRow(
            title = language.pick(
                "Volume, ringer, and Do Not Disturb",
                "Volume, suoneria e Non disturbare",
            ),
            requirement = ShizukuRequirement.NOT_REQUIRED,
            note = language.pick(
                "Ringer and Do Not Disturb only need one-time Do Not Disturb access, not Shizuku.",
                "Per suoneria e Non disturbare basta concedere una volta l'accesso «Non disturbare», non Shizuku.",
            ),
            actionTypeIds = listOf(
                ActionTypeIds.SET_VOLUME,
                ActionTypeIds.SET_RINGER,
                ActionTypeIds.SET_DND,
            ),
        ),
        ShizukuCapabilityRow(
            title = language.pick("Flashlight and vibration", "Torcia e vibrazione"),
            requirement = ShizukuRequirement.NOT_REQUIRED,
            actionTypeIds = listOf(ActionTypeIds.SET_FLASHLIGHT, ActionTypeIds.VIBRATE),
        ),
        ShizukuCapabilityRow(
            title = language.pick(
                "Read aloud, media keys and HTTP requests",
                "Lettura ad alta voce, tasti multimediali e richieste HTTP",
            ),
            requirement = ShizukuRequirement.NOT_REQUIRED,
            actionTypeIds = listOf(
                ActionTypeIds.SPEAK,
                ActionTypeIds.MEDIA_CONTROL,
                ActionTypeIds.HTTP_REQUEST,
            ),
        ),
        ShizukuCapabilityRow(
            title = language.pick("Screen brightness and rotation", "Luminosità e rotazione dello schermo"),
            requirement = ShizukuRequirement.NOT_REQUIRED,
            note = language.pick(
                "They need the \"Modify system settings\" access, which you allow in Android's settings.",
                "Serve l'accesso «Modifica impostazioni di sistema», che concedi nelle impostazioni di Android.",
            ),
            actionTypeIds = listOf(ActionTypeIds.SET_BRIGHTNESS, ActionTypeIds.SET_ROTATION),
        ),
        ShizukuCapabilityRow(
            title = language.pick(
                "Argus notifications and clipboard copy",
                "Notifiche di Argus e copia negli appunti",
            ),
            requirement = ShizukuRequirement.NOT_REQUIRED,
            actionTypeIds = listOf(
                ActionTypeIds.SHOW_NOTIFICATION,
                ActionTypeIds.COPY_TO_CLIPBOARD,
                ActionTypeIds.COPY_TEXT,
            ),
        ),
        ShizukuCapabilityRow(
            title = language.pick(
                "Alarms and timers started manually from chat",
                "Sveglia e timer avviati a mano dalla chat",
            ),
            requirement = ShizukuRequirement.NOT_REQUIRED,
            note = language.pick(
                "When requested in chat (app in the foreground), they always start without Shizuku.",
                "Quando li chiedi in chat (app in primo piano) partono sempre, senza Shizuku.",
            ),
        ),
    )
}

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
    /** Selettore provider: id = ProviderId.wireName. Id sconosciuti = no-op lato VM. */
    fun onSelectProvider(providerId: String) {}
    /** apiKey null = conserva la chiave esistente (pattern onSaveBridge). La chiave NON torna mai nello stato. */
    fun onSaveProviderConfig(
        providerId: String,
        baseUrl: String?,
        model: String?,
        apiKey: String?,
        reasoningEffort: String? = null,
    ) {}
}

// --- 0.5.0 · Privilegi: backend, root `su`, permessi permanenti ---

/** Chi fornisce ORA i comandi privilegiati (specchio di `PrivilegedBackend`, che la UI non conosce). */
enum class PrivilegedBackendUi { NONE, SHIZUKU, SHIZUKU_SHELL, SHIZUKU_ROOT, SUI, ROOT }

/** Stato del backend root (specchio di `RootState`). */
enum class RootStateUi { OFF, CHECKING, ACTIVE, DENIED, NO_SU }

/**
 * Permessi che Argus si concede da sé con un backend privilegiato. L'accesso alle notifiche NON è qui:
 * resta la schermata di sistema (è il permesso più delicato).
 */
enum class PermanentGrantUi(val id: String) {
    WRITE_SECURE_SETTINGS("write_secure_settings"),
    WRITE_SETTINGS("write_settings"),
    USAGE_STATS("usage_stats"),
    DND_ACCESS("dnd_access"),
}

data class PermanentGrantRow(val grant: PermanentGrantUi, val granted: Boolean)

data class PrivilegedUi(
    val backend: PrivilegedBackendUi = PrivilegedBackendUi.NONE,
    val rootState: RootStateUi = RootStateUi.OFF,
    /** Una riga per voce, nell'ordine di [PermanentGrantUi]; vuota = non ancora letta (la sezione la nasconde). */
    val grants: List<PermanentGrantRow> = emptyList(),
) {
    /** Il bottone «Concedi» si offre solo con un backend privilegiato attivo e qualcosa da concedere. */
    val canGrant: Boolean
        get() = backend != PrivilegedBackendUi.NONE && grants.any { !it.granted }

    val rootEnabled: Boolean get() = rootState != RootStateUi.OFF
}
