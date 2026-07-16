# Argus — handoff Codex → Claude dopo P3-2A

**Data:** 2026-07-16
**Branch:** `master`
**Commit implementazione corrente:** `055499c` (`feat(argus): define sensor trigger capabilities`)
**Stato:** P3-0, P3-1A/B/C/D e P3-2A chiuse; prossimo lavoro esclusivo P3-2B.
**Motivo handoff:** soglia weekly Codex concordata con Lorenzo, non blocco tecnico.

Il commit che contiene questo documento è il commit di consegna: ricavarlo con `git log -1` dopo
il pull. Non fidarti di una copia locale non allineata all'hub Unraid.

## 1. Leggi prima di modificare codice

Ordine obbligatorio:

1. `docs/superpowers/specs/2026-07-16-argus-p3-decision-record.md` — fonte normativa corrente;
2. `docs/superpowers/plans/2026-07-16-argus-p3-foundations-state-sensors.md` — piano eseguibile;
3. `docs/design/hermes-bridge-contract.md` — wire realmente deployato;
4. `docs/superpowers/specs/2026-07-12-hermes-android-agent-design.md` rev 9 — quadro master;
5. `.superpowers/sdd/progress.md` — ledger locale, utile ma gitignored;
6. questo handoff.

Gli handoff storici spiegano perché sono state prese certe decisioni, ma non prevalgono sul decision
record P3. In particolare sono superati i vecchi assoluti «shell mai da notifiche», «B8 barriera
invalicabile» e «sensori scartati per sempre». Erano scelte sensate nel contesto precedente e sono
state revocate esplicitamente da Lorenzo, non dagli implementer.

## 2. Stato da verificare appena subentri

```text
git status --short --branch
git log --oneline -12
```

Atteso:

- `master` allineato a `origin/master`;
- working tree pulito;
- sequenza recente almeno:
  - `055499c` P3-2A domain/capability sensori;
  - `1ca0f14` chiusura gate live `/act` v2;
  - `0e6006f` P3-1D stato generativo minimo;
  - `2e6926f` chiusura gate live `/compile` v2;
  - `462bc83`, `7db7a6f`, `e3fa202`, `450c7f5`;
  - `a9e2436`, `6b307c5` fix-pass indipendente sull'eredità P2.

Se trovi file modificati, non sovrascriverli e non assumere che siano tuoi. Usa staging esplicito;
mai `git add .`.

## 3. Evidenza realmente acquisita, distinta dal ragionamento

| Slice | Stato | Evidenza più forte | Commit |
|---|---|---|---|
| P3-0 | PASS | decision record e drift attivo chiusi | `450c7f5` |
| P3-1A | PASS | test host + OnePlus: lettura minima batteria/location | `e3fa202` |
| P3-1B | PASS | reader dumpsys reale confrontato con API Android e usato dall'Engine | `7db7a6f` |
| P3-1C | PASS | compile v2 live → probe/review/arm/fire notification sul OnePlus | `462bc83`, `2e6926f` |
| P3-1D | PASS limitato | health + compile v2 + `/act` v2 reali; valore act fixture | `0e6006f`, `1ca0f14` |
| P3-2A | PASS host | JVM, Robolectric, bridge/deploy; per design nessun listener | `055499c` |

Ultimo gate host forzato:

```text
.\gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest --no-build-cache --rerun-tasks --no-parallel --no-daemon
```

Risultato: `BUILD SUCCESSFUL`, **759/759 task eseguiti** in 2m18s. La suite Python del bridge è
**32/32** sia locale sia su Hermes.

Il primo lancio di quel gate era stato terminato dopo 5 secondi da un timeout sbagliato del wrapper.
Sono stati raccolti `gradlew --status`, fermato pulitamente il daemon e rifatto tutto da zero. Non è
una failure di codice e non va trasformato in una storia su Gradle.

### 3.1 Cosa NON è provato

