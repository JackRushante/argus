# Argus — Piano P0-A: Engine Core (Kotlin puro, device-independent) — rev 2

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Changelog rev 2 (post analisi critica):** semantica `priority` corretta (crescente = il più prioritario esegue ultimo, last-writer-wins); isolamento errori per-automazione; stato device **lazy** (`stateProvider`); cooldown minimo 60 s per regole generative; `ActionResult.Submitted` per il contratto lane-async; **nuovi task** `CronSchedule`/`TimeSpecs` (DST) e `DraftValidator` (validazione dominio + invarianti sicurezza); `AuditSink` + interfaccia `Brain`; `TriggerEvent.NotificationPosted` con `conversationId`/`isGroup`/`notificationKey`; `Connectivity` con direzione; match numeri normalizzato; `ConflictDetector` trigger-aware (sopprime coppie complementari); parser JSON bilanciato; `StateKeys` registry; manifest con contatti `{nome, id}`; azioni `ShowNotification` e `Time.at`.

**Goal:** Costruire l'intero motore di automazione di Argus come libreria **Kotlin/JVM pura** (nessuna dipendenza Android), con Shizuku/Room/AlarmManager dietro interfacce iniettate, testata al 100% con unit test veloci.

**Architecture:** Modulo Gradle `engine-core` (`kotlin("jvm")`). Modelli dominio serializzabili (kotlinx.serialization, polimorfismo sealed). Il motore riceve `TriggerEvent` + un **provider lazy** di `DeviceState`, valuta condizioni contro un `Clock` iniettato, e dispatcha `Action` a un `ActionExecutor` iniettato (fake nei test; implementazione Shizuku in P0-B). Persistenza dietro `AutomationStore`, audit dietro `AuditSink` (fake in-memory nei test; Room in P0-B). Il Brain (transport CliBridge) è rappresentato da un parser puro dell'output testo+JSON. Il calcolo del prossimo scatto Time (cron/at, DST-safe) vive qui (`CronSchedule`/`TimeSpecs`), P0-B lo collega ad AlarmManager.

**Tech Stack:** Kotlin 2.x, kotlinx.serialization-json, kotlinx-coroutines, JUnit5, kotlin-test, java.time. Nessun SDK Android in questo modulo.

**Scope:** Solo P0-A. Le implementazioni Android (`core-shizuku`, `device-tools`, Room, Time-trigger via AlarmManager, wiring) sono il piano **P0-B**. Riferimento spec: `spec-argus-design-rev3.md` (nel bundle), §5-§7, §10, §13.

**Package:** `dev.argus.engine`. Ogni file sotto `engine-core/src/main/kotlin/dev/argus/engine/`, test sotto `engine-core/src/test/kotlin/dev/argus/engine/`.

---

## File structure (decomposizione bloccata qui)

| File | Responsabilità |
|------|----------------|
| `model/Trigger.kt` | sealed `Trigger` + enum ausiliari (`Transition`, `PhoneEvent`, `ConnMedium`, `ConnState`) |
| `model/Condition.kt` | sealed `Condition` (`TimeWindow`, `StateEquals`, `AppInForeground`, `LocationIn`, `And`, `Or`, `Not`) + `CmpOp` |
| `model/Action.kt` | sealed `Action` (deterministiche incl. `ShowNotification` + `InvokeLlm`) + `DndMode`, `tier` |
| `model/Automation.kt` | `Automation`, `AutomationDraft`, `AutomationStatus`, `CreatedBy`, `AutomationId` |
| `model/StateKeys.kt` | registry chiavi/valori `DeviceState` (vocabolario chiuso per `StateEquals`, §5 rev 3) |
| `model/Json.kt` | istanza `Json` condivisa con moduli polimorfici sealed |
| `runtime/DeviceState.kt` | `DeviceState`, `GeoPoint` |
| `runtime/TriggerEvent.kt` | sealed `TriggerEvent` (incl. `conversationId`/`isGroup`/`notificationKey` per Notification) |
| `runtime/ConditionEvaluator.kt` | valuta `Condition` contro `DeviceState` + `Clock` |
| `runtime/TriggerMatcher.kt` | verifica se un `TriggerEvent` soddisfa uno spec `Trigger` (conversationId > sender; numeri normalizzati) |
| `runtime/ActionExecutor.kt` | interfaccia `ActionExecutor`, `FireContext`, `ActionResult` (incl. `Submitted`) |
| `runtime/AutomationStore.kt` | interfaccia `AutomationStore` (persistenza + cooldown) |
| `runtime/AuditSink.kt` | interfaccia `AuditSink` + `AuditEvent`/`AuditKind` + `NoopAuditSink` |
| `runtime/CronSchedule.kt` | parser cron 5 campi + next-fire DST-safe + `TimeSpecs` (cron/at) |
| `runtime/Engine.kt` | orchestrazione: match → cooldown (min generativo) → eval (stato lazy) → dispatch (priorità crescente, isolamento errori, audit) |
| `safety/ConflictDetector.kt` | euristica conflitti trigger-aware (§13/C1) |
| `safety/DraftValidator.kt` | validazione dominio draft + invarianti sicurezza (§10/D7) |
| `brain/Brain.kt` | interfaccia `Brain` + `CompileResult` |
| `brain/CliBridgeParser.kt` | parsing output Hermes (prosa + `@@META@@ {json}`, estrazione bilanciata) → `AutomationDraft` |
| `brain/CapabilityManifest.kt` | `CapabilityManifest` (contatti `{nome,id}`, StateKeys), `CapabilityProbe`, rendering per il prompt |

**Dipendenze tra task:** T0 → T1 → T2 → T3 → T4 → poi **T5, T6, T7 parallelizzabili** → T8 (engine, richiede T5/T6). **T9, T10, T12 parallelizzabili dopo T4**; **T11 richiede T7** (valida i cron). T13 (E2E) per ultimo.

---

## Task 0: Scaffold modulo `engine-core`

**Files:**
- Create: `settings.gradle.kts`
- Create: `engine-core/build.gradle.kts`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/SmokeTest.kt`

- [ ] **Step 1: Scrivi `settings.gradle.kts`**

```kotlin
rootProject.name = "argus"
include("engine-core")
```

- [ ] **Step 2: Scrivi `engine-core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test { useJUnitPlatform() }
kotlin { jvmToolchain(17) }
```

- [ ] **Step 3: Scrivi il test smoke**

```kotlin
package dev.argus.engine
import kotlin.test.Test
import kotlin.test.assertEquals
class SmokeTest {
    @Test fun `toolchain works`() { assertEquals(4, 2 + 2) }
}
```

- [ ] **Step 4: Esegui — deve passare**

Run: `./gradlew :engine-core:test`
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts engine-core/
git commit -m "chore(engine): scaffold pure-JVM engine-core module"
```

---

## Task 1: Modelli `Trigger`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Trigger.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Json.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/model/TriggerSerializationTest.kt`

- [ ] **Step 1: Scrivi il test di round-trip**

