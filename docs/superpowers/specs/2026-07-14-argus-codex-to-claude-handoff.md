# Argus — handoff operativo Codex → Claude

Data: 2026-07-14

Destinatario: Claude Code / prossimo commander

Repository locale: `C:\argus`

Branch: `feat/argus-p0b-dry`

Remote di backup: `origin` → hub Git su Unraid

HEAD al momento dell'handoff: `19bc294 feat(runtime): add guarded generative action lane`

> **AGGIORNAMENTO 2026-07-14 pomeriggio (Claude Fable, sessione `negozio`)**: P1-5 è stata
> completata in quattro commit atomici più il gate device, tutti pushati sull'hub.
> Stato esecutivo, ledger, known issues e checklist sotto sono stati aggiornati di conseguenza;
> il dettaglio completo della ripresa è nel §18 in fondo.
>
> **SECONDO AGGIORNAMENTO (stessa sessione, sera)**: anche **P1-6 è completata** in quattro
> commit (`7d91fb4`, `262310a`, `1ec7b2b`, `e0dba9c`), con migrazioni v1…v8→v9 verdi sul
> OnePlus reale (`OK (8 tests)`). Dettaglio al §19. HEAD attuale: vedere `git log`; la
> prossima slice è **P1-7** (E2E sintetico subito, caratterizzazione reale con Lorenzo).

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
| P0-B reboot + Android 16 LNP | **CHIUSO (Claude+Lorenzo, 2026-07-14 sera)** | Tutti e 4 i punti §5.3 verdi: `verify` OK (boot 35→36, registrazione ricreata da BOOT, DND intatto), fail-closed con Shizuku giù (`OK (2 tests)`), LNP flag attivo → LAN negata + Hermes/Tailscale ok dall'app (46 ms), reset+secondo boot → baseline LAN ripristinata; dettagli §20 |
| P1-0 journal asincrono | Completo | Commit `72b69e4` + correzione mixed outcome `0690723` |
| P1-1 `/act` Android/Hermes | Completo e deployato | Commit `ce96c4c`, test server/JVM/device e smoke live |
| P1-2 parser notifiche | Completo | Commit `bb26927`, Room v8 e migrazioni device |
| P1-3 listener + reply gateway | Completo | Commit `d200b8c`, test host e RemoteInput sintetico device |
| P1-4 lane generativa | Core completo | Commit `19bc294`, TOCTOU/CAS/queue/timeout testati |
| E13 deferred durabile | **Implementato (Claude, P1-6)** | Store cifrato Keystore + CTA "Invia ora" reale; defer-eligible = `channel_expired` **e** `reply_channel_unavailable`; TTL 24h e purge |
| P1-5 capability/arm/bootstrap | **Completa (Claude 2026-07-14)** | Commit `e266fd9`+`b7c25e7`+`8c857fd`+`9a84c3b`+`b5d9578`; gate host verde (114+45+90 test, lint 0 err, APK) e gate device OK (2 test instrumented su CPH2747); dettagli §18 |
| P1-6 settings/whitelist/deferred UI | **Completa (Claude 2026-07-14)** | Commit `7d91fb4`+`262310a`+`1ec7b2b`+`e0dba9c`; E13 cifrato+azionabile, coordinator revoca, picker whitelist, CTA reali; Room v9 migrata sul device (`OK (8 tests)`); dettagli §19 |
| P1-7 E2E WhatsApp | **COMPLETA (Claude+Lorenzo, 2026-07-14 sera)** | Sintetico `81750f2`; reale: Esempio 3 live VERDE (8,5 s e2e, act 7,4 s), gruppo/cooldown verificati sul campo, 4 bug reali fixati (`a34b6d6`, `b445761`, bridge REGOLA 9); residui minori non bloccanti (Doze, verifica eco live) — dettagli §21 |
| P1-8 chiusura | **In corso (Claude, questa sessione)** | Fix UX nomi whitelistati `758f5e9`; restano full gate no-cache, smoke sei schermi, doc/audit, riconciliazione Markdown, review e merge |

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

Commit della ripresa Claude (P1-5, 2026-07-14 pomeriggio), tutti pushati:

10. `b58c01a docs(argus): add detailed Claude handoff` — questo documento.
11. `e266fd9 feat(engine): align generative requirements and validator with P1 lane`
    - P1-5a: `state.read` derivato dal context source `state`; validator ristretto al profilo
      P1 reale della lane; costanti condivise in `GenerativeContract` (engine-core).
12. `b7c25e7 feat(automation): gate generative capabilities behind real readiness`
    - P1-5b: probe pubblica trigger/tool/invoke_llm da grant e readiness reali; gate privacy
      sincrono nell'ingress; handle e observed rows solo WhatsApp trusted; collector privacy
      nel runtime controller (registry clear + reconcile immediato).
13. `8c857fd feat(automation): execute static WhatsApp reply through the reply gateway`
    - P1-5c: `Action.WhatsAppReply` eseguita via `NotificationReplyGateway` col target congelato
      dal trigger; `action.whatsapp_reply` pubblicata solo ora che l'executor esiste.