- P3-1D non ha ripetuto un E2E fisico reader → `/act` → reply: `/act` v2 ha ricevuto il valore
  fixture `4200`. Il reader fisico era già provato in P3-1B/C, il wire `/act` è provato live, ma il
  loro join non va dichiarato PASS finché non viene eseguito davvero.
- P3-2A non ha e non deve avere callback sensore: non registra listener.
- Significant motion in background, secondo evento dopo rearm, process death, package replace,
  boot e cleanup disable/delete sono tutti **NOT PROVEN**.
- Consumo batteria/tempo FGS sensori è **NOT MEASURED**.
- `personal-full`, `play-core` e `play-extended` sono decisioni architetturali, non build flavor già
  consegnati né approvazioni Play Console.
- Nessun reboot è stato fatto in questa presa in carico.

## 4. Decisioni nuove da non annullare per inerzia

### 4.1 Safety e injection

L'invariante è «un dato non fidato non crea o cambia autorità», non «un dato esterno non può
influenzare nulla».

- Un comando `RunShell` resta interamente letterale e fingerprintato.
- Può scattare da Time, Geofence, Connectivity, Sensor locale oppure da una chat WhatsApp 1:1 con
  `conversationId` whitelistato. Il messaggio sceglie il momento, non il comando.
- PhoneState/SMS/caller ID restano esclusi dalla shell: sono spoofabili.
- Valori TAINTED possono andare a UI/clipboard/TTS/notifica locale, contesto LLM e reply sulla stessa
  capability di conversazione; non possono diventare target, package, path, shell, tap privilegiato
  o mutazione autonoma di automazioni.
- Integrità `CLEAN|TAINTED` e riservatezza `PUBLIC|PRIVATE|SECRET` sono assi separati.

Non reintrodurre un divieto globale perché è più semplice da spiegare. Implementa la matrice sink.

### 4.2 Distribuzione pubblica

Tailscale/Hermes è un transport personale, non una dipendenza ontologica del prodotto. Il piano è:

- `personal-full`: capability opt-in, Shizuku e permessi sensibili;
- `play-core`: solo capability compatibili con il canale e con dichiarazioni realmente disponibili;
- `play-extended`: soltanto dopo esito positivo delle dichiarazioni Play.

Non amputare il dominio per adeguarlo preventivamente allo store e non scrivere «Play approved»
senza una review Play reale.

## 5. P3-1D: terreno che erediti già chiuso

Non toccarlo durante P3-2B salvo regressione dimostrata.

- `Action.InvokeLlm` v1 non ha nuovi default e i golden fingerprint v1 sono invariati.
- `Action.InvokeLlmV2` ha tutti i campi required: goal, query classificate, tool, binding reply e
  timeout.
- La lane generativa accetta il marker chiuso `GenerativeAction`, non un `Action` arbitrario.
- Il planner v2 legge soltanto le query elencate; non costruisce lo snapshot state legacy.
- I valori vengono congelati e fingerprint/policy sono verificati prima e dopo il Brain.
- Reader locale approvato = `CLEAN`. Floor riservatezza: builtin `PRIVATE`; setting, property,
  sysfs e dumpsys `SECRET`. Il compilatore può alzare, mai abbassare.
- Review e warning nominano ogni reader e dichiarano invio di notifica + stato verso
  Hermes/provider cloud.
- `/act` v1 e v2 hanno schema/idempotenza separati. Server e Android rifiutano id falso, query
  duplicate, policy/tipo incoerenti, TAINTED nel profilo attuale, underclassification e malformed
  wire senza produrre 500.
- Fixture canonica condivisa: `ops/hermes/state_query_contract_v2.json`.

Hermes è già deployato con `compile=(1,2)` e `act=(1,2)`. Non fare flip o rimuovere v1 durante il
lavoro sensori.

## 6. P3-2A: cosa è stato implementato davvero

### 6.1 Dominio

File chiave:

