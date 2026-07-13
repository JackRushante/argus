# Argus — Piano P0-B: Glue Android (Shizuku, Room, AlarmManager, bridge Hermes)

> **⚠️ Emendamento Commander (2026-07-13):** questo piano va eseguito insieme a
> [`2026-07-13-argus-commander-replan.md`](2026-07-13-argus-commander-replan.md).
> Il gate di hardening del core/contratti viene prima di Shizuku; il vincolo
> “`engine-core` non cambia” è revocato. Il FGS persistente e `USE_EXACT_ALARM`
> non fanno più parte dell'architettura approvata.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **⚠️ Prerequisiti hardware/sessione (questo piano NON è interamente sviluppabile a secco):**
> - **Device OnePlus 15** (`oneplus`, Tailscale 100.74.117.9) raggiungibile via `adb` per instrumented test e verifica Shizuku.
> - **Shizuku** installato e attivo sul device (wireless-debugging o root).
> - **Hermes** (`ssh hermes`, 100.80.142.65) up, con `argus-bridge` via Tailscale Serve HTTPS.
> - Alcuni punti sono **da confermare empiricamente** (API Shizuku esatta, schema output bridge). Sono marcati `🔬 VERIFICA`. Non inventare: sondare il device/hermes e adeguare.

**Goal:** Rendere reali gli schermi M2: implementare le interfacce di `engine-core` su Android (Shizuku executor, Room store/audit, AlarmManager Time trigger, capability probe), il transport Hermes CliBridge per il `compile` one-shot, e il flusso di approvazione — così che **l'Esempio 2 funzioni end-to-end** ("dopo le 23 metti DND") e l'Esempio 1 sia pronto lato compile+arm (geofence registrar → P2).

**Architecture:** Nuovi moduli Android: `core-shizuku` (gateway privilegiato unico), `device-tools` (capacità tipizzate su Shizuku), `data` (Room), `brain-android` (transport HTTP), `automation-android` (wiring engine↔Android + ViewModel). Hilt per DI. Gli schermi `ui` restano invariati: i ViewModel espongono i contratti `XxxState` che già consumano. Il foreground service è **minimo** in P0-B (host di AlarmManager + registrazione trigger); l'hardening completo è P2.

**Tech Stack:** Kotlin 2.1.0, Shizuku API (`dev.rikka.shizuku:api`, `:provider`), Room 2.6.x + KSP, OkHttp 4.12, Hilt 2.52, WorkManager/AlarmManager, kotlinx-coroutines. Min SDK 30.

## Global Constraints

