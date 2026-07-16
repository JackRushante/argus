# Argus — handoff Claude → Codex: P2 CHIUSA, e cosa è cambiato sotto i piedi

- Data: 2026-07-16, Europe/Rome
- Copre: tutto da `0c337e8` (mio handoff precedente) a `5b2efd8`
- Documenti precedenti: `2026-07-15-argus-claude-to-codex-handoff.md`, i tuoi due del 2026-07-15
- **Leggi il §4 prima di toccare il codice**: contiene decisioni di Lorenzo che **ribaltano** cose
  che tu avevi scritto come definitive. Non sono mie iniziative.

---

## 1. Consegna in dieci righe

| Voce | Valore |
|------|--------|
| Branch | **`master`** — `feat/argus-p2-background` è stato mergiato e non serve più |
| HEAD | `5b2efd8 fix(ui): round geofence coordinates and close the physical gate` |
| Merge P2 | `c038604`, `--no-ff`, non distruttivo |
| Working tree / origin | pulito, allineato `0 0` |
| **P2** | **CHIUSA. Definition of Done completa, nessun punto aperto** |
| Gate fisici | **tutti passati con Lorenzo**: SMS/OTP, chiamata, cavo, Bluetooth, **geofence reale** |
| Full gate | **758/758, EXIT=0**, rieseguito dopo ogni cambiamento e **sul merge**, non solo sul branch |
| Audit pre-merge | 12 punti, **nessun finding bloccante** (`c59b3d4`) |
| Bug trovato dal gate fisico | **flapping geofence** — nessun test sintetico poteva mostrarlo (`4339244`) |
| Prossimo | **P3**, riformulata da Lorenzo: due priorità nuove (§6) |

---

## 2. Snapshot verificabile (eseguito oggi)

```
git log --oneline -1        → 5b2efd8
git status --short          → vuoto
git rev-list --left-right --count origin/master...master → 0  0
./gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest \
  --no-build-cache --rerun-tasks --no-parallel  → BUILD SUCCESSFUL, 758/758, EXIT=0

ssh hermes systemctl --user is-active argus-bridge → active
suite remota → 21/21 OK
backup presente: bridge.py.pre-shell-whitelist-20260715
sha256 bridge.py: ecaf4da0…  repo == host ✓

device: versionName=0.1.0  lastUpdateTime=2026-07-15 19:30:13
```

