# Argus — handoff operativo Codex → Claude (P2, quota stop)

- Data: 2026-07-15, Europe/Rome
- Branch: `feat/argus-p2-background`
- Commit di codice finale prima di questo documento: `e5f77a7`
- Base `master`: `0af4ac3`
- Device laboratorio: OnePlus `100.74.117.9:5555`, Android API 36
- Bridge: `argus-bridge` su Hermes, loopback `127.0.0.1:8092` dietro Tailscale Serve HTTPS

## 1. Sintesi esecutiva

P2 è **implementata quasi integralmente nel codice**, ma **non è Definition of Done**: mancano
ancora i bordi fisici/radio e l'Esempio 1 geofence multi-azione. Non fare merge su `master` finché
queste prove non sono state classificate e, dove praticabile, completate.

La sequenza Codex ha consegnato:

1. prova production-path SMS sintetica sul OnePlus e fix del doppio broadcast chiamata;
2. `run_shell` statico e approvato, solo da trigger trusted;
3. trigger Connectivity (BT, POWER, Wi-Fi con sentinella FGS on-demand);
4. geofence durable via `LocationManager.addProximityAlert`;
5. crash-consistency di Connectivity/chiamate/geofence;
6. hardening finale delle regex OTP con RE2/J a tempo lineare;
7. documentazione aggiornata sui limiti RCS/MMS, OxygenOS, DWELL e resumability.

Lo stop quota è avvenuto con working tree inizialmente dirty perché una seconda sessione Codex
stava completando il gate RE2. Quella sessione è stata fermata quando il contatore ufficiale è
sceso dal target 30% al 28%. I file sono stati revisionati, testati, committati in `e5f77a7` e
questo handoff viene aggiunto dopo, senza aprire altre feature.

## 2. Stato Git da ereditare

Commit P2 successivi all'handoff Claude `0c337e8`:

| Commit | Scopo | Stato verificato |
| --- | --- | --- |
| `04e6721` | harness production-path SMS sul device | passato in precedenza `OK (1)` |
| `dea6f79` | identità stabile e numero conservato sui doppi `PHONE_STATE` | test host verdi |
| `cfc0ef4` | shell statica da trigger trusted | host + OnePlus positivo/negativo |
| `f2bca8e` | Connectivity completa | host + gate POWER production sul OnePlus |
| `8215895` | geofence durable framework | host + vera registrazione OS sul OnePlus |
| `a7ee8b3` | pending crash-safe e recovery background | full gate + tre test production device |
| `e5f77a7` | regex estrazione lineare RE2/J | gate mirato, APK build/install, bridge locale/remoto |

Prima di lavorare:

```powershell
cd C:\argus
git fetch origin
git checkout feat/argus-p2-background
git status --short
git log -8 --oneline --decorate
```

Atteso dopo il push di questo handoff: branch e origin allineati, working tree pulito. Non usare
`git reset --hard` e non scartare eventuali modifiche di Lorenzo.

## 3. Evidenza verificata in questa presa in carico

### 3.1 Gate host fino a `a7ee8b3`

Gate indipendente forzato, non dalla cache:

```powershell
.\gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest `
  --no-build-cache --rerun-tasks --no-parallel --console=plain
```

Esito: `BUILD SUCCESSFUL`, 758 task eseguiti, 2m19s, nessun errore test/lint/build.

Prima del full gate è passato anche il gruppo mirato di recovery:

- `dev.argus.automation.connectivity.*`
- `dev.argus.automation.phone.*`
- `dev.argus.automation.geofence.*`
- `dev.argus.automation.ArgusRuntimeControllerTest`

### 3.2 Gate specifico `e5f77a7` (RE2)

Verificato dopo le modifiche RE2:

```powershell
.\gradlew.bat :engine-core:test `
  :automation-android:testDebugUnitTest `
  --tests "dev.argus.automation.ClipboardCopierTest" --console=plain
