# Argus — Codex → Claude: prossimi step prescrittivi e correzioni da non ripetere

- Data: 2026-07-15, Europe/Rome
- Branch: `feat/argus-p2-background`
- Handoff generale da leggere prima: `2026-07-15-argus-codex-to-claude-p2-handoff.md`
- Ultimo commit di codice al momento della stesura: `e5f77a7`
- Obiettivo: chiudere P2 con evidenza reale, fare merge sicuro e preparare P3 senza anticiparne il codice

## 1. Aggiornamento che sostituisce il vecchio residuo SMS/OTP

Lorenzo ha chiarito esplicitamente che la frase “provato e ha funzionato perfettamente” si riferiva
a **entrambe** queste prove:

1. SMS telephony reale ricevuto dal modem → automazione SMS scattata;
2. stessa pipeline → `textMatch`/regex OTP → clipboard → incolla reale riuscito.

Questi gate sono quindi **PASS manuale Lorenzo 2026-07-15**. Non chiedergli di ripeterli e non
continuare a elencarli come aperti. La formulazione corretta è:

> Pipeline live positiva confermata manualmente da Lorenzo; privacy/redazione e casi negativi
> restano coperti separatamente dai test automatizzati e dalla review del codice.

Non trasformare però la sua conferma in qualcosa che non è: Codex non aveva log indipendenti nel
buffer al momento del chiarimento. È evidenza utente positiva, non trace forense.

## 2. Stato tecnico da cui partire

### Git

- branch: `feat/argus-p2-background`;
- `e5f77a7`: RE2/J per regex OTP a tempo lineare;
- `28d1e86`: handoff generale quota-stop;
- il commit che contiene questo supplemento sarà successivo;
- origin Unraid deve essere allineato prima di iniziare una slice.

### Full gate finale già eseguito

Su HEAD con `e5f77a7` incluso:

```powershell
.\gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest `
  --no-build-cache --rerun-tasks --no-parallel --console=plain
```

Esito: **758/758 task, `BUILD SUCCESSFUL`, 2m09s**, zero errori test/lint/build.

Non rieseguirlo immediatamente per rituale. Rieseguilo soltanto dopo nuovi cambiamenti o come gate
pre-merge finale.

### Hermes

- `argus-bridge` active;
- suite locale e remota 20/20;
- backup RE2: `bridge.py.pre-re2-codex-20260715-125622`;
- hash repo/remoto identici:
  - `bridge.py`: `8e20f0cd739946d643b341a5060b065a8c377ce2b79a68df185be83cb623ab75`;
  - `test_bridge.py`: `1aaa7f8c93e1c3dee22f09b84ed9230cb9b96b599a864ea5e8f255485fa9a6f1`.

### OnePlus

- seriale ADB: `100.74.117.9:5555`;
- APK `0.1.0`, target 36, aggiornato alle 12:56:14;
- Shizuku risultava attivo/autorizzato all'ultimo gate;
- nessun reboot eseguito;
- mai eseguito `ArgusNavigationInstrumentedTest` durante P2 Codex;
- cleanup precedente: nessun FGS Connectivity residuo, geofence OS ids `[]`.

## 3. Richiamo tecnico a Claude: errori che non devono ricomparire

Questa sezione è un criterio di accettazione, non una nota di stile. Gli errori sotto sono già
costati diagnosi false, fix aggiuntivi e prove ripetute. Se uno schema ricompare, la slice va
considerata **non pronta**, anche se unit test e review Opus sono verdi.

### 3.1 Non diagnosticare prima dei log

**Errore avvenuto:** il rifiuto di `run_shell` è stato attribuito a una “race Shizuku”. Era falso:
`run_shell` non entrava mai in `availableTools` e l'executor rispondeva
`live_confirmation_required` per design. Il binder non era il problema.

**Perché è grave:** una diagnosi infrastrutturale falsa porta a retry, timing workaround e codice
inutile, mentre la matrice capability/validator/executor resta incoerente.

**Regola obbligatoria:** prima di proporre una causa, tracciare l'intero percorso:

```text
manifest capability
  → available tools/triggers
  → bridge validation
  → DraftValidator
  → FirePolicy
  → executor
  → risultato/audit live
