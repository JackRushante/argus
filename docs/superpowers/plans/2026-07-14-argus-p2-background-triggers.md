# Argus P2 — Trigger in background, PhoneState/SMS, OTP autocopy, Geofence

Data: 2026-07-14 (sera, subito dopo il merge P0-B+P1 su master `8f283ca`).
Mandato: Lorenzo, live ("progedi col prossimo chunk di features, p2"), con tre direttive
esplicite raccolte sul campo:

1. **Shell ultra-powerful**: nessuna denylist sui comandi `run_shell`. Il design attuale è già
   corretto (comando STATICO nel draft, mostrato integrale in review, MAX 8.192 char) e NON va
   ristretto. Resta vietato SOLO parametrizzare comandi dal contenuto di messaggi/notifiche
   (iniezione). L'agente può e deve usare la shell per coprire automazioni non ancora tipizzate.
2. **OTP autocopy** (idea di Lorenzo): SMS in arrivo → estrazione codice 4-8 cifre → clipboard.
   Feature di punta di questa fase.
3. **Trigger sensori/accelerometro: scartato** ("una cagata" — confermato fuori scope, anche P3).

Riferimenti: design master §15 (P2 = hardening background + Geofence → Es. 1 + PhoneState/
Connectivity + resilienza Shizuku), handoff §23 (backlog dal feedback), piano P1 per il metodo.
La diagnosi iniziale della "race Shizuku" è stata poi smentita dal codice e dal test live:
vedi D4 corretto sotto.

## Metodo (invariato da P1)

TDD obbligatorio (RED→GREEN, mutation check sui punti safety), un commit atomico per slice,
gate host completo per slice (`:engine-core:test :ui:testDebugUnitTest :data:testDebugUnitTest
:automation-android:testDebugUnitTest :app:testDebugUnitTest :automation-android:lintDebug
:app:assembleDebug`), push su hub a gate verde, `git add` esplicito, niente token/contenuti
in log. Instrumented via `am instrument` diretto (workaround UTP/Windows, handoff §12).
`ArgusNavigationInstrumentedTest` MAI sul device configurato (resetta onboarding).

## Decisioni di design

### D1 — Ogni trigger usa il meccanismo OS più leggero che funziona davvero

| Trigger | Meccanismo | Processo |
| --- | --- | --- |
| PhoneState SMS_RECEIVED | receiver manifest `SMS_RECEIVED_ACTION` + runtime permission `RECEIVE_SMS` | no service |
| PhoneState INCOMING_CALL / CALL_ENDED | receiver manifest `PHONE_STATE` + `READ_PHONE_STATE` + `READ_CALL_LOG` (serve per il numero) | no service |
| Connectivity POWER | receiver dinamico `ACTION_POWER_CONNECTED/DISCONNECTED` dentro la sentinella condivisa | FGS solo se armato |
| Connectivity BT | receiver manifest `BluetoothDevice.ACTION_ACL_CONNECTED/DISCONNECTED` (esenti) | no service |
| Connectivity WIFI | `NetworkCallback` → richiede processo vivo → **FGS sentinella** | FGS solo se armato |
| Geofence | `LocationManager.addProximityAlert` + PendingIntent esplicito/mutable, senza dipendenza GMS | nessun service |

**Correzione verificata il 2026-07-15:** POWER non è tra le eccezioni agli implicit broadcast
manifest su Android moderno; il vecchio assunto era falso. Il receiver è quindi dinamico e vive
nella stessa sentinella del Wi-Fi. BT ACL resta invece manifest-safe. Riferimenti ufficiali:
[implicit broadcast exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
e [broadcast overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts).

**FGS sentinella**: parte SOLO quando esiste almeno una regola ARMED+enabled il cui trigger lo
richiede (Wi-Fi o POWER); si spegne al reconcile quando non serve più. Tipo FGS
`specialUse` (API 36 li richiede), notifica persistente minima e onesta. Nessun service quando
nessuna regola lo richiede (invariante P0/P1 preservata).

### D2 — OTP via receiver SMS, NON via listener notifiche

Il testo completo dell'SMS è garantito solo dal receiver (le notifiche troncano/raggruppano), e
NON estendiamo l'ingress notifiche oltre WhatsApp (superficie privacy invariata: observed,
picker e reply gateway restano WhatsApp-only). `TriggerEvent.PhoneStateChanged` si estende con
`smsText: String?` **volatile**: vive solo in RAM per la durata del dispatch, MAI persistito,
MAI loggato, MAI nell'eventId in chiaro (solo digest). L'audit registra l'esecuzione, non il
contenuto.