> **Trappola nuova sul tuo §9.** Il confronto `sha256sum` repo/host **può mentire su Windows**:
> oggi `test_bridge.py` risultava diverso, ma il contenuto era **identico** — differivano solo i
> fine riga (CRLF in locale dopo un checkout, LF sull'host). Verifica con
> `diff <(tr -d '\r' < host) <(tr -d '\r' < repo)` prima di concludere che c'è drift.

---

## 3. Cosa ho fatto, in ordine

| Commit | Cosa |
|--------|------|
| `7712afc` | **harness dei gate fisici** (`ArgusPhysicalGateHarness`): arma → azione fisica di Lorenzo → report/cleanup. Tre tempi separati perché una camminata eccede qualunque timeout e perché il processo deve poter morire (è il punto del gate). Non stampa mai `detail` e **asserisce** che non contenga run di cifre |
| `881e876` | evidenza dei gate nel piano + correzione di **tre limiti mal classificati** (§5) |
| `c59b3d4` | **audit pre-merge**, 12 punti |
| `f29e8fa` | **shell innescabile da contatto WhatsApp whitelistato** (§4.2) |
| `4339244` | **fix flapping geofence** (§7) |
| `7024618` | documenti: bug di campo + decisione shell |
| `c038604` | **merge P2 su master** |
| `5b2efd8` | coordinate arrotondate + chiusura DoD col gate fisico |

### 3.1 Gate fisici: esiti (`physical/radio E2E`, etichetta tua §3.2)

| Gate | Esito |
|------|-------|
| Chiamata reale | **PASS** — `FIRED` ×1 + **`SUPPRESSED_DUPLICATE`**: il secondo broadcast anonimo→numerato ha riusato lo stesso event-id ⇒ **il tuo `dea6f79` è confermato sul campo**, non più solo negli unit test |
| Cavo POWER | **PASS** — `FIRED` ×1 per bordo, nessun doppio |
| Bluetooth ACL | **PASS** — `FIRED` ×1 per bordo |
| **Geofence fisico** | **PASS** — 20:03:13 uscita dal lavoro (`set_wifi`+`set_bluetooth`+`show_notification`), 20:08:17 arrivo a casa (`set_wifi`+`show_notification`). **Una volta ciascuna** |
| FGS on-demand | **PASS osservato**: assente → presente dopo l'arm (`types=0x40000000` specialUse) → assente dopo il cleanup |
| Privacy | assertione automatica su **ogni** record audit: nessun run di 4+ cifre in `detail`. Numero, SSID, nome/address BT **mai** arrivati nell'audit |

**Esempio 1 con posizione simulata**: passato prima, via `cmd location providers` (test provider
fused+gps). Etichettato `production-path synthetic guidato dal framework` — **non** physical E2E —
perché il `GeofencingService` valutava sì la posizione e faceva scattare il *nostro* PendingIntent,
ma la posizione la decidevo io. Stato device reale verificato comunque (`wifi_on=0`,
`bluetooth_on=1`).

---

## 4. Decisioni di Lorenzo che NON conosci

**Queste non sono mie proposte.** Sono direttive sue, prese in questa sessione. Alcune ribaltano
cose che tu avevi scritto come definitive: era giusto scriverle così quando le hai scritte, non è
una critica al tuo lavoro.

### 4.1 D0 — il principio fondativo (ora in testa al design master)

> *"L'app non deve avere limiti tecnici ma solo etici... **non esistono NON assoluti, solo NON
> conviene o NON è fattibile**."*

Obiettivo dichiarato: la combinatoria di Tasker (action/if/then/and/or/trigger/condizioni) ma
**creata e armata via LLM**. Il confine etico è **uno solo**: contenuto esterno non fidato non
parametrizza esecuzione né instradamento. **E finisce lì.**

**Regola operativa che ci ha imposto**, e che vale per entrambi: quando scrivi "non si può",
dichiara la classe.

1. **invariante etica** (injection) — l'unica non negoziabile;
2. **non conviene ORA** (sequenziamento);
3. **non è fattibile OGGI** (limite reale, col costo per aggirarlo);
4. **scelta di valore di Lorenzo** — sua, revocabile da lui.

Mai presentare 2/3/4 come 1. Non è teoria: il design conteneva **tre** casi (§5).

### 4.2 La shell è innescabile da un contatto whitelistato — `f29e8fa`

Lorenzo **revoca il proprio divieto**: *"del resto abbiamo la whitelist... i miei contatti
whitelist possono fare tutto"* + *"eccetto spoofing via sms ovviamente"* (l'eccezione l'ha posta
lui, prima che gliela proponessi).

La regola vecchia (solo Time/Geofence/Connectivity) era **più larga della sua linea etica**:
vietava anche di far **partire** un comando statico, non solo di sceglierlo.

| Trigger | Shell | Perché |
|---|---|---|
| Time, Geofence, Connectivity | sì | nessun mittente |
| WhatsApp + `conversationId` whitelistato + `isGroup=false` | **sì (nuovo)** | identità verificata (E15) |
| Notification non-WhatsApp o id non whitelistato | no | qualunque app può postare notifiche |
| PhoneState (SMS **e chiamate**) | no | **mittente SMS e caller ID sono falsificabili** ⇒ nessuna whitelist può renderli un'identità. **Classe 3, non prudenza** |

**L'injection resta impossibile per costruzione**: il `cmd` è letterale nel fingerprint, il
messaggio è un interruttore. Cambia solo *chi* può premerlo.

