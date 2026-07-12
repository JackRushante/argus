# Argus — Piano P0-A: Engine Core (Kotlin puro, device-independent)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Costruire l'intero motore di automazione di Argus come libreria **Kotlin/JVM pura** (nessuna dipendenza Android), con Shizuku/Room/AlarmManager dietro interfacce iniettate, testata al 100% con unit test veloci.

**Architecture:** Modulo Gradle `engine-core` (`kotlin("jvm")`). Modelli dominio serializzabili (kotlinx.serialization, polimorfismo sealed). Il motore riceve `TriggerEvent` + `DeviceState`, valuta condizioni contro un `Clock` iniettato, e dispatcha `Action` a un `ActionExecutor` iniettato (fake nei test; implementazione Shizuku in P0-B). Persistenza dietro `AutomationStore` (fake in-memory nei test; Room in P0-B). Il Brain (transport CliBridge) è rappresentato da un parser puro dell'output testo+JSON.

**Tech Stack:** Kotlin 2.x, kotlinx.serialization-json, JUnit5, kotlin-test, java.time. Nessun SDK Android in questo modulo.

**Scope:** Solo P0-A. Le implementazioni Android (`core-shizuku`, `device-tools`, Room, Time-trigger via AlarmManager, wiring) sono il piano **P0-B**. Riferimento spec: `docs/superpowers/specs/2026-07-12-hermes-android-agent-design.md` (rev 2), §5-§7, §10, §13.

**Package:** `dev.argus.engine`. Ogni file sotto `engine-core/src/main/kotlin/dev/argus/engine/`, test sotto `engine-core/src/test/kotlin/dev/argus/engine/`.

---

## File structure (decomposizione bloccata qui)

| File | Responsabilità |
|------|----------------|
| `model/Trigger.kt` | sealed `Trigger` + enum ausiliari (`Transition`, `PowerState`, `PhoneEvent`) |
| `model/Condition.kt` | sealed `Condition` (`TimeWindow`, `StateEquals`, `AppInForeground`, `LocationIn`, `And`, `Or`, `Not`) + `CmpOp` |
| `model/Action.kt` | sealed `Action` (deterministiche + `InvokeLlm`) + `DndMode`, `tier` |
| `model/Automation.kt` | `Automation`, `AutomationDraft`, `AutomationStatus`, `CreatedBy`, `AutomationId` |
| `model/Json.kt` | istanza `Json` condivisa con moduli polimorfici sealed |
| `runtime/DeviceState.kt` | `DeviceState`, `GeoPoint` |
| `runtime/TriggerEvent.kt` | sealed `TriggerEvent` (`TimeFired`, `NotificationPosted`, `PhoneStateChanged`, `ConnectivityChanged`, `GeofenceTransitioned`) |
| `runtime/ConditionEvaluator.kt` | valuta `Condition` contro `DeviceState` + `Clock` |
| `runtime/TriggerMatcher.kt` | verifica se un `TriggerEvent` soddisfa uno spec `Trigger` |
| `runtime/ActionExecutor.kt` | interfaccia `ActionExecutor`, `FireContext`, `ActionResult` |
| `runtime/AutomationStore.kt` | interfaccia `AutomationStore` (persistenza + cooldown) |
| `runtime/Engine.kt` | orchestrazione: match → cooldown → eval → dispatch (priorità) |
| `safety/ConflictDetector.kt` | euristica conflitti (§13/C1) |
| `brain/CliBridgeParser.kt` | parsing output Hermes (prosa + `@@META@@ {json}`) → `AutomationDraft` |
| `brain/CapabilityManifest.kt` | `CapabilityManifest`, `CapabilityProbe`, rendering per il prompt |