14. `9a84c3b feat(automation): generalize armed registrar for notification triggers`
    - P1-5d: `AndroidArmedAutomationRegistrar` per-trigger, Notification senza subscription
      per-rule, altri trigger fail-closed.
15. `b5d9578 test(automation): gate probe capabilities on real device grants`
    - Gate device P1-5 su OnePlus reale: listener/battery fail-closed, `OK (2 tests)`.

Commit della ripresa Claude serale (P1-6 → P1-7 reale → P1-8), tutti pushati:

16. `7d91fb4`+`262310a`+`1ec7b2b`+`e0dba9c` — P1-6 (E13 cifrato, coordinator revoca, settings
    e picker whitelist, retention observed); dettagli §19.
17. `81750f2 test(automation): prove the generative pipeline end to end` — P1-7 sintetico.
18. `4b1151a docs(argus): record synthetic P1-7 completion in the handoff`
19. `2ca4a10 test(app): add LNP wifi probe and network state permission` — tooling gate reboot.
20. `df15f94 docs(argus): close the P0-B reboot and LNP gate in the handoff`
21. `a34b6d6 fix(notification): survive real WhatsApp metadata and advertise invoke_llm`
    - caratterizzazione reale: key opaca con newline accettata, `invoke_llm` pubblicato con
      readiness, log ingress privacy-safe, REGOLA 9 bridge (isGroup esplicito), fix IME NavHost.
22. `b445761 fix(notification): never fire on reply echoes and key events by message time`
    - anti-eco strutturale: skip self-authored + eventId sul message timestamp.
23. `758f5e9 feat(ui): review shows trusted whitelist names instead of conversation hashes`
    - P1-8: review/lista/dettaglio mostrano il nome whitelistato fidato al posto dell'hash.
24. `3e52599`+`e359b4b`+`d3c93a7`+`e865201` — doc di chiusura: §21 P1-7 reale, riconciliazione
    dei tre Markdown Codex, gate esterni chiusi nell'audit/CLAUDE/contract, §22 P1-8.
25. **`8f283ca` — MERGE no-ff di `feat/argus-p0b-dry` su `master`** (2026-07-14, tutti i gate
    verdi), pushato sull'hub. Da qui lo sviluppo riparte da `master`.
26. `c7db615` — merge `fix/p1-ux-polish` (post-P1, feedback live di Lorenzo): menu overflow
    chat reale (`542f979`: "Svuota conversazione" tiene le card pending — TDD sul VM — e
    "Verifica connessione Hermes"), card budget leggibile senza gergo "P3" e senza layout
    contatore quando nessun limite orario è attivo, esempi home impersonali e armabili con
    le capability correnti (`8d252ed` — il geofence torna con P2). Verificato su device.
    Nota di campo: una regola statica multi-toggle (BT+Wi-Fi off su textMatch "esegui") è
    scattata ISTANTANEA — lane deterministica senza rete — e "Esegui" maiuscolo ha matchato
    (textMatch contains case-insensitive by design, `TriggerMatcher`).

## 23. Osservazioni di campo post-merge e backlog P2 (feedback Lorenzo, 2026-07-14 sera)

- **Race Shizuku osservata una volta**: alle 18:29 un compile ha avuto il manifest SENZA
  `run_shell` ("Shizuku non in esecuzione") benché server e permission fossero a posto; alle
  18:37 Sistema era verde e tutto ok. Causa più probabile: `Shizuku.pingBinder()` false nel
  processo appena riavviato (binder sticky non ancora ricevuto dopo un force-stop ADB). Non
  riprodotta dopo. Se ricapita a un utente: la riga Shizuku in Sistema È la verità del
  momento; eventualmente valutare in P2 un retry/attesa binder nel probe al primo compile.
- **Guardrail confermati dal vivo**: (a) comando shell parametrizzato dal CONTENUTO del
  messaggio → correttamente non compilabile (il cmd nel draft è statico e mostrato integrale
  in review; il contenuto notifica non può cambiare formato/tool/destinatario — contract);
  (b) trigger accelerometro/sensori + azione modalità aereo → `unsupported_capability`
  onesto (non esistono nel modello).
- **Backlog P2 da feedback reale** (in ordine di valore):
  1. **Auto-copy OTP da SMS**: trigger notifica dell'app SMS (o `SMS_RECEIVED`) + estrazione
     REGEX deterministica nel draft (codice 4-8 cifre, esclusi numeri di telefono) + nuova
     azione `copy_to_clipboard` con `EXTRA_IS_SENSITIVE` (pattern clipboard sensibile già
     pronto dal lavoro E13). Oggi inesprimibile: manca l'azione clipboard e nessuna azione
     legge il payload del trigger; il trigger phone_state è fail-closed in P1.
  2. Geofence + phone_state + connectivity armabili (già a piano P2) — il primo esempio
     della home torna quando c'è.
  3. Azione "modalità aereo" via shell statico (`cmd connectivity airplane-mode enable`):
     GIÀ possibile oggi con Shizuku dentro run_shell; valutare un'azione tipizzata dedicata.
  4. Trigger sensori (accelerometro/caduta): P3+ eventuale, richiede listener always-on
     (FGS + battery) — fuori scope P2.

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