```

Esito: `BUILD SUCCESSFUL`; include `SafeExtractionRegexTest`, validator e clipboard Robolectric.

Bridge locale:

```powershell
cd C:\argus\ops\hermes
python -m unittest test_bridge.py
```

Esito: 20/20.

L'APK con RE2 è stato costruito alle 12:52 e installato sul OnePlus. `dumpsys package dev.argus`
ha riportato `versionName=0.1.0`, target 36 e `lastUpdateTime=2026-07-15 12:56:14`.

**Confine dell'evidenza:** non è stato rieseguito il full gate 758-task dopo `e5f77a7`, né il test
clipboard instrumented dopo l'installazione RE2. Il primo task Claude deve quindi essere un full
gate finale su HEAD; non presentare `e5f77a7` come full-gated finché non passa.

### 3.3 Hermes dopo `e5f77a7`

Verificato direttamente dopo lo stop della sessione concorrente:

- servizio `systemctl --user is-active argus-bridge` → `active`;
- suite remota → 20/20;
- backup presente: `~/argus-bridge/bridge.py.pre-re2-codex-20260715-125622`;
- hash repo/remoto identici:
  - `bridge.py`: `8e20f0cd739946d643b341a5060b065a8c377ce2b79a68df185be83cb623ab75`;
  - `test_bridge.py`: `1aaa7f8c93e1c3dee22f09b84ed9230cb9b96b599a864ea5e8f255485fa9a6f1`.

Non passare bearer o altri segreti via command line, ADB o instrumentation. Il meccanismo
file-privato `run-as` one-shot del ledger resta obbligatorio per test live `/compile`.

### 3.4 Evidenza device precedente, ancora valida fino ad `a7ee8b3`

Nel checkpoint `.superpowers/sdd/progress.md` sono registrati:

- `ArgusPhoneIngressInstrumentedTest` → `OK (1)`;
- `ArgusConnectivityInstrumentedTest` → `OK (1)`;
- `ArgusGeofenceInstrumentedTest` → `OK (1)`;
- nessun FGS Connectivity residuo;
- geofence OS ids `[]` dopo cleanup;
- Shizuku attivo/autorizzato durante i gate.

Questi test provano pipeline production e framework registration, ma non sostituiscono i bordi
radio/fisici descritti al §7.

## 4. Cosa cambia davvero nel codice

### 4.1 Telefonia e OTP

- Receiver manifest risolti tramite Hilt entry point esplicito: il vecchio receiver non inizializzava
  l'injection e nessuna regola SMS scattava.
- SMS multipart ricomposti per mittente; corpo volatile, bounded e mai persistito/loggato.
- `textMatch` è valido solo per `SMS_RECEIVED`.
- Chiamate: snapshot persistito, TTL 24h, clock rollback/futuro fail-closed, numero conservato fino
  a `CALL_ENDED`; doppio broadcast anonimo/numerato usa lo stesso event-id.
- `CopyToClipboard` estrae il primo capture group, altrimenti il match intero; clipboard marcata
  sensibile. Fallimenti `otp_not_found`/`extraction_regex_invalid` non alterano la clipboard.
- `e5f77a7`: RE2/J 1.8 elimina ReDoS su testo controllato dal mittente. Il pattern OTP legacy
  `(?<!\+)\b(\d{4,8})\b` viene tradotto internamente nel pattern RE2 sicuro, senza cambiare JSON
  o fingerprint delle regole già approvate.
- RCS e MMS non generano `SMS_RECEIVED`. UI, piano e contract ora dicono esplicitamente
  “SMS telefonici”.

### 4.2 Shell statica

- `RunShell(cmd)` usa `/system/bin/sh -c` solo come azione deterministica già approvata.
- Pubblicata soltanto con Shizuku autorizzato.
- Consentita da trigger Time/Geofence/Connectivity; bloccata da Notification e PhoneState.
- `shell.run` raw resta fuori dalla lane generativa e non deve essere aggiunta.
- Non esiste denylist di comandi: la sicurezza è trust del trigger + fingerprint + approvazione
  esplicita, come deciso da Lorenzo.

### 4.3 Connectivity

- BT ACL manifest-safe; POWER e Wi-Fi nella sentinella FGS `specialUse` on-demand.
- FGS esiste solo se una regola ARMED+enabled Wi-Fi/POWER lo richiede.
- Capability separate per Wi-Fi generico, identità SSID, BT e POWER.
- Snapshot e pending sono committati prima del dispatch. Source key ed event-id sono digest-only;
  nome SSID/device necessario al matcher resta bounded nelle preferences private.
- Recovery `APP_START` ripete lo stesso event-id e prova tutte le sorgenti anche se una fallisce.
- Startup FGS fallito ritorna `START_NOT_STICKY`, evitando restart loop.

### 4.4 Geofence

- Backend `LocationManager.addProximityAlert`, nessuna dipendenza Google Play Services.
- PendingIntent esplicito, interno, mutable e legato a id/fingerprint.
- Solo ENTER/EXIT. DWELL e `loiteringDelayMs != 0` sono rifiutati da Android e Hermes.
- FINE precisa + background location obbligatorie.
- Stato per-rule durable: prepare prima della registrazione OS, activate dopo, pending prima del
  dispatch, cleanup su disable/delete/revoca, ricreazione a process/boot/update.
- Recovery missed-edge legge una posizione one-shot solo se esiste uno stato precedente, con
  isteresi 25 m e clamp Haversine.
- Da stato ignoto: ENTER se già dentro; nessun EXIT inventato se già fuori.

## 5. Audit: problemi corretti e problemi ancora aperti

### Corretti

1. Hilt receiver telefonia non inizializzato.
2. Diagnosi errata “race Shizuku”: `run_shell` era strutturalmente non pubblicato.
3. Perdita di numero fra broadcast RINGING anonimo/numerato e CALL_ENDED.
4. Finestra crash fra commit snapshot e dispatch su chiamate/Connectivity.
5. Starvation del drain quando la prima sorgente pending fallisce.
6. Clock rollback che poteva rendere valido uno snapshot chiamata futuro.
7. Restart loop FGS dopo bootstrap fallito.
8. Documentazione/UI che confondeva SMS con RCS/MMS e toggle Android con OxygenOS.
9. ReDoS nell'estrazione regex OTP.
10. Delete detail che puliva solo AlarmManager, non Connectivity/Geofence indipendentemente.

### Aperti o deliberatamente limitati

1. **`BroadcastReceiver.goAsync()` vs shell fino a 30 s.** Una morte processo dopo il claim può
   lasciare una sequenza multi-azione parziale. La redelivery è at-most-once: non ripete un'azione
   dall'esito incerto. Non promettere resumability per-action. Se servono comandi lunghi, prima
   misurare su device cached/Doze, poi progettare worker/FGS short-lived; non alzare il timeout.
2. **SMS RAM-only.** Se il processo muore durante il dispatch SMS, il testo non è recuperabile.
   È una scelta privacy intenzionale, non un bug da “risolvere” persistendo il messaggio.
3. **Bridge Python vs RE2.** Il validator server rifiuta lookaround/backreference comuni, ma è una
   pre-validazione euristica; alcune sintassi Python rare possono arrivare al validator Android e
   venire rifiutate lì. Nessun bypass, possibile errore UX tardivo. Non inventare un parser regex.
4. **Payload private.** “Digest-only” vale per chiavi sorgente/event-id, non per tutti i payload:
   numero chiamata e nome rete/device necessari al matcher sono bounded in app-private prefs.
   Il corpo SMS non viene mai scritto.
5. **Geofence framework/OEM.** Callback, latenza e missed edge non sono garantiti; sotto 100 m c'è
   warning e il comportamento OxygenOS/Doze va provato sul campo.
6. **Export/import JSON.** Esplicitamente differito oltre P2: era nice-to-have, non DoD. Richiede
   SAF, schema/versioning/redazione e import PENDING da ri-approvare uno per uno.
7. **P0/P1 reboot/LNP.** Restano gate esterni storici. Non riavviare il telefono finché Lorenzo
   non è fisicamente presente e può riattivare ADB TCP/Shizuku.

## 6. La frase utente non ancora classificata

Lorenzo ha scritto: **“provato e ha funzionato perfettamente”**. Non è stato chiarito se si
riferisse a:

- SMS telephony/OTP;
- collegamento/scollegamento cavo;
- ACL Bluetooth;
- geofence reale.

Nessun log recente era rimasto nei tag `ArgusPhone`, `ArgusConnectivity`, `ArgusGeofence`, quindi
non assegnare quella frase a un gate a intuito. La prima domanda a Lorenzo deve essere soltanto:

> Quale prova era: SMS vero/OTP, cavo, Bluetooth oppure geofence?

Poi aggiornare piano/ledger con l'evidenza esatta, senza chiedergli di ripetere un test già valido.

## 7. Cosa NON è ancora provato

1. SMS telephony reale ricevuto dal modem con `SMS_RECEIVED`, app cached.
2. `textMatch` su SMS reale e OTP copiato, poi incollato da Lorenzo in un'altra app.
3. Negativo OTP live: SMS senza codice → `otp_not_found`, clipboard precedente intatta.
4. Chiamata reale: INCOMING_CALL e CALL_ENDED, inclusa regola filtrata per numero.
5. Inserimento/rimozione cavo reale con regola POWER.
6. Connessione/disconnessione ACL di un dispositivo Bluetooth reale.
7. Attraversamento fisico geofence, latenza cached/Doze/OxygenOS e missed-edge recovery.
8. Esempio 1 completo: “esco da casa” → Wi-Fi off + Bluetooth on, con raggio almeno 100 m se
   possibile e aspettative di latenza realistiche.
9. Full gate completo su HEAD dopo `e5f77a7`.

## 8. Ordine consigliato per Claude

1. Chiarire quale test manuale Lorenzo ha già completato e marcarlo nel ledger.
2. Verificare `git status` pulito e origin allineato.
3. Eseguire il full gate su HEAD con il comando del §3.1.
4. Rieseguire **solo se necessario** `ArgusPhoneIngressInstrumentedTest` per provare il packaging
   RE2; mai `ArgusNavigationInstrumentedTest` sul telefono configurato.
5. Eseguire i bordi fisici rimasti con Lorenzo, uno alla volta, raccogliendo prima log/audit.
6. Aggiornare piano P2, contract, design, audit e ledger con verificato vs ragionato.
7. Fare un audit finale del diff `master..HEAD`, in particolare manifest/permissions/FGS/privacy.
8. Solo con DoD accettata: full gate finale, merge non distruttivo su `master`, push e smoke.

Non aggiungere nuove feature durante questa chiusura. Export/import e worker resumable vanno in un
piano successivo con decisione esplicita.

## 9. Comandi operativi sicuri

### Host

```powershell
cd C:\argus
git status --short
git diff --check master..HEAD
.\gradlew.bat test lintDebug assembleDebug assembleDebugAndroidTest `
  --no-build-cache --rerun-tasks --no-parallel --console=plain
```

