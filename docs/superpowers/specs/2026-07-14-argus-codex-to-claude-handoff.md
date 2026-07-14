# Argus — handoff operativo Codex → Claude

Data: 2026-07-14

Destinatario: Claude Code / prossimo commander

Repository locale: `C:\argus`

Branch: `feat/argus-p0b-dry`

Remote di backup: `origin` → hub Git su Unraid

HEAD al momento dell'handoff: `19bc294 feat(runtime): add guarded generative action lane`

## 1. Scopo e motivo dello stop

Questo handoff chiude una sessione di implementazione autonoma iniziata dall'audit di Argus. Lo stop
non corrisponde alla fine del progetto: è un confine intenzionale richiesto da Lorenzo al
raggiungimento di circa il 50% di weekly usage Codex residuo.

L'ultima lettura ufficiale prima della stesura era **55% residuo alle 10:31 Europe/Rome**. Aggiornare
questa riga con la lettura finale prima di consegnare il branch.

Non è stata aperta P1-5 perché sarebbe stato facile lasciare capability, validator e registrar in uno
stato incoerente. P1-4 è invece chiusa su un commit atomico, testato e pushato.

## 2. Snapshot affidabile del repository

- Il branch locale e `origin/feat/argus-p0b-dry` risultavano allineati (`0 0` in
  `git rev-list --left-right --count origin/feat/argus-p0b-dry...HEAD`).
- Tutti i commit elencati sotto sono già stati pushati sull'hub Unraid.
- Il working tree contiene tre modifiche Markdown preesistenti/concorrenziali che **non appartengono
  a questa sessione** e non devono essere incluse alla cieca in commit o cleanup:
  - `CLAUDE.md`
  - `docs/design/argus-p0b-final-audit.md`
  - `docs/superpowers/plans/2026-07-13-argus-commander-replan.md`
- Prima di ogni commit usare sempre `git status --short` e aggiungere file esplicitamente; non usare
  `git add .`.
- Non usare reset/checkout distruttivi: queste tre modifiche possono appartenere a Lorenzo o a un'altra
  sessione Claude.

## 3. Stato esecutivo

| Area | Stato reale | Evidenza / residuo |
| --- | --- | --- |
| P0-B runtime Android | Codice e gate principali verdi | Produzione Hermes→compile→review→arm→AlarmManager→Shizuku→DND→journal passata su OnePlus |
| P0-B process death / outage | Provato su device | Ricreazione processo e Shizuku outage fail-closed passati |
| P0-B reboot + Android 16 LNP | **Gate esterno aperto** | Richiede Lorenzo presente perché il reboot può spegnere `adbd :5555` |
| P1-0 journal asincrono | Completo | Commit `72b69e4` + correzione mixed outcome `0690723` |
| P1-1 `/act` Android/Hermes | Completo e deployato | Commit `ce96c4c`, test server/JVM/device e smoke live |
| P1-2 parser notifiche | Completo | Commit `bb26927`, Room v8 e migrazioni device |
| P1-3 listener + reply gateway | Completo | Commit `d200b8c`, test host e RemoteInput sintetico device |
| P1-4 lane generativa | Core completo | Commit `19bc294`, TOCTOU/CAS/queue/timeout testati |
| E13 deferred durabile | **Non implementato per scelta sicura** | Il sink produzione è unavailable; `channel_expired` resta `FAILED`, mai falso `DEFERRED` |
| P1-5 capability/arm/bootstrap | Prossima slice | Audit già svolto; piano preciso al §9 |
| P1-6 settings/whitelist/deferred UI | Da fare | Include lo store cifrato necessario a E13 |
| P1-7 E2E WhatsApp | Da fare con Lorenzo | Caratterizzazione reale e test positivi/negativi |
| P1-8 chiusura | Da fare | Full gate, documentazione, review e merge solo dopo i gate esterni |

Non dichiarare P0-B o P1 “completi” finché i rispettivi residui non sono verdi.

## 4. Ledger dei commit prodotti o consolidati

Ordine cronologico rilevante:

1. `47d599a test(app): make production E2E cleanup host-safe`
   - rende il cleanup del test produzione invocabile in sicurezza dall'host;
   - evita che un'interruzione lasci DND, automazioni o marker residui.
2. `7af05ae test(app): add phased reboot recovery gate`
   - aggiunge `ArgusRebootE2EInstrumentedTest` in tre fasi `schedule|verify|cleanup`;
   - la fase distruttiva reale non è stata lanciata senza Lorenzo.
3. `eb3f506 docs(argus): add corrected P1 notification plan`
   - piano corretto e vincolante: `docs/superpowers/plans/2026-07-14-argus-p1-generative-notifications.md`.
4. `72b69e4 feat(runtime): add async submission journal contract`
   - P1-0, DB v7, journal asincrono e CAS terminale.
5. `ce96c4c feat(brain): add strict Hermes act endpoint`
   - P1-1, endpoint `/act` lato Android e Hermes.
6. `bb26927 feat(notification): add trusted notification parsing`
   - P1-2, parser trusted e observed conversations, DB v8.
7. `d200b8c feat(notification): add guarded reply ingress`
   - P1-3, listener Android e gateway RemoteInput vincolato all'evento.
