<!-- Prodotto da orchestrazione subagent (workflow argus-android-commands-design): 3 ricercatori Opus 4.8 (architettura azioni + API Android) + sintesi Fable 5 xhigh. Task #51. La 'lista comandi' richiesta da Lorenzo + architettura + coordinamento Hermes + piano TDD. -->

# Argus — Design: azioni di INTERAZIONE Android in scrittura (sintesi orchestratore)

Anchor architetturale: tutti i `when` sul dominio `Action` sono esaustivi senza `else` — aggiungere un subtype rompe la compilazione in ogni punto di integrazione finché non viene gestito (`Action.kt:46-64`, `ActionPrivilege.kt:22-43`, `CapabilityRequirements.kt:92-117`, `ShizukuActionExecutor.kt:53-112`, `DraftValidator.kt:261-323`). Gli unici punti fuori dal compilatore Kotlin sono `ops/hermes/bridge.py` e il reference doc: unici a rischio drift.

---

## 1) LISTA COMANDI da aggiungere (la "lista comandi" richiesta)

### Ondata 1 — alto valore, da implementare

| Comando | Cosa fa | Meccanismo Android | Tier | Permesso | Parametri | Affidabilità / caveat | Priorità |
|---|---|---|---|---|---|---|---|
| `set_alarm` | Imposta la **sveglia reale** dell'app orologio (non una notifica) | Intent `AlarmClock.ACTION_SET_ALARM` + `EXTRA_HOUR/MINUTES/MESSAGE/SKIP_UI/DAYS`, `startActivity` NEW_TASK — stesso pattern di `openHttpUrl` (`AndroidBaseActionSurface.kt:44-54`) | **BASE** | `com.android.alarm.permission.SET_ALARM` (normal, auto-concesso, solo manifest) | `hour:0-23, minute:0-59, label?, skipUi=true, days?:List<Int>` | Alta. `SKIP_UI` è un hint (stock OxygenOS lo onora). Caveat BAL da background: se bloccato, fallback PRIVILEGED `am start -a ... --ei ...` (uid 2000 bypassa BAL) | **P0** — chiude esattamente il gap segnalato |
| `set_timer` | Avvia un timer reale | Intent `AlarmClock.ACTION_SET_TIMER` + `EXTRA_LENGTH` (secondi) | **BASE** | `SET_ALARM` (normal) | `seconds:1-86400, label?, skipUi=true` | Alta, stessi caveat BAL di set_alarm | **P0** |
| `set_brightness` | Luminosità schermo assoluta | `settings put system screen_brightness_mode 0` + `screen_brightness <0-255>` via `PrivilegedShell` (pattern `DeviceTools.kt:80,89`) | **PRIVILEGED** | Shizuku (alternativa BASE: `WRITE_SETTINGS` special grant + `Settings.System.putInt`) | `level:0-255, manualMode=true` | Alta. Forzare `mode=0` o l'auto-brightness sovrascrive; su A12+ pipeline float ma l'int legacy resta onorato su OxygenOS | **P0** |
| `set_dark_mode` | Dark/light/auto di sistema | `cmd uimode night yes\|no\|auto` (via `UiModeManagerService`, propaga il config-change) | **PRIVILEGED** | Shizuku (`MODIFY_DAY_NIGHT_MODE` e `WRITE_SECURE_SETTINGS` non concedibili ad app normali) | `mode: DARK\|LIGHT\|AUTO` | Alta per on/off; lo stile "enhanced" OxygenOS resta su chiavi OEM separate. Preferire `cmd uimode` a `settings put secure ui_night_mode` (notify service non garantito) | **P0** |
| `write_setting` | **Azione parametrica** di scrittura impostazioni — contraltare WRITE di `StateQuery.Setting` (`StateQuery.kt:130-134`) | `cmd settings put <system\|secure\|global> <key> <value>`, argv-list, riusa `SettingNamespace` (`StateQuery.kt:8`) | **PRIVILEGED** | Shizuku (secure/global impossibili altrimenti; system richiederebbe comunque WRITE_SETTINGS) | `namespace, key, value` — **allowlist chiusa di chiavi + valori letterali CLEAN** (vedi §3) | Alta come meccanica; alcune chiavi richiedono broadcast per applicarsi (es. `airplane_mode_on`). Authority-sink: la validazione è il punto critico | **P0** |
| `set_volume` | Volume per-stream (media/ring/alarm/notification) | `AudioManager.setStreamVolume(STREAM_*, v)` | **BASE** | nessuno (DND policy grant solo se porta ring/notif a silenzioso — gate già gestita in `AndroidBaseActionExecutor.kt:37-44`) | `stream: MEDIA\|RING\|ALARM\|NOTIFICATION, level:Int` | Alta | **P1** |
| `set_flashlight` | Torcia on/off | `CameraManager.setTorchMode()` | **BASE** | nessuno (API 23+) | `on:Boolean` | Alta | **P1** |
| `open_settings_screen` | Apre una schermata Impostazioni | Intent `Settings.ACTION_*` con **enum chiuso** (WIFI, BT, DISPLAY, LOCATION, APP_DETAILS, BATTERY, ...) | **BASE** | nessuno | `screen: enum chiuso` (+`pkg?` per APP_DETAILS) | Alta; enum chiuso evita il routing-sink di action string arbitrarie | **P1** |
| `media_control` | Play/pause/next/previous globale | `cmd media_session dispatch play-pause\|next\|previous` | **PRIVILEGED** | Shizuku | `command: enum` | Alta; molto più robusto di `Tap(x,y)` cieco | **P1** |
| `set_auto_rotate` | Auto-rotazione on/off | `settings put system accelerometer_rotation 0\|1` (o via `write_setting`) | **PRIVILEGED** | Shizuku | `on:Boolean` | Alta | **P1** (copre `write_setting`) |
| `set_screen_timeout` | Timeout schermo | `settings put system screen_off_timeout <ms>` (o via `write_setting`) | **PRIVILEGED** | Shizuku | `ms:Int bounded` | Alta | **P1** (copre `write_setting`) |
| `dismiss_alarm` / `snooze_alarm` | Spegne/posticipa sveglia | Intent `AlarmClock.ACTION_DISMISS_ALARM` / `ACTION_SNOOZE_ALARM` | **BASE** | `SET_ALARM` | `label?` / `minutes?` | Alta | **P1** |
| `vibrate` | Pattern vibrazione | `Vibrator.vibrate(effect)` | **BASE** | `VIBRATE` (normal) | `pattern/duration` | Alta | **P2** |

