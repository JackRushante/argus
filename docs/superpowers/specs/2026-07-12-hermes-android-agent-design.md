# Argus — Agente LLM di automazione Android (design)

> **Nome di lavoro:** *Argus* (il gigante dai molti occhi — agente always-on che "vede" lo schermo). Provvisorio, rinominabile.
> **Data:** 2026-07-12 · **Rev:** 2 (post verifica empirica Hermes) · **Autore:** Lorenzo Marci + Claude Code (oneplus)
> **Stato:** design approvato in brainstorming + rianalisi, pronto per handoff frontend (Claude Design) e writing-plans.

## 1. Obiettivo

App Android nativa che è un **motore di automazione stile Tasker/MacroDroid sempre vivo**, in cui un **LLM è il compilatore**: l'utente parla in linguaggio naturale e l'LLM traduce la richiesta in **regole concrete** (trigger → condizioni → azioni) che il motore poi esegue **da solo**, senza LLM a ogni scatto. L'LLM torna in gioco al fire-time solo quando l'azione richiede ragionamento/generazione (es. comporre una risposta WhatsApp). L'app opera con **permessi elevati via Shizuku** (parità con `adb`): vede lo schermo, clicca, usa il terminale, installa pacchetti, legge gli stati del telefono.

Esempi target (devono funzionare a fine P1):
1. *"Crea un geofence sulla mia loc GPS attuale ±50 m; quando esco disattiva wifi e attiva bluetooth."*
2. *"Dopo le 23 controlla lo stato della suoneria e metti DND."*
3. *"Se il contatto Moglie manda un WhatsApp in questa fascia oraria, rispondile tu con un messaggio nel tono X/Y."*

## 2. Contesto (verificato empiricamente su `hermes`)

- **Hermes** (VM `hermes` 100.80.142.65, sul tailnet) ha già `computer_use` con **vision-routing**: se il cervello (`gpt-5.5` via `openai-codex`, OAuth) non è multimodale, lo screenshot viene **pre-analizzato dalla pipeline `auxiliary.vision`** (provider `auto` → OpenRouter/Gemini/Claude) e passato come **testo** al cervello, fuso con l'albero accessibilità/SOM. Web search già attiva via **Brave free**. → Vision e web **non richiedono API dirette**; sono capability ausiliarie disaccoppiate dal canale principale.
- **Transport HTTP verso Hermes — VERIFICATI (risolve il vecchio rischio B5):**
  - **`guida-agent/bridge.py`** (porta 8090) è un **reference implementation dello stesso pattern di Argus**: riceve `{message, history, state, ...}`, chiama Hermes e ritorna `{reply, actions[], memory[], ...}` in JSON che **il client esegue lato suo**. Invoca il modello via **subprocess CLI** (`hermes_cli.main -z <prompt> --cli --ignore-rules -t web --yolo`), toolset ristretto a `web`. Output strutturato via **sentinel `@@META@@ {json}`** + regex (non tool-call nativi). **Latenza ~10-30 s/chiamata.**
  - **`hermes proxy`** (OpenAI-compatible, `/v1/chat/completions`, porta 8645) inoltra a upstream **`nous` o `xai`** con le credenziali OAuth di Hermes. **NON** instrada il codex/ChatGPT gpt-5.5 gratuito.
- **Conseguenza sul transport** (vedi §7): il **gpt-5.5 codex gratis** è raggiungibile **solo** via CLI/bridge (lento, one-shot, testo+JSON). Il **path veloce con tool-call nativi** esiste solo via proxy (Nous/xAI) o LLM diretto (OpenAI/OpenRouter/Gemini), che costa/consuma quota.
- Il **telefono è già sul tailnet** come `oneplus` (100.74.117.9) → Hermes raggiungibile in rete privata senza esporre nulla.
- Il OnePlus 15 è **rootabile/rootato** (rilevante per la persistenza di Shizuku, §9/B1).

## 3. Decisioni di design

