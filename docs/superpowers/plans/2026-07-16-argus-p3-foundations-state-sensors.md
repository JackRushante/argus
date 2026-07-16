# Argus P3 — piano operativo: fondazioni, state reader e sensori

**Fonte decisioni:** `docs/superpowers/specs/2026-07-16-argus-p3-decision-record.md`
**Metodo:** slice verticali TDD, commit atomici, nessun PASS fisico dedotto da JVM/Robolectric.

## Stato iniziale verificato

- P2 è su `master`; full gate iniziale 758/758 verde.
- Fix-pass indipendente: `6b307c5` e `a9e2436`.
- Device raggiungibile via ADB wireless Tailscale; Shizuku attivo al passaggio di consegne.
- Inventario OnePlus conferma significant-motion, stationary, step, proximity, light e FIFO accel.

## P3-0 — decisioni e drift documentale

- [x] D0, transport a due tier, taint per sink, state reader, sensori e profili pubblici definiti.
- [x] Aggiornare spec principale rev 6, `CLAUDE.md`, README contratto bridge e chiudere le righe P2
  stale senza alterare gli handoff storici.
- [x] Aggiungere ADR breve per schema automazione / fingerprint / protocollo separati.

Gate: nessuna contraddizione attiva su B8, sensori, shell WhatsApp, P2 chiusa o Play/SMS.

## P3-1A — lettura minima dello stato esistente

**Stato 2026-07-16: COMPLETA.**

1. [x] Aggiungere `StateReadRequest(keys, foreground, location)` in `engine-core`.
2. [x] Derivare la richiesta dalle condizioni con visitor esaustivo.
3. [x] Per `InvokeLlm(context=state)` mantenere temporaneamente il profilo legacy esplicito, ma non
   leggere stato per azioni deterministiche senza condizioni.
4. [x] Cambiare il provider dell'Engine in request-aware; cache/union solo nello stesso batch.
5. [x] Collegare `CurrentLocationProvider` a `DeviceState.location` senza Shizuku.
6. [x] Pubblicare `state.location` soltanto col grant adatto.

Test obbligatori:

- [x] zero chiamate reader per trigger → notifica/clipboard/toggle senza condizioni;
- [x] una sola key richiesta quando la condizione ne usa una;
- [x] foreground e location indipendenti;
- [x] union nel batch senza perdere valori;
- [x] missing/exception ⇒ UNKNOWN, `NOT UNKNOWN` non esegue;
- [x] revoca Shizuku non blocca una regola che usa solo location/API normali.

Gate device: **PASS** su OnePlus reale con
`ArgusMinimalStateReadInstrumentedTest`: una regola condizionata a batteria e una a location;
runner output privo di valori, coordinate e payload.

## P3-1B — modello `StateQuery`

**Stato 2026-07-16: COMPLETA.**

1. [x] Nuovi sealed subtype e serializzazione golden: Builtin, Setting, SystemProperty, Sysfs,
   DumpsysField; nuovo `Condition.StateCompare` tipizzato.
2. [x] Validator per famiglia, caratteri, path, namespace, tipo/op e limiti.
3. [x] Canonical query id stabile e collision-safe; capability per famiglia.
4. [x] Reader Android argv-only, output/timeout bounded e parser in-process.
5. [x] Probe della query concreta prima di Arm; risultato positivo/negativo redatto in review.
6. [x] Rendering deterministico completo di reader e parametri.

Test negativi **PASS**: traversal/symlink fuori `/sys`, control char, service/key troppo lunghi,
output troncato, campo ambiguo, setting assente, tipo errato, timeout, cancellation e reader
sparito dopo l'arm. Le fixture golden confermano che i fingerprint v1 preesistenti sono invariati.

Gate device iniziale: **PASS** su OnePlus reale con
`ArgusParametricStateReaderInstrumentedTest`. Il path sysfs del voltaggio è risultato non leggibile
dal shell UID e quindi è stato correttamente escluso dal probe; il reader stabile scelto è
`dumpsys battery`/campo `voltage`. Il valore è stato confrontato con il metodo indipendente Android
`ACTION_BATTERY_CHANGED`/`EXTRA_VOLTAGE`, quindi usato da una condizione dell'Engine. Il runner non
ha stampato il campione.

Gate host completo: **PASS**, `test lintDebug assembleDebug assembleDebugAndroidTest` con cache
disattivata, task rieseguiti e niente parallelismo: **758/758 task**, `BUILD SUCCESSFUL`.

## P3-1C — bridge compile v2

**Stato 2026-07-16: COMPLETA** (`462bc83` + fix race del solo harness nel commit successivo).