### D3 — Nuova azione tipizzata `Action.CopyToClipboard`

`CopyToClipboard(extractionRegex: String? = null)`, tier DETERMINISTIC:

- senza regex: copia il payload testuale del trigger (SMS o notifica) così com'è;
- con regex (visibile integrale in review): copia il primo capture group (o il match intero se
  senza gruppi); NO match ⇒ azione FAILED onesta (`otp_not_found`), nessuna copia;
- la scrittura clipboard usa `ClipDescription.EXTRA_IS_SENSITIVE` (stesso pattern E13: niente
  anteprima del codice nella UI di sistema);
- validator: regex compilabile e bounded (≤ 512 char), errore `clipboard_source_missing` se il
  trigger non ha payload testuale (es. time);
- fail-closed dallo spike P2-0: se Android 16 blocca `setPrimaryClip` in background, fallback
  dichiarato = notifica high-priority "OTP: tocca per copiare" (trampoline → copy in foreground
  → finish), e il piano si aggiorna QUI prima di implementare.

Il prompt del bridge impara il tool `copy_to_clipboard` (manifest available_tools + regola
d'uso: regex deterministica nel draft, mai estrazione via LLM).

### D4 — Shell statica approvata; nessuna falsa "race Shizuku"

La mancata esecuzione osservata alle 18:29 era strutturale: `run_shell` non era pubblicato dal
probe, la FirePolicy lo bloccava e l'executor restituiva `live_confirmation_required`. Non esiste
e non va implementato alcun retry binder per correggere quell'episodio.

`run_shell` diventa eseguibile solo quando Shizuku è `AUTHORIZED`, con tre difese concordi:
comando letterale nel fingerprint e mostrato integralmente in review; trigger ammessi soltanto
Time/Geofence/Connectivity; blocco ripetuto da validator, FirePolicy ed executor per ogni evento
Notification/PhoneState. Il tool generativo raw `shell.run` resta vietato.

### D5 — Cosa NON cambia

- Reply/observed/picker: solo WhatsApp. Nessun reply su SMS (né su altri package).
- Nessun comando shell parametrizzato da contenuti esterni (il validator già non lo permette:
  `cmd` statico). Nessuna denylist aggiunta.
- ~~Trigger sensori: fuori scope definitivo.~~ **RIAPERTI da Lorenzo il 2026-07-15** → P3
  (fuori scope di P2, che è cosa diversa da "definitivo"). La motivazione batteria valeva solo per
  l'accelerometro grezzo always-on: `TYPE_SIGNIFICANT_MOTION` è hardware e one-shot, il batching
  con `maxReportLatencyUs` lascia dormire la CPU, e la sentinella FGS `specialUse` esiste già.
- DWELL geofence: P2 consegna ENTER/EXIT e il validator dichiara DWELL non supportato (niente
  finti dwell). **Precisazione sulla classe**: è un limite del **backend scelto**
  (`addProximityAlert`), non un principio — il `GeofencingClient` di GMS ha DWELL nativo con
  `loiteringDelay`, e in alternativa si implementa (ENTER → allarme a T → nessun EXIT ⇒ DWELL).
  Va scritto come "non disponibile con questo runtime", mai come "non si può".

## Fasi

### P2-0 — Spike su device (esiti)

1. ~~`setPrimaryClip` da background su Android 16 reale~~ → **RISOLTO (2026-07-14 sera)**:
   scrittura riuscita da processo instrumentation SENZA focus (OK 1 test, nessuna
   SecurityException) e **verificata da Lorenzo con un incolla reale** ("SPIKE-123456").
   D3 procede col design pulito: copia diretta in background, il fallback CTA resta solo
   come degradazione se l'E2E P2-3 con l'app vera in cached smentisse (improbabile).
   Spike pulito (clearPrimaryClip + uninstall test package); il file di spike si rimuove
   col primo commit P2-3.
2. ~~`addProximityAlert` su OnePlus 15~~ → **SCELTO E PROVATO (2026-07-15)**: registrazione
   framework reale, pipeline Room→ingress→Engine→Shizuku e cleanup verificati su device (`OK (1)`,
   nessun id residuo). Il test usa un ingresso sintetico dopo la registrazione: callback ENTER/EXIT
   da spostamento reale, latenza cached/Doze e comportamento OEM restano gate osservazionali P2-4.
3. Receiver manifest `PHONE_STATE`/`SMS_RECEIVED` con app cached → si valida direttamente
   nell'E2E di P2-2 con SMS reale (niente spike separato: il receiver è il deliverable).

**Ordine di esecuzione aggiornato (valore prima del warm-up)**: P2-2 (PhoneState/SMS) →
P2-3 (OTP, la feature di Lorenzo) → P2-1 (Connectivity) → P2-4 (Geofence) → P2-5 (chiusura).
Il pattern receiver→dispatcher→engine è già consolidato dal listener P1: il warm-up
connectivity non è necessario.

### P2-1 — Connectivity (BT/Power subito, Wi-Fi con FGS)

**Stato 2026-07-15 sera: CHIUSA — entrambi i bordi fisici passati con Lorenzo.**

`ArgusPhysicalGateHarness#armPowerGate` e `#armBluetoothGate`, azioni fisiche reali:
`cavo collegato` / `cavo scollegato` / `bt connesso` / `bt disconnesso` → **`FIRED` ×1 ciascuna**,
`show_notification -> SUCCEEDED`, **nessun doppio scatto**, `detail` senza PII (assertione
automatica: né SSID né nome/address Bluetooth nell'audit).

**FGS on-demand verificato sul device**, non solo nel codice: prima dell'arm era attivo solo il
`NotificationListenerService`; dopo l'arm delle regole POWER è comparso
`ConnectivitySentinelService isForeground=true types=0x40000000` (`specialUse`); dopo
`cleanupGates` la sentinella è sparita e il listener è rimasto bindato. L'invariante "nessun
service quando nessuna regola lo richiede" regge sul campo.

**Nota onesta su BLUETOOTH_CONNECT**: era `granted=false` (opt-in corretto by design) ed è stato
concesso via `pm grant` per il gate, poi revocato. La **CTA UI reale non è stata esercitata** in
questo giro. Non provato: match per nome del device BT (solo regola generica).

- engine-core: capability granulari `connectivity.wifi`, `.wifi.identity`, `.bt`, `.power`;
  parser eventId digest-only (mai SSID/address in chiaro) e requirement per medium/match.
- automation-android: receiver manifest solo BT; POWER dinamico + `NetworkCallback` Wi-Fi dentro
  un unico FGS `specialUse`; snapshot transizioni persistito con chiavi hashate, seed senza falso
  fire, recupero del bordo perso a processo morto e dedup dei callback ripetuti.
- coordinator single-writer: FGS richiesto solo da regole ARMED+enabled Wi-Fi/POWER; registrar,
  enable rollback, disable cleanup e bootstrap riconciliano il servizio in modo fail-closed.
- probe/UI: permission `BLUETOOTH_CONNECT` opt-in; SSID richiede location foreground+background;
  riga "Sentinella connettività" visibile solo quando il servizio è realmente attivo.
- Hermes: prompt e validator server-side applicano `available_triggers` granulari; un match SSID
  richiede anche `connectivity.wifi.identity`. Deploy con backup e suite 19/19 verde.
- gate OnePlus: regola POWER temporanea → FGS reale → ingress/Engine/Room → `/system/bin/true`
  via Shizuku → journal success → cleanup e stop FGS, `OK (1)`. Restano da provare sul campo:
  ACL Bluetooth reale e collegamento/scollegamento cavo reale. Non spegnere il Wi-Fi via ADB,
  perché è il trasporto ADB/Tailscale del laboratorio.

### P2-2 — PhoneState (chiamate + SMS)

**Stato 2026-07-15 sera: CHIUSA — physical/radio E2E passato anche sulle chiamate.**

SMS telephony reale: PASS manuale Lorenzo. **Chiamata reale: PASS osservato nel journal**
(harness `ArgusPhysicalGateHarness#armCallGate`; Lorenzo ha chiamato dal secondo numero):

- `Argus GATE chiamata in arrivo` → **`FIRED` ×1 + `SUPPRESSED_DUPLICATE`**: il secondo broadcast
  (anonimo→numerato) ha riusato lo stesso event-id. Il fix `dea6f79` è quindi confermato SUL CAMPO,
  non solo negli unit test.
- `Argus GATE chiamata terminata` → `FIRED` ×1 (RINGING→IDLE).
- Entrambe `show_notification -> SUCCEEDED`; notifiche viste da Lorenzo.
- Privacy: assertione automatica su ogni record audit (nessun run di 4+ cifre in `detail`) →
  **il numero non è mai arrivato nell'audit**.

**Non provato**: regola filtrata per numero. Richiederebbe il numero come argomento, che non deve
passare da CLI/log; la via pulita per il futuro è leggerlo on-device dal CallLog e armare da lì.

- Permissions: `RECEIVE_SMS` + `READ_PHONE_STATE` runtime, richieste SOLO quando una regola le
  richiede (arm gate → CTA come battery P1-6); righe salute in Sistema; onboarding INVARIATO
  (non tutti vogliono SMS).
- Receiver manifest: PHONE_STATE (RINGING→INCOMING_CALL, IDLE dopo OFFHOOK/RINGING→CALL_ENDED,
  number quando disponibile e permesso) e SMS_RECEIVED (number + smsText volatile D2, multipart
  ricomposti).
- Confine canale esplicito: `SMS_RECEIVED` copre soltanto SMS telephony. Le chat RCS di Google
  Messaggi e gli MMS non passano da questo receiver e non possono attivare OTP/textMatch; la UI
  lo dichiara senza chiamarli genericamente "messaggi".
- Matcher: `numbersMatch` esiste già (suffisso ≥7 cifre). EventId SMS: digest include numero+testo
  hashati, MAI in chiaro. EventId chiamata: identità della transizione, indipendente dal numero,
  così i doppi broadcast anonimo/numerato restano idempotenti ma le regole filtrate possono fare
  match quando il numero arriva in ritardo.
- Registrar/probe/validator/bridge aggiornati. Test: unit + Robolectric + production-path device;
  conferma manuale Lorenzo su SMS telephony reale ricevuto dal modem.

### P2-3 — OTP autocopy (la feature di Lorenzo)

**Stato 2026-07-15: completo. Lorenzo ha verificato live SMS telephony → textMatch/regex →
clipboard e incolla reale in un'altra app; entrambe le automazioni hanno funzionato perfettamente.**

- engine-core: `Action.CopyToClipboard` (D3) + validator + `CapabilityRequirements` + render
  review ("Copia negli appunti · estrazione: `regex`" — regex integrale, §5 non si soffia).
- automation-android: executor clipboard sensibile (o fallback CTA da spike); wiring FireContext
  → payload testuale del trigger (SMS/notifica).
- bridge: tool `copy_to_clipboard` + REGOLA: estrazione SOLO via regex RE2 lineare nel draft;
  suggerire il pattern OTP di default `(?:^|[^+0-9])([0-9]{4,8})(?:[^0-9]|$)` con esclusione
  prefissi telefonici (il vecchio pattern lookbehind resta migrato in modo compatibile; da
  raffinare in TDD con corpus di SMS reali di esempio, inclusi falsi positivi: importi, anni,
  numeri parziali).
- ~~E2E positivo live con Lorenzo: SMS OTP vero → clipboard entro pochi secondi + incolla reale.~~
  **PASS manuale 2026-07-15.** Resta osservazionale il negativo live: SMS senza codice → FAILED
  `otp_not_found`, nessuna copia; il comportamento è già coperto dai test Robolectric.

### P2-4 — Geofence (Es. 1 end-to-end)

**Stato 2026-07-15 sera: Esempio 1 PASSATO con posizione simulata; l'attraversamento FISICO è
armato e in attesa.** La distinzione sotto è deliberata: vedi handoff Codex §3.2, "un test
sintetico non è il bordo reale".

**(a) PASS — `production-path synthetic` GUIDATO DAL FRAMEWORK** (etichetta corretta: NON è
physical/radio E2E). Metodo: test provider `fused`+`gps` via `cmd location providers`, posizione
fissata dentro il geofence e poi spostata a ~500 m. **Il `GeofencingService` del framework ha
valutato la posizione e fatto scattare il NOSTRO PendingIntent** — non abbiamo iniettato l'ingress,
che è ciò che rende questa prova più forte del gate precedente. Journal:
- `uscita geofence` → `FIRED` + `show_notification -> SUCCEEDED`;
- `esempio 1` → `FIRED` + **`set_wifi -> SUCCEEDED` + `set_bluetooth -> SUCCEEDED`**.

**Stato REALE del device confermato dopo lo scatto** (il journal da solo non basta):
`settings get global wifi_on` → `0` ("Wifi is disabled"), `bluetooth_on` → `1`. L'Esempio 1
multi-azione ha davvero agito sul telefono. Nota utile: **ADB è sopravvissuto allo spegnimento del
Wi-Fi** perché Tailscale gira su LTE — il timore "spegnere il Wi-Fi ci fa perdere il device" è
infondato su questo setup.

Cleanup verificato: test provider rimossi, `mock_location` riportato a `default`, Wi-Fi riacceso,
nessuna registrazione geofence residua nel framework.

**(b) APERTO — attraversamento fisico reale.** Armate con `armWorkExitScenario` (EXIT 200 m sulla
posizione del LAVORO → Wi-Fi off + BT on + notifica) e `armHomeArrivalScenario` (ENTER 200 m su
Via Avola 28, geocodificata ON-DEVICE → Wi-Fi on + notifica). Attenzione al modello mentale:
`resolveCurrentLocation` congela all'arm, quindi l'uscita scatta **lasciando il lavoro**, non
arrivando a casa.

**Restano non provati** e sono esattamente ciò che il mock NON può dare: latenza GPS reale,
comportamento in Doze/OxygenOS, recupero del bordo perso.

- Backend framework `LocationManager.addProximityAlert`: un PendingIntent esplicito, interno e
  mutable per regola; `ACCESS_FINE_LOCATION` precisa + `ACCESS_BACKGROUND_LOCATION` obbligatorie.
  Nessuna dipendenza Google Play Services e nessun service residente.
- Registrar per-rule con coordinate/raggio congelati nel draft approvato; unregister indipendente
  al disable/delete/revoca; ri-registrazione a process start, BOOT e package replacement.
- Registry sincrono persistente: identità preparata prima della chiamata OS, sequenza e transizione
  pending salvate prima del dispatch, retry con lo stesso event-id dopo crash e dedup nel journal.
- Recupero del bordo perso durante process death tramite posizione one-shot soltanto quando esiste
  uno stato precedente; isteresi di 25 m al confine per non inventare ENTER/EXIT da rumore GPS.
- Semantica onesta: solo ENTER/EXIT, `DWELL` e loitering non-zero rifiutati da validator Android e
  bridge. Da stato iniziale ignoto: ENTER se già dentro; nessun EXIT iniziale se già fuori.
- Gate OnePlus: registrazione framework reale → ingresso diagnostico → Engine/Room →
  `/system/bin/true` via Shizuku → journal success → dedup → cleanup, `OK (1)` e ids OS vuoti.
- Resta da provare sul campo l'Esempio 1: "quando esco da casa" (meglio ≥100 m; 50 m resta
  accettato con warning) → Wi-Fi off + Bluetooth on. Il callback può arrivare con minuti di ritardo.

Riferimenti verificati: [`LocationManager.addProximityAlert`](https://developer.android.com/reference/android/location/LocationManager#addProximityAlert(double,double,float,long,android.app.PendingIntent)),
[`ACCESS_BACKGROUND_LOCATION`](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
e [limiti location in background](https://developer.android.com/develop/sensors-and-location/location/background).

### P2-5 — Hardening e chiusura

- ~~D4 shell statica: gate host + device Shizuku e regressioni su trigger esterni.~~ **Completo**
  (`cfc0ef4`, positivo e negativo su OnePlus; Notification/PhoneState fail-closed).
- ~~Wizard OEM: nota/CTA per la gestione batteria OnePlus (documentare, niente magia).~~ **Completo**:
  UI distingue l'esenzione Android dai toggle OxygenOS manuali, che Argus non può verificare.
- **Hardening crash-consistency completo:** Connectivity e chiamate persistono atomicamente
  snapshot + payload pending minimale e bounded prima del dispatch, recuperano lo stesso event-id
  ad `APP_START` e continuano il drain delle altre sorgenti se una fallisce. Chiavi sorgente ed
  event-id sono digest-only; nome rete/device e numero, necessari al matcher, restano soltanto
  nelle preferences private. Gli SMS restano intenzionalmente solo in RAM: nessun testo viene
  scritto in preferences/Room/log.
- **Export/import JSON locale differito oltre P2:** era nice-to-have, non Definition of Done. Farlo
  bene richiede Storage Access Framework, schema/versioning/redazione e import sempre PENDING da
  ri-approvare uno a uno; non viene inserito di fretta nella chiusura dei trigger background.
- **Residuo dichiarato, non mascherato:** `RunShell` può durare fino a 30 s, oltre il budget tipico
  di un receiver. I pending recuperano l'evento ma il claim Engine resta at-most-once: morte processo
  durante una sequenza può lasciare `PARTIAL/INTERRUPTED`. La review raccomanda comandi brevi; una
  vera resumability per-action/worker o FGS short-lived richiede design e test cached/Doze dedicati.
- Full gate senza cache, smoke, aggiornamento handoff/CLAUDE/contract/audit, merge su master.

## Definition of Done P2

Stato al 2026-07-15 sera. **Un solo punto resta aperto.**

| # | Requisito | Stato | Classe di evidenza |
|---|-----------|-------|--------------------|
| 1 | Es. 1 della spec passa live (geofence, multi-azione) | **APERTO** | passato con posizione **simulata** (framework-driven synthetic) + stato device reale verificato; **attraversamento fisico armato, in attesa** |
| 2 | OTP autocopy passa live su SMS vero | **PASS** | manuale Lorenzo (evidenza utente, non trace forense) |
| 3 | Chiamata reale (INCOMING_CALL + CALL_ENDED) | **PASS** | physical/radio E2E, journal osservato |
| 4 | Cavo POWER reale | **PASS** | physical E2E, journal osservato |
| 5 | ACL Bluetooth reale | **PASS** | physical E2E, journal osservato |
| 6 | Connectivity e phone_state armabili, fail-closed senza grant | **PASS** | on-device |
| 7 | Nessun service persistente se nessuna regola lo richiede; FGS si spegne da solo | **PASS** | osservato sul device: assente → presente dopo l'arm → assente dopo il cleanup |
| 8 | `run_shell` statico solo da trigger trusted; `shell.run` mai nella lane generativa | **PASS** | host + OnePlus, positivo e negativo (`cfc0ef4`) |
| 9 | Regole esistenti di Lorenzo intatte | **PASS con nota** | inventario on-device: la regola OTP è intatta; Lorenzo ha confermato di aver cancellato lui "prova argus". Le WhatsApp generative e la "esegui" non risultano — probabile cancellazione sua precedente, **non ricostruibile** perché l'audit non traccia il lifecycle delle regole (→ è la motivazione del backlog P3 sui log) |

**Privacy, trasversale a 3/4/5**: assertione automatica su ogni record audit dei gate — nessun run
di 4+ cifre nel campo `detail`. Numero chiamante, SSID e nome/address Bluetooth non sono mai
arrivati nell'audit.

**Non provati, dichiarati**: latenza GPS reale e Doze/OxygenOS; recupero del bordo perso; regola
chiamata filtrata per numero; match per nome del device BT; CTA UI reale per `BLUETOOTH_CONNECT`
(concesso via `pm grant` nel gate, poi revocato); negativo OTP live (`otp_not_found`), coperto dai
soli test Robolectric.