- `engine-core/.../model/Trigger.kt`;
- `engine-core/.../runtime/TriggerEvent.kt`;
- `engine-core/.../runtime/SensorEventIds.kt`;
- `engine-core/.../model/CapabilityRequirements.kt`;
- `engine-core/.../safety/DraftValidator.kt`;
- `engine-core/.../safety/StaticShellSafety.kt`.

`Trigger.Sensor` accetta solo:

- `SIGNIFICANT_MOTION`;
- `STATIONARY_DETECT`;
- `MOTION_DETECT`;
- `STEP_DETECTOR`;
- `STEP_COUNTER`.

Non esistono enum raw accelerometer/gyroscope. `samplingPeriodUs` e `maxReportLatencyUs` sono
riservati ma P3-2A li rifiuta se non null, così un draft non può fingere supporto high-rate.

Policy:

- motion/stationary/significant: `minimumEventCount == 1`;
- step: `1..100000`;
- cooldown sensore: `60000..604800000` ms;
- `TriggerEvent.SensorChanged` contiene solo automation id, fingerprint e kind;
- `SensorEventIds.create` accetta soltanto id, fingerprint, kind e sequenza e restituisce
  `sensor:<kind>:<sha256>`. Nessun parametro raw è disponibile per errore o abuso futuro.

Il matcher richiede lo stesso kind. La shell statica può scattare da un sensore locale, ma solo se
trigger/evento coincidono.

### 6.2 Probe capability Android

File chiave: `automation-android/.../AndroidSensorCapabilities.kt` e
`AndroidCapabilityProbe.kt`.

Il source legge dal `SensorManager`:

- disponibilità del default sensor;
- reporting mode;
- wake-up flag;
- FIFO max event count;
- min/max delay;
- grant `ACTIVITY_RECOGNITION` per step detector/counter.

Compatibilità attuale:

- significant motion: `ONE_SHOT` **e** wake-up;
- stationary/motion detect: `ONE_SHOT`;
- step detector: `SPECIAL_TRIGGER` + grant;
- step counter: `ON_CHANGE` + grant.

`SensorCapabilityPolicy.armableKinds` fa l'intersezione fra:

1. hardware compatibile;
2. grant concesso;
3. backend realmente implementato.

Il terzo insieme è deliberatamente `AndroidCapabilityProbe.IMPLEMENTED_SENSOR_KINDS = emptySet()`.
Questa non è una TODO dimenticata: impedisce che Hermes compili oggi una regola morta. P3-2B deve
cambiarlo a `{SIGNIFICANT_MOTION}` **nello stesso commit/slice che collega il backend reale**.

Il policy probe usa il default sensor. Se su un futuro device compaiono più implementazioni dello
stesso type, non «risolvere» scegliendone una a caso: il registrar e il probe devono selezionare lo
stesso oggetto con una policy unica e testata.

### 6.3 Bridge

`ops/hermes/bridge.py` conosce già i cinque wire `sensor.<kind>` e valida:

- enum chiuso;
- conteggio;
- cooldown;
- sampling/latency null;
- disponibilità del kind esatto nel manifest;
- shell statica ammessa da trigger sensor.

È un rollout server-first: Android non pubblica ancora quei wire. Non serve un nuovo schema per
l'espansione enum già bounded, ma un vecchio server avrebbe rifiutato il manifest futuro; per questo
il deploy è stato anticipato.

Deploy Hermes attuale:

- suite staging/finale 32/32;
- servizio `argus-bridge` active;
- startup `compile=(1, 2) act=(1, 2)`;
- backup `bridge.py.pre-p3a-055499c` e `test_bridge.py.pre-p3a-055499c`;
- hash raw repo/host identici per `bridge.py`, `test_bridge.py` e fixture.

## 7. Prossimo task: P3-2B significant-motion verticale

Non iniziare P3-2C, release profiles o computer-use in parallelo. Chiudi prima questa verticale.

### 7.1 Preflight