| # | Decisione | Scelta |
|---|-----------|--------|
| D1 | Dove vive il cervello | **App standalone con Brain pluggable**; l'app è sempre padrona del loop, il Brain è puro servizio di ragionamento |
| D2 | Scope | **Motore always-live** (Tasker-class, anti-Doze); la chat è l'interfaccia di **configurazione** (LLM compila le regole), non solo esecuzione |
| D3 | Rappresentazione regole | **Ibrido (C)**: trigger/condizioni dichiarativi + catalogo azioni tipizzate **+** jolly `run_shell` e `invoke_llm` |
| D4 | Sicurezza | **Approva-alla-creazione, poi autonomo** + catalogo sempre-conferma (irreversibili) + whitelist contatti + difesa injection |
| D5 | Modello transport Brain | **Due transport, non due provider** (rev 2): `CliBridge` (Hermes/codex gratis, one-shot, lento) + `OpenAICompat` (proxy Nous/xAI o LLM diretto, veloce, tool-call nativi) |
| D6 | Privilegi | **Shizuku come astrazione** (adb-mode o root-mode); shell UID (2000) come livello di privilegio di default, più sicuro di `su`. Root solo per auto-start Shizuku al boot |

## 4. Architettura

**Stack:** Kotlin, Jetpack Compose (UI), Room (persistenza), Hilt (DI), Coroutines/Flow, OkHttp/Ktor (Brain client), EncryptedSharedPreferences (segreti/token), AlarmManager + WorkManager, Play Services Location (geofencing), Shizuku API (`dev.rikka.shizuku`). Min SDK 30.

**Moduli (responsabilità singola, interfacce nette):**

| Modulo | Cosa fa | Fase |
|--------|---------|------|
| `core-shizuku` | Unico gateway privilegiato: shell UID via Shizuku (`newProcess`/user-service) + op tipizzate (screencap, input, pm, settings, dumpsys, svc, cmd, **uiautomator dump**). Coda single-writer con priorità | P0 |
| `device-tools` | Catalogo capacità tipizzate su Shizuku: schermo (capture / tap / swipe / type / **dump_ui via uiautomator**), stati (wifi/bt/dnd/batteria/foreground/gps), toggle, app (install/launch/intent) | P0 |
| `automation-engine` | Store regole (Room) + interprete deterministico: registra trigger, valuta condizioni (albero AND/OR), dispatcha azioni; **rilevatore conflitti euristico** | P0–P1 |
| `triggers` | Registrar OS-managed: Time/Cron (AlarmManager exact + WorkManager) → P0; Notification (NotificationListenerService + WhatsApp RemoteInput) → P1; Geofence (Play Services) + PhoneState (TelephonyCallback) + Connectivity/Power → P2 | P0→P2 |
| `brain` | Interfaccia `Brain` + **transport** `CliBridgeTransport` / `OpenAICompatTransport` | P0 |
| `agent-loop` | Orchestrazione tool-calling: one-shot (compile / InvokeLlm) → P0–P1; loop interattivo computer_use → P3 | P0→P3 |
| `foreground-service` | Servizio persistente: engine + trigger + anti-Doze + watchdog + boot recovery | P2 |
| `security` | Gate approvazione, allowlist irreversibili, whitelist contatti, difese injection, budget guard, audit | P1–P3 |
| `triggers-ui` *(opzionale)* | AccessibilityService per trigger su **eventi UI** e gesture senza shell. **Non serve al core** (lettura schermo = `uiautomator dump`, tap = `input`) | P2+ |
| `ui` *(Claude Design)* | Chat, lista/dettaglio/approvazione automazioni, log, settings, wizard permessi | tutte |

> **Nota AccessibilityService (rev 2):** rimosso dal core. Lettura schermo strutturata via `uiautomator dump` (Shizuku) e tap via `input tap` (Shizuku) coprono l'MVP senza il permesso di accessibilità. L'AccessibilityService serve solo se si vogliono trigger su eventi-UI in tempo reale → modulo opzionale P2+.

