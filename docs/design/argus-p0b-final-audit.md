# Argus P0-B — audit finale Commander

Data audit: 2026-07-14

Branch: `feat/argus-p0b-dry`

Baseline H6: `10bf5de`
Fonte operativa: `docs/superpowers/plans/2026-07-13-argus-commander-replan.md`

## Esito sintetico

Il codice P0-B non è più una demo: runtime, persistenza, bridge, scheduler, Shizuku e UI sono
collegati a implementazioni reali. I gate automatici e le suite Android non interattive sono
verdi. Sono passati anche gli E2E reali Hermes/DND, process-death e outage Shizuku, oltre a
installazione pulita della `0.1.0` e smoke dei sei schermi. La completion resta intenzionalmente
**non dichiarata** finché non vengono eseguiti il reboot di recovery/LNP, la review finale e il
merge.

## Matrice requisiti → evidenza

| Requisito corretto | Stato | Evidenza autorevole |
| --- | --- | --- |
| Compile one-shot tramite Hermes `/compile` v1, HTTPS, bearer, schema strict e idempotenza | Provato | `brain-android`, `argus-bridge`, contract test, auth `401`, compile/replay live, health Android |
| Privacy prima di qualunque chiamata bridge | Provato | `ConfiguredBridgeBrain`, `AppPreferencesStore`, unit test e redirect navigation dopo revoca |
| Draft mostrato deterministicamente, validato e fingerprintato prima dell'arm | Provato | `ApprovalFlow`, `ApprovalService`, `DraftValidator`, Room draft revisionato e relativi test |
| Modifica/re-enable concorrente non arma una revisione diversa | Provato | CAS fingerprint/revision, `enableIfApproved`, test TOCTOU Room/coordinator |
| Persistenza Room fail-closed per JSON corrotto/schema futuro | Provato | placeholder persistente `NEEDS_REVIEW`, migrazioni v1/v2/v3→v5 e test device |
| Claim evento/cooldown/journal atomici e retry idempotente | Provato | engine + `RoomExecutionJournal`; duplicate/crash/retry testati in concorrenza |
| Time trigger Android 16 event-driven, DST-safe, exact opt-in con fallback | Provato | `TimeAlarmCoordinator`, `AndroidTimeAlarmBackend`, 57 test automation + 4 instrumented device |
| Shizuku moderno con UserService, coda single-writer, output cap e azioni tipizzate | Provato | 10 test core, 11 device-tools, test UID shell e round-trip DND reali |
| UI reale Hilt/ViewModel/Flow, non fixture | Provato | commit `10bf5de`, 4 navigation test inclusa card→dettaglio reale, build APK, mapping/UI test |
| Cleartext negato e Android 16 LNP compatibile con Tailscale | Parziale | cleartext negato e health Hermes/Tailscale provata; il denial osservato prima del reboot non è evidenza LNP conclusiva perché la procedura Android richiede il reboot dopo il compat flag |
| Chat → compile → review → arm → AlarmManager → DND → journal | Provato device | `ArgusProductionE2EInstrumentedTest`: Hermes reale, DND TOTAL, journal `SUCCEEDED`, one-shot disabilitata e delivery duplicata ignorata (`OK (1)`) |
| Recovery reale dopo morte processo | Provato device | test host bifase `schedule` → `am kill dev.argus` → alarm → `verify`, PID diverso, un solo `FIRED`, DND e journal corretti (`OK (1)` per fase) |
| Shizuku assente al fire-time | Provato device | daemon reale arrestato: DND invariato, un solo `BLOCKED_POLICY/capability_unavailable`, zero `FIRED`, one-shot consumata senza replay; daemon e stato ripristinati |
| Installazione pulita e sei schermi | Provato device | uninstall/reinstall dell'APK finale `0.1.0`; onboarding, chat, lista, log, sistema e dettaglio reale `OK (4)` anche con keyguard |
| Reboot/boot receiver e stato degradato Shizuku | Pendente device | unit test verdi; manca prova reale dopo reboot e successivo ripristino daemon/ADB |
| Documentazione coerente e merge no-ff | In corso | replan/spec corretti; ledger, review finale e merge dopo i gate device |

## Gate automatici correnti

- `engine-core`: 101 test
- `core-shizuku`: 10 test
- `device-tools`: 11 test
- `data`: 40 test
- `brain-android`: 17 test
- `automation-android`: 57 test
- `ui`: 7 test
- Totale JVM/Robolectric debug: **243**, zero failure/error
- `test lintDebug assembleDebug assembleDebugAndroidTest`: verde
- Gate conclusivo forzato senza cache: **750 task eseguiti**, `BUILD SUCCESSFUL`
- Lint: zero errori; 52 warning manutentivi (dependency/version catalog e 4 suggerimenti KTX)
- Device API 36: navigation 4/4, bridge store 3/3, Shizuku 1/1,
  device-tools 1/1, scheduler/capability 4/4
