# Argus — Commander replan: hardening e completamento P0-B

Data: 2026-07-13  
Base verificata: `5a993d1` + icona `8950bc1`  
Device: OnePlus CPH2747, Android 16/API 36, ADB `100.74.117.9:5555`, Shizuku 13.6 attivo.

## Perché esiste questo emendamento

Le review P0-A/P0-B hanno verificato la conformità ai brief, ma i brief conservano
assunzioni che non reggono alla frontiera di sicurezza reale. Sono riproducibili:

- whitelist reply fail-open e bypass con `replyTargetSender=false`;
- `WhatsAppReply` statico privo delle stesse barriere destinatario;
- cron DST che può restituire un istante non strettamente futuro;
- cooldown check/record non atomico e nessuna idempotenza evento/esecuzione;
- eccezione di una singola azione che interrompe le successive e cancellation inghiottita;
- `/compile` che ignora `schema_version`, `DeviceState` non inviato e body illimitato;
- store/audit insufficienti per ViewModel, recovery e lane generativa;
- FGS persistente e `USE_EXACT_ALARM` incompatibili con il modello Android corrente.

Il piano originale resta utile come catalogo dei task, ma l'ordine e alcuni contratti
sono sostituiti da questo documento.

## Regole di esecuzione

1. Un solo writer; commit piccoli e reversibili.
2. Ogni bug riprodotto diventa un test rosso prima del fix.
3. Nessun effetto privilegiato senza execution ID, policy e audit correlabile.
4. Sicurezza fail-closed: metadata sconosciuto, capability revocata o schema futuro
   bloccano l'arm/esecuzione e portano a `NEEDS_REVIEW` quando opportuno.
5. Verifica sul OnePlus dopo ogni modulo Android; niente modifica manuale dell'orologio.
6. Payload sensibili minimizzati; niente testo notifica/contact ID nei log diagnostici.

## Sequenza vincolante

### H0 — baseline e piano

- [x] Snapshot branch/remoto e working tree.
- [x] ADB e Shizuku verificati sul device.
- [x] Icona isolata in commit dedicato.
- [x] Full baseline test/lint su tutti i moduli (96 test debug, 0 failure;
  APK costruito; lint app: solo `OldTargetApi`).

### H1 — security/runtime core (prima di Shizuku)

- [x] Reply policy unica e fail-closed per reply statiche/generative.
- [x] Rimuovere il bypass pubblico `replyTargetSender=false` o renderlo non armabile.
- [x] Validazione completa di tempo, location, state, limiti e campi azione.
- [x] Fix DST con risultato sempre strettamente `> after` e regression test.
- [x] Engine cancellation-safe, isolamento per azione e timestamp singolo.
- [x] Event/execution ID e claim/cooldown atomico nel contratto store.
- [x] Revalidazione policy/capability al fire-time.

Gate H1: tutti i bypass e i casi DST dell'audit devono essere test verdi; nessun
consumer Android può aggirare la policy.

Verifica 2026-07-13: 87 test engine + 15 test Room verdi; migrazione Room v1→v2
validata sul OnePlus API 36 via instrumentation ADB. Gli event ID sono hashati nel DB.

### H2 — persistenza, approvazione e audit

- [x] Fix TOCTOU `save()` in transazione.
- [x] `AutomationStore`: `all/observe/delete/claim` e state machine con arm fingerprintato.
- [x] Quarantena persistente del JSON corrotto; `armed()` restituisce solo ARMED.
- [x] Draft repository con revision/fingerprint e arm transazionale.
- [x] Execution journal + action result correlati; retention/redazione.
- [x] Regole backup Android per escludere segreti, contatti, audit e chat.