```kotlin
package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
class TriggerSerializationTest {
    @Test fun `time trigger round-trips`() {
        val t: Trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome")
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `one-shot time trigger uses at`() {
        val t: Trigger = Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome")
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `notification trigger keeps discriminator and identity fields`() {
        val t: Trigger = Trigger.Notification(pkg = "com.whatsapp", conversationId = "id:42", isGroup = false)
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assert(json.contains("\"type\":\"notification\"")) { json }
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), json))
    }
    @Test fun `geofence with placeholder round-trips`() {
        val t: Trigger = Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true)
        assertEquals(t, ArgusJson.decodeFromString(Trigger.serializer(), ArgusJson.encodeToString(Trigger.serializer(), t)))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire (tipi inesistenti)**

Run: `./gradlew :engine-core:test --tests '*TriggerSerializationTest'`
Expected: FAIL, unresolved reference `Trigger` / `ArgusJson`.

- [ ] **Step 3: Scrivi `Json.kt`**

```kotlin
package dev.argus.engine.model
import kotlinx.serialization.json.Json
val ArgusJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}
```

- [ ] **Step 4: Scrivi `Trigger.kt`**

```kotlin
package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Transition { ENTER, EXIT, DWELL }
enum class PhoneEvent { INCOMING_CALL, CALL_ENDED, SMS_RECEIVED }
enum class ConnMedium { WIFI, BT, POWER }
/** Per POWER: CONNECTED = alimentazione collegata. */
enum class ConnState { CONNECTED, DISCONNECTED }

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("geofence")
    data class Geofence(
        val lat: Double = 0.0, val lng: Double = 0.0, val radiusM: Double,
        val transition: Transition, val loiteringDelayMs: Long = 0,
        /** true = coordinate risolte dall'app all'ARM con la posizione corrente (spec §7 rev 3). */
        val resolveCurrentLocation: Boolean = false,
    ) : Trigger

    /** Esattamente uno tra [cron] e [at] (enforced dal DraftValidator).
     *  [at] = datetime ISO locale ("2026-07-15T08:00") interpretato in [tz], one-shot. */
    @Serializable @SerialName("time")
    data class Time(val cron: String? = null, val at: String? = null, val tz: String) : Trigger

    @Serializable @SerialName("notification")
    data class Notification(
        val pkg: String,
        /** Chiave stabile della conversazione (shortcutId/JID, spec E15) — preferita per l'identità. */
        val conversationId: String? = null,
        /** Display name: fallback SPOOFABILE, il validator lo marca WARNING. */
        val sender: String? = null,
        /** null = qualsiasi; false obbligatorio per le reply generative (spec §10.3). */
        val isGroup: Boolean? = null,
        val titleMatch: String? = null, val textMatch: String? = null,
    ) : Trigger

    @Serializable @SerialName("phone_state")
    data class PhoneState(val event: PhoneEvent, val number: String? = null) : Trigger

    @Serializable @SerialName("connectivity")
    data class Connectivity(val medium: ConnMedium, val state: ConnState, val match: String? = null) : Trigger
}
```

- [ ] **Step 5: Esegui — deve passare**

Run: `./gradlew :engine-core:test --tests '*TriggerSerializationTest'`
Expected: PASS (4 test).

- [ ] **Step 6: Commit**

```bash
git add engine-core/src/main/kotlin/dev/argus/engine/model engine-core/src/test
git commit -m "feat(engine): Trigger domain model + serialization (conversationId, at, direction)"
```

---

## Task 2: Modelli `Condition`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Condition.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/model/ConditionSerializationTest.kt`

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
class ConditionSerializationTest {
    @Test fun `nested and-or round-trips`() {
        val c: Condition = Condition.And(listOf(
            Condition.TimeWindow(startLocal = "23:00", endLocal = "07:00", tz = "Europe/Rome"),
            Condition.Or(listOf(
                Condition.StateEquals("ringer", CmpOp.NEQ, "silent"),
                Condition.Not(Condition.AppInForeground("com.whatsapp")),
            )),
        ))
        val json = ArgusJson.encodeToString(Condition.serializer(), c)
        assertEquals(c, ArgusJson.decodeFromString(Condition.serializer(), json))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*ConditionSerializationTest'`
Expected: FAIL, unresolved `Condition` / `CmpOp`.

- [ ] **Step 3: Scrivi `Condition.kt`**

```kotlin
package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class CmpOp { EQ, NEQ, GT, LT, CONTAINS }

@Serializable
sealed interface Condition {
    @Serializable @SerialName("time_window")
    data class TimeWindow(val startLocal: String, val endLocal: String, val tz: String) : Condition

    @Serializable @SerialName("state_equals")
    data class StateEquals(val key: String, val op: CmpOp, val value: String) : Condition

    @Serializable @SerialName("app_in_foreground")
    data class AppInForeground(val pkg: String) : Condition

    @Serializable @SerialName("location_in")
    data class LocationIn(val lat: Double, val lng: Double, val radiusM: Double) : Condition

    @Serializable @SerialName("and")
    data class And(val all: List<Condition>) : Condition

    @Serializable @SerialName("or")
    data class Or(val any: List<Condition>) : Condition

    @Serializable @SerialName("not")
    data class Not(val cond: Condition) : Condition
}
```

- [ ] **Step 4: Esegui — deve passare**

Run: `./gradlew :engine-core:test --tests '*ConditionSerializationTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine-core/src/main/kotlin/dev/argus/engine/model/Condition.kt engine-core/src/test
git commit -m "feat(engine): Condition domain model (AND/OR/NOT tree)"
```

---

## Task 3: Modelli `Action` + `Automation` + `StateKeys`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Action.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Automation.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/StateKeys.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/model/AutomationSerializationTest.kt`

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class AutomationSerializationTest {
    @Test fun `automation with generative action round-trips`() {
        val a = Automation(
            id = AutomationId("a1"), name = "reply moglie",
            createdBy = CreatedBy.LLM, status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Notification(pkg = "com.whatsapp", conversationId = "id:42", isGroup = false),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"),
            actions = listOf(Action.InvokeLlm(
                goal = "rispondi nel tono X", contextSources = listOf("notification"),
                allowedTools = listOf("whatsapp_reply"), replyTargetSender = true,
            )),
        )
        val json = ArgusJson.encodeToString(Automation.serializer(), a)
        assertEquals(a, ArgusJson.decodeFromString(Automation.serializer(), json))
    }
    @Test fun `tier classification`() {
        assertTrue(Action.SetWifi(true).tier == ActionTier.DETERMINISTIC)
        assertTrue(Action.ShowNotification("t", "x").tier == ActionTier.DETERMINISTIC)
        assertTrue(Action.InvokeLlm("g", listOf(), listOf(), true).tier == ActionTier.GENERATIVE)
    }
    @Test fun `state keys registry is closed and documented`() {
        assertTrue(StateKeys.RINGER in StateKeys.ALL)
        assertTrue(StateKeys.ALL.getValue(StateKeys.RINGER).contains("silent"))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*AutomationSerializationTest'`
Expected: FAIL, unresolved `Action` / `Automation` / `StateKeys`.

- [ ] **Step 3: Scrivi `Action.kt`**

```kotlin
package dev.argus.engine.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DndMode { OFF, PRIORITY, TOTAL }
enum class ActionTier { DETERMINISTIC, GENERATIVE }

@Serializable
sealed interface Action {
    val tier: ActionTier get() = if (this is InvokeLlm) ActionTier.GENERATIVE else ActionTier.DETERMINISTIC

    @Serializable @SerialName("set_wifi") data class SetWifi(val on: Boolean) : Action
    @Serializable @SerialName("set_bluetooth") data class SetBluetooth(val on: Boolean) : Action
    @Serializable @SerialName("set_dnd") data class SetDnd(val mode: DndMode) : Action
    @Serializable @SerialName("set_ringer") data class SetRinger(val mode: String) : Action
    @Serializable @SerialName("launch_app") data class LaunchApp(val pkg: String) : Action
    @Serializable @SerialName("open_url") data class OpenUrl(val url: String) : Action
    @Serializable @SerialName("show_notification") data class ShowNotification(val title: String, val text: String) : Action
    @Serializable @SerialName("tap") data class Tap(val x: Int, val y: Int) : Action
    @Serializable @SerialName("input_text") data class InputText(val text: String) : Action
    @Serializable @SerialName("whatsapp_reply") data class WhatsAppReply(val text: String) : Action
    @Serializable @SerialName("run_shell") data class RunShell(val cmd: String) : Action

    @Serializable @SerialName("invoke_llm")
    data class InvokeLlm(
        val goal: String,
        val contextSources: List<String>,
        val allowedTools: List<String>,   // MAI shell.run / automation.* (DraftValidator, spec §7)
        val replyTargetSender: Boolean,   // spec §10.4: destinatario vincolato al trigger.sender
        val timeoutMs: Long = 60_000,
    ) : Action
}
```

- [ ] **Step 4: Scrivi `Automation.kt` e `StateKeys.kt`**

```kotlin
// Automation.kt
package dev.argus.engine.model
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class AutomationId(val value: String)
enum class CreatedBy { LLM, USER }
enum class AutomationStatus { PENDING_APPROVAL, ARMED, DISABLED, NEEDS_REVIEW }

const val SCHEMA_VERSION = 1

@Serializable
data class Automation(
    val id: AutomationId,
    val name: String,
    val createdBy: CreatedBy,
    val status: AutomationStatus,
    val trigger: Trigger,
    val actions: List<Action>,
    val conditions: Condition? = null,
    val enabled: Boolean = true,
    /** Nello stesso batch: esecuzione in ordine CRESCENTE → il più prioritario scrive ultimo e vince (spec §5). */
    val priority: Int = 0,
    val cooldownMs: Long = 0,
    val schemaVersion: Int = SCHEMA_VERSION,
)

/** Bozza proposta dall'LLM: come Automation ma senza id/status (li assegna l'app all'approvazione). */
@Serializable
data class AutomationDraft(
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    val conditions: Condition? = null,
    val rationale: String = "",
    val cooldownMs: Long = 0,
)
```

```kotlin
// StateKeys.kt
package dev.argus.engine.model

/** Vocabolario CHIUSO delle chiavi di DeviceState (spec §5 rev 3): il compile non può inventare
 *  chiavi (sono nel manifest) e il DraftValidator rifiuta StateEquals su chiavi fuori registry. */
object StateKeys {
    const val RINGER = "ringer"; const val WIFI = "wifi"; const val BLUETOOTH = "bluetooth"
    const val DND = "dnd"; const val BATTERY = "battery"; const val CHARGING = "charging"
    const val AIRPLANE = "airplane"
    /** chiave -> valori ammessi (usato nel render del manifest e in doc) */
    val ALL: Map<String, String> = mapOf(
        RINGER to "normal|vibrate|silent", WIFI to "on|off", BLUETOOTH to "on|off",
        DND to "off|priority|total", BATTERY to "0-100", CHARGING to "true|false", AIRPLANE to "on|off",
    )
}
```

- [ ] **Step 5: Esegui — deve passare**

Run: `./gradlew :engine-core:test --tests '*AutomationSerializationTest'`
Expected: PASS (3 test).

- [ ] **Step 6: Commit**

```bash
git add engine-core/src/main/kotlin/dev/argus/engine/model engine-core/src/test
git commit -m "feat(engine): Action + Automation models, tier classification, StateKeys registry"
```

---

## Task 4: Runtime interfaces (`ActionExecutor`, `AutomationStore`, `AuditSink`) + fake

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/DeviceState.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/TriggerEvent.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/ActionExecutor.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/AutomationStore.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/AuditSink.kt`
- Create: `engine-core/src/test/kotlin/dev/argus/engine/runtime/Fakes.kt` (fake condivisi dai test del modulo)
- Test: `engine-core/src/test/kotlin/dev/argus/engine/runtime/FakesTest.kt`

- [ ] **Step 1: Scrivi il test dei fake**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
class FakesTest {
    @Test fun `fake executor records calls and submits generative`() = runTest {
        val ex = FakeActionExecutor()
        val ctx = FireContext(TriggerEvent.TimeFired(AutomationId("a1")), DeviceState(), AutomationId("a1"))
        assertEquals(ActionResult.Success, ex.execute(Action.SetWifi(false), ctx))
        assertEquals(ActionResult.Submitted, ex.execute(Action.InvokeLlm("g", listOf(), listOf("whatsapp_reply"), true), ctx))
        assertEquals(2, ex.executed.size)
    }
    @Test fun `fake store cooldown`() = runTest {
        val store = FakeAutomationStore()
        val id = AutomationId("a1")
        store.recordFired(id, 1_000)
        assertEquals(1_000, store.lastFiredAt(id))
    }
    @Test fun `fake audit records events`() = runTest {
        val sink = FakeAuditSink()
        sink.record(AuditEvent(AutomationId("a1"), AuditKind.FIRED, 42))
        assertEquals(AuditKind.FIRED, sink.events.single().kind)
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*FakesTest'`
Expected: FAIL (tipi mancanti).

- [ ] **Step 3: Scrivi `DeviceState.kt` e `TriggerEvent.kt`**

```kotlin
// DeviceState.kt
package dev.argus.engine.runtime
data class GeoPoint(val lat: Double, val lng: Double)
data class DeviceState(
    val values: Map<String, String> = emptyMap(),   // chiavi da StateKeys, es. "ringer" -> "normal"
    val foregroundApp: String? = null,
    val location: GeoPoint? = null,
)
```

```kotlin
// TriggerEvent.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Transition

sealed interface TriggerEvent {
    /** Trigger schedulati per-automazione: portano l'id. */
    data class TimeFired(val automationId: AutomationId) : TriggerEvent
    data class GeofenceTransitioned(val automationId: AutomationId, val transition: Transition) : TriggerEvent
    /** Trigger broadcast: nessun id, richiedono match sullo spec. */
    data class NotificationPosted(
        val pkg: String,
        val conversationId: String? = null,   // chiave stabile estratta dalla notifica (E15)
        val sender: String? = null,           // display name
        val title: String? = null,
        val text: String? = null,
        val isGroup: Boolean = false,
        /** Chiave della StatusBarNotification: l'executor la usa per recuperare il RemoteInput
         *  al momento della reply (P1). Senza, WhatsAppReply non è eseguibile. */
        val notificationKey: String? = null,
    ) : TriggerEvent
    data class PhoneStateChanged(val event: PhoneEvent, val number: String?) : TriggerEvent
    data class ConnectivityChanged(val medium: ConnMedium, val state: ConnState, val name: String?) : TriggerEvent
}
```

- [ ] **Step 4: Scrivi `ActionExecutor.kt`, `AutomationStore.kt`, `AuditSink.kt`**

```kotlin
// ActionExecutor.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId

data class FireContext(val event: TriggerEvent, val state: DeviceState, val automationId: AutomationId)

sealed interface ActionResult {
    /** Azione deterministica completata in modo sincrono. */
    data object Success : ActionResult
    /** Azione GENERATIVA accodata nella lane async (spec §6/C3): execute() NON deve bloccare
     *  10-30 s; l'esito reale della lane viene riportato all'AuditSink. */
    data object Submitted : ActionResult
    data class Failure(val reason: String) : ActionResult
}

interface ActionExecutor { suspend fun execute(action: Action, ctx: FireContext): ActionResult }
```

```kotlin
// AutomationStore.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
interface AutomationStore {
    suspend fun get(id: AutomationId): Automation?
    suspend fun armed(): List<Automation>
    suspend fun save(a: Automation)
    suspend fun setStatus(id: AutomationId, status: AutomationStatus)
    suspend fun recordFired(id: AutomationId, atMillis: Long)
    suspend fun lastFiredAt(id: AutomationId): Long?
}
```

```kotlin
// AuditSink.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId

enum class AuditKind { FIRED, SUPPRESSED_COOLDOWN, CONDITIONS_NOT_MET, ERROR }
data class AuditEvent(val automationId: AutomationId, val kind: AuditKind, val atMillis: Long, val detail: String = "")

/** Log di ogni scatto/soppressione/errore (spec §10.6). Impl Room in P0-B. */
interface AuditSink { suspend fun record(e: AuditEvent) }
object NoopAuditSink : AuditSink { override suspend fun record(e: AuditEvent) {} }
```

- [ ] **Step 5: Scrivi i fake (`Fakes.kt` in `src/test`)**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*

class FakeActionExecutor(private val fail: Set<String> = emptySet()) : ActionExecutor {
    val executed = mutableListOf<Action>()
    override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
        executed += action
        val key = action::class.simpleName ?: ""
        return when {
            key in fail -> ActionResult.Failure("forced")
            action.tier == ActionTier.GENERATIVE -> ActionResult.Submitted
            else -> ActionResult.Success
        }
    }
}

class FakeAutomationStore(seed: List<Automation> = emptyList()) : AutomationStore {
    private val map = seed.associateBy { it.id }.toMutableMap()
    private val fired = mutableMapOf<AutomationId, Long>()
    override suspend fun get(id: AutomationId) = map[id]
    override suspend fun armed() = map.values.filter { it.status == AutomationStatus.ARMED && it.enabled }
    override suspend fun save(a: Automation) { map[a.id] = a }
    override suspend fun setStatus(id: AutomationId, status: AutomationStatus) { map[id]?.let { map[id] = it.copy(status = status) } }
    override suspend fun recordFired(id: AutomationId, atMillis: Long) { fired[id] = atMillis }
    override suspend fun lastFiredAt(id: AutomationId) = fired[id]
}

class FakeAuditSink : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) { events += e }
}
```

- [ ] **Step 6: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*FakesTest'` → PASS (3 test).

```bash
git add engine-core/
git commit -m "feat(engine): runtime interfaces (executor+Submitted, store, audit, events) + fakes"
```

---

## Task 5: `ConditionEvaluator`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/ConditionEvaluator.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/runtime/ConditionEvaluatorTest.kt`

- [ ] **Step 1: Scrivi il test (clock iniettato, deterministico)**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
import java.time.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class ConditionEvaluatorTest {
    private fun clockAt(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test fun `state equals numeric and string`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val st = DeviceState(values = mapOf("battery" to "80", "ringer" to "normal"))
        assertTrue(ev.eval(Condition.StateEquals("battery", CmpOp.GT, "50"), st))
        assertFalse(ev.eval(Condition.StateEquals("ringer", CmpOp.EQ, "silent"), st))
    }
    @Test fun `time window crossing midnight`() {
        val st = DeviceState()
        // 23:30 Rome è dentro [23:00, 07:00]
        val ev = ConditionEvaluator(clockAt("2026-07-12T21:30:00Z")) // 23:30 CEST
        assertTrue(ev.eval(Condition.TimeWindow("23:00", "07:00", "Europe/Rome"), st))
        // 12:00 Rome è fuori
        val ev2 = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        assertFalse(ev2.eval(Condition.TimeWindow("23:00", "07:00", "Europe/Rome"), st))
    }
    @Test fun `and or not compose`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        val st = DeviceState(foregroundApp = "com.x")
        val c = Condition.And(listOf(
            Condition.Not(Condition.AppInForeground("com.whatsapp")),
            Condition.Or(listOf(Condition.AppInForeground("com.x"), Condition.AppInForeground("com.y"))),
        ))
        assertTrue(ev.eval(c, st))
    }
    @Test fun `null condition is true`() {
        val ev = ConditionEvaluator(clockAt("2026-07-12T10:00:00Z"))
        assertTrue(ev.eval(null, DeviceState()))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*ConditionEvaluatorTest'`
Expected: FAIL, unresolved `ConditionEvaluator`.

- [ ] **Step 3: Implementa `ConditionEvaluator.kt`**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

class ConditionEvaluator(private val clock: Clock) {
    /** Condizione null = nessun vincolo = vero. */
    fun eval(c: Condition?, state: DeviceState): Boolean = when (c) {
        null -> true
        is Condition.And -> c.all.all { eval(it, state) }
        is Condition.Or -> c.any.any { eval(it, state) }
        is Condition.Not -> !eval(c.cond, state)
        is Condition.AppInForeground -> state.foregroundApp == c.pkg
        is Condition.StateEquals -> compare(state.values[c.key], c.op, c.value)
        is Condition.LocationIn -> state.location?.let {
            haversineM(it.lat, it.lng, c.lat, c.lng) <= c.radiusM
        } ?: false
        is Condition.TimeWindow -> inWindow(c)
    }

    private fun compare(actual: String?, op: CmpOp, expected: String): Boolean {
        if (actual == null) return false
        val an = actual.toDoubleOrNull(); val en = expected.toDoubleOrNull()
        return when (op) {
            CmpOp.EQ -> actual == expected
            CmpOp.NEQ -> actual != expected
            CmpOp.CONTAINS -> actual.contains(expected)
            CmpOp.GT -> if (an != null && en != null) an > en else actual > expected
            CmpOp.LT -> if (an != null && en != null) an < en else actual < expected
        }
    }

    private fun inWindow(c: Condition.TimeWindow): Boolean {
        val now = LocalTime.now(clock.withZone(ZoneId.of(c.tz)))
        val start = LocalTime.parse(c.startLocal); val end = LocalTime.parse(c.endLocal)
        return if (start <= end) now >= start && now < end          // stessa giornata
        else now >= start || now < end                               // attraversa mezzanotte
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*ConditionEvaluatorTest'` → PASS (4 test).

```bash
git add engine-core/
git commit -m "feat(engine): ConditionEvaluator (time-window, state, geo, AND/OR/NOT)"
```

---

## Task 6: `TriggerMatcher`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/TriggerMatcher.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/runtime/TriggerMatcherTest.kt`

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class TriggerMatcherTest {
    private val m = TriggerMatcher()

    @Test fun `notification conversationId takes precedence over display name`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", conversationId = "jid:42", sender = "Moglie")
        // conversationId giusto ma display name diverso (rinominato/spoof) -> match comunque
        assertTrue(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", conversationId = "jid:42", sender = "Chiunque")))
        // display name giusto ma conversationId diverso (spoof del nome) -> NO match
        assertFalse(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", conversationId = "jid:666", sender = "Moglie")))
    }
    @Test fun `notification falls back to sender when no conversationId in spec`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", sender = "Moglie")
        assertTrue(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", sender = "Moglie")))
        assertFalse(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", sender = "Capo")))
    }
    @Test fun `notification isGroup filter and case-insensitive textMatch`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", isGroup = false, textMatch = "ciao")
        assertTrue(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", text = "CIAO amore", isGroup = false)))
        assertFalse(m.matches(spec, TriggerEvent.NotificationPosted("com.whatsapp", text = "ciao", isGroup = true)))
    }
    @Test fun `time matches by construction`() {
        assertTrue(m.matches(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), TriggerEvent.TimeFired(AutomationId("a1"))))
    }
    @Test fun `connectivity matches medium state and name`() {
        val spec = Trigger.Connectivity(ConnMedium.WIFI, ConnState.DISCONNECTED, match = "Casa")
        assertTrue(m.matches(spec, TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa")))
        assertFalse(m.matches(spec, TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, "Casa")))
        assertFalse(m.matches(spec, TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.DISCONNECTED, "Casa")))
    }
    @Test fun `phone numbers match across formats`() {
        val spec = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+39 393 207 7480")
        assertTrue(m.matches(spec, TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, "3932077480")))
        assertFalse(m.matches(spec, TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, "3331112223")))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*TriggerMatcherTest'`
Expected: FAIL, unresolved `TriggerMatcher`.

- [ ] **Step 3: Implementa `TriggerMatcher.kt`**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*

/** Verifica che un evento runtime soddisfi lo spec del trigger.
 *  I trigger schedulati (Time/Geofence) portano già l'automationId, quindi il match sullo spec è banale. */
class TriggerMatcher {
    fun matches(spec: Trigger, event: TriggerEvent): Boolean = when {
        spec is Trigger.Time && event is TriggerEvent.TimeFired -> true
        spec is Trigger.Geofence && event is TriggerEvent.GeofenceTransitioned -> spec.transition == event.transition
        spec is Trigger.Notification && event is TriggerEvent.NotificationPosted -> matchesNotification(spec, event)
        spec is Trigger.PhoneState && event is TriggerEvent.PhoneStateChanged ->
            spec.event == event.event && (spec.number == null || numbersMatch(spec.number, event.number))
        spec is Trigger.Connectivity && event is TriggerEvent.ConnectivityChanged ->
            spec.medium == event.medium && spec.state == event.state &&
                (spec.match == null || spec.match == event.name)
        else -> false
    }

    private fun matchesNotification(spec: Trigger.Notification, e: TriggerEvent.NotificationPosted): Boolean {
        if (spec.pkg != e.pkg) return false
        if (spec.isGroup != null && spec.isGroup != e.isGroup) return false
        // Identità: conversationId (chiave stabile, spec E15) ha precedenza sul display name spoofabile.
        val identityOk = when {
            spec.conversationId != null -> spec.conversationId == e.conversationId
            spec.sender != null -> spec.sender == e.sender
            else -> true
        }
        if (!identityOk) return false
        if (spec.titleMatch != null && e.title?.contains(spec.titleMatch, ignoreCase = true) != true) return false
        if (spec.textMatch != null && e.text?.contains(spec.textMatch, ignoreCase = true) != true) return false
        return true
    }

    /** Confronto numeri robusto ai formati (+39 / spazi / trattini): solo cifre, match per suffisso (min 7). */
    private fun numbersMatch(spec: String, actual: String?): Boolean {
        if (actual == null) return false
        val a = spec.filter(Char::isDigit); val b = actual.filter(Char::isDigit)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length < 7 || b.length < 7) return a == b
        return a.endsWith(b) || b.endsWith(a)
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*TriggerMatcherTest'` → PASS (6 test).

```bash
git add engine-core/
git commit -m "feat(engine): TriggerMatcher (conversationId precedence, normalized numbers, direction)"
```

---

## Task 7: `CronSchedule` + `TimeSpecs` (next-fire DST-safe)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/CronSchedule.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/runtime/CronScheduleTest.kt`

Contesto (spec §9/E6): il calcolo "prossima occorrenza cron in TZ" è la logica pura più insidiosa del progetto (DST!) e va unit-testata qui, non accanto ad AlarmManager in P0-B. Semantica DST **definita e testata**: ora locale saltata → fire spostato avanti della durata del gap (comportamento documentato di `LocalDateTime.atZone`); ora duplicata → **una sola** esecuzione (primo offset).

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.Trigger
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
class CronScheduleTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun next(cron: String, afterIso: String): Instant? =
        CronSchedule.parse(cron).nextFireAfter(Instant.parse(afterIso), rome)

    @Test fun `daily at 23 local`() {
        assertEquals(Instant.parse("2026-07-12T21:00:00Z"), next("0 23 * * *", "2026-07-12T10:00:00Z"))
    }
    @Test fun `strictly after - fire time itself rolls to next day`() {
        assertEquals(Instant.parse("2026-07-13T21:00:00Z"), next("0 23 * * *", "2026-07-12T21:00:00Z"))
    }
    @Test fun `dst gap - skipped local time shifts forward`() {
        // 29/03/2026: 02:00->03:00 CEST; 02:30 locale non esiste -> 03:30 CEST = 01:30Z
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), next("30 2 * * *", "2026-03-28T23:30:00Z"))
    }
    @Test fun `dst overlap - duplicated local time fires once (earlier offset)`() {
        // 25/10/2026: 03:00 CEST -> 02:00 CET; 02:30 occorre due volte -> prima occorrenza (CEST) = 00:30Z
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), next("30 2 * * *", "2026-10-24T22:00:00Z"))
    }
    @Test fun `step and dow`() {
        assertEquals(Instant.parse("2026-07-12T10:15:00Z"), next("*/15 * * * *", "2026-07-12T10:07:00Z"))
        // 12/07/2026 è domenica -> lunedì 13 alle 09:00 CEST
        assertEquals(Instant.parse("2026-07-13T07:00:00Z"), next("0 9 * * 1", "2026-07-12T10:00:00Z"))
        // dow 7 = domenica
        assertEquals(Instant.parse("2026-07-12T07:00:00Z"), next("0 9 * * 7", "2026-07-11T00:00:00Z"))
    }
    @Test fun `vixie OR between dom and dow`() {
        // "giorno 1 del mese OPPURE lunedì": dopo mercoledì 1/7 sera viene lunedì 6/7, non il 1/8
        assertEquals(Instant.parse("2026-07-06T07:00:00Z"), next("0 9 1 * 1", "2026-07-01T12:00:00Z"))
    }
    @Test fun `leap day`() {
        assertEquals(Instant.parse("2028-02-29T11:00:00Z"), next("0 12 29 2 *", "2026-03-01T00:00:00Z"))
    }
    @Test fun `invalid expressions throw`() {
        assertFailsWith<IllegalArgumentException> { CronSchedule.parse("61 * * * *") }
        assertFailsWith<IllegalArgumentException> { CronSchedule.parse("* * * *") }
        assertFailsWith<IllegalArgumentException> { CronSchedule.parse("x * * * *") }
    }
    @Test fun `timespecs one-shot at`() {
        val t = Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome")
        assertEquals(Instant.parse("2026-07-15T06:00:00Z"), TimeSpecs.nextFire(t, Instant.parse("2026-07-12T00:00:00Z")))
        assertNull(TimeSpecs.nextFire(t, Instant.parse("2026-07-15T06:00:00Z")))   // passato -> mai più
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*CronScheduleTest'`
Expected: FAIL, unresolved `CronSchedule` / `TimeSpecs`.

- [ ] **Step 3: Implementa `CronSchedule.kt`**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Cron 5 campi (min hour dom mon dow). Subset supportato: `*`, `n`, liste `a,b`, range `a-b`, step `*/s` e `a-b/s`.
 *  DOW 0-7 (0 e 7 = domenica). DOM e DOW entrambi ristretti = OR (semantica vixie).
 *  DST: ora locale saltata -> fire spostato avanti della durata del gap; ora duplicata -> una sola
 *  esecuzione al primo offset (comportamento documentato di java.time, testato). */
class CronSchedule private constructor(
    private val minutes: Set<Int>, private val hours: Set<Int>,
    private val dom: Set<Int>, private val months: Set<Int>, private val dow: Set<Int>,
    private val domRestricted: Boolean, private val dowRestricted: Boolean,
) {
    /** Prossimo scatto STRETTAMENTE dopo [after], o null se non esiste entro 5 anni. */
    fun nextFireAfter(after: Instant, zone: ZoneId): Instant? {
        var t = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t < limit) {
            if (t.monthValue !in months) { t = t.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS); continue }
            if (!dayMatches(t.toLocalDate())) { t = t.plusDays(1).truncatedTo(ChronoUnit.DAYS); continue }
            if (t.hour !in hours) { t = t.plusHours(1).truncatedTo(ChronoUnit.HOURS); continue }
            if (t.minute !in minutes) { t = t.plusMinutes(1); continue }
            return t.atZone(zone).toInstant()   // gap -> shift avanti; overlap -> primo offset
        }
        return null
    }

    private fun dayMatches(d: LocalDate): Boolean {
        val domOk = d.dayOfMonth in dom
        val dowOk = (d.dayOfWeek.value % 7) in dow   // java Mon=1..Sun=7 -> cron Sun=0
        return when {
            domRestricted && dowRestricted -> domOk || dowOk
            domRestricted -> domOk
            dowRestricted -> dowOk
            else -> true
        }
    }

    companion object {
        fun parse(expr: String): CronSchedule {
            val f = expr.trim().split(Regex("\\s+"))
            require(f.size == 5) { "cron: servono 5 campi, trovati ${f.size} in '$expr'" }
            val dowRaw = parseField(f[4], 0, 7)
            return CronSchedule(
                minutes = parseField(f[0], 0, 59),
                hours = parseField(f[1], 0, 23),
                dom = parseField(f[2], 1, 31),
                months = parseField(f[3], 1, 12),
                dow = dowRaw.map { it % 7 }.toSet(),
                domRestricted = f[2] != "*",
                dowRestricted = f[4] != "*",
            )
        }

        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            if (field == "*") return (min..max).toSet()
            val out = mutableSetOf<Int>()
            for (part in field.split(',')) {
                val bits = part.split('/', limit = 2)
                val step = if (bits.size == 2)
                    requireNotNull(bits[1].toIntOrNull()?.takeIf { it > 0 }) { "cron: step non valido '$part'" }
                else 1
                val rangePart = bits[0]
                val range = when {
                    rangePart == "*" -> min..max
                    rangePart.contains('-') -> {
                        val lo = rangePart.substringBefore('-').toIntOrNull()
                        val hi = rangePart.substringAfter('-').toIntOrNull()
                        require(lo != null && hi != null && lo <= hi) { "cron: range non valido '$part'" }
                        lo..hi
                    }
                    else -> {
                        val v = requireNotNull(rangePart.toIntOrNull()) { "cron: valore non valido '$part'" }
                        v..v
                    }
                }
                require(range.first >= min && range.last <= max) { "cron: '$part' fuori da [$min,$max]" }
                out += range step step
            }
            return out
        }
    }
}