1. [x] Manifest espone famiglie reader e limiti, non un inventario infinito di chiavi.
2. [x] `/compile` v2 accetta i nuovi subtype; v1 resta esplicitamente incompatibile, non fallback `/chat`.
3. [x] Validator Python e Kotlin usano la stessa fixture positiva/negativa, inclusi policy e limiti.
4. [x] Deploy server-first compatibile `[1,2]`, Android v2, `/health/v2`, hash LF e rollback documentati.
5. [x] Prompt NL reale sul voltaggio produce un draft armabile e verificato fino al fire reale.

Gate: test bridge, compile live, review locale e fire reale; nessun valore state sensibile nei log.

Evidenza:

- bridge locale e host: 29/29; staging e directory finale entrambe verdi;
- gate completo senza cache: 759/759 task eseguiti, `BUILD SUCCESSFUL`;
- file host `bridge.py`, `test_bridge.py`, fixture: hash raw identici al repo; health autenticato
  dichiara compile `[1,2]`, act `[1]` e lo SHA normalizzato atteso;
- OnePlus reale: richiesta naturale «se il voltaggio ... sotto 100000 millivolt» →
  `StateCompare(DumpsysField("battery","voltage"), NUMBER, LT)` → probe locale → review/arm →
  AlarmManager exact → `show_notification` riuscita nel journal → one-shot `DISABLED` → cleanup;
- il primo run ha osservato `FIRED` pochi millisecondi prima del successivo `disableIfApproved`:
  era una race dell'asserzione del test, provata dall'ordine del coordinator. Il rerun con attesa
  esplicita della barriera è `OK (1 test)`; nessun fix di produzione è stato necessario.

## P3-1D — stato generativo minimo

**Stato 2026-07-16: COMPLETA** (`0e6006f`).

1. [x] Lasciare `Action.InvokeLlm` v1 e i relativi fingerprint byte-compatibili.
2. [x] Aggiungere `InvokeLlmV2` con query esatte, tipo, policy, integrità e riservatezza obbligatori.
3. [x] Leggere e congelare soltanto i valori approvati; nessuno snapshot state legacy nel path v2.
4. [x] Validare fingerprint/policy/valori prima e dopo il Brain e usare una lane generativa tipizzata.
5. [x] Aggiungere `/act` v2 strict mantenendo `/act` v1 operativo e separando l'idempotenza per wire.
6. [x] Mostrare in review ogni reader e la disclosure esplicita verso Hermes/provider cloud.

Evidenza:

- suite bridge locale e host **31/31**; gate host completo forzato **759/759 task**;
- deploy server-first: health autenticato `compile=[1,2]`, `act=[1,2]`, servizio attivo e hash raw
  repo/host identici per bridge, test e fixture;
- OnePlus reale: health, compile v2 con `DumpsysField(battery, voltage)` classificato
  `NUMBER/CLEAN/SECRET` e act v2 autenticato sono tutti PASS nello stesso runner;
- il test act usa intenzionalmente un valore fixture (`4200`) per isolare il contratto wire. La
  lettura fisica del voltaggio era già stata provata in P3-1B/P3-1C; non viene falsamente contata
  come nuova prova end-to-end reader → LLM.

## P3-2A — dominio e capability sensori

**Stato 2026-07-16: COMPLETA.**

1. [x] `Trigger.Sensor` con kind chiusi e parametri bounded; `TriggerEvent.SensorChanged` e
   `SensorEventIds` non accettano raw value sensibili.
2. [x] Probe `SensorManager` runtime: disponibilità, reporting mode, wake-up, FIFO, delay e permission.
3. [x] Manifest invia solo l'intersezione hardware + grant + backend realmente collegato.
4. [x] Validator richiede cooldown 60 s..7 giorni e rifiuta raw/high-rate non supportati.

Il server conosce già i wire `sensor.<kind>`, ma la build P3-2A pubblica correttamente zero kind:
non esiste ancora un listener. P3-2B abiliterà `significant_motion` insieme al backend, evitando una
finestra in cui Hermes possa produrre una regola morta.

Gate: JVM + Robolectric; niente listener ancora in questa slice.

Evidenza (`055499c`):

- test dominio, policy capability, rendering e bridge Kotlin verdi; Robolectric legge realmente
  metadata `SensorManager` sintetici per reporting mode, wake-up, FIFO e delay;
- suite bridge locale, staging Hermes e directory finale **32/32**;
- full gate forzato senza cache: **759/759 task**, `BUILD SUCCESSFUL`;
- deploy server-first compatibile: health ancora `compile=[1,2]`, `act=[1,2]`, unit active, backup
  `*.pre-p3a-055499c` e hash raw repo/host identici per bridge, test e fixture;
- nessun gate callback fisico dichiarato: P3-2A non registra listener per definizione.

## P3-2B — significant-motion verticale