**Dipendenze tra task:** T0 → T1 → T2 → T3 → T4(store/executor) → T5(evaluator) → T6(matcher) → T7(engine) → T8(conflitti) → T9(parser) → T10(manifest). T8/T9/T10 dipendono solo dai modelli (T1-T3), quindi parallelizzabili dopo T3.

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
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
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
    @Test fun `notification trigger keeps discriminator`() {
        val t: Trigger = Trigger.Notification(pkg = "com.whatsapp", sender = "Moglie")
        val json = ArgusJson.encodeToString(Trigger.serializer(), t)
        assert(json.contains("\"type\":\"notification\"")) { json }
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
enum class PowerState { PLUGGED, UNPLUGGED }
enum class PhoneEvent { INCOMING_CALL, CALL_ENDED, SMS_RECEIVED }

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("geofence")
    data class Geofence(
        val lat: Double, val lng: Double, val radiusM: Double,
        val transition: Transition, val loiteringDelayMs: Long = 0,
    ) : Trigger

    @Serializable @SerialName("time")
    data class Time(val cron: String, val tz: String) : Trigger

    @Serializable @SerialName("notification")
    data class Notification(
        val pkg: String, val sender: String? = null,
        val titleMatch: String? = null, val textMatch: String? = null,
    ) : Trigger

    @Serializable @SerialName("phone_state")
    data class PhoneState(val event: PhoneEvent, val number: String? = null) : Trigger

    @Serializable @SerialName("connectivity")
    data class Connectivity(
        val wifiSsid: String? = null, val btDevice: String? = null,
        val power: PowerState? = null,
    ) : Trigger
}
```

- [ ] **Step 5: Esegui — deve passare**

Run: `./gradlew :engine-core:test --tests '*TriggerSerializationTest'`
Expected: PASS (2 test).

- [ ] **Step 6: Commit**

```bash
git add engine-core/src/main/kotlin/dev/argus/engine/model/Trigger.kt engine-core/src/main/kotlin/dev/argus/engine/model/Json.kt engine-core/src/test
git commit -m "feat(engine): Trigger domain model + serialization"
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

## Task 3: Modelli `Action` + `Automation`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Action.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/model/Automation.kt`
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
            trigger = Trigger.Notification(pkg = "com.whatsapp", sender = "id:42"),
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
        assertTrue(Action.InvokeLlm("g", listOf(), listOf(), true).tier == ActionTier.GENERATIVE)
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*AutomationSerializationTest'`
Expected: FAIL, unresolved `Action` / `Automation`.

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
    @Serializable @SerialName("tap") data class Tap(val x: Int, val y: Int) : Action
    @Serializable @SerialName("input_text") data class InputText(val text: String) : Action
    @Serializable @SerialName("whatsapp_reply") data class WhatsAppReply(val text: String) : Action
    @Serializable @SerialName("run_shell") data class RunShell(val cmd: String) : Action

    @Serializable @SerialName("invoke_llm")
    data class InvokeLlm(
        val goal: String,
        val contextSources: List<String>,
        val allowedTools: List<String>,
        val replyTargetSender: Boolean,   // §10: destinatario vincolato al trigger.sender
        val timeoutMs: Long = 60_000,
    ) : Action
}
```

- [ ] **Step 4: Scrivi `Automation.kt`**

```kotlin
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

- [ ] **Step 5: Esegui — deve passare**

Run: `./gradlew :engine-core:test --tests '*AutomationSerializationTest'`
Expected: PASS (2 test).

- [ ] **Step 6: Commit**

```bash
git add engine-core/src/main/kotlin/dev/argus/engine/model engine-core/src/test
git commit -m "feat(engine): Action + Automation models, tier classification"
```

---

## Task 4: `ActionExecutor` + `AutomationStore` (interfacce + fake)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/DeviceState.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/TriggerEvent.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/ActionExecutor.kt`
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/AutomationStore.kt`
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
    @Test fun `fake executor records calls`() = runTest {
        val ex = FakeActionExecutor()
        val ctx = FireContext(TriggerEvent.TimeFired(AutomationId("a1")), DeviceState(), AutomationId("a1"))
        val r = ex.execute(Action.SetWifi(false), ctx)
        assertEquals(ActionResult.Success, r)
        assertEquals(listOf<Action>(Action.SetWifi(false)), ex.executed)
    }
    @Test fun `fake store cooldown`() = runTest {
        val store = FakeAutomationStore()
        val id = AutomationId("a1")
        store.recordFired(id, 1_000)
        assertEquals(1_000, store.lastFiredAt(id))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*FakesTest'`
Expected: FAIL (tipi mancanti). Aggiungi `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")` e `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")` a `build.gradle.kts`.

- [ ] **Step 3: Scrivi `DeviceState.kt` e `TriggerEvent.kt`**