## 5. Modello di automazione (schema)

```
Automation {
  id, name, enabled, createdBy(llm|user), schemaVersion,
  status(pending_approval | armed | disabled | needs_review),
  priority: Int, cooldown_ms: Long,
  trigger, conditions[], actions[]
}

Trigger =
  Geofence{lat,lng,radius_m, transition(enter|exit|dwell), loiteringDelay_ms}
| Time{cron | at | interval, tz, window?}
| Notification{package, sender?, titleMatch?, textMatch?}
| PhoneState{event(incoming_call|call_ended|sms_received), number?}
| Connectivity{wifi(ssid?)|bt(device?)|power(plugged|unplugged)}

Condition = TimeWindow{tz} | StateEquals(key,op,val) | LocationIn | AppInForeground   // albero AND/OR

Action(tier=deterministic) =
  SetWifi|SetBluetooth|SetDnd|SetRinger|LaunchApp|OpenUrl
| Tap|InputText|WhatsAppReply(RemoteInput,text)|RunShell(cmd)          // jolly shell
Action(tier=generative) =
  InvokeLlm{ goal, context_sources:[screen|notification|state|...],
             allowed_tools:[...], reply_target(bound=trigger.sender),   // rev 2: destinatario vincolato
             output(reply|actions), timeout_ms }                        // jolly LLM
```

Whitelist contatti/target memorizzano **ID contatto / numero**, non il display name (§13/E7).

## 6. Due tier di esecuzione

- **Deterministico** — l'engine esegue direttamente via `device-tools`/Shizuku. Zero LLM, zero rete, wake minimo. (Es. 1 e 2.)
- **Generativo** (`InvokeLlm`) — l'engine sveglia l'agent-loop, **snapshotta** subito il contesto dichiarato (§13/E1), chiede al Brain risposta/azioni con **tool ristretti a `allowed_tools`** e **destinatario vincolato al `trigger.sender`**, esegue. Costa rete+LLM solo quando serve. (Es. 3.)

A regime i trigger OS-managed (geofence/alarm/notification-listener) scattano **anche in Doze** senza tenere vivo un loop attivo: "sempre live" ≠ "sempre sveglio".

## 7. Brain (pluggable) — modello a due transport

```kotlin
interface Brain {
  suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): AutomationDraft  // one-shot
  suspend fun act(context: FireContext, goal: String, allowedTools: List<ToolId>): ActResult          // one-shot
  fun chat(messages: List<Msg>, tools: List<Tool>): Flow<AgentEvent>                                   // loop interattivo (P3)
}
```

**Il Brain è configurato con un transport**, non con un provider. Due transport:

| Transport | Raggiunge | Latenza | Output | Vision / Web | Uso |
|-----------|-----------|---------|--------|--------------|-----|
| **`CliBridgeTransport`** | Hermes agent → **gpt-5.5 codex (gratis)** via bridge stile `guida-agent/bridge.py` | ~10-30 s/call | testo + JSON (sentinel/schema) | delegate a Hermes (aux-vision, Brave) | **compile** + **InvokeLlm one-shot** (latency-tolerant) |
| **`OpenAICompatTransport`** | proxy Hermes → **Nous/xAI**, *oppure* **LLM diretto** (OpenAI/OpenRouter/Gemini via OAuth/API) | bassa | tool-call nativi | app-side (`vision.analyze`, `web.search`) o modello multimodale | **loop interattivo computer_use** (P3), Direct brain |

Storia coerente: **gratis dove puoi aspettare** (compile, risposta WhatsApp = one-shot), **veloce/a-pagamento dove serve reattività** (pilotare lo schermo in tempo reale). Il `CliBridgeTransport` è l'MVP primario; l'`OpenAICompatTransport` unifica proxy-Hermes e LLM-diretto nello stesso adapter (cambia `base_url` + auth).