1. Generalizzare la sentinella FGS con demand reasons condivisi (connectivity + sensor).
2. Coordinator single-writer e stato persistito; requestTriggerSensor one-shot.
3. Callback → ingress → Engine; rearm idempotente in `NonCancellable` dopo dispatch.
4. Cleanup su disable/delete e recovery APP_START/BOOT/PACKAGE_REPLACED.
5. Notifica FGS esplicita e stop action; nessun wake lock permanente.

### Stato 2026-07-16 (Claude): **HOST COMPLETO — READY FOR PHYSICAL GATE**

Implementazione host chiusa, in TDD, su `master` (commit `b2e9919` → `204de74`). **Non è
`COMPLETE`**: i movimenti fisici, il process restart e i negativi disable/delete richiedono Lorenzo
(gate §7.8 dell'handoff), quindi lo stato resta `READY FOR PHYSICAL GATE` come prescrive la DoD.

Cosa è stato costruito:
- `SensorTriggerCoordinator` — registrazione fisica **condivisa per kind** (più regole = un solo
  `requestTriggerSensor`, fan-out a valle); stato `registered` **solo in memoria** (dopo process
  recreation è vuoto → reconcile ri-registra: nessun falso "registrato ma morto");
  Unavailable→NEEDS_REVIEW, Failure→retry bounded.
- `SensorEventIngress` — one-shot: al callback marca consumed, fan-out per regola, rearm **sempre**
  in `NonCancellable` senza inghiottire `CancellationException`; event-id stabile su redelivery,
  fingerprint di una revisione superata non corrisponde.
- `SharedForegroundSentinel` — **un solo FGS** (decision record §6.2): connectivity e sensori ne
  prendono la vista demand; il servizio si ferma solo quando l'unione dei demand è vuota. Il
  coordinator connectivity resta invariato.
- `AndroidSignificantMotionBackend` — `requestTriggerSensor` reale, non Shizuku, non logga
  `TriggerEvent.values`; rifiuta mode≠ONE_SHOT o wakeUp=false.
- `PrefsSensorDetectionStore` — unico stato persistito, corruption-safe.
- Wiring: registrar (`Trigger.Sensor`→reconcile), enablement, controller (recovery
  APP_START/boot/package), DI con `Provider` per spezzare il ciclo backend→ingress→coordinator.
- **`IMPLEMENTED_SENSOR_KINDS = {SIGNIFICANT_MOTION}`**: capability pubblicata nello stesso slice
  del backend (decision record §6.4).

Evidenza host: **full gate no-cache 759/759, EXIT=0**; suite sensori dedicata (coordinator,
ingress, FGS sentinel, backend Robolectric negativi, store persistito+corruption); bridge 32/32
active (già deployato da Codex con i wire `sensor.*`). Hardware confermato sul OnePlus via
`dumpsys sensorservice`: `significant_motion(17)` flags `0x05` = wake-up + ONE_SHOT.

**Mappatura ai 20 test host dell'handoff §7.7**: 1-8, 10-12, 19-20 nella suite sensori nuova;
9 coperto per composizione (rearm=reconcile rilegge `armed()`); 13 dal recovery del controller +
idempotenza coordinator; 14 per costruzione (il backend non usa Shizuku); 15-16-18 già coperti da
Codex in P3-2A (probe test, render test, bridge 32/32); 17 (golden fingerprint) invariato perché
il modello serializzato `Trigger.Sensor` è di P3-2A e questo slice non lo tocca.

### Gate device fisico (§7.8) — 2026-07-16, con Lorenzo

**PASSATO nella parte sostanziale**, con un'evidenza in tre pezzi complementari:

1. **Effetto + routing** — notifica reale **"movimento significativo"** (testo della nostra
   regola) vista da Lorenzo **camminando**, non scuotendo il telefono in mano. È il comportamento
   corretto del significant-motion, che ignora i movimenti da fermo.
2. **Callback fisico + rearm** — log backend `ArgusSensor: sensor trigger: kind=significant_motion`
   **due volte** (17:26:24 e 17:26:34) dallo **stesso pid** del processo app, e nel framework una
   re-registrazione dallo stesso pid dopo ognuno: il one-shot scatta e il nostro backend lo
   **ri-arma**.
3. **Catena completa fino al journal** — `ArgusSensorIngressInstrumentedTest` (production-path
   synthetic, stesso processo): arma + `onSensorTriggered` → **`FIRED` + `show_notification`
   SUCCEEDED**.
4. **FGS on-demand** — la sentinella condivisa (`ConnectivitySentinelService`, `specialUse`) è
   partita all'arm del sensore e si è **spenta al cleanup**; sensore deregistrato dal framework.

**Lezione di metodo (importante per chi riprende)**: leggere il journal con `am instrument`
**uccide e ricrea il processo dell'app**, e la scrittura async del journal (il callback fa
`scope.launch`) non fa in tempo a committare → il journal appariva vuoto pur essendo la catena
corretta. Per il journal di un trigger fisico: NON leggere subito con `am instrument`; usare il
test production-path synthetic (stesso processo) oppure dare tempo al commit e leggere il DB senza
ricreare il processo.