8. `0690723 fix(runtime): keep mixed async executions submitted`
   - corregge un bug scoperto dopo P1-3: un'esecuzione con una action `SUBMITTED` e una sorella
     sincrona fallita deve restare `SUBMITTED`, altrimenti la action asincrona non può convergere.
9. `19bc294 feat(runtime): add guarded generative action lane`
   - P1-4 core, lane single-consumer, doppia rivalidazione e completamento CAS.

Commit P0-B immediatamente precedenti utili per il contesto:

- `9f2861c fix(bridge): surface provider quota failures safely`
- `a2a9122 docs(argus): finalize P0-B audit and release state`
- `f487168 fix(app): harden final production device gates`
- `10bf5de feat(app): complete production UI wiring`

## 5. P0-B: evidenza ottenuta e unico gate manuale residuo

### 5.1 Prove già verdi

- E2E produzione reale su OnePlus CPH2747, Android 16/API 36:
  Hermes `/compile` → draft → review → arm → AlarmManager exact → Shizuku → DND TOTAL → journal
  `SUCCEEDED`.
- One-shot disabilitato dopo l'esecuzione e duplicate delivery ignorata.
- Process-death E2E bifase: processo target ricreato con PID diverso, un solo evento `FIRED`, DND e
  journal corretti.
- Shizuku outage E2E: daemon realmente fermato, DND invariato, esito
  `BLOCKED_POLICY/capability_unavailable`, zero `FIRED`, nessun replay tardivo; daemon poi riavviato.
- Smoke dei sei schermi su installazione pulita passato; la card usata dallo smoke viene rimossa.
- Full gate precedente: 243/243 test JVM/Robolectric, lint senza errori, APK main e androidTest
  prodotti. La divergenza Compose che causava `NoSuchMethodError` su `FlowRow` è stata corretta
  allineando il BOM.
- Il test di reboot è stato compilato e le fasi non distruttive schedule/cleanup sono state provate.

### 5.2 Stato sicuro lasciato sul device

- ADB wireless raggiungibile su `100.74.117.9:5555` al momento della consegna.
- Shizuku 13.6 attivo.
- DND/Zen lasciato `off`.
- Exact-alarm app-op lasciato `default`.
- Nessun APK `dev.argus.automation.test` residuo dopo il test sintetico P1-3.
- Nessun bearer passato tramite argomenti `am instrument`; i file privati one-shot usati per i live
  test sono stati eliminati.
- Snapshot read-only ripetuto durante l'handoff: `adb get-state=device`, `zen_mode=0`, app-op
  `SCHEDULE_EXACT_ALARM: default`, processi manager e `shizuku_server` visibili.
- Restano installati alcuni package test P0-B (`dev.argus.test`, `dev.argus.brain.test`,
  `dev.argus.data.test`, `dev.argus.shizuku.test`, `dev.argus.device.test`). Non sono ghost del
  notification listener P1-3: non rimuoverli nel mezzo del gate reboot senza prima capire quali fasi
  li usano.

Verificare nuovamente lo stato prima di qualsiasi mutazione: non fidarsi di uno snapshot vecchio.

### 5.3 Gate esterno ancora aperto

Resta il reboot reale che deve provare insieme:

1. recovery del time alarm dopo `BOOT_COMPLETED`;
2. stato degradato/fail-closed mentre Shizuku non è ancora operativo;
3. compat Android 16 `RESTRICT_LOCAL_NETWORK` dopo reboot, con Tailscale/Hermes raggiungibile e LAN
   locale negata come previsto;
4. ripristino del compat flag e cleanup finale.

**Non eseguire il reboot finché Lorenzo non dice esplicitamente di avere il telefono in mano ed essere
sulla Wi-Fi corretta.** Su questo OnePlus il reboot può spegnere `adbd :5555`; senza accesso fisico non
esiste un recupero remoto affidabile.

Il test è:
`dev.argus.ArgusRebootE2EInstrumentedTest`, runner
`dev.argus.test/androidx.test.runner.AndroidJUnitRunner`, argomento `phase=schedule|verify|cleanup`.
Non improvvisare una sequenza parziale: mantenere sempre un percorso di cleanup.

## 6. P1-0: journal asincrono

Implementato nei commit `72b69e4` e `0690723`.

### Contratto

- `FireContext` trasporta fingerprint dell'approvazione, indice action, `eventId` ed `executionId`.
- Una action generativa non blocca l'Engine: l'executor restituisce `ActionResult.Submitted`.
- `SubmittedActionJournal` osserva l'handshake persistito e risolve l'action con CAS esattamente una
  volta verso uno stato terminale onesto.
- È stato aggiunto `DEFERRED` come esito terminale distinto; non equivale a `SUCCEEDED`.
- La manutenzione interrompe submission stale, quindi non rimangono pending per sempre.
- Migrazione Room e schema portati alla versione 7.

### Invariante critica corretta

L'aggregazione di una run mista deve privilegiare la presenza di almeno una action `SUBMITTED`. Prima
del fix, `SUBMITTED + FAILED` poteva diventare prematuramente `PARTIAL`, impedendo alla lane asincrona
di risolvere il proprio record. `0690723` aggiunge la regressione dedicata.

Non modificare l'ordine di precedenza degli stati senza ripetere i test misti e il CAS Room.

## 7. P1-1: `/act` Android e Hermes

Implementato e pushato in `ce96c4c`.

