# Argus — Piano M2: UI Compose (modulo `ui` + app demo)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementare i 6 schermi di Argus in Jetpack Compose Material 3 (dark, italiano) come composable **stateless** cablati sui contratti di stato dell'handoff, con `@Preview` per ogni stato e un modulo `app` demo che li monta su fixture finte producendo un APK installabile.

**Architecture:** Modulo Gradle `ui` (Android library, Compose M3) che dipende da `engine-core` **solo per i tipi** (`Severity`, `AuditKind`, `ValidationIssue`, e i modelli dominio per il mapper). Ogni schermo è `fun XxxScreen(state: XxxState, callbacks: XxxCallbacks)` — nessun ViewModel, networking o DB nel modulo. Un `presentation/RuleRenderMapper` (logica pura testabile) traduce i tipi `engine-core` nei contratti di view. Il modulo `app` fornisce `NavHost`, fixture e `MainActivity` per l'APK demo.

**Tech Stack:** Kotlin 2.1.0, Android Gradle Plugin 8.7.x, Jetpack Compose (BOM 2024.12.01), Material 3, `material-icons-extended`, Navigation Compose, JUnit5 (unit test mapper), min SDK 30 / target SDK 35.

## Global Constraints

_(Da `spec …-hermes-android-agent-design.md`, `…-argus-handoff-frontend.md`, `docs/design/README.md` + `CLAUDE-CODE-TIPS.md`. Valgono per OGNI task.)_

- **Lingua UI: italiano.** Il copy di sicurezza (warning, consensi, "esce verso il cloud", stati Shizuku) va preso **letterale** dal design handoff (`docs/design/README.md` §7, `handoff-frontend.md` §9) — mai parafrasato.
- **Material 3, dark default** + light supportato. Accento blu M3.
- **Colori/typography SOLO da token del tema** (`docs/design/README.md` §5), mai hardcoded nei composable. Colori semantici (armed/pending/error/needs_review/generative/cloud) come estensione del tema.
- **Font:** Roboto (300/400/500/700) per il testo, `FontFamily.Monospace` per comandi shell/cron/id tecnici, `Icons.Rounded.*` per le icone (mappa nomi in `docs/design/README.md` §9).
- **Touch target ≥ 48dp**; contrasto AA. `accent/primary #9ecaff` porta testo scuro `#003257`, mai bianco.
- **Invarianti di sicurezza UI (vincolanti, `handoff-frontend.md` §5):** (1) la regola si mostra da `RuleRender` (dai tipi), mai dalla prosa LLM (`rationale` = commento subordinato); (2) `Severity.ERROR` ⇒ `canArm=false`, bottone Arma disabilitato con motivo; (3) warning **sopra la fold**, non collassati; (4) `run_shell` sempre monospace, integrale, scroll orizzontale, mai troncato; (5) badge "generativa" + "esce verso il cloud" ovunque appaia una regola generativa; (6) nessun bottone di conferma pre-focused su azioni irreversibili.
- **Stateless:** nessuna dipendenza oltre Compose/Material3/engine-core-types; callback come interfacce; ogni schermo ha `@Preview` per stato pieno, empty, error, degradato.
- **Fonti da leggere prima di ogni task UI:** `handoff-frontend.md` (contratti §6, componenti §8, microcopy §9), `docs/design/README.md` (token §5, schermi §7, icone §9), lo screenshot pertinente in `docs/design/screenshots/`.

---

## File structure

| File | Responsabilità |
|------|----------------|
| `ui/build.gradle.kts` | Android library + Compose |
| `ui/src/main/AndroidManifest.xml` | manifest vuoto della library |
| `ui/src/main/kotlin/dev/argus/ui/theme/Color.kt` | token colore (dark + light) da design §5 |
| `ui/src/main/kotlin/dev/argus/ui/theme/SemanticColors.kt` | colori semantici come `CompositionLocal` + extension |
| `ui/src/main/kotlin/dev/argus/ui/theme/Type.kt` | scala tipografica (design §5.4) |
| `ui/src/main/kotlin/dev/argus/ui/theme/Theme.kt` | `ArgusTheme { }` — ColorScheme M3 + Typography + semantic provider |
| `ui/src/main/kotlin/dev/argus/ui/model/UiContracts.kt` | tutti i contratti di stato/callback dell'handoff §6 (tipi condivisi + per-schermo) |
| `ui/src/main/kotlin/dev/argus/ui/model/IconKeys.kt` | mappa `iconKey`(String) → `ImageVector` (design §9) |
| `ui/src/main/kotlin/dev/argus/ui/components/*.kt` | componenti condivisi (handoff §8) |
| `ui/src/main/kotlin/dev/argus/ui/screens/*.kt` | 6 schermi stateless |
| `ui/src/main/kotlin/dev/argus/ui/presentation/RuleRenderMapper.kt` | `Automation` (engine) → `RuleRender` (view), logica pura |
| `ui/src/test/kotlin/dev/argus/ui/presentation/RuleRenderMapperTest.kt` | test del mapper (JVM puro) |
| `ui/src/main/kotlin/dev/argus/ui/preview/Fixtures.kt` | dati finti realistici per i `@Preview` e la demo |
| `app/build.gradle.kts` | application module `dev.argus` |
| `app/src/main/AndroidManifest.xml` | manifest app + `MainActivity` |
| `app/src/main/kotlin/dev/argus/MainActivity.kt` | host Compose |
| `app/src/main/kotlin/dev/argus/nav/ArgusNavHost.kt` | bottom nav + push Dettaglio/Onboarding, cablati su fixture |

