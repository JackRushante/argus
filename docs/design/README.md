# Handoff: Argus — App Android (UI, 6 schermi + notifiche)

> Pacchetto di handoff per implementare in un vero codebase la UI di **Argus**,
> l'agente LLM di automazione Android descritto in `spec-argus-design-rev3.md`
> e `handoff-frontend.md`. Rev design: **1a** (approvata).

---

## 1. Overview

Argus è un'app **personale** (single-user) Android che compila richieste in linguaggio
naturale in **regole di automazione** eseguite sul dispositivo. Il "cervello" (LLM) è
remoto (server *Hermes* sul tailnet, via CliBridge); il dispositivo esegue solo azioni
**già approvate**. La UI ha un unico compito difficile: **rendere leggibile e sicura
l'approvazione di ciò che l'automazione farà** — specialmente per le regole *generative*
(il cui contenuto esce verso il cloud) e per le azioni *shell*.

Questo bundle contiene i **6 schermi principali** (§6 della spec) + le **superfici di
notifica** (§7), in tema **Material 3 dark**, lingua **italiano**, accento **blu M3**.

## 2. Cosa sono i file di questo bundle

I file qui dentro sono **referenze di design realizzate in HTML** — un prototipo
navigabile che mostra aspetto e comportamento voluti, **non** codice di produzione da
copiare così com'è.

Il compito è **ricreare questi schermi nell'ambiente del codebase target** (Android nativo
Kotlin + Jetpack **Compose Material 3** è la scelta naturale, coerente con `plan-P0A`),
usando i pattern e le librerie già presenti. L'HTML/CSS serve solo a fissare layout,
gerarchia, colori, tipografia, copy e stati. Non portare in produzione il markup.

- `Argus.dc.html` — prototipo interattivo completo. Aprilo in un browser per **cliccarlo**
  (bottom nav, apertura bozza → dettaglio → *Arma*, filtri lista, log espandibili,
  wizard onboarding). La sezione **1a** è quella approvata; **1b–1f** sono superfici di
  notifica e varianti alternative di confronto (vedi §11).