**Gate negativo (§7.8) — 2026-07-16, senza Lorenzo, `ArgusSensorNegativeInstrumentedTest`**
(stesso processo, catena di produzione; `OK (3 tests)` su OnePlus):
- **disable → non esegue**: regola armata poi `disable`, `onSensorTriggered` → **0 FIRED**;
- **delete → non esegue**: regola armata poi `delete`, `onSensorTriggered` → **0 FIRED**;
- **delete → deregistrazione fisica (no-leak)**: `reconcile` registra davvero SIGNIFICANT_MOTION,
  dopo il `delete` un secondo `reconcile` lo cancella (`cleanupSucceeded`, `requiredBy` vuoto,
  kind fuori da `registeredKinds`). Confermato dal registro del framework: `dumpsys sensorservice`
  mostra per ogni pid `+` (register all'arm) poi `-` (unregister al reconcile), e **0 connessioni
  `dev.argus` vive** a fine gate (21 menzioni, tutte storiche). L'unico FGS residuo è il
  `NotificationListenerService` (categoria `android`), non la sentinella sensore.

**Restano NON provati (residuo osservazionale, richiedono Lorenzo o tempo reale)**:
- process kill/restart → movimento ancora consegnato (in modo pulito, senza l'artefatto `am
  instrument` sopra) — richiede un movimento fisico dopo il kill;
- **misura consumo/tempo FGS** (DoD: NOT MEASURED) — richiede una finestra di batteria reale.

## P3-2C — stationary/motion e step

- Aggiungere solo i kind presenti e affidabili sul device.
- Stationary/motion: documentare 5–10 s nominali e possibile ritardo non-wakeup.
- Step: runtime request separata `ACTIVITY_RECOGNITION`; baseline memorizzata, reboot-safe; non
  interpretare il counter assoluto come passi della regola.
- Sensori continui: sampling minimo utile e `maxReportLatencyUs > 30 s` se FIFO disponibile.

Gate: misura consumo e tempo FGS prima/dopo; se non misurabile, stato NOT PROVEN, non PASS.

## P3-3 — runtime base senza Shizuku e release profiles

1. Spezzare executor Android normale / Shizuku; router esaustivo per action type.
2. Probe capability per singola azione, non blocco unico `shizukuAvailable`.
3. Test outage: LaunchApp/OpenUrl/DND/Ringer continuano; shell/toggle privilegiati si bloccano.
4. Product flavors/manifests: personal-full e play-core; extended solo quando serve davvero.
5. Checklist dichiarazioni Play e disclosure in-app; nessuna dichiarazione di approvazione finché
   non esiste una review reale Play Console.

## P3-4 — audit lifecycle e TTS

- Eventi arm/edit/enable/disable/delete/quarantine con actor, timestamp, fingerprint prefix e reason;
  mai payload/nome contatto/comando.
- `Speak` usa lo stesso source extractor locale della clipboard; testo TAINTED ammesso perché sink
  locale. Audio focus, locale/voice availability, max length, cancellation e queue policy tipizzati.

## P3-5 — computer-use lento, poi veloce

1. Contratto `AgentTurnTransport` + fake deterministic loop.
2. Tool catalog app-side, budget turni/tempo/output e live-confirmation per sink irreversibili.
3. Osservazione a11y prima; screenshot/vision fallback.
4. Bridge slow gratuito con un tool call per turno e idempotenza.
5. E2E unattended bounded.
6. Solo dopo: OpenAI-compatible/direct transport veloce e streaming UI opzionale.

## P4 readiness gate

Prima di introdurre variabili/template:

- valore runtime porta label + provenance;
- join monotono testato su tutte le trasformazioni;
- sink matrix applicata da validator e fire-time;
- capability target non è rappresentabile come stringa;
- fingerprint include template e policy;
- golden hash v1 invariati;
- nessun percorso TAINTED → command/routing/automation mutation nei property test.

## Regole operative device/host

- Un comando ADB alla volta; mai `ArgusNavigationInstrumentedTest`.
- Mai reboot senza Lorenzo presente.
- Mai token, indirizzi, numeri, coordinate o payload via CLI/log/test args.
- Bridge: deploy da file repo; confronto normalizzato per CRLF; health autenticato senza stampare
  bearer; backup e rollback prima del cambio wire.
- Gate completo standard:
  `gradlew test lintDebug assembleDebug assembleDebugAndroidTest --no-build-cache --rerun-tasks --no-parallel`.