**Dipendenze task:** T0 → T1 → T2 → T3 → (T4 mapper) → T5 componenti → **T6-T11 schermi (parallelizzabili)** → T12 app demo → T13 APK.

---

## Task 0: Scaffold moduli Android (`ui` library + Android plugin)

**Files:**
- Modify: `settings.gradle.kts` (aggiungi `ui`, `app` + pluginManagement)
- Create: `gradle/libs.versions.toml`
- Create: `ui/build.gradle.kts`
- Create: `ui/src/main/AndroidManifest.xml`
- Test: `ui/src/androidTest` non necessario; smoke via compile

**Interfaces:**
- Produces: modulo `ui` compilabile con Compose; version catalog `libs`.

- [ ] **Step 1: Version catalog `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
composeBom = "2024.12.01"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
navigationCompose = "2.8.5"
material3 = "1.3.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Aggiorna `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { google(); mavenCentral() }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "argus"
include("engine-core", "ui", "app")
```

- [ ] **Step 3: `ui/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.argus.ui"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":engine-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.withType<Test> { useJUnitPlatform() }
```

> Nota: `engine-core/build.gradle.kts` resta `kotlin("jvm")`; l'`android`-library può dipendere da un modulo JVM puro senza problemi.

- [ ] **Step 4: `ui/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 5: Compila**

Run: `./gradlew :ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (modulo vuoto, nessun sorgente ancora).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml ui/
git commit -m "chore(ui): scaffold Android Compose library module + version catalog"
```

---

## Task 1: Tema — colori (dark + light)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/theme/Color.kt`
- Create: `ui/src/main/kotlin/dev/argus/ui/theme/SemanticColors.kt`

**Interfaces:**
- Produces: `ArgusDarkColors`/`ArgusLightColors` (M3 `ColorScheme`); `ArgusSemantic` (data class) + `LocalArgusSemantic` (`staticCompositionLocalOf`); helper `semanticFor(status)`/`severityColors(sev)`.

- [ ] **Step 1: `Color.kt`** — porta i token di `docs/design/README.md` §5.1-5.3 (dark) e §11 `1f` (light). Definisci `Color(0xFF…)` per ogni token e assembla due `ColorScheme`:

```kotlin
package dev.argus.ui.theme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// --- superfici dark (design §5.1) ---
val SurfaceBase = Color(0xFF0E1216)
val Surface1 = Color(0xFF161B21)
val Surface2 = Color(0xFF1A1F27)
val SurfaceNav = Color(0xFF151A20)
val OutlineWeak = Color(0xFF242B33)
val OutlineStrong = Color(0xFF2F3944)
// --- testo (design §5.2) ---
val TextPrimary = Color(0xFFEEF1F5)
val TextBody = Color(0xFFE3E6EB)
val TextMuted = Color(0xFF9AA2AD)
val TextFaint = Color(0xFF6F7883)
// --- accento (design §5.3) ---
val AccentPrimary = Color(0xFF9ECAFF)
val AccentOn = Color(0xFF003257)
val AccentContainer = Color(0xFF00497D)
val OnAccentContainer = Color(0xFFD3E4FF)

val ArgusDarkColors = darkColorScheme(
    primary = AccentPrimary, onPrimary = AccentOn,
    primaryContainer = AccentContainer, onPrimaryContainer = OnAccentContainer,
    background = SurfaceBase, onBackground = TextBody,
    surface = SurfaceBase, onSurface = TextBody,
    surfaceVariant = Surface1, onSurfaceVariant = TextMuted,
    outline = OutlineStrong, outlineVariant = OutlineWeak,
)
// Light: derivata dal riferimento §11 1f (Material 3 light standard con stesso accento).
val ArgusLightColors = lightColorScheme(primary = Color(0xFF0061A4), onPrimary = Color.White)
```

- [ ] **Step 2: `SemanticColors.kt`** — colori di stato (design §5.3) come struttura fuori dal `ColorScheme` M3:

```kotlin
package dev.argus.ui.theme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class RolePair(val fg: Color, val bg: Color)

data class ArgusSemantic(
    val armed: RolePair, val pending: RolePair, val error: RolePair,
    val needsReview: RolePair, val disabled: RolePair,
    val generative: RolePair, val cloud: RolePair, val codeText: Color,
)

val ArgusSemanticDark = ArgusSemantic(
    armed = RolePair(Color(0xFF7FE0A0), Color(0xFF123A29)),
    pending = RolePair(Color(0xFFFFCF7A), Color(0xFF4A3300)),
    error = RolePair(Color(0xFFFFB4AB), Color(0xFF2E0F0B)),
    needsReview = RolePair(Color(0xFFFFB59D), Color(0xFF4D1C12)),
    disabled = RolePair(Color(0xFFA7ADB5), Color(0xFF2A2D31)),
    generative = RolePair(Color(0xFFD4BBFF), Color(0xFF372A4D)),
    cloud = RolePair(Color(0xFFFFB787), Color(0xFF3D2A17)),
    codeText = Color(0xFFA6E3B8),
)
val LocalArgusSemantic = staticCompositionLocalOf { ArgusSemanticDark }
```

