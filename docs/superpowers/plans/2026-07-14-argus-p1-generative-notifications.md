# Argus — Piano P1: notifiche WhatsApp e lane generativa

Data: 2026-07-14  
Base: `feat/argus-p0b-dry` dopo chiusura host/device P0-B  
Spec master: `docs/superpowers/specs/2026-07-12-hermes-android-agent-design.md` rev 4  
Precedenza: commander replan P0-B per gli invarianti runtime e di sicurezza.

## Obiettivo verificabile

Portare l'Esempio 3 della spec a un E2E reale e fail-closed:

1. `NotificationListenerService` riceve una notifica WhatsApp 1:1;
2. estrae un'identità di conversazione osservata sul device, `isGroup` e un handle
   `RemoteInput` effimero;
3. l'Engine applica matcher, whitelist, condizioni, cooldown e fire policy;
4. `InvokeLlm` viene accodato e l'Engine ritorna subito `Submitted`;
5. la lane chiama `/act` Hermes one-shot con solo il contesto approvato;
6. prima dell'invio ricontrolla fingerprint, regola, whitelist, conversazione e notifica attiva;
7. invia con `RemoteInput`, oppure registra `DEFERRED` e offre consegna manuale esplicita;
8. nessuna reply di gruppo, verso ID diverso, su regola revisionata o dopo replay tardivo.

## Findings bloccanti emersi dal gap analysis

1. `Brain` e `argus-bridge` espongono solo `compile`; `/act` non esiste.
2. `GenerativeLane.trySubmit(FireContext, InvokeLlm)` non riceve l'indice azione né il
   fingerprint approvato. Non può aggiornare il journal corretto né rilevare TOCTOU dopo edit.
3. La lane può partire prima che l'Engine abbia scritto `SUBMITTED`: senza handshake Room il
   risultato finale potrebbe essere sovrascritto dal journal iniziale.
4. `ExecutionStatus`/`ActionJournalOutcome` non modellano `DEFERRED`; la UI lo prevede ma il
   mapper non può mai produrlo.
5. La maintenance interrompe solo `RUNNING`: un processo ucciso lascia `SUBMITTED` eterno.
6. Non esiste `NotificationListenerService`, registry degli handle attivi o gateway RemoteInput.
7. Il registrar di arm accetta esclusivamente `Trigger.Time`; Notification è sempre non armabile.
8. Il capability probe non pubblica trigger Notification, `InvokeLlm` o reply e non collega la
   battery exemption richiesta da P1.
9. `CapabilityRequirements` conserva i tool raw di `InvokeLlm`, mentre il probe espone quasi solo
   capability `action.*`: senza una mappa coerente una regola generativa resta bloccata.