/** Entry-point unico per P0-B (AlarmManagerTimeTrigger): gestisce cron e at. */
object TimeSpecs {
    /** Prossimo scatto strettamente dopo [after], o null (one-shot passato / cron senza occorrenze).
     *  Lancia IllegalArgumentException/DateTimeException su spec malformato: il DraftValidator
     *  garantisce che non arrivi mai qui un Time invalido. */
    fun nextFire(t: Trigger.Time, after: Instant): Instant? {
        val zone = ZoneId.of(t.tz)
        return when {
            t.cron != null -> CronSchedule.parse(t.cron).nextFireAfter(after, zone)
            t.at != null -> LocalDateTime.parse(t.at).atZone(zone).toInstant().takeIf { it > after }
            else -> null
        }
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*CronScheduleTest'` → PASS (9 test).

```bash
git add engine-core/
git commit -m "feat(engine): CronSchedule + TimeSpecs (5-field cron, vixie OR, DST gap/overlap tested)"
```

---

## Task 8: `Engine` (orchestrazione)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/Engine.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/runtime/EngineTest.kt`

Contratti chiave (spec §5/§6/C3, rev 3): stato device **lazy** (letto solo se un candidato matcha e serve), priorità **crescente** (il più prioritario esegue ultimo → last-writer-wins), **isolamento errori** per-automazione, cooldown minimo 60 s per regole generative, audit di scatti e soppressioni.

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class EngineTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private fun armed(id: String, t: Trigger, acts: List<Action>, cond: Condition? = null, cooldown: Long = 0, prio: Int = 0) =
        Automation(AutomationId(id), id, CreatedBy.LLM, AutomationStatus.ARMED, t, acts, cond, cooldownMs = cooldown, priority = prio)
    private fun engine(store: AutomationStore, ex: ActionExecutor, clockIso: String, audit: AuditSink = NoopAuditSink, now: () -> Long = { 1000 }) =
        Engine(store, ex, ConditionEvaluator(clock(clockIso)), TriggerMatcher(), audit, now)

    @Test fun `time trigger fires deterministic action when condition holds`() = runTest {
        val a = armed("a1", Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
            cond = Condition.StateEquals("ringer", CmpOp.NEQ, "silent"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        engine(store, ex, "2026-07-12T21:30:00Z")
            .onTrigger(TriggerEvent.TimeFired(AutomationId("a1"))) { DeviceState(values = mapOf("ringer" to "normal")) }
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }
    @Test fun `condition false skips actions and audits`() = runTest {
        val a = armed("a1", Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), listOf(Action.SetWifi(false)),
            cond = Condition.StateEquals("ringer", CmpOp.EQ, "silent"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a)); val audit = FakeAuditSink()
        engine(store, ex, "2026-07-12T21:30:00Z", audit)
            .onTrigger(TriggerEvent.TimeFired(AutomationId("a1"))) { DeviceState(values = mapOf("ringer" to "normal")) }
        assertEquals(emptyList<Action>(), ex.executed)
        assertEquals(AuditKind.CONDITIONS_NOT_MET, audit.events.single().kind)
    }
    @Test fun `state provider is lazy - not called when nothing matches`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var reads = 0
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(TriggerEvent.NotificationPosted("com.telegram")) { reads++; DeviceState() }
        assertEquals(0, reads)
        assertEquals(emptyList<Action>(), ex.executed)
    }
    @Test fun `higher priority executes LAST and wins on shared target`() = runTest {
        val low = armed("low", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), prio = 1)
        val high = armed("high", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(true)), prio = 10)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(high, low))
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(TriggerEvent.NotificationPosted("com.whatsapp")) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetWifi(false), Action.SetWifi(true)), ex.executed)  // last-writer-wins
    }
    @Test fun `exception in one automation does not break the batch`() = runTest {
        val bad = armed("bad", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), prio = 0)
        val good = armed("good", Trigger.Notification("com.whatsapp"), listOf(Action.SetBluetooth(true)), prio = 1)
        val store = FakeAutomationStore(listOf(bad, good)); val audit = FakeAuditSink()
        val throwing = object : ActionExecutor {
            val executed = mutableListOf<Action>()
            override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
                if (ctx.automationId == AutomationId("bad")) throw IllegalStateException("boom")
                executed += action; return ActionResult.Success
            }
        }
        val outcomes = engine(store, throwing, "2026-07-12T10:00:00Z", audit)
            .onTrigger(TriggerEvent.NotificationPosted("com.whatsapp")) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetBluetooth(true)), throwing.executed)
        assertEquals(1, outcomes.size)
        assertTrue(audit.events.any { it.kind == AuditKind.ERROR && it.automationId == AutomationId("bad") })
    }
    @Test fun `cooldown suppresses second fire`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"), listOf(Action.SetWifi(false)), cooldown = 5000)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var now = 1000L
        val e = engine(store, ex, "2026-07-12T10:00:00Z", now = { now })
        val ev = TriggerEvent.NotificationPosted("com.whatsapp")
        e.onTrigger(ev) { DeviceState() }; now = 2000L; e.onTrigger(ev) { DeviceState() }
        assertEquals(1, ex.executed.size)
    }
    @Test fun `generative rules get a minimum 60s cooldown even when configured 0`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp"),
            listOf(Action.InvokeLlm("reply", listOf("notification"), listOf("whatsapp_reply"), true)), cooldown = 0)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var now = 1000L
        val e = engine(store, ex, "2026-07-12T10:00:00Z", now = { now })
        val ev = TriggerEvent.NotificationPosted("com.whatsapp")
        e.onTrigger(ev) { DeviceState() }; now = 31_000L; e.onTrigger(ev) { DeviceState() }   // +30s: dentro il minimo
        assertEquals(1, ex.executed.size)
        now = 62_000L; e.onTrigger(ev) { DeviceState() }                                       // +61s: fuori
        assertEquals(2, ex.executed.size)
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*EngineTest'`
Expected: FAIL, unresolved `Engine`.