- `screenshots/` — un PNG per schermo, ritagliato sul telefono.
- `support.js` — runtime del prototipo (necessario solo per aprire l'HTML; **irrilevante**
  per l'implementazione).
- `CLAUDE-CODE-TIPS.md` — direttive operative + tips&tricks per chi implementa con Claude Code.

## 3. Fidelity

**Alta fedeltà (hi-fi).** Colori, tipografia, spaziature e stati sono definitivi e vanno
riprodotti fedelmente con i componenti Material 3 del progetto. Le uniche libertà: micro
differenze di spacing dettate dai componenti M3 nativi e le animazioni, descritte a parole
dove non ovvie.

---

## 4. Font utilizzati

| Ruolo | Famiglia | Pesi | Uso |
|---|---|---|---|
| Testo UI / display | **Roboto** | 300, 400, 500, 700 | Tutto il testo dell'interfaccia. 300 solo per l'orologio della lockscreen. |
| Monospaziato | **Roboto Mono** | 400, 500 | Comandi shell, indirizzo Hermes, `conversationId`, budget LLM, label tecniche (variante 1c). |
| Icone | **Material Symbols Rounded** | opsz 24, wght 400, FILL 0 | Tutte le icone. |

In un progetto Compose usa i font di sistema (Roboto è il default Android) e
`Icons.Rounded.*` / `Icons.Outlined.*` di `material-icons-extended`. I nomi glyph usati
nel prototipo corrispondono 1:1 ai nomi Material Symbols (mappa in §9).

Import esatto usato nel prototipo (solo per riferimento web):

```html
<link href="https://fonts.googleapis.com/css2?family=Roboto:wght@300;400;500;700&family=Roboto+Mono:wght@400;500&display=swap" rel="stylesheet">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0">
```

---

## 5. Design tokens

### 5.1 Colori — superfici (dark)

| Token | Hex | Uso |
|---|---|---|
| `bg/canvas` | `#06080b` | sfondo fuori dal telefono (solo prototipo) |
| `surface/base` | `#0e1216` | sfondo schermo |
| `surface/1` | `#161b21` | card, campi, righe lista |
| `surface/2` | `#1a1f27` / `#1c222b` | draft card, bolle assistant |
| `surface/nav` | `#151a20` | bottom navigation |
| `surface/footer` | `#12161b` | footer azioni (dettaglio/onboarding) |
| `surface/code` | `#0c0f13` / `#05070a` | blocchi comando shell |
| `outline` | `#242b33` / `#23292f` | bordi card e separatori |
| `outline/strong` | `#2f3944` / `#3a434d` | bordi input, pulsanti outline |

### 5.2 Colori — testo

| Token | Hex | Uso |
|---|---|---|
| `text/primary` | `#eef1f5` | titoli |
| `text/body` | `#e3e6eb` | corpo |
| `text/secondary` | `#c1c6cf` | secondario |
| `text/muted` | `#9aa2ad` | terziario / descrizioni |
| `text/faint` | `#6f7883` | label uppercase, meta, timestamp |
| `text/disabled` | `#5b6470` / `#4e565f` | disabilitato, footer versione |

### 5.3 Colori — semantici / accento

| Token | Hex (fg / bg) | Uso |
|---|---|---|
| `accent/primary` | fg `#9ecaff` · su superficie scura | CTA primaria, icone attive, link |
| `accent/on` | `#003257` | testo/icone SOPRA `accent/primary` |
| `accent/container` | `#00497d` · testo `#d3e4ff` | bolla utente, avatar, chip attivo |
| `state/armed` | fg `#7fe0a0` · bg `#123a29` | stato ARMED / successo |
| `state/pending` | fg `#ffcf7a` · bg `#4a3300` | PENDING_APPROVAL / warning |
| `state/error` | fg `#ffb4ab` · bg `#2e0f0b` (bordo `#6b271d`) | ERROR / arm bloccato |
| `state/needs_review` | fg `#ffb59d` · bg `#4d1c12` | NEEDS_REVIEW |
| `state/disabled` | fg `#a7adb5` · bg `#2a2d31` | DISABLED |
| `tag/generative` | fg `#d4bbff` · bg `#372a4d` | badge "generativa" |
| `tag/cloud` | fg `#ffb787` · bg `#3d2a17` (bordo `#6b4420`) | privacy / "esce verso il cloud" / shell |
| `code/text` | `#a6e3b8` | testo comando shell |

### 5.4 Spacing, raggi, tipografia

- **Padding schermo**: 16px orizzontale (18px per gli header di titolo).
- **Gap liste**: 10px tra card; 12px tra messaggi chat.
- **Raggi**: card 14px · card grande/draft 16px · bolle chat 18px (con angolo 4px lato mittente) · chip/badge 20px · pill nav 16px · avatar/toggle-knob 50%.
- **Cornice telefono** (solo prototipo): bezel esterno radius 46px, schermo interno 35px, area 368×786.
- **Scala tipografica** (px): titolo schermo 22/400 · titolo dettaglio 20/400 · voce lista 14.5/500 · corpo 14/400 · corpo chat 14 · secondario 12.5–13 · label uppercase 11/600 letter-spacing .12em · badge 10.5/500 · timestamp/meta 11.
- **Touch target**: pulsanti icona ≥44×44; CTA footer 12–13px padding verticale (≥44px totale).
- **Toggle**: 42×24, knob 18px, on `#9ecaff`/knob `#003257`, off `#3a434d`/knob `#c1c6cf`.

---

## 6. Modello dati (contratti §6)

Ogni automazione ("Rule") ha almeno:

- `id`, `name` (leggibile), `status` ∈ `PENDING_APPROVAL | ARMED | DISABLED | NEEDS_REVIEW`
- `enabled` (bool; solo ARMED/DISABLED lo espongono come toggle)
- `isGenerative` (bool) — se true, il contenuto dell'azione è prodotto dall'LLM al fire-time ed **esce verso il cloud**
- `trigger` `{ icon, line }` — il "QUANDO"
- `conditions[]` — il "SOLO SE"
- `actions[]` `{ icon, label, detail?, isGenerative, isShell, shellCommand?, requiresLiveConfirm }` — l'"ALLORA"
- `warnings[]` `{ sev: ERROR|WARNING|PRIVACY, code, text }`
- `canArm` (bool) + `armBlockedReason` — un `ERROR` blocca l'armamento
- `privacyNote?`, `rationale?` (descrizione del modello), `estimated?` (stima costi regola generativa)
- `recentRuns[]` `{ time, kind, outcome, summary }`

**Invariante di sicurezza chiave (mostrata in UI):** una regola *generativa* **non può**
usare `shell.run` né `app.install` → si traduce in un warning `ERROR` con `canArm=false`
(vedi schermo Dettaglio, esempio "Disinstalla giochi dopo mezzanotte").

Enum esito run: `SUCCESS`, `FAILED/ERROR`, `DEFERRED` (E13: risposta pronta ma non più
consegnabile automaticamente), `CONDITIONS_NOT_MET`, `SUPPRESSED_COOLDOWN`.

---

## 7. Schermi

Riferimenti visivi in `screenshots/`. Ordine bottom nav: **Chat · Automazioni · Log · Sistema**
(Dettaglio e Onboarding sono schermi push, senza bottom nav).

### 7.1 Chat — `01-chat.png` (§6.1)
- **Scopo**: l'utente chiede una regola in linguaggio naturale; Argus risponde con una **DraftCard**.
- **Layout**: header (titolo "Argus" + sottotitolo "chat · compilatore di regole" + overflow) → lista messaggi scrollabile → (opz. indicatore attesa) → input in basso.
- **Messaggi**: utente = bolla `accent/container` allineata a destra (radius 18 con angolo 4 in basso-dx); assistant = bolla `surface/2` a sinistra; notice = banner centrato (info blu / error rosso).
- **DraftCard**: icona trigger + nome regola + badge "generativa"; riga trigger; chip azioni; se `canArm=false` fascia rossa "Non armabile: …"; footer con stato ("Bozza · in attesa di approvazione" ambra) + CTA **"Rivedi e approva →"**.
- **Attesa one-shot** (LLM gratuito, no streaming): riga con spinner + "Argus sta pensando…" + "di solito 10–30 s — trascorsi N s" + **Annulla**. Durante l'attesa l'input è disabilitato con placeholder "In attesa della risposta…".
- **Stati**: empty (chip suggerimenti "Prova a chiedere"), brain-down (banner rosso "Hermes irraggiungibile" + Riprova, input send disabilitato), error (notice rosso).

### 7.2 Automazioni · lista — `02-lista.png` (§6.2)
- **Scopo**: gestire tutte le regole.
- **Layout**: titolo → (opz. banner permessi) → riga **chip filtro** orizzontale scroll (Tutte / In approvazione / Armate / Disattivate / Da rivedere) → lista card.
- **Ordinamento**: `PENDING_APPROVAL` → `NEEDS_REVIEW` → `ARMED` → `DISABLED`. Le pending hanno sfondo ambra tenue (`#1c1708`, bordo `#5c4a10`), le needs-review rosso tenue (`#22110c`).
- **Card riga**: icona trigger + nome (+ pallino ambra se ci sono warning) + sommario trigger; toggle a destra **solo** per ARMED/DISABLED; sotto, riga badge stato + badge "generativa" + timestamp ultima esecuzione a destra.
- **Interazione**: tap sulla card → Dettaglio; tap sul toggle → arma/disarma senza aprire (stopPropagation).

### 7.3 Dettaglio / Approvazione — `03-dettaglio.png` (§6.3) ⭐ schermo cardine
- **Scopo**: leggere per intero cosa farà la regola e **approvarla** (o rifiutarla / modificarla / eseguirla / eliminarla).
- **Layout**: back + tipo ("Approvazione" se pending, altrimenti "Dettaglio") → nome + riga badge (stato · generativa · "esce verso il cloud") → **warnings sopra la fold** → blocchi **QUANDO / SOLO SE / ALLORA** → (stima generativa) → **rationale** del modello (citazione, bordo sinistro) → ultime esecuzioni → **footer azioni per stato**.
- **RuleRender**: ogni blocco è una card `surface/1` con label uppercase. Le azioni **shell** aprono un pannello scuro con warning "privilegi shell (UID 2000)" e il comando in monospazio `code/text` scrollabile orizzontalmente. Le azioni generative mostrano il badge viola.
- **Warnings**: `ERROR` = rosso, titolo "Errore di validazione — arm bloccato"; `PRIVACY`/read+reply = ambra, "Legge dati e può inviarli fuori"; `WARNING` = ambra. Un `ERROR` **disabilita** il pulsante Arma e mostra "Arma bloccato: <motivo>".
- **Footer per stato**:
  - *PENDING*: [Rifiuta] [Modifica] [**Arma** ▮ pieno accent] (Arma disabilitato se `!canArm`).
  - *ARMED/DISABLED*: [🗑 elimina] [Modifica in chat] [**Esegui ora**].
  - *NEEDS_REVIEW*: [**Ricrea in chat**] a piena larghezza.

### 7.4 Log esecuzioni — `04-log.png` (§6.4)
- **Scopo**: cronologia degli scatti, raggruppata per giorno (Oggi / Ieri…).
- **Riga**: icona esito (verde=success, ambra=deferred, rosso=error, grigio=condizioni/cooldown) + nome regola + timestamp a destra + sommario colorato per esito. Le righe soppresse/condizioni-non-soddisfatte sono **attenuate** (opacity .5).
- **Espansione**: le righe con dettaglio si aprono (chevron ruota 180°) mostrando i passi (`SetWifi(off) → ok`, …). Le righe **DEFERRED** mostrano un bottone **"Invia ora"** (E13).

### 7.5 Sistema — `05-sistema.png` (§6.5)
- **Sezioni** (card raggruppate): **Salute** permessi (Shizuku, ottimizzazione batteria, accesso notifiche, posizione background — l'ultima con warning ambra + "Correggi"); **Brain · transport** (CliBridge/Hermes, indirizzo mono `http://100.80.142.65:8090`, stato "raggiungibile", latenza, "Test connessione"); **Whitelist contatti** (nota "memorizzati per conversationId, non per nome — spoofabile", riga contatto con avatar + id mascherato, "Aggiungi contatto"); **Budget LLM** (barra "3 / 20 chiamate quest'ora"); riga "Ripeti configurazione"; footer versione.

### 7.6 Onboarding / permessi — `06-onboarding.png` (§6.6)
- **Wizard 6 step**: Privacy → Hermes → Shizuku → Notifiche → Batteria → Posizione.
- **Layout**: back + "Configurazione · N di 6" → barra segmentata di progresso → icona → titolo → corpo → (box conseguenza ambra per la batteria) → (chip "Opzionale" per la posizione) → checklist step (fatto ✓ / in corso ◉ / da fare ○) → footer [Salta?] [CTA].
- **Consenso esplicito**: lo step Privacy chiarisce che il testo delle notifiche e le richieste in chat viaggiano verso Hermes e i provider cloud; CTA "Ho capito, acconsento".

### 7.7 Superfici di notifica — `07-notifiche.png` (§7)
Quattro mock su lockscreen: **(1)** FGS persistente degradato ("Shizuku non attivo — azioni shell in pausa"); **(2)** conferma live per azione sensibile con comando mono, timer 60 s, [Nega]/[Consenti] (scadenza = Nega); **(3)** consegna differita E13 ("Risposta pronta per Moglie — invia ora"); **(4)** regola in pausa E5 ("manca l'accesso alle notifiche").

---

## 8. Interazioni & comportamento

- **Navigazione**: bottom nav (Chat/Automazioni/Log/Sistema). Dettaglio e Onboarding sono push con back. "Rivedi e approva" (chat) e tap card (lista) → Dettaglio ricordando la provenienza (back torna al chiamante).
- **Arma** (da Dettaglio pending): la regola passa a ARMED, la DraftCard in chat diventa "Approvata e armata" (verde), si torna alla lista, toast "Regola armata".
- **Rifiuta**: elimina la bozza, torna in chat, toast.
- **Toggle lista**: commuta ARMED⇄DISABLED in-place.
- **Invio chat**: append messaggio utente → stato *sending* (spinner + cronometro, input disabilitato) → dopo la risposta appende bozza + messaggio assistant. **Annulla** interrompe con notice.
- **Log**: tap riga espandibile → apre/chiude dettaglio; "Invia ora" su riga DEFERRED → toast.
- **Badge nav**: Automazioni mostra il conteggio pending; Sistema un "!" se c'è una regola NEEDS_REVIEW; Chat un puntino.
- **Toast**: bottom-center, auto-dismiss ~2.6 s, icona check.
- **Animazioni**: spinner rotazione continua (~0.9–1.3 s lineare); toast fade+translate 12px (0.25 s); toggle transizione 0.15 s; chevron log rotate 180°. Nessuna animazione decorativa oltre queste.

## 9. Mappa icone (Material Symbols → uso)

`visibility` (logo/brand) · `chat_bubble` (nav Chat) · `bolt` (nav Automazioni) · `history` (nav Log) · `tune` (nav Sistema) · `notifications` (trigger notifica) · `schedule` (trigger orario/cron) · `my_location` (geofence) · `battery_alert` (trigger batteria) · `sync_problem` (schema incompatibile) · `smart_toy` (generativa) · `terminal` (shell) · `wifi_off`/`bluetooth`/`do_not_disturb_on`/`notifications_active` (azioni) · `shield` (Arma/armed) · `pending` (in approvazione) · `error` (errore) · `pause_circle` (disattivata) · `filter_alt` (condizione) · `cloud_upload`/`privacy_tip` (privacy/cloud) · `insights` (stima) · `check_circle`/`error`/`schedule_send`/`filter_alt_off`/`block` (esiti log) · `place` (geofence preview) · `hub` (transport) · `restart_alt`/`arrow_back`/`chevron_right`/`expand_more`/`arrow_upward`/`more_vert`/`add`/`close`/`play_arrow`/`delete`/`send`.

## 10. State management (riepilogo)

Stato minimo per replicare il prototipo: `screen` + `detailId` + `backTo` (routing); `automations[]` (vedi §6); `filter` (chip lista); `expanded{}` (righe log aperte); `toast`; `obIndex` (step onboarding); e per la chat `{ items[], input, sending, elapsed, brainDown }`. In Compose: un `ViewModel` per area con `StateFlow`; le mutazioni chiave (arm/reject/toggle/send) sono elencate in §8.

## 11. Varianti nel prototipo (contesto, non da implementare)

La sezione **1a** dell'HTML è l'approvata. Le altre sono alternative discusse:
`1b` superfici notifica · `1c` tema denso/monospace verde-acqua · `1d` chat (attesa full-screen + draft ricca) · `1e` dettaglio a timeline · `1f` esempio in tema **light** (utile come riferimento per il color scheme chiaro Material 3).

## 12. File in questo bundle

- `Argus.dc.html` — prototipo (apri in browser; sezione 1a = approvata).
- `screenshots/01…07` — un'immagine per schermo/superficie.
- `support.js` — runtime del prototipo (non per la produzione).
- `CLAUDE-CODE-TIPS.md` — direttive per l'implementazione con Claude Code.