10. Settings/onboarding usano `notificationsGranted` anche per lo step “accesso notifiche”; il dato
    distinto `notificationAccess` viene calcolato ma ignorato. Il callback apre solo il permesso di
    pubblicazione, non `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
11. L'editor whitelist chiede all'utente un `conversationId` raw che normalmente non può conoscere.
12. `ExecutionLogViewModel.onSendNow` è ancora uno stub; non esiste uno store cifrato della reply
    differita né lookup audit-id → execution-id.

## Decisioni corrette prima del codice

- L'identità primaria è `Notification.shortcutId`, hashata e namespaced col package. In sua assenza
  si può usare una `Person.uri` osservata, anch'essa hashata e namespaced; i valori raw non vengono
  persistiti. `tag`, titolo e display name non diventano mai ID trusted. Se nessuna fonte stabile è
  presente, `conversationId=null` e le reply restano bloccate.
- `Notification.EXTRA_IS_GROUP_CONVERSATION` è autorevole solo se presente. Assenza → `null` e
  quindi nessuna reply automatica.
- `StatusBarNotification.key`, `Notification.Action`, `RemoteInput` e `PendingIntent` sono handle
  effimeri in memoria: non vengono serializzati né promessi dopo process death.
- L'event ID è un digest di metadati evento, mai testo/ID raw. Contenuto e ID destinatario non
  entrano in logcat, audit diagnostico o messaggi d'errore.
- `/act` non restituisce un destinatario: restituisce soltanto il testo. Il target resta legato
  localmente alla conversazione verificata dal trigger.
- Contesti P1 ammessi: `notification` e `state`. `screen`/vision e tool interattivi restano P3.
- La lane è bounded, single-consumer e in-memory in P1. Se il processo muore non rigenera e non
  invia tardi: la maintenance marca il job `INTERRUPTED`. Esecuzione durevole/FGS short-lived resta
  una decisione P2 misurata, non un service persistente introdotto di nascosto.
- Se il canale RemoteInput scade dopo la generazione, la reply viene cifrata con Android Keystore,
  conservata con TTL breve e mostrata come `DEFERRED`. Solo un tap esplicito copia/apre WhatsApp.
- Nessuna UI injection automatica come fallback e nessuna reply nei gruppi.

Riferimenti Android verificati:

- `NotificationListenerService.onNotificationPosted` consegna la `StatusBarNotification` sul main
  thread e richiede il grant utente;
- `StatusBarNotification.key` identifica l'istanza della notifica, non la conversazione;
- `Notification.shortcutId` e `EXTRA_IS_GROUP_CONVERSATION` sono metadati platform;
- `RemoteInput.addResultsToIntent(...)` popola l'Intent che viene poi inviato al `PendingIntent`.

## Sequenza di implementazione

### P1-0 — Contratti core e journal asincrono

- Aggiungere a `FireContext` `approvalFingerprint` e `actionIndex`; l'Engine costruisce un context
  per azione e i test provano indice/fingerprint.
- Aggiungere `ExecutionStatus.DEFERRED`, `ActionJournalOutcome.DEFERRED` e `deferredCount`.
- Introdurre un boundary `SubmittedActionJournal` con:
  - attesa reattiva finché action row e execution sono entrambe `SUBMITTED`;
  - risoluzione CAS `SUBMITTED → SUCCEEDED|FAILED|DEFERRED`;
  - ricalcolo transazionale dei contatori e dello stato globale;
  - nessuna risoluzione se execution/action non coincidono o sono già terminali.
- Estendere la maintenance a `RUNNING` e `SUBMITTED` stale; mai replay automatico.
- Migrazione Room v6→v7 e test migrazione/DAO/concorrenza.

Gate: test rossi prima del fix per race worker-before-journal, doppia completion e stale submitted.

### P1-1 — Contratto `/act` Android e Hermes

- Estendere `Brain` con `act(FireContext, goal, contextSources, allowedTools)` e un `ActResult`
  typed: `contextSources` è esplicito per impedire che lo stato venga inviato quando non approvato.
- Request `/act` v1 strict, autenticata e idempotente; request ID deterministico da
  execution-id + action-index.
- Payload minimizzato: goal approvato, notification text/title/sender come blocco untrusted,
  `is_group=false` e stato solo se richiesto. Mai notification key o coordinate.
- P1 accetta solo output reply e tool `whatsapp_reply`; response senza target.
- Stessi limiti, TLS, bearer, cancellation e body cap di `/compile`.
- Estendere `ops/hermes/bridge.py`, test HTTP/contract e documento del protocollo; deploy atomico,
  auth negativa e live act senza stampare contenuti.

Gate: MockWebServer + test server, protocol/schema mismatch, injection, target assente, quota 503,
idempotency replay e cancellation.

### P1-2 — Parser notifiche e identità conversazione

- Parser puro/testabile da una snapshot platform a `TriggerEvent.NotificationPosted`.
- Priorità ID: shortcut hashato/namespaced → Person URI hashata/namespaced → null.
- Estrarre `isGroup` soltanto da metadata esplicito; testo dall'ultimo MessagingStyle con fallback
  bounded a `EXTRA_TEXT`; rimuovere control chars e applicare cap.
- Event ID digest; ignorare package Argus, group summary e notifiche prive di payload utile.
- Store locale delle conversazioni osservate con solo display name bounded, ID e `isGroup`; niente
  testo dei messaggi. Serve al picker whitelist, non concede fiducia automaticamente.

Gate: fixture 1:1/gruppo/senza metadata/spoof/update duplicato e zero PII nei diagnostici.

### P1-3 — Listener e gateway RemoteInput

- `ArgusNotificationListenerService` non esportato, protetto da
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`.
- Al connect reidrata solo gli handle delle notifiche attive, senza ridispatchare eventi vecchi.
- Al post aggiorna il registry e invia l'envelope all'Engine su application scope; al remove elimina
  l'handle.
- Gateway reply ricontrolla package, key attiva, conversationId, `TriggerEventId` della versione
  corrente, `isGroup=false`, RemoteInput free-form e PendingIntent; `CanceledException` diventa
  `channel_expired`, mai successo fittizio. L'handle è one-shot e viene consumato atomicamente.