### Ondata 2 — toggle radio/sistema PRIVILEGED (stesso pattern `svc`/`cmd` di `DeviceTools.kt:54-70`, copia-incolla a basso rischio)

| Comando | Meccanismo | Tier | Caveat | Priorità |
|---|---|---|---|---|
| `set_mobile_data` | `svc data enable\|disable` | PRIVILEGED | Alta affidabilità | P1 |
| `set_nfc` | `svc nfc enable\|disable` | PRIVILEGED | Alta | P2 |
| `set_location` | `cmd location set-location-enabled true\|false` | PRIVILEGED | Alta (A12+) | P2 |
| `set_airplane_mode` | `cmd connectivity airplane-mode enable\|disable` | PRIVILEGED | Alta (A11+) | P2 |
| `force_stop_app` | `am force-stop <pkg>` | PRIVILEGED | Alta | P2 |
| `global_nav` (home/back/recents) | `input keyevent 3\|4\|187` | PRIVILEGED | Alta | P2 |
| `statusbar` (expand/collapse) | `cmd statusbar expand-notifications\|collapse\|expand-settings` | PRIVILEGED | Alta | P2 |
| `take_screenshot` | `screencap -p` — `DeviceTools.capture()` **esiste già** (`DeviceTools.kt:159`), va solo esposto come Action + salvataggio MediaStore | PRIVILEGED | Alta | P2 |

### Esclusi / rinviati (motivo tecnico o di sink, non morale — coerente con D0)

- `hotspot`: API OxygenOS pesantemente custom, fallimento silenzioso probabile — non conviene senza verifica su device.
- `pm clear`, `reboot`, `wm density`: funzionano ma distruttivi/irreversibili, valore d'automazione basso.
- SMS/chiamate in uscita, Intent/broadcast arbitrario: execution/routing sink verso l'esterno (`decision-record §4.2:102-103`) — rinviati a P4 con label taint, non come azione libera ora.
- Lock screen: `input keyevent 26` è un toggle, non un lock affidabile; via pulita = AccessibilityService/DevicePolicy, rinviata.
- Regola pratica OxygenOS 16: **preferire `cmd <service>`/`svc` AOSP alle chiavi `settings` OEM-custom** (refresh rate, battery saver, zen/gaming mode → verifica device prima, gate `decision-record §11.4`).