- [ ] **Step 3: Compila**

Run: `./gradlew :ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/theme/Color.kt ui/src/main/kotlin/dev/argus/ui/theme/SemanticColors.kt
git commit -m "feat(ui): color tokens (dark+light) and semantic status colors"
```

---

## Task 2: Tema — tipografia + `ArgusTheme`

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/theme/Type.kt`
- Create: `ui/src/main/kotlin/dev/argus/ui/theme/Theme.kt`

**Interfaces:**
- Consumes: `ArgusDarkColors`, `ArgusLightColors`, `ArgusSemanticDark`, `LocalArgusSemantic`.
- Produces: `ArgusTheme(darkTheme: Boolean = true, content: @Composable () -> Unit)`; `ArgusType` (`Typography`); accessor `MaterialTheme` + `LocalArgusSemantic.current`.

- [ ] **Step 1: `Type.kt`** — scala di `docs/design/README.md` §5.4 (titolo schermo 22/400, voce lista 14.5/500, corpo 14/400, label uppercase 11/600 ls .12em, badge 10.5, timestamp 11). Usa `FontFamily.Default` (Roboto di sistema).

```kotlin
package dev.argus.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val ArgusType = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.W400, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.W500, fontSize = 14.5f.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = 0.12.em),
)
```

- [ ] **Step 2: `Theme.kt`**

```kotlin
package dev.argus.ui.theme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val ArgusShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun ArgusTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) ArgusDarkColors else ArgusLightColors
    CompositionLocalProvider(LocalArgusSemantic provides ArgusSemanticDark) {
        MaterialTheme(colorScheme = colors, typography = ArgusType, shapes = ArgusShapes, content = content)
    }
}
```

- [ ] **Step 3: Compila**

Run: `./gradlew :ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/theme/
git commit -m "feat(ui): typography scale + ArgusTheme (M3 dark/light, shapes, semantic provider)"
```

---

## Task 3: Contratti di stato UI + mappa icone

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/model/UiContracts.kt`
- Create: `ui/src/main/kotlin/dev/argus/ui/model/IconKeys.kt`

**Interfaces:**
- Consumes: `dev.argus.engine.safety.Severity`, `dev.argus.engine.runtime.AuditKind`.
- Produces: **tutti** i tipi di `handoff-frontend.md` §6 — tipi condivisi (`RuleRender`, `ActionRow`, `UiWarning`, `StatusBadge`, `EngineBanner`, `ShizukuStatus`), `ChatState`+`ChatItem`+`ChatError`+`ChatCallbacks`, `AutomationListState`+`AutomationRow`+`StatusFilter`+callbacks, `AutomationDetailState`+callbacks, `ExecutionLogState`+`LogRow`+`AuditKind`(uso)+`LogOutcome`+callbacks, `SettingsState`+`TransportUi`+`ContactRow`+`BudgetUi`+callbacks, `OnboardingState`+`OnboardingStepState`+`StepKind`+`StepStatus`+callbacks; `iconFor(key: String): ImageVector`.

- [ ] **Step 1: `UiContracts.kt`** — **copia verbatim** i data class/enum/sealed/interface da `handoff-frontend.md` §6 (Tipi condivisi + §6.1–6.6). Sono già Kotlin completo e coerente. Un solo adattamento: `Severity` e `AuditKind` importati da `engine-core` (`import dev.argus.engine.safety.Severity`, `import dev.argus.engine.runtime.AuditKind`). Tutto sotto `package dev.argus.ui.model`.

- [ ] **Step 2: `IconKeys.kt`** — mappa gli `iconKey`/`triggerIconKey` (stringhe usate nei contratti: `"notification"`,`"time"`,`"geofence"`,`"phone"`,`"connectivity"`, + azioni `"wifi_off"`,`"bluetooth"`,`"dnd"`,`"shell"`,`"generative"`,`"notify"`) sui `ImageVector` di `Icons.Rounded.*` seguendo la mappa `docs/design/README.md` §9. Dove l'esatto manca, scegli il più vicino e annota `// TODO icon:`.

```kotlin
package dev.argus.ui.model
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

fun iconFor(key: String): ImageVector = when (key) {
    "notification" -> Icons.Rounded.Notifications
    "time" -> Icons.Rounded.Schedule
    "geofence" -> Icons.Rounded.MyLocation
    "phone" -> Icons.Rounded.Call
    "connectivity" -> Icons.Rounded.Wifi
    "wifi_off" -> Icons.Rounded.WifiOff
    "bluetooth" -> Icons.Rounded.Bluetooth
    "dnd" -> Icons.Rounded.DoNotDisturbOn
    "shell" -> Icons.Rounded.Terminal
    "generative" -> Icons.Rounded.SmartToy
    "notify" -> Icons.Rounded.NotificationsActive
    "cloud" -> Icons.Rounded.CloudUpload
    else -> Icons.Rounded.Bolt
}
```

- [ ] **Step 3: Compila**