```

Scrivere nel report quali passaggi sono osservati e quali inferiti. Se manca il trace, chiamarla
“ipotesi”, non “causa”.

### 3.2 Un test sintetico non è il bordo reale

**Errore avvenuto:** la pipeline SMS aveva JVM/Robolectric e poi un PDU sintetico production-path,
ma il canale modem/receiver non era mai passato end-to-end. Il primo test utente non scattò e solo
il campo rivelò la differenza RCS/SMS e il bug Hilt.

**Regola obbligatoria:** usare tre etichette distinte:

- `unit/integration` — nessun framework/device reale;
- `production-path synthetic` — processo e DI reali, ingresso sintetico;
- `physical/radio E2E` — evento prodotto realmente da modem, BT, cavo o location framework.

Mai scrivere “E2E reale” per la seconda categoria.

### 3.3 Hilt/manifest receiver va provato nel processo reale

**Errore avvenuto:** il receiver telefonia compilava ed era stato revisionato, ma la dipendenza Hilt
non veniva inizializzata nel percorso manifest. Risultato: SMS ricevuto, zero automazioni.

**Regola obbligatoria:** ogni nuovo `BroadcastReceiver`, `Service` o entry point framework deve avere:

1. test di estrazione/normalizzazione puro;
2. test production-process con grafo Hilt reale;
3. almeno un gate device che attraversi il boundary framework;
4. log minimale privo di PII che dimostri ingresso e completion.

“Compila” non dimostra lifecycle/injection.

### 3.4 Verificare le regole Android dalla documentazione, non dalla memoria

**Errore avvenuto:** il piano trattava BT ACL e POWER come entrambi manifest-exempt. Solo BT ACL lo
è; POWER ha richiesto receiver dinamico nella sentinella FGS.

**Regola obbligatoria:** per broadcast, permessi, FGS, PendingIntent e background execution:

- link alla pagina Android ufficiale nel piano;
- API/target/minSdk espliciti;
- prova sul OnePlus API 36;
- nessun “dovrebbe funzionare” usato come gate.

### 3.5 Non pubblicare capability senza l'intera catena pronta

**Errori avvenuti:** `phone_state.call` poteva risultare disponibile senza `READ_CALL_LOG`; in altre
slice tool/capability e executor non erano sempre concordi.

**Regola obbligatoria:** una capability è pubblicabile solo se sono allineati:

- probe reale;
- requirements del modello;
- manifest/permission flow;
- validator Android;
- validator Hermes;
- FirePolicy;
- executor;
- UI arm gate e recovery.

Testare anche la revoca dopo arm e l'assenza del grant, non solo il positivo.

### 3.6 Persistire lo snapshot non basta: serve protocollo pending/ack

**Errore avvenuto:** chiamate e Connectivity committavano lo snapshot prima del dispatch, ma non
l'envelope pending. Una morte processo in quella finestra perdeva definitivamente il bordo.

**Regola obbligatoria per ogni nuovo ingresso:** progettare prima le transizioni:

```text
observe
  → persist snapshot + pending atomico
  → dispatch con event-id stabile
  → ack/clear dopo ritorno
  → recovery a process start