- [ ] **Step 3: Implementa `Engine.kt`**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus

/** @param now fornitore di epoch-millis (iniettato per testabilità; su Android = System::currentTimeMillis). */
class Engine(
    private val store: AutomationStore,
    private val executor: ActionExecutor,
    private val evaluator: ConditionEvaluator,
    private val matcher: TriggerMatcher,
    private val audit: AuditSink = NoopAuditSink,
    private val now: () -> Long,
) {
    companion object {
        /** Tetto anti trigger-storm/ping-pong per le regole generative (spec §5/C2). */
        const val MIN_GENERATIVE_COOLDOWN_MS = 60_000L
    }

    data class FireOutcome(val automation: Automation, val actions: List<Action>, val results: List<ActionResult>)

    /** [stateProvider] è invocato LAZY (al più una volta per batch): gli eventi che non matchano
     *  nessuna regola armata non costano una lettura stato (spec §9, onestà batteria). */
    suspend fun onTrigger(event: TriggerEvent, stateProvider: suspend () -> DeviceState): List<FireOutcome> {
        val candidates = when (event) {
            is TriggerEvent.TimeFired -> listOfNotNull(store.get(event.automationId))
            is TriggerEvent.GeofenceTransitioned -> listOfNotNull(store.get(event.automationId))
            else -> store.armed()
        }.filter { it.status == AutomationStatus.ARMED && it.enabled }
            // priorità CRESCENTE: il più prioritario esegue ULTIMO e vince sui target condivisi (last-writer-wins, spec §5/C1)
            .sortedWith(compareBy({ it.priority }, { it.id.value }))

        var cached: DeviceState? = null
        suspend fun state(): DeviceState = cached ?: stateProvider().also { cached = it }

        val outcomes = mutableListOf<FireOutcome>()
        for (a in candidates) {
            try {
                if (!matcher.matches(a.trigger, event)) continue
                val cooldown = effectiveCooldown(a)
                if (cooldown > 0) {
                    val last = store.lastFiredAt(a.id)
                    if (last != null && now() - last < cooldown) {
                        audit.record(AuditEvent(a.id, AuditKind.SUPPRESSED_COOLDOWN, now())); continue
                    }
                }
                if (a.conditions != null && !evaluator.eval(a.conditions, state())) {
                    audit.record(AuditEvent(a.id, AuditKind.CONDITIONS_NOT_MET, now())); continue
                }
                val ctx = FireContext(event, state(), a.id)
                val results = a.actions.map { executor.execute(it, ctx) }   // Failure non interrompe la catena: esito PARTIAL nel log (spec §6)
                store.recordFired(a.id, now())
                audit.record(AuditEvent(a.id, AuditKind.FIRED, now(),
                    results.joinToString { it::class.simpleName ?: "?" }))
                outcomes += FireOutcome(a, a.actions, results)
            } catch (e: Exception) {
                audit.record(AuditEvent(a.id, AuditKind.ERROR, now(), e.message ?: "error"))
            }
        }
        return outcomes
    }

    private fun effectiveCooldown(a: Automation): Long =
        if (a.actions.any { it.tier == ActionTier.GENERATIVE }) maxOf(a.cooldownMs, MIN_GENERATIVE_COOLDOWN_MS)
        else a.cooldownMs
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*EngineTest'` → PASS (7 test).

```bash
git add engine-core/
git commit -m "feat(engine): Engine (lazy state, ascending priority=last-writer-wins, error isolation, min generative cooldown, audit)"
```

---

## Task 9: `ConflictDetector` (euristica trigger-aware, §13/C1)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/safety/ConflictDetector.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/safety/ConflictDetectorTest.kt`

Contesto: "stesso target, valori opposti" da solo produrrebbe un falso positivo sul pattern **più normale** dell'automazione domestica (wifi off uscendo + wifi on entrando) e il warning diventerebbe rumore. Le coppie con trigger **complementari** (stesso geofence, transizioni opposte; stessa connectivity, state opposti) sono legittime e vengono soppresse.

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class ConflictDetectorTest {
    private fun a(id: String, t: Trigger, act: Action) = Automation(AutomationId(id), id, CreatedBy.LLM,
        AutomationStatus.ARMED, t, listOf(act))
    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")

    @Test fun `opposite wifi on overlapping triggers conflict`() {
        val w = ConflictDetector().detect(listOf(a("1", time, Action.SetWifi(true)), a("2", time, Action.SetWifi(false))))
        assertTrue(w.any { it.targetKey == "wifi" })
    }
    @Test fun `same direction no conflict`() {
        assertEquals(emptyList(), ConflictDetector().detect(listOf(a("1", time, Action.SetWifi(true)), a("2", time, Action.SetWifi(true)))))
    }
    @Test fun `complementary geofence enter-exit pair is legitimate, no conflict`() {
        val exit = a("1", Trigger.Geofence(45.4, 11.0, 100.0, Transition.EXIT), Action.SetWifi(false))
        val enter = a("2", Trigger.Geofence(45.4, 11.0, 100.0, Transition.ENTER), Action.SetWifi(true))
        assertEquals(emptyList(), ConflictDetector().detect(listOf(exit, enter)))
    }
    @Test fun `complementary connectivity pair is legitimate, no conflict`() {
        val on = a("1", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED, "Casa"), Action.SetBluetooth(false))
        val off = a("2", Trigger.Connectivity(ConnMedium.WIFI, ConnState.DISCONNECTED, "Casa"), Action.SetBluetooth(true))
        assertEquals(emptyList(), ConflictDetector().detect(listOf(on, off)))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*ConflictDetectorTest'`
Expected: FAIL, unresolved `ConflictDetector`.

- [ ] **Step 3: Implementa `ConflictDetector.kt`**

```kotlin
package dev.argus.engine.safety
import dev.argus.engine.model.*

data class ConflictWarning(val targetKey: String, val automationIds: List<AutomationId>, val message: String)

/** Euristica volutamente semplice (spec §13/C1): azioni sullo STESSO target con valori opposti,
 *  MA con soppressione delle coppie complementari legittime (enter/exit, connected/disconnected).
 *  NON è analisi statica completa dello spazio-trigger (indecidibile). Serve a segnalare, non a bloccare. */
class ConflictDetector {
    private data class Setting(val key: String, val value: String)
    private data class Entry(val auto: Automation, val setting: Setting)

    private fun setting(a: Action): Setting? = when (a) {
        is Action.SetWifi -> Setting("wifi", a.on.toString())
        is Action.SetBluetooth -> Setting("bluetooth", a.on.toString())
        is Action.SetDnd -> Setting("dnd", a.mode.name)
        is Action.SetRinger -> Setting("ringer", a.mode)
        else -> null
    }

    /** Coppie di trigger complementari = pattern legittimo, non conflitto. */
    private fun complementary(t1: Trigger, t2: Trigger): Boolean = when {
        t1 is Trigger.Geofence && t2 is Trigger.Geofence ->
            t1.lat == t2.lat && t1.lng == t2.lng && t1.transition != t2.transition
        t1 is Trigger.Connectivity && t2 is Trigger.Connectivity ->
            t1.medium == t2.medium && t1.match == t2.match && t1.state != t2.state
        else -> false
    }

    fun detect(automations: List<Automation>): List<ConflictWarning> {
        val entries = automations.flatMap { a -> a.actions.mapNotNull { act -> setting(act)?.let { Entry(a, it) } } }
        val warnings = mutableListOf<ConflictWarning>()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            val e1 = entries[i]; val e2 = entries[j]
            if (e1.auto.id == e2.auto.id) continue
            if (e1.setting.key != e2.setting.key || e1.setting.value == e2.setting.value) continue
            if (complementary(e1.auto.trigger, e2.auto.trigger)) continue
            warnings += ConflictWarning(e1.setting.key, listOf(e1.auto.id, e2.auto.id),
                "'${e1.auto.name}' e '${e2.auto.name}' impostano '${e1.setting.key}' a valori opposti " +
                    "(${e1.setting.value} vs ${e2.setting.value}) su trigger potenzialmente sovrapposti")
        }
        return warnings.distinct()
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*ConflictDetectorTest'` → PASS (4 test).

```bash
git add engine-core/
git commit -m "feat(engine): trigger-aware ConflictDetector (suppresses legitimate complementary pairs)"
```

---

## Task 10: `Brain` + `CliBridgeParser` (output Hermes → `AutomationDraft`)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/brain/Brain.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/brain/CliBridgeParser.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/brain/CliBridgeParserTest.kt`

Contesto (spec §2/§7): Hermes via CliBridge ritorna **prosa + riga `@@META@@ {json}`**. Per il `compile`, il JSON meta contiene `{"draft": {...AutomationDraft...}}`. Il parser deve essere robusto: estrazione **bilanciata** dell'oggetto JSON (non regex greedy — la prosa dopo il meta può contenere graffe), fail-soft su malformazioni, ed errore esplicito quando il meta c'è ma manca `draft` (altrimenti l'utente vede una risposta senza regola e non sa perché).

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.brain
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
class CliBridgeParserTest {
    private val p = CliBridgeParser()
    @Test fun `extracts prose and draft`() {
        val raw = """
            Ho creato l'automazione per la suoneria.
            @@META@@ {"draft": {"name":"dnd sera","trigger":{"type":"time","cron":"0 23 * * *","tz":"Europe/Rome"},
            "actions":[{"type":"set_dnd","mode":"PRIORITY"}]}}
        """.trimIndent()
        val r = p.parseCompile(raw)
        assertEquals("Ho creato l'automazione per la suoneria.", r.reply)
        val d = assertNotNull(r.draft)
        assertEquals("dnd sera", d.name)
        assertEquals(Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"), d.trigger)
    }
    @Test fun `balanced extraction survives prose with braces after the meta`() {
        val raw = """ok @@META@@ {"draft":{"name":"x","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":true}]}} ricorda {questa} nota"""
        val r = p.parseCompile(raw)
        assertNotNull(r.draft)
        assertNull(r.metaError)
    }
    @Test fun `braces inside json strings do not break extraction`() {
        val raw = """@@META@@ {"draft":{"name":"tono {caldo}","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":false}]}}"""
        assertEquals("tono {caldo}", p.parseCompile(raw).draft?.name)
    }
    @Test fun `no meta yields reply only`() {
        val r = p.parseCompile("Non ho capito, puoi ripetere?")
        assertEquals("Non ho capito, puoi ripetere?", r.reply)
        assertNull(r.draft); assertNull(r.metaError)
    }
    @Test fun `meta without draft field is an explicit error`() {
        val r = p.parseCompile("""ok @@META@@ {"Draft": {"name":"typo"}}""")
        assertNull(r.draft)
        assertNotNull(r.metaError)
    }
    @Test fun `malformed meta yields reply plus error, no crash`() {
        val r = p.parseCompile("ok @@META@@ {non json}")
        assertNull(r.draft)
        assertNotNull(r.metaError)
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*CliBridgeParserTest'`
Expected: FAIL, unresolved `CliBridgeParser`.

- [ ] **Step 3: Scrivi `Brain.kt` e implementa `CliBridgeParser.kt`**

```kotlin
// Brain.kt
package dev.argus.engine.brain
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.runtime.DeviceState

data class CompileResult(val reply: String, val draft: AutomationDraft?, val metaError: String?)

/** Porta verso il modello (spec §7). P0: solo compile one-shot.
 *  act() (InvokeLlm, P1) e chat() streaming (P3) verranno aggiunti nelle fasi rispettive. */
interface Brain {
    suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult
}
```

```kotlin
// CliBridgeParser.kt
package dev.argus.engine.brain
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SENTINEL = "@@META@@"

@Serializable private data class MetaEnvelope(val draft: AutomationDraft? = null)

/** Parser puro dell'output CliBridge (spec §2/§7). Fail-soft: mai crash, sempre reply + eventuale metaError. */
class CliBridgeParser(private val json: Json = lenient()) {
    fun parseCompile(raw: String): CompileResult {
        val idx = raw.indexOf(SENTINEL)
        if (idx < 0) return CompileResult(raw.trim(), null, null)
        val prose = raw.substring(0, idx).trim()
        val obj = extractJsonObject(raw.substring(idx + SENTINEL.length))
            ?: return CompileResult(prose, null, "nessun oggetto JSON dopo il sentinel")
        return try {
            val draft = json.decodeFromString(MetaEnvelope.serializer(), obj).draft
            if (draft == null) CompileResult(prose, null, "meta presente ma senza campo 'draft'")
            else CompileResult(prose, draft, null)
        } catch (e: Exception) {
            CompileResult(prose, null, e.message ?: "meta parse error")
        }
    }

    /** Primo oggetto JSON BILANCIATO (graffe contate fuori dalle stringhe): robusto a prosa dopo il meta. */
    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }

    private companion object { fun lenient() = Json(ArgusJson) { isLenient = true; ignoreUnknownKeys = true } }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*CliBridgeParserTest'` → PASS (6 test).

```bash
git add engine-core/
git commit -m "feat(engine): Brain interface + CliBridgeParser (balanced JSON extraction, explicit missing-draft error)"
```

---

## Task 11: `DraftValidator` (dominio + invarianti sicurezza)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/safety/DraftValidator.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/safety/DraftValidatorTest.kt`

Contesto (spec §10/D7): **nessun draft LLM entra nell'engine senza passare di qui**. Il parser è lenient (`ignoreUnknownKeys`), quindi accetterebbe draft semanticamente rotti (cron invalido, chiavi stato inventate, tool inesistenti) che la UI mostrerebbe come regole valide destinate a non scattare mai. Qui vive anche l'**invariante di sicurezza** §7: mai `shell.run`/`app.install`/`automation.*` negli `allowed_tools` di un `InvokeLlm` (l'LLM genererebbe al fire-time azioni mai approvate), e le reply generative solo su conversazioni 1:1 whitelistate identificate per `conversationId`.

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class DraftValidatorTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show", "state.read"))
    private fun errors(issues: List<ValidationIssue>) = issues.filter { it.severity == Severity.ERROR }.map { it.code }

    private val validGenerative = AutomationDraft(
        name = "reply moglie",
        trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
        actions = listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)),
        cooldownMs = 120_000,
    )

    @Test fun `valid generative draft has no errors`() {
        assertEquals(emptyList(), errors(v.validate(validGenerative, whitelistedIds = setOf("jid:42"))))
    }
    @Test fun `forbidden tools in InvokeLlm are rejected`() {
        val d = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("shell.run", "automation.create"), true)))
        val e = errors(v.validate(d, setOf("jid:42")))
        assertEquals(2, e.count { it == "tool_forbidden" })
    }
    @Test fun `generative reply on group or without conversationId is rejected`() {
        val group = validGenerative.copy(trigger = Trigger.Notification("com.whatsapp", conversationId = "jid:g", isGroup = null))
        assertTrue("reply_target_group" in errors(v.validate(group, setOf("jid:g"))))
        val noId = validGenerative.copy(trigger = Trigger.Notification("com.whatsapp", sender = "Moglie", isGroup = false))
        assertTrue("reply_needs_conversation_id" in errors(v.validate(noId)))
    }
    @Test fun `target must be whitelisted when whitelist provided`() {
        assertTrue("target_not_whitelisted" in errors(v.validate(validGenerative, whitelistedIds = setOf("jid:other"))))
    }
    @Test fun `invalid cron and unknown state key are errors`() {
        val d = AutomationDraft("x", Trigger.Time(cron = "99 99 * * *", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)), conditions = Condition.StateEquals("suoneria", CmpOp.EQ, "silent"))
        val e = errors(v.validate(d))
        assertTrue("cron_invalid" in e); assertTrue("state_key_unknown" in e)
    }
    @Test fun `time requires exactly one of cron and at`() {
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(cron = "0 8 * * *", at = "2026-07-15T08:00", tz = "Europe/Rome"), listOf(Action.SetWifi(true))))))
        assertTrue("time_spec" in errors(v.validate(AutomationDraft("x",
            Trigger.Time(tz = "Europe/Rome"), listOf(Action.SetWifi(true))))))
    }
    @Test fun `small geofence radius is a warning, empty actions an error`() {
        val d = AutomationDraft("x", Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true), emptyList())
        val issues = v.validate(d)
        assertTrue("no_actions" in errors(issues))
        assertTrue(issues.any { it.severity == Severity.WARNING && it.code == "radius_small" })
    }
    @Test fun `read tools plus reply channel is a privacy warning`() {
        val d = validGenerative.copy(actions = listOf(Action.InvokeLlm("g", listOf(), listOf("whatsapp_reply", "state.read"), true)))
        assertTrue(v.validate(d, setOf("jid:42")).any { it.code == "read_plus_reply" && it.severity == Severity.WARNING })
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*DraftValidatorTest'`
Expected: FAIL, unresolved `DraftValidator`.

- [ ] **Step 3: Implementa `DraftValidator.kt`**

```kotlin
package dev.argus.engine.safety
import dev.argus.engine.model.*
import dev.argus.engine.runtime.CronSchedule
import java.time.LocalDateTime
import java.time.ZoneId

enum class Severity { ERROR, WARNING }
data class ValidationIssue(val severity: Severity, val code: String, val message: String)

/** Gate di dominio per i draft LLM (spec §10/D7): ERROR blocca l'arm, WARNING è mostrato in approvazione.
 *  Da ri-eseguire anche al fire-time sugli allowed_tools (difesa in profondità, spec §10.4). */
class DraftValidator(
    private val knownTools: Set<String>,
    private val stateKeys: Set<String> = StateKeys.ALL.keys,
) {
    companion object {
        /** Mai ammessi al fire-time generativo (spec §7): eseguirebbero azioni arbitrarie mai approvate. */
        val FORBIDDEN_IN_INVOKE_LLM = setOf("shell.run", "app.install")
        const val FORBIDDEN_PREFIX = "automation."
    }

    fun validate(d: AutomationDraft, whitelistedIds: Set<String> = emptySet()): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun err(code: String, msg: String) { issues += ValidationIssue(Severity.ERROR, code, msg) }
        fun warn(code: String, msg: String) { issues += ValidationIssue(Severity.WARNING, code, msg) }

        if (d.actions.isEmpty()) err("no_actions", "Il draft non contiene azioni")

        when (val t = d.trigger) {
            is Trigger.Time -> {
                if ((t.cron == null) == (t.at == null)) err("time_spec", "Time richiede esattamente uno tra cron e at")
                runCatching { ZoneId.of(t.tz) }.onFailure { err("tz_invalid", "Timezone '${t.tz}' non valida") }
                t.cron?.let { c -> runCatching { CronSchedule.parse(c) }.onFailure { err("cron_invalid", "Cron '$c' non valido: ${it.message}") } }
                t.at?.let { a -> runCatching { LocalDateTime.parse(a) }.onFailure { err("at_invalid", "Datetime '$a' non valido (atteso ISO locale, es. 2026-07-15T08:00)") } }
            }
            is Trigger.Geofence -> {
                if (t.radiusM <= 0) err("radius_invalid", "Raggio geofence deve essere > 0")
                else if (t.radiusM < 100) warn("radius_small", "Raggio ${t.radiusM.toInt()} m sotto i 100 m consigliati: scatti ritardati/mancati possibili (spec E14)")
                if (!t.resolveCurrentLocation && t.lat == 0.0 && t.lng == 0.0)
                    err("geofence_coords", "Coordinate mancanti: specificare lat/lng o resolveCurrentLocation=true")
            }
            is Trigger.Notification -> {
                if (t.pkg.isBlank()) err("pkg_blank", "Package della notifica mancante")
                if (t.conversationId == null && t.sender != null)
                    warn("sender_spoofable", "Match per display name: spoofabile (spec E15), preferire conversationId")
            }
            else -> {}
        }

        checkConditions(d.conditions, ::err)

        for (a in d.actions) when (a) {
            is Action.RunShell -> warn("shell_review", "Comando shell: autonomo solo dopo approvazione del comando letterale (spec §10.2)")
            is Action.WhatsAppReply -> if (d.trigger !is Trigger.Notification)
                err("reply_no_notification", "WhatsAppReply richiede un trigger Notification (serve il RemoteInput)")
            is Action.InvokeLlm -> validateInvokeLlm(a, d.trigger, whitelistedIds, ::err, ::warn)
            else -> {}
        }

        if (d.actions.any { it.tier == ActionTier.GENERATIVE } && d.cooldownMs < 60_000)
            warn("cooldown_raised", "Cooldown sotto 60 s su regola generativa: l'engine imporrà 60 s (spec §5)")

        return issues
    }

    private fun checkConditions(c: Condition?, err: (String, String) -> Unit) {
        when (c) {
            null -> {}
            is Condition.And -> c.all.forEach { checkConditions(it, err) }
            is Condition.Or -> c.any.forEach { checkConditions(it, err) }
            is Condition.Not -> checkConditions(c.cond, err)
            is Condition.StateEquals -> if (c.key !in stateKeys)
                err("state_key_unknown", "Chiave di stato '${c.key}' fuori dal registry StateKeys")
            is Condition.TimeWindow -> runCatching { ZoneId.of(c.tz) }.onFailure { err("tz_invalid", "Timezone '${c.tz}' non valida") }
            else -> {}
        }
    }

    private fun validateInvokeLlm(
        a: Action.InvokeLlm, trigger: Trigger, whitelist: Set<String>,
        err: (String, String) -> Unit, warn: (String, String) -> Unit,
    ) {
        if (a.allowedTools.isEmpty()) err("no_tools", "InvokeLlm senza allowed_tools")
        for (tool in a.allowedTools) {
            if (tool in FORBIDDEN_IN_INVOKE_LLM || tool.startsWith(FORBIDDEN_PREFIX))
                err("tool_forbidden", "Tool '$tool' vietato al fire-time generativo (spec §7/§10.4)")
            else if (tool !in knownTools) err("tool_unknown", "Tool '$tool' non nel catalogo")
        }
        if (a.allowedTools.any { it.startsWith("screen.") || it == "state.read" } && "whatsapp_reply" in a.allowedTools)
            warn("read_plus_reply", "Tool di lettura + canale in uscita: possibile esfiltrazione di contesto verso il mittente (spec §10.4)")
        if (a.replyTargetSender) {
            val n = trigger as? Trigger.Notification
            when {
                n == null -> err("reply_target_no_notification", "reply_target richiede un trigger Notification")
                n.isGroup != false -> err("reply_target_group", "Reply generative solo su chat 1:1: serve isGroup=false (spec §10.3)")
                n.conversationId == null -> err("reply_needs_conversation_id", "Reply generative richiedono conversationId (spec E15)")
                whitelist.isNotEmpty() && n.conversationId !in whitelist ->
                    err("target_not_whitelisted", "Conversazione non in whitelist (spec §10.3)")
            }
        }
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*DraftValidatorTest'` → PASS (8 test).

```bash
git add engine-core/
git commit -m "feat(engine): DraftValidator (domain checks + hard security invariants on InvokeLlm)"
```

---

## Task 12: `CapabilityManifest` + `CapabilityProbe`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/brain/CapabilityManifest.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/brain/CapabilityManifestTest.kt`

Contesto (spec §7): a inizio sessione il Brain riceve un manifest ("sa dove si trova e cosa può/non può fare"). Include la **whitelist come coppie `{displayName, id}`** — è così che il compile binda "Moglie" → `conversationId` senza tool contatti — e il **registry StateKeys**, così il compile non inventa chiavi di stato. `CapabilityProbe` è l'interfaccia (impl Android in P0-B).

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.brain
import dev.argus.engine.model.StateKeys
import kotlin.test.Test
import kotlin.test.assertTrue
class CapabilityManifestTest {
    @Test fun `render lists device, contacts with ids, state keys and disabled tools`() {
        val m = CapabilityManifest(
            deviceModel = "OnePlus 15", androidVersion = 16, shizukuAvailable = true,
            grantedPermissions = listOf("notification_listener"),
            availableTools = listOf("screen.capture", "toggle.set", "whatsapp_reply"),
            unavailableTools = mapOf("vision.analyze" to "nessun provider multimodale"),
            whitelistedContacts = listOf(WhitelistedContact("Moglie", "jid:42")),
        )
        val s = m.render()
        assertTrue(s.contains("OnePlus 15"))
        assertTrue(s.contains("Android 16"))
        assertTrue(s.contains("vision.analyze") && s.contains("nessun provider multimodale"))
        assertTrue(s.contains("Moglie") && s.contains("jid:42"))
        assertTrue(s.contains(StateKeys.RINGER) && s.contains("silent"))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*CapabilityManifestTest'`
Expected: FAIL, unresolved `CapabilityManifest`.

- [ ] **Step 3: Implementa `CapabilityManifest.kt`**

```kotlin
package dev.argus.engine.brain
import dev.argus.engine.model.StateKeys
import dev.argus.engine.runtime.DeviceState

data class WhitelistedContact(val displayName: String, val id: String)

data class CapabilityManifest(
    val deviceModel: String,
    val androidVersion: Int,
    val shizukuAvailable: Boolean,
    val grantedPermissions: List<String>,
    val availableTools: List<String>,
    val unavailableTools: Map<String, String>,   // tool -> motivo
    val whitelistedContacts: List<WhitelistedContact>,
    val stateKeys: Map<String, String> = StateKeys.ALL,
) {
    fun render(): String = buildString {
        appendLine("DISPOSITIVO: $deviceModel, Android $androidVersion")
        appendLine("SHIZUKU: ${if (shizukuAvailable) "attivo (privilegi shell)" else "NON attivo — azioni shell in coda"}")
        appendLine("PERMESSI: ${grantedPermissions.joinToString().ifEmpty { "nessuno" }}")
        appendLine("TOOL DISPONIBILI: ${availableTools.joinToString()}")
        if (unavailableTools.isNotEmpty())
            appendLine("TOOL NON DISPONIBILI: " + unavailableTools.entries.joinToString { "${it.key} (${it.value})" })
        appendLine("CHIAVI STATO (le uniche ammesse in state_equals): " +
            stateKeys.entries.joinToString { "${it.key}=${it.value}" })
        appendLine("CONTATTI WHITELIST (usa l'id come conversationId nei trigger/reply): " +
            whitelistedContacts.joinToString { "${it.displayName} (id: ${it.id})" }.ifEmpty { "nessuno" })
    }
}

/** Impl Android in P0-B (sonda Shizuku/permessi/Android reali). */
interface CapabilityProbe {
    suspend fun probe(currentState: DeviceState): CapabilityManifest
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*CapabilityManifestTest'` → PASS.

```bash
git add engine-core/
git commit -m "feat(engine): CapabilityManifest (contacts with ids, StateKeys registry) + CapabilityProbe"
```

---

## Task 13: Test di integrazione end-to-end (i 3 esempi + gate sicurezza, engine-side)

**Files:**
- Test: `engine-core/src/test/kotlin/dev/argus/engine/EndToEndTest.kt`

Verifica che i pezzi compongano i comportamenti dei 3 esempi (spec §11) **lato engine** (executor fake), incluso il percorso completo draft → validator → arm (con risoluzione `resolveCurrentLocation`) → fire, e che il gate di sicurezza fermi i draft vietati. Nessun nuovo codice di produzione.

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.model.*
import dev.argus.engine.runtime.*
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
class EndToEndTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private fun engine(store: AutomationStore, ex: ActionExecutor, clockIso: String) =
        Engine(store, ex, ConditionEvaluator(clock(clockIso)), TriggerMatcher(), NoopAuditSink) { 1000 }
    private val validator = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show"))

    @Test fun `example2 - compile, validate, arm, fire DND after 23`() = runTest {
        // 1) compile: output Hermes -> draft
        val raw = "Fatto.\n@@META@@ {\"draft\":{\"name\":\"dnd\",\"trigger\":{\"type\":\"time\",\"cron\":\"0 23 * * *\",\"tz\":\"Europe/Rome\"}," +
            "\"conditions\":{\"type\":\"state_equals\",\"key\":\"ringer\",\"op\":\"NEQ\",\"value\":\"silent\"}," +
            "\"actions\":[{\"type\":\"set_dnd\",\"mode\":\"PRIORITY\"}]}}"
        val draft = assertNotNull(CliBridgeParser().parseCompile(raw).draft)
        // 2) validator verde
        assertEquals(emptyList(), validator.validate(draft).filter { it.severity == Severity.ERROR })
        // 3) approva -> Automation ARMED
        val auto = Automation(AutomationId("a1"), draft.name, CreatedBy.LLM, AutomationStatus.ARMED,
            draft.trigger, draft.actions, draft.conditions)
        // 4) next-fire calcolabile (P0-B lo passa ad AlarmManager)
        assertNotNull(TimeSpecs.nextFire(draft.trigger as Trigger.Time, Instant.parse("2026-07-12T10:00:00Z")))
        // 5) fire alle 23:30 con suoneria "normal"
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        engine(store, ex, "2026-07-12T21:30:00Z")
            .onTrigger(TriggerEvent.TimeFired(AutomationId("a1"))) { DeviceState(values = mapOf("ringer" to "normal")) }
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }

    @Test fun `example1 - placeholder resolved at arm, geofence exit toggles wifi off bt on`() = runTest {
        // draft con resolveCurrentLocation (il compile non conosce il GPS, spec §7): warning raggio, nessun ERROR
        val draft = AutomationDraft("geo casa",
            Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true),
            listOf(Action.SetWifi(false), Action.SetBluetooth(true)))
        val issues = validator.validate(draft)
        assertEquals(emptyList(), issues.filter { it.severity == Severity.ERROR })
        assertTrue(issues.any { it.code == "radius_small" })
        // all'ARM l'app risolve le coordinate correnti
        val resolved = (draft.trigger as Trigger.Geofence).copy(lat = 45.4, lng = 11.0, resolveCurrentLocation = false)
        val auto = Automation(AutomationId("g1"), draft.name, CreatedBy.LLM, AutomationStatus.ARMED, resolved, draft.actions)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(TriggerEvent.GeofenceTransitioned(AutomationId("g1"), Transition.EXIT)) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetWifi(false), Action.SetBluetooth(true)), ex.executed)
    }

    @Test fun `example3 - whatsapp 1-1 from whitelisted conversation triggers generative reply as Submitted`() = runTest {
        val auto = Automation(AutomationId("w1"), "reply", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), replyTargetSender = true)),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        val outcomes = engine(store, ex, "2026-07-12T16:30:00Z")   // 18:30 CEST, dentro la finestra
            .onTrigger(TriggerEvent.NotificationPosted("com.whatsapp", conversationId = "jid:42",
                sender = "Moglie", text = "ciao", isGroup = false, notificationKey = "sbn:1")) { DeviceState() }
        assertEquals(1, ex.executed.size)
        assertEquals(ActionTier.GENERATIVE, ex.executed.first().tier)
        assertEquals(listOf<ActionResult>(ActionResult.Submitted), outcomes.single().results)  // lane async, engine non blocca
    }

    @Test fun `security gate - draft asking for shell at generative fire-time never reaches the engine`() = runTest {
        val malicious = AutomationDraft("innocua",
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.InvokeLlm("aiuta", listOf("notification"), listOf("whatsapp_reply", "shell.run"), true)))
        val errors = validator.validate(malicious, whitelistedIds = setOf("jid:42"))
            .filter { it.severity == Severity.ERROR }
        assertTrue(errors.any { it.code == "tool_forbidden" })   // ERROR => canArm=false, mai ARMED
    }
}
```

- [ ] **Step 2: Esegui — deve passare (tutto già implementato)**

Run: `./gradlew :engine-core:test --tests '*EndToEndTest'`
Expected: PASS (4 test). Nota timezone: `2026-07-12T16:30:00Z` = 18:30 CEST (dentro [18:00,22:00]).

- [ ] **Step 3: Esegui l'intera suite**

Run: `./gradlew :engine-core:test`
Expected: BUILD SUCCESSFUL, tutti i test verdi.

- [ ] **Step 4: Commit**

```bash
git add engine-core/
git commit -m "test(engine): end-to-end coverage of the 3 spec examples + security gate (engine-side)"
```

---

## Definition of Done (P0-A)

- [ ] `./gradlew :engine-core:test` verde, 0 dipendenze Android nel modulo.
- [ ] I 3 esempi dello spec passano lato engine con executor fake, **incluso** il percorso draft → validator → arm-resolution → fire.
- [ ] Tutte le interfacce che P0-B implementerà sono definite: `ActionExecutor` (con contratto `Submitted`), `AutomationStore`, `AuditSink`, `CapabilityProbe`, `Brain`.
- [ ] `CronSchedule`/`TimeSpecs` testati sui casi DST (gap e overlap Europe/Rome) — P0-B non contiene logica calendario.
- [ ] `DraftValidator` blocca i draft con tool vietati in `InvokeLlm` e le reply generative fuori da 1:1 whitelistate.
- [ ] Serializzazione round-trip stabile per l'intero schema automazioni (input al Room converter di P0-B).

## Handoff verso P0-B (prossimo piano)

P0-B implementa le interfacce su Android:
- `ShizukuActionExecutor` (device-tools): deterministiche sincrone; **`InvokeLlm` → accoda in lane async e ritorna `Submitted`** (l'esito della lane va all'`AuditSink`).
- `RoomAutomationStore` + `RoomAuditSink` (colonne JSON via `ArgusJson`; decode fallito → `NEEDS_REVIEW`, mai drop).
- `AlarmManagerTimeTrigger`: usa **`TimeSpecs.nextFire`** e emette `TriggerEvent.TimeFired`; ri-registra al boot.
- `NotificationListener` (P1): popola `conversationId`/`isGroup`/`notificationKey` (ricerca empirica: shortcutId/tag/EXTRA_PEOPLE_LIST — spec §16) e conserva la `StatusBarNotification` per il RemoteInput (fallback E13 se scaduta).
- `LazyDeviceStateProvider`: costruisce il `DeviceState` solo quando l'engine lo chiede (contratto `stateProvider`).
- Flusso approvazione: `CliBridgeParser` → **`DraftValidator`** (ERROR ⇒ `canArm=false`) → risoluzione `resolveCurrentLocation` → `ConflictDetector` → ARM.
- `AndroidCapabilityProbe` (manifest con whitelist `{nome, id}` reali).
- I contratti di stato UI per Claude Design derivano da questi tipi — vedi `handoff-frontend.md` nel bundle.