```kotlin
// DeviceState.kt
package dev.argus.engine.runtime
data class GeoPoint(val lat: Double, val lng: Double)
data class DeviceState(
    val values: Map<String, String> = emptyMap(),   // es. "ringer" -> "normal", "wifi" -> "on"
    val foregroundApp: String? = null,
    val location: GeoPoint? = null,
)
```

```kotlin
// TriggerEvent.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.PowerState
import dev.argus.engine.model.Transition
sealed interface TriggerEvent {
    /** Trigger schedulati per-automazione: portano l'id. */
    data class TimeFired(val automationId: AutomationId) : TriggerEvent
    data class GeofenceTransitioned(val automationId: AutomationId, val transition: Transition) : TriggerEvent
    /** Trigger broadcast: nessun id, richiedono match sullo spec. */
    data class NotificationPosted(val pkg: String, val sender: String?, val title: String?, val text: String?) : TriggerEvent
    data class PhoneStateChanged(val event: PhoneEvent, val number: String?) : TriggerEvent
    data class ConnectivityChanged(val wifiSsid: String?, val btDevice: String?, val power: PowerState?) : TriggerEvent
}
```

- [ ] **Step 4: Scrivi `ActionExecutor.kt` e `AutomationStore.kt`**

```kotlin
// ActionExecutor.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
data class FireContext(val event: TriggerEvent, val state: DeviceState, val automationId: AutomationId)
sealed interface ActionResult {
    data object Success : ActionResult
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

- [ ] **Step 5: Scrivi i fake (`Fakes.kt` in `src/test`)**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
class FakeActionExecutor(private val fail: Set<String> = emptySet()) : ActionExecutor {
    val executed = mutableListOf<Action>()
    override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
        executed += action
        val key = action::class.simpleName ?: ""
        return if (key in fail) ActionResult.Failure("forced") else ActionResult.Success
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
```

- [ ] **Step 6: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*FakesTest'` → PASS.

```bash
git add engine-core/
git commit -m "feat(engine): runtime interfaces (executor, store, events) + test fakes"
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
    @Test fun `notification matches package and sender`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", sender = "Moglie", textMatch = "ciao")
        val ev = TriggerEvent.NotificationPosted("com.whatsapp", "Moglie", null, "ciao amore")
        assertTrue(m.matches(spec, ev))
    }
    @Test fun `notification rejects wrong sender`() {
        val spec = Trigger.Notification(pkg = "com.whatsapp", sender = "Moglie")
        val ev = TriggerEvent.NotificationPosted("com.whatsapp", "Capo", null, "x")
        assertFalse(m.matches(spec, ev))
    }
    @Test fun `time matches by construction`() {
        assertTrue(m.matches(Trigger.Time("0 23 * * *", "Europe/Rome"), TriggerEvent.TimeFired(AutomationId("a1"))))
    }
    @Test fun `connectivity matches wifi ssid`() {
        assertTrue(m.matches(Trigger.Connectivity(wifiSsid = "Casa"), TriggerEvent.ConnectivityChanged("Casa", null, null)))
        assertFalse(m.matches(Trigger.Connectivity(wifiSsid = "Casa"), TriggerEvent.ConnectivityChanged("Bar", null, null)))
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
        spec is Trigger.Notification && event is TriggerEvent.NotificationPosted ->
            spec.pkg == event.pkg &&
                (spec.sender == null || spec.sender == event.sender) &&
                (spec.titleMatch == null || (event.title?.contains(spec.titleMatch) == true)) &&
                (spec.textMatch == null || (event.text?.contains(spec.textMatch) == true))
        spec is Trigger.PhoneState && event is TriggerEvent.PhoneStateChanged ->
            spec.event == event.event && (spec.number == null || spec.number == event.number)
        spec is Trigger.Connectivity && event is TriggerEvent.ConnectivityChanged ->
            (spec.wifiSsid == null || spec.wifiSsid == event.wifiSsid) &&
                (spec.btDevice == null || spec.btDevice == event.btDevice) &&
                (spec.power == null || spec.power == event.power)
        else -> false
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*TriggerMatcherTest'` → PASS (4 test).

```bash
git add engine-core/
git commit -m "feat(engine): TriggerMatcher (event vs spec)"
```