```

Test minimi: crash prima dispatch, errore dispatch, errore prima source non affama le altre,
corruzione storage, cancellation rilanciata, event-id identico dopo recreation.

### 3.7 Cleanup deve essere indipendente e verificato

**Errore avvenuto:** delete detail puliva AlarmManager ma poteva lasciare registrazioni
Connectivity/Geofence. Inoltre un errore del primo cleanup rischiava di saltare i successivi.

**Regola obbligatoria:** scheduler, Connectivity, Geofence e futuri registrar si puliscono tutti,
indipendentemente. Dopo ogni test device riportare esplicitamente:

- regole diagnostiche eliminate;
- FGS assente;
- geofence ids vuoti;
- permessi/appops temporanei ripristinati;
- DND/Wi-Fi/Bluetooth nello stato iniziale.

### 3.8 Non confondere canali simili nell'UX o nei report

**Errore avvenuto:** “messaggio” è stato usato come se RCS, MMS e SMS telephony avessero lo stesso
receiver. Non è vero.

**Regola obbligatoria:** nominare sempre il canale esatto: `SMS telephony`, `RCS`, `MMS`,
`NotificationListener`, `PHONE_STATE`. Se un canale è fuori scope, dichiararlo in UI e DoD.

### 3.9 Il modello va confrontato con gli esempi utente, non solo con lo schema

**Errore avvenuto:** `textMatch` mancava e il gap è emerso dal comportamento di Hermes, non dalla
review del modello. Due giri sono serviti anche per rendere realmente cliccabili tutte le righe
Settings, non solo per mostrarle come CTA.

**Regola obbligatoria:** prima del codice costruire una tabella esempio → campi modello → matcher →
UI → test. Fare inoltre un tap-test reale per ogni CTA critica; l'aspetto cliccabile non è prova di
hit target o callback wiring.

### 3.10 Input approvato non significa input innocuo

**Errore avvenuto:** regex OTP approvata ma eseguita dal motore backtracking Java su testo scelto
dal mittente: superficie ReDoS. È stato necessario `e5f77a7` con RE2/J.

**Regola obbligatoria:** per regex, template, URL, shell e payload remoti valutare separatamente:

- chi sceglie la configurazione;
- chi controlla l'input a runtime;
- complessità/limiti;
- compatibilità delle regole già fingerprintate.

### 3.11 Non sovrastimare nei handoff

**Errore ricorrente:** “completo”, “E2E” o una causa certa sono apparsi prima della prova che
giustificava quei termini.

Ogni handoff futuro deve avere quattro blocchi separati:

1. verificato oggi con comando/esito;
2. verificato in precedenza e non rieseguito;
3. ragionato/code-reviewed;
4. non provato.

Se una frase non entra chiaramente in uno dei quattro, riscriverla.

## 4. Piano eseguibile per chiudere P2

### Fase A — Preflight senza modifiche

1. Leggere entrambi gli handoff Codex e il piano P2.
2. `git fetch origin` e verificare HEAD/origin.
3. `git status --short` deve essere vuoto.
4. Non rifare il full gate finché non viene cambiato codice.
5. Verificare device con un solo comando ADB alla volta.
6. Chiedere a Lorenzo solo disponibilità per la specifica prova fisica successiva.

### Fase B — Chiamate reali

Obiettivo: provare `INCOMING_CALL` e `CALL_ENDED`, incluso almeno un filtro numero.

Preparazione:

- verificare `READ_PHONE_STATE` e `READ_CALL_LOG`;
- creare regole diagnostiche con azione innocua (`ShowNotification`), non shell;
- una regola generica e, se il numero è disponibile, una filtrata;
- pulire `logcat` prima della chiamata.

Pass:

- broadcast RINGING osservato senza numero in log;
- eventuale secondo broadcast numerato usa lo stesso event-id;
- regola generica scatta una volta;
- regola filtrata scatta quando il numero è disponibile;
- IDLE dopo RINGING/OFFHOOK produce CALL_ENDED una volta;
- nessun numero in log/audit in chiaro;
- cleanup completo.

Fail da investigare con log prima di cambiare codice:

- nessun ingresso al receiver;
- ingresso al receiver sì, matcher no;
- doppio FIRED;
- CALL_ENDED senza chiamata precedente;
- numero perso fra RINGING e IDLE.

### Fase C — Cavo POWER reale

Obiettivo: collegamento e scollegamento fisico producono entrambi una sola transizione.

Preparazione:

- regole diagnostiche CONNECTED/DISCONNECTED con `ShowNotification`;
- verificare che la sentinella FGS sia attiva solo perché le regole lo richiedono;
- non usare il Wi-Fi come oggetto del test.

Pass:

- plug → un evento CONNECTED;
- unplug → un evento DISCONNECTED;
- callback ripetuti non duplicano FIRED;
- disable/delete → sentinella si ferma;
- nessun FGS residuo.

### Fase D — Bluetooth ACL reale

Obiettivo: device Bluetooth reale produce CONNECTED/DISCONNECTED tramite receiver ACL.

Preparazione:

- grant `BLUETOOTH_CONNECT` solo quando richiesto;
- una regola generica, poi opzionalmente una con `match` nome;
- non registrare address/nome nei log.

Pass:

- connect/disconnect una volta ciascuno;
- nome preservato sul bordo DISCONNECTED per il matcher;
- address assente da event-id/log;
- process recreation non genera un falso fire iniziale.

### Fase E — Geofence reale, prima innocuo poi Esempio 1

Step 1, prova non distruttiva:

- raggio consigliato almeno 100–150 m;
- azione `ShowNotification`;
- posizione precisa + background grant;
- attraversamento fisico reale, accettando latenza di minuti;
- verificare ENTER/EXIT e cleanup.

Step 2, Esempio 1 della spec:

- `EXIT` → Wi-Fi off + Bluetooth on;
- farlo soltanto con Lorenzo consapevole che il Wi-Fi sostiene ADB/Tailscale;
- non spegnere mai il Wi-Fi da remoto per “forzare” il test;
- raccogliere il risultato dall'app/audit dopo che il telefono torna raggiungibile;
- verificare ordine/esito delle due azioni e stato `PARTIAL` se una fallisce.

Pass P2: almeno un vero callback di attraversamento e un'esecuzione multi-azione dell'Esempio 1,
con aspettative di latenza documentate. Non promettere affidabilità istantanea.

### Fase F — Negativo OTP opzionale ma utile

Non ripetere il positivo. Se Lorenzo è disponibile, inviare un SMS telephony che soddisfa il
`textMatch` ma non contiene un codice valido:

- risultato `otp_not_found`;
- clipboard precedente intatta;
- nessun testo SMS in log/DB/audit.

È un bordo osservazionale; i test Robolectric lo coprono già. Non bloccare inutilmente il merge se
Lorenzo non vuole inviare un altro SMS, ma dichiarare la differenza fra test automatico e live.

## 5. Audit pre-merge obbligatorio

Prima del merge, produrre un report breve con esito per ogni punto:

1. `git diff master..HEAD` — nessun file inatteso o segreto.
2. Manifest — receiver/exported/permissions/FGS type coerenti.
3. Capability matrix — probe, validator, bridge, FirePolicy, executor concordi.
4. Privacy — SMS body assente da preferences/Room/audit/log; event-id e source key digest-only.
5. Persistenza — pending/ack e corruzione fail-closed.
6. Lifecycle — app-start, boot/update e revoca grant.
7. Cleanup — tutti i registrar indipendenti.
8. UI — righe salute e CTA realmente cliccabili.
9. Hermes — suite, active, hash identici, backup.
10. Device — DND off, nessun FGS/geofence diagnostico, Shizuku in stato noto.
11. Regole di Lorenzo — WhatsApp generative e “esegui” non mutate o invalidate senza motivo.
12. Documenti — verificato/ragionato/non provato coerenti fra piano, spec, ledger e handoff.

Se il codice cambia, rieseguire:

```powershell
.\gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest `
  --no-build-cache --rerun-tasks --no-parallel --console=plain
```