Gate: Robolectric/parser + instrumented notification sintetica; il test reale WhatsApp richiede
grant esplicito di Lorenzo e non stampa il messaggio.

### P1-4 — Lane generativa e TOCTOU finale

- Channel bounded single-consumer; `trySubmit` fa solo snapshot/accodamento e ritorna subito.
- Worker attende l'handshake journal `SUBMITTED` prima di chiamare il brain.
- Prima del brain: privacy/token/battery/context sources; dopo il brain e prima dell'invio:
  automazione ancora ARMED, fingerprint invariato, stessa action all'indice, trigger e ID evento
  coerenti, whitelist corrente e policy reply ancora valide.
- Successo aggiorna journal; errori bridge/timeout/policy aggiornano `FAILED`; canale scaduto crea
  record `DEFERRED` cifrato e notifica locale.
- Una seconda completion o redelivery non può reinviare.

Gate: engine non attende, edit/delete/revoca whitelist durante LLM bloccano l'invio, doppia worker
completion CAS, process cancellation e queue full fail-closed.

### P1-5 — Capability, arm e bootstrap

- Pubblicare `trigger.notification` solo col listener grant.
- Pubblicare `action.invoke_llm` solo con battery exemption e configurazione runtime sufficiente;
  `action.whatsapp_reply`/tool raw solo col listener.
- Allineare tool raw e capability persistite; niente alias permissivi.
- Registrar Notification verifica il grant globale senza registrazione per-rule; Time invariato.
- Cambio grant/foreground riconcilia e porta a `NEEDS_REVIEW` per revoca strutturale.

Gate: regola Notification non armabile senza grant/exemption; revoca post-arm fail-closed.

### P1-6 — Settings, onboarding, whitelist e deferred UI

- Stato salute combinato ma veritiero: permesso pubblicazione e accesso listener distinti.
- CTA: prima `POST_NOTIFICATIONS`, poi `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- Battery CTA apre la pagina package-specific `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` solo
  dopo gesto utente e con permission manifest appropriata; fallback alla lista impostazioni.
- Picker whitelist usa conversazioni 1:1 osservate; input raw resta diagnostico avanzato, non flusso
  principale. Nessun gruppo selezionabile.
- `onSendNow(logId)` risolve audit→execution→deferred, decifra solo dopo tap, copia e apre WhatsApp;
  segna consumato. TTL e delete su revoke privacy.
- Aggiornare copy P0-B ormai falso.

Gate: ViewModel/Compose test e nessuna reply plaintext nei DB/log di diagnostica.

### P1-7 — E2E e hardening device

- Synthetic E2E con una notification test controllata e RemoteInput receiver locale.
- Real WhatsApp characterization, con Lorenzo presente:
  - concedere listener access;
  - ricevere un messaggio 1:1 e uno di gruppo;
  - registrare solo presenza/sorgente/hash e `isGroup`, non contenuto;
  - scegliere l'identità realmente stabile sul OnePlus/WhatsApp installato.
- E2E reale Esempio 3 con contatto whitelistato, Hermes `/act`, RemoteInput e audit finale.
- Negative: gruppo, ID spoof, notifica rimossa durante LLM, whitelist revocata, rule edit, provider
  503, queue full, process death e duplicate event.
- Misurare con schermo spento/Doze e battery exemption; niente promessa di durability P2.

### P1-8 — Chiusura

- Full test/lint/build senza cache, clean install e smoke dei sei schermi.
- Aggiornare spec, bridge contract, CLAUDE, ledger e audit finale.
- Commit piccoli, push su hub Unraid, review finale; merge solo con P0-B reboot/LNP e P1 gate verdi.

## Definition of Done P1

- Esempio 3 passa realmente su una conversazione 1:1 whitelistata.
- Gruppi, ID non whitelistati, metadata ambigui e regole stale non possono inviare.
- L'Engine ritorna `Submitted` senza attendere Hermes; il journal converge a un terminale onesto.
- E13 produce `DEFERRED` azionabile, non un falso successo.
- Nessun testo/target/token in logcat, CLI, audit diagnostico o backup.
- Battery exemption e notification listener sono verificati separatamente e visibili in UI.
- Nessun service persistente, replay tardivo o fallback UI automatico introdotto fuori scope.
