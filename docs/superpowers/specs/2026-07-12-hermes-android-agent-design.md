# Argus — Agente LLM di automazione Android (design)

> **Nome di lavoro:** *Argus* (il gigante dai molti occhi — agente always-on che "vede" lo schermo). Provvisorio, rinominabile.
> **Data:** 2026-07-12 · **Rev:** 3 (post analisi critica pre-handoff) · **Autore:** Lorenzo Marci + Claude Code (oneplus)
> **Stato:** design approvato, pronto per handoff frontend (vedi `handoff-frontend.md`) e per l'esecuzione di P0-A.
>
> **Changelog rev 3** (rispetto a rev 2):
> - **Sicurezza:** invariante hard sugli `allowed_tools` di `InvokeLlm` (niente `shell.run` / `automation.*` / tool sempre-conferma); policy esplicita per `run_shell`; rendering **deterministico** della regola in approvazione (mai la parafrasi LLM); gestione **gruppi WhatsApp** (default: solo chat 1:1 per le reply generative).
> - **Schema:** `Time{cron|at}` (tolto `interval`, YAGNI); `conditions` = albero And/Or/**Not** singolo (non array); `Connectivity` con direzione (connected/disconnected); `Notification` con `conversationId` + `isGroup` + `notificationKey` runtime; `Geofence.resolveCurrentLocation` (placeholder risolto all'arm); registry chiavi `DeviceState`; semantica `priority` definita (il più prioritario esegue **ultimo** → last-writer-wins).
> - **Robustezza:** `DraftValidator` obbligatorio prima dell'approvazione; cooldown minimo imposto (60 s) per regole generative; isolamento errori per-automazione nell'engine; contratto `ActionExecutor` con `Submitted` per le generative (lane async); `AuditSink` come interfaccia P0-A; calcolo cron (incl. DST) spostato in engine-core.
> - **Phasing:** corretta la promessa P0 (Es. 1 end-to-end richiede il geofence → P2); battery-optimization exemption anticipata a P1; esplicitato il nesso exemption ⇒ watchdog (B4).
> - **Edge nuovi:** E13 (RemoteInput invalidato dalla latenza LLM), E14 (latenza/raggio minimo geofence), E15 (match sender spoofabile → `conversationId`).

## 1. Obiettivo

App Android nativa che è un **motore di automazione stile Tasker/MacroDroid sempre vivo**, in cui un **LLM è il compilatore**: l'utente parla in linguaggio naturale e l'LLM traduce la richiesta in **regole concrete** (trigger → condizioni → azioni) che il motore poi esegue **da solo**, senza LLM a ogni scatto. L'LLM torna in gioco al fire-time solo quando l'azione richiede ragionamento/generazione (es. comporre una risposta WhatsApp). L'app opera con **permessi elevati via Shizuku** (parità con `adb`): vede lo schermo, clicca, usa il terminale, installa pacchetti, legge gli stati del telefono.

Esempi target (devono funzionare a fine **P2** — vedi §15 per cosa funziona quando):
1. *"Crea un geofence sulla mia loc GPS attuale ±50 m; quando esco disattiva wifi e attiva bluetooth."*
2. *"Dopo le 23 controlla lo stato della suoneria e metti DND."*
3. *"Se il contatto Moglie manda un WhatsApp in questa fascia oraria, rispondile tu con un messaggio nel tono X/Y."*

## 2. Contesto (verificato empiricamente su `hermes`)

- **Hermes** (VM `hermes` 100.80.142.65, sul tailnet) ha già `computer_use` con **vision-routing**: se il cervello (`gpt-5.5` via `openai-codex`, OAuth) non è multimodale, lo screenshot viene **pre-analizzato dalla pipeline `auxiliary.vision`** (provider `auto` → OpenRouter/Gemini/Claude) e passato come **testo** al cervello, fuso con l'albero accessibilità/SOM. Web search già attiva via **Brave free**. → Vision e web **non richiedono API dirette**; sono capability ausiliarie disaccoppiate dal canale principale.
- **Transport verso Hermes — VERIFICATI (risolve il vecchio rischio B5):**
  - **`argus-bridge`** espone `POST /compile` v1 tramite Tailscale Serve HTTPS; riceve manifest strutturato + `DeviceState` redatto, chiama Hermes/gpt-5.5 e ritorna un envelope strict con `AutomationDraft`. Bearer runtime, request ID idempotente, limiti body e parser fail-closed. **Latenza ~10-30 s/chiamata.** Contratto: `docs/design/hermes-bridge-contract.md`.
  - **`guida-agent/bridge.py`** (porta 8090) resta il bridge separato della Guida Bali e non è un fallback Argus.
  - **`hermes proxy`** (OpenAI-compatible, `/v1/chat/completions`, porta 8645) inoltra a upstream **`nous` o `xai`** con le credenziali OAuth di Hermes. **NON** instrada il codex/ChatGPT gpt-5.5 gratuito.
- **Conseguenza sul transport** (vedi §7): il **gpt-5.5 codex gratis** è raggiungibile **solo** via CLI/bridge (lento, one-shot, testo+JSON). Il **path veloce con tool-call nativi** esiste solo via proxy (Nous/xAI) o LLM diretto (OpenAI/OpenRouter/Gemini), che costa/consuma quota.
- **Sicurezza del bridge (rev 4):** `argus-bridge` binda solo su loopback (`127.0.0.1:8092`); Tailscale Serve termina TLS sul tailnet. Ogni endpoint richiede bearer e il protocollo lega `schema_version`, `request_id` e `Idempotency-Key`. Nessun cleartext opt-in Android.
- Il **telefono è già sul tailnet** come `oneplus` (100.74.117.9) → Hermes raggiungibile in rete privata senza esporre nulla.
- Il OnePlus 15 è **rootabile/rootato** (rilevante per la persistenza di Shizuku, §9/B1).

## 3. Decisioni di design

| # | Decisione | Scelta |
|---|-----------|--------|
| D1 | Dove vive il cervello | **App standalone con Brain pluggable**; l'app è sempre padrona del loop, il Brain è puro servizio di ragionamento |
| D2 | Scope | **Motore always-live** (Tasker-class, anti-Doze); la chat è l'interfaccia di **configurazione** (LLM compila le regole), non solo esecuzione |
| D3 | Rappresentazione regole | **Ibrido (C)**: trigger/condizioni dichiarativi + catalogo azioni tipizzate **+** jolly `run_shell` e `invoke_llm` |
| D4 | Sicurezza | **Approva-alla-creazione, poi autonomo** + catalogo sempre-conferma (irreversibili) + whitelist contatti + difesa injection + **invarianti hard sui tool generativi (rev 3)** |
| D5 | Modello transport Brain | **Due transport, non due provider**: `CliBridge` (Hermes/codex gratis, one-shot, lento) + `OpenAICompat` (proxy Nous/xAI o LLM diretto, veloce, tool-call nativi) |
| D6 | Privilegi | **Shizuku come astrazione** (adb-mode o root-mode); shell UID (2000) come livello di privilegio di default, più sicuro di `su`. Root solo per auto-start Shizuku al boot |
| D7 | Validazione (rev 3) | **Nessun draft LLM entra nell'engine senza `DraftValidator`**: gli ERROR bloccano l'arm, i WARNING sono mostrati in approvazione. La parafrasi dell'LLM non è mai la fonte di verità: la UI renderizza la regola **dai tipi** |

## 4. Architettura

**Stack:** Kotlin, Jetpack Compose (UI), Room (persistenza), Hilt (DI), Coroutines/Flow, OkHttp/Ktor (Brain client), EncryptedSharedPreferences (segreti/token), AlarmManager + WorkManager, Play Services Location (geofencing), Shizuku API (`dev.rikka.shizuku`). Min SDK 30.

**Moduli (responsabilità singola, interfacce nette):**

| Modulo | Cosa fa | Fase |
|--------|---------|------|
| `engine-core` *(JVM puro)* | Modelli dominio, TriggerMatcher, ConditionEvaluator, Engine, **CronSchedule/TimeSpecs (incl. DST)**, ConflictDetector, **DraftValidator**, CliBridgeParser, CapabilityManifest, interfacce (`ActionExecutor`, `AutomationStore`, `AuditSink`, `CapabilityProbe`, `Brain`) | P0-A |
| `core-shizuku` | Unico gateway privilegiato: shell UID via Shizuku (`newProcess`/user-service) + op tipizzate (screencap, input, pm, settings, dumpsys, svc, cmd, **uiautomator dump**). Coda single-writer con priorità | P0-B |
| `device-tools` | Catalogo capacità tipizzate su Shizuku: schermo (capture / tap / swipe / type / **dump_ui via uiautomator**), stati (wifi/bt/dnd/batteria/foreground/gps), toggle, app (install/launch/intent) | P0-B |
| `automation-engine` | Wiring Android di engine-core: Room store, state provider **lazy**, audit su Room | P0-B–P1 |
| `triggers` | Registrar OS-managed: Time (AlarmManager exact via `TimeSpecs.nextFire`) → P0-B; Notification (NotificationListenerService + WhatsApp RemoteInput) → P1; Geofence (Play Services) + PhoneState (TelephonyCallback) + Connectivity/Power → P2 | P0-B→P2 |
| `brain` | Interfaccia `Brain` + **transport** `CliBridgeTransport` / `OpenAICompatTransport` | P0-B |
| `agent-loop` | Orchestrazione tool-calling: one-shot (compile / InvokeLlm) → P0-B–P1; loop interattivo computer_use → P3 | P0-B→P3 |
| `foreground-service` | Servizio persistente: engine + trigger + anti-Doze + watchdog + boot recovery | P2 |
| `security` | Gate approvazione, allowlist irreversibili, whitelist contatti, difese injection, budget guard, audit UI | P1–P3 |
| `triggers-ui` *(opzionale)* | AccessibilityService per trigger su **eventi UI** e gesture senza shell. **Non serve al core** (lettura schermo = `uiautomator dump`, tap = `input`) | P2+ |
| `ui` *(Claude Design)* | Chat, lista/dettaglio/approvazione automazioni, log, settings, wizard permessi — **contratti completi in `handoff-frontend.md`** | tutte |

> **Nota AccessibilityService:** rimosso dal core. Lettura schermo strutturata via `uiautomator dump` (Shizuku) e tap via `input tap` (Shizuku) coprono l'MVP senza il permesso di accessibilità. L'AccessibilityService serve solo se si vogliono trigger su eventi-UI in tempo reale → modulo opzionale P2+.

## 5. Modello di automazione (schema)

```
Automation {
  id, name, enabled, createdBy(llm|user), schemaVersion,
  status(pending_approval | armed | disabled | needs_review),
  priority: Int, cooldown_ms: Long,
  trigger, conditions (albero, opzionale), actions[]
}

Trigger =
  Geofence{lat, lng, radius_m, transition(enter|exit|dwell), loiteringDelay_ms,
           resolveCurrentLocation: Bool}      // rev 3: true = coordinate risolte all'ARM
| Time{cron | at, tz}                          // rev 3: exactly-one; `at` = one-shot ISO local; niente `interval` (YAGNI)
| Notification{package, conversationId?, sender?, isGroup?, titleMatch?, textMatch?}
                                               // rev 3: conversationId = chiave stabile (shortcutId/JID), preferita al display name
| PhoneState{event(incoming_call|call_ended|sms_received), number?}   // match numeri normalizzato (suffisso cifre)
| Connectivity{medium(wifi|bt|power), state(connected|disconnected), match?}
                                               // rev 3: direzione esplicita; per power: connected=alimentazione collegata

Condition = TimeWindow{tz} | StateEquals(key,op,val) | LocationIn | AppInForeground
            | And | Or | Not                   // rev 3: albero singolo (non array), Not incluso

Action(tier=deterministic) =
  SetWifi|SetBluetooth|SetDnd|SetRinger|LaunchApp|OpenUrl|ShowNotification   // rev 3: ShowNotification (feedback/avvisi)
| Tap|InputText|WhatsAppReply(RemoteInput,text)|RunShell(cmd)                // jolly shell
Action(tier=generative) =
  InvokeLlm{ goal, context_sources:[screen|notification|state|...],
             allowed_tools:[...],              // rev 3: MAI shell.run / automation.* / tool sempre-conferma (invariante hard, DraftValidator)
             reply_target(bound=trigger.sender),
             output(reply|actions), timeout_ms }
```

**Regole trasversali (rev 3):**
- **Chiavi di `StateEquals`**: vocabolario chiuso `StateKeys` (`ringer: normal|vibrate|silent`, `wifi: on|off`, `bluetooth: on|off`, `dnd: off|priority|total`, `battery: 0-100`, `charging: true|false`, `airplane: on|off`). Chiave fuori registry → ERROR del validator. Il registry è incluso nel CapabilityManifest così il compile non inventa chiavi.
- **`priority`**: nello stesso batch di trigger le automazioni eseguono in ordine di priorità **crescente** → la più prioritaria scrive **per ultima** e vince sui target condivisi (last-writer-wins coerente con C1).
- **Cooldown generativo minimo**: se `actions` contiene `InvokeLlm`, l'engine impone `effective_cooldown = max(cooldown_ms, 60 000)`.
- Whitelist contatti/target memorizzano **`conversationId`/numero**, non il display name (§13/E7, E15).

## 6. Due tier di esecuzione

- **Deterministico** — l'engine esegue direttamente via `device-tools`/Shizuku, **in modo sincrono**. Zero LLM, zero rete, wake minimo. (Es. 1 e 2.)
- **Generativo** (`InvokeLlm`) — l'executor **accoda e ritorna subito `Submitted`** (lane async, §13/C3): l'engine non resta bloccato 10-30 s. La lane sveglia l'agent-loop, **snapshotta** subito il contesto dichiarato (§13/E1), chiede al Brain risposta/azioni con **tool ristretti a `allowed_tools`** e **destinatario vincolato al `trigger.sender`**, esegue, riporta l'esito all'`AuditSink`. Costa rete+LLM solo quando serve. (Es. 3.)

Se un'azione deterministica fallisce, le successive della stessa regola **vengono comunque tentate** (i toggle sono indipendenti); l'esito è `PARTIAL` nel log. Le catene UI fragili (Tap dopo LaunchApp) sono responsabilità del tier interattivo P3, non delle regole P0-P1.

A regime i trigger OS-managed (geofence/alarm/notification-listener) scattano **anche in Doze** senza tenere vivo un loop attivo: "sempre live" ≠ "sempre sveglio".

## 7. Brain (pluggable) — modello a due transport

```kotlin
interface Brain {
  suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult  // one-shot (P0)
  suspend fun act(context: FireContext, goal: String, allowedTools: List<ToolId>): ActResult        // one-shot (P1)
  fun chat(messages: List<Msg>, tools: List<Tool>): Flow<AgentEvent>                                 // loop interattivo (P3)
}
```

**Il Brain è configurato con un transport**, non con un provider. Due transport:

| Transport | Raggiunge | Latenza | Output | Vision / Web | Uso |
|-----------|-----------|---------|--------|--------------|-----|
| **`CliBridgeTransport`** | Hermes agent → **gpt-5.5 codex (gratis)** via `argus-bridge` HTTPS | ~10-30 s/call | envelope JSON strict v1 | delegate a Hermes (aux-vision, Brave) | **compile** + **InvokeLlm one-shot** (latency-tolerant) |
| **`OpenAICompatTransport`** | proxy Hermes → **Nous/xAI**, *oppure* **LLM diretto** (OpenAI/OpenRouter/Gemini via OAuth/API) | bassa | tool-call nativi | app-side (`vision.analyze`, `web.search`) o modello multimodale | **loop interattivo computer_use** (P3), Direct brain |

Storia coerente: **gratis dove puoi aspettare** (compile, risposta WhatsApp = one-shot), **veloce/a-pagamento dove serve reattività** (pilotare lo schermo in tempo reale). Il `CliBridgeTransport` è l'MVP primario; l'`OpenAICompatTransport` unifica proxy-Hermes e LLM-diretto nello stesso adapter (cambia `base_url` + auth).

**Contratto tool esposto al Brain:**
- *Device tools:* `screen.capture` · `screen.tap` · `screen.swipe` · `screen.type` · `screen.dump_ui` · `state.read` · `toggle.set` · `app.launch` · `app.install` · `shell.run` · `web.search` · `vision.analyze` · `notify.show`
- *Automation tools:* `automation.create(draft)` · `list` · `get` · `update` · `delete` · `simulate`
- **Invariante (rev 3):** `shell.run`, `app.install` e tutti gli `automation.*` sono utilizzabili **solo in chat** (umano presente). Non sono mai ammessi negli `allowed_tools` di un `InvokeLlm` — il `DraftValidator` rifiuta il draft con ERROR.

**Capability Manifest** (a inizio sessione): modello device, versione Android, stato Shizuku, permessi concessi, tool disponibili (da probe reale), **whitelist come coppie `{displayName, conversationId}`** (rev 3: così il compile può bindare "Moglie" → id senza tool contatti; per contatti fuori whitelist il compile risponde "aggiungilo prima alla whitelist"), **registry `StateKeys`** → così l'agente "sa dove si trova e cosa può/non può fare".

**Compile e stato del device (rev 3):** il CliBridge è one-shot senza tool-call, quindi il compile non può "leggere" il device on-demand. Gli stati leggeri sono pre-iniettati nel prompt (manifest + `DeviceState` corrente); per la **posizione** non si legge il GPS a ogni messaggio: il draft usa `Geofence.resolveCurrentLocation=true` e **l'app risolve le coordinate al momento dell'approvazione** (semanticamente più corretto: "qui" = dove approvi, non dove hai chiesto).

## 8. Layer Shizuku

Unico punto che tocca Shizuku. Espone `PrivilegedShell.run(cmd): Result` + op tipizzate. Tutto ciò che si fa via `adb` gira identico (`input tap`, `screencap`, `pm install`, `settings put`, `dumpsys`, `svc`, `cmd`, `uiautomator dump`). **Esecuzione serializzata** su coda single-writer con priorità (§13/C3). Resilienza in §9/B1.

## 9. Sopravvivenza background / anti-Doze

Principio: appoggiarsi ai meccanismi **OS-managed** che scattano anche in Doze, non tenere vivo un loop.

- **Foreground service** (`specialUse`/`dataSync`) con notifica persistente a bassa priorità — ospita engine e listener.
- **Time** → `AlarmManager` `setExactAndAllowWhileIdle` + WorkManager per job tolleranti. Next-fire calcolato da `TimeSpecs.nextFire` (engine-core, DST-safe).
- **Geofence** → Play Services Geofencing (OS-managed, sopravvive a Doze). **Richiede `ACCESS_BACKGROUND_LOCATION` ("Consenti sempre")**, permesso a sé notorio su Android 11+ → step dedicato nell'onboarding. Aspettative oneste in §13/E14 (raggio min consigliato 100 m, exit con minuti di ritardo).
- **Notifiche/WhatsApp** → `NotificationListenerService` (bindato, resiste) + **WhatsApp direct-reply via RemoteInput**.
- **Watchdog** → AlarmManager heartbeat che resuscita il service se ucciso (`START_STICKY`). **Nota (rev 3):** su Android 12+ il riavvio del FGS dal background è consentito **solo grazie** alla battery-optimization exemption (senza, `ForegroundServiceStartNotAllowedException`); se l'utente la nega, il watchdog è inerte → la UI deve mostrare lo stato "protezione ridotta" (vedi handoff frontend, banner engine).
- **Boot** → receiver `BOOT_COMPLETED` re-idrata le automazioni armate e ri-registra i trigger.
- **Onboarding OEM** → wizard OxygenOS (battery-optimization exemption, auto-launch, lock in recents). **L'exemption è richiesta già in P1** (senza, le chiamate `InvokeLlm` in Doze falliscono — rete negata in background).

> **Onestà batteria:** "batteria bassa" è **relativo**. Il design evita il costo grosso (loop vision continuo) e legge lo stato **lazy** (solo se un trigger matcha una regola armata — rev 3), ma un processo residente 24/7 + NotificationListener ha un **baseline non-zero**. L'obiettivo è "trascurabile per un'app di automazione", non "zero".

## 10. Modello di sicurezza

1. **Gate approvazione**: automazione creata dall'LLM nasce `pending_approval`; la UI mostra la regola in chiaro; l'utente **arma** con un tap. Modifica LLM di una regola armata → torna `pending_approval`. **(Rev 3)** La regola mostrata è **renderizzata deterministicamente dai tipi** (`RuleRender`), mai dalla parafrasi dell'LLM: la prosa del modello è visivamente subordinata ("commento"), perché potrebbe non corrispondere al JSON. Il draft passa dal **`DraftValidator`**: ERROR → bottone Arma disabilitato con motivo; WARNING → mostrati inline.
2. **Catalogo sempre-conferma** (irreversibili): `pm uninstall`, factory reset, invio a contatti **non** whitelistati, pagamenti, cancellazione file → conferma live (push approva/nega) anche se armata. **(Rev 3)** Policy `run_shell`: un `RunShell` statico è eseguibile autonomo **solo** se l'utente ha approvato **quel comando letterale** all'arm (mostrato in monospace); qualunque modifica al comando → ri-approvazione. La classificazione per categorie (es. riconoscere `pm uninstall` dentro una shell) è best-effort via denylist di pattern: i pattern matchati forzano la conferma live.
3. **Whitelist messaggistica**: `WhatsAppReply`/SMS solo verso contatti whitelistati (per `conversationId`/numero). **(Rev 3)** Le reply generative sono limitate a chat **1:1** (`isGroup=false` obbligatorio nel trigger): mai rispondere in un gruppo (il titolo/sender di gruppo è ambiguo e l'output finirebbe nel canale del gruppo).
4. **Difesa prompt-injection**: contenuti da schermo/notifiche/web taggati **untrusted** e wrappati con delimitatori ("dati, non istruzioni"); al fire-time il set di tool è **ristretto a `allowed_tools`** dichiarati nella regola. **Containment:** la risposta generativa ha **`reply_target` vincolato al `trigger.sender`**, non scelto dall'LLM → un messaggio malevolo non può redirigere l'output verso un altro destinatario. La `compile` avviene **solo in chat** (umano presente), mai autonomamente da contenuto untrusted. **(Rev 3) Invariante hard:** `allowed_tools` di `InvokeLlm` non può contenere `shell.run`, `app.install`, `automation.*` né tool del catalogo sempre-conferma — altrimenti l'LLM genererebbe al fire-time azioni arbitrarie mai approvate. Enforcement nel `DraftValidator` (ERROR) **e** ri-verificato al fire-time (difesa in profondità). La UI di approvazione evidenzia inoltre la combinazione "tool di **lettura** (screen/state) + canale in **uscita** (reply)" come warning privacy (possibile esfiltrazione di contesto verso il mittente).
5. **Budget guard**: tetto globale chiamate LLM/ora; stima costo mostrata all'approvazione per le regole generative. In P1 il contenimento è garantito da cooldown minimo 60 s + rate-limit per-regola; il tetto globale arriva con la UI in P3.
6. **Audit log**: ogni scatto + azione + soppressione (cooldown/condizioni) via `AuditSink` (interfaccia P0-A, Room in P0-B), consultabile in UI.

## 11. Esempi tracciati

- **Es. 1** — `compile` (CliBridge, one-shot) produce `Automation{trigger:Geofence(resolveCurrentLocation=true, 50m→**warning raggio piccolo**, exit), actions:[SetWifi(off),SetBluetooth(on)]}` → all'**arm** l'app legge il GPS e fissa lat/lng → Play Services registra il geofence → scatto = 2 azioni deterministiche. Zero LLM a regime. *(End-to-end da P2 — il registrar geofence è P2; compile+engine pronti da P0.)*
- **Es. 2** — `Automation{trigger:Time(cron "0 23 * * *", tz), conditions:StateEquals(ringer,!=,silent), actions:[SetDnd(priority)]}` → AlarmManager exact (next-fire da `TimeSpecs`) sveglia l'engine, valuta condizione, esegue. Deterministico. *(End-to-end da P0.)*
- **Es. 3** — `Automation{trigger:Notification(pkg=whatsapp, conversationId=<id conv Moglie>, isGroup=false), conditions:TimeWindow(X), actions:[InvokeLlm{goal, context:[notification], allowed_tools:[whatsapp_reply], reply_target=trigger.sender, output:reply}]}` → NotificationListener scatta → executor ritorna `Submitted`, lane async → agent-loop (CliBridge one-shot) con tool ristretti → Brain compone → invio via RemoteInput al mittente (fallback E13 se la notifica è sparita nel frattempo). LLM al fire-time, solo per generare, solo quel tool, solo verso quella conversazione 1:1. *(End-to-end da P1.)*

## 12. Handoff frontend (Claude Design)

**→ Documento dedicato: `handoff-frontend.md`** (nel bundle). Contiene: baseline design (M3, dark, italiano), navigazione, **contratti di stato completi per tutti gli schermi** (Chat, Lista, Dettaglio/Approvazione, Log, Settings, Onboarding), stati Shizuku, error/empty state, direttive di sicurezza UI (rendering deterministico, badge generativo/privacy, `run_shell` monospace), superfici notifica, componenti condivisi, cosa NON progettare.

Punto chiave da non perdere: **l'MVP non ha streaming** — il transport primario è one-shot con attese di 10-30 s; la chat va progettata attorno a uno stato di attesa lungo e onesto, non attorno a token streaming (quello arriva in P3 con `OpenAICompatTransport`).

## 13. Edge case, barriere invalicabili, conflitti — e soluzioni

### Barriere invalicabili (hard blockers)

| # | Barriera | Soluzione |
|---|----------|-----------|
| B1 | **Shizuku muore al reboot** (non-root): un'app non può ri-concedersi Shizuku da sola. | **Root = percorso supportato** (OnePlus rootato): auto-start Shizuku via servizio Magisk/init al boot. Non-root = **degradato** (self-start Wireless-ADB ove possibile, altrimenti coda azioni shell + notifica "riattiva Shizuku"; le azioni via API Android normali continuano). Stato `DEGRADED_AFTER_REBOOT` visibile in UI. |
| B2 | **FGS non avviabile da background** (Android 12+). | **Non avviamo mai un FGS da background senza esenzione**: il service è già persistente; i callback (geofence/alarm) e il NotificationListener (già bindato) lavorano nel service in esecuzione. Il watchdog (B4) dipende dalla battery exemption — nesso esplicitato in §9. |
| B3 | **Exact alarm revocabile** (Android 13+). | `USE_EXACT_ALARM` (ok per app di automazione sideloaded) o guida a "Sveglie e promemoria"; fallback inexact+WorkManager per timing non critico. |
| B4 | **OxygenOS uccide il foreground service**. | Battery-optimization exemption (che abilita anche il restart FGS dal background) + wizard auto-launch/lock-recents + `START_STICKY` + **watchdog AlarmManager**. Best-effort documentato: rischio residuo accettato; senza exemption il watchdog è inerte → banner "protezione ridotta". |
| B5 | ~~Gateway Hermes senza endpoint programmatico~~ | **RISOLTO:** `argus-bridge` `/compile` v1 è live su Tailscale HTTPS, autenticato e testato da Android; `hermes proxy` resta l'opzione P3 OpenAI-compatible. Vedi §2/§7. |
| B6 | **Aux-vision senza provider multimodale raggiungibile**. | Configurare almeno **una key multimodale** (Gemini free); il probe avvisa e **disabilita** le regole che dipendono dalla visione schermo. |
| B7 | **Finestre `FLAG_SECURE`** (banking): `screencap` torna nero. | Rilevare e riportare al Brain ("schermo illeggibile: finestra sicura"); provare `uiautomator dump` come fallback (l'albero a11y resta leggibile); non crashare. |
| B8 | **Latenza del brain gratuito** (CliBridge ~10-30 s/call): inusabile per un loop computer_use multi-turn (200 s+/task). | **Il brain gratuito serve solo one-shot** (compile, InvokeLlm). Il **loop interattivo (P3)** usa `OpenAICompatTransport` con un modello veloce (proxy Nous/xAI o Direct). Trade-off costo/velocità esplicito. |

### Conflitti

| # | Conflitto | Soluzione |
|---|-----------|-----------|
| C1 | **Automazioni contraddittorie** (A: wifi off su exit; B: wifi on a orario) → thrash. | **Euristica trigger-aware** all'arm-time (rev 3): stesso target-key + valori opposti → warning, **ma le coppie complementari legittime sono soppresse** (stesso geofence con transizioni opposte; stessa connectivity con state opposti) — altrimenti ogni normale coppia on/off genererebbe rumore e il warning perderebbe significato. **Non** analisi statica completa (indecidibile). Runtime: **last-writer-wins con `priority` crescente = il più prioritario esegue ultimo e vince** (semantica definita, §5) + action log. |
| C2 | **Trigger storm** (spam notifiche; geofence flapping). | **Debounce/cooldown per-regola** (`cooldown_ms`), **minimo 60 s imposto per regole generative** (rev 3 — evita anche il ping-pong conversazionale), geofence `dwell`+`loiteringDelay`+isteresi, rate-limit su `InvokeLlm`. Denylist del proprio package nel trigger Notification (anti auto-trigger). |
| C3 | **Fire concorrenti** sull'unica shell Shizuku. | Executor a **coda single-writer con priorità**; azioni generative → **`ActionResult.Submitted`** e lane async (contratto esplicito, rev 3): l'engine non blocca mai su un InvokeLlm. Un'eccezione su una regola **non interrompe il batch** (isolamento per-automazione). |
| C4 | **Runaway costo/batteria** da regola generativa mal configurata. | Cooldown minimo 60 s (engine) + **budget guard** globale (max chiamate/ora, P3) + stima costo all'approvazione + warning UI. |

### Edge case

| # | Edge case | Soluzione |
|---|-----------|-----------|
| E1 | **Latenza LLM vs trigger transitorio**. | **Snapshot del contesto al fire-time** (fatto dalla lane async prima della chiamata); timeout azione; per l'urgente preferire deterministico. |
| E2 | **Tap per coordinate rotto** da risoluzione/orientamento/tema. | Preferire targeting via `uiautomator dump` (elemento per testo/id + bounds); coord ultima spiaggia; re-dump prima di agire + check stato atteso. |
| E3 | **RemoteInput WhatsApp assente** (notifica senza reply action). | Primario = RemoteInput; fallback tier-2 = automazione UI (più fragile); se nessuno → notifica "impossibile auto-rispondere". |
| E4 | **Ban/ToS WhatsApp** per invio "come utente". | Preferire RemoteInput (indistinguibile da reply normale) all'UI injection; rate-limit; uso personale; whitelist-gated. Rischio documentato. |
| E5 | **Permesso revocato a runtime**. | Capability probe ad ogni scatto + periodico; regola con capability mancante → **pausa** (non fail silenzioso) + notifica; visibile in UI. |
| E6 | **Timezone/DST per cron**. | Cron con **TZ esplicita**; next-fire calcolato in engine-core (`CronSchedule`, unit-testato sui casi DST): ora saltata → fire spostato avanti della durata del gap; ora duplicata → **una sola** esecuzione (prima occorrenza). |
| E7 | **Ambiguità nome contatto** ("Moglie" → contatto sbagliato). | Whitelist per **`conversationId`/numero**; il binding avviene al compile **scegliendo dalla whitelist nel manifest** (coppie nome+id — niente tool contatti necessario); mostrato in approvazione. |
| E8 | **Migrazione schema automazioni** dopo update app. | `schemaVersion` + Room migrations; decode fallito o versione incompatibile → regola `needs_review` (mai drop silenzioso). |
| E9 | **Recovery post-reboot** dei trigger. | Receiver `BOOT_COMPLETED` re-idrata e ri-registra (richiede Shizuku up — vedi B1). |
| E10 | **OAuth token DirectLlmBrain scaduto/ruotato**. | Refresh flow; fallback a CliBridge/Hermes se configurato, altrimenti notifica re-auth. Direct è **secondario** (P3). |
| E11 | **Privacy — i contenuti escono dall'homelab** (WhatsApp/schermo → Hermes → upstream Nous/xAI/OpenRouter/OpenAI). | Informativa esplicita nell'onboarding (step WELCOME_PRIVACY, consenso) + warning per-regola sulle azioni generative. Opzione futura: upstream self-hosted/locale per contenuti sensibili. Redazione opzionale di campi sensibili prima dell'invio. |
| E12 | **Contesto WhatsApp limitato alla notifica** (non l'intera conversazione). | Documentato: la risposta generativa vede solo il testo notificato; per più contesto servirebbe UI automation più pesante (fuori MVP). |
| E13 | **(Rev 3) RemoteInput invalidato durante la latenza LLM** (10-30 s: l'utente legge sul PC → notifica dismessa → canale reply morto). Caso **frequente**, non teorico. | La lane async ri-verifica la notifica prima dell'invio; se sparita → **notifica locale "Risposta pronta — tocca per inviare"** (apre WhatsApp con testo in clipboard o re-invia se la notifica ricompare). Mai far finta di aver risposto: l'audit registra `FAILED(canale scaduto)` o `DEFERRED(consegna manuale)`. |
| E14 | **(Rev 3) Geofence: aspettative realistiche.** Play Services raccomanda raggio ≥100 m; l'exit può arrivare con **minuti** di ritardo (batching a schermo spento). | Il validator emette WARNING sotto i 100 m; la UI di approvazione mostra "scatto entro ~2-15 min dall'uscita". L'Es. 1 con ±50 m è accettato ma con aspettative dichiarate. |
| E15 | **(Rev 3) Sender di notifica spoofabile** (il display name è controllato dal mittente: chiunque può chiamarsi "Moglie"). | Match preferito su **`conversationId`** (chiave stabile della conversazione: shortcutId/JID — da confermare empiricamente in P1, vedi §16); il match per display name resta come fallback ma il validator lo marca WARNING ("match per nome, spoofabile"). Le regole generative **richiedono** conversationId whitelistato. |

## 14. Strategia di test

- **Unit (table-driven):** interprete regole (match trigger, condizioni AND/OR/NOT, dispatch, priorità/last-writer-wins, isolamento errori, cooldown minimo generativo), **CronSchedule/TimeSpecs (incl. DST gap/overlap Europe/Rome)**, `DraftValidator` (incl. invarianti sicurezza), (de)serializzazione schema + migrazioni, euristica conflitti (incl. soppressione coppie complementari), parser output CliBridge (sentinel/JSON bilanciato, fail-soft).
- **Instrumented (device reale):** op Shizuku (screencap/tap/shell/install/uiautomator), registrar trigger, boot recovery, **estrazione `conversationId`/`isGroup` dalle notifiche WhatsApp reali** (E15).
- **Contract test Brain:** risposte mockate per entrambi i transport → tool-call/JSON attesi.
- **Injection test:** contenuto untrusted non escala oltre `allowed_tools`, non cambia `reply_target`, e un draft con tool vietati **viene rifiutato dal validator** (e ri-bloccato al fire-time).
- **Golden/eval:** i 3 prompt NL → `AutomationDraft` atteso (Brain mockato) → `DraftValidator` verde.

## 15. Phasing (base per la divisione task agent-driven)

Principio: **prima il valore nuovo e latency-tolerant** (automazioni da NL, che oggi non hai), **dopo il loop schermo interattivo** (latency-sensitive e che già ottieni via Claude Code+ADB).

- **P0-A — Engine core (JVM puro):** modelli + matcher + evaluator + engine + **cron/DST** + **validator** + conflitti + parser + manifest + interfacce. Piano dedicato nel bundle. *(Nessun risultato utente-visibile: fondamenta testate.)*
- **P0-B — Fondamenta Android + compile one-shot:** `core-shizuku` + `device-tools` + capability probe + `Brain`(CliBridge) + agent-loop one-shot + Room store + trigger **Time** + azioni deterministiche + gate approvazione + UI minima. **Risultato: Es. 2 funziona end-to-end** (chat "crea automazione" → approva → esegue). L'Es. 1 è pronto lato compile+engine ma **scatta davvero solo da P2** (registrar geofence). Niente vision, niente loop interattivo.
- **P1 — Generativo + notifiche:** trigger **Notification** (WhatsApp RemoteInput, estrazione `conversationId`/`isGroup`) + azione **`InvokeLlm` one-shot** (lane async + `Submitted`) + whitelist contatti + `reply_target` binding + fallback E13 + **battery-optimization exemption** (richiesta qui, non in P2: senza, le chiamate LLM in Doze falliscono). **Risultato: Es. 3 funziona.**
- **P2 — Hardening background:** `foreground-service` + anti-Doze + watchdog + boot recovery + wizard OEM/background-location + trigger **Geofence** (→ **Es. 1 end-to-end**) e **PhoneState**/**Connectivity** + resilienza Shizuku + (opz.) `triggers-ui`.
- **P3 — Interattivo + secondo brain + sicurezza avanzata:** **loop computer_use interattivo** via `OpenAICompatTransport` (modello veloce/multimodale) + `DirectLlmBrain` (OAuth) + tool app-side `web.search`/`vision.analyze` + conferma-irreversibili live + budget guard UI + streaming chat.