### Contratto di rete

- `Brain.act(context, goal, contextSources, allowedTools): ActResult` è separato da `/compile`.
- Trasporto HTTPS-only, bearer runtime, schema e content-type/body limitati, errori tipizzati e
  cancellation-safe.
- `request_id` deterministico: SHA-256 di `executionId + NUL + actionIndex`.
- Il payload `/act` P1 invia soltanto il contesto notification/state richiesto e l'unico tool
  `whatsapp_reply`.
- La risposta accettata contiene soltanto il testo generato; Hermes non sceglie né restituisce un
  target. Il destinatario rimane locale e legato al trigger.
- Handle Android, notification key e identificativi target non attraversano il bridge.

### Deploy Hermes

- File attivo: `~/argus-bridge/bridge.py`.
- Backup creato prima del deploy: `~/argus-bridge/bridge.py.pre-act-20260714`.
- Service user attivo tramite `argus-bridge.service`.
- Il deploy è stato atomico e seguito da test e health check.
- La descrizione della unit è ancora generica/vecchia (“one-shot bridge”/compile); è solo metadata e
  può essere aggiornata in P1-8, senza confonderla con una lacuna funzionale.

### Evidenza

- Suite server Hermes: 15/15.
- Test JVM `brain-android`, lint e build Android verdi.
- Smoke remoto `/act` reale passato.
- Test instrumented OnePlus `liveActReturnsTextWithoutRemoteTarget` passato.
- Nessun fallback a provider a pagamento è stato configurato. Se il provider restituisce quota/503,
  Argus deve restare fail-closed e mostrare l'errore tipizzato.

## 8. P1-2 e P1-3: ingresso notifica e canale di reply

### 8.1 Parser trusted e observed conversations (`bb26927`)

- `NotificationEventParser` è puro e testabile; `AndroidNotificationSnapshotFactory` isola le API
  Android.
- Il parser di dominio resta generico: accetta package sintatticamente validi e nega Argus/self,
  group summary e payload inutilizzabili. La fiducia per **inviare** viene applicata più avanti da
  `FirePolicy`/reply gateway, che ammettono soltanto `com.whatsapp` e `com.whatsapp.w4b`.
- Identità: shortcut/shortcutId quando disponibile, poi `Person.uri`; i valori raw vengono hashati
  SHA-256 e namespaced prima della persistenza.
- Lo stato gruppo è tri-state: un'identità ambigua non viene promossa a conversazione 1:1.
- Summary, notifiche dell'app stessa e record senza payload utile vengono ignorati.
- L'`eventId` è un digest; key e testo raw non compaiono nell'identificativo.
- `observed_conversations` salva soltanto hash conversazione, package, display name limitato,
  `isGroup` e `lastSeen`; non salva il testo del messaggio.
- Update monotono: un evento stale non può sovrascrivere metadata più recenti.
- Room/schema portati a v8; migrazioni reali v1→v8 passate 7/7 sul device.

### 8.2 Listener e gateway (`d200b8c`)

