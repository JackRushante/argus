# Argus — Handoff Frontend per Claude Design (rev 1, 2026-07-12)

> Complemento operativo di `spec-argus-design-rev3.md` §12. **Questo è il documento di lavoro per la UI**: la spec dà il contesto di sistema, qui ci sono navigazione, contratti di stato, comportamenti, stati d'errore e direttive di sicurezza UI. In caso di conflitto tra i due, vale questo documento.

## 1. Contesto in breve

Argus è un'app Android **personale, sideloaded** (OnePlus 15, OxygenOS, min SDK 30) di automazione stile Tasker in cui **l'LLM compila le regole via chat** e un motore deterministico le esegue. L'utente è uno solo, tecnico. Lingua UI: **italiano**.

Flusso cardine: *chat → l'LLM propone una regola (draft) → schermata di approvazione → regola armata → scatta da sola → log*.

**Cosa produce Claude Design:** visual design + componenti **Jetpack Compose stateless** cablati contro i contratti di stato di questo documento (§6). Niente ViewModel, niente logica, niente networking: ogni schermo è `fun XxxScreen(state: XxxState, callbacks: XxxCallbacks)` + `@Preview` con dati fake realistici (inclusi stati error/empty/degradati). Theming Material 3 con token centralizzati.

## 2. Baseline di design

- **Material 3**, dynamic color con fallback a palette custom; **dark theme default** (app da smanettoni, usata anche di notte), light supportato.
- Telefono only, portrait-first (landscape best-effort, nessun layout dedicato).
- Densità informativa medio-alta: l'utente è esperto, preferisce dati a decorazione. Niente onboarding marketing, niente illustrazioni riempitive.
- Icone Material Symbols. Monospace (`FontFamily.Monospace`) per: comandi shell, cron, JSON, id tecnici.
- Accessibilità: contrasti AA, touch target 48dp — standard, nessun requisito extra.

## 3. Navigazione

**Bottom navigation bar, 4 destinazioni:**

| Tab | Schermo | Badge |
|-----|---------|-------|
| 💬 Chat | §6.1 | pallino se risposta arrivata mentre si era altrove |
| ⚙️ Automazioni | §6.2 lista → push §6.3 dettaglio | count regole `pending_approval` |
| 📜 Log | §6.4 | — |
| 🛠 Sistema | §6.5 settings | warning se salute degradata (Shizuku giù, ecc.) |

