# Argus — Agente LLM di automazione Android (design)

> **Nome di lavoro:** *Argus* (il gigante dai molti occhi — agente always-on che "vede" lo schermo). Provvisorio, rinominabile.
> **Data:** 2026-07-12 · **Autore:** Lorenzo Marci + Claude Code (oneplus)
> **Stato:** design approvato in brainstorming, pronto per handoff frontend (Claude Design) e writing-plans.

## 1. Obiettivo

App Android nativa che è un **motore di automazione stile Tasker/MacroDroid sempre vivo**, in cui un **LLM è il compilatore**: l'utente parla in linguaggio naturale e l'LLM traduce la richiesta in **regole concrete** (trigger → condizioni → azioni) che il motore poi esegue **da solo**, senza LLM a ogni scatto. L'LLM torna in gioco al fire-time solo quando l'azione richiede ragionamento/generazione (es. comporre una risposta WhatsApp). L'app opera con **permessi elevati via Shizuku** (parità con `adb`), può vedere lo schermo, cliccare, usare il terminale, installare pacchetti e leggere gli stati del telefono.

Esempi target (devono funzionare a fine P1):
1. *"Crea un geofence sulla mia loc GPS attuale ±50 m; quando esco disattiva wifi e attiva bluetooth."*
2. *"Dopo le 23 controlla lo stato della suoneria e metti DND."*
3. *"Se il contatto Moglie manda un WhatsApp in questa fascia oraria, rispondile tu con un messaggio nel tono X/Y."*

## 2. Contesto (verificato)

- **Hermes** (VM `hermes` 100.80.142.65, sul tailnet) ha già: tool `computer_use(action='capture')` con **vision-routing** — se il cervello (`gpt-5.5` via `openai-codex`, OAuth) non è multimodale, lo screenshot viene **pre-analizzato dalla pipeline `auxiliary.vision`** (provider `auto` → OpenRouter/Gemini/Claude) e passato come **testo** al cervello, fuso con l'albero accessibilità/SOM. Web search già attiva via **Brave free** (`search_backend: brave-free`, `BRAVE_SEARCH_API_KEY` impostata). → Vision e web **non richiedono API dirette**; sono capability ausiliarie disaccoppiate dal canale principale.
- Il **telefono è già sul tailnet** come `oneplus` (100.74.117.9) → Hermes è raggiungibile in rete privata senza esporre nulla.
- Il OnePlus 15 è **rootabile/rootato** (rilevante per la persistenza di Shizuku, §9).

## 3. Decisioni di design (dal brainstorming)

| # | Decisione | Scelta |
|---|-----------|--------|
| D1 | Dove vive il cervello | **App standalone con Brain pluggable** (agent loop on-device; Brain intercambiabile Hermes ↔ LLM diretto OAuth) |
| D2 | Scope | **Motore always-live** (Tasker-class, anti-Doze); la chat è l'interfaccia di **configurazione** (LLM compila le regole), non solo esecuzione |
| D3 | Rappresentazione regole | **Ibrido (C)**: trigger/condizioni dichiarativi + catalogo azioni tipizzate **+** jolly `run_shell` e `invoke_llm` per la coda lunga |
| D4 | Sicurezza | **Approva-alla-creazione, poi autonomo** + catalogo azioni sempre-conferma (irreversibili) + whitelist contatti + difesa injection |

## 4. Architettura

**Stack:** Kotlin, Jetpack Compose (UI), Room (persistenza), Hilt (DI), Coroutines/Flow, OkHttp/Ktor (Brain client), EncryptedSharedPreferences (segreti/token), AlarmManager + WorkManager, Play Services Location (geofencing), Shizuku API (`dev.rikka.shizuku`). Min SDK 30.

**Moduli (responsabilità singola, interfacce nette):**