- `ArgusNotificationListenerService` è `exported=false` e protetto da
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`.
- Alla connessione reidrata soltanto gli handle attivi; **non ridispatcha eventi vecchi**.
- Alla post registra sincronicamente l'handle prima del dispatch nell'application scope.
- Remove/disconnect invalidano gli handle process-local.
- Il gateway accetta il reply soltanto se coincidono tutti questi vincoli:
  - package WhatsApp trusted;
  - notification key esatta;
  - conversation hash esatto;
  - `TriggerEventId` esatto, per impedire TOCTOU quando Android riusa la stessa SBN key per un
    messaggio più nuovo;
  - conversazione confermata 1:1 (`isGroup == false`);
  - `RemoteInput` free-form;
  - handle non ancora consumato.
- Il consumo è atomico one-shot. `PendingIntent.CanceledException` diventa `channel_expired`.
- Gli oggetti che contengono testo in memoria non sono data class, per non avere `toString()` con
  plaintext accidentale.

### Evidenza e peculiarità Windows/ADB

- Test host coprono mismatch target, stale event ID, gruppo, testo invalido, canale cancellato,
  one-shot e rehydrate senza replay.
- `NotificationReplyGatewayInstrumentedTest` è passato due volte sul OnePlus dopo il binding finale
  all'event ID.
- Gradle `connectedDebugAndroidTest` su Windows fallisce nell'infrastruttura UTP perché il seriale
  wireless contiene `:` e viene usato come path. Non è un fallimento applicativo.
- Workaround provato: build APK test, `adb install -r -t`, invocazione diretta `am instrument`, poi
  uninstall del package test.

## 9. P1-4: lane generativa completata

Commit: `19bc294`.

### Comportamento produzione

- `AndroidGenerativeLane` usa una `Channel` bounded (capacità 8) e un solo consumer process-local.
- `trySubmit` è non-suspend; congela le mappe/liste rilevanti prima dell'accodamento.
- Il worker attende fino a 5 secondi l'handshake reale Room: execution e action devono risultare
  `SUBMITTED`. Questo chiude la race tra `trySubmit` e persistenza dell'Engine.
- Prima di chiamare Hermes rivalida:
  - automazione corrente ancora `ARMED` e enabled;
  - approval fingerprint identico;
  - action allo stesso indice ancora esattamente identica;
  - `FirePolicy` condivisa con l'Engine.
- Dopo il ritorno di Hermes ripete la rivalidazione completa prima di toccare il reply gateway.
- Contratto P1 ammesso: notification obbligatoria; context source distinti e solo
  `notification|state`; `allowedTools == ["whatsapp_reply"]`; `replyTargetSender == true`.
- Timeout della chiamata Brain deriva dall'action approvata.
- Il gateway riceve package, key, conversation hash ed event ID congelati dal trigger.
- Il completamento è CAS tramite `SubmittedActionJournal`; duplicate/redelivery terminali non possono
  reinviare.
- Queue full fallisce immediatamente e l'Engine registra un fallimento, senza attese o fallback.
- La DI Hilt condivide la stessa `RevalidatingFirePolicy` tra Engine e lane.

### Test specifici

- race di handshake journal;
- percorso successo;
- duplicate/redelivery dopo terminale;
- revoca policy durante Brain;
- edit/delete della regola durante Brain;
- coda bounded;
- contratto non valido;
- timeout Brain;
- `DEFERRED` soltanto se il sink conferma di avere persistito davvero il plaintext.

Gate eseguito dopo il codice finale: test engine/data/automation, lint automation e assemble app
verdi.

### E13: stato intenzionalmente incompleto

`DeferredReplySink` è il boundary corretto, ma la produzione usa ancora
`UnavailableDeferredReplySink`. Se il `PendingIntent` scade, l'esecuzione diventa
`FAILED(channel_expired)`. Questo è intenzionale: marcare `DEFERRED` senza uno store cifrato e
durabile perderebbe il testo pur mostrando all'utente un'azione recuperabile inesistente.

Gap scoperto nell'audit finale: la lane chiama il sink soltanto quando il gateway ritorna
`channel_expired`. Se la notifica viene rimossa, aggiornata con un event ID nuovo o il registry viene
perso durante la latenza Hermes, `takeMatching()` ritorna null e il gateway produce
`reply_channel_unavailable`; questo caso oggi non entra nel deferred. Il comportamento corrente è
sicuro (`FAILED`, nessun falso invio), ma l'integrazione E13 deve trattare almeno
`channel_expired` **e** `reply_channel_unavailable` come candidati alla consegna manuale, dopo tutte le
rivalidazioni già presenti.

Non sostituire il sink con memoria volatile, preferences in chiaro o un no-op che ritorna successo.
E13 si chiude soltanto in P1-6 con persistenza cifrata, TTL, UI e cancellazione su privacy revoke.

## 10. Prossima slice esatta: P1-5 capability, arm e bootstrap

L'audit del codice corrente è già stato svolto. Questi sono i gap osservati, non ipotesi:

1. `AndroidCapabilityProbe` tiene ancora `whatsapp_reply` in `PHASE_UNAVAILABLE` e non pubblica
   correttamente `trigger.notification`, `action.invoke_llm`, `action.whatsapp_reply` o il tool raw.
2. `CapabilityRequirements` aggiunge a `InvokeLlm` la capability typed più ogni tool raw; perciò una
   regola generativa reale non può armarsi con il manifest corrente.
3. Il context source `state` non deriva ancora un requisito `state.read`.
4. Il `DraftValidator` può accettare contratti che la lane e `/act` rifiuteranno, per esempio tool
   extra, context vuoto/sconosciuto o combinazioni non supportate in P1.
5. `ShizukuActionExecutor` continua a rifiutare la action statica `Action.WhatsAppReply`; pubblicare la
   capability typed senza implementare l'executor sarebbe un bug.
6. Il registrar esistente è orientato al trigger Time; Notification richiede un check globale del
   listener grant, non una registrazione OS per singola regola.

File di ingresso da leggere prima di modificare P1-5:

- `engine-core/src/main/kotlin/dev/argus/engine/model/CapabilityRequirements.kt`
- `engine-core/src/main/kotlin/dev/argus/engine/safety/DraftValidator.kt`
- `automation-android/src/main/kotlin/dev/argus/automation/AndroidCapabilityProbe.kt`
- `automation-android/src/main/kotlin/dev/argus/automation/AutomationEnablementCoordinator.kt`
- `automation-android/src/main/kotlin/dev/argus/automation/ArgusRuntimeController.kt`
- `automation-android/src/main/kotlin/dev/argus/automation/ShizukuActionExecutor.kt`
- `automation-android/src/main/kotlin/dev/argus/automation/AppPreferencesStore.kt`
- `brain-android/src/main/kotlin/dev/argus/brain/BridgeConfigurationStore.kt`
- `automation-android/src/main/kotlin/dev/argus/automation/di/ArgusModule.kt`
- `app/src/main/kotlin/dev/argus/ArgusApplication.kt`

Il percorso foreground è già disponibile: `ArgusApplication.onStart()` chiama
`ArgusRuntimeController.onForeground()`, e il controller possiede già `CapabilityReconciler`. Estendere
questo flusso invece di creare polling o un foreground service. `AppPreferencesStore.observe()` è uno
`StateFlow`, mentre la presenza del bearer richiede la lettura suspend di `BridgeConfigurationStore`:
la readiness non va forzata dentro un probe puramente sincrono con `runBlocking` sul main thread.

### Sequenza raccomandata, mantenendo commit piccoli

#### P1-5a — requisiti e validator

- Aggiungere test di requirements prima del codice.
- Per `InvokeLlm`, derivare:
  - `action.invoke_llm`;
  - i tool raw esattamente approvati;
  - `state.read` quando `contextSources` include `state`.
- Rendere il validator coerente col contratto realmente implementato dalla lane:
  - context non vuoto, distinto, contiene `notification`, soltanto `notification|state`;
  - `allowedTools` esattamente `listOf("whatsapp_reply")` in P1;
  - `replyTargetSender == true`;
  - trigger Notification e target whitelistato/1:1 secondo le regole già esistenti.
- Non allargare l'allowlist “per il futuro”: ogni capability pubblicata deve avere executor e test
  oggi.

#### P1-5b — readiness e capability probe

- Introdurre un boundary asincrono tipo `GenerativeRuntimeReadiness`.
- La readiness minima deve verificare almeno:
  - bearer bridge realmente presente tramite `BridgeConfigurationStore.bearerToken()`;
  - privacy accettata tramite `AppPreferencesStore`;
  - battery optimization exemption, come richiesto dal piano P1;
  - accesso notification listener per il percorso notification/reply.
- Pubblicare `trigger.notification` soltanto con listener grant.
- Pubblicare `action.invoke_llm` soltanto con runtime configurato, privacy e battery exemption.
- Pubblicare il tool raw `whatsapp_reply` soltanto con listener grant.
- Pubblicare `action.whatsapp_reply` **solo** dopo avere implementato il percorso statico tramite
  `NotificationReplyGateway`; altrimenti lasciarlo unavailable con motivo esatto.
- Rimuovere il vecchio motivo generico “disponibile da P1” e produrre reason map specifiche.
- Includere correttamente i raw tool Shizuku già supportati nel set available/transient; non creare
  alias permissivi tra typed capability e tool wire.
- Prima di pubblicare `trigger.notification`, chiudere il minimo privacy-runtime già in questa slice:
  - `NotificationIngress.registerPosted()` deve consultare un gate sincrono (`StateFlow.value`) e non
    creare handle quando privacy è false;
  - `persistAndDispatch()` deve ricontrollare il gate, perché la revoca può avvenire tra callback main
    thread e coroutine;
  - `NotificationReplyHandleFactory`/ingress deve conservare handle RemoteInput soltanto per package
    WhatsApp trusted; i trigger Notification generici non hanno bisogno di un handle di reply;
  - il cambio preference deve innescare reconcile immediato.
- P1-6 completerà purge, retention e UX della revoca, ma P1-5 non deve rendere armabile il trigger
  lasciando aperto l'ingest dopo consenso revocato.

#### P1-5c — action statica WhatsApp

Decisione consigliata: implementarla ora perché il modello e la UI già la espongono.

- Iniettare nel `ShizukuActionExecutor` un boundary verso `NotificationReplyGateway`.
- Usare soltanto il target del `FireContext` Notification; stessi package/key/conversation/eventId e
  stesse guardie one-shot della lane generativa.
- Non usare testo/target proveniente da Hermes.
- Se il trigger non è Notification, è gruppo, l'handle è stale o il canale è scaduto: fallimento
  tipizzato e fail-closed.
- Aggiornare DI e test costruttori. L'audit ha trovato un numero limitato di call site, principalmente
  test e `ArgusModule`.

Se si decide di rinviarla, non pubblicare `action.whatsapp_reply` e documentare esplicitamente che in
P1 è disponibile solo il tool raw dentro `InvokeLlm`.

#### P1-5d — registrar e reconcile

- Generalizzare il registrar senza introdurre un service persistente:
  - Time continua a usare il coordinator AlarmManager;
  - Notification verifica il grant globale e lo snapshot persistito `ARMED`, ma non registra una
    subscription per-rule nel sistema;
  - trigger non implementati falliscono chiusi.
- Al ritorno in foreground/settings, `CapabilityReconciler` deve osservare revoche strutturali e
  portare la regola a `NEEDS_REVIEW` con CAS legata al fingerprint.
- Non affidarsi soltanto al successivo `ON_START`: `SettingsViewModel.revokePrivacy()` avviene mentre
  l'app è già foreground. La revoca deve chiudere immediatamente la readiness e innescare reconcile,
  oppure un collector sullo `StateFlow` preferences deve farlo in modo serializzato.
- Il fire-time resta comunque la seconda difesa: nessuna revoca concorrente deve poter inviare.

### Gate minimo P1-5

- Test table-driven requirements/validator/probe.
- Regola Notification non armabile senza listener grant.
- Regola InvokeLlm non armabile senza privacy, bridge token o battery exemption.
- Revoca post-arm → reconcile `NEEDS_REVIEW` e fire-time fail-closed.
- Nessuna capability advertised senza implementation path.
- Full test `engine-core`, `data`, `automation-android`, lint e `app:assembleDebug`.
- Test device mirato per grant listener e battery exemption; non modificare grant globali alla cieca.

Matrice minima di regressione consigliata:

| Livello | Scenario | Esito obbligatorio |
| --- | --- | --- |
| Requirements | Notification + InvokeLlm(notification, whatsapp_reply) | Include `trigger.notification`, `action.invoke_llm`, `whatsapp_reply` |
| Requirements | InvokeLlm(notification+state, whatsapp_reply) | Aggiunge `state.read` una sola volta |
| Validator | context vuoto o senza notification | Errore prima dell'arm |
| Validator | context duplicato/sconosciuto | Errore prima dell'arm |
| Validator | tool vuoto, extra o con case/alias diverso | Errore; nessuna normalizzazione permissiva |
| Validator | `replyTargetSender=false` | Errore nel contratto reply P1 |
| Probe | listener off, tutto il resto on | `trigger.notification` e raw reply unavailable con reason esatta |
| Probe | listener on, privacy/token/battery off | trigger disponibile, `action.invoke_llm` unavailable |
| Probe | listener+privacy+token+battery on | capability generativa e raw reply disponibili |
| Probe | Shizuku installed-not-running con regola state/tool | Missing transiente, non review strutturale immediata |
| Reconciler | listener o privacy revocati dopo arm | CAS a `NEEDS_REVIEW` sulla fingerprint corrente |
| Reconciler | regola editata durante reconcile | Non consuma/quarantena una revisione più nuova |
| Ingress privacy | listener grant on ma privacy false | Nessun handle, observed row o dispatch |
| Data minimization | notifica non-WhatsApp con stable identity | Può essere evento generico, ma non entra nel picker/store P1 |
| Handle minimization | non-WhatsApp con RemoteInput free-form | Nessun handle nel reply registry |
| Revoca privacy | handle già attivo e Brain in corso | Registry ripulito; rivalidazione finale blocca l'invio |
| Executor statico | handle/event/whitelist esatti | Un solo invio e terminale onesto |
| Executor statico | gruppo/stale/mismatch/canceled PI | Zero invii e failure tipizzata |
| Runtime | due foreground + cambio Shizuku concorrenti | Reconcile serializzato, nessun doppio side effect |

Una subtlety già presente nel codice: `availableTools` contiene i raw tool Shizuku quando autorizzato,
ma `FirePolicySnapshot.availableCapabilities` oggi contiene soprattutto capability typed/state. Poiché
`CapabilityRequirements.forAction(InvokeLlm)` persiste anche i raw `allowedTools`, i due insiemi devono
essere allineati esplicitamente; non basta correggere la lista inviata a Hermes.

## 11. P1-6, P1-7 e P1-8

### P1-6 — settings, whitelist e deferred durabile

- Mostrare separatamente salute `POST_NOTIFICATIONS` e accesso notification listener.
- CTA in ordine: permission pubblicazione, poi `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- Battery exemption soltanto dopo gesto utente, con intent package-specific e fallback alla lista.
  Il manifest corrente non dichiara ancora
  `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: aggiungerla insieme alla CTA e ai test,
  non come permission isolata priva di UX.
- Picker whitelist basato sulle conversazioni 1:1 osservate; gruppi non selezionabili. Input hash/raw
  rimane diagnostica avanzata, non UX principale.
- Centralizzare la revoca privacy in un coordinator, non nel solo setter UI:
  - chiudere prima il gate sincrono usato dal notification ingress;
  - svuotare immediatamente `ActiveNotificationReplyRegistry`;
  - impedire sia `ObservedConversationStore.record()` sia il dispatch quando il consenso è false,
    anche se Android mantiene il listener grant;
  - filtrare lo store/picker P1 ai package WhatsApp trusted e alle sole conversazioni confermate 1:1;
    il parser e i trigger Notification generici possono restare separati per evoluzioni future;
  - riconciliare/quarantenare le regole generative già armate;
  - purgare deferred, conversazioni osservate e decidere esplicitamente la retention della whitelist.
- Oggi `observed_conversations` è bounded a 200 righe ma non ha TTL/clear API e conserva il
  `displayName` locale in chiaro. Definire retention e delete-on-revoke; non confondere l'hash del
  conversation ID con anonimizzazione completa del display name.
- Implementare lo store E13 cifrato:
  - plaintext cifrato prima della persistenza;
  - chiave Android Keystore non esportabile;
  - relazione audit→execution→deferred verificabile;
  - TTL e pulizia manutentiva;
  - delete immediato su privacy revoke;
  - niente testo/target in log, `toString`, backup o audit diagnostico.
- `onSendNow(logId)`: risolve il deferred, decifra solo dopo tap, rende il contenuto azionabile e
  segna consumato. Non inviare automaticamente dopo che il canale originario è scaduto.
- Solo dopo una persistenza confermata il sink può far convergere a `DEFERRED`.
- Ampliare il ramo defer-eligible della lane almeno a `reply_channel_unavailable`: è l'esito normale
  di remove/update/restart del registry durante la chiamata LLM. Aggiungere test distinti per
  `PendingIntent.CanceledException`, registry rimosso e stessa notification key aggiornata con event
  ID nuovo; nessuno deve inviare al nuovo evento per errore.
- Il manifest produzione ha già `allowBackup=false`, `fullBackupContent=false` e regole che escludono
  ogni dominio da cloud backup/device transfer. Preservare questo hardening e aggiungere una
  regressione manifest quando si introduce la tabella deferred.

### P1-7 — E2E e caratterizzazione reale

- E2E sintetico completo: notification controllata → parser → arm → lane → Hermes `/act` → receiver
  RemoteInput locale → journal.
- Con Lorenzo presente e consenso esplicito, caratterizzare WhatsApp reale sul OnePlus:
  - un messaggio 1:1 e uno di gruppo;
  - verificare quale metadata è stabile nella versione installata;
  - persistere soltanto presenza/sorgente/hash/isGroup, mai il contenuto.
- Esempio 3 reale con contatto whitelistato.
- Negativi obbligatori: gruppo, ID spoof, notifica rimossa durante LLM, whitelist revocata, rule edit,
  provider 503, queue full, process death, duplicate event.
- Misurare schermo spento/Doze con battery exemption. Non promettere durability P2.

### P1-8 — chiusura e merge

- Full test/lint/build senza cache e senza parallelismo.
- Clean install e smoke dei sei schermi.
- Aggiornare spec, contratto bridge, audit, ledger e `CLAUDE.md`, ma prima riconciliare le tre
  modifiche concorrenti elencate al §2.
- Review finale dei diff, commit piccoli, push su hub Unraid.
- Merge su `master` soltanto con:
  - reboot/LNP P0-B verde;
  - Definition of Done P1 verde;
  - E13 realmente azionabile;
  - Esempio 3 reale 1:1 passato;
  - device ripulito e configurazioni ripristinate.

## 12. Comandi di verifica già provati

### Host Android

Gate mirato normale:

```powershell
.\gradlew.bat :engine-core:test :data:testDebugUnitTest :automation-android:testDebugUnitTest :automation-android:lintDebug :app:assembleDebug --no-daemon
```

Per la chiusura usare anche il gate completo senza cache/parallelismo, coerente con i task presenti
nel progetto, e leggere il report invece di fidarsi soltanto dell'exit code.

### Hermes locale

Da `ops/hermes`:

```powershell
python -m unittest -v test_bridge.py
```

Sul server usare `python3`. Prima di un deploy confrontare il file remoto e creare un backup; copiare
atomicamente, riavviare la user unit, verificare `systemctl --user status` e health. Non stampare mai
l'environment file.

### ADB wireless

```powershell
adb connect 100.74.117.9:5555
adb -s 100.74.117.9:5555 get-state
adb -s 100.74.117.9:5555 shell getprop ro.product.model
adb -s 100.74.117.9:5555 shell getprop ro.build.version.sdk
```

Non lanciare due comandi ADB in parallelo verso questo device wireless.

### Workaround test P1-3 su Windows

```powershell
.\gradlew.bat :automation-android:assembleDebugAndroidTest --no-daemon
adb -s 100.74.117.9:5555 install -r -t automation-android\build\outputs\apk\androidTest\debug\automation-android-debug-androidTest.apk
adb -s 100.74.117.9:5555 shell am instrument -w -r -e class dev.argus.automation.notification.NotificationReplyGatewayInstrumentedTest dev.argus.automation.test/androidx.test.runner.AndroidJUnitRunner
adb -s 100.74.117.9:5555 uninstall dev.argus.automation.test
```

La riga `am instrument` non deve contenere bearer, token o testo reale di conversazioni.

### Harness reboot, solo dopo conferma di Lorenzo

APK e runner:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
adb -s 100.74.117.9:5555 install -r -t app\build\outputs\apk\debug\app-debug.apk
adb -s 100.74.117.9:5555 install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s 100.74.117.9:5555 shell am instrument -w -r -e class dev.argus.ArgusRebootE2EInstrumentedTest -e phase schedule dev.argus.test/androidx.test.runner.AndroidJUnitRunner
```