Implementazione, tutta in TDD: `StaticShellSafety.allows(trigger|event, whitelist)` con i `when`
**esaustivi preservati**; verifica concorde su validator + FirePolicy + executor e **sull'evento
reale**, non solo sul trigger dichiarato; l'executor riceve la whitelist con **default vuoto**
(fail-closed per chi dimentica di cablarla) e la **rilegge a ogni scatto**, così rimuovere un
contatto revoca subito. Nuovo warning `shell_contact_trigger`: il rischio nuovo non è *cosa*
esegue, è che il **momento** lo scelga il contatto. Bridge riallineato (`_shell_trigger_allowed`,
REGOLA 11, schema azioni), suite 21/21, deployato con backup.

### 4.3 B8 declassata da "barriera invalicabile" a trade-off

Il design classificava B8 fra i **blocchi hard**: *"latenza brain gratuito: **inusabile** per un
loop computer_use multi-turn"*. Lorenzo: *"l'llm magari ci mette pure un minuto, ma facendo gli
screenshot e analizzandoli al volo... può fare anche quello"*.

**"Inusabile" era un assunto sulla tolleranza alla latenza, non un fatto.** Per automazione **non
presidiata** 200 s vanno benissimo: nessuno aspetta.

**Conseguenza sulla tua P3-0**: la decisione bloccante "quale provider veloce" **cambia forma**.
Due tier: (a) lento/gratuito via CliBridge, default consigliato in onboarding; (b) veloce/a
pagamento, **opzionale**, solo per uso presidiato. Il tier (b) **non è più un prerequisito**.

Nota tecnica utile: dove l'albero a11y è leggibile (WhatsApp) usare `uiautomator dump`
— deterministico e gratis — e tenere la vision come **fallback** per UI opache.

### 4.4 I sensori sono riaperti

Erano *"una cagata, scartati per sempre"* (2026-07-14). Lorenzo li riapre: *"se si può fare in modo
smart perché non farlo? (che poi deve essere il leit motiv di tutta questa operazione)"*.

La motivazione batteria valeva **solo per l'accelerometro grezzo always-on**:
`TYPE_SIGNIFICANT_MOTION` è hardware e one-shot, il batching con `maxReportLatencyUs` lascia
dormire la CPU, e **la sentinella FGS `specialUse` esiste già**: i sensori possono salirci sopra
senza nuova infrastruttura. Era una classe 4, non una legge.

### 4.5 P3 riformulata: due priorità PRIMARIE

**(1) State keys — non enumerarle, esporre i *lettori*.** Le 7 chiavi attuali (`ringer`, `wifi`,
`bluetooth`, `dnd`, `battery`, `charging`, `airplane`) non basteranno mai: né noi né Lorenzo
possiamo prevedere cosa servirà leggere (il suo esempio: il voltaggio batteria). Proposta
architetturale: famiglie parametriche — `settings.get(ns,key)`, `getprop(name)`, `sysfs(path)`,
`dumpsys(service,campo)`. Il closed-world diventa **"insieme chiuso di lettori, insieme aperto di
valori"**. Il controllo che il validator perde si recupera **all'arm**: leggere davvero la chiave;
se non risponde, non armare. Fallimento onesto in approvazione, non a mezzanotte.

**(2) Sensori** (§4.4).

### 4.6 P4 = variabili + control flow + **taint tracking**

Il divario con Tasker **non è il numero di azioni**. Verificato: Tasker dichiara "oltre 200 azioni
base", il suo indice A-Z ne elenca ~486; Argus ne ha 13 — ma `run_shell` con shell UID 2000 ne
copre una fetta enorme, e `invoke_llm` non ha equivalenti. Il divario è il **linguaggio**: Tasker
ha `If/Else/Goto/For/Perform Task/Return` e `Variable Set/Split/Convert/Array`; noi compiliamo
regole **piatte**.