1. Pull e verifica working tree pulito.
2. Rileggi §6 del decision record e P3-2B nel piano.
3. Esegui almeno test dominio/sensor policy/bridge prima del primo edit.
4. Ispeziona, senza copiare alla cieca, i pattern già robusti di:
   - `ConnectivitySentinelCoordinator`/`Service`/store;
   - `GeofenceCoordinator`/ingress/store;
   - `TimeAlarmCoordinator` per fingerprint e callback stale;
   - `ArgusRuntimeController` e recovery `APP_START`/boot/package replace;
   - `ArmedAutomationRegistrar` e `AutomationEnablementCoordinator`.

### 7.2 Architettura richiesta

Implementa una sola infrastruttura FGS con demand reasons condivisi. Non creare un secondo servizio
sensori accanto a `ConnectivitySentinelService`.

Proposta di confine, adattabile se i test mostrano un nome migliore:

```text
ForegroundDemandReason
  ├─ Connectivity(source/medium)
  └─ Sensor(automationId, fingerprint, kind)

SensorTriggerRuntime
  ├─ reconcile()
  ├─ unregister(automationId/fingerprint)
  └─ recover(reason)
```

Requisiti:

- coordinator single-writer;
- demand set persistito bounded e corruption-safe;
- stop FGS solo quando il set è vuoto;
- preservare il comportamento `START_NOT_STICKY` dopo bootstrap failure, evitando restart loop;
- nessun wake lock permanente;
- notifica FGS esplicita che dica perché Argus resta attivo e offra uno stop coerente con la policy;
- nessun payload sensore, nome regola o comando nella notifica/audit.

### 7.3 Backend significant motion

Usa `SensorManager.requestTriggerSensor`, non `registerListener`: significant motion è one-shot.

Il backend deve:

- selezionare esattamente lo stesso default sensor descritto dal probe;
- rifiutare mode diverso da `ONE_SHOT` o `wakeUp=false`;
- restituire un esito tipizzato per registered/already registered/unavailable/failure;
- supportare `cancelTriggerSensor` per disable/delete/reconcile;
- non loggare `TriggerEvent.values`;
- non usare Shizuku: questa capability deve restare disponibile durante outage Shizuku.

Non impostare `IMPLEMENTED_SENSOR_KINDS` finché questi percorsi non esistono e i test registrar non
dimostrano che una regola armata viene registrata davvero.

Non confondere lo stato logico per-regola con la sottoscrizione fisica Android. Per P3-2B esiste un
solo default sensor significant-motion: usa di default **una registrazione fisica condivisa per
kind** e fan-out verso lo snapshot delle regole eleggibili. Creare un `TriggerEventListener` per
ogni regola spreca code/listener di piattaforma, incontra un limite finito e rende fragile il
cleanup quando più regole usano lo stesso sensore. Se l'evidenza sul OnePlus imponesse davvero una
topologia diversa, fermati, raccogli log e aggiungi un test multi-regola prima di cambiarla.

Il contratto richiesto è quindi:

- prima regola eleggibile del kind → registra il sensore fisico;
- regole successive dello stesso kind → aggiornano soltanto il demand set logico;
- disable/delete di una regola → non cancella il sensore se ne resta almeno un'altra;
- callback fisica → un envelope metadata-only distinto per ogni regola ancora eleggibile;
- ultima regola rimossa → `cancelTriggerSensor` e rimozione del demand FGS.

### 7.4 Stato e identità

Per ogni registrazione conserva almeno:

- automation id;
- approval fingerprint;
- kind;
- stato desiderato per-regola;
- detection pending con event id e sequenza, se la callback è già arrivata;
- versione schema/store e bounds.

La registrazione fisica osservata è stato **process-local**, non verità persistibile. Dopo process
recreation qualunque vecchio `registered=true` deve essere ignorato o invalidato da una generation
id: si riparte dal demand desiderato e si riconcilia con una nuova chiamata Android. Un booleano
globale persistito trasformerebbe il crash proprio nel falso positivo «registrato ma morto».

Lo stato logico resta per-regola. Una callback vecchia deve fallire chiusa se la regola è stata
modificata, disabilitata, eliminata o ha fingerprint diverso.

