# Argus

**Natural-language automation for Android, compiled by an LLM, executed by a deterministic engine.**

Argus is a Tasker-class Android automation app where the LLM is the *compiler*, not the executor: you describe a rule in plain language ("every day at 9 send me the BTC price", "when I leave home turn off Wi-Fi"), the LLM compiles it into a structured `{trigger → conditions → actions}` rule, you review and approve a byte-stable fingerprint of the executable data, and from then on a deterministic engine runs it — no LLM in the execution loop, except for explicitly generative actions. Elevated privileges are optional via [Shizuku](https://shizuku.rikka.app/); a base tier works without it.

> Il resto di questo documento è in italiano (come la UI dell'app).

---

## Indice

1. [Cos'è Argus](#cosè-argus)
2. [Architettura](#architettura)
3. [Catalogo trigger, condizioni e azioni](#catalogo-trigger-condizioni-e-azioni)
4. [Provider LLM e bridge Hermes](#provider-llm-e-bridge-hermes)
5. [Sicurezza & privacy](#sicurezza--privacy)
6. [Permessi e tier](#permessi-e-tier)
7. [Build & installazione](#build--installazione)
8. [Per i tester](#per-i-tester)
9. [Stato del progetto e roadmap](#stato-del-progetto-e-roadmap)
10. [Licenza](#licenza)

---

## Cos'è Argus

Argus **non è un chatbot che "fa cose"**. È un motore di automazione sempre vivo (stile Tasker/MacroDroid) in cui l'LLM ha un ruolo solo: **compilare** linguaggio naturale in una regola strutturata e tipizzata. Il ciclo di vita è:

1. **Descrivi** la regola in chat, in linguaggio naturale.
2. **L'LLM compila** la richiesta in una bozza `{trigger, condizioni, azioni}` su un vocabolario chiuso di tipi.
3. **Il validator** (`DraftValidator`) controlla la bozza: vocabolari chiusi, bound su ogni campo, invarianti di sicurezza. Un errore blocca l'approvazione.
4. **Tu approvi** dalla schermata di dettaglio. La regola viene mostrata **renderizzata dai tipi**, mai dalla parafrasi dell'LLM, e congelata con un **fingerprint SHA-256** dei soli dati eseguibili.
5. **Il motore deterministico esegue**: alarm OS-managed, receiver di sistema, geofence, notification listener. L'LLM non viene interpellato allo scatto.

L'unica eccezione è l'azione generativa esplicita (`invoke_llm`): lì l'LLM torna in gioco *al fire-time*, ma dentro un contratto rigido (tool consentiti chiusi, destinatario vincolato, timeout, budget) che hai approvato in anticipo.

Perché questa separazione conta:

- **Prevedibilità** — la regola che scatta alle 3 di notte è esattamente quella che hai approvato, byte per byte.
- **Costo** — niente chiamate LLM a ogni scatto: l'LLM si paga (o si self-hosta) solo alla compilazione e nelle azioni generative.
- **Sicurezza** — il contenuto esterno (un SMS, una notifica) può al massimo *premere un interruttore* già approvato; non può mai diventare parte di un comando (vedi [Sicurezza & privacy](#sicurezza--privacy)).

Filosofia dichiarata del progetto: **potenza Tasker-class senza limiti tecnici artificiali, con un solo confine non negoziabile** — il contenuto esterno non fidato non può creare o cambiare autorità di esecuzione (difesa dall'injection *by construction*, non tramite filtri sul prompt).

---

## Architettura

### Moduli Gradle

| Modulo | Tipo | Responsabilità |
|---|---|---|
| `engine-core` | Kotlin **JVM puro** (zero Android) | Modelli di dominio, `Engine`, `TriggerMatcher`, `ConditionEvaluator`, `CronSchedule` (incl. DST), `DraftValidator`, `ApprovalFingerprints`, `StaticShellSafety`, parser Brain, `AuditSink`. Testabile in millisecondi su host. |
| `brain-android` | Android library | Transport LLM: `CliBridgeTransport` (bridge Hermes), `OpenAICompatTransport` (OpenAI/Gemini/OpenRouter/custom), `AnthropicMessagesTransport`. Catalogo provider, store cifrato delle chiavi, normalizzazione usage. |
| `automation-android` | Android library | Runtime Android event-driven: AlarmManager (exact/inexact), geofence, notification listener, receiver SMS/telefonia/connectivity/Bluetooth, sensori, lane generativa, budget/usage, ViewModel. |
| `data` | Android library | Persistenza Room: automazioni, audit (append-only, redatto), usage LLM. |
| `ui` | Android library | Jetpack Compose Material 3, 6 schermi **stateless** (chat, lista, dettaglio/approvazione, log, sistema, onboarding) + preview. |
| `device-tools` | Android library | Capacità tipizzate sopra Shizuku (stati device, toggle, screen tools). |
| `core-shizuku` | Android library | Unico gateway privilegiato: shell UID via Shizuku, coda single-writer. |
| `app` | Android application | `dev.argus` — Hilt, navigation, wiring dei runtime reali. |

### Flusso compile → approve → arm → fire

```
 utente (chat, linguaggio naturale)
        │
        ▼
 ┌─────────────┐   bozza JSON strict    ┌────────────────┐
 │ Brain (LLM) │ ─────────────────────► │ DraftValidator │  ERROR ⇒ non armabile
 │ compila     │                        │ vocaboli chiusi│  WARNING ⇒ mostrato
 └─────────────┘                        └───────┬────────┘
   Hermes bridge │ OpenAI │ Anthropic           │
   Gemini │ OpenRouter │ custom                 ▼
                              ┌──────────────────────────────┐
                              │ APPROVAZIONE (schermo Detail)│
                              │ regola renderizzata DAI TIPI │
                              │ fingerprint SHA-256 dei soli │
                              │ dati eseguibili              │
                              └──────────────┬───────────────┘
                                             │ arm
                                             ▼
                              ┌──────────────────────────────┐
                              │ REGISTRAR OS-MANAGED         │
                              │ AlarmManager · geofence ·    │
                              │ NotificationListener ·       │
                              │ receiver SMS/conn/BT · sensori│
                              └──────────────┬───────────────┘
                                             │ evento
                                             ▼
                              ┌──────────────────────────────┐
                              │ ENGINE DETERMINISTICO        │
                              │ match trigger → condizioni → │
                              │ azioni · cooldown · dedup ·  │
                              │ audit di ogni esito          │
                              └──────┬───────────────┬───────┘
                                     │               │ solo azioni generative
                                     ▼               ▼
                              azioni deterministiche  lane invoke_llm
                              (nessun LLM)            (LLM con contratto chiuso)
```

---

## Catalogo trigger, condizioni e azioni

I nomi sotto sono i discriminatori wire reali del modello (`engine-core`).

### Trigger

| Trigger | Parametri principali | Note |
|---|---|---|
| `time` | esattamente uno tra `cron` (ricorrente), `at` (datetime ISO locale, one-shot) e `afterMs` (ritardo relativo, one-shot); `tz`; `precision` `FLEXIBLE\|EXACT` | Cron con gestione DST in engine-core. `afterMs` ("tra 2 minuti…") schedula exact di default. |
| `immediate` | — | Scatta una volta all'arm della regola. Serve per i one-shot "adesso" senza corse contro l'orologio. |
| `notification` | `pkg`, `conversationId` (chiave stabile, preferita), `sender` (fallback spoofabile ⇒ WARNING), `isGroup`, `titleMatch`, `textMatch` | Usato per WhatsApp; le reply generative richiedono chat 1:1 (`isGroup=false`) e `conversationId`. |
| `phone_state` | `event` `INCOMING_CALL\|CALL_ENDED\|SMS_RECEIVED`, `number`, `textMatch` (solo SMS) | Mittente/caller ID considerati **spoofabili**: mai abilitati a innescare shell. |
| `connectivity` | `medium` `WIFI\|BT\|POWER`, `state` `CONNECTED\|DISCONNECTED`, `match` (es. SSID/nome device) | POWER = alimentazione collegata/scollegata. |
| `geofence` | `lat`/`lng`/`radiusM`, `transition` `ENTER\|EXIT\|DWELL`, `loiteringDelayMs`, `resolveCurrentLocation` | `resolveCurrentLocation=true`: le coordinate vengono risolte dall'app all'arm ("la mia posizione attuale"); non passano mai dall'LLM. |
| `sensor` | `kind` `significant_motion\|stationary_detect\|motion_detect\|step_detector\|step_counter`, `minimumEventCount` | Solo famiglie event-driven a basso rate; il runtime emette l'evento aggregato, nessun valore raw entra nel journal. Cooldown minimo 60 s. |

### Condizioni

Albero singolo componibile con `and` / `or` / `not` (profondità max 8, max 64 condizioni).

| Condizione | Parametri | Note |
|---|---|---|
| `time_window` | `startLocal`, `endLocal`, `tz` | Fascia oraria. |
| `state_equals` | `key`, `op`, `value` | Su registry **chiuso** di chiavi device: `ringer`, `wifi`, `bluetooth`, `dnd`, `battery`, `charging`, `airplane`. |
| `state_compare` | `query` (famiglie `builtin`, `setting`, `system_property`, `sysfs`, `dumpsys_field`), `valueType` `TEXT\|NUMBER\|BOOLEAN`, `op` `EQ\|NEQ\|GT\|LT\|CONTAINS`, `expected`, `policyVersion` | Lettori di stato **parametrici** (P3): famiglie chiuse, parametri validati e fingerprintati, probe pre-arm. Sola lettura per costruzione. |
| `app_in_foreground` | `pkg` | Richiede lettore privilegiato. |
| `location_in` | `lat`, `lng`, `radiusM` | Posizione dentro un raggio. |

Semantica fail-closed: uno stato non leggibile è `UNKNOWN` e la condizione fallisce chiusa, **anche sotto `not`**.

### Azioni

| Azione | Cosa fa | Privilegio |
|---|---|---|
| `set_wifi` / `set_bluetooth` | Toggle radio | Shizuku |
| `set_dnd` | DND `off\|priority\|total` | Base (accesso "Non disturbare") |
| `set_ringer` | Suoneria normal/vibrate/silent | Base |
| `set_volume` | Volume per stream (`MEDIA\|RING\|ALARM\|NOTIFICATION`), percentuale 0–100 | Base |
| `launch_app` / `open_url` | Avvia app / apre URL | Base (affidabili da background solo con Shizuku) |
| `open_settings_screen` | Apre una schermata Impostazioni da **enum chiuso** (mai action-string arbitrarie) | Base |
| `show_notification` | Notifica locale di Argus | Base |
| `set_alarm` / `set_timer` | Sveglia/timer **reali** dell'app orologio via Intent `AlarmClock` | Base |
| `copy_to_clipboard` | Copia il payload del trigger (SMS/notifica), opzionalmente ridotto al primo capture group di una regex lineare — caso d'uso OTP. Estrazione deterministica: il testo non lascia il telefono | Base |
| `set_flashlight` / `vibrate` | Torcia on/off, vibrazione one-shot | Base |
| `whatsapp_reply` | Reply WhatsApp via RemoteInput della notifica | Base (notification listener) |
| `run_shell` | Comando shell **letterale** approvato alla lettera | Shizuku |
| `write_setting` | `settings put` parametrico su `system\|secure\|global` (qualsiasi chiave, valore letterale approvato) | Shizuku |
| `tap` / `input_text` | Primitive di input UI | Shizuku |
| `invoke_llm` | **Generativa**: al fire-time l'LLM produce testo e lo consegna a un sink approvato — reply WhatsApp alla stessa chat 1:1 whitelistata, oppure notifica locale. Tool opzionale `web.search` (ricerca web server-side del provider) | Base |
| `invoke_llm_v2` | Variante P3 con contesto di stato esplicito: ogni lettore, tipo e classificazione entra nel fingerprint approvato | Base |

---

## Provider LLM e bridge Hermes

Il "Brain" è pluggable: l'app è sempre padrona del loop, l'LLM è un servizio di ragionamento dietro un'interfaccia di transport.

| Provider | Transport | Web search | Costi mostrati |
|---|---|---|---|
| **Hermes (self-hosted)** | bridge dedicato (vedi sotto) | sì (lato agente) | solo token |
| **OpenAI** | Responses API (`web_search` server-side) | sì | stima in $ da listino |
| **Anthropic** | Messages API (server tool `web_search`) | sì | stima in $ da listino |
| **Google Gemini** | shim OpenAI-compat + API nativa per il grounding (`google_search`) | sì | stima in $ da listino |
| **OpenRouter** | OpenAI-compat (web via slug `:online`) | sì | solo token |
| **Custom (OpenAI-compat)** | endpoint a scelta (Ollama, LiteLLM, z.ai, ecc.) | no | n/d |

- **BYOK**: i provider diretti richiedono la **tua** API key, inserita in-app e salvata cifrata sul device. Nessun account Argus, nessun backend del progetto.
- **Budget**: tracking usage per turno (token e, dove il listino è noto, micro-USD), limiti configurabili; una regola generativa che sfora il budget viene soppressa e auditata (`SUPPRESSED_BUDGET`).

### Bridge Hermes (opzionale, self-host)

`ops/hermes/bridge.py` è un servizio one-shot pensato per chi ha già un agente LLM self-hosted su un proprio server: espone `POST /compile` (NL → bozza di regola) e `POST /act` (lane generativa) con envelope strict e versionati, bearer token, request-id idempotenti, limiti su body e output, parsing fail-closed. Binda solo su loopback e va pubblicato via HTTPS su una rete privata (es. una VPN mesh / Tailscale Serve). Unit systemd e `.env` di esempio inclusi in `ops/hermes/`.

**Senza bridge l'app funziona comunque**: si sceglie un provider diretto con la propria chiave. Il bridge serve solo a chi vuole compilare le regole con il proprio agente self-hosted invece che con un'API commerciale.

Contratto completo: `docs/design/hermes-bridge-contract.md`.

---

## Sicurezza & privacy

Il modello di minaccia principale è la **prompt injection da contenuti esterni** (SMS, notifiche, pagine web): la difesa è strutturale, non basata su filtri.

- **Fingerprint di approvazione** — SHA-256 (`argus-approval-v1`) del JSON canonico dei **soli dati eseguibili**; la prosa dell'LLM è esclusa dall'hash. Ciò che scatta è byte-per-byte ciò che hai approvato; qualsiasi modifica richiede ri-approvazione.
- **Rendering dai tipi** — la schermata di approvazione mostra la regola ricostruita dai tipi del dominio, mai la parafrasi dell'LLM. I comandi shell sono mostrati integrali, in monospace, mai troncati.
- **`DraftValidator`** — nessuna bozza entra nell'engine senza validazione: vocabolari chiusi (chiavi di stato, enum, tool), bound su ogni campo (lunghezze, range, profondità albero condizioni), invarianti hard (es. `allowed_tools` di `invoke_llm` non può mai contenere `shell.run` o `automation.*` — ricontrollato anche al fire-time).
- **Taint boundary** — il contenuto esterno può alimentare *sink* locali o vincolati (clipboard, notifica, reply alla stessa chat verificata) ma **non può mai creare autorità**: mai TAINTED → comando, routing, target, path/package o mutazione di automazioni. Il messaggio in arrivo è un interruttore, mai parte del comando.
- **`StaticShellSafety`** — `run_shell` è innescabile solo da trigger a identità non falsificabile (time, immediate, geofence, connectivity, sensor) o da una chat WhatsApp 1:1 **whitelistata** identificata da `conversationId` stabile. SMS e caller ID sono esclusi per limite reale (spoofabili), e il binding trigger-approvato ↔ evento-live è verificato a runtime.
- **Audit log senza PII** — ogni scatto, soppressione, errore e transizione di lifecycle (arm/disable/delete/needs-review) viene registrato su Room append-only con **reason-code a vocabolario chiuso**: mai testo libero, mai contenuto dei messaggi; gli id evento sono hashati.
- **Minimizzazione verso il Brain** — il compile riceve un manifest di capability e uno stato device **redatto** (solo chiavi del registry approvato); le coordinate GPS non lasciano mai il telefono (passa solo `location_available`). Le reply generative sono vincolate al mittente del trigger, solo chat 1:1 whitelistate.
- **Fail-closed ovunque** — stato mancante = `UNKNOWN` = condizione falsa; metadata ambigui (es. `isGroup` sconosciuto) = non autorizzato; Shizuku assente = l'azione privilegiata fallisce pulita e viene auditata, mai eseguita "più tardi".
- **Chiavi e segreti** — API key cifrate on-device, mai nello stato UI, mai nel repo, mai nell'APK; backup dell'app disabilitato.

---

## Permessi e tier

Argus degrada in modo esplicito: l'onboarding mostra una lista onesta di cosa dipende da cosa, e ogni permesso extra si concede solo quando una regola lo usa.

**Obbligatori per iniziare**: configurazione del Brain (un provider qualsiasi) e presa visione privacy. Tutto il resto è skippabile.

| Permesso / accesso | Serve per | Quando |
|---|---|---|
| Notifiche (`POST_NOTIFICATIONS`) | esiti, avvisi, sink notifica generativo | consigliato subito |
| **Sveglie e promemoria** (`SCHEDULE_EXACT_ALARM`) | puntualità dei trigger `time` EXACT e dei ritardi "tra N minuti" | quando una regola chiede precisione; fallback inexact se negato |
| Accesso notifiche (notification listener) | trigger `notification`, reply WhatsApp | regole su notifiche |
| Posizione (fine + background) | `geofence`, `location_in` | regole di posizione |
| SMS / stato telefono / registro chiamate | trigger `phone_state` | regole telefonia (opt-in separato) |
| Bluetooth vicino (`BLUETOOTH_CONNECT`) | trigger connectivity BT | regole BT |
| Accesso "Non disturbare" | `set_dnd`, silenziamento via volume | regole DND |
| Esenzione ottimizzazione batteria | affidabilità su OEM aggressivi | consigliato |

### Con e senza Shizuku

| Tier | Cosa copre |
|---|---|
| **Base (senza Shizuku)** | volume, suoneria, DND, torcia, vibrazione, notifiche, clipboard/OTP, sveglie e timer reali, reply WhatsApp, azioni generative, tutti i trigger |
| **Degradato senza Shizuku** | `launch_app`, `open_url`, `open_settings_screen`, sveglia/timer **da regola in background** (Android limita l'avvio di activity dal background: con Shizuku passano da `am start`, senza partono solo ad app in primo piano) |
| **Solo con Shizuku** | toggle Wi-Fi/Bluetooth, `run_shell`, `write_setting`, `tap`/`input_text`, lettori di stato privilegiati (`setting`, `system_property`, `sysfs`, `dumpsys_field`, app in foreground) |

Nota: su device non-root Shizuku va avviato via ADB e **non sopravvive al reboot**; Argus lo rileva e degrada fail-closed (l'azione privilegiata non viene mai eseguita in ritardo), guidando al ripristino.

---

## Build & installazione

### Requisiti

| Cosa | Versione |
|---|---|
| Gradle | wrapper **8.13** (incluso) |
| Android Gradle Plugin | **8.13.2** |
| Kotlin | **2.1.0** (KSP 2.1.0-1.0.29) |
| JDK | toolchain **17** per i moduli (auto-provisioning foojay); Gradle stesso va lanciato con JDK 17–21 (es. il JBR di Android Studio) |
| Android SDK | compileSdk/targetSdk **36**, minSdk **30** (Android 11+) |
| Stack | Jetpack Compose (BOM 2025.05), Room 2.6.1, Hilt 2.57.1, Shizuku API 13.1.5 |

### Comandi

```bash
# suite engine (JVM puro, veloce — la verifica primaria)
./gradlew :engine-core:test

# APK debug
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# APK release (firmato se esiste un keystore.properties locale — vedi sotto; altrimenti unsigned)
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# test dei singoli moduli
./gradlew :brain-android:test :automation-android:test :data:test :ui:test
```

Per firmare la release in proprio: crea `keystore.properties` nella root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) — il file e i keystore sono gitignored e non vanno mai committati. Gli APK ufficiali firmati sono nelle [Release GitHub](https://github.com/JackRushante/argus/releases).

Installazione: sideload via `adb install` (o trasferimento APK). Non esiste distribuzione Play. Al primo avvio l'onboarding guida provider LLM e permessi.

Crea un file `local.properties` con `sdk.dir=<path del tuo Android SDK>` se Android Studio non lo genera da sé.

---

## Per i tester

Grazie per provarla. Cose utili da sapere e da stressare:

**Cosa provare**

- **Compilazione**: chiedi regole in chat, dalle banali alle ambigue ("tra 2 minuti mandami una notifica col cambio EUR/USD", "quando esco di casa spegni il Wi-Fi", "se mia moglie scrive su WhatsApp dopo le 23 rispondile tu"). Verifica che la bozza renderizzata in approvazione corrisponda a quello che intendevi — è la regola vera, non la parafrasi.
- **Approvazione**: prova a farti proporre regole "cattive" (shell da SMS, tool non consentiti, valori fuori range) e verifica che il validator le blocchi con un motivo chiaro.
- **Esecuzione**: arma regole `time` (cron e one-shot), `immediate`, connectivity, geofence, notifiche. Testa i casi ostili: reboot, cambio timezone, ora legale, app killata dall'OEM, Shizuku spento a metà.
- **Degradazione**: nega permessi e spegni Shizuku, e verifica che l'app dica onestamente cosa non può fare invece di fallire in silenzio.
- **Budget** (regole generative): imposta un limite basso e verifica la soppressione auditata.

**Che log guardare**

- **Tab "Log" in-app**: ogni scatto, soppressione (cooldown/duplicato/budget), errore ed evento di lifecycle, con dettaglio per-azione espandibile. È la fonte di verità: se una regola non è scattata, lì c'è il reason-code.
- **Schermo "Sistema"**: stato di transport/provider, Shizuku, permessi, esenzione batteria — utile da allegare a una segnalazione.
- Per i crash: `adb logcat` filtrato su `dev.argus`.

**Come segnalare**

Apri una [Issue](https://github.com/JackRushante/argus/issues) con: cosa hai chiesto in chat (testo esatto), la regola come mostrata in approvazione, cosa ti aspettavi, cosa è successo, le righe rilevanti del Log in-app, versione Android e OEM, stato Shizuku. Il log in-app non contiene testi dei messaggi né dati personali: si può incollare serenamente.

---

## Stato del progetto e roadmap

**Sviluppo attivo.** Fasi completate e verificate su device reale: P0 (engine core + glue Android), P1 (notifiche/reply generative WhatsApp), P2 (trigger in background: SMS/OTP, chiamate, connectivity/power/BT, geofence). **P3 in corso**: lettori di stato parametrici, trigger sensore, tier base senza Shizuku, multi-provider con budget.

Roadmap breve:

- **P4** — variabili e control-flow (parità con la combinatoria Tasker: if/then, valori dinamici) con propagazione del taint su ogni valore; primo pezzo già rilasciato (sink notifica generativo per `invoke_llm`, anche ricorrente).
- **Computer-use** — loop interattivo schermo→azione a due tier (percorso lento/self-hosted valido, percorso veloce opzionale).

**Disclaimer**: è un progetto personale, sviluppato e testato principalmente su un singolo device (OnePlus 15, Android 16, non-root). Aspettatevi spigoli su altri OEM — è esattamente il feedback che cerchiamo. Nessuna garanzia: le regole eseguono azioni reali sul vostro telefono, leggete cosa approvate.

---

## Licenza

Licenza: da definire.