- E2E API 36: produzione Hermes/DND 1/1; process-death 2 fasi; outage Shizuku 3 fasi;
  stato esterno finale DND `off`, exact-alarm `default`, daemon Shizuku attivo
- Bridge server: 12/12 test; health post-deploy da Android `OK (1)`
- Rerun produzione post-clean-install: Shizuku riallineato al nuovo UID; compile fermato dalla
  quota provider prima di draft/arm/allarme, con DND `off` ed exact-alarm ripristinato a `default`

## Problemi bloccanti trovati e corretti

1. **Reply policy fail-open.** Whitelist vuota, `replyTargetSender=false` e reply statica potevano
   aggirare il vincolo destinatario. Ora la policy è unica, fail-closed e rivalidata al fire-time.
2. **TOCTOU e idempotenza.** Cooldown, claim evento, salvataggio e re-enable avevano finestre di
   race. Ora usano transazioni/CAS su fingerprint e ID evento stabile.
3. **DST non strettamente futuro.** Gap/overlap potevano restituire una ricorrenza già consumata.
   `CronSchedule` garantisce `next > after` con regression test Europe/Rome.
4. **Cancellation ed error isolation.** Eccezioni/cancellazioni potevano interrompere il batch o
   essere trasformate in failure ordinaria. La cancellazione propaga; ogni azione è isolata.
5. **Bridge permissivo.** Schema ignorato, `reply` non type-safe, body illimitato e fallback
   `/chat`. Ora `/compile` è l'unico path, con schema/content-type/body cap e errori tipizzati.
6. **Segreti/configurazione.** Bearer non configurabile in sicurezza e corruzione ciphertext
   preservabile. Ora Android Keystore, update atomico, HTTPS-only e validazione del token esistente.
7. **Room insufficiente per recovery/UI.** Mancavano Flow bounded, journal per-azione, quarantine e
   retention. Aggiunti senza includere dati sensibili nei backup (backup app disabilitato).
8. **Scheduler Android non attuale.** Il brief proponeva FGS persistente e `USE_EXACT_ALARM`.
   Sostituiti da runtime event-driven e `SCHEDULE_EXACT_ALARM` revocabile con fallback inexact.
9. **UI ancora demo.** Fixture, callback inerti e loading/review incompleti. Hilt/ViewModel reali,
   Room Flow, onboarding, config Hermes, privacy revoke, conferme e capability gate sono cablati.
10. **Arm/enable stale.** Una review o toggle concorrente poteva agire sulla revisione successiva.
    Arm, disable ed enable sono ora condizionati al fingerprint esatto.
11. **Manifest capability incompatibile col bridge.** Il probe pubblicava solo nomi semantici
    (`toggle.set`, `app.launch`), mentre il validator Hermes accetta i discriminatori wire delle
    azioni (`set_dnd`, ...). I test con fixture artificiali non lo rilevavano. Ora JSON, manifest e
    journal condividono `ActionTypeIds`, e il test usa le capability prodotte davvero da Android
    (`746668b`).
12. **Bearer nel comando di instrumentation.** Un primo harness diagnostico passava il token con
    `am instrument -e`, la cui command line può finire nei log di adbd. Il token è stato ruotato,
    logcat ripulito e tutti gli E2E live ora consumano una sola volta un file privato `run-as`,
    eliminato prima della chiamata di rete; nessun segreto transita più negli argomenti ADB.
13. **Icona tematica non valida.** Il layer monochrome era la bitmap opaca tinta di bianco e il tag
    API 33 viveva nel resource base. Ora usa un mask vector entro la safe zone e resource `-v33`,
    mantenendo la variante adaptive a colori per API 30–32 (`82fa87f` + hardening finale).
14. **Drift binario Compose.** La prima card reale della lista crashava con `NoSuchMethodError` su
    `FlowRow`: `ui` compilava contro Foundation 1.7.6, mentre Hilt portava nell'APK la 1.8.2. Il BOM
    è ora allineato a Compose `2025.05.01`/Foundation 1.8.2 e lo smoke card→dettaglio resta come
    regressione su device.
15. **Errori provider scambiati per compile valido.** La CLI Hermes può terminare con exit `0` ma
    testo diagnostico privo di `@@META@@`; il bridge lo trasformava in `200/draft_missing`. Ora
    output senza protocollo è `502`, quota riconosciuta è `503` idempotente e stdout/stderr non
    vengono esposti. Fix distribuito su Hermes e coperto da 12 test.

## Correzioni ai piani/spec

- Il commander replan prevale sul brief P0-B storico.
- Nessun FGS persistente in P0-B. P2 potrà usare worker durevole o FGS short-lived solo se il
  budget del receiver risulta insufficiente in misure reali.
- `SCHEDULE_EXACT_ALARM`, non `USE_EXACT_ALARM`; exact solo per regole che lo richiedono.
- Il geofence è compilabile/reviewabile, ma resta non armabile finché manca il registrar P2.
  Salvare ARMED una regola ineseguibile sarebbe fail-open.