**Contratto tool esposto al Brain:**
- *Device tools:* `screen.capture` · `screen.tap` · `screen.swipe` · `screen.type` · `screen.dump_ui` · `state.read` · `toggle.set` · `app.launch` · `app.install` · `shell.run` · `web.search` · `vision.analyze`
- *Automation tools:* `automation.create(draft)` · `list` · `get` · `update` · `delete` · `simulate`

**Capability Manifest** (a inizio sessione): modello device, versione Android, stato Shizuku, permessi concessi, tool disponibili (da probe reale), whitelist attive → così l'agente "sa dove si trova e cosa può/non può fare".

## 8. Layer Shizuku

Unico punto che tocca Shizuku. Espone `PrivilegedShell.run(cmd): Result` + op tipizzate. Tutto ciò che si fa via `adb` gira identico (`input tap`, `screencap`, `pm install`, `settings put`, `dumpsys`, `svc`, `cmd`, `uiautomator dump`). **Esecuzione serializzata** su coda single-writer con priorità (§13/C3). Resilienza in §9/B1.

## 9. Sopravvivenza background / anti-Doze

Principio: appoggiarsi ai meccanismi **OS-managed** che scattano anche in Doze, non tenere vivo un loop.

- **Foreground service** (`specialUse`/`dataSync`) con notifica persistente a bassa priorità — ospita engine e listener.
- **Time** → `AlarmManager` `setExactAndAllowWhileIdle` + WorkManager per job tolleranti.
- **Geofence** → Play Services Geofencing (OS-managed, sopravvive a Doze, batteria bassa). **Richiede `ACCESS_BACKGROUND_LOCATION` ("Consenti sempre")**, permesso a sé notorio su Android 11+ → step dedicato nell'onboarding.
- **Notifiche/WhatsApp** → `NotificationListenerService` (bindato, resiste) + **WhatsApp direct-reply via RemoteInput**.
- **Watchdog** → AlarmManager heartbeat che resuscita il service se ucciso (`START_STICKY`).
- **Boot** → receiver `BOOT_COMPLETED` re-idrata le automazioni armate e ri-registra i trigger.
- **Onboarding OEM** → wizard OxygenOS (battery-optimization exemption, auto-launch, lock in recents).

> **Onestà batteria (rev 2):** "batteria bassa" è **relativo**. Il design evita il costo grosso (loop vision continuo), ma un processo residente 24/7 + NotificationListener ha un **baseline non-zero**. L'obiettivo è "trascurabile per un'app di automazione", non "zero".

## 10. Modello di sicurezza

1. **Gate approvazione**: automazione creata dall'LLM nasce `pending_approval`; la UI mostra la regola in chiaro (incluso ogni `run_shell`); l'utente **arma** con un tap. Modifica LLM di una regola armata → torna `pending_approval`.
2. **Catalogo sempre-conferma** (irreversibili): `pm uninstall`, factory reset, invio a contatti **non** whitelistati, pagamenti, cancellazione file → conferma live (push approva/nega) anche se armata.
3. **Whitelist messaggistica**: `WhatsAppReply`/SMS solo verso contatti whitelistati (per ID/numero).
4. **Difesa prompt-injection**: contenuti da schermo/notifiche/web taggati **untrusted** e wrappati con delimitatori ("dati, non istruzioni"); al fire-time il set di tool è **ristretto a `allowed_tools`** dichiarati nella regola. **Rev 2 — containment rafforzato:** la risposta generativa ha **`reply_target` vincolato al `trigger.sender`**, non scelto dall'LLM → un messaggio malevolo non può redirigere l'output verso un altro destinatario (al massimo genera un testo strano verso il mittente stesso). La `compile` avviene **solo in chat** (umano presente), mai autonomamente da contenuto untrusted.
5. **Budget guard**: tetto globale chiamate LLM/ora; stima costo mostrata all'approvazione per le regole generative.
6. **Audit log**: ogni scatto + azione in Room, consultabile.