- **Onboarding (§6.6)**: flusso fullscreen al primo avvio; rientrabile da Sistema → "Ripeti configurazione". Skippabile step-per-step (l'app funziona degradata).
- **Dettaglio/Approvazione (§6.3)**: schermata push raggiungibile da (a) card draft in chat, (b) riga in lista, (c) notifica.
- **Regola d'oro**: l'**arm avviene solo nel Dettaglio**, mai inline in chat — l'utente deve avere davanti la regola completa renderizzata quando arma (vedi §5).

## 4. Vincolo di prodotto: niente streaming nell'MVP

Il Brain primario (CliBridge/Hermes) è **one-shot con latenza 10-30 s, senza streaming e senza tool-call progressivi**. Lo streaming arriva solo in P3 (transport OpenAICompat). Quindi:

- La chat va progettata attorno a uno **stato di attesa lungo e onesto**: indicator "Argus sta pensando…" con **tempo trascorso visibile** (es. "12 s — di solito 10-30 s") e bottone **Annulla**.
- Niente skeleton di token in arrivo, niente cursore lampeggiante da streaming.
- Input disabilitato durante l'attesa (una richiesta alla volta), con la richiesta in corso visibile.
- Prevedere (senza implementarlo ora) che in P3 il medesimo layout possa ospitare messaggi in streaming e card tool-call: non incastrare il design su "un blocco alla volta".

## 5. Direttive di sicurezza UI (vincolanti)

1. **La regola si mostra dai dati, mai dalla prosa.** La card/schermata di approvazione renderizza `RuleRender` (derivato deterministicamente dai tipi `Automation`). La spiegazione testuale dell'LLM (`rationale`) è mostrata come **commento subordinato** (stile citazione, etichetta "descrizione del modello"), perché potrebbe non corrispondere alla regola reale.
2. **ERROR di validazione bloccano l'arm**: bottone "Arma" disabilitato con motivo visibile (`armBlockedReason`). I WARNING non bloccano ma sono sempre visibili sopra la fold, non collassati.
3. **`run_shell` è sempre in monospace, integrale, mai troncato** (scroll orizzontale se serve), con badge "esegue comandi con privilegi shell". Se il comando cambia, la regola torna in approvazione.
4. **Badge tier**: le regole con `InvokeLlm` portano ovunque (lista, dettaglio, log) un badge "generativa 🤖" + badge privacy "i contenuti escono verso Hermes/cloud" (spec E11).
5. **Warning combinazioni sensibili** (dal validator, code `read_plus_reply`): evidenza visiva dedicata — "questa regola legge dati dal dispositivo e li può inviare al mittente".
6. **Mai bottoni di conferma pre-focusati** su azioni irreversibili (elimina regola, esegui ora).

## 6. Schermi e contratti di stato

I contratti sono Kotlin (UI-layer). Derivano dai tipi di `engine-core` (piano P0-A) ma sono già formattati per la view: stringhe pronte, niente logica nel Composable. `Severity`, `AuditKind`, `ValidationIssue` sono i tipi di engine-core.

### Tipi condivisi

```kotlin
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
```

### 6.1 Chat

```kotlin
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
}
```

Comportamenti: empty state = suggerimenti d'esempio tappabili (i 3 esempi della spec); `MalformedReply` è un evento **atteso** (LLM sbaglia il JSON) → tono non catastrofico, CTA "riprova"; la DraftCard resta nello storico con status aggiornato (APPROVED/REJECTED) dopo il passaggio in Dettaglio; SUPERSEDED quando una nuova proposta la sostituisce.

### 6.2 Automazioni · lista

```kotlin
data class AutomationListState(
    val rows: List<AutomationRow>,
    val filter: StatusFilter,               // ALL | ARMED | PENDING | DISABLED | NEEDS_REVIEW (chips)
    val banner: EngineBanner,
    val loading: Boolean,
)

data class AutomationRow(
    val id: String,
    val name: String,
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
}
```

Comportamenti: empty state = "Nessuna automazione — chiedila in chat" con CTA che cambia tab; le `PENDING_APPROVAL` ordinate in cima con evidenza; `NEEDS_REVIEW` (migrazione schema fallita, spec E8) con icona dedicata e spiegazione nel dettaglio; il banner `EngineBanner` è persistente e tappabile → Sistema.

### 6.3 Automazione · dettaglio / approvazione

Stesso schermo per review (status PENDING) e ispezione (ARMED/DISABLED/NEEDS_REVIEW); cambia il footer.

```kotlin
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
)

interface AutomationDetailCallbacks {
    fun onArm(); fun onReject()             // footer in stato PENDING
    fun onSetEnabled(enabled: Boolean); fun onDelete()   // footer ARMED/DISABLED (delete con conferma, §5.6)
    fun onAskEdit()                          // torna in chat con contesto ("modifica questa regola…")
    fun onRunNow()                           // test manuale: esegue le azioni ORA saltando trigger/condizioni (conferma prima)
    fun onOpenFullLog()
}
```

Comportamenti: header = nome + badge status + badge generativa/privacy; corpo = RuleRender a sezioni (Quando / Solo se / Allora); warning ERROR con stile bloccante e `canArm=false`; "Esegui ora" richiede conferma e mostra l'esito come toast + entry log; per i geofence con `resolveCurrentLocation` mostrare `geofencePreviewLabel` al posto delle coordinate.

### 6.4 Log esecuzioni

```kotlin
data class ExecutionLogState(
    val entries: List<LogRow>,
    val filterAutomationName: String?,      // null = tutte; settato quando si arriva dal dettaglio
    val loading: Boolean,
)

data class LogRow(
    val id: String,
    val timeLabel: String,                  // "oggi 23:00"
    val automationName: String,
    val kind: AuditKind,                    // FIRED | SUPPRESSED_COOLDOWN | CONDITIONS_NOT_MET | ERROR
    val outcome: LogOutcome,                // solo per FIRED
    val summary: String,                    // "2/2 azioni ok" / "soppressa (cooldown)" / "risposta inviata a Moglie"
    val expandedDetail: List<String>?,      // righe per-azione al tap (incl. esito lane generativa, anche DEFERRED E13)
)

enum class LogOutcome { SUCCESS, PARTIAL, FAILED, SUBMITTED, DEFERRED }
// SUBMITTED = generativa in corso; DEFERRED = "risposta pronta, consegna manuale" (spec E13)

interface ExecutionLogCallbacks { fun onExpand(id: String); fun onClearFilter(); fun onOpenAutomation(id: String) }
```

Comportamenti: timeline raggruppata per giorno; `SUPPRESSED_COOLDOWN`/`CONDITIONS_NOT_MET` visivamente attenuate (sono rumore utile, non eventi); `ERROR` e `FAILED` in rosso con dettaglio; `DEFERRED` con CTA "invia ora" (riapre il flusso E13).

### 6.5 Sistema (settings)

```kotlin
data class SettingsState(
    val transport: TransportUi,
    val shizuku: ShizukuStatus,
    val batteryExempt: Boolean,
    val notificationAccess: Boolean,
    val backgroundLocation: BgLocationState,   // GRANTED | WHILE_IN_USE | DENIED | NOT_NEEDED (nessuna regola geofence)
    val whitelist: List<ContactRow>,
    val budget: BudgetUi,
    val privacyAccepted: Boolean,
    val appVersionLabel: String,
)

sealed interface TransportUi {
    data class CliBridge(val url: String, val reachable: Boolean?, val lastLatencyLabel: String?) : TransportUi
    data class OpenAICompat(val baseUrl: String, val model: String, val authState: AuthState) : TransportUi  // P3, mostrare come "in arrivo"
}
enum class AuthState { OK, EXPIRED, NOT_CONFIGURED }
enum class BgLocationState { GRANTED, WHILE_IN_USE, DENIED, NOT_NEEDED }

data class ContactRow(val displayName: String, val conversationId: String)   // id in monospace, azione rimuovi
data class BudgetUi(val maxCallsPerHour: Int, val usedThisHourLabel: String) // "3/20 quest'ora"

interface SettingsCallbacks {
    fun onEditBridgeUrl(url: String); fun onTestConnection()
    fun onOpenShizukuFix()      // deep-link allo step onboarding giusto per lo stato corrente
    fun onOpenBatteryFix(); fun onOpenNotificationAccessFix(); fun onOpenLocationFix()
    fun onRemoveContact(conversationId: String); fun onAddContact()   // picker → risoluzione conversationId
    fun onBudgetChange(maxPerHour: Int)
    fun onRerunOnboarding()
}
```

Comportamenti: sezione "Salute" in cima con le 4 righe stato (Shizuku / batteria / notifiche / posizione), ognuna verde o con CTA fix; "Test connessione" mostra latenza misurata ("ok, 14 s — normale per Hermes"); whitelist con spiegazione del perché esiste (spec §10.3).

### 6.6 Onboarding / permessi

```kotlin
data class OnboardingState(
    val steps: List<OnboardingStepState>,
    val currentIndex: Int,
    val canFinish: Boolean,     // WELCOME_PRIVACY e BRAIN_CONFIG obbligatori; il resto skippabile (degradato)
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

interface OnboardingCallbacks { fun onStepCta(kind: StepKind); fun onSkip(kind: StepKind); fun onNext(); fun onBack(); fun onFinish() }
```

- **WELCOME_PRIVACY** (obbligatorio): informativa E11 in linguaggio piano — "il testo delle notifiche e ciò che chiedi in chat viaggia verso Hermes (tuo server) e da lì verso provider cloud (OpenAI/Nous/…)" — con consenso esplicito.
- **BRAIN_CONFIG** (obbligatorio): URL bridge Hermes precompilato (`http://100.80.142.65:8090`), bottone test.
- **SHIZUKU**: lo step ha 4 sotto-stati che riusano `ShizukuStatus` (§ tipi condivisi) con copy dedicato (§9). Su device rootato mostrare la via "avvio automatico al boot via root".
- **BATTERY_OEM**: spiegare la conseguenza reale del rifiuto: "senza, le risposte AI in background falliranno spesso e il servizio può essere ucciso" (spec §9/§15-P1).
- **BACKGROUND_LOCATION**: richiesto solo quando esiste (o si sta per armare) una regola geofence — lo step in onboarding è presentato come opzionale/futuro (`NOT_NEEDED`).

## 7. Superfici di notifica (fuori app)

Da progettare come template (mock statici, non Compose):

1. **Notifica persistente FGS** (P2, minima priorità): "Argus attivo — N regole armate". Stato degradato: "Argus — Shizuku non attivo, azioni shell in pausa" (tap → Sistema).
2. **Conferma live** (catalogo sempre-conferma, spec §10.2): "La regola 'X' vuole: disinstallare com.foo" + azioni **Consenti / Nega** + timeout dichiarato ("scade tra 60 s, poi = Nega").
3. **Consegna differita E13**: "Risposta pronta per Moglie — il messaggio non è più rispondibile automaticamente. Tocca per inviare." (tap → apre WhatsApp sulla chat, testo in clipboard, snackbar di conferma).
4. **Regola in pausa** (capability persa, spec E5): "'X' in pausa: manca l'accesso alle notifiche" (tap → fix in Sistema).

## 8. Componenti condivisi (libreria minima)

- `StatusBadgeChip(status: StatusBadge)` — colori semantici coerenti ovunque.
- `RuleCard(rule: RuleRender, compact: Boolean)` — variante compatta (chat/lista) ed estesa (dettaglio).
- `ActionRowItem(row: ActionRow)` — gestisce shell monospace, badge generativa, badge conferma-live.
- `WarningBanner(warning: UiWarning)` — ERROR bloccante vs WARNING informativo.
- `EngineBannerBar(banner: EngineBanner)` — banner salute persistente.
- `LatencyIndicator(elapsedSec: Int, expectedRangeLabel: String, onCancel)` — l'attesa one-shot (§4).
- `EmptyState(icon, title, body, cta?)`.

## 9. Microcopy — tono e esempi

Tono: asciutto, tecnico ma umano, mai allarmistico, sempre con la conseguenza pratica. Esempi vincolanti per gli stati Shizuku:

| Stato | Copy |
|-------|------|
| NOT_INSTALLED | "Shizuku non è installato. Serve per dare ad Argus i privilegi shell (come adb)." CTA: "Scarica Shizuku" |
| INSTALLED_NOT_RUNNING | "Shizuku è installato ma non in esecuzione. Avvialo (via root parte da solo al boot)." CTA: "Apri Shizuku" |
| RUNNING_NOT_AUTHORIZED | "Shizuku è attivo ma Argus non è autorizzato." CTA: "Richiedi autorizzazione" |
| AUTHORIZED | "Shizuku attivo — privilegi shell disponibili." |
| DEGRADED_AFTER_REBOOT | "Dopo il riavvio Shizuku è spento. Le azioni shell sono in coda; il resto funziona." CTA: "Riattiva" |

Errori chat: "Hermes non risponde (timeout 60 s). Il server potrebbe essere occupato — riprova." / "La risposta non conteneva una regola valida. Riprova o riformula." (+ dettaglio tecnico espandibile).

## 10. Cosa NON progettare (YAGNI / fasi successive)

- Streaming chat, card tool-call progressive, vista computer-use (P3).
- Editor visuale/drag-drop di regole (le regole si modificano ri-chiedendo in chat).
- Multi-profilo, multi-lingua, tablet/foldable layout dedicati.
- Store/marketplace di regole, condivisione.
- Schermata budget avanzata (P3): in MVP il budget è una riga in Sistema.

## 11. Checklist di consegna

- [ ] Tema M3 (dark+light) con token; tipografia; palette semantica status/severity.
- [ ] 6 schermi Compose stateless contro i contratti §6, ognuno con preview: stato pieno, empty, error, degradato (banner), e per il Dettaglio: PENDING con ERROR bloccante, PENDING pulita, ARMED.
- [ ] Componenti condivisi §8 come composable riusabili.
- [ ] Mock statici delle 4 superfici di notifica §7.
- [ ] Nessuna dipendenza oltre Compose/Material3; nessun accesso a rete/DB; callback come interfacce.