| Modulo | Cosa fa | Dipende da |
|--------|---------|-----------|
| `core-shizuku` | Unico gateway privilegiato: shell UID via Shizuku (`newProcess`/user-service) + op tipizzate (screencap, input, pm, settings, dumpsys, svc, cmd) | Shizuku |
| `device-tools` | Catalogo capacità tipizzate: schermo (capture/tap/swipe/type/dump_ui), stati (wifi/bt/dnd/batteria/foreground/gps), toggle, app (install/launch/intent) | core-shizuku, Android APIs |
| `automation-engine` | Store regole (Room) + interprete deterministico: registra trigger, valuta condizioni (albero AND/OR), dispatcha azioni; rilevatore conflitti | triggers, device-tools |
| `triggers` | Registrar OS-managed: Geofence (Play Services), Time/Cron (AlarmManager exact + WorkManager), Notification (NotificationListenerService + WhatsApp RemoteInput), PhoneState (TelephonyCallback), Connectivity/Power (BroadcastReceiver) | — |
| `brain` | Interfaccia `Brain` pluggable + adapter `HermesBrain` / `DirectLlmBrain` | device-tools (per tool schema) |
| `agent-loop` | Loop tool-calling on-device (computer_use locale): esegue i tool localmente, rimanda i risultati al Brain | brain, device-tools |
| `foreground-service` | Servizio persistente che ospita engine + trigger + gestione anti-Doze + watchdog | automation-engine |
| `security` | Gate approvazione, allowlist irreversibili, whitelist contatti, difese injection, budget guard, audit | — |
| `ui` *(Claude Design)* | Chat, lista/dettaglio/approvazione automazioni, log, settings, wizard permessi | tutti i ViewModel |

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
             allowed_tools:[...], output(reply|actions), timeout_ms }  // jolly LLM