## 6. Merge su master: ordine sicuro

Non fare merge finché la DoD geofence reale non è accettata oppure Lorenzo decide esplicitamente
di differirla come gate osservazionale post-merge.

Quando autorizzato:

1. commit piccoli e descrittivi sul branch;
2. push branch e verificare origin;
3. full gate finale dopo l'ultimo codice;
4. `git checkout master`;
5. `git pull --ff-only origin master`;
6. verificare che non esistano modifiche locali di Lorenzo;
7. `git merge --no-ff feat/argus-p2-background` con messaggio P2 esplicito;
8. smoke host/APK;
9. push `master`;
10. tornare a uno stato device pulito e documentarlo.

Niente force-push, reset distruttivi o checkout che scarti file utente.

## 7. Dopo P2: non partire direttamente a scrivere P3

La roadmap design indica P3:

- loop interattivo `computer_use`;
- `OpenAICompatTransport` veloce/multimodale;
- eventuale `DirectLlmBrain` OAuth;
- `web.search`/`vision.analyze` app-side;
- conferme live per irreversibili;
- budget guard globale e stima costo;
- streaming chat.

Prima del codice serve un piano P3 nuovo e una decisione di Lorenzo su:

1. provider/modello, costo accettabile e fallback;
2. latenza massima per turno;
3. quali tool interattivi sono realmente necessari;
4. dati che possono lasciare il telefono;
5. conferma live e timeout;
6. budget globale orario/giornaliero;
7. strategia screenshot/vision e redazione;
8. comportamento offline/quota esaurita.

Il CliBridge gratuito one-shot non va riciclato per un loop multi-turn lento. Non configurare un
provider a pagamento senza autorizzazione esplicita.

Separatamente resta il vecchio gate reboot/LNP P0/P1. Si esegue solo con Lorenzo fisicamente
presente e un percorso certo per riattivare ADB TCP e Shizuku.

## 8. Divieti operativi

