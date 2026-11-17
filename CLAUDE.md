# Argus — App Android di automazione LLM-driven

Motore di automazione Tasker-class always-on: l'LLM (via Hermes o altro Brain configurabile) **compila** richieste in linguaggio naturale in regole `{trigger, condizioni, azioni}`; un motore deterministico le esegue senza LLM a ogni scatto. Distribuzione: GitHub Releases e F-Droid; tier base senza Shizuku, capability privilegiate opzionali con Shizuku, Sui o root. UI **bilingue**: inglese di default (`values/`), italiano (`values-it/`), secondo la lingua del sistema.

## Documenti fonte di verità (leggere PRIMA di implementare)

| Documento | Contenuto |
|---|---|
| `README.md` | Catalogo di trigger, condizioni e azioni, sezione sicurezza, permessi e tier: è lo stato pubblico corrente |
| `CHANGELOG.md` | Cosa è cambiato in ogni release, con i limiti noti |
| `docs/design/redesign-a.md` | Tema, token e componenti condivisi della UI (redesign A e componenti 0.7.2/0.8) |
| `docs/design/hermes-bridge-contract.md` | Contratto e confine di sicurezza del bridge Hermes dedicato |
| `docs/design/argus-schema-versioning-adr.md` | Versioni dello schema delle regole e compatibilità |
| `docs/design/screenshots/*.png` | Riferimento visivo degli schermi |
| `e2e/README.md` | Suite end-to-end su emulatore e modello di copertura a coppie |

Spec, piani e report di fase stanno fuori dal repository pubblico.

## Struttura moduli

- `engine-core/` — **Kotlin JVM puro** (`kotlin("jvm")`), zero dipendenze Android. Modelli dominio, Engine, CronSchedule, DraftValidator, policy di sicurezza, simulatore della prova a secco, parser Brain. Package `dev.argus.engine`. `model/FeatureGates.kt` tiene un interruttore per ogni tipo nuovo del vocabolario.
- `ui/` — Android library, Jetpack Compose Material 3. Schermi **stateless** (`fun XxxScreen(state, callbacks)`) + `@ArgusThemePreviews` per ogni stato. Package `dev.argus.ui`. Dipende da `engine-core` solo per i tipi.
- `brain-android/`, `core-shizuku/`, `device-tools/`, `data/`, `automation-android/` — transport LLM e cassaforte dei segreti, gateway privilegiato, tool tipizzati, Room (versione 14) con backup e condivisione, runtime Android event-driven.
- `app/` — application module `dev.argus`: Hilt, navigation e wiring dei ViewModel/runtime reali. Le fixture restano confinate a test e preview Compose.

## Build & test

- `./gradlew :engine-core:test` — suite engine (JVM, veloce; è la verifica primaria)
- `./gradlew :engine-core:test testDebugUnitTest lintDebug` — quello che gira in CI (`.github/workflows/ci.yml`)
- `./gradlew :app:assembleDebug` — compilazione Android
- JVM: Gradle gira su JDK 17–21 (per esempio il JBR di Android Studio, configurato in `~/.gradle/gradle.properties`; NON committare `org.gradle.java.home` nel repo); i moduli usano `jvmToolchain(17)`.
- SDK Android: `local.properties` (gitignorato) con `sdk.dir=<percorso dell'SDK>`; compileSdk/targetSdk 36, minSdk 30.

## Convenzioni

- **TDD per engine-core**: test prima, poi implementazione.
- Commit in inglese, prefissi `feat(engine):` / `feat(ui):` / `fix(…):` / `chore:` / `test:` / `docs:`.
- Stringhe UI in **inglese e italiano**: risorse in `values/` e `values-it/`; il codice di resa usa `l.pick(en, it)`. Il copy di sicurezza non si parafrasa.
- Colori, tipografia e forme SOLO dai token del tema (`docs/design/redesign-a.md`), mai hardcoded nei composable.
- Impronta: la forma serializzata di ogni regola esistente resta identica byte per byte (`WireBytes071CompatibilityTest`); un campo nuovo su un tipo esistente ha un default e `@EncodeDefault(EncodeDefault.Mode.NEVER)`.
- Nessun nome di host privato, indirizzo del tailnet o nome di dispositivo nei file del repository: è pubblico.

## Invarianti di sicurezza (NON negoziabili)

1. Niente auto-arm: una bozza diventa ARMED solo dal Dettaglio, mai dalla chat.
2. `ValidationIssue` con severity ERROR ⇒ `canArm=false`, bottone Arma disabilitato con motivo.
3. `allowed_tools` di `InvokeLlm` non può MAI contenere `shell.run`, `app.install`, `automation.*` (DraftValidator + ricontrollo al fire-time).
4. La regola in approvazione si renderizza **dai tipi** (`RuleRender`), mai dalla parafrasi dell'LLM.
5. Reply generative: solo chat 1:1 (`isGroup=false`), solo `conversationId` whitelistato, destinatario vincolato a `trigger.sender`.
6. Comandi shell sempre in monospace, integrali, mai troncati.
7. Il contenuto esterno (TAINTED) può alimentare sink locali/vincolati e, con la postura Aggressive, i campi dei template approvati (URL, package, impostazioni: mostrati integralmente in approvazione); nei comandi shell entra sempre come un unico argomento quotato e non diventa mai sintassi di comando; non può mai modificare le automazioni (decisione D1 del 2026-10-25).
8. Missing state = UNKNOWN e fallisce chiuso anche sotto `Not`; mai sostituire un valore assente con stringa vuota.

## Stato

- P0–P4 completate: engine, persistenza, approvazione, audit, bridge Hermes, notifiche generative, trigger in background, lettori parametrici, sensori, tier base senza Shizuku, variabili e flusso strutturato.
- 0.7: redesign A (tema chiaro e scuro).
- 0.7.2/0.8 (prossima release): correzioni dell'audit di 0.7.1 e il vocabolario nuovo (intervalli con ripristino, conferma e domande, risposte vincolate dell'AI, valori conservati e modalità, cassaforte dei segreti, SMS, chiamate e intent, nuovi trigger e condizioni, prova a secco, regole prima del primo sblocco), tutto acceso in `FeatureGates`.
