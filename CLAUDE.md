# Argus — App Android di automazione LLM-driven

Motore di automazione Tasker-class always-on: l'LLM (via Hermes o altro Brain configurabile) **compila** richieste in linguaggio naturale in regole `{trigger, condizioni, azioni}`; un motore deterministico le esegue senza LLM a ogni scatto. Il deploy corrente è personale/sideload su OnePlus 15; P3 separa un profilo pubblico base dalle capability opzionali Shizuku. Lingua UI: **italiano**.

## Documenti fonte di verità (leggere PRIMA di implementare)

| Documento | Contenuto |
|---|---|
| `docs/superpowers/specs/2026-07-12-hermes-android-agent-design.md` | Spec di sistema **rev 6** — architettura e stato P0-P3 |
| `docs/superpowers/specs/2026-07-16-argus-p3-decision-record.md` | Fonte normativa P3: D0, taint per sink, state reader, sensori, transport e release profiles |
| `docs/superpowers/plans/2026-07-16-argus-p3-foundations-state-sensors.md` | Piano operativo P3 corrente e gate per slice |
| `docs/superpowers/plans/2026-07-13-argus-commander-replan.md` | Piano operativo corretto e stato corrente dell'implementazione |
| `docs/design/argus-p0b-final-audit.md` | Matrice requisiti/evidenze, rischi residui e gate di chiusura P0-B |
| `docs/design/hermes-bridge-contract.md` | Contratto v1 e confine di sicurezza del bridge Argus dedicato |
| `docs/superpowers/specs/2026-07-12-argus-handoff-frontend.md` | Contratti di stato UI completi (6 schermi), direttive sicurezza UI, navigazione, microcopy |
| `docs/superpowers/plans/2026-07-12-argus-p0a-engine-core.md` | Piano P0-A rev 2: engine core JVM puro, 13 task TDD |
| `docs/design/README.md` + `docs/design/CLAUDE-CODE-TIPS.md` | Design handoff (Claude Design, rev 1a approvata): token colore §5, modello dati §6, schermi §7, icone §9 |
| `docs/design/screenshots/*.png` | Riferimento visivo hi-fi per ogni schermo |

## Struttura moduli

- `engine-core/` — **Kotlin JVM puro** (`kotlin("jvm")`), zero dipendenze Android. Modelli dominio, Engine, CronSchedule, DraftValidator, parser Brain. Package `dev.argus.engine`.
- `ui/` — Android library, Jetpack Compose Material 3. Schermi **stateless** (`fun XxxScreen(state, callbacks)`) + `@Preview` per ogni stato. Package `dev.argus.ui`. Dipende da `engine-core` solo per i tipi.
- `brain-android/`, `core-shizuku/`, `device-tools/`, `data/`, `automation-android/` — bridge HTTPS, gateway privilegiato, tool tipizzati, Room e runtime Android event-driven.
- `app/` — application module `dev.argus`: Hilt, navigation e wiring dei ViewModel/runtime reali. Le fixture restano confinate a test e preview Compose.

## Build & test

- `./gradlew :engine-core:test` — suite engine (JVM, veloce; è la verifica primaria di M1)
- `./gradlew :ui:assembleDebug` / `./gradlew :app:assembleDebug` — compilazione Android
- JVM: Gradle usa il JBR 21 di Android Studio (configurato in `~/.gradle/gradle.properties`, NON committare `org.gradle.java.home` nel repo); i moduli usano `jvmToolchain(17)` con resolver foojay.
- SDK Android: `local.properties` (gitignorato) punta a `C:/Users/Admin/AppData/Local/Android/Sdk` (platforms 34/35/36 presenti).

## Convenzioni

- **TDD per engine-core**: test prima, poi implementazione, come da piano P0-A (i task specificano test e codice — seguirli fedelmente).
- Commit in inglese, prefissi `feat(engine):` / `feat(ui):` / `chore:` / `test:` / `docs:` come nei piani.
- Stringhe UI in **italiano**; il copy di sicurezza va preso letterale dal design handoff (`docs/design/README.md` §7) — mai parafrasato.
- Colori/typography SOLO dai token del tema (design §5), mai hardcoded nei composable.

## Invarianti di sicurezza (NON negoziabili, spec §10 + design tips §3)

1. Niente auto-arm: una bozza diventa ARMED solo dal Dettaglio, mai dalla chat.
2. `ValidationIssue` con severity ERROR ⇒ `canArm=false`, bottone Arma disabilitato con motivo.
3. `allowed_tools` di `InvokeLlm` non può MAI contenere `shell.run`, `app.install`, `automation.*` (DraftValidator + ricontrollo al fire-time).
4. La regola in approvazione si renderizza **dai tipi** (`RuleRender`), mai dalla parafrasi dell'LLM.
5. Reply generative: solo chat 1:1 (`isGroup=false`), solo `conversationId` whitelistato, destinatario vincolato a `trigger.sender`.
6. Comandi shell sempre in monospace, integrali, mai troncati.
7. Il contenuto esterno può alimentare sink locali/vincolati ma non creare autorità: mai TAINTED → command, routing, target, path/package o mutazione automazioni (decision record §4).
8. Missing state = UNKNOWN e fallisce chiuso anche sotto `Not`; mai sostituire un valore assente con stringa vuota.

## Fasi

- **M1 / P0-A**: engine core JVM completato e coperto da test.
- **M2 / UI**: 6 schermi Compose e app demo su fixture completati.
- **M3 / P0-B** (gate finali): persistenza, approvazione, audit, bridge Hermes v1, scheduler, Shizuku executor e wiring Android sono implementati. I gate esterni storici sono documentati nei report privati; endpoint self-hosted, nomi device e indirizzi del tailnet non appartengono al repository pubblico.
- **P1 / notifiche generative**: COMPLETA. Lane `invoke_llm`, reply WhatsApp reale, E13 cifrato, whitelist/binding 1:1 e anti-eco verificati.
- **P2 / background triggers**: COMPLETA e mergiata. SMS/OTP, chiamate, connectivity/power/Bluetooth e geofence fisici verificati; handoff chiusura `2026-07-16-argus-claude-to-codex-p2-closed.md`.
- **P3**: ATTIVA. Ordine vincolante: decision/dataflow → lettori parametrici → sensori → base senza Shizuku → audit/TTS → computer-use lento e poi veloce opzionale.