Dopo `schedule`, fermarsi e verificare marker/stato prima del reboot. Eseguire `verify` solo dopo
riattivazione Shizuku e ADB secondo il protocollo del test; eseguire sempre `cleanup` alla fine o dopo
un'interruzione. Non mettere qui un comando reboot “one-liner”: la conferma fisica è un gate umano,
non un dettaglio automatizzabile.

## 13. Vincoli di sicurezza non negoziabili

1. Mai bearer/token in `am instrument -e`, command line ADB, logcat, output CI o commit.
2. Per live test usare solo un file privato one-shot nel sandbox `run-as`; eliminarlo in `finally`.
3. Non leggere/stampare l'environment file Hermes e non includere segreti nell'handoff.
4. Nessun fallback automatico verso provider a pagamento.
5. Non sovrascrivere alla cieca configurazione o grant Shizuku: l'UID può cambiare dopo reinstall e le
   grant di altre app non vanno toccate.
6. Non revocare app-op/permission mentre un instrumentation process è ancora vivo.
7. Nessun reboot senza Lorenzo fisicamente pronto.
8. Nessun invio a gruppi, identità ambigue o target selezionato dal modello.
9. Nessun replay automatico di reply dopo process death/reconnect.
10. Nessun `DEFERRED` se il testo non è cifrato e persistito con successo.
11. Nessuna capability pubblicata senza executor e test corrispondenti.
12. Preservare i tre Markdown concorrenti; niente commit omnibus.