**Il nodo, e va capito prima di scrivere codice**: le variabili **rompono la fondazione**. Tutta
la sicurezza poggia su *"Lorenzo approva il letterale, ne prendiamo il fingerprint, eseguiamo
quello"*. `RunShell("echo %TESTO_SMS")` non è un letterale: è un template, e il comando vero non
esiste al momento dell'Arma. Si riconcilia in un modo solo: **reticolo di taint a due classi** —
valori non contaminati (orologio, state reader, dati della regola) possono parametrizzare; valori
contaminati (testo SMS/notifica, output di rete) **non raggiungono mai** shell né destinatari. E
il fingerprint deve coprire template **+** politica di taint.

**Vincolo su P3, e costa zero adesso**: non chiudere la porta. Se `ActionResult` non potrà mai
portare un valore, P4 diventa un rewrite. E i payload dei trigger andrebbero marcati come
contaminati **esplicitamente** invece che per convenzione: oggi `CopyToClipboard` già consuma
testo non fidato e la regola vive in un commento.

### 4.7 Altri backlog registrati (con le sue parole)

- **Audit log del lifecycle regole**: oggi l'audit registra scatti e soppressioni ma **non** chi
  arma/cancella e quando. Nasce da un buco reale: l'inventario ha mostrato regole mancanti e **non
  ho potuto dire chi le avesse rimosse**. (Poi chiarito: le aveva cancellate lui.)
- **Azione TTS `Speak`**: non esiste nel catalogo e Android **non espone il TTS via shell**
  (classe 3). Ma il wiring è già tutto lì: sarebbe `CopyToClipboard` con il motore TTS al posto
  della clipboard, stessa fonte, stesso `extractionRegex`. Nota: **volume e altoparlante sono già
  possibili oggi** via `run_shell` da trigger fidato. Leggere ad alta voce un messaggio ostile è
  **innocuo** (è audio, non esecuzione): l'invariante anti-injection non ha motivo di bloccarlo.
- **IDEA, esplicitamente NON pianificare**: rerouting per-azione verso un modello vision leggero,
  approvato all'arm ed entrante nel fingerprint. Lorenzo: *"voglio solo che appunti l'idea, nn che
  la metti in gioco a P3"*. Dettagli e insidia (la detection "serve vision" **non** è affidabile al
  compile time) nel task #38.

---

## 5. Tre limiti che il design classificava male — corretti

Applicando la tassonomia di D0 al documento stesso:

| Prima | Realtà | Ora |
|---|---|---|
| **B8** fra le *barriere invalicabili* | classe 2 (trade-off di latenza) | declassata, due tier |
| **Sensori** "scartati per sempre" | classe 4 (scelta di valore) | riaperti da Lorenzo |
| **DWELL** scritto come principio | classe 3: limite del **backend scelto** (`addProximityAlert`) — il `GeofencingClient` di GMS ha DWELL nativo, e in alternativa si implementa (ENTER → allarme a T → nessun EXIT ⇒ DWELL) | riformulato "non disponibile con questo runtime" |

Nessuna delle tre era sbagliata quando è stata scritta. Erano **abitudini non riesaminate** — ed è
così che l'app perde potenza senza che nessuno lo decida.

---

## 6. Il bug che il gate fisico ha trovato — `4339244`

**Questo è il pezzo che giustifica tutta la fatica dei gate fisici.**

Alle 18:31 e alle 18:34, con Lorenzo **fermo alla scrivania**, la regola "uscito dal lavoro"
(200 m, centrata sulla sua posizione reale) è scattata **due volte**, spegnendogli il Wi-Fi.

**Diagnosi provata, non ipotizzata** (tuo §3.1):
- Due EXIT consecutive sono **impossibili per costruzione** — `beginTransition` ritorna null se
  `lastTransition == transition` ⇒ **in mezzo c'è stato un ENTER**: è flapping.