## 11. Esempi tracciati

- **Es. 1** — `compile` (CliBridge, one-shot) legge `state.read(gps)` → `Automation{trigger:Geofence(lat,lng,50,exit), actions:[SetWifi(off),SetBluetooth(on)]}` → armata → Play Services registra il geofence → scatto = 2 azioni deterministiche. Zero LLM a regime.
- **Es. 2** — `Automation{trigger:Time(cron "0 23 * * *", tz), conditions:[StateEquals(ringer,!=,silent)], actions:[SetDnd(on)]}` → AlarmManager exact sveglia l'engine, valuta condizione, esegue. Deterministico.
- **Es. 3** — `Automation{trigger:Notification(pkg=whatsapp, sender=<id Moglie>), conditions:[TimeWindow(X)], actions:[InvokeLlm{goal, context:[notification], allowed_tools:[whatsapp_reply], reply_target=trigger.sender, output:reply}]}` → NotificationListener scatta → agent-loop (CliBridge one-shot) con tool ristretti → Brain compone → invio via RemoteInput al mittente. LLM al fire-time, solo per generare, solo quel tool, solo verso Moglie.

## 12. Handoff frontend (Claude Design)

Lo spec definisce **schermi + contratti di stato**; Claude Design produce visual + Compose contro quei contratti; il suo zip torna come componenti da cablare ai ViewModel/engine.

Schermi: (1) **Chat** — streaming, card tool-call, approvazioni inline; (2) **Automazioni · lista** — armate/pending/disabilitate + toggle; (3) **Automazione · dettaglio/approvazione** — regola in chiaro, arma/nega/modifica, warning conflitti/costo/privacy; (4) **Log esecuzioni** — timeline audit; (5) **Settings** — Brain (transport CliBridge/OpenAICompat, endpoint Hermes / login OAuth), stato Shizuku, whitelist; (6) **Onboarding/permessi** — wizard Shizuku, notification access, battery/OEM, **background location**.

Ogni schermo avrà nel plan il proprio **contratto di stato** (es. `AutomationDetailState{ ruleHuman:String, actions:List<ActionRow>, canArm:Boolean, warnings:List<String> }`) così il design è indipendente dall'implementazione.

## 13. Edge case, barriere invalicabili, conflitti — e soluzioni

### Barriere invalicabili (hard blockers)

| # | Barriera | Soluzione |
|---|----------|-----------|
| B1 | **Shizuku muore al reboot** (non-root): un'app non può ri-concedersi Shizuku da sola. | **Root = percorso supportato** (OnePlus rootato): auto-start Shizuku via servizio Magisk/init al boot. Non-root = **degradato** (self-start Wireless-ADB ove possibile, altrimenti coda azioni shell + notifica "riattiva Shizuku"; le azioni via API Android normali continuano). |
| B2 | **FGS non avviabile da background** (Android 12+). | **Non avviamo mai un FGS da background**: il service è già persistente; i callback (geofence/alarm) e il NotificationListener (già bindato) lavorano nel service in esecuzione. |
| B3 | **Exact alarm revocabile** (Android 13+). | `USE_EXACT_ALARM` (ok per app di automazione sideloaded) o guida a "Sveglie e promemoria"; fallback inexact+WorkManager per timing non critico. |
| B4 | **OxygenOS uccide il foreground service**. | Battery-optimization exemption + wizard auto-launch/lock-recents + `START_STICKY` + **watchdog AlarmManager**. Best-effort documentato: rischio residuo accettato. |
| B5 | ~~Gateway Hermes senza endpoint programmatico~~ | **RISOLTO (rev 2):** `guida-agent/bridge.py` è un reference impl HTTP funzionante; `hermes proxy` è OpenAI-compatible. Vedi §2/§7. |
| B6 | **Aux-vision senza provider multimodale raggiungibile**. | Configurare almeno **una key multimodale** (Gemini free); il probe avvisa e **disabilita** le regole che dipendono dalla visione schermo. |
| B7 | **Finestre `FLAG_SECURE`** (banking): `screencap` torna nero. | Rilevare e riportare al Brain ("schermo illeggibile: finestra sicura"); provare `uiautomator dump` come fallback; non crashare. |
| B8 | **Latenza del brain gratuito** (CliBridge ~10-30 s/call): inusabile per un loop computer_use multi-turn (200 s+/task). | **Il brain gratuito serve solo one-shot** (compile, InvokeLlm). Il **loop interattivo (P3)** usa `OpenAICompatTransport` con un modello veloce (proxy Nous/xAI o Direct). Trade-off costo/velocità esplicito. |