## 10. P1-5 capability, arm e bootstrap — **COMPLETATA (Claude, 2026-07-14)**

> Questa sezione era il piano della slice; è stata eseguita integralmente secondo la sequenza
> a/b/c/d raccomandata, inclusa la decisione consigliata di implementare subito la action
> statica (P1-5c). Vedere §18 per le decisioni, l'evidenza e i residui rimandati a P1-6.
> Il testo originale resta sotto come riferimento del contratto implementato.

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

Aggiornato dopo P1-5 (Claude, 2026-07-14) — i punti risolti sono marcati.

- ~~`Action.WhatsAppReply` statica è modellata ma non eseguita~~ → **RISOLTO in P1-5c**: eseguita
  via `NotificationReplyGateway`, capability pubblicata solo col listener grant.
- ~~Probe e requirements disallineati per la lane generativa~~ → **RISOLTO in P1-5a/b**: derivazione
  e set available/transient ora coprono trigger, invoke_llm, tool raw (incl. Shizuku) e `state.read`.
- ~~Validator e lane hanno contratti diversi~~ → **RISOLTO in P1-5a**: il validator impone il
  profilo esatto della lane (`GenerativeContract`); una rule non conforme non supera più la review.
- E13 non esiste in produzione; il fallimento onesto corrente è corretto. (Invariato → P1-6.)
- La lane tenta E13 soltanto su `channel_expired`; remove/update della notifica genera invece
  `reply_channel_unavailable`. Senza ampliare il set defer-eligible, il caso E13 più comune non offrirà
  la CTA manuale anche dopo avere implementato lo store cifrato. (Invariato → P1-6.)
- ~~**Privacy blocker** su `NotificationIngress`~~ → **RISOLTO in P1-5b**: gate sincrono in
  `registerPosted()`/`rehydrate()` e ricontrollo in `persistAndDispatch()`; test dedicati.
- **Minimizzazione dati:** → **Parzialmente risolto in P1-5b**: handle RemoteInput e righe
  `observed_conversations` ora solo per package WhatsApp trusted; il matching Notification generico
  resta intatto. Restano P1-6: retention/TTL, delete-on-revoke e filtro 1:1 del picker.