Verifica H2 2026-07-13: 92 test engine + 32 test Room verdi. Migrazioni Room
v1/v2/v3→v4 validate sul OnePlus API 36 (`OK (3 tests)`). Una modifica post-review
o un flip diretto ad ARMED senza fingerprint porta a `NEEDS_REVIEW`; il runtime store
non espone più alcun `save()` pubblico. Gli edit persistono il fingerprint della versione
base, quindi una regola eliminata durante la review non può essere ricreata dal draft.
App lint/build verdi (resta solo `OldTargetApi`, H4).

### H3 — brain/bridge

- [x] Parser `/compile` strict: `schema_version` obbligatoria e compatibile.
- [x] `reply` type-safe, body limitato, content type verificato, cancellation testata.
- [x] Inviare solo lo `DeviceState` richiesto e redatto; eliminare il commento falso.
- [x] Bridge Hermes `/compile` versionato, autenticato, idempotente e testato live.
- [x] HTTPS o trasporto autenticato equivalente; niente opt-in cleartext globale.
- [x] Probe Android 16 preliminare: Hermes via VPN Tailscale resta raggiungibile e il client
  gestisce il denial LAN senza crash.
- [ ] Test Local Network Protection autorevole: enable compat flag → reboot → denial LAN;
  disable flag → reboot → baseline LAN positiva, con Hermes verde dopo il ripristino.

Verifica H3: 17 test JVM `brain-android`, 12 test HTTP server e lint senza errori; servizio
`argus-bridge` su loopback dietro Tailscale Serve HTTPS; auth negativa `401`; compile live + replay
idempotente; health e compile instrumented sul OnePlus API 36 (`OK (2 tests)`). Il probe
pre-reboot ha osservato health HTTPS via Tailscale e denial LAN diretta, ma non viene contato come
prova LNP conclusiva: la procedura Android richiede un reboot dopo il cambio compat. L'override è
ora disabilitato; denial autorevole e baseline positiva restano nel gate reboot H7.

### H4 — Android scheduling e capability

- [x] Portare compile/target SDK a 36; smoke predictive back/edge-to-edge su device resta in H7.
- [x] `SCHEDULE_EXACT_ALARM` solo con `TimePrecision.EXACT`, fallback inexact e race di revoca gestita.
- [x] Reconciliation implementata per boot, time/timezone, package replace e grant/revoca;
  runtime installato in `Application`, capability reconcile proattivo e test scheduler su device.
- [x] Recovery reale dopo morte processo verificata con alarm OS-managed e PID target diverso.
- [ ] Recovery reale attraverso reboot da chiudere nel gate H7.
- [x] Scheduler event-driven, nessun FGS persistente.
- [x] Capability requirements persistite e fingerprintate; resta il probe/reconcile proattivo
  revoca -> pausa/`NEEDS_REVIEW`.

### H5 — Shizuku, device-tools ed executor

- [x] `core-shizuku` con UserService/gateway verificato su API 36.
- [x] Coda single-writer con priority + execution ID, timeout e output cap.
- [x] `device-tools` tipizzati e test non distruttivi sul device.
- [x] Executor per-action; `RunShell` bloccato finché manca conferma live.

Verifica H5 2026-07-13: 100 test engine, 10 core-shizuku, 11 device-tools e 51
automation-android verdi, lint senza errori. Sul OnePlus/API 36 il test UserService ha
confermato UID 2000 (`OK (1 test)`); il round-trip device-tools ha validato screenshot,
UI dump multi-window, stato/foreground e DND con ripristino (`OK (1 test)`). `Tap`,
`InputText` e `WhatsAppReply` restano fail-closed fuori fase; ogni azione mutante P0
porta execution ID e priorità fino alla coda privilegiata. Dopo il reboot non basta il
processo manager Shizuku: il daemon va verificato/riavviato, caso da esporre nel wiring H6.

### H6 — wiring reale e UI

- [x] ViewModel/Hilt reali con Flow Room bounded, bootstrap runtime e recovery applicativa.
- [x] Correggere log ID/generative, privacy note, CloudTag, loading e conferme.
- [x] Bloccare in UI le capability/fasi non implementate.
- [x] String resources, accessibilità e test Compose/navigation essenziali.