## 16. Rischi aperti da confermare in planning

- **`conversationId` WhatsApp (E15):** verificare empiricamente su device quale chiave stabile espongono le notifiche (candidati: `shortcutId`, `tag`, `EXTRA_PEOPLE_LIST`, sortKey) e come si rileva `isGroupConversation`. Prerequisito P1; se nessuna chiave è affidabile, fallback dichiarato al display name (con warning permanente in UI).
- **Transport loop interattivo (P3):** quale modello veloce/multimodale per `OpenAICompatTransport` — proxy Nous/xAI (verificare costo/quota e supporto immagini) vs Direct Gemini/OpenRouter. Budget latenza per-turno target.
- **Argus bridge:** `/compile` v1 completato e isolato dal bridge Guida Bali. Per `act` P1 estendere il contratto Argus con una nuova versione/endpoint, senza riaprire il fallback `/chat`.
- **B1:** meccanismo esatto di persistenza Shizuku post-reboot sul OnePlus 15 rootato (Magisk module vs init vs opzione root nell'app Shizuku).
- **B6:** provider multimodale per aux-vision (Gemini free candidato).
- Policy `USE_EXACT_ALARM` (sideload-only accettabile).

## 17. Fuori scope (YAGNI)

- Play Store distribution (app sideloaded personale).
- Multi-utente / multi-profilo.
- Editor visuale di regole drag-and-drop (le regole le crea l'LLM; la UI le mostra/approva).
- Sync cloud delle automazioni (locale; **export/import JSON locale** invece è quasi gratis — nice-to-have P2, assicura contro un wipe).
- Trigger `Time.interval` arbitrario (i periodici comuni sono esprimibili in cron; interval non-cron eventualmente via WorkManager, se mai servirà).
- Loop computer_use interattivo su brain gratuito (impedito dalla latenza — vedi B8).
- Reply generative nei gruppi WhatsApp (vietate in MVP — §10.3).