L'event id deve essere stabile nelle redelivery. Non costruirlo soltanto con `System.currentTimeMillis`
dopo ogni retry: persisti prima del dispatch la sequenza/detection e riusa `SensorEventIds.create`.

### 7.5 Callback, ingress e rearm

Flusso atteso:

```text
TriggerEventListener.onTrigger
  → snapshot bounded delle regole ancora eleggibili
  → per ogni regola persisti detection pending + event id
  → per ogni regola TriggerEnvelope(SensorChanged)
  → Engine.onTrigger(lazy state), serializzato/idempotente
  → completa ogni pending una sola volta
  → se la stessa revisione è ancora ARMED, rearm one-shot
```

Il rearm deve avvenire anche se il dispatch fallisce o viene cancellato, ma senza nascondere la
cancellation originale:

- cattura l'esito/errore;
- in `finally`/`NonCancellable` riconcilia solo la revisione ancora corrente;
- rilancia `CancellationException`;
- non riarmare una regola disabilitata durante il dispatch;
- se rearm fallisce, lascia stato recuperabile e audit metadata-only, non un falso registered.

Non chiamare direttamente azioni dal listener: passa sempre dall'Engine e dalla fire policy.

### 7.6 Lifecycle completo

Collega e testa:

- arm/enable;
- edit con fingerprint nuovo;
- disable;
- delete;
- app start;
- process recreation;
- `BOOT_COMPLETED`;
- `MY_PACKAGE_REPLACED`/package replace;
- capability revocation o sensor sparito.

Recovery registra soltanto automazioni `ARMED` con fingerprint valido e requirement esatto. Un
outage Shizuku non deve influire. Sensor assente/incompatibile è strutturale: `NEEDS_REVIEW`, non
retry infinito. Un failure transitorio del servizio può restare blocked/retry bounded, ma va
classificato da evidenza, non dall'intuizione.

### 7.7 Test host minimi prima del device

Scrivi prima i test. Copertura minima:

1. registrar significant motion positivo e tutti gli altri kind ancora non implementati negativi;
2. request one-shot idempotente: reconcile ripetuto non duplica;
3. callback produce evento senza raw value;
4. event id uguale sulla redelivery e diverso sulla detection successiva;
5. fingerprint stale non esegue e non si riarma;
6. dispatch success → rearm;
7. dispatch failure → rearm + errore preservato;
8. cancellation → cleanup/rearm bounded + cancellation rilanciata;
9. disable/delete durante dispatch → nessun rearm;
10. store corrotto/future schema/out-of-bounds → fail closed, mai crash/restart loop;
11. demand union connectivity + sensor: togliere uno non ferma l'altro;
12. ultimo demand rimosso → FGS stop;
13. APP_START/boot/package replace riconciliano senza duplicare;
14. Shizuku down non rimuove sensor capability;
15. manifest pubblica `sensor.significant_motion` solo con hardware corretto e backend attivo;
16. review mostra il kind esatto e cooldown;
17. golden fingerprint v1 restano byte-identici;
18. bridge rifiuta kind non pubblicato/raw/high-rate.
19. due regole significant-motion condividono una sola registrazione fisica ma ricevono ciascuna un
    envelope; disabilitarne una lascia viva l'altra senza callback duplicate;
20. una nuova process generation non crede a un vecchio stato fisico `registered` persistito.

Compila sempre anche AndroidTest: un gate mirato JVM non scopre firme stale nei test device.

### 7.8 Gate device fisico

Device: OnePlus `100.74.117.9:5555`, Android API 36, Tailscale ADB. Shizuku è installato e attivo,
ma il test sensore non deve dipenderne.

Regole operative:

- un comando ADB alla volta;
- mai `ArgusNavigationInstrumentedTest`;
- mai reboot senza Lorenzo presente;
- mai bearer, numeri, coordinate, payload o sample tramite CLI/runner/log;
- raccogli log e stato prima di proporre un fix;
- dopo build, install e instrumentation restano comandi separati.