- `SettingsViewModel.revokePrivacy()` → **Parzialmente risolto in P1-5b**: un collector sullo
  `StateFlow` preferences in `ArgusRuntimeController` svuota il reply registry e riconcilia
  immediatamente (l'ingress è comunque chiuso dal gate sincrono). Resta P1-6 il coordinator
  completo: purge observed conversations/whitelist/deferred e UX della revoca.
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

## 15. Prima checklist per Claude — ESEGUITA (2026-07-14); checklist per la ripresa successiva

La checklist originale (1-8) è stata eseguita integralmente: handoff+piano letti, git verificato,
tre Markdown concorrenti preservati, i quattro test contrattuali letti prima di toccare i contratti,
P1-5 aperta in ordine a→b→c→d con TDD, un commit per sotto-slice pushato dopo gate verde, nessuna
decisione fuori piano (la static reply era la scelta consigliata dal piano stesso), e il gate device
è stato eseguito davvero sul OnePlus.

Checklist per chi riprende (P1-6):

1. Leggere il §18 di questo handoff e ripetere `git status --short` + verifica sync (`0 0`).
2. Non assorbire i tre Markdown concorrenti (§2): sono ancora nel working tree, intatti.
3. Prima di P1-6 leggere: `SettingsViewModel`, `OnboardingViewModel`, `ExecutionLogViewModel`,
   `AndroidUiHealth`, `RoomObservedConversationStore`, la sezione §11 (P1-6) qui e il piano P1.
4. P1-6 include: coordinator revoca privacy completo (purge observed/whitelist/deferred),
   store E13 cifrato + `DEFERRED` azionabile, defer-eligible esteso a `reply_channel_unavailable`,
   CTA separate `POST_NOTIFICATIONS` / listener / battery (manifest permission inclusa),
   picker whitelist dalle conversazioni osservate 1:1, retention/TTL, copy budget corretto.
5. Il contratto capability/validator è ora fonte di verità condivisa (`GenerativeContract`):
   non allargarlo senza aggiornare insieme lane, validator, requirements e probe con test.
6. Il gate reboot/LNP P0-B resta aperto e richiede Lorenzo fisicamente presente (§5.3).

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

## 18. Ripresa Claude 2026-07-14 — P1-5 completata

Sessione Claude Fable (Claude Code, macchina `negozio`), stesso branch, TDD per ogni slice
(test rossi verificati prima del codice; per il registrar anche una mutation check).

### 18.1 Cosa è stato implementato

**P1-5a (`e266fd9`, engine-core).** Nuovo `object GenerativeContract` in
`engine-core/.../model/Action.kt`: costanti condivise del profilo P1
(`notification|state`, `allowed_tools == ["whatsapp_reply"]`, `state.read`).
`CapabilityRequirements.forAction(InvokeLlm)` ora deriva anche `state.read` quando i
`contextSources` includono `state`. `DraftValidator.validateInvokeLlm` impone il profilo esatto
della lane con codici nuovi: `context_sources_empty`, `context_notification_required`,
`context_sources_duplicated`, `context_source_unsupported`, `allowed_tools_unsupported`
(nessuna normalizzazione case/alias). I codici esistenti (`tool_forbidden`, `tool_unknown`,
`reply_target_unbound`, ecc.) restano invariati, così come i loro test.

**P1-5b (`b7c25e7`, automation-android).** Nuovo boundary suspend
`GenerativeRuntimeReadiness` (+ impl `AndroidGenerativeRuntimeReadiness`: bearer via
`BridgeConfigurationStore.bearerToken()` fail-closed, privacy da `AppPreferencesStore`
StateFlow — niente runBlocking). `AndroidCapabilityProbe` (nuovo 4° parametro costruttore):
- `trigger.notification` e tool raw `whatsapp_reply` ⟺ listener grant; reason esatta
  `REASON_NOTIFICATION_LISTENER = "accesso alle notifiche non concesso"` nel manifest;
- `action.invoke_llm` ⟺ bearer + privacy + battery exemption (structural, mai transient);
- i raw tool Shizuku (`state.read`, `screen.*`, `toggle.set`, `app.launch`) ora stanno negli
  stessi set available/transient delle capability typed (la subtlety di fine §10 è chiusa);
- `whatsapp_reply` rimosso da `PHASE_UNAVAILABLE_TOOLS` (la reason "disponibile da P1" era
  falsa), mantenuto in `KNOWN_TOOLS` via `GenerativeContract`.
`NotificationIngress` riceve un gate sincrono `privacyAccepted: () -> Boolean`
(StateFlow.value dal DI): `registerPosted()` e `rehydrate()` non creano handle senza consenso,
`persistAndDispatch()` lo ricontrolla prima di record/dispatch.
`NotificationReplyHandleFactory.from()` conserva handle solo per package WhatsApp trusted;
`persistAndDispatch` filtra le observed conversations agli stessi package (i trigger
Notification generici continuano a dispatch-are). `ArgusRuntimeController` ora prende
`Flow<ShizukuGatewayStatus>` + `AppPreferencesStore` + `ActiveNotificationReplyRegistry` e
colleziona il flow privacy: alla revoca svuota il registry e riconcilia subito (serializzato
dal mutex esistente), senza aspettare ON_START.

**P1-5c (`8c857fd`).** `ShizukuActionExecutor` riceve `NotificationReplyGateway`;
`Action.WhatsAppReply` esce da `unsupported_phase` e invia il testo approvato dello snapshot al
target congelato dal `FireContext` (package/key/conversation/eventId del trigger verificato).
Eventi non-Notification → `reply_event_unverified`; i codici del gateway passano invariati
(`reply_channel_unavailable`, `channel_expired`, ecc.), one-shot invariato. Solo ora la probe
pubblica `action.whatsapp_reply`, sotto lo stesso listener grant del tool raw.

**P1-5d (`9a84c3b`).** `TimeAlarmArmedAutomationRegistrar` → `AndroidArmedAutomationRegistrar`
(stesso file `RuntimeAdapters.kt`, nuovo parametro `FirePolicySnapshotProvider`): Time invariato
via coordinator AlarmManager; Notification verifica `trigger.notification` nello snapshot
capability + riga persistita ARMED/enabled, senza subscription OS per-rule né service; trigger
non implementati e failure dello snapshot → `false` (ApprovalFlow esegue già il rollback onesto).
La quarantena post-arm passa dal `CapabilityReconciler` esistente, che con la probe nuova vede
le revoche listener/privacy/token/battery come structural missing → `NEEDS_REVIEW` con CAS.

**Gate device (`b5d9578`).** `AndroidCapabilityProbeInstrumentedTest` aggiornato al costruttore
readiness-aware + nuovo test: sul OnePlus reale (package instrumentato senza grant) le letture
listener/battery API 36 rispondono false senza lanciare e la probe non pubblica nulla di
notification/generativo, con la reason esatta nel manifest. Eseguito con il workaround §12
(install + `am instrument` diretto): `OK (2 tests)`; package di test disinstallato, `zen_mode=0`,
nessun grant modificato.

### 18.2 Evidenza gate

- Host (ripetuto dopo ogni slice e a fine lavoro): `:engine-core:test` 114/114,
  `:data:testDebugUnitTest` 45/45, `:automation-android:testDebugUnitTest` 90/90,
  `lintDebug` 0 errori (9 warning preesistenti), `:app:assembleDebug` ok.
- Device: `OK (2 tests)` su CPH2747 / Android 16 / API 36 via ADB wireless.
- Matrice §10: righe Requirements/Validator/Probe/Reconciler/Ingress privacy/Data minimization/
  Handle minimization/Executor statico coperte da test host; "Revoca privacy con Brain in corso"
  coperta dalla coppia collector-test (registry ripulito) + `GenerativeActionLaneTest` esistente
  (rivalidazione finale blocca l'invio); "due foreground + Shizuku concorrenti" resta garantita
  dal `reconcileMutex` (invariato).

### 18.3 Decisioni prese (tutte dentro il perimetro del piano)

1. Static reply implementata subito (P1-5c), come consigliato dal piano — nessuna domanda aperta.
2. `action.whatsapp_reply` segue il **solo** listener grant: il consenso privacy chiude già
   l'ingest a monte (nessun handle ⇒ nessun evento ⇒ fail-closed al fire), coerente col §10.
3. Il filtro observed-conversations ai package WhatsApp è stato fatto qui (la matrice §10 lo
   chiedeva per P1-5); retention/TTL/purge-on-revoke restano P1-6 come da piano.
4. Il collector privacy vive in `ArgusRuntimeController` (opzione "collector sullo StateFlow"
   suggerita dal §10 P1-5d): niente coordinator nuovo prima di P1-6.
5. Nessuna modifica a lane, journal, bridge o Hermes: contratti P1-0…P1-4 intatti.

### 18.4 Residui espliciti per P1-6 (oltre al §11)

- Coordinator revoca privacy completo: purge `observed_conversations`, whitelist e deferred
  (il registry e il reconcile immediato sono già coperti dal controller).
- Store E13 cifrato + `onSendNow` + defer-eligible esteso a `reply_channel_unavailable`.
- CTA/salute separati per `POST_NOTIFICATIONS`, listener access e battery exemption
  (manifest: manca ancora `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, da aggiungere con la CTA).
- Picker whitelist dalle osservate 1:1; retention/TTL e display name in chiaro (200 righe).
- Copy budget "disponibile da P1" in `SettingsViewModel.onBudgetChange()` (è P3, correggere).
- La UI Settings usa ancora `notificationsGranted` per lo step "accesso notifiche" (§gap 10 del
  piano P1): non toccato in P1-5, è materia P1-6.

### 18.5 Note operative per chi riprende (Windows/questa macchina)

- **PowerShell + Gradle**: mai chiudere la pipeline con `Select-Object -First N` su un comando
  gradle in corsa — PowerShell stacca la pipe e il build continua orfano (o muore a metà),
  l'exit code letto non è affidabile e i timestamp ingannano. Pattern usato e affidabile:
  `gradlew ... *> file.log; $LASTEXITCODE; Select-String file.log`.
- A inizio sessione la cache `~/.gradle/caches/8.13/transforms` era corrotta (metadata.bin
  illeggibili, probabilmente daemon della sessione precedente): risolto con `gradlew --stop` +
  delete della sola directory `transforms` (si rigenera).
- ADB wireless attivo su `100.74.117.9:5555` per tutta la sessione; un solo comando ADB alla
  volta, come da §12.

### 18.6 Stato consegna

- HEAD: `b5d9578`; `git rev-list --left-right --count origin/feat/argus-p0b-dry...HEAD` = `0 0`.
- Working tree: soltanto i tre Markdown concorrenti del §2 (intatti, mai staged) più questo
  aggiornamento dell'handoff.
- Device: ADB raggiungibile, Shizuku attivo, DND off, nessun package di test residuo di questa
  sessione (restano i package P0-B elencati al §5.2, non toccati).
- Gate esterni invariati: reboot/LNP P0-B (§5.3) e P1-7 reale richiedono Lorenzo.

## 19. Ripresa Claude 2026-07-14 sera — P1-6 completata

Stessa sessione del §18, proseguita su mandato esplicito di Lorenzo. Quattro commit, TDD, gate
host completo dopo ogni slice (ora include anche `:app:testDebugUnitTest`), mutation check sul
sink (payload in chiaro ⇒ test rossi).

### 19.1 Cosa è stato implementato

**P1-6a (`7d91fb4`).** Room v9: tabella `deferred_replies` — una riga per action generativa,
PK (executionId, actionIndex), `packageName` (solo per aprire l'app giusta al tap),
`createdAt/expiresAt/consumedAt` e `payload` che contiene ESCLUSIVAMENTE ciphertext AES-GCM
(chiave dedicata non esportabile, alias `argus_deferred_reply_aes_v1`, cipher in
`DeferredReplyCrypto.kt` con key provider iniettabile per i test). FK CASCADE su
`fire_claims`: la retention del journal elimina anche il ciphertext orfano; la maintenance
purga scadute e consumate. `PersistentDeferredReplySink` sostituisce il sink unavailable
(rimosso): `DEFERRED` esiste solo dopo persistenza cifrata confermata, TTL 24h
(`DEFAULT_TTL_MILLIS`). La lane ora considera defer-eligible `channel_expired` **e**
`reply_channel_unavailable` (`DEFER_ELIGIBLE_CODES`); tutto il resto resta FAILED, sink
fallito ⇒ FAILED onesto. Migrazione 8→9 con `MigrationTest` esteso (ogni versione legacy → 9,
inclusa la verifica FK CASCADE su v8→v9).

**P1-6b (`262310a`).** `DeferredReplyManager` risolve la CTA E13: audit row → executionId
(mappa interna del ViewModel, mai esposta) → riga valida → decrypt SOLO dopo il tap →
clipboard con flag `EXTRA_IS_SENSITIVE` → apertura WhatsApp (mai invio automatico) →
`markConsumed` CAS one-shot. Il testo non entra in messaggi UI, log o stato
(`DeliverableReply` non è una data class). `PrivacyRevocationCoordinator` centralizza la
revoca: chiude il gate (preference) PRIMA, poi svuota il reply registry, `clear()` su
observed conversations (nuovo metodo del boundary engine-core) e sulle deferred; esiti
tipizzati `Revoked | RevokedWithResidualData | Failed`, idempotente e richiamabile su purge
parziale. **Decisione documentata**: la whitelist contatti si CONSERVA alla revoca (è
configurazione esplicita dell'utente; le regole generative armate vengono comunque
quarantenate dal reconcile). `SettingsViewModel.revokePrivacy()` usa il coordinator.

**P1-6c (`1ec7b2b`).** `SettingsState` separa `notificationsGranted` (pubblicazione) da
`notificationListenerGranted` (lettura/reply) con due righe salute e CTA distinte
(`onOpenNotificationListenerFix` → `ACTION_NOTIFICATION_LISTENER_SETTINGS`); fix del gap 10
del piano (la UI usava il dato sbagliato). Onboarding: lo step notifiche completa solo con
entrambi i grant e la CTA è a due stadi nell'ordine prescritto. Battery: manifest
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + dialogo package-specific da gesto utente con
fallback alla lista; `ManifestHardeningTest` (nuovo unit test del modulo app) blinda
allowBackup=false, dataExtractionRules e la nuova permission. Picker whitelist:
`observedWhitelistCandidates()` filtra WhatsApp trusted, `isGroup == false`, non già in
whitelist, limite 20; dialog nel NavHost con id mascherati; l'editor raw resta "Inserimento
manuale". Copy budget corretto (cooldown 60 s per regola · budget globale P3).

**Retention (`e0dba9c`).** Le observed conversations condividono la max-age del journal
(30 giorni): oltre al bound 200 righe e al delete-on-revoke, un display name non più visto
esce dal DB alla maintenance.

### 19.2 Evidenza

- Gate host per ogni slice: engine-core 114, data 49, automation-android 106, app 3
  (ManifestHardeningTest), lint 0 errori, `app:assembleDebug` ok (conteggi finali).
- Device (CPH2747, via `am instrument` §12): `dev.argus.data.MigrationTest` → `OK (8 tests)`
  (migrazioni reali v1…v8→v9 con dati legacy e FK CASCADE); package test disinstallato.
- Mutation check: sink senza cifratura ⇒ 2 test rossi; ripristinato.

### 19.3 Residui P1-6 → P1-7/P1-8

- Il flusso `onSendNow` è coperto da unit test Robolectric (clipboard + consumo one-shot);
  la verifica visiva della CTA sul device rientra nello smoke dei sei schermi di P1-8.
- Il picker si popola solo con listener grant e privacy attivi: la prova live è parte della
  caratterizzazione P1-7 con Lorenzo.
- Nessun nuovo residuo privacy: i punti del §11 risultano tutti implementati o
  esplicitamente decisi (whitelist conservata alla revoca).

### 19.4 Nota infrastruttura macchina `negozio`

Durante la sessione Tailscale è morto: il servizio Windows **Helper IP (`iphlpsvc`)**
risultava DISABILITATO (dipendenza di Tailscale). Ripristinato con
`Set-Service iphlpsvc -StartupType Automatic; Start-Service iphlpsvc; Start-Service Tailscale`.
Se il push verso l'hub fallisce con timeout su `100.73.8.92:22`, controllare prima questi due
servizi.

### 19.5 Prossimi passi esatti (per chi riprende)

1. ~~**P1-7 sintetico**~~ → **FATTO (`81750f2`)**: `GenerativeEndToEndTest` (host Robolectric,
   componenti di produzione reali, tre scenari: happy path con journal convergente e duplicate
   soppresso, notifica rimossa durante la generazione → `DEFERRED` cifrato, gruppo → zero
   fire; mutation check sul wiring) + `GenerativeEndToEndInstrumentedTest` eseguito sul
   OnePlus (`OK (1 test)`, RemoteInput consegnato davvero dal sistema, Brain locale, nessun
   grant modificato, package di test rimosso). Fixato anche un flake reale in
   `ExecutionLogViewModelTest` (cancel+join del viewModelScope prima di `resetMain`).
2. **P1-7 reale + gate reboot P0-B**: unica sessione con Lorenzo (§5.3 e §11 P1-7) —
   caratterizzazione WhatsApp vera (1:1 + gruppo, stabilità identità), Esempio 3 live con
   contatto whitelistato via picker, negativi on-device, misure Doze.
3. **P1-8**: full gate senza cache, clean install, smoke sei schermi, riconciliazione dei tre
   Markdown concorrenti, aggiornamento spec/bridge contract/audit, review, merge (solo con
   tutti i gate verdi).

## 20. Gate reboot P0-B — CHIUSO (Claude + Lorenzo, 2026-07-14 sera)

Sessione live con Lorenzo fisicamente al telefono, protocollo §5.3/§12 seguito integralmente.

1. **Recovery time alarm (§5.3.1)**: `schedule` OK (regola sentinella EXACT a +2h, marker
   verificati), riavvio fisico (boot 35→36), Shizuku riattivato via starter nativo
   (`libshizuku.so` dall'APK dir — lo `start.sh` classico non esiste su questo device),
   `verify` OK: registrazione AlarmManager ricreata da BOOT_COMPLETED (`updatedAtMillis`
   avanzato, stesso fingerprint/eventAt, EXACT), processo ricreato, regola mai scattata,
   DND invariato, cleanup completo.
2. **Degradato fail-closed (§5.3.2)**: con Shizuku giù post-boot,
   `AndroidCapabilityProbeInstrumentedTest` → `OK (2 tests)` (manifest coerente, niente
   capability Shizuku pubblicate, listener/battery fail-closed).
3. **LNP Android 16 (§5.3.3)**: flag `RESTRICT_LOCAL_NETWORK` abilitato PRIMA del riavvio;
   post-boot con Tailscale sospeso il nuovo `LocalNetworkProbeInstrumentedTest`
   (socket dalla SocketFactory della Network Wi-Fi, endpoint 192.168.0.1:80) → **denied**
   pulito; con Tailscale riattivato e flag ancora attivo, health dall'app → **Hermes
   raggiungibile, 46 ms**. Nota: la prima esecuzione del probe è fallita per
   `ACCESS_NETWORK_STATE` mancante nel manifest (ora aggiunta, permission normal).
4. **Ripristino (§5.3.4)**: `am compat reset` non basta a runtime (le regole di rete
   per-UID restano fino al riavvio, come il contratto bridge prevedeva) → secondo riavvio →
   baseline **allowed** (17 ms). Flag pulito (`dumpsys platform_compat`: nessun override per
   `dev.argus`; resta un vecchio override innocuo `dev.argus.brain.test=false` di P0-B).

Stato device a fine gate: Shizuku attivo, `SCHEDULE_EXACT_ALARM: default`, `zen_mode=0`,
ADB ripristinato su `:5555`, onboarding produzione COMPLETATO da Lorenzo (privacy, bearer
Hermes configurato — trasferito via pipe ssh→stdin→file one-shot sul device, mai in
chat/command line, file poi cancellato — listener access e battery exemption concessi con le
CTA nuove P1-6). Commit tooling: `2ca4a10`.

**Il blocker principale del merge è caduto.** Restano per il merge: P1-7 reale
(caratterizzazione WhatsApp + Esempio 3 live, in corso nella stessa serata) e P1-8.

## 21. P1-7 reale — COMPLETATA (Claude + Lorenzo, 2026-07-14 sera)

Stessa sessione live del §20, subito dopo la chiusura del gate reboot. Lorenzo al telefono,
contatto reale "Ottica Marci" (secondo numero suo) whitelistato via picker P1-6.
**Esempio 3 della spec è passato dal vivo**, e la caratterizzazione ha scovato quattro bug
reali, tutti fixati in TDD nella stessa serata.

### 21.1 Esempio 3 live — VERDE

Regola compilata da Hermes in linguaggio naturale ("rispondi tu a Ottica Marci tra le 9-13 /
16-20..."), armata da Lorenzo dalla review, messaggio WhatsApp vero in fascia oraria:

- risposta generativa consegnata su WhatsApp in **8 520 ms end-to-end** (journal SUCCEEDED,
  CAS one-shot); lato bridge `act ... elapsed_ms=7398` su gpt-5.5; compile precedente 12 272 ms;
- nessun testo/target/token nei log Android né nell'audit (verificato durante la sessione);
- negativo gruppo: stesso contatto whitelistato dentro un gruppo nuovo → conversazione
  osservata con `isGroup=1`, **zero FIRED** (fail-closed strutturale confermato dal vivo);
- cooldown 60 s osservato sul campo (vedi eco sotto: il secondo evento è stato soppresso).

### 21.2 Quattro bug reali trovati dalla caratterizzazione (tutti fixati)

1. **Tag WhatsApp = Base64 con newline finale** → la notification key conteneva un control
   char e il parser la rifiutava: nessuna conversazione osservata, picker vuoto. Fix
   (`a34b6d6`): la key è un identificatore opaco DI SISTEMA — accettata intatta (solo
   blank/length), il `conversationId` resta severo; test di caratterizzazione con la key
   reale. Nel debug sono stati aggiunti log ingress privacy-safe (`ArgusIngress`, solo
   package + booleani).
2. **`invoke_llm` assente da `manifest.availableTools`** → Hermes (regola 1 del prompt: usa
   SOLO i tool elencati) ripiegava su una `whatsapp_reply` statica: "Draft con risposta
   fissa". Fix (`a34b6d6`): la probe pubblica `invoke_llm` quando il runtime generativo è
   pronto (bearer+privacy+battery), in `unavailableTools` con motivo altrimenti.
3. **Hermes lasciava `isGroup=null` sul trigger** → arm bloccato ("reply solo su chat 1:1
   verificata"). Fix (`a34b6d6`, `ops/hermes/bridge.py` REGOLA 9): reply WhatsApp richiedono
   `conversationId` whitelistato E `isGroup=false` ESPLICITO; deploy atomico su hermes
   (backup `bridge.py.pre-isgroup-rule-20260714`, 15/15 test, unit restartata).
4. **Eco della reply = "doppio arm sul singolo messaggio"** (scoperto da Lorenzo): ~8,5 s
   dopo l'invio WhatsApp ripubblica la notifica con la NOSTRA risposta come ultimo messaggio;
   per il vecchio eventId (postTime nel digest) era un evento nuovo — solo il cooldown ha
   evitato il fire (un loop senza cooldown sarebbe stato possibile). Fix strutturale
   (`b445761`), doppio: (a) update con ultimo messaggio self-authored
   (`latestMessageFromSelf`, sender null o match con `EXTRA_MESSAGING_PERSON`) → MAI un
   evento; (b) eventId keyed sul **timestamp del messaggio** MessagingStyle
   (`latestMessageTimestampMillis ?: postedAtMillis`), title fuori dal digest → i repost
   cosmetici ("2 nuovi messaggi") diventano `SUPPRESSED_DUPLICATE` onesti. Mutation check
   eseguito. Chiude la riga DoD "duplicate event fail-closed" senza dipendere dal cooldown.

### 21.3 Fix UX della stessa sessione

- **Campo chat coperto dalla tastiera** (segnalato da Lorenzo, target 36 edge-to-edge):
  `padding(innerPadding) + consumeWindowInsets + imePadding` sul NavHost (`a34b6d6`).
- **Hash nella review** ("sta sbobba di numeri", promesso a Lorenzo, consegnato in P1-8):
  `RuleRenderMapper` accetta una mappa conversationId→displayName risolta da
  `ContactWhitelistStore` (store fidato, mai prosa LLM) e la review/lista/dettaglio mostrano
  "da **Ottica Marci** (identità verificata, chat 1:1)"; senza label l'hash resta integrale.
  Fedele per costruzione: il `TriggerMatcher` dà al conversationId precedenza esclusiva sul
  sender. Commit `758f5e9`.

### 21.4 Residui P1-7 (minori, non bloccanti per il merge)

- **Misura Doze/schermo spento**: non eseguita formalmente (telefono in uso tutta la
  sessione). La battery exemption è attiva; la misura si completa da sola alla prima
  risposta con schermo spento da tempo — Lorenzo la osserva nell'uso quotidiano.
- **Verifica live del fix anti-eco** (`b445761` installato sul device a fine sessione,
  listener ribound): al prossimo messaggio reale l'eco NON deve produrre alcun evento
  (né FIRED né SUPPRESSED_COOLDOWN spurio). Attesa dall'uso normale. Primo indizio
  positivo già visto: al rehydrate del listener le notifiche in shade (inclusa quella con
  la reply come ultimo messaggio) vengono scartate dal parser senza generare eventi.
- La regola "Rispondi a Ottica Marci quando occupato" è rimasta ARMATA sul telefono per
  scelta di Lorenzo (funziona; risponderà nelle fasce orarie configurate).

## 22. P1-8 — CHIUSA (Claude + Lorenzo, 2026-07-14)

1. **Fix UX nomi whitelistati** (`758f5e9`, TDD): vedi §21.3.
2. **Full gate senza cache**: `clean` + test degli 8 moduli + lint (automation-android e
   app) + `assembleDebug`, `--no-build-cache`: **BUILD SUCCESSFUL in 1m49s, 344 task
   eseguiti, 331 test verdi** (engine-core 117, automation-android 111, data 49,
   brain-android 21, device-tools 11, core-shizuku 10, ui 9, app 3), lint 0 errori.
3. **Riconciliazione dei tre Markdown concorrenti**: erano modifiche Codex della sessione
   P0-B (rerun post-clean-install fermato dalla quota provider, caveat UID/grant Shizuku).
   Preservate verbatim in `e359b4b`; residui chiusi in `d3c93a7` (le condizioni audit 3 e 6
   sono soddisfatte dai fatti della serata: gate reboot §20 e compile live §21).
4. **Doc aggiornata** (`d3c93a7`): audit (condizioni 3+6 ✓), `CLAUDE.md` (P0-B gate tutti
   chiusi + stato P1), bridge contract (regole prompt server, REGOLA 9), replan checkbox.
5. **Smoke sul device di produzione** (APK del full gate, install `-r` SENZA wipe del setup
   di Lorenzo): cold start 338 ms senza crash; navigazione read-only via ADB con screenshot
   verificati di Chat, Automazioni (card con "da Ottica Marci (identità verificata,
   chat 1:1)" — fix UX live), Dettaglio (badge cloud, privacy note, condizioni orarie,
   goal generativo), Log (Esempio 3 FIRED 1/1 + eco storica soppressa dal cooldown),
   Sistema (salute tutta verde, due righe notifiche distinte, token protetto configurato).
   L'onboarding (sesto schermo) è stato eseguito DAL VIVO da Lorenzo in questa stessa
   serata (privacy → bearer → listener → battery). **Nota per chi riprende**:
   `ArgusNavigationInstrumentedTest` NON va lanciato su questo device configurato — il suo
   tearDown resetta privacy/onboarding (pensato per install pulite da laboratorio).
6. **Merge su master**: eseguito a valle di questa chiusura con tutti i gate verdi
   (P0-B esterni §20, P1-7 reale §21, full gate e smoke qui sopra); hash e dettagli
   annotati nel ledger §4 con il commit di merge.