## 14. Known issues e rischi da non perdere

- `Action.WhatsAppReply` statica è modellata ma non eseguita: decidere in P1-5, senza advertising
  falso.
- Probe e requirements sono oggi disallineati per la lane generativa: è il primo blocker funzionale.
- Validator e lane hanno contratti diversi: una rule oggi può superare review ma fallire al fire.
- E13 non esiste in produzione; il fallimento onesto corrente è corretto.
- La lane tenta E13 soltanto su `channel_expired`; remove/update della notifica genera invece
  `reply_channel_unavailable`. Senza ampliare il set defer-eligible, il caso E13 più comune non offrirà
  la CTA manuale anche dopo avere implementato lo store cifrato.
- **Privacy blocker:** `NotificationIngress.persistAndDispatch()` oggi registra la conversazione e
  dispatcha senza consultare `privacyAccepted`; il listener Android può quindi continuare a
  processare metadata dopo una revoca se il grant di sistema resta attivo. Aggiungere un gate
  sincrono prima della registrazione handle e un secondo gate prima di persist/dispatch.
- **Minimizzazione dati:** il parser generico accetta qualunque package valido non-self e
  `persistAndDispatch()` salva ogni observed conversation con identità stabile. Per il P1 WhatsApp,
  filtrare la persistenza/picker ai package WhatsApp trusted senza rompere il matching Notification
  generico. Il gateway di invio è già correttamente ristretto.