Run: `./gradlew :ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/model/
git commit -m "feat(ui): view-layer state contracts (handoff §6) + icon key mapping"
```

---

## Task 4: `RuleRenderMapper` (engine → view) con test

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/presentation/RuleRenderMapper.kt`
- Test: `ui/src/test/kotlin/dev/argus/ui/presentation/RuleRenderMapperTest.kt`

**Interfaces:**
- Consumes: `dev.argus.engine.model.*` (`Automation`, `Trigger`, `Condition`, `Action`), contratti `RuleRender`/`ActionRow`.
- Produces: `object RuleRenderMapper { fun map(a: Automation): RuleRender; fun mapDraft(d: AutomationDraft): RuleRender }`.

Questo è il pezzo di presentazione **testabile a unità** (JVM): traduce i tipi dominio nelle righe italiane deterministiche mostrate in approvazione (direttiva sicurezza §5.1 — la view non deve mai derivare la regola dalla prosa LLM).

- [ ] **Step 1: Scrivi il test**

```kotlin
package dev.argus.ui.presentation
import dev.argus.engine.model.*
import dev.argus.ui.model.RuleRender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class RuleRenderMapperTest {
    @Test fun `notification 1-1 generative rule renders trigger, condition, generative flag`() {
        val a = Automation(
            AutomationId("w1"), "Rispondi a Moglie", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", sender = "Moglie", isGroup = false),
            listOf(Action.InvokeLlm("rispondi nel tono X", listOf("notification"), listOf("whatsapp_reply"), true)),
            conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome"),
        )
        val r: RuleRender = RuleRenderMapper.map(a)
        assertEquals("notification", r.triggerIconKey)
        assertTrue(r.triggerLine.contains("WhatsApp") && r.triggerLine.contains("Moglie"))
        assertTrue(r.conditionLines.any { it.contains("18:00") && it.contains("22:00") })
        assertTrue(r.isGenerative)
        assertTrue(r.actions.single().isGenerative)
        assertTrue(r.privacyNote != null)
    }
    @Test fun `time DND rule renders cron humanized and deterministic action`() {
        val a = Automation(
            AutomationId("d1"), "DND notte", CreatedBy.LLM, AutomationStatus.ARMED,
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val r = RuleRenderMapper.map(a)
        assertEquals("time", r.triggerIconKey)
        assertTrue(r.triggerLine.contains("23:00"))
        assertTrue(!r.isGenerative && r.privacyNote == null)
        assertEquals(1, r.actions.size)
    }
    @Test fun `shell action is flagged and command preserved`() {
        val a = Automation(
            AutomationId("s1"), "Backup", CreatedBy.LLM, AutomationStatus.PENDING_APPROVAL,
            Trigger.Time(cron = "0 3 * * *", tz = "Europe/Rome"),
            listOf(Action.RunShell("cp -r /sdcard/DCIM /sdcard/backup")),
        )
        val row = RuleRenderMapper.map(a).actions.single()
        assertTrue(row.isShell)
        assertEquals("cp -r /sdcard/DCIM /sdcard/backup", row.shellCommand)
    }
}
```

- [ ] **Step 2: Esegui — deve fallire**

Run: `./gradlew :ui:testDebugUnitTest --tests '*RuleRenderMapperTest'`
Expected: FAIL, unresolved `RuleRenderMapper`.

- [ ] **Step 3: Implementa `RuleRenderMapper.kt`**

Traduci ogni `Trigger`/`Condition`/`Action` in stringhe italiane. Regole di rendering (dai contratti §6 e microcopy):
- `Trigger.Notification` → `"Quando: notifica <appLabel(pkg)> da <sender/conversationId> (<chat 1:1 | gruppo>)"`, iconKey `"notification"`.
- `Trigger.Time(cron)` → umanizza i cron comuni (`0 23 * * *` → `"Ogni giorno alle 23:00 (Europe/Rome)"`); fallback `"Cron '<expr>' (<tz>)"`. `Trigger.Time(at)` → `"Una volta il <at> (<tz>)"`. iconKey `"time"`.
- `Trigger.Geofence` → `resolveCurrentLocation` ? `"Quando esci dalla posizione attuale (±<r> m)"` : `"Quando <enter/exit/dwell> da <lat,lng> (±<r> m)"`. iconKey `"geofence"`.
- `Condition` → appiattisci l'albero And/Or/Not in righe indentate; `TimeWindow` → `"Solo tra le <start> e le <end> (<tz>)"`; `StateEquals` → `"Solo se <key> <op leggibile> <value>"`.
- `Action` → `ActionRow(iconKey, label, detail?, isShell, shellCommand?, isGenerative, requiresLiveConfirm)`:
  - `SetWifi(on)` → label `"Attiva/Disattiva Wi-Fi"`, iconKey `"wifi_off"`.
  - `SetDnd(mode)` → `"Attiva Non disturbare (<mode>)"`, iconKey `"dnd"`.
  - `RunShell(cmd)` → label `"Esegui comando shell"`, `isShell=true`, `shellCommand=cmd`, iconKey `"shell"`.
  - `InvokeLlm` → label `"Rispondi con l'AI"`, `detail="Obiettivo: <goal> · tool: <allowedTools>"`, `isGenerative=true`, iconKey `"generative"`.
  - `ShowNotification` → `"Mostra notifica"`, detail = testo, iconKey `"notify"`.
- `isGenerative` = qualunque azione ha `tier==GENERATIVE`. Se generativa, `privacyNote = "Il testo delle notifiche verrà inviato a Hermes e ai provider cloud per generare la risposta."` (letterale da design §7.3).

Implementa con `when` esaustivi. Nessun accesso a `Context`/risorse (test JVM puro): stringhe inline italiane. Fornisci un `appLabel(pkg)` minimale (`com.whatsapp`→`"WhatsApp"`, else pkg).

- [ ] **Step 4: Esegui — deve passare, poi commit**

Run: `./gradlew :ui:testDebugUnitTest --tests '*RuleRenderMapperTest'` → PASS (3 test).

```bash
git add ui/src/main/kotlin/dev/argus/ui/presentation ui/src/test
git commit -m "feat(ui): RuleRenderMapper (deterministic engine->view rule rendering) + tests"
```

---

## Task 5: Componenti condivisi (handoff §8)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/components/Badges.kt` (`StatusBadgeChip`, `GenerativeTag`, `CloudTag`)
- Create: `ui/src/main/kotlin/dev/argus/ui/components/RuleCard.kt` (`RuleCard`, `ActionRowItem`, `ShellCommandBlock`)
- Create: `ui/src/main/kotlin/dev/argus/ui/components/Banners.kt` (`WarningBanner`, `EngineBannerBar`, `LatencyIndicator`, `EmptyState`)

**Interfaces:**
- Consumes: contratti §6, `LocalArgusSemantic`, `iconFor`.
- Produces: i composable riusabili citati in `handoff-frontend.md` §8, ognuno con `@Preview`.

- [ ] **Step 1: `Badges.kt`** — `StatusBadgeChip(status: StatusBadge)` centralizza colore+icona+testo dalla tabella semantica (ARMED verde/`shield`, PENDING ambra/`pending`, DISABLED grigio/`pause_circle`, NEEDS_REVIEW rosso/`sync_problem`). `GenerativeTag()` viola `smart_toy`+"generativa"; `CloudTag()` ambra `cloud_upload`+"esce verso il cloud". Colori da `LocalArgusSemantic.current`. `@Preview` con i 4 stati affiancati.

- [ ] **Step 2: `RuleCard.kt`**
  - `ActionRowItem(row: ActionRow)`: icona `iconFor(row.iconKey)` + label; se `row.isGenerative` mostra `GenerativeTag`; se `row.requiresLiveConfirm` mostra chip "conferma live"; se `row.isShell` sotto la riga rende `ShellCommandBlock(row.shellCommand!!)`.
  - `ShellCommandBlock(cmd)`: superficie scura (`#0C0F13`), label "esegue comandi con privilegi shell (UID 2000)", comando in `FontFamily.Monospace` con `Modifier.horizontalScroll` (mai a capo/troncato — invariante §5.4). Testo colore `semantic.codeText`.
  - `RuleCard(rule: RuleRender, compact: Boolean)`: compatta (chat/lista) = trigger line + chip azioni + badge; estesa (dettaglio) = blocchi QUANDO/SOLO SE/ALLORA con label uppercase, ogni azione via `ActionRowItem`. Se `rule.isGenerative` badge generativa+cloud in testa.
  - `@Preview` per: regola deterministica, regola generativa, regola con shell.

- [ ] **Step 3: `Banners.kt`**
  - `WarningBanner(w: UiWarning)`: ERROR = rosso bloccante (icona `error`), WARNING/PRIVACY = ambra. Testo `w.text`.
  - `EngineBannerBar(banner: EngineBanner)`: barra persistente tappabile; mappa i valori (`SHIZUKU_DOWN`→"Shizuku non attivo — azioni shell in pausa", ecc., copy §9) — `NONE` non rende nulla.
  - `LatencyIndicator(elapsedSec, expectedRangeLabel, onCancel)`: spinner + "Argus sta pensando… (Ns)" + "di solito 10-30 s" + bottone **Annulla** (handoff §4).
  - `EmptyState(icon, title, body, cta)`.
  - `@Preview` per ciascuno.

- [ ] **Step 4: Compila**

Run: `./gradlew :ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/components/
git commit -m "feat(ui): shared components (badges, RuleCard+shell block, banners, latency, empty)"
```

---

## Task 6: Schermo Dettaglio / Approvazione (§6.3, cardine)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/screens/AutomationDetailScreen.kt`

**Interfaces:**
- Consumes: `AutomationDetailState`, `AutomationDetailCallbacks`, `RuleCard`, `WarningBanner`, `StatusBadgeChip`.
- Produces: `fun AutomationDetailScreen(state, callbacks)`.

Riferimento visivo: `docs/design/screenshots/03-dettaglio.png`. È lo schermo dove gli invarianti di sicurezza sono più critici.

- [ ] **Step 1: Implementa** seguendo `handoff-frontend.md` §6.3 e design §7.3:
  - Header: back + tipo ("Approvazione" se PENDING, "Dettaglio" altrimenti) → nome + riga badge (status via `StatusBadgeChip` + generativa + cloud se `rule.isGenerative`).
  - **Warning SOPRA la fold** (prima del RuleRender): `state.warnings.forEach { WarningBanner(it) }`.
  - Corpo: `RuleCard(state.rule, compact=false)` (blocchi QUANDO/SOLO SE/ALLORA).
  - Se generativa: riga `estimatedLlmCallsPerDay`.
  - `rationale` reso come **citazione subordinata** (bordo sinistro, label "descrizione del modello") — mai fonte di verità.
  - `geofencePreviewLabel` al posto delle coordinate quando valorizzato.
  - `recentRuns` (ultime 3-5) come righe compatte.
  - Footer per stato:
    - PENDING: `[Rifiuta] [Modifica] [Arma]` — Arma abilitato **solo** se `state.canArm`; se disabilitato mostra `armBlockedReason`.
    - ARMED/DISABLED: `[elimina] [Modifica in chat] [Esegui ora]` (delete e run-now senza pre-focus, §5.6).
    - NEEDS_REVIEW: `[Ricrea in chat]` full-width.

- [ ] **Step 2: `@Preview`** — PENDING pulita (canArm=true), PENDING con ERROR bloccante (canArm=false, armBlockedReason valorizzato, regola generativa che chiede shell), ARMED, NEEDS_REVIEW. Usa `Fixtures` (Task 12 li centralizza; qui definisci preview-data inline se Fixtures non esiste ancora).

- [ ] **Step 3: Compila**

Run: `./gradlew :ui:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/screens/AutomationDetailScreen.kt
git commit -m "feat(ui): AutomationDetail/Approval screen (warnings-above-fold, ERROR blocks arm)"
```

---

## Task 7: Schermo Chat (§6.1)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/screens/ChatScreen.kt`

**Interfaces:**
- Consumes: `ChatState`, `ChatCallbacks`, `RuleCard`, `LatencyIndicator`, `EmptyState`.
- Produces: `fun ChatScreen(state, callbacks)`.

Riferimento: `docs/design/screenshots/01-chat.png`, `handoff-frontend.md` §6.1 + §4 (no streaming).

- [ ] **Step 1: Implementa**: header "Argus / chat · compilatore di regole" + overflow; lista messaggi (`UserMessage` bolla `primaryContainer` a destra r18 angolo 4; `AssistantMessage` `surface2` a sinistra; `SystemNotice` banner centrato info/error; `DraftCard` = `RuleCard(compact=true)` + footer stato + CTA "Rivedi e approva →" che chiama `onOpenDraft`; se ERROR nelle issues mostra fascia "Non armabile: <motivo>"). In fondo input + send. Se `state.sending`: `LatencyIndicator(elapsed, "di solito 10-30 s", onCancelPending)` e input disabilitato con placeholder "In attesa della risposta…". Se `brainReachable==false`: banner rosso "Hermes irraggiungibile" + Riprova (`onRetry`), send disabilitato. Empty state = chip suggerimenti tappabili (i 3 esempi della spec) → `onInputChange`+`onSend`.

- [ ] **Step 2: `@Preview`** — conversazione con DraftCard PROPOSED, stato `sending` (spinner+cronometro), empty (suggerimenti), brain-down, `MalformedReply` notice.

- [ ] **Step 3: Compila** → `./gradlew :ui:compileDebugKotlin` → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/screens/ChatScreen.kt
git commit -m "feat(ui): Chat screen (one-shot latency indicator, draft cards, no streaming)"
```

---

## Task 8: Schermo Automazioni · lista (§6.2)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/screens/AutomationListScreen.kt`

**Interfaces:**
- Consumes: `AutomationListState`, `AutomationListCallbacks`, `StatusBadgeChip`, `EngineBannerBar`, `EmptyState`.
- Produces: `fun AutomationListScreen(state, callbacks)`.

Riferimento: `docs/design/screenshots/02-lista.png`, handoff §6.2.

- [ ] **Step 1: Implementa**: titolo → `EngineBannerBar(state.banner)` → riga chip filtro orizzontale (`StatusFilter`, `onFilter`) → lista `AutomationRow`. Ordinamento fisso `PENDING_APPROVAL → NEEDS_REVIEW → ARMED → DISABLED`; pending con sfondo ambra tenue, needs-review rosso tenue. Card riga: icona trigger (`iconFor` sul triggerSummary? no — usa un iconKey nel row) + nome (+ pallino ambra se `hasWarnings`) + `triggerSummary`; toggle a destra **solo** per ARMED/DISABLED (`onToggleEnabled`, click indipendente dalla card, non propagare); sotto, badge stato + badge generativa + `lastFiredLabel`/`nextFireLabel`. Tap card → `onOpen`. Empty = `EmptyState("Nessuna automazione — chiedila in chat", cta cambia tab)`.

> Nota: `AutomationRow` non ha un `triggerIconKey` nell'handoff; aggiungilo al contratto (`val triggerIconKey: String`) in `UiContracts.kt` se manca — coerente con §6.2 "icona trigger".

- [ ] **Step 2: `@Preview`** — lista mista (pending+armed+disabled+needs_review), empty, con `EngineBanner.SHIZUKU_DOWN`.

- [ ] **Step 3: Compila** → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/screens/AutomationListScreen.kt ui/src/main/kotlin/dev/argus/ui/model/UiContracts.kt
git commit -m "feat(ui): Automation list screen (fixed ordering, inline toggle, filter chips)"
```

---

## Task 9: Schermo Log esecuzioni (§6.4)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/screens/ExecutionLogScreen.kt`

**Interfaces:**
- Consumes: `ExecutionLogState`, `ExecutionLogCallbacks`, `LogRow`, `LogOutcome`, `AuditKind`.
- Produces: `fun ExecutionLogScreen(state, callbacks)`.

Riferimento: `docs/design/screenshots/04-log.png`, handoff §6.4.

- [ ] **Step 1: Implementa**: timeline raggruppata per giorno (header "Oggi"/"Ieri" da `timeLabel`). Riga: icona esito (SUCCESS verde `check_circle`, DEFERRED ambra `schedule_send`, FAILED/ERROR rosso `error`, CONDITIONS_NOT_MET/SUPPRESSED grigio, questi ultimi attenuati opacity .5) + nome regola + timestamp a destra + `summary` colorato. Righe con `expandedDetail` espandibili (chevron ruota 180° su `onExpand`), mostrano i passi per-azione. Righe `DEFERRED` → bottone "Invia ora" (`onOpenAutomation` o callback E13). `filterAutomationName` → header "Solo: <nome>" con "×" (`onClearFilter`).

- [ ] **Step 2: `@Preview`** — log con success/partial/deferred/error/suppressed, riga espansa, filtrato per automazione.

- [ ] **Step 3: Compila** → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/screens/ExecutionLogScreen.kt
git commit -m "feat(ui): Execution log screen (per-day grouping, expandable, deferred send)"
```

---

## Task 10: Schermo Sistema / Settings (§6.5)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/screens/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsState`, `SettingsCallbacks`, `TransportUi`, `ContactRow`, `BudgetUi`, `ShizukuStatus`.
- Produces: `fun SettingsScreen(state, callbacks)`.

Riferimento: `docs/design/screenshots/05-sistema.png`, handoff §6.5.

- [ ] **Step 1: Implementa** sezioni a card:
  - **Salute**: 4 righe (Shizuku via `state.shizuku`, batteria `batteryExempt`, notifiche `notificationAccess`, posizione `backgroundLocation`), ognuna verde o con CTA fix (`onOpenShizukuFix`/`onOpenBatteryFix`/`onOpenNotificationAccessFix`/`onOpenLocationFix`). La posizione background con warning ambra se non GRANTED.
  - **Brain · transport**: `CliBridge` → url in monospace (`onEditBridgeUrl`), stato raggiungibile + latenza, "Test connessione" (`onTestConnection`); `OpenAICompat` → mostrato "in arrivo" (P3).
  - **Whitelist contatti**: nota "memorizzati per conversationId, non per nome — spoofabile"; righe `ContactRow` (avatar + displayName + id **mascherato in monospace**), rimuovi (`onRemoveContact`); "Aggiungi contatto" (`onAddContact`).
  - **Budget LLM**: barra `usedThisHourLabel` (es. "3 / 20 quest'ora").
  - Riga "Ripeti configurazione" (`onRerunOnboarding`); footer versione `appVersionLabel`.

- [ ] **Step 2: `@Preview`** — tutto verde; Shizuku degradato + batteria non-exempt (CTA fix visibili); whitelist con 1 contatto.

- [ ] **Step 3: Compila** → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/screens/SettingsScreen.kt
git commit -m "feat(ui): System/Settings screen (health, transport, whitelist, budget)"
```

---

## Task 11: Schermo Onboarding / permessi (§6.6)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/screens/OnboardingScreen.kt`

**Interfaces:**
- Consumes: `OnboardingState`, `OnboardingStepState`, `StepKind`, `StepStatus`, `OnboardingCallbacks`.
- Produces: `fun OnboardingScreen(state, callbacks)`.

Riferimento: `docs/design/screenshots/06-onboarding.png`, handoff §6.6 + microcopy §9.

- [ ] **Step 1: Implementa**: back + "Configurazione · N di 6" → barra segmentata progresso → icona step → titolo → corpo → (box conseguenza ambra per BATTERY_OEM) → (chip "Opzionale" per BACKGROUND_LOCATION) → checklist stati (DONE ✓ / IN_PROGRESS ◉ / TODO ○ / SKIPPED / BLOCKED) → footer `[Salta?]`(`onSkip`) `[CTA]`(`onStepCta` con `ctaLabel`). Copy Shizuku **letterale** dalla tabella `handoff-frontend.md` §9. Lo step WELCOME_PRIVACY con consenso esplicito E11 ("Ho capito, acconsento"); `canFinish` gate su WELCOME_PRIVACY+BRAIN_CONFIG.

- [ ] **Step 2: `@Preview`** — step Privacy, step Shizuku in stato RUNNING_NOT_AUTHORIZED, step Batteria (box conseguenza), step Posizione (opzionale).

- [ ] **Step 3: Compila** → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/screens/OnboardingScreen.kt
git commit -m "feat(ui): Onboarding/permissions wizard (6 steps, Shizuku sub-states, consent)"
```

---

## Task 12: Fixture centralizzate + modulo `app` demo (NavHost)

**Files:**
- Create: `ui/src/main/kotlin/dev/argus/ui/preview/Fixtures.kt`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/dev/argus/MainActivity.kt`
- Create: `app/src/main/kotlin/dev/argus/nav/ArgusNavHost.kt`
- Create: `app/src/main/res/values/strings.xml` (`app_name` = "Argus")

**Interfaces:**
- Consumes: tutti gli schermi + contratti; `ArgusTheme`.
- Produces: `object Fixtures` con uno stato realistico per ogni schermo (i 3 esempi della spec + regole plausibili, tutti gli stati: pending/armed/disabled/needs_review/generativa/shell/arm-bloccato + esiti log); app installabile.

- [ ] **Step 1: `Fixtures.kt`** — centralizza i dati finti (design tips §6): i 3 esempi (geofence Wi-Fi/BT, DND 23:00, reply WhatsApp Moglie) + backup foto shell + promemoria farmaci NEEDS_REVIEW. Copre tutti gli stati badge e tutti i `LogOutcome`. Rifattorizza le preview degli schermi (Task 6-11) per usarlo dove comodo.

- [ ] **Step 2: `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.argus"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.argus"
        minSdk = 30; targetSdk = 35
        versionCode = 1; versionName = "0.1-demo"
    }
    buildTypes { getByName("debug") { isDebuggable = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":ui"))
    implementation(project(":engine-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
}
```

- [ ] **Step 3: `AndroidManifest.xml`** con `MainActivity` (launcher, `android:label="@string/app_name"`, tema Material) e `strings.xml`.

- [ ] **Step 4: `MainActivity.kt`** — `setContent { ArgusTheme { ArgusNavHost() } }`, edge-to-edge.

- [ ] **Step 5: `ArgusNavHost.kt`** — `Scaffold` con `NavigationBar` 4 voci (Chat/Automazioni/Log/Sistema, icone §9, badge pending su Automazioni) + `NavHost`. Ogni destinazione monta lo schermo su `Fixtures`; i callback fanno navigazione demo (es. `onOpen(id)` → push `AutomationDetailScreen` da fixture; `onOpenDraft` idem) e per il resto aggiornano uno `remember { mutableStateOf(...) }` locale o mostrano un toast. Onboarding raggiungibile da Sistema → "Ripeti configurazione".

- [ ] **Step 6: Compila**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK in `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 7: Commit**

```bash
git add ui/src/main/kotlin/dev/argus/ui/preview app/
git commit -m "feat(app): demo NavHost + fixtures wiring all 6 screens (installable APK)"
```

---

## Task 13: Verifica build completa + APK demo

**Files:** nessun nuovo file.

- [ ] **Step 1: Suite unit** — `./gradlew test` → engine-core + ui mapper verdi.
- [ ] **Step 2: Assemble** — `./gradlew :app:assembleDebug :ui:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3: Lint leggero** — `./gradlew :ui:lintDebug` (non bloccante; annota i warning). 
- [ ] **Step 4: (facoltativo, se device connesso)** `adb -s <oneplus-tailscale> install -r app/build/outputs/apk/debug/app-debug.apk` e apri l'app; verifica navigazione tra i 6 schermi. Se il device non è disponibile in sessione, l'APK resta il deliverable.
- [ ] **Step 5: Commit finale** (se ci sono fix)

```bash
git commit -am "chore: M2 build green — engine+ui tests pass, demo APK assembles"
```

---

## Definition of Done (M2)

- [ ] `./gradlew test` verde (engine-core + `RuleRenderMapper`).
- [ ] `./gradlew :app:assembleDebug` produce `app-debug.apk`.
- [ ] I 6 schermi esistono come composable **stateless** contro i contratti §6, ognuno con `@Preview` per gli stati chiave (incl. Dettaglio PENDING-con-ERROR-bloccante).
- [ ] Componenti condivisi §8 riusabili; nessun colore/font hardcoded fuori dal tema.
- [ ] Invarianti di sicurezza UI rispettati: nessun auto-arm, ERROR blocca l'arma, warning sopra la fold, `run_shell` monospace integrale, badge generativa+cloud presenti.
- [ ] Copy di sicurezza italiano **letterale** dal design handoff.
- [ ] Le 4 superfici di notifica (§7) restano mock statici — **non** in questo modulo (P0-B/P2).

## Self-review (fatta)

- **Spec coverage:** handoff §6 (6 contratti)→T3; §5 direttive sicurezza→T6 (Dettaglio) + T4 (render dai tipi); §8 componenti→T5; §4 no-streaming→T7; §9 microcopy Shizuku→T11; token design §5→T1-2; icone §9→T3. ✓
- **Placeholder:** i task rimandano ai doc fonte per il copy/screenshot invece di duplicarli — scelta consapevole (gli esecutori ricevono i doc). I frammenti di codice-chiave (tema, mapper, contratti, catalogo versioni) sono completi. ✓
- **Type-consistency:** contratti presi verbatim da handoff §6; `AutomationRow.triggerIconKey` aggiunto in T8 (nota esplicita); `RuleRenderMapper.map/mapDraft` coerente T4↔T6/T7. ✓