- Non eseguire `ArgusNavigationInstrumentedTest` sul device configurato.
- Non fare reboot senza Lorenzo presente.
- Non spegnere Wi-Fi da remoto durante una sessione ADB/Tailscale.
- Non passare bearer/segreti via CLI, log o instrumentation arguments.
- Non aggiungere `shell.run` alla lane generativa.
- Non introdurre denylist shell: contraddice la decisione esplicita di Lorenzo.
- Non aprire sensori/state keys dinamicamente: catalogo closed-world.
- Non persistere testo SMS per rendere il recovery “più facile”.
- Non implementare export/import durante la chiusura P2.
- Non dichiarare DWELL simulato: resta unsupported.
- Non aggiungere service persistenti per aggirare limiti Android.

## 9. Formato obbligatorio dei checkpoint Claude

Ogni checkpoint deve dire, in quest'ordine:

1. commit/HEAD e working tree;
2. cosa è stato verificato oggi, con comando e risultato;
3. cosa viene solo ereditato da prove precedenti;
4. cosa resta ragionato/non provato;
5. stato device e cleanup;
6. stato Hermes e hash se toccato;
7. unico prossimo step;
8. cosa deve fare Lorenzo, solo se davvero necessario.

Evitare frasi come “dovrebbe”, “probabilmente race”, “E2E” o “completo” senza associare subito
l'evidenza che le rende vere.

## 10. Definition of Done immediata per la consegna Claude

Claude può dichiarare P2 chiusa soltanto quando:

- SMS/OTP positivo resta registrato PASS manuale e non viene richiesto di nuovo;
- chiamata reale è passata o formalmente differita da Lorenzo;
- cavo e BT reali sono passati o formalmente differiti;
- geofence fisico/Esempio 1 è passato oppure Lorenzo accetta esplicitamente il rischio residuo;
- audit §5 non ha finding bloccanti;
- full gate dopo l'ultimo codice è verde;
- Hermes e device sono puliti;
- piano/spec/ledger distinguono prove reali, sintetiche e ragionate;
- branch è pushato e il merge su master è eseguito solo con worktree pulito.

Se uno di questi punti è aperto, scrivere “P2 implementata, gate X aperto”, non “P2 completa”.

## 11. Readiness audit P3: cosa esiste davvero e come pianificarla

Questa sezione evita un altro salto prematuro da una roadmap a una dichiarazione di feature.
Sul codice corrente P3 **non è iniziata**: esistono i contratti e i placeholder utili a non
precluderla, non un'implementazione parziale da completare alla cieca.

Stato osservato:

- `engine-core/src/main/kotlin/dev/argus/engine/brain/Brain.kt` espone concretamente solo
  `compile`/`act`; il `chat()` mostrato nella spec è un'estensione P3, non un contratto già
  presente nel codice;
- `brain-android/src/main/kotlin/dev/argus/brain/HermesBrain.kt` e `CliBridgeTransport.kt` coprono
  i soli flussi one-shot; lo streaming è esplicitamente rinviato a P3;
- Settings mostra `OpenAICompat` come “in arrivo”, senza transport operativo;
- `automation-android/src/main/kotlin/dev/argus/automation/AndroidCapabilityProbe.kt` pubblica
  `web.search` e `vision.analyze` come non disponibili;
- non risultano implementazioni operative di `OpenAICompatTransport`, `DirectLlmBrain`, OAuth,
  loop `computer_use` o conferme live;
- il budget globale è oggi copy/roadmap UI, non un guard runtime;
- il vecchio rischio spec che descriveva `/act` come lavoro futuro era documentazione obsoleta:
  `/act` v1 è già implementato e deployato in P1; la spec master è stata corretta in questo
  checkpoint.

### 11.1 Decisioni bloccanti prima del piano

Claude deve ottenere da Lorenzo, senza inventare default costosi o invasivi:

1. provider e modello per il loop veloce, incluso eventuale fallback;
2. tetto di spesa e di chiamate per ora/giorno;
3. budget massimo di latenza per turno e durata massima del loop;
4. quali dati possono uscire dal telefono: screenshot, UI tree, notifiche e testo clipboard;
5. politica di redazione, retention e logging per immagini/contenuti sensibili;
6. catalogo iniziale chiuso dei tool interattivi;
7. azioni che richiedono conferma live, timeout e comportamento su deny/assenza utente;
8. comportamento offline, su quota esaurita e su token OAuth scaduto.

Se una decisione manca, il piano deve segnalarla come blocco o offrire due alternative con
trade-off. Non trasformarla silenziosamente in una scelta architetturale di Claude.

