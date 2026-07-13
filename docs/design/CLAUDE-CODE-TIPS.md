# Argus — Direttive & tips per Claude Code

Guida operativa per chi implementa questo design in un vero codebase. Leggi **prima**
`README.md` (schermi, token, contratti). Qui trovi il *come* e i tranelli.

---

## 0. Regola d'oro

Il prototipo HTML è **verità di design, non di codice**. Riproduci layout, gerarchia,
colori, copy e stati; **non** trasportare markup/CSS. Target consigliato:
**Android nativo Kotlin + Jetpack Compose Material 3** (coerente con `plan-P0A`,
Engine Core in Kotlin puro). Se il progetto ha già un design system Compose, usalo.

## 1. Prima di scrivere codice

1. Apri `Argus.dc.html` in un browser e **clicca ogni flusso**: invio chat → bozza →
   "Rivedi e approva" → **Arma**; filtri lista; toggle; log espandibili; wizard onboarding.
   Le parole e i micro-stati contano quanto i pixel.
2. Ispeziona il codebase: c'è già un tema M3? colori/typography/`Shapes` definiti? un
   pattern di navigazione (Nav Compose, Voyager, …)? un layer dati per le regole?
3. **Non partire dalla UI**: parti dal **modello** (`Rule`, `Action`, `RuleStatus`,
   `Warning`, `RunOutcome` — §6 del README). La UI è una funzione dello stato.

## 2. Ordine di implementazione consigliato

1. **Tema**: porta i token di §5 in `ColorScheme` dark M3 + `Typography` (Roboto) + `Shapes`.
   Definisci anche i colori **semantici** (armed/pending/error/needs_review/generative/cloud)
   come estensioni del tema, non hardcoded nei composable.
2. **Componenti condivisi** (compaiono ovunque): `StatusBadge`, `GenerativeTag`,
   `CloudTag`, `RuleRow` (lista), `RuleRenderBlock` (QUANDO/SOLO SE/ALLORA), `WarningCard`,
   `ShellCommandBlock`.
3. **Schermi cardine per primi**: **Dettaglio/Approvazione** → **Chat** → **Lista**.
   Poi Log, Sistema, Onboarding.
4. **Superfici di notifica** per ultime (sono `Notification` di sistema, non composable).

## 3. Il punto che NON puoi sbagliare: sicurezza & approvazione

Argus esegue azioni reali sul telefono. La UI è l'ultima barriera. Rispetta questi
invarianti — sono requisiti, non estetica:

- **Niente auto-arm.** Una bozza è inerte finché l'utente non preme **Arma** nel Dettaglio.
  La chat non arma nulla; offre solo "Rivedi e approva".
- **`ERROR` blocca l'arma.** Se una regola ha un warning `sev=ERROR` (es. regola generativa
  che vuole `shell.run`/`app.install`), `canArm=false`: il pulsante **Arma** è disabilitato
  e mostri "Arma bloccato: <motivo>". Mai aggirare lato UI.
- **Warning sopra la fold.** Nel Dettaglio i warning stanno **prima** del RuleRender, non in
  fondo. L'utente deve vederli senza scrollare.
- **"Esce verso il cloud" sempre visibile** per regole generative e note privacy (badge ambra
  + warning dedicato). È il segnale di esfiltrazione di contesto.
- **Shell = trattamento speciale**: pannello scuro, etichetta "privilegi shell (UID 2000)",
  comando in monospazio, e — se `requiresLiveConfirm` — conferma live a fire-time (notifica §7.2
  con timer 60 s, dove **la scadenza vale come Nega**, mai come Consenti).
- **Whitelist per `conversationId`, non per nome.** In Sistema mostra l'id mascherato e la nota
  che il nome è spoofabile. Non identificare i contatti per display name nel modello.

## 4. Tips Compose specifici

- **Icone**: i nomi glyph nel prototipo = Material Symbols. In Compose mappa su
  `Icons.Rounded.*`/`Icons.Outlined.*` (`material-icons-extended`). Dove manca l'esatto,
  scegli il più vicino e annota il TODO — non inventare SVG.
