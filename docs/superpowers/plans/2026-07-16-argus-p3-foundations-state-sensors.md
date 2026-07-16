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

1. Manifest espone famiglie reader e limiti, non un inventario infinito di chiavi.
2. `/compile` v2 accetta i nuovi subtype; v1 resta esplicitamente incompatibile, non fallback `/chat`.
3. Validator Python e Kotlin devono avere fixture condivise positive/negative.
4. Deploy atomico host + Android; health/version/hash normalizzati; rollback documentato.
5. Prompt NL reale: «se il voltaggio scende sotto X…» produce draft armabile o chiarimento onesto.

Gate: test bridge, compile live, review locale e fire reale; nessun valore state sensibile nei log.

## P3-1D — stato generativo minimo

Non aggiungere campi default a `Action.InvokeLlm` v1. Creare un subtype/profilo v2 che elenca query
esplicite. Il bridge `/act` riceve soltanto quelle; ogni valore ha classificazione privacy/taint.
Migrare una regola solo con nuova approvazione, mai riscrivendo il fingerprint in silenzio.

## P3-2A — dominio e capability sensori

1. `Trigger.Sensor` con kind chiusi e parametri bounded; `TriggerEvent.SensorChanged` con event id
   privo di raw value sensibili.
2. Probe `SensorManager` runtime: disponibilità, reporting mode, wake-up, FIFO e permission.
3. Manifest invia solo kind realmente armabili sul device.
4. Validator richiede cooldown coerente e rifiuta raw/high-rate non supportati.

Gate: JVM + Robolectric; niente listener ancora in questa slice.

## P3-2B — significant-motion verticale

1. Generalizzare la sentinella FGS con demand reasons condivisi (connectivity + sensor).
2. Coordinator single-writer e stato persistito; requestTriggerSensor one-shot.
3. Callback → ingress → Engine; rearm idempotente in `NonCancellable` dopo dispatch.
4. Cleanup su disable/delete e recovery APP_START/BOOT/PACKAGE_REPLACED.
5. Notifica FGS esplicita e stop action; nessun wake lock permanente.

Gate device fisico:

- arm con app cached/background;
- movimento reale ⇒ una sola esecuzione;
- secondo movimento ⇒ prova che il one-shot è stato riarmato;
- process kill/restart ⇒ ancora attivo;
- disable/delete ⇒ nessun callback residuo.

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