---

## 2) ARCHITETTURA

### Checklist di innesto per ogni nuova azione tipizzata (~11-13 punti di edit, quasi tutti compile-enforced)

| # | File | Modifica |
|---|------|----------|
| 1 | `engine-core/.../model/Action.kt:12-27` | `const val` in `ActionTypeIds` (wire id stabile, es. `SET_ALARM = "set_alarm"`) |
| 2 | `Action.kt:44-108` | nuova `@Serializable @SerialName data class ... : Action` |
| 3 | `Action.kt:46-64` | ramo `ActionTier.DETERMINISTIC` nel getter `tier` |
| 4 | `ActionPrivilege.kt:22-43` | ramo BASE/PRIVILEGED in `ActionPrivileges.of` (fonte canonica della classificazione) |
| 5 | `CapabilityRequirements.kt:28-40` | `const val ACTION_XXX` in `CapabilityIds` |
| 6 | `CapabilityRequirements.kt:92-117` | ramo in `forAction` — entra nel contratto approvato; `CapabilityReconciler.reconcile` (`AndroidCapabilityProbe.kt:429-433`) marca `needsReview` se la derivazione cambia |
| 7 | esecuzione BASE | metodo in `BaseActionSurface` (`AndroidBaseActionExecutor.kt:14-22`) + logica host-testata in `AndroidBaseActionExecutor.kt:29-77` + adapter sottile `AndroidBaseActionSurface.kt:16-55` |
| 7' | esecuzione PRIVILEGED | metodo su `DeviceController` (`DeviceTools.kt:34-43`) + impl argv-list `runChecked` (`DeviceTools.kt:197-212`) — **mai `sh -c`**, binari `/system/bin/*` assoluti (`DeviceTools.kt:257-265`, D3 §5.2) |
| 8 | `ShizukuActionExecutor.kt:53-112` | case dispatch; per BASE usare il pattern `baseActions?.xxx() ?: success { tools.xxx(...) }` (righe 59-70) che dà fallback Shizuku gratuito (utile per il caveat BAL di set_alarm) |
| 9 | `AndroidCapabilityProbe.kt:366-407` | bucket `*_ACTION_TYPES` + `*_CAPABILITIES`; ragione tipizzata in `unavailableTools` (:283-304); eventuale nuovo flag di grant in `AndroidCapabilityState` (:37-56) |
| 10 | `DraftValidator.kt:261-323` | ramo di validazione dominio (compile-enforced) |
| 11-12 | `bridge.py` + reference.md | vedi §4 |

### Raccomandazione: IBRIDO — parametrica + tipizzate curate (non aut-aut)

**Sì all'azione parametrica `write_setting(namespace, key, value)`**, contraltare WRITE di `StateQuery.Setting`:
- La simmetria è già mezza pagata: `SettingNamespace{SYSTEM,SECURE,GLOBAL}` (`StateQuery.kt:8`), regex `QUERY_NAME` (`StateQuery.kt:30`), fingerprint `canonicalId` (`StateQuery.kt:111-122`), pattern famiglia→capabilityId. `WriteSettingPolicy` nasce gemello di `StateQueryPolicy` (`StateQuery.kt:20-87`).
- Elimina il drift a 13 punti: una nuova chiave scrivibile = allargare una allowlist, zero edit al sealed/probe/bridge. È la stessa ragione per cui i lettori sono stati resi parametrici in P3 (decision record §1.2).
- Come i reader, il `canonicalId` della scrittura entra in `CapabilityRequirements.forAction` → ogni chiave scritta passa dalla review e dal `CapabilityReconciler`.

**Ma con asimmetria WRITE vs READ** (unico punto dove il pattern dei reader NON si copia tale e quale):
- La lettura è confinata (`StateContextClassification.minimumConfidentiality` → SECRET, `Action.kt:127-134`); la **scrittura crea autorità** (es. `secure adb_enabled`, `enabled_accessibility_services`, `location_mode`). Quindi: **allowlist chiusa di chiavi per namespace** (non regex aperta), con SECURE/GLOBAL che partono da un set minimo, ogni chiave aggiunta deliberatamente (scelta di prodotto classe-4, revocabile — decision record §2).
- Sempre PRIVILEGED (Shizuku): `settings put` su secure/global non ha percorso app-normale.

