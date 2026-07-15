# Argus — handoff operativo Claude → Codex (2026-07-15)

**Direzione**: inversa rispetto a `2026-07-14-argus-codex-to-claude-handoff.md` (d'ora in poi "il
ledger"). Copre **tutto l'operato di Claude dall'handoff ricevuto da Codex fino a ora**.

**Motivo dello stop**: saturazione imminente del weekly limit del piano Claude. Nessun blocco
tecnico. Il lavoro si ferma a metà di P2, in stato pulito e con gate verde.

**Come leggere questo documento**: il §1 è la consegna in dieci righe. Il §2 è l'unica sezione con
comandi e output *eseguiti oggi* (usala per verificare senza fidarti). Il §11 elenca esplicitamente
**ciò che NON è provato** — è la sezione che conta di più per non ereditare conclusioni sbagliate.

---

## 1. Consegna in dieci righe

| Voce | Valore |
|------|--------|
| Branch di lavoro | `feat/argus-p2-background` |
| HEAD | `02592b2 fix(phone): resolve receiver dependencies via explicit Hilt entry points` |
| Working tree | pulito (`git status --short` vuoto) |
| Master | `0af4ac3` — contiene P0-B + P1 completi (merge `8f283ca`) e il round UX post-P1 (merge `c7db615`) |
| Commit P2 non ancora mergiati | 13 (`0291216` → `02592b2`), tutti pushati sull'hub |
| Gate host su HEAD | **verde, rieseguito oggi**, `EXIT=0` (§2.1) |
| Bridge Hermes | `active`; il file deployato è **identico** al repo (hash in §2.2) |
| Device OnePlus | raggiungibile, APK del fix installata 2026-07-14 23:10, permessi telefonia tutti `granted=true` (§2.3) |
| Lavoro aperto | P2-3 verifica live (#20), run_shell (#24), Connectivity (#21), Geofence (#22), chiusura (#23) |
| Rischio principale | il canale SMS **non ha ancora mai funzionato end-to-end sul device reale** |

---

## 2. Snapshot verificabile (comandi eseguiti oggi, 2026-07-15)

### 2.1 Gate host su HEAD `02592b2`

```
cd /c/argus && ./gradlew.bat :engine-core:test :ui:testDebugUnitTest :data:testDebugUnitTest \
  :brain-android:testDebugUnitTest :automation-android:testDebugUnitTest :app:testDebugUnitTest \
  :automation-android:lintDebug :app:assembleDebug --no-daemon
→ BUILD SUCCESSFUL in 20s — EXIT=0
```

Nota Windows non negoziabile: **mai** mettere `gradlew` in una pipe con `Select-Object` (stacca la
pipe e il build prosegue orfano). Redirect su file + `$LASTEXITCODE`.

### 2.2 Hermes

```
ssh hermes "systemctl --user is-active argus-bridge"   → active
sha256 ~/argus-bridge/bridge.py       = 1d5e03e12ef0aec583209c3a11b5f0c4d9054c5504ca0e4778dfe49bfa75712d
sha256 repo ops/hermes/bridge.py      = 1d5e03e12ef0aec583209c3a11b5f0c4d9054c5504ca0e4778dfe49bfa75712d  ✓ identici
sha256 ~/argus-bridge/test_bridge.py  = de175cd3c1a0d1b6f3901eef3ef7f25c3301c8bd3aec611139ff5775b5d05000
sha256 repo ops/hermes/test_bridge.py = de175cd3c1a0d1b6f3901eef3ef7f25c3301c8bd3aec611139ff5775b5d05000  ✓ identici
```

Il bridge è stato deployato **3 volte** in questa sessione, ogni volta con backup datato lato host:
`bridge.py.pre-triggers-rule-20260714`, `bridge.py.pre-sms-textmatch-20260714`,
`bridge.py.pre-clipboard-20260714`. Suite bridge: **17/17** verde prima dell'ultimo restart.

Procedura di deploy (invariata, da rispettare): backup datato → `scp` → `python3 -m unittest
test_bridge` sull'host → restart unit → `is-active`.

### 2.3 Device (OnePlus 15, `100.74.117.9:5555`)

```
adb connect 100.74.117.9:5555      → connected
dumpsys package dev.argus          → versionName=0.1.0  lastUpdateTime=2026-07-14 23:10:32
RECEIVE_SMS        granted=true
READ_PHONE_STATE   granted=true
READ_CALL_LOG      granted=true
ACCESS_FINE_LOCATION granted=true
logcat -s ArgusPhone               → VUOTO (nessun SMS ancora ricevuto dopo il fix)
```

`lastUpdateTime=23:10` è il build che contiene il fix receiver `02592b2`. Il buffer logcat è stato
pulito apposta (`logcat -c`) per il retest: **il tag da guardare è `ArgusPhone`**.

Attenzione: il daemon ADB era caduto e la connessione wireless va rifatta a ogni ripresa
(`adb connect 100.74.117.9:5555`). Un comando ADB alla volta; **mai** concatenare install dopo build
con `;` senza controllare l'exit code — è già successo di installare l'APK vecchio dopo una build
fallita, con mezz'ora di diagnosi buttata.

---

## 3. Perimetro: cosa è successo dall'handoff Codex→Claude

L'handoff di Codex si fermava con P1-5 da fare. Da lì:

| Fase | Esito | Dove è documentata |
|------|-------|--------------------|
| P1-5 capability/arm/bootstrap | completata | ledger §18 |
| P1-6 settings, whitelist, deferred durabile | completata | ledger §19 |
| Gate reboot P0-B | **chiuso** con Lorenzo presente | ledger §20 |
| P1-7 E2E reale | completata, 4 bug reali trovati e fixati | ledger §21 |
| P1-8 chiusura + **merge su master** | completata, merge `8f283ca` | ledger §22 |
| Round UX post-P1 | mergiato, `c7db615` | ledger §19-bis / commit |
| Osservazioni di campo + backlog P2 | registrate | ledger §23 ⚠️ **contiene una conclusione errata, vedi §5.2** |
| **P2 (in corso)** | ~metà | **questo documento, §4-§6** — il ledger NON è ancora aggiornato con P2 |

Il punto importante: **il ledger si ferma a §23**. Tutta P2 vive solo nei commit, nel piano
`docs/superpowers/plans/2026-07-14-argus-p2-background-triggers.md` e in questo documento.
Aggiornare il ledger con P2 è parte del task #23 (chiusura), non è stato fatto.

---

## 4. P2: cosa è stato costruito, commit per commit, con l'intento

Branch `feat/argus-p2-background`, 13 commit, tutti pushati. Ordine cronologico:

| Commit | Cosa | Intento non deducibile dal diff |
|--------|------|--------------------------------|
| `0291216` | piano P2 | Sezioni D1-D5. **D5 = "cosa NON cambia"**: reply/observed/picker restano solo WhatsApp, mai shell parametrizzata da contenuti esterni, niente denylist, DWELL onesto. |
| `e7e1dc5` | esito spike + riordino | Lo spike P2-0 (clipboard da background su Android 16) è **passato**: Lorenzo ha incollato "SPIKE-123456" da un'app terza. Questo ha cambiato il design dell'OTP: copia diretta, **zero tap**, niente notifica-ponte. Il piano è stato riordinato per valore: SMS → OTP → Connectivity → Geofence → chiusura. |
| `306f8fe` | `PhoneEventParser` (engine-core) | JVM puro, testabile senza device. `eventId = "phone:" + digest(event, number, text, atMillis)`: il digest serve a deduplicare **senza** mai mettere numero o testo in chiaro nell'id. |
| `b9bfd3f` | canale telefonia (receiver + ingress) | `PhoneEventIngress` fa il lavoro vero (ricomposizione multipart per mittente, transizioni di chiamata con stato persistito, duplicati no-op) ed è testato in JVM. I receiver sono gusci minimi sul framework. |
| `e3c5f49` | probe + registrar | Capability **granulari**: `trigger.phone_state.sms` (RECEIVE_SMS) e `trigger.phone_state.call` (READ_PHONE_STATE). Deliberatamente separate: Lorenzo può concedere solo SMS senza dare accesso alle chiamate. |
| `ce30650` | grant one-tap in Settings | Righe "Trigger SMS"/"Trigger chiamate" NEUTRAL con azione esplicita. |
| `f9d9bd3` | `available_triggers` sul filo + REGOLA 10 | Hermes inventava trigger inesistenti. Ora il manifest porta la lista dei trigger reali e il prompt ha la REGOLA 10: trigger fuori lista → `unsupported_capability` **citando la riga Sistema** che lo spiega. |
| `5de9853` | fix UX: bottone "Attiva" | Bug trovato live da Lorenzo. |
| `22cdfb9` | fix UX: riga intera cliccabile in qualsiasi stato | Secondo giro dello stesso bug (vedi §5.3). |
| `b673375` | `textMatch` sui trigger SMS | **Gap reale del modello** trovato da Hermes stesso, non da noi (§5.4). |
| `4f2e3b2` | notifiche solo-titolo armabili | Bug del validator trovato live: arm bloccato (§5.5). |
| `9c3d2f1` | `copy_to_clipboard` con estrazione regex | L'OTP end-to-end. Rimosso anche lo spike instrumented test, che aveva esaurito il suo scopo. |
| `02592b2` | **fix receiver via entry point Hilt espliciti** | Il bug che ha impedito al canale SMS di funzionare (§5.1). Ultimo commit, **non ancora verificato live**. |

### 4.1 Il canale telefonia in una figura

```
receiver manifest (SMS_RECEIVED, guard android:permission="android.permission.BROADCAST_SMS")
   │  PHONE_STATE
   ▼
PhoneEventIngress   (multipart ricomposti per mittente; transizioni chiamata via prefs;
   │                 duplicati no-op — i broadcast PHONE_STATE arrivano doppi)
   ▼
PhoneEventParser    (engine-core, JVM puro — eventId digest, testo/numero mai in chiaro)
   ▼
Engine → TriggerMatcher → FirePolicy → ShizukuActionExecutor
```

**`smsText` è VOLATILE**: vive in RAM dentro `TriggerEvent.PhoneStateChanged`, non è mai persistito,
loggato, né incluso in chiaro nell'event id. Il doc-comment sul campo lo dice esplicitamente perché
è il tipo di invariante che si perde al primo refactor distratto.

### 4.2 OTP autocopy — il design che Lorenzo ha chiesto

Richiesta testuale di Lorenzo: *"argus tiene sotto controllo la ricezione sms e se vede un codice tra
le 4 e le 8 cifre che non ha prefisso telefonico di nessun tipo lo copia direttamente negli
appunti"*.

- `Action.CopyToClipboard(extractionRegex: String?)` — **deterministica**, non generativa.
- Payload dal trigger: `smsText` (solo SMS_RECEIVED) o testo notifica. Nessun'altra fonte.
- Regex: primo capture group, altrimenti match intero. Bound ≤512 char, deve compilare, ed è
  **visibile integrale in review** — Lorenzo approva la regex letterale, non una descrizione.
- `EXTRA_IS_SENSITIVE` su `ClipData.description.extras` → Android non mostra l'anteprima del codice.
- Fallimenti **onesti**: `otp_not_found`, `clipboard_source_missing`, e in entrambi i casi la
  **clipboard resta intatta** (testato in Robolectric: `ClipboardCopierTest`).
- Pattern OTP suggerito lato bridge: `(?<!\+)\b(\d{4,8})\b` — il lookbehind esclude i prefissi.
- Validator: `clipboard_source_missing` se il trigger non è Notification né PhoneState(SMS_RECEIVED)
  — cioè un `copy_to_clipboard` senza una fonte di testo **non è compilabile**, non fallisce a runtime.

### 4.3 Bridge Hermes (`ops/hermes/bridge.py`)

- `validate_manifest`: `available_triggers` opzionale e bounded (lista ≤32, stringhe ≤64); chiavi
  sconosciute restano **vietate**.
- REGOLA 10 nel prompt: usa solo i trigger della lista; se manca, `unsupported_capability` con
  indicazione della riga Sistema.
- Schema: `phone_state` + `textMatch` (solo SMS_RECEIVED), `copy_to_clipboard` + `extractionRegex`.
- Specs: `"phone_state": ({"type","event"}, {"number","textMatch"})`,
  `"copy_to_clipboard": ({"type"}, {"extractionRegex"})`.
- **Trappola per chi tocca i test**: il secondo parametro di `validate_action` è `available_tools`,
  **non** una whitelist. Ci ho perso tempo.

---

## 5. Le cose che dai diff NON si vedono

Questa è la sezione per cui vale la pena leggere un handoff invece dei commit.

### 5.1 La trappola Hilt sui BroadcastReceiver (il bug di HEAD)

`@AndroidEntryPoint` + `@Inject lateinit` su un `BroadcastReceiver` **richiede `super.onReceive()`**:
è lì dentro che la classe generata esegue l'injection. Ma `BroadcastReceiver.onReceive` è
**abstract**, quindi `super.onReceive()` **non compila**. Risultato del primo tentativo P2-2: i
receiver nascevano, il `lateinit` non era mai inizializzato, e il canale SMS moriva in silenzio.

Pattern corretto, ora in `PhoneBroadcastReceivers.kt`:

```kotlin
@EntryPoint @InstallIn(SingletonComponent::class)
interface PhoneIngressEntryPoint {
    fun phoneEventIngress(): PhoneEventIngress
    @ApplicationScope fun applicationScope(): CoroutineScope
}
private fun entryPoint(context: Context) =
    EntryPointAccessors.fromApplication(context.applicationContext, PhoneIngressEntryPoint::class.java)
```

È sicuro perché `Application.onCreate` precede sempre ogni delivery di un receiver manifest: il grafo
Singleton esiste già. **Non reintrodurre `@AndroidEntryPoint` su questi receiver.**

### 5.2 ⚠️ Il ledger §23 contiene una conclusione ERRATA — da correggere

Il §23 attribuisce il rifiuto shell delle 18:29 a una **race del binder Shizuku**. **È sbagliato.**
Verificato nel codice (oggi, di nuovo):

- `AndroidCapabilityProbe`: `ActionTypeIds.RUN_SHELL` **non compare mai** in `availableTools`.
- `PHASE_UNAVAILABLE_TOOLS` dichiara `"shell.run" → "conferma live non implementata"`.
- `ShizukuActionExecutor:64`: `is Action.RunShell -> ActionResult.Failure("live_confirmation_required")`.

Quindi il rifiuto era **strutturale, per design**, non una race: run_shell non è mai stato
compilabile né eseguibile. Lorenzo ha corretto la diagnosi in tempo reale. La correzione del §23 fa
parte del task #24 e **non è stata ancora scritta nel ledger**. Se stai per indagare una race
Shizuku: non esiste, non perderci tempo.

### 5.3 I bottoni "non cliccabili" — perché è successo due volte

`onFix` era collegato **solo** al bottone "Correggi", che appare solo in stato WARN. Le righe NEUTRAL
erano decorative. Primo fix: `actionLabel` esplicito ("Attiva"). Lorenzo ha subito ribattuto che
*anche* le righe verdi/grigie non erano cliccabili ("posizione è grigio e nn lo posso cmq cloccare").
Fix definitivo: **l'intera `Row` è `.clickable` in qualsiasi stato**. Lezione: in quella schermata
ogni riga è un affordance, non un badge.

### 5.4 Il gap `textMatch` l'ha trovato Hermes, non noi

Lorenzo ha chiesto una regola con filtro sul testo dell'SMS e Hermes ha risposto onestamente: *"il
trigger SMS disponibile non espone un filtro sul contenuto"*. Era vero: il modello non aveva il
campo. Aggiunto in TDD su modello + matcher + validator + render + bridge (`b673375`).

`Trigger.PhoneState.textMatch`: contains case-insensitive, **solo** su SMS_RECEIVED, **fail-closed**
senza testo. Errore `sms_text_match_invalid` se lo si mette su un evento chiamata.

### 5.5 `encodeDefaults=true` e i fingerprint — controllo obbligatorio

`ArgusJson` ha `encodeDefaults=true`. Aggiungere un campo con default a un tipo serializzato
**cambia i fingerprint delle regole esistenti** che usano quel tipo. Prima di aggiungere `textMatch`
ho verificato che **nessuna regola phone_state esistesse** sul device → sicuro. Questo controllo va
rifatto ogni volta che si tocca un tipo serializzato. Non è teorico: romperebbe le regole armate.

### 5.6 Direttive esplicite di Lorenzo in questa sessione

Queste sono decisioni del proprietario, non opinioni tecniche:

1. **Trigger sensori: scartati per sempre.** *"quella dei sensori è una cagata"*. Il §23 li segnava
   "P3+ eventuale" — sono chiusi. Non riproporli.
2. **Shell ultra powerful e unlimited** (verbatim): *"ok escludere possibilità injection di comandi
   adb da whatsapp o sms, ma se io chiedo all'agente di fare comandi adb avanzati con trigger che nn
   vengono da fuori, deve eseguirli, accesso shell o rish via shizuku deve essere ultra powerfull e
   Unlimited, è il bello! xké magari qualche automazione non è ancora prevista ma l'agente può usare
   la shell per crearla!"* → task #24. La linea è netta: **il trigger non deve venire da fuori**, il
   comando è **statico e approvato letteralmente in review**. Il tool raw `shell.run` per
   `invoke_llm` **resta vietato**.
3. **OTP autocopy**: implementato (§4.2). Non era nel piano originale, l'ha chiesto lui.

---

## 6. Task aperti (lo stato della todo list, con il perché)

### #20 — P2-3 OTP end-to-end · `in_progress` · **è il primo da chiudere**

Implementazione **completa e installata**. Manca **solo la verifica live**, che era in corso quando
la sessione si è fermata: Lorenzo stava per mandarsi un SMS `"prova argus 345798"` dal secondo
numero. Cosa deve succedere:

- `logcat -s ArgusPhone` → `sms ricevuto: parts=1`
- regola "Notifica SMS prova Argus" (già armata sul device) → notifica **"SMS ricevuto!"** + `FIRED` nel Log
- se anche la regola OTP è armata → **`345798` negli appunti**, incollabile

Storia da non dimenticare: al primo tentativo **nessuna delle due regole è partita** → è stato quello
a far trovare il bug §5.1. Il fix è installato ma **mai visto funzionare**.

### #24 — P2-3b sbloccare `run_shell` nelle automazioni armate

Direttiva di Lorenzo (§5.6.2). Lavoro previsto:

- executor: eseguire via `PrivilegedShell` invece del `Failure("live_confirmation_required")`;
- probe: pubblicare `run_shell` in `availableTools` **quando Shizuku è disponibile**;
- gate: approvazione del **comando letterale** in review (è già mostrato integrale nel draft);
- `shell.run` raw per `invoke_llm`: **resta** in `PHASE_UNAVAILABLE_TOOLS`, non toccarlo;
- correggere la nota del ledger §23 (§5.2).

### #21 — P2-1 trigger Connectivity

**Correzione Codex 2026-07-15:** BT ACL è manifest-exempt, POWER no. POWER usa quindi un receiver
dinamico dentro lo stesso **FGS sentinella** (`specialUse`) del Wi-Fi, acceso **solo** quando una
regola ARMED+enabled Wi-Fi/POWER lo richiede. L'assunto originale BT/Power entrambi exempt non va
ereditato.

### #22 — P2-4 Geofence

Spike `addProximityAlert` → registrar per-rule con unregister e boot recovery → wizard background
location → Esempio 1 della home live.

**Aggiornamento Codex 2026-07-15:** backend framework, registrar, cleanup, recovery boot/process,
dedup persistente e retry crash-safe implementati. Gate host verde e gate OnePlus `OK (1)` sulla
pipeline reale fino a Shizuku; nessuna registrazione diagnostica residua. Il test forza l'ingresso
dopo una vera registrazione OS, quindi NON prova ancora un callback prodotto da uno spostamento
fisico né la latenza cached/Doze. Restano questi ultimi gate e l'Esempio 1 multi-azione sul campo.

### #23 — P2-5 hardening e chiusura

Wizard OEM, decisione esplicita sull'export/import JSON nice-to-have, full gate no-cache,
**aggiornamento del ledger con tutta P2** (§3), CLAUDE/contract/audit e merge su master. Il retry
binder Shizuku non è un task: la vecchia diagnosi di race era errata e `run_shell` è già passato
live con il modello fail-closed concordato.

### Residui osservazionali da P1

Misura Doze/schermo spento e conferma live anti-eco: si verificano nell'uso quotidiano, non serve una
sessione dedicata.

---

## 7. Vincoli di sicurezza non negoziabili

Il ledger §13 resta valido **integralmente**. Ribaditi perché P2 li tocca da vicino:

- **Mai** bearer/token in `am instrument -e`, command line ADB, logcat, chat, output CI o commit.
- **Mai** leggere o stampare l'environment file di Hermes.
- Nessun fallback automatico a provider a pagamento.
- Nessun reboot del device senza Lorenzo presente.
- Nessun invio a gruppi o identità ambigue.
- **Mai** `git add .` — add espliciti, sempre.
- TDD obbligatorio; un commit atomico per slice; push su hub dopo gate verde.
- **Contenuti SMS e notifiche MAI in log, DB o audit** — solo conteggi e booleani. I log `ArgusPhone`
  rispettano questo: `"sms ricevuto: parts=N"`, `"call state: X number=bool"`.
- ⚠️ **`ArgusNavigationInstrumentedTest` MAI sul device configurato di Lorenzo**: il tearDown resetta
  privacy e onboarding.

---

## 8. Mappa dei file toccati da P2

`git diff --stat master..HEAD` → 43 file, +1263/−19. I punti d'ingresso:

| Area | File chiave |
|------|-------------|
| Modello/engine | `engine-core/.../phone/PhoneEventParser.kt`, `model/Trigger.kt` (PhoneState.textMatch), `model/Action.kt` (CopyToClipboard), `model/CapabilityRequirements.kt`, `runtime/TriggerMatcher.kt`, `safety/DraftValidator.kt` |
| Android telefonia | `automation-android/.../phone/` (Ingress, BroadcastReceivers, PrefsCallStateStore), `AndroidManifest.xml` |
| Clipboard | `automation-android/.../ClipboardCopier.kt`, `ShizukuActionExecutor.kt`, `di/ArgusModule.kt` |
| Capability | `automation-android/.../AndroidCapabilityProbe.kt`, `RuntimeAdapters.kt` |
| UI | `ui/.../screens/SettingsScreen.kt`, `presentation/RuleRenderMapper.kt`, `model/UiContracts.kt`, `app/.../nav/ArgusNavHost.kt` |
| Filo | `brain-android/.../CliBridgeTransport.kt` (`available_triggers`) |
| Hermes | `ops/hermes/bridge.py`, `ops/hermes/test_bridge.py` |

Test nuovi: `PhoneEventParserTest`, `PhoneEventIngressTest`, `PhoneManifestHardeningTest`,
`ClipboardCopierTest` (Robolectric). Estesi: `CapabilityRequirementsTest`, `TriggerMatcherTest`,
`DraftValidatorTest`, `RuleRenderMapperTest`, `AndroidCapabilityProbeTest`,
`ArmedAutomationRegistrarTest`, `CliBridgeTransportTest`, `ShizukuActionExecutorTest`,
`GenerativeEndToEnd(+Instrumented)`.

---

## 9. Workaround ambiente (macchina `negozio`, Windows)

- Gate: redirect su file + `$LASTEXITCODE`. **Mai** `gradlew | Select-Object -First N`.
- `am instrument` diretto per gli instrumented test.
- Un comando ADB alla volta; riconnettere `adb connect 100.74.117.9:5555` a ogni ripresa.
- Rebind del listener notifiche dopo ogni reinstall:
  `cmd notification disallow_listener dev.argus/dev.argus.automation.notification.ArgusNotificationListenerService`
  seguito da `allow_listener`.
- Deploy bridge: vedi §2.2.

---

## 10. Prima checklist per Codex (nell'ordine)

1. `git fetch && git log --oneline master..feat/argus-p2-background` — attesi 13 commit, HEAD `02592b2`.
2. Rileggi §5.2 **prima** di guardare il §23 del ledger, così non erediti la diagnosi sbagliata.
3. `adb connect 100.74.117.9:5555 && adb logcat -s ArgusPhone` — **il retest SMS di Lorenzo potrebbe
   essere già avvenuto** mentre l'handoff veniva scritto: il primo posto dove guardare è questo tag.
4. Chiudi #20 sull'esito reale: verde → E2E OTP confermato; rosso → §5.1 è il punto di partenza della
   diagnosi, il canale è nuovo e mai visto funzionare.
5. Poi #24 (direttiva esplicita di Lorenzo, §5.6.2), quindi #21, #22, #23.
6. Non rieseguire il gate se non hai toccato codice: è verde su HEAD da oggi (§2.1).

---

## 11. Cosa NON è provato — leggi questa sezione

Elenco onesto delle conclusioni **non verificate**, per non ereditarle come fatti:

1. **Il canale SMS non ha mai funzionato end-to-end su device reale.** Zero volte. Tutta la catena
   receiver→ingress→parser→engine→azione è provata solo in JVM/Robolectric. Il fix `02592b2` è
   ragionato e compila, ma `logcat -s ArgusPhone` è **vuoto**: nessuna prova che i receiver vengano
   effettivamente invocati sul device.
2. **`copy_to_clipboard` non ha mai copiato un OTP vero.** Provato: lo spike manuale (clipboard da
   background funziona su Android 16 — quello sì, confermato da Lorenzo che ha incollato
   "SPIKE-123456") e i test Robolectric. Mai i due pezzi insieme in produzione.
3. **Il trigger chiamate non è mai stato provato live** — né RINGING, né CALL_ENDED. Solo unit test.
4. **`textMatch` non è mai stato verificato live** su un SMS reale.
5. **La REGOLA 10 e `available_triggers`**: verificati dai test del bridge, mai osservati mentre
   rifiutano un trigger inventato in una conversazione reale con Hermes.
6. La "race Shizuku" del ledger §23 **non è mai stata riprodotta** e §5.2 spiega perché
   l'interpretazione era sbagliata in partenza.

Tutto il resto in questo documento è stato eseguito e ha prodotto l'output riportato.

---

## 12. Riga finale

Nessun debito nascosto, nessun file non committato, nessun deploy divergente dal repo. Il branch è
pulito e il gate è verde. Quello che manca è **la prova sul campo del canale telefonia** — ed è
esattamente il primo passo che ti lascio.

Buon lavoro.