- `SettingsViewModel.revokePrivacy()` oggi cambia soltanto la preference. Non svuota il registry, non
  purga observed conversations/whitelist/deferred e non forza reconcile mentre l'app resta
  foreground. Risolvere con un coordinator idempotente e test dei fallimenti parziali.
- Il DB conserva fino a 200 display name di conversazioni osservate in chiaro. Il backup Android è
  correttamente disabilitato/escluso, ma retention e delete-on-revoke sono ancora una decisione P1-6.
- L'identità preferisce shortcut e ripiega su Person URI. Se WhatsApp espone metadata diversi tra due
  forme di notifica o dopo un update, la stessa chat può cambiare hash e comparire due volte nel
  picker. Il comportamento sicuro è non matchare; non auto-unire mai per display name. P1-7 deve
  caratterizzare la stabilità sulla stessa chat attraverso messaggi singoli, bundle/update e riavvio.
- Il notification handle registry è process-local per scelta P1: dopo process death non deve esserci
  replay tardivo.
- UTP/Gradle su Windows non gestisce bene il seriale ADB wireless con `:`; usare instrumentation
  diretta e cleanup del test package.
- Dopo clean install l'UID di Argus può cambiare e la grant Shizuku precedente non vale più; richiedere
  autorizzazione supportata invece di manipolare globalmente i dati Shizuku.