---

## Task 7: `Engine` (orchestrazione)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/runtime/Engine.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/runtime/EngineTest.kt`

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.runtime
import dev.argus.engine.model.*
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
class EngineTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private fun armed(id: String, t: Trigger, acts: List<Action>, cond: Condition? = null, cooldown: Long = 0, prio: Int = 0) =
        Automation(AutomationId(id), id, CreatedBy.LLM, AutomationStatus.ARMED, t, acts, cond, cooldownMs = cooldown, priority = prio)

    @Test fun `time trigger fires deterministic action when condition holds`() = runTest {
        val a = armed("a1", Trigger.Time("0 23 * * *", "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
            cond = Condition.StateEquals("ringer", CmpOp.NEQ, "silent"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T21:30:00Z")), TriggerMatcher()) { 1000 }
        engine.onTrigger(TriggerEvent.TimeFired(AutomationId("a1")), DeviceState(values = mapOf("ringer" to "normal")))
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }
    @Test fun `condition false skips actions`() = runTest {
        val a = armed("a1", Trigger.Time("x", "Europe/Rome"), listOf(Action.SetWifi(false)),
            cond = Condition.StateEquals("ringer", CmpOp.EQ, "silent"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T21:30:00Z")), TriggerMatcher()) { 1000 }
        engine.onTrigger(TriggerEvent.TimeFired(AutomationId("a1")), DeviceState(values = mapOf("ringer" to "normal")))
        assertEquals(emptyList<Action>(), ex.executed)
    }
    @Test fun `broadcast notification scans armed and matches`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp", sender = "Moglie"), listOf(Action.WhatsAppReply("ok")))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T10:00:00Z")), TriggerMatcher()) { 1000 }
        engine.onTrigger(TriggerEvent.NotificationPosted("com.whatsapp", "Moglie", null, "ciao"), DeviceState())
        assertEquals(1, ex.executed.size)
    }
    @Test fun `cooldown suppresses second fire`() = runTest {
        val a = armed("a1", Trigger.Notification("com.whatsapp", sender = null), listOf(Action.WhatsAppReply("ok")), cooldown = 5000)
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(a))
        var now = 1000L
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T10:00:00Z")), TriggerMatcher()) { now }
        val ev = TriggerEvent.NotificationPosted("com.whatsapp", null, null, "x")
        engine.onTrigger(ev, DeviceState()); now = 2000L; engine.onTrigger(ev, DeviceState())
        assertEquals(1, ex.executed.size) // secondo entro cooldown => soppresso
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
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationStatus

/** @param now fornitore di epoch-millis (iniettato per testabilità; su Android = System::currentTimeMillis). */
class Engine(
    private val store: AutomationStore,
    private val executor: ActionExecutor,
    private val evaluator: ConditionEvaluator,
    private val matcher: TriggerMatcher,
    private val now: () -> Long,
) {
    data class FireOutcome(val automation: Automation, val actions: List<Action>, val results: List<ActionResult>)

    suspend fun onTrigger(event: TriggerEvent, state: DeviceState): List<FireOutcome> {
        val candidates: List<Automation> = when (event) {
            is TriggerEvent.TimeFired -> listOfNotNull(store.get(event.automationId))
            is TriggerEvent.GeofenceTransitioned -> listOfNotNull(store.get(event.automationId))
            else -> store.armed()
        }.filter { it.status == AutomationStatus.ARMED && it.enabled }
            .sortedByDescending { it.priority }

        val outcomes = mutableListOf<FireOutcome>()
        for (a in candidates) {
            if (!matcher.matches(a.trigger, event)) continue
            if (!evaluator.eval(a.conditions, state)) continue
            if (a.cooldownMs > 0) {
                val last = store.lastFiredAt(a.id)
                if (last != null && now() - last < a.cooldownMs) continue
            }
            val ctx = FireContext(event, state, a.id)
            val results = a.actions.map { executor.execute(it, ctx) }
            store.recordFired(a.id, now())
            outcomes += FireOutcome(a, a.actions, results)
        }
        return outcomes
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*EngineTest'` → PASS (4 test).

```bash
git add engine-core/
git commit -m "feat(engine): Engine orchestration (match, cooldown, priority, dispatch)"
```

---

## Task 8: `ConflictDetector` (euristica §13/C1)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/safety/ConflictDetector.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/safety/ConflictDetectorTest.kt`

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.safety
import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class ConflictDetectorTest {
    private fun a(id: String, act: Action) = Automation(AutomationId(id), id, CreatedBy.LLM, AutomationStatus.ARMED,
        Trigger.Time("x", "Europe/Rome"), listOf(act))
    @Test fun `opposite wifi toggles conflict`() {
        val w = ConflictDetector().detect(listOf(a("1", Action.SetWifi(true)), a("2", Action.SetWifi(false))))
        assertTrue(w.any { it.targetKey == "wifi" })
    }
    @Test fun `same direction no conflict`() {
        assertEquals(emptyList(), ConflictDetector().detect(listOf(a("1", Action.SetWifi(true)), a("2", Action.SetWifi(true)))))
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

/** Euristica volutamente semplice (§13/C1): rileva azioni sullo STESSO target con valori opposti.
 *  NON è analisi statica completa dello spazio-trigger (indecidibile). Serve a segnalare, non a bloccare. */
class ConflictDetector {
    private data class Setting(val key: String, val value: String)

    private fun setting(a: Action): Setting? = when (a) {
        is Action.SetWifi -> Setting("wifi", a.on.toString())
        is Action.SetBluetooth -> Setting("bluetooth", a.on.toString())
        is Action.SetDnd -> Setting("dnd", a.mode.name)
        is Action.SetRinger -> Setting("ringer", a.mode)
        else -> null
    }

    fun detect(automations: List<Automation>): List<ConflictWarning> {
        val byKey = mutableMapOf<String, MutableMap<String, MutableList<AutomationId>>>()
        for (auto in automations) for (act in auto.actions) {
            val s = setting(act) ?: continue
            byKey.getOrPut(s.key) { mutableMapOf() }.getOrPut(s.value) { mutableListOf() }.add(auto.id)
        }
        return byKey.filterValues { it.size > 1 }.map { (key, byVal) ->
            ConflictWarning(key, byVal.values.flatten().distinct(),
                "Più automazioni impostano '$key' a valori diversi (${byVal.keys.joinToString()})")
        }
    }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*ConflictDetectorTest'` → PASS (2 test).

```bash
git add engine-core/
git commit -m "feat(engine): ConflictDetector heuristic (opposite settings on same target)"
```

---

## Task 9: `CliBridgeParser` (output Hermes → `AutomationDraft`)

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/brain/CliBridgeParser.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/brain/CliBridgeParserTest.kt`

Contesto (spec §2/§7): Hermes via CliBridge ritorna **prosa + riga `@@META@@ {json}`**. Per il `compile`, il JSON meta contiene `{"draft": {...AutomationDraft...}}`. Il parser deve essere robusto ad assenza/malformazione del blocco.

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
        assertEquals("Ho creato l'automazione per la suoneria.", r.reply.trim())
        val d = assertNotNull(r.draft)
        assertEquals("dnd sera", d.name)
        assertEquals(Trigger.Time("0 23 * * *", "Europe/Rome"), d.trigger)
    }
    @Test fun `no meta yields reply only`() {
        val r = p.parseCompile("Non ho capito, puoi ripetere?")
        assertEquals("Non ho capito, puoi ripetere?", r.reply.trim())
        assertNull(r.draft)
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

- [ ] **Step 3: Implementa `CliBridgeParser.kt`**

```kotlin
package dev.argus.engine.brain
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SENTINEL = "@@META@@"

@Serializable private data class MetaEnvelope(val draft: AutomationDraft? = null)

data class ParsedCompile(val reply: String, val draft: AutomationDraft?, val metaError: String?)

/** Parser puro dell'output CliBridge (spec §2/§7). Fail-soft: JSON malformato => reply + metaError, mai crash. */
class CliBridgeParser(private val json: Json = lenient()) {
    fun parseCompile(raw: String): ParsedCompile {
        if (!raw.contains(SENTINEL)) return ParsedCompile(raw, null, null)
        val idx = raw.indexOf(SENTINEL)
        val prose = raw.substring(0, idx)
        val tail = raw.substring(idx + SENTINEL.length)
        val obj = Regex("\\{.*}", RegexOption.DOT_MATCHES_ALL).find(tail)?.value
            ?: return ParsedCompile(prose, null, "no json object after sentinel")
        return try {
            ParsedCompile(prose, json.decodeFromString(MetaEnvelope.serializer(), obj).draft, null)
        } catch (e: Exception) {
            ParsedCompile(prose, null, e.message ?: "meta parse error")
        }
    }
    private companion object { fun lenient() = Json(ArgusJson) { isLenient = true; ignoreUnknownKeys = true } }
}
```

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :engine-core:test --tests '*CliBridgeParserTest'` → PASS (3 test).

```bash
git add engine-core/
git commit -m "feat(engine): CliBridgeParser (prose + @@META@@ json -> AutomationDraft, fail-soft)"
```

---

## Task 10: `CapabilityManifest` + `CapabilityProbe`

**Files:**
- Create: `engine-core/src/main/kotlin/dev/argus/engine/brain/CapabilityManifest.kt`
- Test: `engine-core/src/test/kotlin/dev/argus/engine/brain/CapabilityManifestTest.kt`

Contesto (spec §7): a inizio sessione il Brain riceve un manifest ("sa dove si trova e cosa può/non può fare"). `CapabilityProbe` è l'interfaccia (impl Android in P0-B); qui definiamo il tipo e il rendering testuale per il prompt.

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine.brain
import kotlin.test.Test
import kotlin.test.assertTrue
class CapabilityManifestTest {
    @Test fun `render lists device and disabled tools`() {
        val m = CapabilityManifest(
            deviceModel = "OnePlus 15", androidVersion = 16, shizukuAvailable = true,
            grantedPermissions = listOf("notification_listener"),
            availableTools = listOf("screen.capture", "toggle.set"),
            unavailableTools = mapOf("vision.analyze" to "nessun provider multimodale"),
            whitelistedContacts = listOf("Moglie"),
        )
        val s = m.render()
        assertTrue(s.contains("OnePlus 15"))
        assertTrue(s.contains("Android 16"))
        assertTrue(s.contains("vision.analyze") && s.contains("nessun provider multimodale"))
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :engine-core:test --tests '*CapabilityManifestTest'`
Expected: FAIL, unresolved `CapabilityManifest`.

- [ ] **Step 3: Implementa `CapabilityManifest.kt`**

```kotlin
package dev.argus.engine.brain
import dev.argus.engine.runtime.DeviceState

data class CapabilityManifest(
    val deviceModel: String,
    val androidVersion: Int,
    val shizukuAvailable: Boolean,
    val grantedPermissions: List<String>,
    val availableTools: List<String>,
    val unavailableTools: Map<String, String>,   // tool -> motivo
    val whitelistedContacts: List<String>,
) {
    fun render(): String = buildString {
        appendLine("DISPOSITIVO: $deviceModel, Android $androidVersion")
        appendLine("SHIZUKU: ${if (shizukuAvailable) "attivo (privilegi shell)" else "NON attivo — azioni shell in coda"}")
        appendLine("PERMESSI: ${grantedPermissions.joinToString().ifEmpty { "nessuno" }}")
        appendLine("TOOL DISPONIBILI: ${availableTools.joinToString()}")
        if (unavailableTools.isNotEmpty())
            appendLine("TOOL NON DISPONIBILI: " + unavailableTools.entries.joinToString { "${it.key} (${it.value})" })
        appendLine("CONTATTI WHITELIST: ${whitelistedContacts.joinToString().ifEmpty { "nessuno" }}")
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
git commit -m "feat(engine): CapabilityManifest + CapabilityProbe interface + prompt rendering"
```

---

## Task 11: Test di integrazione end-to-end (i 3 esempi, engine-side)

**Files:**
- Test: `engine-core/src/test/kotlin/dev/argus/engine/EndToEndTest.kt`

Verifica che i pezzi compongano i comportamenti dei 3 esempi (spec §11) **lato engine** (executor fake). Nessun nuovo codice di produzione.

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.engine
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.model.*
import dev.argus.engine.runtime.*
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
class EndToEndTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test fun `example2 - compile then fire DND after 23`() = runTest {
        // 1) compile: output Hermes -> draft
        val raw = "Fatto.\n@@META@@ {\"draft\":{\"name\":\"dnd\",\"trigger\":{\"type\":\"time\",\"cron\":\"0 23 * * *\",\"tz\":\"Europe/Rome\"}," +
            "\"conditions\":{\"type\":\"state_equals\",\"key\":\"ringer\",\"op\":\"NEQ\",\"value\":\"silent\"}," +
            "\"actions\":[{\"type\":\"set_dnd\",\"mode\":\"PRIORITY\"}]}}"
        val draft = assertNotNull(CliBridgeParser().parseCompile(raw).draft)
        // 2) approva -> Automation ARMED
        val auto = Automation(AutomationId("a1"), draft.name, CreatedBy.LLM, AutomationStatus.ARMED,
            draft.trigger, draft.actions, draft.conditions)
        // 3) fire alle 23:30 con suoneria "normal"
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T21:30:00Z")), TriggerMatcher()) { 1000 }
        engine.onTrigger(TriggerEvent.TimeFired(AutomationId("a1")), DeviceState(values = mapOf("ringer" to "normal")))
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }

    @Test fun `example1 - geofence exit toggles wifi off bt on`() = runTest {
        val auto = Automation(AutomationId("g1"), "geo", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Geofence(45.4, 11.0, 50.0, Transition.EXIT),
            listOf(Action.SetWifi(false), Action.SetBluetooth(true)))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T10:00:00Z")), TriggerMatcher()) { 1000 }
        engine.onTrigger(TriggerEvent.GeofenceTransitioned(AutomationId("g1"), Transition.EXIT), DeviceState())
        assertEquals(listOf<Action>(Action.SetWifi(false), Action.SetBluetooth(true)), ex.executed)
    }

    @Test fun `example3 - whatsapp from Moglie in window triggers generative reply`() = runTest {
        val auto = Automation(AutomationId("w1"), "reply", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Notification("com.whatsapp", sender = "id:42"),
            listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), replyTargetSender = true)),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"))
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        val engine = Engine(store, ex, ConditionEvaluator(clock("2026-07-12T18:30:00Z")), TriggerMatcher()) { 1000 }
        engine.onTrigger(TriggerEvent.NotificationPosted("com.whatsapp", "id:42", null, "ciao"), DeviceState())
        assertEquals(1, ex.executed.size)
        assertEquals(ActionTier.GENERATIVE, ex.executed.first().tier)
    }
}
```

- [ ] **Step 2: Esegui — deve passare (tutto già implementato)**

Run: `./gradlew :engine-core:test --tests '*EndToEndTest'`
Expected: PASS (3 test). Se `example2` fallisce sul parsing, verifica che `MetaEnvelope`/`AutomationDraft` accettino `conditions` polimorfico (già coperto da `ArgusJson`).

- [ ] **Step 3: Esegui l'intera suite**

Run: `./gradlew :engine-core:test`
Expected: BUILD SUCCESSFUL, tutti i test verdi.

- [ ] **Step 4: Commit**

```bash
git add engine-core/
git commit -m "test(engine): end-to-end coverage of the 3 spec examples (engine-side)"
```

---

## Definition of Done (P0-A)

- [ ] `./gradlew :engine-core:test` verde, 0 dipendenze Android nel modulo.
- [ ] I 3 esempi dello spec passano lato engine con executor fake.
- [ ] Tutte le interfacce che P0-B implementerà sono definite: `ActionExecutor`, `AutomationStore`, `CapabilityProbe`.
- [ ] Serializzazione round-trip stabile per l'intero schema automazioni (input al Room converter di P0-B).

## Handoff verso P0-B (prossimo piano)

P0-B implementa le interfacce su Android: `ShizukuActionExecutor` (device-tools), `RoomAutomationStore`, `AlarmManagerTimeTrigger` che emette `TriggerEvent.TimeFired`, `CliBridgeBrain` (HTTP verso il bridge Hermes) che usa `CliBridgeParser`, `AndroidCapabilityProbe`, e il wiring nel `foreground-service`. I contratti di stato UI per Claude Design derivano da questi tipi (es. `AutomationDetailState` costruito da `Automation` + `ConflictDetector`).