Sequenza fisica richiesta:

1. probe sul device conferma significant motion `ONE_SHOT`, wake-up e metadati, senza sample;
2. arma una regola innocua (notifica locale) e manda app cached/background;
3. Lorenzo muove fisicamente il telefono: una sola esecuzione osservata;
4. attendi la barriera di rearm, poi secondo movimento: seconda esecuzione distinta;
5. process kill/restart controllato, senza reboot: movimento ancora consegnato;
6. disable: movimento non esegue;
7. ri-enable e poi delete: nessun callback residuo;
8. verifica demand/FGS cleanup e assenza wake lock permanente;
9. solo con Lorenzo presente, se ancora necessario, pianifica il gate reboot separato.

Il journal da solo non prova l'effetto e un effetto da solo non prova il routing. Per la notifica
locale raccogli entrambi, senza stampare payload. Non dare PASS per «il codice dovrebbe».

## 8. Errori e cattive abitudini da non ripetere

Questa è la parte volutamente franca richiesta da Lorenzo. L'attribuzione è precisa: non tutto ciò
che segue è un errore tuo.

### 8.1 Errori Claude confermati

1. Hai attribuito il rifiuto `run_shell` a una race Shizuku. Era falso: `run_shell` non entrava in
   `availableTools` e l'executor rispondeva `live_confirmation_required` per design. Prima di dare
   un nome elegante a un bug, segui il dato lungo manifest → validator → requirements → executor.
2. Hai sovrastimato l'anello di retroazione Wi-Fi nonostante il tuo stesso output contenesse
   evidenza contraria. Lo hai riconosciuto nel tuo handoff: conserva quella disciplina. Una
   narrazione coerente non prevale mai sul log.
3. Il primo harness dei gate fisici armava il geofence con `cooldownMs = 0`, ignorando la
   contromisura già prescritta da C2. Lo hai corretto e ammesso, ma un harness non rappresentativo
   può produrre una diagnosi falsa quanto il codice di produzione: deve rispettare gli stessi
   invarianti del validator.
4. `cleanupGates` cancellava dallo store senza annullare le registrazioni OS. Il reconcile al
   successivo avvio era self-healing, ma non rendeva il gate pulito. Per P3-2B usa il percorso di
   cleanup reale e asserisci sia demand vuoto sia cancellazione fisica; non affidarti a un riavvio
   futuro per ripulire il test corrente.

### 8.2 Problemi ereditati che Codex ha dovuto correggere

- Geofence: l'evidenza non considerava correttamente età, accuratezza e disco d'incertezza; cleanup
  del harness doveva lasciare zero registrazioni OS.
- Shell WhatsApp: il routing live doveva restare legato alla stessa chat/revisione approvata, non
  soltanto a una policy statica permissiva.
- Logica tri-state: `NOT UNKNOWN` non può diventare TRUE. È stato corretto in `a9e2436`.
- Coordinate UI: il rendering esponeva rumore floating/negative zero.

Non riaprire questi fix durante il lavoro sensori senza una regressione riproducibile.

### 8.3 Cose che NON sono colpe Claude

- La firma stale del probe in un AndroidTest è stata introdotta durante P3-1D Codex e scoperta dal
  gate AndroidTest; è registrata per ricordare a entrambi di compilare il test APK.
- Il timeout da 5 s del primo gate P3-2A è stato un errore operativo Codex.
- Il falso mismatch SHA CRLF è una trappola Windows che tu hai documentato correttamente.
- Sul geofence sotto pressione hai rifiutato di scrivere PASS prima dell'evidenza fisica: scelta
  corretta da mantenere.

### 8.4 Regole pratiche conseguenti

- Non chiamare «race» un comportamento strutturale senza traccia temporale.
- Non chiamare «production bug» una race del solo test: in P3-1C il primo run aveva già eseguito e
  il test osservava `FIRED` prima del successivo disable; è stata corretta la barriera del harness.