### Device

Un comando ADB alla volta:

```powershell
adb connect 100.74.117.9:5555
adb devices
adb -s 100.74.117.9:5555 logcat -c
adb -s 100.74.117.9:5555 logcat -v time ArgusPhone:D ArgusConnectivity:D ArgusGeofence:D '*:S'
```

Classi consentite e mirate:

- `dev.argus.ArgusPhoneIngressInstrumentedTest`
- `dev.argus.ArgusConnectivityInstrumentedTest`
- `dev.argus.ArgusGeofenceInstrumentedTest`
- `dev.argus.ArgusStaticShellInstrumentedTest`

Non eseguire `dev.argus.ArgusNavigationInstrumentedTest`. Non spegnere il Wi-Fi da remoto: sostiene
ADB/Tailscale. Non fare reboot senza Lorenzo presente. Non passare segreti con `am instrument -e`.

### Hermes

```powershell
ssh hermes "systemctl --user is-active argus-bridge"
ssh hermes "cd ~/argus-bridge && python3 -m unittest test_bridge.py"
ssh hermes "sha256sum ~/argus-bridge/bridge.py ~/argus-bridge/test_bridge.py"
```

Prima di ogni deploy: backup timestamped, copia atomica, suite remota, restart, `is-active`, hash
repo/host. Non configurare automaticamente provider a pagamento.