**E azioni tipizzate curate dove Intent/typed è più sicuro o più robusto**:
- `set_alarm`/`set_timer`: Intent `AlarmClock.*` = **BASE, zero Shizuku, zero grant runtime** — irraggiungibile via `write_setting`.
- `set_dark_mode`: `cmd uimode night` passa dal service e propaga il config-change; scrivere `ui_night_mode` non sempre notifica.
- `set_brightness`, `set_volume`, `open_settings_screen`: semantica di prodotto, valori bounded validabili, review leggibile ("luminosità 40%" vs "system screen_brightness 102").

Regola di progetto: **typed action quando esiste una semantica/API migliore del raw setting; `write_setting` come lungo-coda per tutto il resto** (font_scale, screen_off_timeout, accelerometer_rotation, stay_on_while_plugged_in, chiavi future senza release).

---

## 3) SICUREZZA / VALIDAZIONE (D0: limiti solo etici)

L'unico invariante assoluto di D0 è: *"un dato non fidato non può creare o cambiare autorità"* (`2026-07-16-argus-p3-decision-record.md` §4, D2). Tutto il resto è tipizzazione/validazione, non divieto.

- **BASE senza privilegi**: `set_alarm`, `set_timer`, `dismiss/snooze_alarm`, `set_flashlight`, `set_volume`, `open_settings_screen`, `vibrate`. Funzionano anche sul profilo play-core (decision record §7.1). Grant gestiti col pattern esistente (DND policy in `AndroidBaseActionExecutor.kt:31-35`, probe `AndroidCapabilityProbe.kt:100-103`).
- **PRIVILEGED (Shizuku)**: tutte le scritture `settings put`/`cmd`/`svc`. Nessun grant utente extra, ma disponibilità pubblicata solo con Shizuku attivo (`PRIVILEGED_ACTION_TYPES`, `AndroidCapabilityProbe.kt:366-370`).
- **`write_setting` è un authority/routing sink** per la matrice D2 (`decision-record:96-109`: "setting key scelto dinamicamente" con input TAINTED = NO). Regime identico a `run_shell`:
  - `namespace`, `key`, `value` **letterali CLEAN nel fingerprint**, mai interpolati da contenuto del trigger (SMS/notifiche);
  - `key` validata con regex tipo `QUERY_NAME` (`StateQuery.kt:30`) **più** allowlist chiusa; `value` bounded, rifiuto NUL/newline/control char (D3 §5.2, `decision-record:150`) — non c'è shell injection (argv separati) ma il rifiuto dei control char resta;
  - review pre-arm: l'utente vede key+value letterali (mitigazione "prudenza", non divieto morale).
- Validazione valori per le tipizzate: `level in 0..255`, `seconds in 1..86400`, enum chiusi per mode/stream/screen — nei `require(...)` di `DeviceTools`/executor e nel ramo `DraftValidator.kt:261-323`.
- Nessun guardrail aggiuntivo oltre a questi: niente denylist morali su cosa automatizzare, coerente con la direttiva (memoria `argus_design_philosophy`).

---

## 4) COORDINAMENTO HERMES — cosa cambia esattamente

Due copie dello schema da tenere allineate + un validatore:

1. **`ops/hermes/bridge.py` — `DRAFT_SCHEMA_TEXT`** (`bridge.py:127+`, sezione Action ~171-181): aggiungere una riga per tipo, es.
   - `- {"type":"set_alarm", "hour":integer 0-23, "minute":integer 0-59, "label":string|null, "skipUi":boolean}`
   - `- {"type":"set_timer", "seconds":integer 1-86400, "label":string|null}`
   - `- {"type":"set_brightness", "level":integer 0-255}`
   - `- {"type":"set_dark_mode", "mode":"DARK"|"LIGHT"|"AUTO"}`
   - `- {"type":"write_setting", "namespace":"system"|"secure"|"global", "key":string, "value":string}` con nota stile `run_shell` (reference:136-138): valori letterali, mai interpolazione dal trigger.