- **Shizuku è l'unico canale privilegiato.** Nessun `Runtime.exec("su")` diretto: tutto passa da `core-shizuku`. Shell UID 2000 (parità adb) è il privilegio di default.
- **`engine-core` cambia solo tramite il gate di hardening documentato nel replan**: sicurezza fail-closed, DST, cancellation, idempotenza e contratti store/audit vengono corretti prima del wiring Android. Dopo il gate, le interfacce tornano congelate per l'implementazione P0-B.
- **Ogni draft passa dal `DraftValidator` prima dell'arm**; `Severity.ERROR` ⇒ `canArm=false`. Invariante ri-verificato al fire-time sugli `allowed_tools` (difesa in profondità, spec §10.4).
- **⚠️ (da review P0-A Unit D) Passare SEMPRE la whitelist del manifest a `DraftValidator.validate(draft, whitelistedIds)`.** Il controllo `target_not_whitelisted` scatta *solo se* `whitelistedIds` è non vuota: chiamare `validate(draft)` senza whitelist lascia passare reply generative verso *qualsiasi* `conversationId` 1:1. In P0-B (T8 `ApprovalFlow`, T9 ViewModel) la whitelist reale va sempre threaded; valutare fail-closed su whitelist vuota per le reply generative. Opz. hardening: normalizzare il case dei tool forbidden e vietare il prefisso `automation` nudo (oggi dot-exact/case-sensitive, coperto dall'allowlist deny-by-default ma non stand-alone).
- **Decode Room fallito o `schemaVersion` incompatibile → `NEEDS_REVIEW`**, mai drop silenzioso (spec E8).
- **Privacy:** i contenuti (chat, notifiche) escono verso Hermes→upstream; il consenso onboarding (E11) è prerequisito all'uso del Brain.
- Bridge Hermes: binda **solo sull'interfaccia Tailscale**; il protocollo porta `schema_version` (spec §2 rev 3).
- **⚠️ (da review finale) `privacyNote` → `UiWarning`.** Quando un ViewModel costruisce `AutomationDetailState.warnings` / `DraftCard.issues`, DEVE riportare `RuleRender.privacyNote` (popolato da `RuleRenderMapper` per le regole generative) come `UiWarning` di privacy — altrimenti la disclosure cloud E11 sparisce silenziosamente dallo schermo di approvazione (il campo del mapper non è reso da alcun componente `ui`).
- **⚠️ (da review finale) Log send-now id.** `ExecutionLogCallbacks.onSendNow(logId)` riceve l'id di una `LogRow`, **non** di un'automazione; la consegna E13 di P0-B deve risolvere la reply differita dalla entry di log, non navigare (`onOpenAutomation` resta per il suo scopo reale).
- **⚠️ (da review finale) Engine cancellation.** Quando P0-B tocca `Engine.onTrigger`, **rilanciare `CancellationException`** prima del `catch (e: Exception)`: così com'è inghiottirebbe la cancellazione cooperativa nel foreground service.
- **⚠️ (da review finale) §10.4 residuo.** Quando il catalogo tool al fire-time cresce in P1, ogni tool del catalogo *always-confirm* deve entrare in `FORBIDDEN_IN_INVOKE_LLM` (oggi solo `shell.run`/`app.install`/`automation.*`).

## File structure

| Modulo / File | Responsabilità |
|---|---|
| `core-shizuku/` | `ShizukuGateway` (bind, permesso, `PrivilegedShell.run`), coda single-writer con priorità |
| `device-tools/` | `DeviceTools` (capture/tap/type/dumpUi, toggle wifi/bt/dnd, state read, app install/launch) su `PrivilegedShell` |
| `data/` | Room: `AutomationEntity`, `AuditEntity`, DAO, `RoomAutomationStore`, `RoomAuditSink`, converters via `ArgusJson` |
| `brain-android/` | `CliBridgeTransport` (OkHttp → `argus-bridge` HTTPS v1), `HermesBrain : Brain` |
| `automation-android/` | `ShizukuActionExecutor : ActionExecutor`, `LazyDeviceStateProvider`, `AlarmManagerTimeTrigger`, `AndroidCapabilityProbe`, `ApprovalFlow`, ViewModel (Chat/List/Detail/Log/Settings/Onboarding), `ArgusForegroundService` (minimo), Hilt modules |
| **Prereq lato Hermes** | servizio dedicato `argus-bridge` con `/compile` strict v1, bearer e idempotenza |

**Dipendenze task:** T0 (Hermes bridge) parallelo a T1. T1→T2 (shizuku→device-tools). T3 (Room) indipendente. T4 (brain) dipende T0. T5 (executor) dipende T2. T6 (time trigger) dipende T3. T7 (probe) dipende T2. T8 (ApprovalFlow) dipende T3+T4. T9 (ViewModel+wiring) dipende tutti. T10 (service) dipende T6. T11 (E2E su device) per ultimo.

---

## Task 0: 🔬 Generalizzare il bridge Hermes con endpoint `/compile`

**Files (su `hermes`, via ssh):**
- Deploy: `~/argus-bridge/bridge.py` + `argus-bridge.service` (sorgenti in `ops/hermes/`)

**Interfaces:**
- Produces: `POST /compile` → envelope v1 con `request_id`, `reply`, `meta.draft/error_code`; `GET /health` v1 autenticato.

Contesto verificato (spec §2/§16): il bridge Guida Bali esponeva solo `/chat`; per Argus è stato creato un servizio separato che riceve messaggio, manifest strutturato e stato redatto e produce un `AutomationDraft` nello schema di `engine-core`.

- [x] **Step 1:** bridge Guida Bali riletto e rischi reali verificati (auth non inviata dal vecchio client, bind `0.0.0.0`, nessun `/compile`).
- [x] **Step 2:** creato servizio separato `argus-bridge`: `/compile` strict v1, manifest strutturato, stato redatto, bearer, idempotenza e limiti. Il bridge Guida Bali resta invariato.
- [x] **Step 3:** test HTTP automatici + compile live: draft Time/DND decodificabile e seconda request con stesso ID servita dalla cache.
- [x] **Step 4:** contratto documentato in `docs/design/hermes-bridge-contract.md`; server e unit systemd versionati sotto `ops/hermes/` e deployati sulla VM.

> Il fallback `/chat` è stato rimosso: dati eseguibili accettati solo dal contratto `/compile` versionato.

---

## Task 1: `core-shizuku` — gateway privilegiato

**Files:**
- Create: `core-shizuku/build.gradle.kts` (android library; dep `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`)
- Create: `core-shizuku/src/main/AndroidManifest.xml` (ShizukuProvider)
- Create: `core-shizuku/src/main/kotlin/dev/argus/shizuku/ShizukuGateway.kt`
- Create: `core-shizuku/src/main/kotlin/dev/argus/shizuku/PrivilegedShell.kt`
- Test: `core-shizuku/src/androidTest/.../ShizukuGatewayInstrumentedTest.kt` (device reale)

**Interfaces:**
- Produces: `interface PrivilegedShell { suspend fun run(cmd: List<String>, priority: Int = 0): ShellResult }` con `data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)`; `ShizukuGateway.status(): ShizukuStatus` (riusa l'enum di engine-core/ui); `suspend fun requestPermission()`.

- [ ] **Step 1:** 🔬 Verifica l'API Shizuku disponibile: il pattern moderno è **user-service** (AIDL) o `Shizuku.newProcess(cmd, env, dir)` via l'API pubblica. Conferma sul device quale funziona con Shizuku 13.x (`Shizuku.newProcess` è `@hide` ma esposto da `rikka.shizuku:api`; in alternativa un `IUserService` bindato). Scegli e annota.
- [ ] **Step 2:** `ShizukuGateway`: gestione lifecycle (`Shizuku.addBinderReceivedListener`, `pingBinder`, `checkSelfPermission`, `requestPermission`), mappatura stato → `ShizukuStatus` (NOT_INSTALLED via PackageManager, INSTALLED_NOT_RUNNING, RUNNING_NOT_AUTHORIZED, AUTHORIZED). `DEGRADED_AFTER_REBOOT` gestito a livello app (B1) quando il binder cade.
- [ ] **Step 3:** `PrivilegedShell` impl: esegue il comando con UID shell, legge stdout/stderr/exit. **Coda single-writer con priorità** (spec C3): un `Channel`/`Mutex` serializza le run; le richieste con `priority` più alta passano avanti (usa una priority queue protetta da Mutex). Timeout per-comando.
- [ ] **Step 4:** 🔬 Instrumented test sul device: `run(listOf("echo","argus"))` → stdout "argus", exit 0; `run(listOf("id"))` → contiene `uid=2000(shell)`. `./gradlew :core-shizuku:connectedDebugAndroidTest` (device connesso).
- [ ] **Step 5:** Commit `feat(shizuku): privileged shell gateway with prioritized single-writer queue`.

---

## Task 2: `device-tools` — capacità tipizzate su Shizuku

**Files:**
- Create: `device-tools/build.gradle.kts`
- Create: `device-tools/src/main/kotlin/dev/argus/device/DeviceTools.kt`
- Create: `device-tools/src/main/kotlin/dev/argus/device/StateReader.kt`
- Test: `device-tools/src/androidTest/.../DeviceToolsInstrumentedTest.kt`

**Interfaces:**
- Consumes: `PrivilegedShell`.
- Produces: `class DeviceTools(shell: PrivilegedShell)` con `suspend fun setWifi(on)`, `setBluetooth(on)`, `setDnd(mode)`, `setRinger(mode)`, `launchApp(pkg)`, `openUrl(url)`, `tap(x,y)`, `inputText(t)`, `capture(): ByteArray`, `dumpUi(): String`, `installApk(path)`; `class StateReader(shell)` con `suspend fun read(keys: Set<String>): DeviceState` (solo le chiavi richieste — lazy).

- [ ] **Step 1:** Mappa ogni op sul comando shell (parità adb, tutti noti dal debug Android):
  - `setWifi(on)` → `svc wifi enable|disable`; `setBluetooth` → `svc bluetooth enable|disable` (o `cmd bluetooth_manager enable`); `setDnd(mode)` → `cmd notification set_dnd priority|none|off` (🔬 confermare stringhe su OxygenOS); `launchApp` → `monkey -p <pkg> 1` o `am start`; `tap` → `input tap x y`; `inputText` → `input text <esc>`; `capture` → `screencap -p` (stdout binario); `dumpUi` → `uiautomator dump /dev/tty` o file poi `cat`; `installApk` → `pm install -r <path>`.
  - `StateReader.read` mappa le chiavi `StateKeys`: `ringer`→`cmd audio`/`settings get`; `wifi`→`settings get global wifi_on`; `battery`→`dumpsys battery`; `charging`→idem; `foregroundApp`→`dumpsys activity activities | grep mResumed` (🔬). Solo le chiavi in `keys`.
- [ ] **Step 2:** Test instrumented mirati (device): `setDnd(TOTAL)` poi `read({"dnd"})` == total; `capture()` ritorna PNG non vuoto (magic bytes `\x89PNG`); `dumpUi()` contiene `<hierarchy`.
- [ ] **Step 3:** Commit `feat(device-tools): typed Shizuku capabilities + lazy StateReader`.

---

## Task 3: `data` — Room store + audit

**Files:**
- Create: `data/build.gradle.kts` (Room + KSP)
- Create: `data/src/main/kotlin/dev/argus/data/entities/*.kt`, `dao/*.kt`, `ArgusDatabase.kt`, `RoomAutomationStore.kt`, `RoomAuditSink.kt`, `Converters.kt`
- Test: `data/src/test/.../RoomStoreTest.kt` (Robolectric o in-memory), `data/src/androidTest/.../MigrationTest.kt`

**Interfaces:**
- Consumes: `engine-core` (`Automation`, `AutomationStore`, `AuditSink`, `AuditEvent`, `ArgusJson`).
- Produces: `RoomAutomationStore : AutomationStore`, `RoomAuditSink : AuditSink`, `ArgusDatabase`.

- [ ] **Step 1:** `AutomationEntity(id, name, status, enabled, priority, cooldownMs, schemaVersion, json)` — `json` = `Automation` serializzato via `ArgusJson` (colonna unica per lo schema polimorfico; le colonne piatte servono a query/ordinamento). `AuditEntity(id auto, automationId, kind, atMillis, detail)`.
- [ ] **Step 2:** `Converters` per enum; `RoomAutomationStore` mappa entity↔dominio. **Decode fallito → `NEEDS_REVIEW`** (E8): in `get()/armed()`, `runCatching { ArgusJson.decode… }` e su fallimento ritorna l'automazione in stato NEEDS_REVIEW con un placeholder, mai eccezione.
- [ ] **Step 3:** Test (in-memory/Robolectric): round-trip di ogni tipo di `Automation`; `armed()` filtra ARMED+enabled; `recordFired/lastFiredAt`; un blob JSON corrotto → NEEDS_REVIEW.
- [ ] **Step 4:** Test migrazione schema (androidTest) v1→v1 no-op + scaffolding per future migrazioni.
- [ ] **Step 5:** Commit `feat(data): Room automation store + audit sink (JSON column, decode-fail→needs_review)`.

---

## Task 4: `brain-android` — `CliBridgeTransport` + `HermesBrain`

**Files:**
- Create: `brain-android/build.gradle.kts` (OkHttp)
- Create: `brain-android/src/main/kotlin/dev/argus/brain/CliBridgeTransport.kt`
- Create: `brain-android/src/main/kotlin/dev/argus/brain/HermesBrain.kt`
- Test: `brain-android/src/test/.../HermesBrainTest.kt` (MockWebServer)

**Interfaces:**
- Consumes: `engine-core` (`Brain`, `CompileResult`, `CliBridgeParser`, `CapabilityManifest`, `DeviceState`).
- Produces: `class HermesBrain(transport) : Brain` (`compile()` implementato; `act()`/`chat()` → `TODO(P1/P3)`).

- [x] **Step 1:** contract test MockWebServer per happy path, no-draft, schema/request ID/type strict, timeout, auth, body cap e cancellation.
- [x] **Step 2:** transport unico HTTPS `/compile`; bearer runtime, request id idempotente, manifest strutturato e `DeviceState` redatto. Il fallback `/chat` è stato rimosso fail-closed.
- [x] **Step 3:** `HermesBrain.compile` inoltra realmente manifest+state e mappa gli errori a codici stabili senza dettagli remoti.
- [x] **Step 4:** unit test JVM e instrumented test live su OnePlus/API 36 completati.

---

## Task 5: `ShizukuActionExecutor` (implementa `ActionExecutor`)

**Files:**
- Create: `automation-android/src/main/kotlin/dev/argus/automation/ShizukuActionExecutor.kt`
- Test: `automation-android/src/test/.../ShizukuActionExecutorTest.kt` (DeviceTools fake)

**Interfaces:**
- Consumes: `engine-core` (`ActionExecutor`, `Action`, `ActionResult`, `FireContext`), `DeviceTools`.
- Produces: `class ShizukuActionExecutor(tools, generativeLane) : ActionExecutor`.

- [ ] **Step 1:** Test con `DeviceTools` fake: `execute(SetWifi(false))` → chiama `tools.setWifi(false)`, ritorna `Success`; `execute(SetDnd(PRIORITY))` → `tools.setDnd`; `execute(InvokeLlm(...))` → **accoda nella `generativeLane` e ritorna `Submitted` senza bloccare** (spec §6/C3); `execute(RunShell(cmd))` non approvato → `Failure` (gate). Un'op che lancia → `Failure(reason)` (non propaga l'eccezione oltre l'engine, che ha già isolamento per-automazione).
- [ ] **Step 2:** Implementa il `when(action)` esaustivo mappando su `DeviceTools`. Le deterministiche sono **sincrone**. `InvokeLlm` → `generativeLane.submit(ctx, action)` + `Submitted` (la lane vera è P1; in P0-B la lane è uno stub che logga e ritorna, dato che le regole generative arrivano in P1). `WhatsAppReply`/`Tap`/`InputText` → in P0-B possono lanciare `Failure("P1")` (non nel percorso Es. 2).
- [ ] **Step 3:** Commit `feat(automation): ShizukuActionExecutor (deterministic sync, generative→Submitted)`.

---

## Task 6: `AlarmManagerTimeTrigger` + boot recovery

**Files:**
- Create: `automation-android/src/main/kotlin/dev/argus/automation/AlarmManagerTimeTrigger.kt`
- Create: `automation-android/src/main/kotlin/dev/argus/automation/BootReceiver.kt`
- Test: `automation-android/src/test/.../TimeTriggerSchedulingTest.kt` (usa `TimeSpecs`, clock fisso)

**Interfaces:**
- Consumes: `engine-core` (`TimeSpecs`, `Trigger.Time`, `TriggerEvent.TimeFired`, `Engine`), `AlarmManager`.
- Produces: `class AlarmManagerTimeTrigger(ctx, store, engine)` con `suspend fun rescheduleAll()`, `fun onAlarm(automationId)`.

- [ ] **Step 1:** Test JVM: data una `Automation` con `Trigger.Time(cron="0 23 * * *")`, `nextFireAt(now)` usa `TimeSpecs.nextFire` e produce l'istante atteso (riusa i casi di `CronScheduleTest`). La registrazione AlarmManager è mockata (verifica che `setExactAndAllowWhileIdle` sia chiamato con l'epoch giusto).
- [ ] **Step 2:** Impl: per ogni automazione armata con trigger Time, calcola next-fire e registra un exact alarm (`PendingIntent` con `automationId`). `onAlarm` → `engine.onTrigger(TimeFired(id)) { lazyState }` → ri-registra il prossimo. `BootReceiver` (`BOOT_COMPLETED`) → `rescheduleAll()` (richiede Shizuku up, B1/E9).
- [ ] **Step 3:** Manifest: `RECEIVE_BOOT_COMPLETED`, `USE_EXACT_ALARM` (sideload accettabile, spec B3), receiver.
- [ ] **Step 4:** Commit `feat(automation): AlarmManager exact Time trigger (TimeSpecs-driven) + boot recovery`.

---

## Task 7: `AndroidCapabilityProbe`

**Files:**
- Create: `automation-android/src/main/kotlin/dev/argus/automation/AndroidCapabilityProbe.kt`
- Test: `automation-android/src/test/.../CapabilityProbeTest.kt` (dipendenze fake)

**Interfaces:**
- Consumes: `engine-core` (`CapabilityProbe`, `CapabilityManifest`, `WhitelistedContact`, `StateKeys`), `ShizukuGateway`, whitelist store.
- Produces: `class AndroidCapabilityProbe(...) : CapabilityProbe`.

- [ ] **Step 1:** Test: date fake (Shizuku authorized, permessi X, whitelist `[{Moglie, jid:42}]`) → `probe()` ritorna manifest con `deviceModel`/`androidVersion` (Build), `shizukuAvailable=true`, tool disponibili/indisponibili coerenti (es. `vision.analyze` unavailable se nessun provider), `stateKeys=StateKeys.ALL`, contatti whitelist `{nome,id}`.
- [ ] **Step 2:** Impl: legge `Build.MODEL`/`Build.VERSION.SDK_INT`, stato Shizuku, permessi concessi (notification listener, background location…), whitelist dal `data` store; deriva `availableTools`/`unavailableTools` in base a capability presenti.
- [ ] **Step 3:** Commit `feat(automation): AndroidCapabilityProbe (manifest with contacts+state keys)`.

---

## Task 8: `ApprovalFlow` (parser → validator → resolve → conflict → arm)

**Files:**
- Create: `automation-android/src/main/kotlin/dev/argus/automation/ApprovalFlow.kt`
- Test: `automation-android/src/test/.../ApprovalFlowTest.kt`

**Interfaces:**
- Consumes: `engine-core` (`CliBridgeParser`, `DraftValidator`, `ConflictDetector`, `AutomationStore`, `Trigger.Geofence`), `LocationProvider`.
- Produces: `class ApprovalFlow(...)` con `suspend fun toDraftReview(compile: CompileResult): DraftReview`, `suspend fun arm(draft, resolvedLocation?): ArmResult`.

Questo è il ponte tra il `compile` del Brain e lo stato UI `AutomationDetailState` (canArm, warnings) e l'atto di arm.

- [ ] **Step 1:** Test: un draft valido → `DraftReview(canArm=true, warnings=[])`; un draft generativo con `shell.run` → `canArm=false`, warning ERROR `tool_forbidden`; un geofence `resolveCurrentLocation=true` → all'`arm` risolve la posizione corrente (LocationProvider fake) e salva coordinate concrete con `resolveCurrentLocation=false`; conflitti da `ConflictDetector` inclusi nei warnings.
- [ ] **Step 2:** Impl: `toDraftReview` = `DraftValidator.validate(draft, whitelist)` → mappa `ValidationIssue`+`ConflictWarning` in `UiWarning`, `canArm = nessun ERROR`. `arm` = risolve `resolveCurrentLocation` (legge GPS all'arm, spec §7), assegna `AutomationId`+`ARMED`, `store.save`, registra il trigger (Time via T6). ri-valida al fire-time è responsabilità dell'executor/engine.
- [ ] **Step 3:** Commit `feat(automation): ApprovalFlow (validate→resolve location→conflict→arm)`.

---

## Task 9: ViewModel + Hilt wiring (schermi M2 → dati reali)

**Files:**
- Create: `automation-android/src/main/kotlin/dev/argus/automation/vm/*ViewModel.kt` (Chat, List, Detail, Log, Settings, Onboarding)
- Create: `automation-android/src/main/kotlin/dev/argus/automation/di/*Module.kt` (Hilt)
- Modify: `app/` — sostituisci le fixture con i ViewModel reali (mantieni le fixture per i `@Preview`)
- Test: `automation-android/src/test/.../*ViewModelTest.kt` (coroutines-test, fake Brain/Store)

**Interfaces:**
- Consumes: tutti i precedenti + i contratti `ui` (`ChatState`, `AutomationDetailState`, …), `RuleRenderMapper`.
- Produces: un `ViewModel` per schermo che espone `StateFlow<XxxState>` e implementa `XxxCallbacks`.

- [ ] **Step 1:** `ChatViewModel`: `onSend` → `HermesBrain.compile` (stato `sending` con cronometro) → `ApprovalFlow.toDraftReview` → append `DraftCard`; `onOpenDraft` → naviga a Detail. Test: invio → sending true → dopo risposta, DraftCard con `RuleRender` da `RuleRenderMapper`; brain down → `brainReachable=false`.
- [ ] **Step 2:** `DetailViewModel`: costruisce `AutomationDetailState` (rule via `RuleRenderMapper`, warnings, canArm) da store/ApprovalFlow; `onArm`→`ApprovalFlow.arm`; `onReject`/`onDelete`/`onRunNow`. `ListViewModel`, `LogViewModel` (da `RoomAuditSink`), `SettingsViewModel` (transport/shizuku/whitelist/budget), `OnboardingViewModel` (stati permessi reali).
- [ ] **Step 3:** Hilt modules: bind `Brain`=`HermesBrain`, `ActionExecutor`=`ShizukuActionExecutor`, `AutomationStore`=`RoomAutomationStore`, `AuditSink`=`RoomAuditSink`, `CapabilityProbe`=`AndroidCapabilityProbe`, `Engine`, `PrivilegedShell`. `@HiltAndroidApp` in app.
- [ ] **Step 4:** `app`: NavHost monta i ViewModel (`hiltViewModel()`), le fixture restano solo nei preview.
- [ ] **Step 5:** Commit `feat(app): real ViewModels + Hilt wiring (screens on live engine/brain/store)`.

---

## Task 10: `ArgusForegroundService` (minimo) + notifica persistente

**Files:**
- Create: `automation-android/src/main/kotlin/dev/argus/automation/ArgusForegroundService.kt`
- Modify: `app/AndroidManifest.xml` (service `foregroundServiceType`, permessi FGS + notifiche)

**Interfaces:**
- Produces: service che ospita AlarmManager registration + engine dispatch; notifica persistente bassa priorità ("Argus attivo — N regole armate" / degradato "Shizuku non attivo").

- [ ] **Step 1:** Service `specialUse`/`dataSync` (spec §9) con notifica low-priority; all'avvio `rescheduleAll()` dei Time trigger; `START_STICKY`. In P0-B **non** avvia da background senza esenzione (B2) — parte dall'app. L'anti-Doze/watchdog completo è **P2**.
- [ ] **Step 2:** La notifica riflette `ShizukuStatus` (degradato → testo "azioni shell in pausa", tap → Sistema).
- [ ] **Step 3:** Commit `feat(automation): minimal foreground service + persistent status notification`.

---

## Task 11: 🔬 Verifica end-to-end su device (Esempio 2)

**Files:** nessun nuovo file (o `automation-android/src/androidTest/.../Example2E2ETest.kt`).

- [ ] **Step 1:** Build+install: `./gradlew :app:installDebug` sul device (`adb -s <oneplus>`). Concedi Shizuku + notifiche + batteria via onboarding.
- [ ] **Step 2:** In chat: "dopo le 23 controlla la suoneria e metti DND". Verifica: appare la `DraftCard`, il Dettaglio mostra `RuleRender` corretto (trigger 23:00, condizione ringer≠silent, azione SetDnd), `canArm=true`. Arma.
- [ ] **Step 3:** Sposta l'orologio o abbassa il cron a `now+2min` (fixture di test), suoneria non-silent → all'orario l'engine esegue `SetDnd(PRIORITY)` via Shizuku → verifica `cmd notification get_dnd` == priority. Log mostra `FIRED · 1/1 azioni ok`. Con suoneria silent → `CONDITIONS_NOT_MET` nel log.
- [ ] **Step 4:** 🔬 Esempio 1 (parziale): "geofence sulla posizione attuale, uscendo wifi off / bt on" → compile+validator+arm ok (warning raggio<100m), coordinate risolte all'arm. Lo **scatto reale** del geofence è P2 (registrar Play Services): qui verifica solo fino all'arm.
- [ ] **Step 5:** Commit `test(e2e): Example 2 DND-after-23 works end-to-end on device`.

---

## Definition of Done (P0-B)

- [ ] Esempio 2 funziona **end-to-end sul device**: chat → compile (Hermes) → approvazione → arm → AlarmManager → esecuzione DND via Shizuku → log.
- [ ] Esempio 1 pronto fino all'arm (registrar geofence = P2).
- [ ] Tutte le interfacce di `engine-core` implementate su Android; `engine-core` invariato.
- [ ] Room: round-trip completo, decode-fail → NEEDS_REVIEW.
- [ ] `DraftValidator` nel percorso di arm; ERROR blocca l'arm in UI.
- [ ] Shizuku gateway con coda prioritaria; instrumented test verdi sul device.
- [x] Bridge Hermes `/compile` HTTPS, autenticato, documentato e verificato live.

## Handoff verso P1 / P2

- **P1:** trigger Notification (NotificationListenerService, 🔬 estrazione `conversationId`/`isGroup`/`notificationKey` — spec §16), WhatsApp RemoteInput, lane generativa reale (`InvokeLlm` async + fallback E13), whitelist contatti UI, **battery-optimization exemption** (anticipata qui, spec §15). → Esempio 3.
- **P2:** geofence (Play Services) → Esempio 1 end-to-end; PhoneState/Connectivity; foreground-service hardening + watchdog + anti-Doze; wizard OEM/background-location; resilienza Shizuku post-reboot.
- **P3:** loop `computer_use` interattivo (`OpenAICompatTransport`), `DirectLlmBrain` OAuth, streaming chat, budget guard UI.

## Self-review (fatta)

- **Spec coverage:** §5-7 schema/brain→T4/T8; §8 Shizuku→T1/T2; §9 Time/boot→T6/T10; §10 sicurezza/validator→T8; E8 needs_review→T3; §15 P0-B scope→tutti; §16 rischi (bridge, conversationId)→T0 (🔬), P1 handoff. ✓
- **Placeholder:** i punti non determinabili a secco sono marcati 🔬 VERIFICA con l'azione di sondaggio, non lasciati vaghi; il codice determinabile (Room, executor mapping, transport) è specificato. ✓ (Piano eseguito con device → rifinitura in-sessione attesa e legittima.)
- **Type-consistency:** implementa le interfacce esatte di engine-core (`ActionExecutor.execute→ActionResult` con `Submitted`, `AutomationStore`, `AuditSink`, `CapabilityProbe`, `Brain.compile`); `PrivilegedShell.run`/`ShellResult`/`DeviceTools` coerenti T1↔T2↔T5. ✓
