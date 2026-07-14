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
| PhoneState INCOMING_CALL / CALL_ENDED | receiver manifest `PHONE_STATE` + `READ_PHONE_STATE` | no service |
| Connectivity POWER | receiver manifest `ACTION_POWER_CONNECTED/DISCONNECTED` (esenti dalle restrizioni implicit broadcast) | no service |
| Connectivity BT | receiver manifest `BluetoothDevice.ACTION_ACL_CONNECTED/DISCONNECTED` (esenti) | no service |
| Connectivity WIFI | `NetworkCallback` → richiede processo vivo → **FGS sentinella** | FGS solo se armato |
| Geofence | da spike P2-0: `LocationManager.addProximityAlert` (PendingIntent, no GMS dep) vs alternativa | no service se proximity |

**FGS sentinella**: parte SOLO quando esiste almeno una regola ARMED+enabled il cui trigger lo
richiede (oggi: solo Wi-Fi connectivity); si spegne al reconcile quando non serve più. Tipo FGS
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

### D4 — Resilienza Shizuku (race osservata sul campo, handoff §23)

Al primo `resolve()` della probe dopo il process start, se lo status è `INSTALLED_NOT_RUNNING`
ma la permission risulta concessa, attendere il binder sticky fino a ~2 s (poll breve) prima di
rispondere: elimina il falso "shell non disponibile" visto alle 18:29. Fail-closed invariato se
il binder non arriva.

### D5 — Cosa NON cambia

- Reply/observed/picker: solo WhatsApp. Nessun reply su SMS (né su altri package).
- Nessun comando shell parametrizzato da contenuti esterni (il validator già non lo permette:
  `cmd` statico). Nessuna denylist aggiunta.
- Trigger sensori: fuori scope definitivo.
- DWELL geofence: se proximity alert non lo supporta nativamente, P2 consegna ENTER/EXIT e
  DWELL resta dichiarato non supportato dal validator (niente finti dwell).

## Fasi

### P2-0 — Spike su device (esiti)

1. ~~`setPrimaryClip` da background su Android 16 reale~~ → **RISOLTO (2026-07-14 sera)**:
   scrittura riuscita da processo instrumentation SENZA focus (OK 1 test, nessuna
   SecurityException) e **verificata da Lorenzo con un incolla reale** ("SPIKE-123456").
   D3 procede col design pulito: copia diretta in background, il fallback CTA resta solo
   come degradazione se l'E2E P2-3 con l'app vera in cached smentisse (improbabile).
   Spike pulito (clearPrimaryClip + uninstall test package); il file di spike si rimuove
   col primo commit P2-3.
2. `addProximityAlert` su OnePlus 15 → spostato all'APERTURA di P2-4 (il PendingIntent
   scatta con app cached/Doze? latenza/raggio?). Decide D1-geofence.
3. Receiver manifest `PHONE_STATE`/`SMS_RECEIVED` con app cached → si valida direttamente
   nell'E2E di P2-2 con SMS reale (niente spike separato: il receiver è il deliverable).

**Ordine di esecuzione aggiornato (valore prima del warm-up)**: P2-2 (PhoneState/SMS) →
P2-3 (OTP, la feature di Lorenzo) → P2-1 (Connectivity) → P2-4 (Geofence) → P2-5 (chiusura).
Il pattern receiver→dispatcher→engine è già consolidato dal listener P1: il warm-up
connectivity non è necessario.

### P2-1 — Connectivity (BT/Power subito, Wi-Fi con FGS)

- engine-core: nessun cambio modello (Trigger.Connectivity esiste); `CapabilityIds.TRIGGER_CONNECTIVITY`
  e derivazione requirements se mancante.