### Conflitti

| # | Conflitto | Soluzione |
|---|-----------|-----------|
| C1 | **Automazioni contraddittorie** (A: wifi off su exit; B: wifi on a orario) → thrash. | **Euristica semplice** all'arm-time (stesso target-key + valori opposti su spazi-trigger potenzialmente sovrapposti → warning in approvazione) — **non** analisi statica completa (indecidibile in generale). Runtime: **last-writer-wins** + campo `priority` + action log. |
| C2 | **Trigger storm** (spam notifiche; geofence flapping). | **Debounce/cooldown per-regola** (`cooldown_ms`), geofence `dwell`+`loiteringDelay`+isteresi, rate-limit su `InvokeLlm`. |
| C3 | **Fire concorrenti** sull'unica shell Shizuku. | Executor a **coda single-writer con priorità**; azioni generative (lente) in lane async senza bloccare le deterministiche. |
| C4 | **Runaway costo/batteria** da regola generativa mal configurata. | **Budget guard** globale (max chiamate/ora) + stima costo all'approvazione + warning UI. |

### Edge case

| # | Edge case | Soluzione |
|---|-----------|-----------|
| E1 | **Latenza LLM vs trigger transitorio**. | **Snapshot del contesto al fire-time**; timeout azione; per l'urgente preferire deterministico. |
| E2 | **Tap per coordinate rotto** da risoluzione/orientamento/tema. | Preferire targeting via `uiautomator dump` (elemento per testo/id + bounds); coord ultima spiaggia; re-dump prima di agire + check stato atteso. |
| E3 | **RemoteInput WhatsApp assente** (notifica senza reply action). | Primario = RemoteInput; fallback tier-2 = automazione UI (più fragile); se nessuno → notifica "impossibile auto-rispondere". |
| E4 | **Ban/ToS WhatsApp** per invio "come utente". | Preferire RemoteInput (indistinguibile da reply normale) all'UI injection; rate-limit; uso personale; whitelist-gated. Rischio documentato. |
| E5 | **Permesso revocato a runtime**. | Capability probe ad ogni scatto + periodico; regola con capability mancante → **pausa** (non fail silenzioso) + notifica; visibile in UI. |
| E6 | **Timezone/DST per cron**. | Cron con **TZ esplicita**; wall-clock via AlarmManager; semantica standard cron per ora saltata/duplicata. |
| E7 | **Ambiguità nome contatto** ("Moglie" → contatto sbagliato). | Whitelist per **ID contatto/numero**; risolto al compile-time e mostrato in approvazione. |
| E8 | **Migrazione schema automazioni** dopo update app. | `schemaVersion` + Room migrations; su cambio incompatibile → regola `needs_review`. |
| E9 | **Recovery post-reboot** dei trigger. | Receiver `BOOT_COMPLETED` re-idrata e ri-registra (richiede Shizuku up — vedi B1). |
| E10 | **OAuth token DirectLlmBrain scaduto/ruotato**. | Refresh flow; fallback a CliBridge/Hermes se configurato, altrimenti notifica re-auth. Direct è **secondario** (P3). |
| E11 | **Privacy — i contenuti escono dall'homelab** (WhatsApp/schermo → Hermes → upstream Nous/xAI/OpenRouter/OpenAI). | Da mettere nero su bianco nell'onboarding + warning per-regola sulle azioni generative. Opzione futura: upstream self-hosted/locale per contenuti sensibili. Redazione opzionale di campi sensibili prima dell'invio. |
| E12 | **Contesto WhatsApp limitato alla notifica** (non l'intera conversazione). | Documentato: la risposta generativa vede solo il testo notificato; per più contesto servirebbe UI automation più pesante (fuori MVP). |

## 14. Strategia di test

- **Unit (table-driven):** interprete regole (match trigger, condizioni AND/OR, dispatch), (de)serializzazione schema + migrazioni, helper sicurezza (restrizione tool + `reply_target` binding al fire-time), euristica conflitti, parser output CliBridge (sentinel/JSON).
- **Instrumented (device reale):** op Shizuku (screencap/tap/shell/install/uiautomator), registrar trigger, boot recovery.
- **Contract test Brain:** risposte mockate per entrambi i transport → tool-call/JSON attesi.
- **Injection test:** contenuto untrusted non escala oltre `allowed_tools` **e** non cambia `reply_target`.
- **Golden/eval:** i 3 prompt NL → `AutomationDraft` atteso (Brain mockato).

## 15. Phasing (base per la divisione task agent-driven) — riordinato in rev 2

Principio: **prima il valore nuovo e latency-tolerant** (automazioni da NL, che oggi non hai), **dopo il loop schermo interattivo** (latency-sensitive e che già ottieni via Claude Code+ADB).

- **P0 — Fondamenta + compile one-shot:** `core-shizuku` + `device-tools` + capability probe + `Brain`(CliBridge) + `agent-loop` one-shot + `automation-engine` base + trigger **Time** + azioni deterministiche + gate approvazione. **Risultato: Es. 1 e 2 funzionano** (chat "crea automazione" → approva → esegue). Niente vision, niente loop interattivo.
- **P1 — Generativo + notifiche:** trigger **Notification** (WhatsApp RemoteInput) + azione **`InvokeLlm` one-shot** + whitelist contatti + `reply_target` binding. **Risultato: Es. 3 funziona.**
- **P2 — Hardening background:** `foreground-service` + anti-Doze + watchdog + boot recovery + wizard OEM/background-location + trigger **Geofence** e **PhoneState** + resilienza Shizuku + (opz.) `triggers-ui` (AccessibilityService per trigger UI).
- **P3 — Interattivo + secondo brain + sicurezza avanzata:** **loop computer_use interattivo** via `OpenAICompatTransport` (modello veloce/multimodale) + `DirectLlmBrain` (OAuth) + tool app-side `web.search`/`vision.analyze` + conferma-irreversibili + budget guard + audit UI + euristica conflitti.

## 16. Rischi aperti da confermare in planning

- **Transport loop interattivo (P3):** quale modello veloce/multimodale per `OpenAICompatTransport` — proxy Nous/xAI (verificare costo/quota e supporto immagini) vs Direct Gemini/OpenRouter. Budget latenza per-turno target.
- **CliBridge da generalizzare:** riusare/estendere `guida-agent/bridge.py` per esporre `compile`/`act` (schema `AutomationDraft`) oltre a `/chat`; definire lo schema JSON strutturato e il parser robusto.
- **B1:** meccanismo esatto di persistenza Shizuku post-reboot sul OnePlus 15 rootato (Magisk module vs init).
- **B6:** provider multimodale per aux-vision (Gemini free candidato).
- Policy `USE_EXACT_ALARM` (sideload-only accettabile).

## 17. Fuori scope (YAGNI)

- Play Store distribution (app sideloaded personale).
- Multi-utente / multi-profilo.
- Editor visuale di regole drag-and-drop (le regole le crea l'LLM; la UI le mostra/approva).
- Sync cloud delle automazioni (locale; eventuale backup via homelab in futuro).
- Loop computer_use interattivo su brain gratuito (impedito dalla latenza — vedi B8).