- **Badge di stato**: un solo composable `StatusBadge(status)` che centralizza colore+icona+testo
  (vedi tabella §5.3). Evita `when(status)` sparsi.
- **RuleRender**: modella QUANDO/SOLO SE/ALLORA come lista di sezioni tipizzate, non markup fisso —
  regole diverse hanno numeri di azioni/condizioni diversi. Lo shell-block e il generative-badge
  sono varianti dell'item azione.
- **Lista**: ordinamento fisso `PENDING → NEEDS_REVIEW → ARMED → DISABLED`; il toggle è
  `clickable` indipendente dalla card (in Compose: gestisci il click del toggle senza propagare
  al click della riga).
- **Chat one-shot**: è **request/response senza streaming**. Lo stato *sending* deve: disabilitare
  input+send, mostrare spinner + cronometro ("trascorsi N s") + Annulla. Nessun typing-effect.
- **Colori a contrasto**: `accent/primary #9ecaff` è pensato per testo scuro sopra
  (`on = #003257`). Non mettere testo bianco sui riempimenti accent.
- **Touch target ≥48dp** (il prototipo usa 44px CSS; in Compose alza a 48dp min).
- **Timestamp/label** in italiano e con timezone Europe/Rome dove mostrata (cron/geofence).

## 5. Copy & i18n

- Tutta la UI è in **italiano** — mantienilo. Estrai le stringhe in risorse (`strings.xml`)
  fin da subito; il copy di sicurezza (warning, consensi, "esce verso il cloud") è
  **prodotto**, revisionalo con cura e non parafrasarlo a caso.
- Usa il copy esatto del prototipo per: stati badge, testi warning, step onboarding,
  messaggi notifica. Sono stati calibrati per chiarezza/sicurezza.

## 6. Dati realistici per lo sviluppo

Il prototipo usa i 3 esempi della spec (geofence Wi-Fi/BT in uscita, DND dopo le 23,
reply WhatsApp a "Moglie") + alcune regole plausibili (backup foto shell, promemoria
farmaci con schema incompatibile, ecc.). Riusali come fixture/preview
`@Preview` per coprire **tutti** gli stati: pending, armed, disabled, needs_review,
generativa, shell, arm-bloccato, e i vari esiti di log (success/deferred/error/
conditions_not_met/suppressed_cooldown).

## 7. Definition of done per schermo

- [ ] Tutti gli stati del README §7 resi (non solo il "happy path").
- [ ] Warning/`ERROR` bloccano l'arma; nessun percorso auto-arma.
- [ ] Badge/colori dai token, non hardcoded.
- [ ] Copy italiano identico al prototipo per stringhe di sicurezza.
- [ ] Touch target ≥48dp; contrasto testo/accent corretto.
- [ ] `@Preview` per gli stati principali.

## 8. Cosa NON fare

- ❌ Non copiare l'HTML/CSS né `support.js` in produzione.
- ❌ Non aggiungere schermi, sezioni o "abbellimenti" non presenti nel prototipo senza chiedere.
- ❌ Non introdurre gradienti decorativi, ombre esagerate o emoji: il tema è M3 dark sobrio.
- ❌ Non trattare le regole generative come quelle deterministiche (privacy + tool vietati).
- ❌ Non identificare i contatti whitelist per nome invece che per `conversationId`.

## 9. Prompt di partenza suggerito per Claude Code

> "Implementa la UI di Argus in Jetpack Compose Material 3 (dark), lingua italiana, seguendo
> `README.md` di questo bundle. Parti dal modello dati (§6), poi il tema (§5) e i componenti
> condivisi (§3 di questo file), infine gli schermi nell'ordine Dettaglio → Chat → Lista → Log
> → Sistema → Onboarding. Rispetta gli invarianti di sicurezza del §3 (niente auto-arm, ERROR
> blocca l'arma, warning sopra la fold, 'esce verso il cloud' sempre visibile). Genera `@Preview`
> per ogni stato. Non copiare l'HTML: ricrealo con i pattern del progetto."