- automation-android: receiver manifest BT/power → `TriggerEvent.ConnectivityChanged` → engine
  (pattern del listener P1: dispatcher + dedup eventId); FGS sentinella + NetworkCallback per
  Wi-Fi; registrar esteso (`Trigger.Connectivity` armabile quando il grant/meccanismo c'è);
  probe pubblica capability e tool; reconcile accende/spegne il FGS.
- UI: riga salute "Servizio sentinella" in Sistema SOLO quando attivo (onestà FGS).
- Test: unit (registrar/probe/dispatcher), Robolectric E2E sintetico, instrumented su device
  (BT on/off reale, cavo alimentazione reale). Bridge prompt: esempi connectivity.

### P2-2 — PhoneState (chiamate + SMS)

- Permissions: `RECEIVE_SMS` + `READ_PHONE_STATE` runtime, richieste SOLO quando una regola le
  richiede (arm gate → CTA come battery P1-6); righe salute in Sistema; onboarding INVARIATO
  (non tutti vogliono SMS).
- Receiver manifest: PHONE_STATE (RINGING→INCOMING_CALL, IDLE dopo OFFHOOK/RINGING→CALL_ENDED,
  number quando disponibile e permesso) e SMS_RECEIVED (number + smsText volatile D2, multipart
  ricomposti).
- Matcher: `numbersMatch` esiste già (suffisso ≥7 cifre). EventId: digest include number+testo
  hashati, MAI in chiaro.
- Registrar/probe/validator/bridge aggiornati. Test: unit + Robolectric + device con SMS vero
  (numero di Lorenzo).

### P2-3 — OTP autocopy (la feature di Lorenzo)

- engine-core: `Action.CopyToClipboard` (D3) + validator + `CapabilityRequirements` + render
  review ("Copia negli appunti · estrazione: `regex`" — regex integrale, §5 non si soffia).
- automation-android: executor clipboard sensibile (o fallback CTA da spike); wiring FireContext
  → payload testuale del trigger (SMS/notifica).
- bridge: tool `copy_to_clipboard` + REGOLA: estrazione SOLO via regex nel draft; suggerire il
  pattern OTP di default `(?<!\+)\b(\d{4,8})\b` con esclusione prefissi telefonici (da
  raffinare in TDD con corpus di SMS reali di esempio, inclusi falsi positivi: importi, anni,
  numeri parziali).
- E2E live con Lorenzo: SMS OTP vero (es. codice di test) → clipboard entro pochi secondi,
  audit senza contenuto. Negativo: SMS senza codice → FAILED `otp_not_found`, nessuna copia.

### P2-4 — Geofence (Es. 1 end-to-end)

- Meccanismo da spike P2-0. `resolveCurrentLocation` all'arm esiste già
  (`FrameworkCurrentLocationProvider`).
- Wizard background location: grant `ACCESS_BACKGROUND_LOCATION` già presente sul device di
  Lorenzo; riga salute già esistente diventa azionabile (CTA settings) quando una regola
  geofence la richiede.
- Registrar: registrazione per-rule (lat/lng/radius congelati nel draft approvato), unregister
  al disable/delete/revoca; boot recovery: ri-registrazione post-BOOT (pattern AlarmManager
  P0-B §5.3).
- ENTER/EXIT; DWELL secondo D5. Es. 1 live: "quando esco da casa ±50 m disattiva il Wi-Fi e
  attiva il Bluetooth" (multi-azione!).

### P2-5 — Hardening e chiusura

- D4 retry binder Shizuku nel probe (test con fake clock).
- Wizard OEM: nota/CTA per la gestione batteria OnePlus (documentare, niente magia).
- Export/import JSON locale delle automazioni (nice-to-have §17: assicura contro un wipe;
  import = draft da ri-approvare UNO A UNO, mai arm diretto — fingerprint non trasferibile).
- Full gate senza cache, smoke, aggiornamento handoff/CLAUDE/contract/audit, merge su master.

## Definition of Done P2

- Es. 1 della spec passa live (geofence reale, multi-azione).
- OTP autocopy passa live su SMS vero; il contenuto SMS non appare MAI in log/DB/audit.
- Trigger connectivity e phone_state armabili, verificati on-device, fail-closed senza grant.
- Nessun service persistente quando nessuna regola armata lo richiede; il FGS sentinella si
  spegne da solo ed è dichiarato in Sistema.
- La race Shizuku del primo compile non è più riproducibile (retry binder).
- Regole esistenti di Lorenzo (WhatsApp generative + "esegui") intatte a ogni migrazione.