- Provider quota/503 è un percorso atteso e deve restare distinguibile da una risposta modello valida.
- La service unit Hermes ha descrizione metadata non aggiornata; non è un blocker.
- `SettingsViewModel.onBudgetChange()` mostra ancora copy “disponibile da P1”, ma il budget globale è
  P3 nella spec master; P1 usa cooldown minimo/rate limit per-regola. Correggere il copy in P1-6,
  senza costruire un budget manager fuori scope.
- Il merge resta bloccato dal gate reboot/LNP anche se P1 venisse completata prima.

## 15. Prima checklist per Claude

1. Leggere integralmente questo handoff e il piano P1 corretto commit `eb3f506`.
2. Eseguire `git status --short`, verificare branch e sync remote.
3. Non assorbire i tre Markdown concorrenti.
4. Leggere i test di `GenerativeActionLaneTest`, `RoomExecutionJournalTest`,
   `NotificationReplyGatewayTest` e `AndroidCapabilityProbeTest` prima di cambiare contratti.
5. Aprire P1-5 con test requirements/validator, poi probe/readiness, poi executor/registrar.
6. Tenere un solo commit per sotto-slice e pushare sull'hub Unraid dopo gate verde.
7. Se una decisione cambia il modello di sicurezza o l'UX (specialmente static reply o E13), chiedere a
   Lorenzo; per dettagli implementativi coerenti col piano procedere autonomamente.
8. Non dichiarare completion sulla base dei soli test host: i gate device esplicitamente elencati sono
   parte della Definition of Done.

## 16. Definition of Done complessiva per la ripresa

La ripresa può dirsi conclusa soltanto quando:

- P0-B reboot recovery e Android 16 LNP sono provati e ripuliti;
- una regola Notification/InvokeLlm si arma soltanto con capability reali e si quarantena su revoca;
- Esempio 3 invia realmente a una conversazione WhatsApp 1:1 whitelistata;
- gruppo, spoof, stale event/rule, revoca whitelist, provider failure, queue full, process death e
  duplicate event restano fail-closed;
- l'Engine non attende Hermes e ogni submission converge a un terminale onesto;
- E13 è cifrato, durabile, azionabile e cancellabile, non un falso successo;
- nessun testo, target o token appare in log/CLI/audit/backup;
- full gate, clean install, smoke UI e review finale sono verdi;
- branch pushato, documentazione riconciliata e merge eseguito soltanto dopo tutti i gate.

## 17. Riga finale quota e consegna

- Quota weekly residua finale: **50%**.
- Lettura ufficiale: `2026-07-14 10:49:50 Europe/Rome` (`08:49:50Z`).
- Reset indicato dal rate-limit server: `2026-07-21 08:16:24 Europe/Rome`.
- Parent del commit handoff: `19bc294`.
- Subject del commit handoff: `docs(argus): add detailed Claude handoff`.
- Verifica richiesta dopo il push:
  `git rev-list --left-right --count origin/feat/argus-p0b-dry...HEAD` deve restituire `0 0`.