## 10. Matrice DoD P2

| Requisito | Codice | Gate sintetico/framework | Gate fisico/live |
| --- | --- | --- | --- |
| PhoneState/SMS armabile e fail-closed | completo | `OK (1)` production path | aperto salvo chiarimento utente |
| OTP autocopy e privacy | completo | host + device sintetico | aperto salvo chiarimento utente |
| Connectivity | completo | POWER production `OK (1)` | cavo/BT aperti salvo chiarimento |
| Geofence ENTER/EXIT | completo | registrazione OS + pipeline `OK (1)` | attraversamento/Esempio 1 aperti |
| Shell trusted-only | completo | host + Shizuku positivo/negativo | completo per lo scope P2 |
| FGS solo on-demand | completo | start/stop e cleanup verificati | OxygenOS/Doze osservazionale |
| Crash-consistency edge | completo | unit/integration/device | resumability per-action fuori scope |
| Regex OTP non-ReDoS | completo (`e5f77a7`) | host + APK install + bridge 20/20 | full gate HEAD ancora da fare |
| Migrazioni/regole esistenti intatte | nessuna nuova migration P2 finale | gate precedenti verdi | ricontrollare prima del merge |

## 11. File chiave

- Piano P2: `docs/superpowers/plans/2026-07-14-argus-p2-background-triggers.md`
- Design master: `docs/superpowers/specs/2026-07-12-hermes-android-agent-design.md`
- Contract bridge: `docs/design/hermes-bridge-contract.md`
- Handoff Claude originario: `docs/superpowers/specs/2026-07-15-argus-claude-to-codex-handoff.md`
- Ledger locale: `.superpowers/sdd/progress.md`
- Regex: `engine-core/src/main/kotlin/dev/argus/engine/safety/SafeExtractionRegex.kt`
- Clipboard: `automation-android/src/main/kotlin/dev/argus/automation/ClipboardCopier.kt`
- Telefonia: `automation-android/src/main/kotlin/dev/argus/automation/phone/`
- Connectivity: `automation-android/src/main/kotlin/dev/argus/automation/connectivity/`
- Geofence: `automation-android/src/main/kotlin/dev/argus/automation/geofence/`
- Bridge: `ops/hermes/bridge.py`, `ops/hermes/test_bridge.py`

## 12. Regole di sicurezza da non perdere

- Sensori/state keys restano closed-world; niente discovery dinamica.
- Trigger Notification/PhoneState non possono eseguire shell statica.
- `shell.run` generativo resta vietato.
- Nessuna coordinata precisa inviata al bridge.
- Nessun testo SMS in Room, preferences, audit o log.
- Nessuna approvazione/fingerprint trasferibile tramite import.
- Cancellation sempre rilanciata, mai inghiottita da `runCatching` generico.
- Cleanup di scheduler/Connectivity/Geofence indipendente: un errore non deve saltare gli altri.
- Nessun test navigation sul device configurato; nessun reboot remoto non recuperabile.

## 13. Conclusione onesta

La parte ingegneristica P2 è vicina alla chiusura e i principali rischi trovati dall'audit sono
stati corretti. La distanza residua non è soprattutto “scrivere altro codice”: è provare il
comportamento reale di modem, Bluetooth, cavo e geofence/OxygenOS, poi chiudere documentazione e
merge senza trasformare evidenza sintetica in una promessa di affidabilità sul campo.

Riprendere da §6, poi §8. Non dichiarare P2 completa prima della matrice §10 aggiornata.
