# Changelog

Patch notes per ogni commit, dal più recente al più vecchio. Generato dalla storia git del progetto.

## `87c1e9502` — 2026-07-17

**feat(#50 S15 + fix campo): usage Hermes end-to-end + afterMs schedula EXACT di default**

Fix campo (2026-07-17): un "tra 3 minuti" (afterMs, precision FLEXIBLE -> INEXACT) e' scattato
con +2m33s di batching OEM su OnePlus; l'utente ha creduto la regola morta e l'ha cancellata.
Un ritardo relativo esplicito e' puntuale per natura: TimeAlarmPlanner.effectivePrecision ora
tratta afterMs come EXACT (fallback inexact senza permesso exact-alarm). Politica di SCHEDULING,
non parte del trigger: fingerprint invariato. Allineato anche il recoveryRecord di deliverLocked.

S15 lato Kotlin (chiude il filo del commit 9c39863 lato bridge):
- CompileResult.usage: TurnUsage? (engine-core, additivo).
- CliBridgeTransport: BridgeUsageEnvelope opzionale negli envelope /compile e /act (parser
  resta strict: il bridge sanitizza a un set chiuso di chiavi), mappato fail-closed in TurnUsage.
  L'usage viaggia anche sugli esiti senza draft: il turno e' costato comunque.
- MeteredBrain.compile registra result.usage (prima: null fisso -> token/costo N/D in budget UI).

Ordine di deploy rispettato: app col campo dichiarato PRIMA del bridge che lo invia.

TDD (RED verificati) + full gate --rerun-tasks 154 task, 0 fail.

## `00d62e5e4` — 2026-07-17

**feat(#31-B): audit lifecycle regole — traccia di chi/quando mette e toglie un'automazione**

Motivazione (Lorenzo): regole sparite dall'inventario senza alcuna traccia; e in campo il log
non mostrava "creata" alla creazione di una regola. #31-A copriva solo i fallimenti; questo
completa il ciclo di vita RIUSCITO.

Nuovi AuditKind (engine-core) + reason a vocabolario CHIUSO (redazione in RoomAuditSink,
pattern SCHEDULING_REASONS — reason fuori set -> token generico, mai testo libero, zero PII):
- RULE_ARMED("approval") — unico funnel: ApprovalFlow.finalizeRegistration, PRIMA del registrar
  per l'ordine cronologico con l'eventuale disable immediato.
- RULE_DISABLED("user" | "one_shot_consumed" | "expired") — EnablementCoordinator (manuale),
  TimeAlarmCoordinator.deliverLocked + registerImmediate (one-shot post-fire), reconcile EXPIRED.
- RULE_ENABLED("user") — solo a esito finale riuscito (il rollback resta col solo ENABLE_FAILED).
- RULE_DELETED("user") — AutomationDetailViewModel.onDelete (unico caller di store.delete).
- RULE_NEEDS_REVIEW("fire_policy" | "capability_lost" | "validation_failed" |
  "requirements_changed" | "planner_failed") — i 3 call-site di markNeedsReviewIfApproved,
  emesso SOLO se la transizione è avvenuta davvero.

Tutti gli innesti col pattern non-fatale standard (il logging non cambia mai l'esito).
UI: etichette italiane in UiStateMappers + icone log/detail. DI: AuditSink iniettato in
CapabilityReconciler e AndroidArmedAutomationRegistrar.

Non tracciato (follow-up di prodotto): quarantena read-path di RoomAutomationStore.toDomain
(markNeedsReview su ogni lettura, senza idempotenza: spammerebbe un evento per read).
Retention: già esistente (AuditDao.trim, 2000 righe/30gg via RoomJournalMaintenance).

Gate indipendente --rerun-tasks: engine-core 230, data 68, automation-android 400, ui 36,
brain-android + app:compileDebugKotlin — BUILD SUCCESSFUL, 0 fail.

## `9c39863ec` — 2026-07-17

**feat(#50 S15): bridge allega l'usage reale di Hermes a /compile e /act + hardening fail-closed**

S15: run_gpt passa --usage-file alla CLI hermes e ritorna (output, usage). Le risposte 200 di
/compile e /act includono un campo "usage" OPZIONALE (mai null esplicito: le app con parser
strict lo dichiareranno nullable) col subset chiuso sanitizzato del report reale:
input/output/total_tokens, api_calls, model, provider, cost_status, estimated_cost_usd.
Schema catturato live dalla CLI (cost_status="included" sul piano Codex -> costo 0).
_sanitize_usage e' fail-closed: forma sospetta -> campo omesso; l'usage e' best-effort e non
puo' mai far fallire una risposta buona (temp file sempre ripulito).

NOTA DEPLOY: NON deployato su hermes. Ordine obbligato: prima l'app (CliBridgeTransport ha
ignoreUnknownKeys=false: un campo nuovo non dichiarato romperebbe il parse), poi il bridge.
Lato Kotlin (envelope + TurnUsage + MeteredBrain.compile) in arrivo.

Fix colti al passaggio:
- validate_act_request: type-check degli elementi PRIMA del dedup set() — una lista annidata
  in context_sources causava TypeError invece del 400 pulito (test fail-closed ora verde).
- mock runner dei test HTTP: firma allineata a run_gpt(prompt, tools=...) — i 2 test /act
  fallivano con 502 spurio dall'aggiunta del kwarg tools (web).
Suite bridge: 36/36 verdi (prima: 29/32).

## `b644889b3` — 2026-07-17

**feat(#62): trigger a ritardo relativo (time.afterMs) — "tra N" ri-armabile**

Follow-up #61. "notificami tra 2 minuti" era compilato come time.at assoluto: ri-armando
la regola il conto NON ripartiva (istante ormai passato). Ora "tra N" -> time.afterMs
(ritardo RELATIVO in ms), ancorato all'arm e quindi ri-armabile.

Design: campo afterMs: Long? su Trigger.Time (NON un nuovo sottotipo sealed), cosi riusa
al 100% la macchina d'allarme (reconcile/recovery/exact-inexact) senza toccare i when
esaustivi altrove. @EncodeDefault(NEVER): quando null e' OMESSO dal wire -> i Time cron/at
restano byte-identici e i fingerprint v1 pinnati non si rompono. Vincolo: esattamente uno
tra {cron, at, afterMs}.

Ancora congelata: afterMs si risolve a now+afterMs UNA volta all'arm e si congela sul
ScheduledTimeAlarm persistito (TimeAlarmPlanner.next(..., existing)); senza freeze ogni
reconcile (APP_START/BOOT) ricalcolerebbe now+afterMs slittando in avanti. One-shot: fira
una volta e si auto-disabilita (Trigger.Time.isOneShot() = at != null || afterMs != null);
il re-arm cancella il record e ne calcola uno nuovo -> il countdown riparte. Reboot: l'ancora
persistita sopravvive -> scatta al target originale (come un timer/allarme).

- engine-core: Trigger.afterMs + isOneShot(); TimeSpecs.nextFire ramo afterMs; DraftValidator
  esattamente-uno + bound 1s..7g (MIN/MAX_DELAY_MS).
- automation-android: TimeAlarmCoordinator freeze ancora + one-shot via isOneShot(); GenerativeActionLane.
- ui: RuleRenderMapper "Una volta, tra <umanizzato>".
- brain-android + ops/hermes/bridge.py: compile prompt "tra N"->afterMs; validate_trigger accetta
  afterMs (bound 1s..7g). Bridge deployato su hermes.

Verificato: full gate 4 moduli --rerun-tasks verde (incl. V1FingerprintCompatibilityTest byte-stabile);
suite bridge +1 test afterMs. Live E2E su /compile deployato: "tra 3 minuti"->afterMs=180000,
"ogni giorno alle 8"->cron "0 8 * * *", "subito"->immediate. APK assemblato e installato.

## `c5bfd9a09` — 2026-07-17

**fix(#61): compile — disambigua trigger (subito/tra N/ogni N) + abilita ricorrenti generativi**

Hermes compilava "notificami tra 2 minuti" come trigger "immediate" (0 ritardo, scatta all'arm, non
ri-armabile). Verificato dal DB device: trigger={"type":"immediate"} su una regola "tra 2 minuti".

Fix compile prompt (app regola 15/16 + bridge 13/14): scelta trigger netta —
- immediate SOLO per "subito/adesso" (0 ritardo);
- time.at per "tra N minuti/ore" (at = ora+N, formato ISO locale SENZA offset/Z: LocalDateTime.parse
  dell'app rifiuta "+02:00");
- time.cron per RICORRENTI ("ogni 24 ore", "ogni settimana") -> il sink ri-genera ad ogni fire.
Sblocca i casi reali di Lorenzo: "ogni 24 ore il prezzo BTC", "ogni settimana il risultato del Milan".

Verificato live via Hermes: "tra 2 min"->time.at "2026-07-17T18:52" (no offset); "ogni 24 ore"->
time.cron "0 18 * * *"; "subito"->immediate. Bridge deployato + repo sync.

TODO follow-up (#61): trigger a ritardo-relativo per "tra N" ri-armabile (oggi time.at e' assoluto,
il re-arm non fa ripartire il conto alla rovescia; i ricorrenti cron invece ripartono).

## `e42b7fc76` — 2026-07-17

**feat(#31): audit log più ricco — arm/scheduling/validazione/abilitazione falliti**

Oggi l'audit registrava solo eventi fire-time (FIRED/SUPPRESSED_*/...); i fallimenti di arm non erano
persistiti da nessuna parte (ci ha rallentati diagnosticando la sveglia che non si armava). Ora ci sono.

- AuditSink: 4 nuovi kind VALIDATION_REJECTED, ARM_FAILED, SCHEDULING_FAILED, ENABLE_FAILED.
- Emissione (AuditSink iniettato dove il reason e' noto, nessun cambio di signature Boolean):
  ApprovalFlow -> VALIDATION_REJECTED (join dei ValidationIssue.code) e ARM_FAILED (registrar_failed);
  TimeAlarmCoordinator -> SCHEDULING_FAILED (expired/dispatch_failed/reschedule_failed/scheduling_failed);
  AutomationEnablementCoordinator -> ENABLE_FAILED (review_required/scheduling_failed).
- Privacy: RoomAuditSink.redactedDetail estesa a VOCABOLARIO CHIUSO per i nuovi kind (code snake_case
  ^[a-z][a-z0-9_]{0,63}$ bounded per la validazione; set fissi per scheduling/enable). Testo libero
  (notifiche/SMS/goal) MAI nel detail. FIRED/SUPPRESSED invariati.
- UI: UiStateMappers.auditSummary etichette IT + toLogOutcome -> FAILED; ExecutionLog/AutomationDetail
  screen -> icona rossa. I nuovi motivi compaiono automaticamente nel tab Log.

Test: RoomStore (redazione), ApprovalFlow (ARM/VALIDATION), EnablementCoordinator (ENABLE),
TimeAlarmCoordinator (SCHEDULING), UiStateMappers. 1318 testcase, full gate verde.

## `cab3eb68d` — 2026-07-17

**fix(#41): health check per contenimento invece di uguaglianza esatta delle liste schema**

Un redeploy del bridge che AGGIUNGE una schema version (es. annuncia [1,2,3]) faceva cadere offline
le app vecchie che pinavano esattamente [1,2] (uguaglianza di lista Kotlin = contenuto+ordine esatti).

Fix in CliBridgeTransport.health(): compatibilita' per CONTENIMENTO — il bridge e' compatibile se
ANNUNCIA le versioni che l'app usa (COMPILE_SCHEMA_VERSION in compileSchemaVersions; ACT + ACT_V2 in
actSchemaVersions), non se le liste sono identiche. health.schemaVersion accettato se >= (non !=).
Cosi' un redeploy che estende il contratto non rompe le app esistenti; un bridge che TOGLIE una
versione usata resta correttamente incompatibile. Lo SHA era gia' solo format-checked (regex), nessun
cambio li'.

Test: [1,2,3] (superset) ora ACCETTATO (nuovo test forward-compat); [1] senza la v2 usata, sha
malformato, campo sconosciuto restano rifiutati. Doc hermes-bridge-contract aggiornato. Bridge non cambia.

## `e63ea9a7a` — 2026-07-17

**fix(#60): sink-notifica esegue davvero — race one-shot + timeout web**

Il sink compilava+armava+scattava ma l'azione invoke_llm falliva (Log: "rule inactive", "act timeout").

BUG 2 (principale) — race del one-shot generativo: un trigger one-shot (immediate, o time con `at`) si
disabilita appena dispatchato, ma la lane generativa processa async (brain call di secondi); alla
ri-validazione trovava status!=ARMED -> "rule_inactive". Fix in GenerativeActionLane.validate: check
fingerprint+azione PRIMA dello stato, poi stato tollerante al one-shot consumato (isOneShot = Immediate
o Time.at!=null). Sicuro: ApprovalFingerprints.of normalizza status/enabled -> un one-shot consumato ha
lo STESSO fingerprint; un edit non ri-approvato resta approval_changed; cancellata resta rule_missing.
Reply (Notification) e ricorrenti cron disabilitate restano rule_inactive (invariate).

BUG 1 — "act timeout": la ricerca web via Hermes ha latenza variabile. Fix: compile prompt setta
timeoutMs=120000 per invoke_llm con web.search; CliBridgeTransport.defaultClient 60s->125s (HTTP >=
timeout azione max 120s); bridge MODEL_TIMEOUT 55->90s (deployato).

Test: +5 lane (one-shot immediate/time-at completano da disabilitati; cron ricorrente resta inactive;
fingerprint cambiato->approval_changed; cancellata->rule_missing) + CliBridge timeout 125s. Full gate verde.

## `e27fbbf36` — 2026-07-17

**feat(#59 O4b): compile prompt + bridge — sink-notifica ora SUPPORTATO end-to-end**

Quarta ondata (parte compile+bridge): il modello ora compila "mandami una notifica con X" invece di
rifiutarlo, e il bridge /act genera il testo senza notifica in arrivo.

- AgentMessageSupport (app compile prompt): regola 16 da "rifiuta" a due modi di deliver
  (WHATSAPP_REPLY | LOCAL_NOTIFICATION). DRAFT_SCHEMA_TEXT invoke_llm: + deliver + notificationTitle.
- bridge.py (deployato + repo sync):
  - _valid_notification_toolset (mirror isNotificationToolset).
  - validate_act_request v1: branch reply vs sink. Sink (toolset senza whatsapp_reply) accetta
    context_sources []/[state] (mai notification) e context = {state} soltanto (notifica ASSENTE);
    reply invariato.
  - validate_action invoke_llm: campi deliver/notificationTitle ammessi; LOCAL_NOTIFICATION validato
    (notification toolset, replyTargetSender false, titolo <=120, no "notification" in sources).
  - Regola 14 di compile: da rifiuto a due modi (mirror app regola 16). Schema invoke_llm aggiornato.
  - build_act_prompt invariato: produce {"reply_text":...} che l'app mappa sulla consegna (notifica).

Smoke validatori bridge 11/11 OK. Bridge attivo (source 215d519c757a). Full gate brain verde.
Prossimo: APK + test live via Hermes (armo una regola sink e verifico la notifica).

## `9a927ee44` — 2026-07-17

**feat(#59 O4a): path act SENZA notifica per il sink da timer (Kotlin)**

Quarta ondata (parte Kotlin): il sink-notifica da trigger time/immediate non ha una notifica WhatsApp
in arrivo. Reso opzionale il contesto notifica in tutto il path act.

- AgentMessageSupport: requireGenerativeContextSources(sources, useReplyTool) — sink accetta [] o
  subset {state}, mai "notification". Nuovi prompt SENZA framing WhatsApp: actSystemTextNotification
  (genera il testo di una notifica) + actUserTextNotification (state o "Genera ora..."). Reply invariato.
- OpenAICompat/Anthropic act(): branch useReplyTool. Sink -> notification=null, niente
  requireWhatsAppNotification, prompt notifica. generate()/generateViaResponses/generateViaGeminiNative
  ora accettano notification nullable. Reply invariato.
- CliBridgeTransport act(): sink -> envelope con context.notification ASSENTE (ActContextEnvelope.
  notification nullable @EncodeDefault(NEVER)), context_sources []/[state]. Reply-envelope BYTE-INVARIANTE
  (lockato da test).

Contratto wire sink /act (il bridge O4b lo implementa uguale): context.notification omessa, context.state
null/oggetto, context_sources []/[state], allowed_tools []/[web.search], nessun requisito NotificationPosted.

Test: +sink su TimeFired (prompt NOTIFICA non-WhatsApp, rifiuto "notification"); reply byte-identico. Full gate verde.

## `5dd10108f` — 2026-07-17

**feat(#59 O3): transport plain-path per il sink-notifica (niente reply tool)**

Terza ondata: quando allowedTools non contiene whatsapp_reply (sink notifica), i transport producono
testo PLAIN invece di forzare il reply tool.

- AgentMessageSupport.requireGenerativeToolset: accetta isAllowedToolset || isNotificationToolset e
  ritorna useReplyTool = whatsapp_reply in allowedTools.
- OpenAICompatTransport / AnthropicMessagesTransport: generate() prende useReplyTool. false -> niente
  reply tool, niente tool_choice forzato, system = actSystemTextPlain, testo dal content. I path web
  dedicati (OpenAI Responses / Gemini nativo) erano gia' plain. true -> invariato.
- CliBridgeTransport act/actV2: gate rilassato a isAllowedToolset || isNotificationToolset; envelope
  inoltra allowedTools verbatim (il bridge produrra' plain quando manca whatsapp_reply).

Test: +6 (OpenAICompat/Anthropic sink senza reply tool + con web; CliBridge accetta [web.search]/[]).

NOTA (da chiudere in O4): per il sink da TIMER (evento non-WhatsApp, contextSources senza notification)
l'act() dei transport fallisce ancora a monte su requireActContextSources/requireWhatsAppNotification —
l'intero path act assume una notifica WhatsApp. O4 aggiunge il path senza-notifica + bridge + compile.

## `a8d9ea11b` — 2026-07-17

**feat(#59 O2): la lane generativa posta la notifica locale (LOCAL_NOTIFICATION)**

Seconda ondata del sink-notifica: GenerativeActionLane esegue davvero il deliver LOCAL_NOTIFICATION.

- AndroidGenerativeLane: nuovo param richiesto `notifier: AutomationNotifier`. validContract branch su
  deliver (LOCAL_NOTIFICATION = !replyTargetSender + isNotificationToolset + titolo valido + contextSources
  vuota/subset{state}, specchio del DraftValidator). process(): dopo il testo, se LOCAL_NOTIFICATION ->
  deliverNotification -> notifier.show(title=notificationTitle, text, context), SUCCEEDED; nessun requisito
  NotificationPosted (funziona da trigger time/immediate). notifier.show che lancia -> FAILED "notify_failed".
  Path reply (WHATSAPP_REPLY + InvokeLlmV2) byte-invariato.
- DI: la lane riceve il notifier reale (AndroidAutomationNotifier, lo stesso di ShizukuActionExecutor).
- Probe: NON toccato — la capability d'arm LOCAL_NOTIFICATION e' ACTION_SHOW_NOTIFICATION, gia' pubblicata
  con notificationsGranted; invoke_llm+web.search gia' con generativeReady.

Test: +4 lane (notifica postata, da trigger non-notifica, notifier che lancia, whatsapp_reply rifiutato);
12 test reply pre-esistenti verdi. Full gate verde. Restano O3 (transport plain path) e O4 (compile/bridge).

## `6fb6ff7a5` — 2026-07-17

**feat(#59 O1): contratto sink-notifica generativo (engine-core)**

Prima ondata del sink-notifica (#59): invoke_llm potra' consegnare il testo generato come NOTIFICA
LOCALE (non solo reply WhatsApp). O1 = dominio engine-core, inerte finche' O2 (lane) + O4 (compile)
non lo cablano — nessuna regola LOCAL_NOTIFICATION e' ancora compilabile.

- Action.InvokeLlm: + deliver: GenerativeDeliverMode {WHATSAPP_REPLY|LOCAL_NOTIFICATION} (default reply)
  + notificationTitle. @EncodeDefault(NEVER) sui due campi -> le regole reply gia' approvate mantengono
  il fingerprint SHA-256 byte-identico (V1FingerprintCompatibilityTest verde). Enum senza @SerialName
  -> UPPERCASE case-sensitive.
- GenerativeContract.isNotificationToolset: allowedTools del sink = solo web.search opzionale, MAI
  whatsapp_reply (il sink e' la notifica, non un tool).
- DraftValidator.validateInvokeLlm: branch su deliver. LOCAL_NOTIFICATION = titolo non-blank/<=120/no
  control, isNotificationToolset, replyTargetSender=false, contextSources vuota o subset {state} (mai
  notification). WHATSAPP_REPLY invariato.
- CapabilityRequirements: LOCAL_NOTIFICATION richiede ACTION_SHOW_NOTIFICATION (+web.search).
- RuleRenderMapper: "Genera e notifica" vs "Genera e rispondi".

Test: serializzazione (deliver UPPERCASE + retro-compat legacy->WHATSAPP_REPLY), validator (valido +
errori), capability, render. Full gate verde tutti i moduli (automation-android compila; la lane
rifiuta LOCAL_NOTIFICATION a runtime -> O2).

## `55e4e1fc4` — 2026-07-17

**fix(#58): web.search armabile (probe available set) + Hermes rifiuta pulito la notifica-da-timer**

Emerso dallo screenshot di Lorenzo (regola "notifica cambio EUR/IDR tra 5 min"):
(a) BUG F2: web.search era pubblicata in availableTools (compilatore) ma NON nel set `available`
    (arm). CapabilityRequirements.InvokeLlm fa addAll(allowedTools) -> web.search e' RICHIESTA
    all'arm -> una regola reply+web compilava ma NON si armava ("capacita non disponibile:
    web.search"). Fix: AndroidCapabilityProbe aggiunge TOOL_WEB_SEARCH al set `available` quando
    generativeReady (specchio di availableTools).
(b) UX: Hermes provava a compilare "notifica generata da un timer" (sink P4 non ancora fatto)
    producendo un invoke_llm invalido (no context notification, show_notification come tool). Ora il
    compile prompt (app AgentMessageSupport regola 16 + bridge regola 14) impone: la consegna
    generativa e' SEMPRE una reply a notifica in arrivo; show_notification NON e' un tool generativo;
    se l'utente chiede una notifica GENERATA da timer/orario/immediate -> draft null +
    "unsupported_capability" con spiegazione (arriva con P4).

Test: AndroidCapabilityProbeTest asserisce web.search in availableCapabilities. Bridge deployato +
ops/hermes/bridge.py sincronizzato. Full gate verde.

## `6801904c0` — 2026-07-17

**feat(#57): web search per OpenAI (Responses API) e Gemini (API nativa) — validato live**

Il web di OpenAI e Gemini non passa da /chat/completions. Aggiunti i due endpoint corretti, single-turn
(il provider fa il loop web internamente). Validato LIVE 2026-07-17 con chiavi reali:
- OpenAI Responses: cambio EUR/USD reale + citazione exchange-rates.org/BCE.
- Gemini native: cambio EUR/USD reale + groundingMetadata.

- ProviderCatalog: WebSearchMechanism += OPENAI_RESPONSES, GEMINI_NATIVE (rimossi OPENAI_SEARCH,
  GEMINI_GROUNDING). OpenAI->OPENAI_RESPONSES, Gemini->GEMINI_NATIVE.
- OpenAICompatTransport.generate: quando applyWeb, when(webSearch): OPENAI_RESPONSES->generateViaResponses
  (POST {base}/responses, tools:[{type:web_search}], input:[system,user], parse output->message->output_text,
  usage input/output_tokens); GEMINI_NATIVE->generateViaGeminiNative (POST {host}/v1beta/models/{model}:
  generateContent, header x-goog-api-key, tools:[{google_search:{}}], systemInstruction+contents, parse
  candidates[0].content.parts[].text, usage usageMetadata). OPENROUTER_ONLINE invariato (:online).
- AgentMessageSupport.actSystemTextPlain: system prompt PLAIN per il ramo web (no reply-tool: il modello
  genera il testo diretto). Redazione chiavi invariata.

Web sui diretti ora: OpenRouter + OpenAI + Gemini + Anthropic (serve credito) tutti coperti. Full gate verde.

## `6056762ce` — 2026-07-17

**chore(hermes): sync ops/hermes/bridge.py con il deployato (web tool + trigger immediate)**

Il bridge deployato su hermes aveva accumulato le modifiche web (F2: -t web, /act allowed_tools,
compile schema) e immediate (validate_trigger, AVAILABLE_TRIGGER_IDS, StaticShellSafety mirror,
Regola 13). Allineo la copia versionata come fonte di verita' per il relay Codex.

## `fac7a3afd` — 2026-07-17

**feat(#54): onboarding — lista onesta "cosa richiede Shizuku"**

L'utente, nello step SHIZUKU dell'onboarding, vede cosa richiede Shizuku e cosa degrada senza,
cosi' sa cosa non puo' fare. Derivato da ActionPrivilege + fix #53 (activity-launch da background).

- ui/UiContracts: ShizukuRequirement {REQUIRED, RECOMMENDED, NOT_REQUIRED}, ShizukuCapabilityRow
  (con actionTypeIds reali), ShizukuCapabilityCatalog.rows() (invariante: ogni action in una riga).
  OnboardingState.shizukuCapabilities.
- OnboardingViewModel: popola la lista.
- OnboardingScreen: sezione dentro lo step SHIZUKU (non un nuovo step), raggruppata per categoria.
- Categorie: REQUIRED = set_wifi/set_bluetooth/write_setting/run_shell; RECOMMENDED = activity-launch
  da background (alarm/timer/launch_app/open_url/open_settings, affidabili solo con Shizuku via am start);
  NOT_REQUIRED = volume/ringer/dnd/torcia/vibrazione/notifiche/clipboard + alarm/timer da primo piano.

Test: ShizukuCapabilityCatalogTest (categorie + unicita'), OnboardingViewModelTest (stato espone lista).
Full gate verde tutti i moduli.

## `e0a4fcdeb` — 2026-07-17

**fix(web/F3): Gemini web = NONE (grounding shim OpenAI-compat non raggiungibile) + smoke live**

Smoke live 2026-07-17 con chiavi reali:
- OpenRouter `:online`: FUNZIONA end-to-end (tasso EUR/USD reale + citazione xe.com). Web validato live.
- Anthropic web_search_20250305: body accettato (errore solo "credit too low", non schema) -> formato ok.
- Gemini: `extra_body.google.tools=[{google_search:{}}]` (formato documentato) da 400 "Unknown name
  'tools' at 'extra_body.google'" anche su gemini-3-flash-preview. Il `tools` OpenAI top-level idem.
  Mismatch doc<->API lato Google: il web nativo non passa da /chat/completions. -> Gemini webSearch=NONE
  (degradazione graziosa: nessun extra_body, nessuna richiesta che darebbe 400).

Web sui provider diretti confermato: OpenRouter (live) + Anthropic (formato ok, serve credito). Gemini/
OpenAI = NONE onesto; Hermes = bridge (F2). Test ProviderCatalog/OpenAICompat aggiornati. Gate verde.

## `e4bca2424` — 2026-07-17

**feat(web/F3): web search server-side sui provider diretti (Anthropic/OpenRouter/Gemini)**

Terza fase del web tool (#52). Abilita il web nell'invoke_llm sui transport cloud diretti, single-turn
(il provider fa il loop internamente, una sola chiamata; nessun refactor client-side).

- ProviderCatalog: enum WebSearchMechanism {NONE, ANTHROPIC_TOOL, OPENROUTER_ONLINE, OPENAI_SEARCH,
  GEMINI_GROUNDING} + ProviderQuirks.webSearch per provider.
- OpenAICompatTransport: thread webRequested (web.search in allowedTools) in generate();
  OpenRouter -> model ":online"; Gemini -> extra_body.google.tools=[{google_search:{}}] (verificato:
  il tools OpenAI standard e' rifiutato dal compat layer); tool_choice=auto quando web attivo.
- AnthropicMessagesTransport: tools=[reply, {"type":"web_search_20250305","name":"web_search"}] +
  tool_choice auto quando web richiesto.
- OpenAI = NONE (onesto): il web dei gpt-5.x passa dalla Responses API, non da /chat/completions;
  web_search_options richiede un modello -search-preview. Degradazione graziosa (web ignorato).
- AgentMessageSupport.requireReplyTool ora usa isAllowedToolset (accetta [reply, web.search]).

Test: OpenAICompat (openrouter :online, gemini extra_body, openai NONE degrada), Anthropic (server
tool), ProviderCatalog. Full gate verde. Smoke live (Gemini ha credito) = prossimo step del main loop.

## `82b6b8be2` — 2026-07-17

**fix(#55): set_volume.level e' una percentuale 0-100 mappata sul max reale dello stream**

Bug sul campo: `level` era usato come indice assoluto dello stream (ALARM max ~7-16) poi clampato,
quindi "volume al 35%" (level 35) diventava 100% (clamp a max). L'LLM/utente pensano in percentuale.

- AndroidBaseActionExecutor.setVolume: `level` valido 0..100; mappa `actual = if(level==0) 0 else
  max(1, round(level/100*maxStreamVolume))`. Percent>0 non silenzia mai (min 1); 100% = max.
- DraftValidator: range gia' 0..100 (MAX_VOLUME_LEVEL=100), aggiornato solo il commento stale.
- AgentMessageSupport: schema set_volume documenta level come percentuale 0-100.

Test: AndroidBaseActionExecutorTest riscritto per la semantica percentuale (50%@16->8, 100%->max,
1%@16->1 no-silenzio, 0->0, 101/-1 invalid); ShizukuActionExecutorTest routing test adattati.
Full gate verde tutti i moduli. Bridge (descrizione set_volume) = step separato.

## `124fe60c0` — 2026-07-17

**feat(#56): trigger "immediate" (esegui-una-volta-all'arm) — fix root-cause sveglie**

Un time one-shot a "adesso" non e' mai schedulabile (l'istante scappa nel passato tra
bozza->approva->schedula -> nextFire=null -> EXPIRED -> disableIfApproved -> "pianificazione
non riuscita" -> regola DISABLED). Diagnosi confermata sul campo con screenshot: "imposti subito
una sveglia alle 12:15" -> Hermes genera trigger time=now, che fallisce la pianificazione.

Fix: nuovo Trigger.Immediate = fira UNA VOLTA all'armamento, senza orologio. L'orario della
sveglia/timer sta nell'AZIONE (set_alarm/set_timer), non nel trigger. Nessuna corsa contro il tempo.

engine-core (Ondata A):
- Trigger.Immediate (data object, @SerialName "immediate"); CapabilityIds.TRIGGER_IMMEDIATE;
  TriggerEvent.ImmediateFired : Registered (mirror di TimeFired).
- TriggerMatcher: ImmediateFired matcha SOLO una regola Immediate.
- StaticShellSafety: Immediate e' trigger FIDATO (fira on-arm per volonta' utente, nessun attore
  esterno) come Time/Geofence/Sensor/Connectivity.
- CapabilityRequirements/DraftValidator/RuleRenderMapper: branch Immediate.

automation-android (Ondata B):
- ImmediateEventDispatcher + EngineImmediateEventDispatcher (mirror del time).
- ArmedAutomationRegistrar.registerImmediate: all'arm dispatcha ImmediateFired (il motore esegue
  le azioni) poi consuma la regola (disableIfApproved), come un time one-shot dopo il fire. Id con
  timestamp -> un re-arm ri-fira.
- AndroidCapabilityProbe: trigger.immediate sempre disponibile (nessuna dipendenza OS) + in
  availableTriggers.
- AgentMessageSupport (compile prompt): regola 15 — comandi one-shot "subito/adesso" e sveglia/timer
  -> trigger immediate; orario nell'azione, mai un time a istante gia' passato.

Bridge Python (immediate declare/map) = prossimo step, poi APK combinato. Full gate verde tutti i moduli.

## `cff9a5d26` — 2026-07-17

**feat(web/F2-kotlin): probe pubblica web.search, compile prompt lo dichiara, CliBridgeTransport lo inoltra**

Seconda fase (lato Kotlin) del web tool (#52). Rende il web dichiarabile e instradabile verso Hermes.

- AndroidCapabilityProbe: web.search tolto da PHASE_UNAVAILABLE_TOOLS (non più sempre off) e
  pubblicato in available_tools quando generativeReady (⇔ invoke_llm disponibile); altrimenti in
  unavailable con REASON_GENERATIVE_RUNTIME. Resta in KNOWN_TOOLS (noto al validator).
- AgentMessageSupport: regola 14 di compile — invoke_llm.allowedTools può includere web.search
  oltre a whatsapp_reply SOLO se il goal richiede dati online/live (cambio, meteo, prezzi, news).
- CliBridgeTransport: act/actV2 accettano allowedTools via GenerativeContract.isAllowedToolset
  (non più uguaglianza esatta a [whatsapp_reply]); web.search viaggia nell'envelope campo
  `allowed_tools`. Rimossa costante ACT_REPLY_TOOL.

Manca il bridge Python (run_gpt -t web,clarify + /act che accetta web + schema compile) per il
web end-to-end via Hermes: prossimo step, poi rebuild+install APK. Transport diretti = F3.

Test: AndroidCapabilityProbeTest (web available/unavailable per generativeReady), CliBridgeTransportTest
(act/actV2 inoltrano [reply,web.search], rifiutano [web.search] senza reply). Full gate verde.

## `7ab9f3a0d` — 2026-07-17

**feat(web/F1): contratto generativo accetta web.search opzionale accanto al reply**

Prima fase del web tool (#52, piano light server-side). Rilassa il contratto allowedTools
delle azioni invoke_llm/invoke_llm_v2 per ammettere `web.search` (tool di sola lettura,
server-side) accanto al reply OBBLIGATORIO. Nessun refactor: architettura single-turn intatta.

- GenerativeContract: TOOL_WEB_SEARCH="web.search", OPTIONAL_TOOLS, isAllowedToolset()
  (reply presente + nessun duplicato + resto solo tool opzionali read-only).
- DraftValidator: uguaglianza esatta -> isAllowedToolset() nei due path (v1/v2). I check
  per-tool (shell/automation.* vietati, tool_unknown) restano difesa in profondita'.
- GenerativeActionLane.validContract: stessa relax (v1 e v2).

Transport/probe/compile-prompt NON toccati (F2/F3). Fasi successive: bridge Hermes -t web,
CliBridgeTransport, quirks web per-provider. Design: docs/superpowers/plans/2026-07-17-argus-web-tool-server-side.md

Test: +web cases in DraftValidatorTest (reply-only ok, reply+web ok, web-solo/duplicato/shell
rifiutati) e GenerativeActionLaneTest (contratto web accettato). Full gate verde (tutti i moduli).

## `bae98fbdf` — 2026-07-17

**fix(actions): sveglia/timer/settings/app affidabili da background via Shizuku (caveat BAL)**

Le azioni activity-launch (set_alarm/set_timer/open_settings_screen/launch_app/open_url)
partono da un Intent startActivity, che Android 14+/OEM (OnePlus) bloccano da contesto
BACKGROUND. Da un'automazione (trigger time -> receiver/AlarmManager) fallivano: sul campo
"Imposta sveglia 10:53" -> set_volume SUCCEEDED, set_alarm FAILED (action_failed, eccezione
BAL ingoiata silenziosamente da guarded).

Fix (routing Shizuku, deciso da Lorenzo):
- device-tools: DeviceController.setAlarm/setTimer/openSettingsScreen privilegiati via
  `am start` argv (identita' shell uid 2000, esente dal blocco BAL). Speculari a launchApp/openUrl.
- ShizukuActionExecutor: nuovo segnale shizukuReady; le 5 activity-launch preferiscono il
  privilegiato quando Shizuku e' AUTORIZZATO, altrimenti Intent BASE (foreground). Le azioni
  Manager (DND/ringer/volume/torcia/vibrazione) restano BASE (gia' ok da background).
- base: ActivityStartBlockedException -> fallimento onesto `activity_start_blocked` (nel journal)
  al posto del generico action_failed quando l'Intent BASE viene bloccato da background.
- DI: shizukuReady = gateway.status()==AUTHORIZED, riletto a ogni scatto (revoca senza riavvio).

Test: +2 device-tools (argv am start + validazione), +2 ShizukuActionExecutor (privilegiato
quando ready / Manager restano base), +1 base (activity_start_blocked). Full gate verde.

## `de8b17db7` — 2026-07-17

**fix(#51): namespace write_setting MAIUSCOLO nel prompt compile (era case-mismatch)**

SettingNamespace e' un enum senza @SerialName -> serializza MAIUSCOLO (SYSTEM),
e ArgusJson e' case-sensitive (niente decodeEnumsCaseInsensitive). Il prompt
diceva 'system' minuscolo -> un modello che lo seguiva produceva JSON che l'app
NON deserializzava. Allineato a MAIUSCOLO (coerente con la lettura state.setting).
Colto verificando la serializzazione prima di attivare il bridge Hermes.

## `704507340` — 2026-07-17

**P3 #51 S4: azioni BASE set_volume + set_flashlight + open_settings_screen + vibrate**

Quarto slice comandi Android, tutte BASE (nessuno Shizuku), via Manager/Intent
non raggiungibili da write_setting. Opus 4.8 TDD, main loop full gate
--rerun-tasks (394 verdi).

- set_volume(stream MEDIA|RING|ALARM|NOTIFICATION, level): AudioManager, clamp a
  getStreamMaxVolume, gate DND per silenziare ring/notif (volume_policy_unavailable).
- set_flashlight(on): CameraManager.setTorchMode (torch_unavailable se no flash).
- open_settings_screen(screen enum chiuso, pkg? per APP_DETAILS): Intent
  Settings.ACTION_* da enum CHIUSO (niente routing sink), settings_screen_unresolved.
- vibrate(durationMs 1..10000): Vibrator/VibratorManager, permesso normal VIBRATE.

Innesto completo compile-enforced + nuovi enum VolumeStream/SettingsScreen.
Pubblicate BASE ungated nel probe. Schema client-side + reference aggiornati.
bridge.py Hermes: attivazione batch dal main loop con Lorenzo.

## `d1deff692` — 2026-07-17

**P3 #51 S3: azione parametrica write_setting (PRIVILEGED, settings put)**

Liberta di automazione assoluta: scrivere QUALSIASI impostazione Android
system|secure|global per chiave, contraltare WRITE dei lettori parametrici.
Opus 4.8 TDD, verificato dal main loop col full gate --rerun-tasks (394 verdi).

DECISIONE (D0 vince sul design doc): PARAMETRICA senza allowlist. Invariante D2
soddisfatto come run_shell: namespace/key/value LETTERALI CLEAN nel fingerprint
approvato, mai dal trigger. Guardrail = WriteSettingPolicy (regex key stile
QUERY_NAME, value <=1024 non-vuoto, reject NUL/newline/control char) + review
umana pre-arm (terna letterale in RuleRenderMapper). Nessun altro limite.

DECISIONE (bug evitato): forAction deriva SOLO la capability famiglia
ACTION_WRITE_SETTING, non il canonicalId per-chiave - metterlo brickerebbe ogni
regola nel CapabilityReconciler stateless (structurally-missing permanente);
il binding per-chiave sta nel fingerprint, come i lettori parametrici
(StateCompare/InvokeLlmV2 gatano su family, non su canonicalId).

Tier PRIVILEGED (Shizuku): argv /system/bin/settings put <ns> <key> <value>,
mai sh -c. Probe pubblica write_setting gated su Shizuku. Schema client-side +
reference con nota stile run_shell. bridge.py Hermes: lo aggiorna il main loop.

## `4cb96fc83` — 2026-07-17

**P3 #51 S1: azioni set_alarm + set_timer (BASE, AlarmClock Intent)**

Prima slice dei comandi di interazione Android: imposta la SVEGLIA/TIMER REALE
di Android via Intent AlarmClock (non una notifica), chiude il gap segnalato da
Lorenzo. Tier BASE: solo permesso normal SET_ALARM, nessuno Shizuku, nessun
grant runtime. Opus 4.8 TDD, verificato dal main loop col full gate
--rerun-tasks (360 verdi).

Innesto completo (~13 punti, gli when esaustivi lo garantiscono): Action.kt
(ActionTypeIds+data class SetAlarm/SetTimer+tier), ActionPrivilege (BASE),
CapabilityRequirements+ActionCapabilities, DeviceState.forAction, ExecutionJournal,
DraftValidator (range hour/minute/seconds+label), AndroidBaseActionExecutor+Surface
(Intent AlarmClock con EXTRA, fail-clean alarm_app_unresolved), ShizukuActionExecutor
dispatch base-only, AndroidCapabilityProbe (pubblicate sempre BASE, ungated),
manifest SET_ALARM, RuleRenderMapper. Schema client-side (AgentMessageSupport +
reference) aggiornato: i provider diretti le generano. bridge.py Hermes: lo
aggiorna il main loop separatamente.

Fallback Shizuku am-start per il caveat BAL: TODO futuro. Verifica fisica
(sveglia reale) a Lorenzo.

## `7815673ea` — 2026-07-17

**docs(P3): lista comandi interazione Android + architettura + piano (#51)**

Da orchestrazione subagent (3 ricercatori Opus + sintesi Fable): lista comandi (sveglia/timer BASE via Intent, brightness/dark-mode/write_setting PRIVILEGED, + ondata toggle), architettura ibrida (azione parametrica write_setting contraltare WRITE dei lettori state + azioni tipizzate curate), sicurezza D0 (authority sink come run_shell), coordinamento Hermes (bridge.py DRAFT_SCHEMA_TEXT + validator), piano TDD S1..S6. S1 = set_alarm+set_timer (BASE, chiude il gap 'imposta sveglia -> solo notifica').

## `789d2e7f0` — 2026-07-17

**P3 #49 refinement: budget token-only per provider senza prezzo noto**

Deciso da Lorenzo: i costi in dollari hanno senso solo per i provider a prezzo
noto; Hermes/OpenRouter/Custom sono token-only. Implementato inline da Fable 5
xhigh in TDD, verificato dal main loop col full gate --rerun-tasks (360 verdi).

- ProviderSpec.costTracked: true SOLO OpenAI/Anthropic/Gemini; Hermes/OpenRouter/
  Custom token-only (prices di OpenRouter svuotate: stima da listino statico era
  fragile - 2 modelli su centinaia - e non leggevamo il costo reale).
- CostEstimator: null per i provider non costTracked.
- UsageDao.tokensBetween: aggregato SUM(tokensIn)/SUM(tokensOut) per provider e
  finestra (null=n/d != 0).
- BudgetSettingsStore: maxTokensPerMonth (globale + per-provider).
- BudgetPolicy: priced -> tetto costo mensile; token-only -> tetto TOKEN mensile
  (in+out); tetto token globale somma SOLO i token-only; limiti chiamate ora/giorno
  universali invariati; cooldown Engine intatto. Nuovo LimitWindow.MONTH_TOKENS.
- UI: BudgetSection mostra token in/out per provider+finestra come metrica
  primaria, dollari (USD+EUR) SOLO per i priced; BudgetLimitsDialog con campo
  "Token / mese". SUPPRESSED_BUDGET audit detail "month_tokens:<scope>".

Note (dai risk report, non bloccanti): il tetto token globale conta solo i
token-only (i priced restano sul tetto costo); righe legacy OpenRouter con costo
si auto-risolvono al rollover mese. Verifica visiva Compose su device: Lorenzo.

## `4db609757` — 2026-07-17

**P3 #49 Wave 5 (budget policy + UI): S13 MeteredBrain/CostEstimator/BudgetPolicy + S14 BudgetUi**

Orchestrazione subagent (Fable build-book + Opus TDD), verificata dal main loop
col full gate --rerun-tasks --no-build-cache (360 task verdi).

S13 - MeteredBrain decorator registrato in ArgusModule.brain() (avvolge
ConfiguredBridgeBrain, unico choke-point compile/act/actV2): pre-call BudgetPolicy
hard-block (ActResult metaError=budget_exceeded + usage_event BLOCKED_BUDGET,
niente transport) o soft-warning one-shot per finestra; post-call scrive
usage_event con usage + costMicros da CostEstimator (prezzi ProviderCatalog,
micro-USD, pricingVersion, modello sconosciuto->null). BudgetSettingsStore
(maxCallsPerHour/Day, maxCostPerMonthMicros, softThreshold; 0/null=illimitato).
BudgetPolicy globale + per-provider (HOUR->DAY->MONTH_COST, fail-open). Nuovi
AuditKind/ExecutionStatus.SUPPRESSED_BUDGET (engine-core) sul modello
SUPPRESSED_COOLDOWN, propagati da GenerativeActionLane e resi visibili nel
journal/UI (ambra). Cooldown per-regola 60s dell'Engine INVARIATO (il budget e'
l'aggregato cross-regola, separato).

S14 - BudgetUi tipizzata (usedHour/limitHour, usedDay/limitDay, costMonthMicros/
costLimitMicros, perProvider, softWarningActive) + BudgetSection reale: progress
ora/giorno/costo MENSILE, banner soft, breakdown per-provider, costo in USD ed
EUR (tasso fisso con disclaimer "stima"), BudgetLimitsDialog che persiste i tetti
su BudgetSettingsStore (onBudgetChange/Day/MonthlyCost, via il placeholder no-op).
SettingsViewModel legge gli aggregati da UsageDao.

Con questo #48 (multi-provider) e #49 (budget) sono COMPLETI lato codice. Restano
solo pezzi device/Hermes: verifica visiva UI su device, S15 (bump schema usage
Hermes), S16 (E2E release da APK pulito).

## `16fca18cd` — 2026-07-16

**P3 #49 Wave 4 (budget fondamenta): S11 Room usage_events + S12 ActResult.usage**

Orchestrazione subagent (Fable build-book + Opus TDD), verificata dal main loop
col full gate --rerun-tasks --no-build-cache (360 task verdi).

S11 - modulo data: tabella append-only usage_events (providerId, model, kind
COMPILE|ACT|ACT_V2, outcome OK|ERROR|BLOCKED_BUDGET, tokensIn/out?, costMicros?,
pricingVersion?) + indice timestamp; UsageDao (insert + aggregateBetween con SUM
nullable per n/d != 0 + purgeBefore); UsageWindows zone/DST-safe (ora rolling,
giorno, MESE corrente); migrazione Room 9->10 versionata con test host
(Robolectric + MigrationTestHelper) e device; retention usage 35gg agganciata a
RoomJournalMaintenance. Schema 10.json generato da KSP incluso.

S12 - ActResult.usage: TurnUsage trasloca in engine-core (typealias di compat in
brain-android); ActResult guadagna val usage: TurnUsage? = null come ultimo
param con default (XOR text/metaError intatto, ActResult non-@Serializable,
golden fingerprint v1 provati invariati); OpenAICompat/Anthropic popolano
ActResult.usage dal parsing token (rimosso il lastUsage @Volatile laterale),
TransportBackedBrain lo propaga. CliBridge/Hermes resta usage=null fino a S15.

NON PROVATO su device: MigrationTest esteso (compila host, run am instrument
rinviata). Prossimo: Wave 5 = S13 MeteredBrain/CostEstimator/BudgetPolicy/
SUPPRESSED_BUDGET + S14 BudgetUi USD+EUR/tetto mensile.

## `af0d3352d` — 2026-07-16

**P3 #48 Wave 3 (UI): S9 Settings multi-provider + S10 onboarding wizard**

Orchestrazione subagent (Fable 5 build-book + Opus 4.8 TDD), verificata dal
main loop col full gate --rerun-tasks --no-build-cache (360 task verdi).

S9 - Settings multi-provider: TransportUi.DirectProvider + ProviderChoiceUi;
SettingsCallbacks.onSelectProvider/onSaveProviderConfig; SettingsViewModel emette
il ramo transport dal selectedProviderId() dello ProviderConfigStore (Hermes->
CliBridge come prima; diretti->DirectProvider con authState da hasApiKey);
TransportSection azionabile (selettore provider, editor chiave masked+modello,
Test connessione provider-aware); ProviderConfigurationDialog (chiave mai in
rememberSaveable, mai riproposta, apiKey null conserva l'esistente); via
InArrivoChip dal ramo provider. La chiave API non entra MAI nel UiState.

S10 - Onboarding "Scegli il cervello": dentro lo step BRAIN_CONFIG esistente
(nessun nuovo StepKind) l'utente sceglie Hermes self-hosted o un provider BYOK
(OpenAI/Anthropic/Gemini/OpenRouter/custom); OnboardingViewModel su
ProviderConfigStore con selectProvider/saveProviderConfig (persiste, ri-legge
bearerToken, sonda health, avanza come saveBridge); onSkip continua a rifiutare
il salto del cervello; canFinish invariato. Chiave mai nello stato onboarding.

NON PROVATO: il layer Compose (chip selettore, dialog, rami DirectProvider)
compila (assembleDebug verde) e le preview rendono, ma va verificato VISIVAMENTE
da Lorenzo su device. Test ViewModel: 9 (Settings) + 8 (Onboarding) verdi.

## `dd9414307` — 2026-07-16

**P3 #48 S8: harness smoke live opt-in (chiavi da env, auto-skip in CI)**

LiveApiSmokeTest colpisce le API reali dei provider SOLO quando le ARGUS_LIVE_*_KEY
sono nell'ambiente; senza, si auto-salta (nessuna rete, innocuo nel gate/CI).
Nessuna chiave nel codice. Smoke eseguito dal main loop il 2026-07-16 con le
chiavi reali di Lorenzo (poi revocate): OpenAI (gpt-5-mini) e Gemini
(gemini-2.5-flash) generazione reale + sentinel @@META@@ parsato; Anthropic
(claude-sonnet-4-5) generazione reale; OpenRouter no-credit 402 -> TransportException
kind=BUDGET (mapping errore validato). Tutti e 4 i transport confermati end-to-end.

## `db19ce8f7` — 2026-07-16

**P3 #48 Wave 2: transport concreti (S5 OpenAICompat, S6 compile, S7 Anthropic)**

Prodotta via orchestrazione subagent (Opus 4.8 TDD), verificata dal main loop
con full gate --rerun-tasks --no-build-cache (360 task verdi).

S5 - OpenAICompatTransport (un solo adapter Chat Completions per OPENAI/GEMINI/
OPENROUTER/CUSTOM_OPENAI_COMPAT, parametrizzato dai quirks del ProviderCatalog,
niente if(provider==)): act/actV2 SINGLE-TURN (mirror CliBridgeTransport),
tool whatsapp_reply + tool_choice/output-cap secondo quirks, parse usage->
TurnUsage (esposta via lastUsage in attesa di ActResult.usage in S12), health()
minimale, mapping errori 401->AUTH/402->BUDGET/429->RATE_LIMIT/4xx-5xx->HTTP/
timeout->TIMEOUT senza trapelare la chiave. Registra i 4 provider nella factory.

S6 - OpenAICompatTransport.compile client-side: riproduce il prompt Hermes
(13 regole + schema draft) tool-less, parsa la riga @@META@@ col parser sentinel
esistente (CliBridgeParser) -> CompileResult; fail-soft su risposta vuota/senza
sentinel/malformata (metaError tipizzato, mai crash). La chiave resta solo negli
header.

S7 - AnthropicMessagesTransport (Messages API): x-api-key + anthropic-version,
max_tokens obbligatorio, system separato, tool_use/tool_result, usage.input/
output_tokens->TurnUsage, no-credit 400/402->BUDGET. Estratto AgentMessageSupport
condiviso (prompt/validazione/redazione) e OpenAICompat rifattorizzato per
delegarci (comportamento invariato). Registra ANTHROPIC nella factory.

Fix main-loop: ConfiguredBridgeBrainTest - il caso "provider senza transport"
(Wave 1: not_yet_implemented) e' obsoleto ora che tutti i provider hanno un
transport; riscritto sulla proprieta' reale documentata (un throw della factory
si propaga fuori dal try di TransportBackedBrain). Questo fallimento e' stato
colto SOLO dal full gate del main loop, non dal gate per-modulo degli agenti.

compile()/act() dei provider diretti restano SINGLE-TURN (il loop multi-turno
computer-use e' P3-5). Prossimo: S8 smoke live con chiavi reali (main loop).

## `359f72f14` — 2026-07-16

**docs(P3): reference prompt compile Hermes per S6 (compile client-side)**

Estratto build_prompt + DRAFT_SCHEMA_TEXT + STATE_QUERY_SCHEMA_TEXT + SENTINEL da hermes:~/argus-bridge/bridge.py, per riusare lo stesso contratto compile sui provider diretti senza divergere. Nessun segreto (solo template).

## `a00ec0ff6` — 2026-07-16

**P3 #48 Wave 1: fondamenta transport multi-provider (S1-S4)**

Fondamenta a comportamento INVARIATO (Hermes continua bit-identico). Prodotta
via orchestrazione subagent (Fable 5 build-book + Opus 4.8 TDD), verificata
dal main loop con full gate --rerun-tasks --no-build-cache (360 task verdi).

S1 - contratto transport: AgentTransport + TransportHealth, TransportException
(kind: CONFIGURATION/TIMEOUT/NETWORK/AUTH/HTTP/PROTOCOL/RATE_LIMIT/BUDGET) con
typealias BridgeErrorKind/BridgeException (refactor senza riscrittura),
TurnUsage (pure-data, non popolata in Wave 1). CliBridgeTransport implementa
AgentTransport (providerId=HERMES). HermesBrain -> TransportBackedBrain(transport)
via git mv, mappatura eccezione->metaError identica. ChatViewModel gestisce i
due nuovi kind. NB: ActResult.usage RIMANDATO a S12 (engine-core non puo'
dipendere da brain-android dove nasce TurnUsage; ActResult non e' @Serializable,
zero impatto golden hash).

S2 - ProviderId {HERMES,OPENAI,ANTHROPIC,GEMINI,OPENROUTER,CUSTOM_OPENAI_COMPAT}
+ ProviderCatalog (authStyle, quirks forceToolChoiceAuto/outputCapParam/
extraBodyPassthrough, prezzi micro-USD con PRICING_VERSION). Niente segreti.
z.ai escluso (decisione Lorenzo). Prezzi da listini pubblici 2026 - DA RIVEDERE.

S3 - ProviderConfigStore generalizza BridgeConfigurationStore (selected + per
provider baseUrl/model; chiavi solo via ProviderSecrets on-demand), riusa
AesGcmTokenCodec + Keystore (BridgeKeystore estratto), migrazione additiva e
rollback-safe da config Hermes legacy, test anti-leak chiavi.

S4 - TransportFactory (HERMES->CliBridge; altri provider -> TransportNotImplemented
fino a Wave 2) + seam ConfiguredBridgeBrain via factory con cache per config
+ DI ArgusModule (brain() e grafo a valle invariati).

NOT PROVEN (no device sul gate host): i test instrumented brain-android
(ProviderConfigStoreInstrumentedTest, HermesBridgeInstrumentedTest aggiornato)
compilano ma non eseguiti - da verificare su device prima della release.

## `5c679836f` — 2026-07-16

**docs(P3): decisioni Lorenzo su multi-provider/budget (OpenRouter, no z.ai, USD+EUR, tetto mensile)**

## `b1079901b` — 2026-07-16

**docs(P3): design multi-provider (#48) + budget/costi (#49) da orchestrazione subagent**

Piano prodotto da workflow argus-multiprovider-design: 4 ricercatori Opus 4.8 xhigh (codebase Brain/transport + budget; API OpenAI/Anthropic/Gemini/z.ai) + sintesi Fable 5 xhigh. Ancorato a file:linea reali e contratti API 2026. 16 sotto-slice TDD con dipendenze/gate + 7 domande aperte per Lorenzo. DA RIVEDERE prima dell'implementazione.

## `aff245b35` — 2026-07-16

**test(harness): cleanupGates mirato per gate (-e gate <substr>), niente over-delete**

cleanupGates cancellava TUTTE le regole 'Argus GATE': pulendo un gate spazzava via anche quelle di un altro ancora armate (le geofence tragitto di Lorenzo cancellate durante il cleanup sensore). Ora con -e gate <sottostringa> rimuove solo quel gate; senza arg pulisce tutto ma lo dichiara a video. I reconcile restano store-driven, quindi le regole non cancellate restano registrate.

## `966a489ae` — 2026-07-16

**P3-3: flip test outage Shizuku -> tier base (DND continua, non si blocca)**

Con il tier base attivo SetDnd e' una azione BASE (NotificationManager,
accesso DND implicito dal notification listener), quindi in un outage Shizuku
la regola DEVE eseguire, non essere bloccata. ArgusShizukuOutageE2E ora
verifica: FIRED (non BLOCKED_POLICY), nessun blocco policy, journal set_dnd
SUCCEEDED, DND davvero a TOTAL, regola ancora ARMED. cleanup ripristina il DND
(ora cambia davvero) via Shizuku riavviato. Compila; la run resta host-driven
(prepare/verify/cleanup con l'host che ferma/riavvia il daemon).

Chiude P3-3: tier base senza Shizuku completo (router, executor base, routing,
probe per-azione, attivazione + gate DND su device, test outage aggiornato).

## `79c3f1181` — 2026-07-16

**P3-3 B4: gate DND tier base su device (accesso DND implicito via listener)**

ArgusBaseTierDndInstrumentedTest prova sul OnePlus reale che, essendo Argus
un notification listener abilitato, l'accesso «Non disturbare» è implicito
(isNotificationPolicyAccessGranted == true) e che AndroidBaseActionSurface
cambia davvero il filtro via NotificationManager (INTERRUPTION_FILTER_PRIORITY)
SENZA Shizuku, ripristinando poi lo stato originale. OK (1 test).

Conseguenza: la pagina di sistema «Accesso Non disturbare» mostra
«impossibile abilitare perche' l'app ha accesso alle notifiche» perche' il
grant e' gia' implicito; nessuna riga di grant DND da aggiungere per il tier
personal-full. Il grant esplicito resta rilevante solo per un profilo pubblico
senza listener (P3-3 §4/§5).

## `38881e9da` — 2026-07-16

**P3-3 B4: attiva il tier base (adapter reale + DI + manifest DND)**

- AndroidBaseActionSurface: adapter reale NotificationManager/AudioManager/PackageManager/Intent per DND/Ringer/LaunchApp/OpenUrl senza Shizuku.
- Manifest: ACCESS_NOTIFICATION_POLICY (grant "Accesso Non disturbare").
- DI: inietta baseActions nell'executor e baseTierActive=true nel probe, cosi' le azioni base girano via API normali e si pubblicano senza Shizuku.

Con Shizuku autorizzato le capability restano disponibili (nessuna regola armata va in review); l'esecuzione DND/Ringer passa ora da NotificationManager e richiede il grant "Non disturbare", da concedere sul device. Resta il flip del test outage E2E (task #47).

## `a3cafd357` — 2026-07-16

**P3-3 B3: probe capability per-azione (tier base, flag baseTierActive)**

Spezza il blocco unico shizukuAvailable: le capability BASE si pubblicano
per-azione quando baseTierActive - LaunchApp/OpenUrl sempre, SetDnd/SetRinger
col grant ACCESS_NOTIFICATION_POLICY (dndPolicyGranted) - mentre Wi-Fi/BT/
shell/lettori restano gated su Shizuku. Default baseTierActive=false =
comportamento legacy invariato (test esistenti verdi); il flip lo attiva il
DI in B4, allineato all'executor che esegue davvero le base senza privilegi.
Nuovi test host coprono i tre casi (base attivo/grant, base attivo/no-grant,
base inattivo).

## `25f275c6c` — 2026-07-16

**fix(settings): posizione background concessa non deve apparire grigia**

backgroundLocationState metteva NOT_NEEDED prima del grant reale: con
«sempre/precisa» concesso ma nessuna regola geofence armata, la riga restava
grigia e sembrava non applicata (segnalazione Lorenzo). Ora un grant completo
(bg+fg) legge sempre GRANTED; NOT_NEEDED solo se davvero non concesso e non
richiesto. Test host AndroidUiHealthTest (RED riproduceva il bug).

## `9c895b9a4` — 2026-07-16

**docs(P3-3): stato A/B1/B2 landed + roadmap B3/B4 (grant DND)**

## `e23c9dc26` — 2026-07-16

**P3-3: routing base/privilegiato nell'executor (iniezione opzionale)**

ShizukuActionExecutor accetta un AndroidBaseActionExecutor opzionale: quando
presente, DND/Ringer/LaunchApp/OpenUrl passano dalle API Android normali e
NON piu' dallo shell Shizuku; le privilegiate (Wi-Fi/BT/shell/tap/input)
restano su tools. Default null = comportamento legacy invariato, cosi' il
telefono di Lorenzo non cambia finche' il DI non attiva il tier base (B4,
che richiedera' il grant DND-access). Test di routing host + regressioni
verdi.

## `326043c68` — 2026-07-16

**P3-3: executor base senza Shizuku (DND/Ringer/LaunchApp/OpenUrl)**

AndroidBaseActionExecutor esegue le azioni BASE con API Android normali
tramite un seam iniettabile (BaseActionSurface): mapping mode, grant policy
DND e validazione package/URL sono JVM-puri e host-testati; l'adapter reale
NotificationManager/AudioManager/Intent sara' sottile. Grant mancante ->
fallimento tipizzato (dnd_policy_unavailable/ringer_policy_unavailable),
mai crash ne' blocco. Non ancora cablato nell'executor live.

## `a586556d4` — 2026-07-16

**P3-3: router esaustivo ActionPrivilege (BASE vs PRIVILEGED)**

Classificatore chiuso azione->tier in engine-core (decision record §7.3,
piano P3-3 §1): separa le azioni eseguibili con API Android normali (BASE)
da quelle che richiedono lo shell Shizuku (PRIVILEGED). `when` esaustivo
senza else: una nuova Action non compila finché non se ne dichiara il
privilegio. Fondamento per lo split executor e il probe capability
per-azione dei prossimi commit; nessun consumatore ancora ricablato.

## `bcbeacbeb` — 2026-07-16

**P3-2B: gate negativo sensore (disable/delete/no-leak) su device**

Aggiunge ArgusSensorNegativeInstrumentedTest (stesso processo, catena di
produzione) che prova le proprietà complementari al gate positivo:
- disable -> onSensorTriggered non produce FIRED;
- delete  -> onSensorTriggered non produce FIRED;
- delete dell'ultima regola di un kind -> il reconcile deregistra il
  sensore fisico (no-leak): cleanupSucceeded, requiredBy vuoto, kind fuori
  da registeredKinds.

Eseguito sul OnePlus: OK (3 tests). dumpsys sensorservice conferma 0
connessioni dev.argus vive a fine gate (register all'arm, unregister al
reconcile su ogni pid). Restano a Lorenzo solo il process-restart fisico e
la misura consumo/tempo FGS.

## `73d03c133` — 2026-07-16

**docs(argus): record the physical sensor gate result and the method lesson**

Gate fisico significant-motion passato nella sostanza (2026-07-16 con Lorenzo):
effetto+routing dalla notifica reale vista camminando, callback+rearm dai log
backend e framework, catena fino al journal dal test synthetic. Registra la
lezione: leggere il journal con am instrument uccide il processo e la scrittura
async non committa - non e' un bug, e' il metodo di lettura. Restano
osservazionali disable/delete/process-restart e la misura FGS.

## `39e6ae7e4` — 2026-07-16

**test(sensor): production-path synthetic proves the sensor pipeline end to end**

Chiama sensorEventIngress().onSensorTriggered nello stesso processo, con regola
armata, e verifica FIRED + show_notification SUCCEEDED nel journal. Passa.

Chiarisce un falso allarme del gate fisico: sul campo il callback c'era (log
backend "sensor trigger" x2) e il sensore si ri-armava (re-registrazione framework
stesso pid), ma reportGates via `am instrument` mostrava il journal vuoto. Causa:
am instrument uccide e ricrea il processo app, e la scrittura async del journal
(scope.launch nel callback) non faceva in tempo a committare. La catena e'
corretta: lo strumento di lettura distruggeva il dato. L'effetto reale era gia'
provato dalla notifica "movimento significativo" vista da Lorenzo camminando.

## `bd9565395` — 2026-07-16

**test(sensor): stage the physical significant-motion gate harness**

armSensorGate arma una regola innocua significant-motion, pronta per il gate
fisico di Lorenzo (muovi il telefono -> una esecuzione -> rearm). Non va lanciata
finche' Lorenzo non e' pronto: il FGS resta acceso fino a cleanupGates. Installata
ma NON armata.

## `d765c85e6` — 2026-07-16

**test(sensor): probe publishes significant-motion on real hardware**

Instrumented read-only (non tocca store o regole): sul OnePlus il probe pubblica
sensor.significant_motion ora che il backend e' collegato. Chiude il punto 1 del
gate device senza Lorenzo. availableTriggers contiene sensor.significant_motion
= true; regole di Lorenzo intatte dopo il reinstall.

## `c40e6a915` — 2026-07-16

**docs(argus): record P3-2B host-complete, ready for physical gate**

Implementazione significant-motion chiusa lato host in TDD; stato READY FOR
PHYSICAL GATE (non COMPLETE) perche' movimenti, process restart e negativi
disable/delete richiedono Lorenzo. Mappa i 20 test dell'handoff alla copertura
reale: cosa e' nella suite nuova, cosa e' coperto per composizione/costruzione,
cosa era gia' di Codex in P3-2A. Full gate 759/759.

## `555bef031` — 2026-07-16

**test(sensor): a corrupt detection record fails closed**

Un wire name di kind inesistente nelle prefs deve rendere il pending null, non
crashare: nessuna redelivery su dati corrotti.

## `204de74c4` — 2026-07-16

**feat(sensor): wire the significant-motion runtime and publish the capability**

Collega il runtime sensori al ciclo di vita e ABILITA la capability nello stesso
slice del backend reale, come prescrive il decision record 6.4: il probe pubblica
sensor.significant_motion solo nell'intersezione hardware + backend collegato.

- registrar: ramo Trigger.Sensor -> sensor.reconcile(), pre-check sulla
  capability granulare, esclude needsReview/failed;
- enablement e runtime controller: reconcile sensori in enable/disable/rollback e
  su APP_START/boot/package (i one-shot non sopravvivono a process death). Il
  recovery dei pending usa requiredBy come insieme autorizzato, senza dare lo
  store al controller;
- DI: un solo SharedForegroundSentinel possiede il backend FGS; connectivity e
  sensori ne prendono la vista demand, cosi' il coordinator connectivity resta
  invariato. Il ciclo backend->ingress->coordinator->backend e' spezzato da un
  Provider<SensorEventIngress> risolto solo al primo callback;
- IMPLEMENTED_SENSOR_KINDS = {SIGNIFICANT_MOTION}.

Grafo Hilt verde (nessun ciclo/binding mancante), suite automation-android
invariata, AndroidTest compilato.

## `b5ed5a09d` — 2026-07-16

**feat(sensor): android significant-motion backend and persisted detection store**

Backend via SensorManager.requestTriggerSensor (one-shot, non registerListener),
non Shizuku: la capability sopravvive a un outage Shizuku. Il listener rimuove
se stesso allo scatto e delega all'ingress; non tocca mai TriggerEvent.values.
Il backend non si fida dello stato calcolato dal probe: rifiuta mode != ONE_SHOT
o wakeUp=false. Robolectric copre solo i path negativi (kind sbagliato, sensore
assente, cancel no-op); il path positivo e' il gate fisico.

Detection store su SharedPreferences con commit sincrono: unico stato sensori
persistito. Corruption-safe (fingerprint/kind/sequence malformati -> pending
null, mai crash). Event id stabile sulla redelivery, sequenza monotona per la
detection successiva, pending di una revisione superata non corrisponde.

## `af8e98c34` — 2026-07-16

**feat(sensor): share one foreground sentinel between connectivity and sensors**

Il decision record P3 6.2 impone un solo FGS con demand reasons condivisi, non
un servizio per dominio. SharedForegroundSentinel possiede l'unico backend e lo
accende finche' almeno un dominio lo richiede: togliere il Wi-Fi non spegne il
sensore e viceversa.

demandBackend(reason) espone il sentinel come ConnectivitySentinelBackend per un
singolo demand, cosi' il coordinator connectivity lo usa SENZA modifiche (cambia
solo il wiring) e il coordinator sensori dichiara il proprio demand quando ha
almeno un kind registrato. start/stop scattano solo sulla transizione
vuoto<->non-vuoto: nessun avvio o arresto ridondante del FGS.

## `b2e9919e0` — 2026-07-16

**feat(sensor): add the significant-motion coordinator and ingress core**

Cuore logico del runtime sensori P3-2B, testato in JVM (14 test). Non pubblica
ancora la capability: il backend fisico e il wiring arrivano nelle slice
successive, e IMPLEMENTED_SENSOR_KINDS resta vuoto finche' la catena non e'
completa.

Coordinator: registrazione fisica CONDIVISA per kind (piu' regole dello stesso
kind = un solo requestTriggerSensor, fan-out a valle). Lo stato registered vive
SOLO in memoria: dopo process recreation e' vuoto e il reconcile ri-registra dal
demand desiderato, cosi' un crash non lascia mai un falso "registrato ma morto".
Unavailable strutturale -> NEEDS_REVIEW, failure transitorio -> retry bounded.

Ingress: un sensore one-shot si disattiva da solo allo scatto, quindi il
callback marca consumed prima di tutto e ri-arma sempre in NonCancellable, senza
inghiottire la CancellationException (un one-shot non riarmato e' un sensore
morto in silenzio). Event id stabile sulla redelivery via detection pending;
la detection successiva ne ottiene uno nuovo. Fingerprint della vecchia
revisione non corrisponde dopo un edit. Mai azioni dal listener: solo envelope
verso l'Engine.

## `bf487b460` — 2026-07-16

**docs(argus): hand P3 sensor runtime to Claude**

## `055499c78` — 2026-07-16

**feat(argus): define sensor trigger capabilities**

## `1ca0f14ca` — 2026-07-16

**test(argus): close the live act v2 gate**

## `0e6006f9c` — 2026-07-16

**feat(argus): add classified minimal state to act v2**

## `2e6926faa` — 2026-07-16

**test(argus): close the live bridge v2 gate**

## `462bc8362` — 2026-07-16

**feat(bridge): compile typed state readers with v2**

## `7db7a6fbb` — 2026-07-16

**feat(state): add typed parametric readers**

## `e3fa20293` — 2026-07-16

**feat(engine): read only state required by each rule**

## `450c7f550` — 2026-07-16

**docs(argus): lock P3 decisions and compatibility boundaries**

## `a9e243623` — 2026-07-16

**fix(engine): keep unavailable conditions fail closed**

## `6b307c597` — 2026-07-16

**fix(argus): harden geofence evidence and shell routing**

## `c6dee11a9` — 2026-07-16

**docs(argus): hand P2 back to Codex closed, with the reversals he doesn't know**

P2 chiusa su master con l'evidenza riga per riga. L'handoff mette in testa cio'
che Codex non puo' dedurre dai diff: le decisioni di Lorenzo che RIBALTANO cose
scritte come definitive (shell da contatto whitelistato, B8 declassata da
barriera a trade-off, sensori riaperti) e il principio D0 con la tassonomia
delle quattro classi.

Registra anche i miei errori: l'anello di retroazione Wi-Fi sovrastimato quando
l'evidenza contraria era gia' nel mio output, il cooldown 0 nell'harness e il
cleanup che non annulla le registrazioni OS.

E una trappola nuova sul suo 9: il confronto sha256 repo/host mente su Windows
per via dei CRLF.

## `5b2efd8fe` — 2026-07-15

**fix(ui): round geofence coordinates and close the physical gate**

La review mostrava 15.266659599999999: Double.toString espone la
rappresentazione IEEE 754 a chi deve solo riconoscere un posto. Cinque decimali
valgono ~1 m, e su raggi da centinaia di metri il resto e' rumore. Trovato da
Lorenzo sul device.

Chiude anche l'ultimo punto della DoD P2. Il 2026-07-15 alle 20:03 e alle 20:08
Lorenzo ha attraversato due confini veri: FIRED una volta ciascuna, tutte le
azioni riuscite. Le stesse regole, stesso raggio, stesso posto, due ore prima
scattavano due volte in tre minuti a device fermo: il fix 4339244 regge sul
campo, non solo negli unit test.

Non promettiamo la latenza: due bordi entro il minuto non sono una statistica e
l'aspettativa dichiarata resta quella di E14.

## `c0386041f` — 2026-07-15

**Merge branch feat/argus-p2-background: P2 background triggers**

Telefonia (SMS + chiamate), OTP autocopy, Connectivity (BT/cavo/Wi-Fi con
sentinella FGS on-demand), Geofence durable via LocationManager, shell statica da
trigger fidati, crash-consistency e regex OTP a tempo lineare.

Gate FISICI passati con Lorenzo il 2026-07-15: SMS/OTP reale, chiamata reale
(FIRED x1 + duplicato soppresso, cioe' il fix dea6f79 confermato sul campo e non
solo negli unit test), cavo, Bluetooth ACL. FGS on-demand osservato comparire
dopo l'arm e sparire dopo il cleanup.

Esempio 1 geofence: passato con posizione SIMULATA, con lo stato reale del
telefono verificato (wifi_on=0, bluetooth_on=1). L'attraversamento FISICO resta
un gate osservazionale post-merge: rischio residuo accettato esplicitamente da
Lorenzo, NON dichiarato come PASS.

Il gate fisico ha ripagato subito: ha trovato un flapping del geofence che
nessun test sintetico poteva mostrare, perche' nel mock la posizione la decide
il test (fix in 4339244).

Su decisione di Lorenzo, la shell e' ora innescabile anche da una chat WhatsApp
1:1 whitelistata (f29e8fa): il divieto precedente era piu' largo della sua linea
etica. SMS e chiamate restano esclusi perche' l'identita' e' falsificabile.

Audit pre-merge: 12 punti, nessun finding bloccante. Full gate 758/758 verde
dopo ogni cambiamento di codice.

## `7024618b2` — 2026-07-15

**docs(argus): record the field flapping bug and the shell whitelist decision**

Piano: la sezione geofence ora contiene la diagnosi PROVATA del flapping (due
EXIT consecutive sono impossibili per costruzione, quindi l'ENTER intermedio era
certo anche senza vederlo) e il fix. Registra anche la correzione a una nostra
analisi frettolosa: avevamo detto che l'Esempio 1 sabota il proprio sensore
spegnendo il Wi-Fi, ma "Wifi scanning is always available" era gia' nel nostro
output e lo avevamo ignorato.

DoD: l'Esempio 1 fisico NON diventa un PASS perche' conviene. E' registrato come
rischio residuo accettato esplicitamente da Lorenzo, che e' una strada che la DoD
di Codex prevedeva.

Design: C2/E14 dicono ora che il framework puo' annunciare bordi FALSI a device
fermo, non solo mancarne di veri. 10.2 riflette la revoca del divieto shell da
parte di Lorenzo, con la ragione per cui PhoneState resta fuori (identita'
falsificabile, limite reale e non prudenza). L'intestazione non dichiara piu' P2
in corso.

## `4339244c9` — 2026-07-15

**fix(geofence): refuse a framework edge the real position contradicts**

Bug di campo 2026-07-15: device fermo al centro di un'area da 200 m, due EXIT in
tre minuti, Wi-Fi spento due volte. Il dedup non poteva accorgersene: fra i due
EXIT era arrivato un ENTER altrettanto spurio, e per lo store erano bordi reali.
Un EXIT ripetuto e' impossibile per costruzione, quindi il ping-pong era certo
anche senza vederlo (una regola solo-EXIT non matcha l'ENTER e non lo logga).

Il design prometteva isteresi contro il rumore ma la implementava solo nel
recupero post-crash. Ora la stessa difesa, con lo stesso margine di 25 m, copre
anche il percorso normale: un bordo smentito dalla posizione non avanza la
sequenza, altrimenti il dedup lo tratterebbe come avvenuto davvero.

Fail-open deliberato: senza posizione leggibile il bordo si accetta. Perdere un
attraversamento vero e' peggio che accettarne uno spurio, e senza posizione non
abbiamo elementi per smentire il framework, che resta il segnale primario.
Vicino alla circonferenza, entro l'isteresi, l'ultima parola resta sua.

Il verificatore dipende da una lookup del trigger, non dall'intero
AutomationStore.

## `f29e8fa7c` — 2026-07-15

**feat(shell): let a whitelisted contact trigger an approved command**

Decisione di Lorenzo: revoca il proprio divieto precedente. La regola era piu'
larga della sua linea etica — vietava anche di far PARTIRE un comando statico,
non solo di sceglierlo. L'injection resta impossibile per costruzione: il cmd e'
letterale nel fingerprint, il messaggio e' un interruttore. Cambia solo chi puo'
premerlo.

SMS e chiamate restano esclusi, e non per prudenza: mittente e caller ID sono
falsificabili, quindi nessuna whitelist puo' renderli un'identita'. Il
conversationId WhatsApp invece e' una chiave stabile (E15).

L'identita' e' verificata tre volte in modo concorde (validator, FirePolicy,
executor) e sull'evento REALE, non solo sul trigger dichiarato. L'executor
riceve la whitelist con default vuoto: chi dimentica di cablarla ottiene il
comportamento chiuso. La whitelist e' riletta a ogni scatto, cosi' rimuovere un
contatto gli revoca la shell subito.

La review guadagna shell_contact_trigger: il rischio nuovo non e' cosa esegue ma
che il momento lo scelga il contatto.

Harness: i gate ora armano con cooldown 5 min invece di 0. Il default 0 lasciava
le regole geofence esposte al flapping del motore di posizione, contro cui il
design (C2) prescrive proprio un cooldown per-regola.

## `c59b3d42d` — 2026-07-15

**docs(argus): record the pre-merge audit with twelve verdicts**

Dodici punti dell'handoff Codex 5, nessun finding bloccante. Le righe ereditate
sono marcate come tali: persistenza pending/ack e lifecycle restano prove di
Codex non rieseguite, non conclusioni mie.

Due difese meritano di essere citate perche' non si possono dimenticare:
StaticShellSafety e' fonte unica con when esaustivi sui sealed, quindi una nuova
famiglia di trigger non compila finche' non si decide se la shell e' ammessa; e
PrefsCallStateStore impedisce con un require a runtime che un SMS entri nel
pending persistito.

## `881e876d5` — 2026-07-15

**docs(argus): record the physical gate evidence and correct three misclassed limits**

Piano P2: chiamata/cavo/BT chiusi come physical E2E con l'evidenza del journal
(la chiamata conferma dea6f79 sul campo: secondo broadcast SUPPRESSED_DUPLICATE).
Geofence separa esplicitamente il PASS con posizione simulata dall'attraversamento
fisico ancora aperto, come chiede l'handoff Codex 3.2. DoD ora e' una matrice con
la classe di evidenza per riga: un solo punto resta aperto.

Design: D0 registra la direttiva di Lorenzo (limiti solo etici) con la tassonomia
delle quattro classi. B8 declassata da "barriera invalicabile" a trade-off di
latenza: per automazione non presidiata il brain gratuito basta, quindi il loop
interattivo diventa due tier e quello a pagamento e' opzionale. Sensori e DWELL
riportati alla loro classe reale.

## `7712afc2f` — 2026-07-15

**test(gates): add the physical trigger harness for P2 radio edges**

I gate fisici non possono iniettare l'evento: l'harness arma le regole
diagnostiche e lascia il processo morire, cosi' modem/cavo/radio/framework
consegnano davvero. Da qui i tre tempi (arm* -> azione fisica -> report/cleanup),
necessari anche perche' una camminata per il geofence eccede qualunque timeout.

reportGates non stampa mai il campo detail e asserisce che non contenga run di
cifre: stamparlo sarebbe la fuga di PII che il gate deve escludere.
reportInventory conta le regole senza rivelarne il contenuto.

La geocodifica dell'indirizzo avviene sul device: non entra nel repo.

## `45f95c68b` — 2026-07-15

**docs(argus): prescribe Claude P2 closeout and P3 planning**

## `28d1e86cd` — 2026-07-15

**docs(argus): hand P2 back to Claude at quota stop**

## `e5f77a7c3` — 2026-07-15

**fix(action): bound extraction regex to linear time**

## `a7ee8b380` — 2026-07-15

**fix(background): recover durable trigger edges**

## `8215895b8` — 2026-07-15

**feat(geofence): add durable framework background triggers**

## `f2bca8ede` — 2026-07-15

**feat(connectivity): add on-device background triggers**

## `cfc0ef4ca` — 2026-07-15

**feat(shell): run approved static commands on trusted triggers**

## `dea6f7923` — 2026-07-15

**fix(phone): preserve caller identity across duplicate broadcasts**

## `04e67217d` — 2026-07-15

**test(phone): prove production SMS pipeline on device**

## `0c337e862` — 2026-07-15

**docs(argus): hand P2 back to Codex with proven state and open questions**

Handoff inverso Claude -> Codex: snapshot verificato oggi (gate verde su
02592b2, bridge deployato identico al repo, permessi device), i 13 commit P2
con l'intento dietro, le trappole non deducibili dai diff (entry point Hilt sui
receiver, encodeDefaults e fingerprint, textMatch), le direttive esplicite di
Lorenzo e la lista di cio' che NON e' provato.

Corregge la diagnosi errata della "race Shizuku" nel ledger 23: il rifiuto
run_shell era strutturale, non una race.

## `02592b2a9` — 2026-07-14

**fix(phone): resolve receiver dependencies via explicit Hilt entry points**

The first P2-2 receivers used @AndroidEntryPoint field injection which
lives in the generated super.onReceive - never called (and not callable:
BroadcastReceiver.onReceive is abstract at compile time), so ingress
stayed uninitialized and no SMS rule ever fired (found live by Lorenzo).
EntryPointAccessors is safe here: Application.onCreate precedes every
manifest receiver delivery. Adds privacy-safe diagnostics (counts and
states only, TAG ArgusPhone).

## `9c3d2f190` — 2026-07-14

**feat(action): copy_to_clipboard with deterministic regex extraction (OTP)**

P2-3: new DETERMINISTIC action copies the textual trigger payload (SMS
or notification) to the clipboard, optionally reduced to the first
capture group of a draft-visible regex; the clip is marked sensitive
so codes never show in system previews and the text never leaves the
device. Honest failures leave the clipboard untouched (otp_not_found,
clipboard_source_missing); the validator rejects non-textual triggers
and uncompilable/oversized regexes; the review renders the regex
integrally. Probe publishes the tool unconditionally; bridge schema,
validator (re.compile, 512 max) and prompt updated and deployed
(17/17, unit active). Removes the P2-0 clipboard spike.

## `4f2e3b222` — 2026-07-14

**fix(engine): title-only notifications are armable**

The bridge accepts an empty notification body (a title-only Android
notification is legitimate) but the client validator demanded both
fields, blocking arm with "Campo obbligatorio" - found live by Lorenzo
on the first SMS rule. Title stays required, the body is bounded only.

## `b67337561` — 2026-07-14

**feat(engine): sms triggers can filter on message text**

Found live by Lorenzo: Hermes correctly refused "SMS containing X"
because Trigger.PhoneState had no text filter. textMatch (contains,
case-insensitive, valid only with SMS_RECEIVED - the validator rejects
it on calls) matches the volatile smsText, renders integrally in the
review line and is accepted by the bridge draft schema/validator.
Safe now: no phone_state rule exists yet, so approved fingerprints
are untouched. Deployed on hermes (16/16, unit active).

## `22cdfb926` — 2026-07-14

**fix(settings): every health row is tappable in any state**

Beyond the WARN "Correggi" and the opt-in "Attiva" buttons, the whole
row now opens its related panel/permission on tap (green rows too, for
inspection/revoke; the NOT_NEEDED location row can pre-grant ahead of
geofence rules). Found live by Lorenzo.

## `5de9853a8` — 2026-07-14

**fix(settings): opt-in health rows get an explicit Attiva button**

HealthRow wired onFix only to the WARN-state "Correggi" button, so the
NEUTRAL telephony rows were dead to the touch (found live by Lorenzo).
Opt-in rows now render an explicit action button via actionLabel.

## `f9d9bd31b` — 2026-07-14

**feat(brain): carry available triggers over the wire and enforce them in compile**

The manifest envelope gains available_triggers; the bridge accepts it
as an optional bounded list (legacy clients unchanged, unknown keys
still rejected) and RULE 10 makes the model compile ONLY listed
triggers, answering unsupported_capability with the exact Sistema row
that unlocks the missing grant. Deployed on hermes (backup
bridge.py.pre-triggers-rule-20260714, 16/16 tests, unit active).

## `ce3065064` — 2026-07-14

**feat(settings): one-tap telephony trigger grants in the system screen**

Two always-actionable health rows (SMS triggers, call triggers) with
NEUTRAL state while not granted (opt-in, not a health problem): the
tap launches the runtime permission request directly (calls also ask
READ_CALL_LOG, without which PHONE_STATE carries no number and
"call from X" rules cannot match). Status refreshes on grant result.

## `e3c5f4908` — 2026-07-14

**feat(phone): publish telephony capabilities and arm phone_state rules**

P2-2b part 2: the probe derives trigger.phone_state.sms/.call from the
real RECEIVE_SMS / READ_PHONE_STATE grants; the manifest gains an
availableTriggers list (rendered as "TRIGGER DISPONIBILI" only when
present, legacy manifests unchanged) so Hermes never proposes a dead
trigger; the registrar arms broadcast-backed phone_state rules against
their granular capability, everything else stays fail-closed.

## `b9bfd3f04` — 2026-07-14

**feat(phone): add the telephony event channel (SMS + call state)**

P2-2b part 1: manifest receivers (SMS_RECEIVED guarded by the system
BROADCAST_SMS permission, PHONE_STATE) delegate to a JVM-tested
PhoneEventIngress that recomposes multipart SMS per sender, treats
call-state TRANSITIONS (duplicate framework broadcasts are no-ops,
IDLE counts as call-ended only after RINGING/OFFHOOK via a persisted
last-state) and dispatches to the engine. Capability ids split per
event kind (trigger.phone_state.sms/.call) because the OS grants
differ. SMS text stays RAM-only end to end.

## `306f8fe75` — 2026-07-14

**feat(engine): add phone event parser with volatile sms text**

P2-2a: TriggerEvent.PhoneStateChanged gains smsText (RAM-only, needed
by the P2-3 clipboard action); PhoneEventParser produces fail-closed
envelopes where neither the number nor the text ever reach the event
id in cleartext (digest only), with the same bounds/control-char
policy as the notification parser.

## `e7e1dc57a` — 2026-07-14

**docs(argus): record the clipboard spike result and reorder P2 by value**

Background setPrimaryClip works on real Android 16 (verified by a real
paste): OTP autocopy proceeds with the clean design. Execution order
now puts PhoneState/SMS and OTP first.

## `0291216e3` — 2026-07-14

**docs(argus): add the P2 plan - background triggers, PhoneState/SMS, OTP autocopy, geofence**

Scoped from the design master (par. 15), the field backlog (handoff par. 23)
and Lorenzo's live mandate: shell stays unlimited-with-review, OTP autocopy
is the headline feature, sensor triggers are out for good.

## `0af4ac37a` — 2026-07-14

**docs(argus): record field observations and the P2 backlog from live feedback**

## `0751bfc5c` — 2026-07-14

**docs(argus): record the post-P1 UX polish round in the handoff ledger**

## `c7db6156e` — 2026-07-14

**Merge branch fix/p1-ux-polish: post-P1 UX feedback round**

Real overflow menu in chat (clear conversation, Hermes health check),
readable budget card copy, impersonal armable chat examples.
Verified on device (menu opens, examples shown, budget card legible).

## `8d252ed26` — 2026-07-14

**fix(ui): readable budget card and impersonal armable chat examples**

The budget section rendered a descriptive sentence inside the hourly
counter slot (monospace, squeezed, hyphenated to bits). With no active
hourly limit it now renders as plain full-width text, in user words
(no "P3" jargon); the counter+progress layout stays for when a real
limit ships. Chat suggestions no longer reference personal contacts
and only propose rules armable with current capabilities (time or
notification triggers - the geofence example returns with P2).

## `542f9794b` — 2026-07-14

**feat(chat): make the header overflow menu real**

The three-dot icon was decorative (no click target). It now opens a
menu with "Svuota conversazione" (clears messages/notices, keeps
pending draft cards - they are the approval channel, not history) and
"Verifica connessione Hermes" (re-runs the health check).

## `35c1da988` — 2026-07-14

**docs(argus): record the master merge in the handoff ledger**

## `8f283ca46` — 2026-07-14

**Merge branch feat/argus-p0b-dry: P0-B production runtime + P1 generative notifications**

All external gates green as of 2026-07-14 evening (with Lorenzo on the
physical device): reboot recovery + Android 16 LNP, degraded fail-closed,
live Example 3 (8.5s e2e generative WhatsApp reply), real WhatsApp
characterization (4 real bugs found and fixed, including structural
anti-echo), full no-cache gate (331 tests, lint clean) and read-only
smoke of the six screens on the production install.

## `e86520170` — 2026-07-14

**docs(argus): close P1-8 in the handoff with full gate and smoke evidence**

## `d3c93a744` — 2026-07-14

**docs(argus): close P0-B external gates and record P1 status across docs**

Audit conditions 3 (reboot/LNP) and 6 (live compile rerun) are done as
of the 2026-07-14 evening session; CLAUDE.md reflects P0-B gates all
green plus the P1 status; bridge contract documents the server-side
compile prompt rules including RULE 9 (whitelisted conversationId +
explicit isGroup=false for WhatsApp replies); commander replan checkbox
closed with evidence.

## `e359b4b92` — 2026-07-14

**docs(argus): record post-clean-install rerun findings from the P0-B session**

Preserves the concurrent Codex edits verbatim: production rerun after
clean install (Shizuku re-granted for the new UID, compile stopped by
provider quota as an honest 503, no draft/mutations), the UID/grant
caveat, and the follow-up condition to repeat the live compile when the
provider returns.

## `3e525998b` — 2026-07-14

**docs(argus): record real P1-7 completion and evening fixes in the handoff**

Section 21: live Example 3 green (8.5s e2e), four real bugs found by
characterization and fixed (base64 key, invoke_llm manifest, bridge
rule 9, reply echo), UX fixes, minor non-blocking residues. Status
table and commit ledger updated through 758f5e9.

## `758f5e9d6` — 2026-07-14

**feat(ui): review shows trusted whitelist names instead of conversation hashes**

The approval card rendered raw hashed conversation ids
("shortcut:com.whatsapp:62be..."). RuleRenderMapper now accepts a
conversationId -> displayName map resolved from ContactWhitelistStore
(the trusted store, never LLM prose) and renders
"da Ottica Marci (identita verificata, chat 1:1)".

Faithful by construction: TriggerMatcher gives conversationId exclusive
precedence over sender, so the whitelisted name IS the real match
criterion. Without a trusted label the full hash stays visible.

Chat draft cards, detail screen and list rows all resolve labels
reactively (whitelist changes re-render open cards).

## `b4457611e` — 2026-07-14

**fix(notification): never fire on reply echoes and key events by message time**

Second live P1-7 finding, spotted by Lorenzo: 8.5s after the generative
reply was delivered the rule fired again and only the 60s generative
cooldown suppressed it. WhatsApp reposts the conversation notification
with our own reply as the latest MessagingStyle message, and the event
id (digest over post time and title) treated that repost as a fresh
event. Two structural fixes, both TDD with a mutation check:

- The snapshot now carries the latest message timestamp and whether the
  latest message is authored by the user (null sender convention or
  match against EXTRA_MESSAGING_PERSON). The parser drops self-authored
  updates outright: "when X writes to me" never includes my own reply,
  so the anti-loop no longer depends on the cooldown alone.
- The event id is now keyed on the MessagingStyle message timestamp
  (post time only as fallback) and the title left the digest: cosmetic
  reposts of the same message become honest SUPPRESSED_DUPLICATE claims
  instead of new events.

Group negative also verified live: a message from the whitelisted
contact inside a group was observed as isGroup=1 and produced zero
fires.

## `a34b6d656` — 2026-07-14

**fix(notification): survive real WhatsApp metadata and advertise invoke_llm**

P1-7 real-device characterization findings, all reproduced then fixed
in TDD:

- WhatsApp notification tags are Base64.DEFAULT with a trailing
  newline, so the system notification key contains a control char. The
  parser and the reply gateway treated that as malformed and dropped
  every real conversation; the key is an opaque system identifier and
  is now accepted (length/blank checks stay), kept intact for registry
  and gateway matching, and still only ever hashed into event IDs.
- The Hermes compile prompt only offers action types listed in
  manifest.available_tools, and the probe never listed invoke_llm
  there: the model honestly fell back to a static reply. The manifest
  now advertises invoke_llm when the generative runtime is ready and
  explains the missing readiness otherwise.
- New binding rule 9 in the bridge compile prompt: WhatsApp replies
  require a whitelisted conversationId and an EXPLICIT isGroup=false
  (the validator already rejected the null tri-state, blocking Arm),
  and generated replies must use the exact P1 invoke_llm profile.
  Deployed to hermes (backup kept, unit restarted, 15/15 host tests).
- The ingress now logs privacy-safe outcomes only (package + booleans)
  after debugging a silent listener; chat input (and every screen) now
  lifts with the IME via padding+consumeWindowInsets+imePadding, since
  edge-to-edge on target 36 ignores adjustResize alone.

Live evidence on the OnePlus: real 1:1 message -> generative reply
delivered in 8.5s end-to-end (bridge act 7.4s, gpt-5.5), journal
SUCCEEDED, and a second message within 60s correctly
SUPPRESSED_COOLDOWN. Example 3 of the master spec is green.

## `df15f94ff` — 2026-07-14

**docs(argus): close the P0-B reboot and LNP gate in the handoff**

## `2ca4a10d6` — 2026-07-14

**test(app): add LNP wifi probe and network state permission**

Reboot gate 5.3.3-4 tooling: LocalNetworkProbeInstrumentedTest opens a
socket from the Wi-Fi Network SocketFactory (never a plain Socket that
Tailscale tun0 would absorb) against a caller-supplied LAN endpoint and
asserts the expected allowed/denied outcome, with Tailscale suspended
as the bridge contract prescribes. ACCESS_NETWORK_STATE (normal
permission) joins the manifest: ConnectivityManager callbacks require
it and the app does networking anyway.

Executed live on the OnePlus (Android 16): flag enabled at boot ->
LAN denied cleanly while Hermes over Tailscale stayed reachable from
the app (46 ms health), flag reset + reboot -> LAN baseline restored.

## `4b1151a20` — 2026-07-14

**docs(argus): record synthetic P1-7 completion in the handoff**

## `81750f2f5` — 2026-07-14

**test(automation): add synthetic generative end-to-end coverage**

P1-7 synthetic. GenerativeEndToEndTest (host, Robolectric) stitches the
real production components together the way DI does: synthetic WhatsApp
StatusBarNotification -> NotificationIngress/parser -> rule armed via
the real draft repository with the parser-derived conversation id ->
Engine claim on Room -> generative lane -> local Brain -> reply gateway
-> RemoteInput delivered to a test receiver -> journal CAS. Covers the
happy path (engine returns Submitted before the model responds, journal
converges to SUCCEEDED, duplicate redelivery suppressed, observed row
feeds the picker), the E13 path (notification removed mid-generation ->
DEFERRED with decryptable ciphertext only) and the group negative
(same conversation identity, zero fires). A wiring mutation check
(disconnected lane) fails both positive tests. The instrumented variant
ran on the OnePlus (Android 16): OK (1 test), with the system actually
delivering the RemoteInput broadcast. Also fixes a real flake in
ExecutionLogViewModelTest: viewModelScope must be cancelled and joined
before Dispatchers.resetMain.

## `5a72828a1` — 2026-07-14

**docs(argus): record P1-6 completion in the operational handoff**

Adds section 19 with the four P1-6 commits (encrypted deferred store,
actionable send-now + revocation coordinator, separated notification
health with real battery CTA and whitelist picker, observed retention),
the device migration evidence, the whitelist-retention decision, the
iphlpsvc/Tailscale infrastructure note for this machine and the exact
next steps (synthetic P1-7 now, real P1-7 + reboot gate with Lorenzo,
then P1-8).

## `e0dba9c46` — 2026-07-14

**feat(data): apply journal retention to observed conversation metadata**

P1-6 retention decision: locally observed display names now share the
journal max-age policy — a conversation not seen again within the
retention window leaves the picker store, on top of the existing
200-row bound and the delete-on-revoke purge. Maintenance reports the
trimmed rows.

## `1ec7b2bce` — 2026-07-14

**feat(ui): separate notification health, real battery cta and whitelist picker**

P1-6c. SettingsState now reports posting permission and notification
listener access as distinct health rows with their own fix CTAs, in the
mandated order: POST_NOTIFICATIONS first, then
ACTION_NOTIFICATION_LISTENER_SETTINGS; the onboarding step copy stops
promising "P1 later", completes only with both grants and drives the
same two-stage CTA. The battery fix now requests the package-specific
ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog (manifest permission
added, user gesture only) with the system list as fallback, guarded by
a manifest hardening regression test that also pins allowBackup=false
and the full data-extraction exclusions next to the new deferred table.
The whitelist picker lists recently observed WhatsApp 1:1 conversations
(groups, tri-state identities, foreign packages and already whitelisted
ids filtered out) with masked ids; the raw hash editor survives as the
advanced manual entry. The stale generative-budget copy now points to
the per-rule 60s cooldown and the P3 global budget.

## `262310a8f` — 2026-07-14

**feat(automation): make deferred replies actionable and centralize privacy revocation**

P1-6b. ExecutionLogViewModel resolves the E13 CTA: the audit row maps
to its execution, DeferredReplyManager decrypts the stored ciphertext
only after the tap, the text is copied to the clipboard flagged as
sensitive, WhatsApp is opened without any automatic send, and the row
is consumed with a one-shot CAS so the CTA cannot replay; the reply
text never enters UI messages, logs or state. PrivacyRevocationCoordinator
replaces the preference-only revocation: it closes the synchronous gate
first, then clears reply handles, observed conversations (new clear()
on the store) and encrypted deferred replies, reports partial purge
failures and stays idempotent for retry. The contact whitelist is
intentionally preserved: it is explicit user configuration and armed
generative rules are quarantined by reconcile anyway.

## `7d91fb40d` — 2026-07-14

**feat(data): add encrypted durable store for deferred replies**

P1-6a. Room v9 adds deferred_replies: one row per generative action,
AES-GCM ciphertext only (dedicated non-exportable Keystore key), TTL,
one-shot CAS consumption and FK CASCADE to the execution journal so
retention also kills orphaned ciphertext; maintenance purges expired
and consumed rows. PersistentDeferredReplySink replaces the unavailable
sink: the lane can mark DEFERRED only after the encrypted row is truly
persisted, and the defer-eligible set now includes
reply_channel_unavailable (notification removed/updated or registry
lost during Hermes latency) next to channel_expired; every other
gateway refusal stays FAILED. Migration 8->9 covered in MigrationTest
for every legacy version.

## `3f3702082` — 2026-07-14

**docs(argus): record P1-5 completion in the operational handoff**

Updates the Codex->Claude handoff after the P1-5 resume: executive
status table, commit ledger, resolved known issues, next-resume
checklist for P1-6 and a new section 18 with the implemented slices,
gate evidence (host + real device), in-plan decisions, explicit P1-6
leftovers, Windows/Gradle operational notes and the delivery state.

## `b5d9578dc` — 2026-07-14

**test(automation): gate probe capabilities on real device grants**

P1-5 device gate. The instrumented probe test now builds against the
readiness-aware constructor using the real bridge/preferences stores of
the instrumented package, and adds a targeted check that the actual
API 36 listener-access and battery-exemption reads fail closed for an
ungranted package: no notification trigger, no generative capability,
no raw or static reply, with the exact listener reason in the manifest.
Executed on the OnePlus (CPH2747, Android 16) via direct am instrument:
OK (2 tests); no global grant was modified and the test package was
uninstalled afterwards.

## `9a84c3bc1` — 2026-07-14

**feat(automation): generalize armed registrar for notification triggers**

P1-5d. TimeAlarmArmedAutomationRegistrar becomes
AndroidArmedAutomationRegistrar: Time keeps the exact AlarmManager
coordinator path, Notification verifies the global listener grant from
the capability snapshot plus the persisted ARMED/enabled row without
creating any per-rule OS subscription or persistent service, and every
unimplemented trigger stays fail-closed. Snapshot failures register as
false so arming rolls back honestly. Structural revocations after arm
were already quarantined by CapabilityReconciler through the updated
probe, and the privacy collector added in P1-5b reconciles while the
app stays foreground; fire-time policy remains the second defense.

## `8c857fd10` — 2026-07-14

**feat(automation): execute static WhatsApp reply through the reply gateway**

P1-5c. Action.WhatsAppReply leaves unsupported_phase: the executor now
sends the approved snapshot text through NotificationReplyGateway with
the package, notification key, conversation and event ID frozen from
the verified Notification trigger, so no text or target can come from
the model. Non-Notification events fail closed with
reply_event_unverified and gateway refusals (group, stale handle,
expired channel, untrusted package) surface as their typed codes with
one-shot handle consumption unchanged. With a real implementation path
the probe now publishes action.whatsapp_reply under the same listener
grant as the raw reply tool.

## `b7c25e76b` — 2026-07-14

**feat(automation): gate generative capabilities behind real readiness**

P1-5b. AndroidCapabilityProbe now derives availability from the actual
runtime instead of a static phase map: trigger.notification and the raw
whatsapp_reply tool follow the notification listener grant (with an
exact unavailability reason), action.invoke_llm requires the stored
bridge bearer, accepted privacy and battery exemption via the new
suspend GenerativeRuntimeReadiness boundary, and the Shizuku raw tools
now live in the same available/transient sets that CapabilityRequirements
persists. The static action.whatsapp_reply capability stays unpublished
until its executor exists.

NotificationIngress consults a synchronous privacy gate before creating
handles and re-checks it before persist/dispatch, so a revocation
between the listener callback and the coroutine cannot leak metadata;
reply handles and observed conversation rows are limited to trusted
WhatsApp packages while generic Notification triggers keep flowing.
ArgusRuntimeController now collects the preferences flow, clears the
reply handle registry and reconciles immediately on privacy revocation
instead of waiting for the next ON_START, and takes the Shizuku status
as a Flow so the behavior is testable.

## `e266fd9d8` — 2026-07-14

**feat(engine): align generative requirements and validator with P1 lane**

P1-5a. CapabilityRequirements now derives state.read when an InvokeLlm
context includes the state source, next to action.invoke_llm and the
exact approved raw tools. DraftValidator enforces the only InvokeLlm
profile the generative lane actually executes in P1: non-empty distinct
context sources limited to notification|state and including
notification, and allowed_tools exactly [whatsapp_reply] with no case
or alias normalization. The shared constants live in GenerativeContract
so validator, requirements and lane cannot drift silently.

## `b58c01a22` — 2026-07-14

**docs(argus): add detailed Claude handoff**

## `19bc2944e` — 2026-07-14

**feat(runtime): add guarded generative action lane**

## `069072369` — 2026-07-14

**fix(runtime): keep mixed async executions submitted**

## `d200b8c92` — 2026-07-14

**feat(notification): add guarded reply ingress**

## `bb2692717` — 2026-07-14

**feat(notification): add trusted notification parsing**

## `ce96c4cf8` — 2026-07-14

**feat(brain): add strict Hermes act endpoint**

## `72b69e4fa` — 2026-07-14

**feat(runtime): add async submission journal contract**

## `eb3f50638` — 2026-07-14

**docs(argus): add corrected P1 notification plan**

## `7af05aea2` — 2026-07-14

**test(app): add phased reboot recovery gate**

## `47d599ab5` — 2026-07-14

**test(app): make production E2E cleanup host-safe**

## `9f2861cf4` — 2026-07-14

**fix(bridge): surface provider quota failures safely**

## `a2a91227f` — 2026-07-14

**docs(argus): finalize P0-B audit and release state**

## `f48716842` — 2026-07-14

**fix(app): harden final production device gates**

## `82fa87fe8` — 2026-07-14

**fix(app): add proper themed launcher icon**

## `746668bc8` — 2026-07-14

**fix(brain): advertise compilable Android actions**

## `10bf5deb4` — 2026-07-14

**feat(app): complete production UI wiring**

## `50bcb9e30` — 2026-07-14

**fix(brain): enforce privacy consent before compile**

## `ee8a8d2f8` — 2026-07-14

**feat(app): wire production automation runtime**

## `dfb0782f4` — 2026-07-13

**feat(data): expose reactive execution log**

## `8ca0c4b78` — 2026-07-13

**feat(brain): apply bridge settings without restart**

## `a4479435f` — 2026-07-13

**feat(brain): protect bridge credentials with Android Keystore**

## `f33c0f822` — 2026-07-13

**build(app): add compatible Hilt wiring toolchain**

## `7dc848718` — 2026-07-13

**test(brain): require explicit Android API metadata**

## `5038c2b53` — 2026-07-13

**fix(brain): separate Android release from API level**

## `62f6f528c` — 2026-07-13

**feat(automation): orchestrate safe draft approval**

## `4d8b3899e` — 2026-07-13

**test(brain): isolate Android local network protection**

## `4d1cd31d7` — 2026-07-13

**feat(automation): probe and reconcile Android capabilities**

## `213b986c6` — 2026-07-13

**fix(runtime): quarantine only unchanged approvals**

## `251ad3478` — 2026-07-13

**feat(data): persist contact whitelist policy**

## `530c51578` — 2026-07-13

**docs(plan): record Shizuku device gate**

## `e3405b351` — 2026-07-13

**feat(shizuku): correlate privileged actions by execution**

## `c1463d437` — 2026-07-13

**fix(shizuku): harden permission and service lifecycles**

## `6e3b5a56a` — 2026-07-13

**fix(device-tools): parse Android 16 resumed activity**

## `c539190c3` — 2026-07-13

**fix(automation): keep unsupported actions fail closed**

## `e2d55e33e` — 2026-07-13

**feat(automation): add fail-closed Shizuku action executor**

## `0ce078e2c` — 2026-07-13

**fix(device-tools): dump multi-window UI trees**

## `26e08bf78` — 2026-07-13

**feat(device-tools): add typed Shizuku capabilities**

## `c107f2ba9` — 2026-07-13

**feat(shizuku): add prioritized UserService shell gateway**

## `02b9ee55b` — 2026-07-13

**fix(automation): preserve alarms across replacement races**

## `1c62c3640` — 2026-07-13

**docs(plan): record Android scheduler checkpoint**

## `019eada59` — 2026-07-13

**feat(automation): add resilient AlarmManager scheduler**

## `1e1577af7` — 2026-07-13

**feat(time): make exact alarm precision explicit**

## `27b20b47b` — 2026-07-13

**feat(policy): persist full capability requirements**

## `9aa0c93c0` — 2026-07-13

**build(android): target API 36**

## `81a567219` — 2026-07-13

**docs: align project context with Hermes bridge v1**

## `224a530de` — 2026-07-13

**feat(brain): secure versioned Hermes compile bridge**

## `530b146f7` — 2026-07-13

**fix(approval): bind edits to their approved base**

## `b0dedfe8b` — 2026-07-13

**feat(runtime): persist redacted execution journal**

## `7cdd914eb` — 2026-07-13

**feat(approval): make draft arm revision-bound and atomic**

## `d36d899b7` — 2026-07-13

**fix(data): quarantine invalid rules and expose reactive store**

## `c0a742c95` — 2026-07-13

**feat(data): expose observable store and persist quarantine**

## `c41e98464` — 2026-07-13

**fix(runtime): make trigger execution policy-bound and idempotent**

## `c5a066041` — 2026-07-13

**fix(engine): harden safety gates and runtime semantics**

## `44a260d29` — 2026-07-13

**fix(engine): schedule DST transitions deterministically**

## `866811c3f` — 2026-07-13

**fix(engine): fail closed on unknown notification group**

## `ec4bb0aa5` — 2026-07-13

**fix(engine): make draft validation fail closed**

## `a3eae2ac5` — 2026-07-13

**docs(plan): add P0-B hardening gate and Android 16 baseline**

## `8950bc1c2` — 2026-07-13

**feat(app): add Argus adaptive launcher icon**

## `5a993d132` — 2026-07-13

**feat(brain): HermesBrain via CliBridge transport (compile one-shot, MockWebServer tested)**

brain-android module: CliBridgeTransport (OkHttp -> Hermes bridge :8090) and
HermesBrain implementing engine-core's Brain. /compile preferred (structured
{reply, meta:{draft}, schema_version}); /chat fallback (default, live-bridge
today) delegates to engine-core CliBridgeParser for the @@META@@ sentinel.
Endpoint switch via useCompileEndpoint flag. 60s timeout. Transport throws
typed BridgeException (TIMEOUT/NETWORK/HTTP); HermesBrain maps it to
CompileResult.metaError so the ViewModel never crashes; CancellationException
propagates. 11 MockWebServer tests, all green.

Additive: settings.gradle.kts include("brain-android"). engine-core unmodified.

## `97236e5ec` — 2026-07-13

**feat(data): Room automation store + audit sink (JSON column, decode-fail→needs_review)**

New `data` module (com.android.library, dev.argus.data) implementing
engine-core's AutomationStore + AuditSink on Room 2.6.1 + KSP.

- AutomationEntity: flat columns for query/order + json column carrying the
  full polymorphic Automation via ArgusJson; lastFiredAt column for cooldown.
- AuditEntity: append-only audit log (indexed).
- RoomAutomationStore: entity<->domain map; flat columns authoritative on read;
  save preserves lastFiredAt. Decode-fail OR incompatible schemaVersion ->
  NEEDS_REVIEW placeholder (id/name preserved), never throws / never drops (E8).
- RoomAuditSink: suspend insert.
- 12 Robolectric tests (round-trip, armed() filter, recordFired/lastFiredAt,
  setStatus, corrupt-json->NEEDS_REVIEW, schemaVersion-incompat->NEEDS_REVIEW,
  audit ordering). MigrationTest v1 no-op scaffold (instrumented, P2).
- Version catalog: Room/KSP/serialization/coroutines/Robolectric/JUnit4.
- engine-core untouched.

## `54776bbd8` — 2026-07-13

**Merge branch 'feat/argus-engine-ui': Argus M1 (engine-core) + M2 (UI + demo APK)**

M1 — engine-core (Kotlin/JVM puro): modelli dominio, Engine (lazy state,
last-writer-wins, isolamento errori, cooldown generativo), CronSchedule/DST,
DraftValidator (invarianti sicurezza), CliBridgeParser, CapabilityManifest,
E2E dei 3 esempi + security gate. 67 test verdi.

M2 — ui (Compose M3 dark, italiano) + app: tema da token, contratti verbatim,
RuleRenderMapper, componenti condivisi, 6 schermi stateless, Fixtures + NavHost,
APK demo installabile (inerte, zero permessi). 14 test ui verdi.

Sviluppo agent-driven (Opus 4.8) con review per unità (10 unità, tutte Approved),
review finale whole-branch + fix-pass (tutti Approved). APK installato e
smoke-tested su oneplus. P0-B (glue Android reale) pianificato per la prossima sessione.

## `644d4986b` — 2026-07-13

**test(engine): round-trip Action.SetWifi (complete schema DoD)**

## `4e0477d9a` — 2026-07-13

**docs(plan): P0-B carry-forward constraints from final review**

Append four carry-forward constraints to Global Constraints: privacyNote must
surface as a privacy UiWarning (E11), onSendNow carries a LogRow id (not an
automation id), Engine.onTrigger must rethrow CancellationException, and the
fire-time always-confirm catalog must join FORBIDDEN_IN_INVOKE_LLM as it grows.

## `3a3696afa` — 2026-07-13

**chore: scrub PII from fixtures/tests**

Replace the real personal phone number and Hermes Tailscale IP with clearly-fake
placeholders in fixtures/tests only (Fixtures.kt, SettingsScreen.kt previews,
TriggerMatcherTest.kt): +39 320 000 0000 (digits 393200000000) and 100.64.0.1.
TriggerMatcher assertion updated consistently (digit-suffix match) — stays green.
Docs keep the real values by design.

## `025e2211a` — 2026-07-13

**test(ui): RuleRenderMapper risky-branch coverage**

Add 4 tests flagged by final review (existing 3 kept):
- table-driven requiresLiveConfirm per action (InvokeLlm=false;
  WhatsAppReply/Tap/InputText=true);
- And/Or/Not condition tree flattens into the expected indented lines;
- non-humanizable cron falls back to the raw "Cron '...'" form;
- geofence transition=ENTER + resolveCurrentLocation=true line.

## `3b0973d9e` — 2026-07-13

**test(engine): DraftValidator hardening + full-schema round-trip**

- DraftValidator: normalize tool case before the forbidden-tool match and also
  forbid the bare "automation" prefix (no dot); allowlist backstop unchanged.
  New test asserts app.install, bare "automation", and mixed-case SHELL.RUN /
  Automation.Create all yield tool_forbidden.
- New SchemaRoundTripTest: table-driven decode(encode(x))==x for every subtype
  not covered elsewhere — Trigger.PhoneState/Connectivity, Condition.LocationIn,
  all remaining Actions (SetBluetooth/SetDnd/SetRinger/LaunchApp/OpenUrl/
  ShowNotification/Tap/InputText/WhatsAppReply/RunShell), and AutomationDraft.

## `c19656436` — 2026-07-13

**fix(app): wire nav affordances in demo host**

Now that the additive callbacks exist, cable them in the fake-data demo:
- List onEmptyCta → switch tab to Chat; onBannerTap → switch tab to Sistema
  (added a switchTab helper; surface a non-NONE list banner so the tap is
  demonstrable).
- Detail onBack → popBackStack().
- Log onSendNow → snackbar "Risposta inviata"; onOpenAutomation kept for its
  real (open-automation) purpose.

## `8e0b7dba2` — 2026-07-13

**fix(ui): wire list empty/banner nav + log send-now callbacks**

- AutomationListCallbacks: additive onEmptyCta()/onBannerTap() (default no-op);
  forwarded to EmptyState.onCta and EngineBannerBar.onClick so the empty-state
  "Vai in chat" button and health banner are no longer inert.
- ExecutionLogCallbacks: additive onSendNow(logId) (default no-op); "Invia ora"
  re-pointed from onOpenAutomation(row.id) to onSendNow(row.id) — row.id is a
  LOG-row id, not an automation id (E13).

## `0aab26150` — 2026-07-13

**fix(ui): detail badge opt-out + arm-blocked fallback + rationale case + onBack**

- RuleCard: add optional showGenerativeHeader (default true); Detail passes
  false so the generativa+cloud pair renders once (header badge row, §5.4).
- Detail: arm-blocked reason always shown when !canArm, with generic Italian
  fallback "Regola non armabile" when armBlockedReason is null (§5.2).
- Detail: rationale label lowercased to "descrizione del modello" (§5.1).
- AutomationDetailCallbacks.onBack additive default no-op; header back
  affordance now clickable (host wires the pop).

## `d149a3597` — 2026-07-13

**chore: M2 build green — engine+ui tests pass, demo APK assembles**

Task 13 verification (M2 Unit F):
- ./gradlew :app:assembleDebug → BUILD SUCCESSFUL, app-debug.apk (~55.5 MiB).
- ./gradlew test → engine-core (61 tests) + ui RuleRenderMapper (3) green.
- ./gradlew :ui:assembleDebug → OK.
- Lint (:app :ui lintDebug) non-blocking, no fatal issues.

Fix found during verification: drop redundant android:label on the launcher
activity (RedundantLabel) — the <application> label already applies.

## `b88d201c2` — 2026-07-13

**feat(app): demo NavHost + fixtures wiring all 6 screens (installable APK)**

Task 12 (M2 Unit F): centralized preview fixtures + `app` demo module.

- ui/preview/Fixtures.kt: single source of realistic fake data for all 6
  screens — the 3 spec examples (geofence Wi-Fi/BT exit, DND 23:00, WhatsApp
  reply "Moglie") + plausible rules (backup foto shell, promemoria farmaci
  NEEDS_REVIEW, forbidden-shell generative arm-blocked). Covers every badge
  state (armed/pending/disabled/needs_review), generative+cloud, integral
  shell, and every LogOutcome (SUCCESS/PARTIAL/FAILED/SUBMITTED/DEFERRED).
- app module: application `dev.argus` (minSdk 30, target/compile 35,
  versionName 0.1-demo), MainActivity (edge-to-edge, ArgusTheme + ArgusNavHost),
  manifest + strings/colors/themes.
- nav/ArgusNavHost.kt: Scaffold + bottom NavigationBar (Chat/Automazioni/Log/
  Sistema, §9 icons, pending/needs-review badges) + NavHost mounting each screen
  on Fixtures. Push Detail from chat draft / list row; Onboarding from Sistema;
  live demo state (chat send → canned DraftCard after simulated latency, filter
  chips, inline toggle, snackbars). Demo on fake data — no engine/network.

Screens/contracts/theme/engine-core unmodified.

## `f10943e25` — 2026-07-13

**feat(ui): Onboarding/permissions wizard (6 steps, Shizuku sub-states, consent)**

## `270928641` — 2026-07-13

**feat(ui): System/Settings screen (health, transport, whitelist, budget)**

## `0c0c50c6f` — 2026-07-13

**feat(ui): Execution log screen (per-day grouping, expandable, deferred send)**

## `77e979248` — 2026-07-13

**feat(ui): Automation list screen (fixed ordering, inline toggle, filter chips)**

## `397ad7b7b` — 2026-07-13

**feat(ui): Chat screen (one-shot latency indicator, draft cards, no streaming)**

## `ea7951f3c` — 2026-07-13

**feat(ui): AutomationDetail/Approval screen (warnings-above-fold, ERROR blocks arm)**

## `9be1085d0` — 2026-07-13

**feat(ui): shared components (badges, RuleCard+shell block, banners, latency, empty)**

Also adds the approved Unit B carry-over: 6 action icon keys (ringer,
launch_app, open_url, tap, input_text, whatsapp_reply) to iconFor so risky
actions no longer share the generic Bolt fallback with benign ones.

## `bc7e8dcbf` — 2026-07-13

**feat(ui): RuleRenderMapper (deterministic engine->view rule rendering) + tests**

## `e33c1e9b3` — 2026-07-13

**feat(ui): view-layer state contracts (handoff §6) + icon key mapping**

## `06cb138f7` — 2026-07-13

**feat(ui): typography scale + ArgusTheme (M3 dark/light, shapes, semantic provider)**

## `e8da46e9f` — 2026-07-13

**feat(ui): color tokens (dark+light) and semantic status colors**

## `aeaa0d979` — 2026-07-13

**chore(ui): scaffold Android Compose library module + version catalog**

## `a3d7f3a93` — 2026-07-13

**docs(plan): P0-B must always pass whitelist to DraftValidator (Unit D review)**

## `8f49d7afc` — 2026-07-13

**test(engine): end-to-end coverage of the 3 spec examples + security gate (engine-side)**

## `f3f08657d` — 2026-07-13

**feat(engine): DraftValidator (domain checks + hard security invariants on InvokeLlm)**

## `187ea0d5c` — 2026-07-13

**feat(engine): Brain interface + CliBridgeParser (balanced JSON extraction, explicit missing-draft error)**

## `08f627170` — 2026-07-13

**feat(engine): CapabilityManifest (contacts with ids, StateKeys registry) + CapabilityProbe**

## `19842d667` — 2026-07-13

**feat(engine): trigger-aware ConflictDetector (suppresses legitimate complementary pairs)**

## `8295cecd8` — 2026-07-13

**feat(engine): Engine (lazy state, ascending priority=last-writer-wins, error isolation, min generative cooldown, audit)**

## `8d51d4dbb` — 2026-07-13

**docs(plan): fix CronSchedule KDoc (*/s closed block comment) at source**

## `e260e9f95` — 2026-07-13

**feat(engine): CronSchedule + TimeSpecs (5-field cron, vixie OR, DST gap/overlap tested)**

## `d578c76c8` — 2026-07-13

**feat(engine): TriggerMatcher (conversationId precedence, normalized numbers, direction)**

## `383c585f2` — 2026-07-13

**feat(engine): ConditionEvaluator (time-window, state, geo, AND/OR/NOT)**

## `6f29564c5` — 2026-07-13

**feat(engine): runtime interfaces (executor+Submitted, store, audit, events) + fakes**

## `0df3d6791` — 2026-07-13

**feat(engine): Action + Automation models, tier classification, StateKeys registry**

## `3a7fbf832` — 2026-07-13

**feat(engine): Condition domain model (AND/OR/NOT tree)**

## `14c25d299` — 2026-07-13

**feat(engine): Trigger domain model + serialization (conversationId, at, direction)**

## `2cc759e1c` — 2026-07-13

**chore(engine): scaffold pure-JVM engine-core module**

## `842e76866` — 2026-07-13

**chore(engine): settings include engine-core, keep foojay resolver; ignore SDD scratch**

## `0d107b063` — 2026-07-13

**plan: M2 (UI Compose) + P0-B (Android glue)**

- plan-M2: modulo ui + app demo, 13 task, schermi stateless dai contratti
  handoff §6, tema da design §5, RuleRenderMapper testato, APK demo.
- plan-P0B: core-shizuku, device-tools, Room, brain-android (CliBridge),
  automation-android (executor/time-trigger/probe/approval/VM), FGS minimo.
  Punti da verificare su device marcati 🔬. Eseguito prossima sessione.

## `5bb417f6c` — 2026-07-13

**chore(repo): .gitattributes (LF per gradlew, binari marcati)**

## `96cbdc57e` — 2026-07-13

**chore(repo): M0 setup — rev3 docs, design handoff, gradle 8.13 wrapper**

- spec aggiornata a rev 3 + handoff-frontend nei specs/
- piano P0-A aggiornato a rev 2 (14 file, 13 task TDD)
- design handoff Claude Design (rev 1a approvata) in docs/design/
- CLAUDE.md progetto, .gitignore, settings+properties, wrapper 8.13
- sviluppo spostato su PC negozio (C:\argus), storia importata da oneplus

## `d6136377e` — 2026-07-12

**plan: P0-A engine-core (Kotlin puro, device-independent, TDD)**

Motore automazioni completo come libreria JVM: modelli dominio serializzabili,
ConditionEvaluator, TriggerMatcher, Engine (cooldown/priorita/dispatch),
ConflictDetector euristico, CliBridgeParser, CapabilityManifest. Shizuku/Room/
AlarmManager dietro interfacce iniettate (impl in P0-B). 11 task TDD, i 3
esempi dello spec coperti lato engine.

## `4f58381e7` — 2026-07-12

**design rev2: rianalisi post-verifica Hermes**

- B5 risolto: guida-agent/bridge.py (reference impl HTTP) + hermes proxy verificati
- Brain rimodellato su 2 transport (CliBridge codex-gratis one-shot / OpenAICompat veloce)
- B8 nuovo: latenza brain gratuito ~10-30s -> loop interattivo spostato a P3
- AccessibilityService declassato a opzionale (uiautomator dump + input via Shizuku coprono core)
- D6 Shizuku-come-astrazione (shell UID default, root solo per boot-persistence)
- containment injection: reply_target vincolato a trigger.sender
- edge case privacy (contenuti escono da homelab) + contesto WhatsApp limitato a notifica
- background-location esplicitato, batteria "bassa" ridimensionata onestamente
- phasing riordinato: automazioni prima (latency-tolerant), schermo interattivo dopo

## `522d8bb35` — 2026-07-12

**design: spec Argus — agente LLM automazione Android**

Design approvato in brainstorming: motore Tasker-class always-live con
LLM compilatore NL->regole, Brain pluggable (Hermes/OAuth), Shizuku,
approva-alla-creazione, + sezione edge case/barriere/conflitti con soluzioni.