- Non aggiungere default a subtype v1 per comodità: cambia serializzazione e fingerprint.
- Non allargare il manifest prima del backend: hardware presente non significa regola armabile.
- Non usare il raw SHA Windows/Linux senza normalizzare LF; se il deploy è via `scp`, verifica anche
  il raw hash ma interpreta correttamente eventuali differenze.
- Non fermarti ai happy path JSON: liste al posto di enum, duplicati, Unicode malformato, NaN,
  oggetti non hashable e id canonici falsi devono fallire senza 500.
- Non limitarti a unit test: JVM/Robolectric, AndroidTest compilato, deploy e gate fisico provano
  confini diversi.

## 9. Rischi e mancanze prevedibili

Non sono autorizzazione a espandere P3-2B; sono cose da preservare nel backlog.

- I flavor `personal-full`/`play-core` non esistono ancora: P3-3.
- `ACTIVITY_RECOGNITION` non va chiesto in P3-2B: significant motion non lo richiede. Aggiungilo con
  UX e profilo corretti soltanto nella slice step P3-2C.
- Step counter richiederà baseline persistita e reboot-safe; il valore assoluto non è «passi della
  regola».
- Stationary/motion possono avere latenza 5–10 s e non essere wake-up: dichiararlo in review.
- FIFO disponibile non significa batching implementato. Non pubblicare sampling/latency prima di
  registrarli davvero e misurarli.
- OxygenOS può terminare FGS/processi nonostante le API standard: il gate deve separare esenzione
  Android verificabile dai toggle vendor non verificabili.
- Un device con più sensor implementation dello stesso type richiede selection policy condivisa
  probe/backend.
- Dynamic sensor add/remove non è gestito. Valutalo solo dopo la verticale base e con un caso reale.
- Audit lifecycle completo, TTS, runtime base senza Shizuku, computer-use e P4 variables restano
  slice successive nel piano; non mescolarle al significant motion.

## 10. Comandi di verifica sicuri

Bridge locale:

```text
cd ops/hermes
python -m unittest test_bridge.py
```

Gate host:

```text
.\gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest --no-build-cache --rerun-tasks --no-parallel --no-daemon
```

Hermes, senza bearer in CLI:

```text
ssh hermes 'systemctl --user is-active argus-bridge'
ssh hermes 'journalctl --user -u argus-bridge -n 8 --no-pager --output=cat'
ssh hermes 'cd /home/lorenzo/argus-bridge && python3 -m unittest test_bridge.py'
```

Per un deploy:

- stage sotto `/home/lorenzo/argus-bridge-stage-<commit>`;
- test in stage;
- backup esplicito nella directory finale;
- copia file repo, test finale, restart, active/log startup;
- hash repo/host;
- cleanup della stage solo dopo `realpath` e confronto con il path atteso.

Non leggere/stampare `argus-bridge.env`. Il bearer live passa solo tramite il file privato one-shot
dell'app/test, mode 0600, e viene cancellato dopo l'uso.

## 11. Definition of Done P3-2B

P3-2B è chiusa soltanto quando:

- backend e coordinator one-shot sono idempotenti e recovery-safe;
- FGS demand reasons sono condivisi con connectivity;
- manifest pubblica soltanto significant motion realmente armabile;
- callback stale/fingerprint mismatch falliscono chiuse;
- rearm funziona dopo prima callback e dopo process recreation;
- disable/delete cancellano registrazione e demand;
- full gate host è verde con AndroidTest compilato;
- sul OnePlus passano due movimenti distinti, process restart e negativi cleanup;
- consumo/tempo FGS sono almeno misurati e riportati, non inventati;
- piano, decision record e ledger separano PASS, NOT PROVEN e rischio residuo;
- working tree è pulito e commit pushati sull'hub Unraid.

Se Lorenzo non è disponibile per il movimento/reboot, implementazione e test host possono avanzare,
ma lo stato resta `READY FOR PHYSICAL GATE`, non `COMPLETE`.