- L'ENTER era **invisibile nel journal**: `TriggerMatcher` richiede `spec.transition ==
  event.transition`, quindi una regola solo-EXIT lo riceve, **aggiorna lo stato**, e non produce
  audit. Vedevamo metà del ping-pong.
- Escluso il residuo del mio mock (83 min dopo l'arm; centro = posizione reale; fix reale
  `hAcc=12.8` contro `accuracy=5` del mock). Escluso il recupero (mappa EXIT→ENTER, mai
  EXIT→EXIT). **Il dedup funzionava correttamente.**

**Causa**: il design prometteva isteresi contro il rumore (C2/E14) ma la implementava **solo nel
recupero post-crash**. Il percorso normale si fidava del booleano `KEY_PROXIMITY_ENTERING` senza
mai confrontarlo con la posizione. **Completarla è un fix di un intento già dichiarato**, non una
feature.

**Fix**: `LocationBackedTransitionVerifier` chiede una seconda opinione alla posizione prima di
accettare il bordo, con lo **stesso margine di 25 m** del recupero (una sola definizione di
"rumore", non due che divergono). Un bordo smentito **non avanza la sequenza** — altrimenti il
dedup lo tratterebbe come avvenuto e il ping-pong tornerebbe invisibile. **Fail-open deliberato**:
senza posizione leggibile il bordo passa, perché perdere un attraversamento vero è peggio che
accettarne uno spurio; e dentro l'isteresi l'ultima parola resta al framework, che lì è più
accurato di noi.

**Confermato sul campo la sera stessa**: due attraversamenti veri → `FIRED` **una volta ciascuno**,
zero flapping, stesse regole e stesso posto dove poche ore prima ne faceva due in tre minuti.

---

## 7. Errori miei, corretti

Nello spirito del tuo §3.11.

1. **Ho sovrastimato un anello di retroazione.** Avevo raccontato con sicurezza che l'Esempio 1
   "sabota il sensore da cui dipende" spegnendo il Wi-Fi. **Falso, o molto più debole**:
   `cmd wifi status` sullo stesso device diceva già `Wifi scanning is always available` — il
   sistema continua a scansionare per la posizione a Wi-Fi spento. **L'evidenza era nel mio
   output e ho preferito la narrazione elegante.** Resta vera solo la sfumatura: al chiuso il GNSS
   non vede il cielo e il fix pesa su Wi-Fi/celle (rumore massimo); all'aperto e in movimento —
   quando il geofence serve — domina il GNSS.
2. **Il mio harness armava con `cooldownMs = 0`**, ignorando la contromisura che C2 già
   prescriveva contro il geofence flapping. Bug mio, non del motore. Ora arma con 5 minuti — che
   **mitiga la frequenza, non la causa** (il fix vero è il §6).
3. **Il mio `cleanupGates` non annulla le registrazioni OS**: `store.delete()` da solo non lo fa,
   l'unregister vive nel percorso del ViewModel. Le orfane le pulisce il reconcile al successivo
   avvio dell'app (self-healing verificato). Chi userà l'harness lo sappia.

---

## 8. Come sono andato contro le tue regole §3

Onestà, non voto:

- **§3.1 (non diagnosticare prima dei log)**: rispettata. Sul flapping non ho proposto una causa
  finché non ho potuto **dimostrarla dal codice** (due EXIT consecutive impossibili ⇒ ENTER certo).
- **§3.2 (sintetico ≠ reale)**: rispettata. Il mock geofence è etichettato
  `production-path synthetic guidato dal framework`, mai "E2E".
- **§3.11 (non sovrastimare)**: rispettata **sotto pressione**. Lorenzo ha chiesto di *"dare come
  success"* il geofence per non far slittare il merge: ho rifiutato di scriverlo `PASS` e l'ho
  registrato come **rischio residuo accettato esplicitamente** — strada che la tua DoD prevedeva.
  (Poi è passato davvero, ed è diventato PASS con l'evidenza.)
- **Non rispettata**: il §7 sul feedback loop (vedi sopra). Ho fatto esattamente l'errore che il
  tuo §3.1 descrive, solo su un tema minore.

---

## 9. Dove siamo e dove andiamo

**Siamo**: P2 chiusa e su master. L'app fa trigger telefonia, connettività, geofence e OTP, con i
bordi radio provati sul campo. Il device di Lorenzo ha la regola OTP armata e **due geofence
diagnostici ancora armati** — da decidere con lui se toglierli o tenerli per raccogliere la
statistica di latenza che oggi non abbiamo (due bordi non sono un campione).

**Andiamo**, nell'ordine che ha dato Lorenzo:

1. **P3-0 decision record** — la tua proposta, ma allargata: dentro ci va **anche il modello di
   data flow e taint** (§4.6). È ciò che rende P4 una feature invece che un rewrite. E la
   decisione provider cambia forma per B8 (§4.3).
2. **P3-1 state keys via lettori parametrici** (§4.5) — priorità di Lorenzo.
3. **P3-2 sensori** con i meccanismi hardware (§4.4) — priorità di Lorenzo.
4. Poi: tier base senza Shizuku (task #35 — scoperta utile: `LaunchApp`, `OpenUrl`, `SetDnd`,
   `SetRinger` **non hanno bisogno di Shizuku**; restano Shizuku-only `SetWifi`/`SetBluetooth`
   perché Android ha tolto il toggle alle app, più `Tap`/`InputText` (o Accessibility) e
   `RunShell`), audit log del lifecycle, `Speak`.
5. **P4**: variabili + control flow + taint.

**Nota pubblico** (task #35): `RECEIVE_SMS` è ristretto da Play Store agli handler SMS predefiniti
⇒ **l'OTP autocopy non è pubblicabile sullo Store**, solo sideload. E Shizuku da riattivare a ogni
reboot resta *il* blocco UX per un utente non smanettone. Tailscale invece **non è un vincolo**:
serve a noi per ADB e come trasporto verso Hermes, ma il Brain è pluggable per design.

---

## 10. Cosa NON è provato

1. Recupero del bordo perso (geofence) in condizioni reali.
2. Regola chiamata **filtrata per numero**: richiederebbe il numero come argomento, che non deve
   passare da CLI/log. Via pulita: leggerlo on-device dal CallLog e armare da lì.
3. Match per **nome** del device Bluetooth (provata solo la regola generica).
4. **CTA UI reale** per `BLUETOOTH_CONNECT`: concesso via `pm grant` nel gate, poi revocato.
5. Negativo OTP live (`otp_not_found`): coperto solo da Robolectric.
6. **Percorso device live della shell da contatto whitelistato** (§4.2): host verde e bridge 21/21,
   ma sul device non è stato esercitato.
7. **Latenza geofence**: due bordi entro il minuto ≠ statistica. L'aspettativa dichiarata resta E14.
8. Reboot fisico: resta il gate esterno storico.

---

## 11. Regole operative invariate

Tutto il tuo §12 resta valido. Aggiungo:

- `ArgusNavigationInstrumentedTest` **mai** sul device configurato.
- Niente reboot senza Lorenzo.
- Niente segreti via CLI/log/instrumentation.
- **L'output `println` dell'harness si legge solo con `logcat -s System.out`**, non da
  `am instrument`.
- Confronto hash repo/host: attenzione ai CRLF (§2).
- Un comando ADB alla volta; riconnettere `adb connect 100.74.117.9:5555` a ogni ripresa.

---

## 12. Conclusione onesta

P2 è chiusa **con l'evidenza, non con l'ottimismo**: ogni riga della DoD ha la sua classe di prova,
e ciò che non è provato è elencato al §10.

La cosa che vale di più, però, non è P2: è che la direttiva di Lorenzo al §4.1 ha prodotto **tre
limiti caduti in un pomeriggio**, e nessuno dei tre era stato scritto da uno sprovveduto — li
avevamo scritti noi, con buone ragioni, e poi nessuno li aveva più riesaminati. Se rileggendo
questi documenti trovi un mio "non si può" senza la classe accanto, è un bug del documento.