- Le azioni P1/P2/P3 restano visibili come capability indisponibili, non simulate.

## Rischi e miglioramenti non bloccanti

### Dipendenza operativa corrente

- Al momento dell'audit l'account Codex usato da Hermes risponde `429` per quota esaurita e non è
  configurato un provider alternativo. Il precedente E2E compile è valido, health e bridge sono
  verdi, ma nuove compilazioni restano indisponibili finché la quota non si rinnova o Lorenzo non
  sceglie un fallback. Il bridge ora espone correttamente l'indisponibilità come `503`, mai come
  bozza valida.

### Da affrontare in P1

- Notification trigger e prova empirica del `conversationId` WhatsApp/`isGroup`.
- RemoteInput e fallback `DEFERRED` quando la notifica scade durante la latenza LLM.
- Lane `InvokeLlm` realmente durevole, snapshot immediato e battery exemption.
- Budget/rate limit globale; oggi la UI lo dichiara esplicitamente non disponibile.

### Da affrontare in P2

- Registrar Play Services geofence, PhoneState e Connectivity.
- Catene lunghe: `goAsync()` ha un budget breve, mentre una shell può avere timeout superiore.
  Spostare il lavoro lungo in un meccanismo durevole; non aumentare semplicemente il timeout.
- Procedura/UX di ripristino Shizuku via Wireless ADB sul OnePlus attuale non-root; nessun
  auto-start privilegiato va promesso finché non esiste un percorso realmente provato.
- Policy esplicita per eventi persi durante un outage Shizuku (retry con expiry oppure drop): P0-B
  sceglie il drop fail-closed, lo audita e mantiene attive solo le ricorrenze future.
- Wizard OxygenOS e misure vere di Doze/background, non assunzioni.
- Export/import locale firmato delle regole: oggi backup disabilitato protegge i dati ma un wipe
  elimina anche le automazioni.

### Da affrontare in P3

- Loop `computer_use` interattivo e transport/provider veloce multimodale.
- Conferma live per irreversibili/RunShell, vision/web e streaming chat.
- Eventuale cifratura Room se il threat model passa dal backup/cloud a compromissione locale.

## Residui intenzionali P0-B

- `Tap`, `InputText`, `WhatsAppReply`, `RunShell`, geofence e generative lane sono fail-closed.
- Exact alarm negato degrada a inexact invece di perdere l'esecuzione; la precisione effettiva
  merita una superficie UI dedicata in una fase successiva.
- Delete concorrente nel dettaglio è unconditional ma non può armare/resuscitare una regola; un
  eventuale CAS-delete sarebbe hardening additivo, non un bypass attuale.
- I test ViewModel sono selettivi: i confini safety sono coperti nei servizi sottostanti e la
  navigazione reale è strumentata; ampliare solo quando emergono regressioni concrete.
- Dopo uninstall/reinstall Android assegna un nuovo UID, mentre Shizuku Manager può conservare la
  grant associata al vecchio. La produzione deve richiedere nuovamente l'autorizzazione tramite il
  flusso supportato e resta fail-closed; l'edit mirato del JSON Shizuku è solo una procedura del
  laboratorio E2E con backup, non una funzione dell'app.

## Condizioni per chiudere P0-B

1. ~~Autorizzare Shizuku ed eseguire E2E Hermes/DND/journal con duplicate delivery.~~ Fatto.
2. ~~Eseguire E2E bifase con morte processo reale e outage Shizuku.~~ Fatto.
3. ~~Reboot: verificare boot recovery/degraded Shizuku, denial LNP dopo compat+reboot, baseline LAN
   dopo disable+reboot e Hermes/Tailscale dopo il ripristino.~~ **Fatto (2026-07-14 sera, con
   Lorenzo)**: tutti e 4 i punti verdi — recovery AlarmManager post-boot, degraded fail-closed,
   LNP denial con flag attivo + Hermes/Tailscale ok (46 ms), baseline LAN dopo secondo boot
   (17 ms). Dettagli: handoff §20.
4. ~~Installazione pulita e smoke dei sei schermi.~~ Fatto sulla `0.1.0`, `OK (4)`.
5. ~~Full gate finale.~~ Fatto: 750 task eseguiti, 243/243 test e zero errori lint.
6. ~~Ripetere il compile live post-clean-install quando la quota Hermes si rinnova o viene scelto
   un fallback; il path di indisponibilità `503` è già verificato e fail-closed.~~ **Fatto
   (2026-07-14 sera)**: quota gpt-5.5 rinnovata, compile live 12,3 s + `/act` 7,4 s durante
   l'Esempio 3 reale di P1-7 (handoff §21); nessun fallback provider introdotto.
7. Aggiornamento ledger, commit/push, review e merge no-ff su `master` dopo il gate reboot.