```

Whitelist contatti/target memorizzano **ID contatto / numero**, non il display name (§13).

## 6. Due tier di esecuzione

- **Deterministico** — l'engine esegue direttamente via `device-tools`/Shizuku. Zero LLM, zero rete, wake minimo. (Es. 1 e 2.)
- **Generativo** (`InvokeLlm`) — l'engine sveglia l'agent-loop, **snapshotta** subito il contesto dichiarato (§13 #13), chiede al Brain risposta/azioni con **tool ristretti a `allowed_tools`**, esegue. Costa rete+LLM solo quando serve. (Es. 3.)

A regime i trigger OS-managed (geofence/alarm/notification-listener) scattano **anche in Doze** senza tenere vivo un loop attivo: "sempre live" ≠ "sempre sveglio".

## 7. Brain (pluggable) + contratto tool

```kotlin
interface Brain {
  suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): AutomationDraft
  suspend fun act(context: FireContext, goal: String, allowedTools: List<ToolId>): ActResult
  fun chat(messages: List<Msg>, tools: List<Tool>): Flow<AgentEvent>
}
```

| | `HermesBrain` | `DirectLlmBrain` |
|---|---|---|
| Trasporto | HTTP/WS al gateway Hermes (via tailnet) | endpoint OpenAI-compatible via **OAuth token** (no API) |
| Vision | delegata a Hermes (`computer_use`/aux-vision) — l'app manda i byte PNG | tool app-side `vision.analyze` → endpoint multimodale configurato (es. Gemini free) |
| Web search | delegata a Hermes (Brave free) | tool app-side `web.search` (Brave free) |

L'app resta **sempre padrona del loop** (serve al motore always-live); il Brain è puro servizio di ragionamento. MVP wirato su un Brain, l'altro è un adapter aggiuntivo (P3).

**Contratto tool esposto al Brain:**
- *Device tools:* `screen.capture` · `screen.tap` · `screen.swipe` · `screen.type` · `screen.dump_ui` · `state.read` · `toggle.set` · `app.launch` · `app.install` · `shell.run` · `web.search` · `vision.analyze`
- *Automation tools:* `automation.create(draft)` · `list` · `get` · `update` · `delete` · `simulate`

**Capability Manifest** (a inizio sessione): modello device, versione Android, stato Shizuku, permessi concessi, tool disponibili (da probe reale), whitelist attive → così l'agente "sa dove si trova e cosa può/non può fare".

## 8. Layer Shizuku

Unico punto che tocca Shizuku. Espone `PrivilegedShell.run(cmd): Result` + op tipizzate. Tutto ciò che oggi si fa via `adb` gira identico. **Esecuzione serializzata** su coda single-writer con priorità (§13 #14). Resilienza in §9.

## 9. Sopravvivenza background / anti-Doze

Principio: appoggiarsi ai meccanismi **OS-managed** che scattano anche in Doze, non tenere vivo un loop.

- **Foreground service** (`specialUse`/`dataSync`) con notifica persistente a bassa priorità.
- **Time** → `AlarmManager` `setExactAndAllowWhileIdle` + WorkManager per job tolleranti.
- **Geofence** → Play Services Geofencing (OS-managed, sopravvive a Doze, batteria bassa).
- **Notifiche/WhatsApp** → `NotificationListenerService` (bindato, resiste) + **WhatsApp direct-reply via RemoteInput**.
- **Watchdog** → AlarmManager heartbeat periodico che resuscita il service se ucciso (`START_STICKY`).
- **Boot** → receiver `BOOT_COMPLETED` re-idrata le automazioni armate e ri-registra tutti i trigger.
- **Onboarding OEM** → wizard OxygenOS (battery-optimization exemption, auto-launch, lock in recents).

## 10. Modello di sicurezza

1. **Gate approvazione**: automazione creata dall'LLM nasce `pending_approval`; la UI mostra la regola in chiaro (incluso ogni `run_shell`); l'utente **arma** con un tap. Modifica LLM di una regola armata → torna `pending_approval`.
2. **Catalogo sempre-conferma** (irreversibili): `pm uninstall`, factory reset, invio a contatti **non** whitelistati, pagamenti, cancellazione file → conferma live (push approva/nega) anche se la regola è armata.
3. **Whitelist messaggistica**: `WhatsAppReply`/SMS solo verso contatti whitelistati (per ID/numero), altrimenti conferma per-uso.
4. **Difesa prompt-injection**: contenuti da schermo/notifiche/web taggati **untrusted** e wrappati con delimitatori espliciti ("dati, non istruzioni"); al fire-time il set di tool è **ristretto a `allowed_tools`** dichiarati nella regola — contenuto malevolo non può escalare a `shell.run`/`pm install`. La `compile` (creazione regole) avviene **solo in chat** (umano presente), mai autonomamente da contenuto untrusted.
5. **Budget guard**: tetto globale chiamate LLM/ora; stima costo mostrata all'approvazione per le regole generative.
6. **Audit log**: ogni scatto + azione in Room, consultabile.

## 11. Esempi tracciati

- **Es. 1** — `compile` legge `state.read(gps)` → `Automation{trigger:Geofence(lat,lng,50,exit), actions:[SetWifi(off),SetBluetooth(on)]}` → armata → Play Services registra il geofence → scatto = 2 azioni deterministiche. Zero LLM a regime.
- **Es. 2** — `Automation{trigger:Time(cron "0 23 * * *", tz), conditions:[StateEquals(ringer,!=,silent)], actions:[SetDnd(on)]}` → AlarmManager exact sveglia l'engine, valuta condizione, esegue. Deterministico.
- **Es. 3** — `Automation{trigger:Notification(pkg=whatsapp, sender=<id Moglie>), conditions:[TimeWindow(X)], actions:[InvokeLlm{goal, context:[notification], allowed_tools:[whatsapp_reply], output:reply}]}` → NotificationListener scatta → agent-loop con tool ristretti → Brain compone → invio via RemoteInput. LLM al fire-time, solo per generare, solo quel tool.

## 12. Handoff frontend (Claude Design)

Lo spec definisce **schermi + contratti di stato**; Claude Design produce visual + Compose contro quei contratti; il suo zip torna come componenti da cablare ai ViewModel/engine.

Schermi: (1) **Chat** — streaming, card tool-call, approvazioni inline; (2) **Automazioni · lista** — armate/pending/disabilitate + toggle; (3) **Automazione · dettaglio/approvazione** — regola in chiaro, arma/nega/modifica, warning conflitti/costo; (4) **Log esecuzioni** — timeline audit; (5) **Settings** — Brain (endpoint Hermes / login OAuth), stato Shizuku, whitelist; (6) **Onboarding/permessi** — wizard Shizuku, accessibilità, notification access, battery/OEM, location.

Ogni schermo avrà nel dettaglio del plan il proprio **contratto di stato** (es. `AutomationDetailState{ ruleHuman:String, actions:List<ActionRow>, canArm:Boolean, warnings:List<String> }`) così il design è indipendente dall'implementazione.

## 13. Edge case, barriere invalicabili, conflitti — e soluzioni

### Barriere invalicabili (hard blockers)

| # | Barriera | Soluzione |
|---|----------|-----------|
| B1 | **Shizuku muore al reboot** (non-root): senza re-grant l'app perde i privilegi elevati. Un'app non può ri-concedersi Shizuku da sola. | **Root = percorso supportato** (il OnePlus è rootato): auto-start Shizuku via servizio Magisk/init al boot. Non-root = **degradato**: fallback al self-start Wireless-ADB di Shizuku ove possibile, altrimenti l'engine mette in coda le azioni shell e notifica "riattiva Shizuku"; le azioni via API Android normali continuano. |
| B2 | **FGS non avviabile da background** (Android 12+): un trigger che scatta con app in background non può *avviare* un foreground service. | **Non avviamo mai un FGS da background**: il service è già persistente. I callback (geofence/alarm) e il NotificationListener (già bindato) fanno il lavoro nel service già in esecuzione. |
| B3 | **Exact alarm revocabile** (Android 13+ `SCHEDULE_EXACT_ALARM`). | Richiedi `USE_EXACT_ALARM` (ok per app di automazione sideloaded) o guida l'utente a "Sveglie e promemoria"; fallback inexact+WorkManager per timing non critico. |
| B4 | **OxygenOS uccide aggressivamente** il foreground service. | Battery-optimization exemption + wizard auto-launch/lock-recents + `START_STICKY` + **watchdog AlarmManager** che resuscita il service. Best-effort documentato: rischio residuo accettato. |
| B5 | **Gateway Hermes potrebbe non esporre un endpoint programmatico multimodale** (solo Telegram). | **Da verificare nel plan** (rischio con owner). Se assente: aggiungere un thin endpoint HTTP al gateway (è suo, open source) o guidare via la session API esistente. |
| B6 | **Aux-vision senza provider multimodale raggiungibile** (solo codex-OAuth → niente dove instradare le immagini). | Configurare almeno **una key multimodale** (Gemini free); il capability probe avvisa se vision non disponibile e **disabilita** le regole che dipendono dalla visione schermo. |
| B7 | **Finestre `FLAG_SECURE`** (banking): `screencap` torna nero. | Rilevare e riportare al Brain ("schermo illeggibile: finestra sicura"); provare l'albero accessibilità come fallback; non crashare. |

### Conflitti

| # | Conflitto | Soluzione |
|---|-----------|-----------|
| C1 | **Automazioni contraddittorie** (regola A: wifi off su exit geofence; regola B: wifi on a orario) → thrash. | **Rilevatore conflitti** all'arm-time (analisi statica: spazi-trigger sovrapposti + azioni opposte sullo stesso target → warning in approvazione) + runtime **last-writer-wins** con campo `priority` opzionale + action log. |
| C2 | **Trigger storm** (app che spamma notifiche; geofence flapping al bordo). | **Debounce/cooldown per-regola** (`cooldown_ms`), geofence con `dwell`+`loiteringDelay`+isteresi raggio, rate-limit su `InvokeLlm`. |
| C3 | **Fire concorrenti** serializzati sull'unica shell Shizuku. | Executor a **coda single-writer con priorità**; le azioni generative (lente) girano in lane async senza bloccare quelle deterministiche. |
| C4 | **Runaway costo/batteria** da regola generativa mal configurata (`InvokeLlm` su ogni notifica). | **Budget guard** globale (max chiamate/ora) + stima costo all'approvazione + warning UI "questa regola invoca l'LLM ad ogni X". |

### Edge case

| # | Edge case | Soluzione |
|---|-----------|-----------|
| E1 | **Latenza LLM vs trigger transitorio** (la chiamata finisce prima che la risposta sia pronta). | **Snapshot del contesto al fire-time** passato all'LLM; timeout azione; per l'urgente preferire azioni deterministiche. Latenza generativa documentata. |
| E2 | **Tap per coordinate rotto** da risoluzione/orientamento/tema diversi. | Preferire targeting via **albero accessibilità** (elemento per testo/id + bounds); coord solo ultima spiaggia; re-dump UI prima di agire + check stato atteso. |
| E3 | **RemoteInput WhatsApp assente** (notifica senza action di reply). | Primario = RemoteInput; fallback tier-2 = automazione UI (apri/naviga/scrivi/invia) più lenta/fragile; se nessuno → notifica "impossibile auto-rispondere". Capability rilevata al fire-time. |
| E4 | **Ban/ToS WhatsApp** per invio "come utente". | Preferire RemoteInput (indistinguibile da reply normale) all'UI injection; rate-limit; uso personale; whitelist-gated. Rischio documentato. |
| E5 | **Permesso revocato a runtime** (notification access/accessibilità/location). | Capability probe ad ogni scatto + periodico; regola con capability mancante → **pausa** (non fail silenzioso) + notifica "serve permesso X"; visibile in UI. |
| E6 | **Timezone/DST per cron**. | Cron con **TZ esplicita**; wall-clock via AlarmManager; semantica standard cron per ora saltata/duplicata. |
| E7 | **Ambiguità nome contatto** ("Moglie" → contatto sbagliato). | Whitelist per **ID contatto/numero**, non display name; risolto al compile-time e mostrato in approvazione. |
| E8 | **Migrazione schema automazioni** dopo update app. | `schemaVersion` + Room migrations; su cambio incompatibile → regola `needs_review` invece di rompere. |
| E9 | **Recovery post-reboot** dei trigger. | Receiver `BOOT_COMPLETED` re-idrata le regole armate e ri-registra geofence/alarm (richiede Shizuku up per azioni shell — vedi B1). |
| E10 | **OAuth token DirectLlmBrain scaduto/ruotato**. | Refresh flow; su fallimento fallback a `HermesBrain` se configurato, altrimenti notifica re-auth. DirectLlmBrain è **secondario** (P3); Hermes è il primario. |

## 14. Strategia di test

- **Unit (table-driven):** interprete regole (match trigger, valutazione condizioni AND/OR, dispatch azioni), (de)serializzazione schema + migrazioni, helper sicurezza (restrizione tool al fire-time), rilevatore conflitti.
- **Instrumented (device reale):** op Shizuku (screencap/tap/shell/install), registrar trigger, boot recovery.
- **Contract test Brain:** risposte Hermes/LLM mockate → tool-call attesi; adapter simmetrici.
- **Injection test:** contenuto untrusted non riesce a escalare oltre `allowed_tools`.
- **Golden/eval:** i 3 prompt NL → `AutomationDraft` atteso (Brain mockato).

## 15. Phasing (base per la divisione task agent-driven)

- **P0 — Fondamenta:** `core-shizuku` + `device-tools` + capability probe + `agent-loop` chat interattiva con **un** Brain. Risultato: *"chatti e agisce sul telefono"* (computer_use on-device).
- **P1 — Motore automazioni:** schema + Room store + interprete + approva-alla-creazione + trigger **Time** e **Notification** (WhatsApp RemoteInput) + azioni deterministiche + `InvokeLlm`. Risultato: **i 3 esempi funzionano**.
- **P2 — Hardening background:** foreground service + anti-Doze + watchdog + wizard OEM + trigger **Geofence** e **PhoneState** + resilienza Shizuku + boot recovery.
- **P3 — Sicurezza & secondo Brain:** whitelist + conferma-irreversibili + audit UI + rilevatore conflitti + budget guard + `DirectLlmBrain` (OAuth) + tool app-side `web.search`/`vision.analyze`.

## 16. Rischi aperti da confermare in planning

- **B5** endpoint programmatico Hermes (multimodale) — verificare prima di finalizzare `HermesBrain`.
- **B1** meccanismo esatto di persistenza Shizuku post-reboot sul OnePlus 15 rootato.
- **B6** quale provider multimodale usare per aux-vision quando Brain = Direct (Gemini free candidato).
- Policy `USE_EXACT_ALARM` (sideload-only accettabile).

## 17. Fuori scope (YAGNI)

- Play Store distribution (app sideloaded personale).
- Multi-utente / multi-profilo.
- Editor visuale di regole drag-and-drop (le regole le crea l'LLM; la UI le mostra/approva, non le costruisce a mano).
- Sync cloud delle automazioni (locale + eventuale backup via il tuo homelab in futuro).