### 11.2 Ordine consigliato delle slice P3

Preparare prima una spec e un piano dedicati, con task piccoli, gate e test nominati. Ordine
raccomandato:

1. **P3-0 — Decision record:** provider/modello, privacy, budget, conferme, timeout e fallback.
2. **P3-1 — Contratti:** eventi streaming, tool-call, cancellazione, timeout, idempotenza e
   mapping errori; solo JVM, nessuna rete reale. Decidere esplicitamente se introdurre una
   `InteractiveBrain` separata: non obbligare `HermesBrain` a fingere uno streaming che non
   supporta e non aggiungere un `Flow` vuoto/default che mascheri una capability assente.
3. **P3-2 — `OpenAICompatTransport`:** auth fuori da log/CLI, strict decoding, cancellation-safe,
   MockWebServer per stream troncato, tool call malformata, 401/429/5xx, retry limitato e
   idempotenza. Non riusare il parser permissivo `/chat`.
4. **P3-3 — Tool app-side closed-world:** iniziare dal minimo indispensabile; screenshot e
   `uiautomator` con limiti di dimensione, rilevazione `FLAG_SECURE`, redazione e capability
   completa. `web.search`/`vision.analyze` restano assenti dal manifest finché l'intera catena
   non è operativa.
5. **P3-4 — Agent loop:** max turn, max tempo, max costo, cancellation strutturata, esito
   terminale esplicito e recovery senza rieseguire un'azione incerta.
6. **P3-5 — Conferme live:** richiesta durevole con fingerprint dell'azione, scadenza, deny di
   default e binding tra approvazione e letterale realmente eseguito.
7. **P3-6 — UI:** streaming onesto, stop/cancel, stato provider/quota, costo stimato e consuntivo,
   errori recuperabili, impostazioni senza segreti in chiaro.
8. **P3-7 — Device E2E:** scenari innocui prima, poi una singola azione reversibile; prove di
   background/cancellazione/quota/deny e cleanup indipendente.

Ogni slice deve chiudere prima unit/integration test, poi eventuale device gate. Non mettere
transport, loop, UI e tool reali nello stesso commit: renderebbe impossibile attribuire errori e
fare rollback mirati.

### 11.3 Debito documentale corretto in questo checkpoint

La spec master è stata riallineata su quattro punti che Claude deve mantenere coerenti in futuro:

- l'intestazione non descrive più P0-B “in chiusura” mentre il branch corrente è P2;
- `/act` v1 non è più descritto come lavoro P1 futuro;
- recovery boot implementata/sintetica e reboot fisico non sono più confusi;
- export/import JSON non è più chiamato “quasi gratis” né infilato nella chiusura P2.

Prima di consegnare qualsiasi nuova fase, eseguire un grep dei documenti per promesse stale e
confrontarle con codice, test e prove device. La documentazione non deve raccontare uno stato
più avanzato né più arretrato del repository.

### 11.4 Spot-check statico Codex eseguito prima della consegna

Dopo il full gate, senza cambiare altro codice, Codex ha ricontrollato i bordi ad alto rischio.
Esito della sola ispezione statica:

- manifest: SMS è protetto da `BROADCAST_SMS`; PHONE_STATE e BT ACL restano receiver dei broadcast
  di sistema; POWER non è erroneamente registrato nel manifest;
- capability: `phone_state.call` viene pubblicata solo con `READ_PHONE_STATE` **e**
  `READ_CALL_LOG`; il launcher Settings richiede entrambi;
- privacy log: i nuovi tag riportano stato/contatori/booleani e classi d'errore, non corpo SMS,
  numero, SSID, nome o address Bluetooth;
- persistenza telefonia: il testo SMS resta solo nell'envelope volatile; il pending persistito è
  limitato alle chiamate e non accetta `SMS_RECEIVED`/`smsText`;
- cleanup delete/disable: scheduler, Connectivity e Geofence vengono tentati separatamente;
- capability P3: `web.search`, `vision.analyze`, `shell.run` interattivo e azioni UI restano
  dichiarati indisponibili; `run_shell` statico è una action distinta e fingerprintata.

Questo spot-check non sostituisce chiamata/cavo/BT/geofence fisici e non autorizza la dicitura
“P2 completa”. Serve a evitare che Claude ripeta subito audit già chiusi senza un nuovo diff.