2. **`bridge.py` — validatore server-side** (`bridge.py:1154-1263`): `kind not in available_tools → False` (:1161) funziona da solo (gate sul manifest), ma **ogni nuovo tipo DEVE avere una entry nella tabella `fields`** (:1163-1181, required/optional) o viene rifiutato (:1182) + validazione tipata per-campo (:1184-1263): range hour/minute/level/seconds, enum mode/namespace, regex+allowlist per `write_setting.key`.
3. **Regole vincolanti del prompt** (`build_prompt`, `bridge.py:594+`; regola 1 a :613 "usa solo tipi in available_tools"): aggiungere una regola numerata per `write_setting` (come reg. 11 per run_shell): "key/value letterali, solo chiavi dell'allowlist, mai contenuto del trigger". Nessuna regola serve per set_alarm/set_timer.
4. **Reference client-side** `docs/superpowers/specs/2026-07-16-hermes-compile-prompt-reference.md:125-146` (oggi termina a `input_text`:134): copia testuale identica delle stesse righe — il doc esiste per evitare drift (righe 175-181, "non divergere") — e aggiornare la golden suite del compile diretto.
5. **Chiusura del loop col manifest**: i nuovi type compaiono in `manifest.available_tools` solo dopo l'estensione di `AndroidCapabilityProbe.availableTools` (`AndroidCapabilityProbe.kt:263-282`) — quindi il modello non genererà `set_alarm` finché il device non lo pubblica: rollout sicuro per costruzione.

---

## 5) PIANO a sotto-slice TDD (ordine e dipendenze)

Ogni slice segue lo stesso rituale: test host-JVM su executor/validator/policy → implementazione → estensione probe → allineamento bridge.py + reference + golden compile → verifica su device (OnePlus 15, gate `decision-record §11.4`).

**S1 — `set_alarm` + `set_timer` (BASE, Intent). PRIMO SLICE, valore massimo/costo minimo.**
- Nessuno Shizuku, nessun grant runtime, solo manifest `SET_ALARM`. Tocca: Action.kt, ActionPrivilege (BASE), CapabilityRequirements, `BaseActionSurface`+`AndroidBaseActionExecutor` (host-test: mapping extra, validazione range, fallimento tipizzato `alarm_app_unresolved`)+`AndroidBaseActionSurface`, dispatch executor col pattern `baseActions?.setAlarm() ?: fallback tools` (fallback Shizuku `am start` pronto per il caveat BAL), probe bucket BASE, DraftValidator, bridge+reference.
- Chiude il gap concreto "il modello ha impostato solo una notifica". Nessuna dipendenza.

**S2 — `set_brightness` + `set_dark_mode` (PRIVILEGED, pattern `DeviceTools`).**
- Copia strutturale di `setDnd`/`setRinger`: due nuovi metodi `DeviceController` + argv `runChecked`. Test host su costruzione argv + require range; device-check su OxygenOS (auto-brightness override, uimode propagation). Dipende solo dal rituale rodato in S1 (non tecnicamente da S1).

**S3 — `write_setting` parametrica + `WriteSettingPolicy` (PRIVILEGED).**
- Il grosso del lavoro è la policy pura (gemella di `StateQueryPolicy`, host-testabile al 100%): allowlist per namespace, regex key, bound value, `canonicalId` fingerprint in `forAction`. Poi un solo metodo `DeviceTools`. Dipende concettualmente da S2 (le chiavi provate in S2 seminano l'allowlist iniziale: brightness/timeout/rotation/font_scale).
- Include la regola numerata nel prompt e la nota run_shell-style nello schema.

**S4 — BASE Manager pack: `set_volume`, `set_flashlight`, `open_settings_screen` (+`vibrate`).**
- Estende `BaseActionSurface` come S1; `set_volume` riusa la gate DND esistente. Indipendente da S2/S3.

**S5 — PRIVILEGED toggle pack: `media_control`, `set_mobile_data`, `dismiss/snooze_alarm`, poi nfc/location/airplane/force-stop/statusbar/screenshot-as-action.**
- Meccanico dopo S2 (stesso pattern `svc`/`cmd`); screenshot riusa `DeviceTools.capture()` (`DeviceTools.kt:159`).

**S6 — consolidamento Hermes**: golden suite compile aggiornata con casi che generano i nuovi tipi (sveglia da NL, "abbassa la luminosità alle 22", "dark mode al tramonto"), verifica reconciler needsReview su automazioni esistenti.

Dipendenze: S1 → (S2 → S3), S4 in parallelo a S2/S3, S5 dopo S2, S6 incrementale a ogni slice (bridge+reference si aggiornano dentro ogni slice, S6 è la verifica d'insieme).