Verifica H6 2026-07-14: commit `10bf5de` pushato; 243 test JVM/Robolectric verdi,
`test lintDebug assembleDebug assembleDebugAndroidTest` verde; sul OnePlus/API 36 navigazione
`OK (4)`, secure bridge store `OK (3)`, Shizuku `OK (1)`, device-tools `OK (1)` e scheduler/probe
`OK (4)`. Il package finale è autorizzato in Shizuku; dopo la risincronizzazione del daemon il
gateway è `AUTHORIZED` e gli E2E H7 privilegiati sono passati.

### H7 — E2E e merge

- [x] Chat -> compile -> review -> fingerprint -> arm -> alarm -> DND -> journal; duplicate
  delivery ignorata e one-shot disabilitata.
- [x] Negative non-reboot: denial/revoca, duplicate event, crash/retry, Shizuku down,
  schema futuro, bridge lento/malformato, process death e DST.
- [ ] Reboot reale: boot recovery, stato degradato Shizuku e ciclo LNP compat on/off.
- [x] Installazione pulita dell'APK `0.1.0` e smoke dei sei schermi, inclusa card Room reale →
  dettaglio (`OK (4)`).
- [x] Full test/lint/build conclusivo: 750 task forzati senza cache, 243/243 test e zero errori
  lint; APK main/androidTest prodotti e hashati.
- [ ] Ripetere il compile reale post-clean-install quando il provider Hermes torna disponibile;
  l'attuale quota è esposta correttamente come `503` e non produce draft o mutazioni.
- [ ] Aggiornare spec/ledger, push backup, review finale e merge no-ff su master.

Verifica H7 parziale 2026-07-14: E2E produzione Hermes/DND `OK (1)`, process-death bifase
`schedule`/`am kill`/`verify` verde con PID diverso e un solo `FIRED`, outage reale del daemon
Shizuku verde con `BLOCKED_POLICY/capability_unavailable`, zero mutazioni e nessun replay tardivo.
Cleanup verificato: DND `off`, exact-alarm `default`, Shizuku nuovamente attivo.
Lo smoke pulito ha inoltre trovato e corretto un drift binario Compose 1.7.6/1.8.2 che causava
`NoSuchMethodError` sulla prima `FlowRow`; BOM allineato e regressione device verde.
Il bridge è stato inoltre hardenizzato contro quota/provider output senza protocollo: `502/503`
invece di falso `200`, 12/12 test server e health Android post-deploy verde. La quota Codex corrente
è un vincolo operativo esterno; nessun fallback provider è stato scelto automaticamente.
La reinstallazione ha inoltre cambiato l'UID applicativo: il vecchio grant Shizuku non è stato
riusato e il gateway è tornato `AUTHORIZED` solo dopo una nuova autorizzazione/riallineamento.

## Decisioni Android 16

- Baseline di sviluppo: compile/target 36; minSdk resta 30.
- Le connessioni Tailscale devono essere provate anche con compat flag
  `RESTRICT_LOCAL_NETWORK`; il range CGNAT `100.64.0.0/10` è rilevante per la
  protezione LAN, anche se il traffico passa da VPN.
- I job avviati da FGS rispettano comunque le quote su Android 16; un service
  “sempre vivo” non è una scorciatoia valida.
- Network Security Config deve negare cleartext per default.

## Definition of Done P0-B

P0-B è completo solo quando l'E2E deterministico DND passa sul device e i negative
gate H1-H3 sono verdi. Un APK che compila o una demo UI non costituiscono completion.

Nota di scope corretta: il geofence è compilabile e validabile in P0-B, ma resta non armabile
in produzione finché il registrar Play Services non viene implementato in P2. Armare una regola
che il runtime non può registrare sarebbe un comportamento fail-open.
