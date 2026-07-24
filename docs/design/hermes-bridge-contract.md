# Contratto Argus ↔ Hermes bridge

Stato reale da `v0.3.1`: `/compile` v2 corrente (`AutomationDraft` piatto v1 o programma P4 v2);
`/compile` v1 è mantenuto per rollout. Server Hermes e client Android implementano `/act` v1/v2/v3:
la v3 è il canale P4 risolto con dati runtime separati, validazione strict e negoziazione health.

> **Confine di versione P3:** `schema_version` in questo documento versiona esclusivamente il
> protocollo bridge. Non è la versione dello schema Room/automazioni e non è la versione del
> materiale canonico usato per il fingerprint di approvazione. Le tre evolvono separatamente
> secondo `argus-schema-versioning-adr.md`. `/act` v2 è additivo e non modifica né migra
> `Action.InvokeLlm`/`/act` v1. I futuri turni agentici richiederanno un altro contratto
> esplicitamente versionato, mai un fallback implicito a `/chat`.

## Endpoint e confine di sicurezza

- Base URL: `https://your-hermes-host.ts.net` (raggiungibile solo dal tailnet).
- `argus-bridge` ascolta esclusivamente su `127.0.0.1:8092`; Tailscale Serve termina TLS su `443`.
- Ogni endpoint, incluso `/health`, richiede `Authorization: Bearer <token>`.
- Il token viene fornito a `CliBridgeTransport` a runtime tramite `BridgeAuthProvider`: non va in Git,
  in `BuildConfig`, nelle risorse o nell'APK.
- Redirect HTTP/HTTPS disabilitati nel client, per non inoltrare accidentalmente l'header di auth.
- Il vecchio `/chat` della Guida Bali non è un fallback Argus: non è versionato e non produce un
  envelope abbastanza stretto per dati eseguibili.

Sorgenti operative versionate:

- server: `ops/hermes/bridge.py`;
- unit systemd: `ops/hermes/argus-bridge.service`;
- client: `brain-android/.../CliBridgeTransport.kt`.

Il confronto repo/host va fatto sul contenuto normalizzato LF oppure sul file Python compilato e
testato: un SHA-256 dei byte grezzi può segnalare falso drift quando il checkout Windows usa CRLF.

## Health e negoziazione

`GET /health` è il contratto legacy, conservato durante il rollout server → Android.

Risposta `200 application/json`:

```json
{"schema_version":1,"status":"ok","model":"gpt-5.5"}
```

Il client Android corrente non usa più questo endpoint. Usa `GET /health/v2`:

```json
{
  "schema_version": 2,
  "status": "ok",
  "model": "<ARGUS_MODEL configurato>",
  "compile_schema_versions": [1, 2],
  "act_schema_versions": [1, 2, 3],
  "source_sha256": "<64 caratteri esadecimali lowercase>"
}
```

Compatibilità per CONTENIMENTO (#41): Android accetta il bridge se le liste `compile_schema_versions`
/`act_schema_versions` CONTENGONO le versioni che l'app usa (compile v2, act v1+v2+v3). Un redeploy che
AGGIUNGE una versione (es. annuncia `[1,2,3]`) resta compatibile con le app vecchie; un bridge che
TOGLIE una versione usata dall'app è incompatibile. `health.schema_version` è accettato se >= a quello
atteso dall'app. `source_sha256` è l'hash di `bridge.py` dopo normalizzazione `CRLF|CR → LF`; permette di
confrontare checkout Windows e deploy Linux senza falso drift. Il client rifiuta anche campi
sconosciuti, status/modello non validi, body non JSON o oltre il limite.

La v3 è richiesta dalla health Android: un bridge fermo a `[1,2]` è dichiarato incompatibile prima
dell'esecuzione. L'aggiunta resta backward-compatible per containment: i client vecchi continuano a
usare v1/v2.

## `POST /compile`

Header obbligatori:

```text
Authorization: Bearer <token>
Content-Type: application/json
Accept: application/json
Idempotency-Key: <request_id>
```

Request v2 corrente:

```json
{
  "schema_version": 2,
  "request_id": "uuid-o-id-opaco",
  "message": "Dopo le 23 attiva non disturbare prioritario",
  "manifest": {
    "device_model": "OnePlus CPH2747",
    "android_api": 36,
    "shizuku_available": true,
    "granted_permissions": ["android.permission.INTERNET"],
    "available_tools": ["set_dnd", "set_wifi", "show_notification"],
    "available_triggers": [
      "time", "notification", "geofence", "phone_state.sms", "phone_state.call",
      "connectivity.wifi", "connectivity.wifi.identity",
      "connectivity.bt", "connectivity.power",
      "sensor.significant_motion", "sensor.stationary_detect", "sensor.motion_detect",
      "sensor.step_detector", "sensor.step_counter"
    ],
    "unavailable_tools": {},
    "whitelisted_contacts": [],
    "state_keys": {"dnd": "off|priority|total"},
    "state_readers": {
      "policy_version": 1,
      "families": ["builtin", "setting", "system_property", "sysfs", "dumpsys_field"],
      "limits": {
        "max_query_name_length": 96,
        "max_sysfs_path_length": 256,
        "max_expected_length": 1024,
        "timeout_millis": 10000,
        "max_output_bytes": 65536,
        "max_scalar_chars": 4096
      }
    }
  },
  "state": {
    "values": {"dnd": "off"},
    "foreground_app": null,
    "location_available": false
  }
}
```

`available_tools` contiene discriminatori di azioni realmente compilabili (`set_dnd`,
`show_notification`, …) e i soli tool generativi effettivi (`whatsapp_reply`, `web.search`,
`notify.show`). `state.read` è una capability runtime interna per `contextSources=["state"]` e viene
negoziata nei requisiti/reader, non è un'azione wire. Alias raw senza esecutore (`screen.capture`,
`screen.dump_ui`, `toggle.set`, `app.launch`) sono sempre esclusi dagli available e dichiarati in
`unavailable_tools` con una ragione chiusa. Il probe Android deriva tutto dallo stesso snapshot:
un'azione non disponibile non deve mai apparire compilabile.

In v2 `available_triggers` è obbligatorio, univoco e in ordine canonico. Nel solo v1 legacy può
essere omesso per i client pre-P2. Quando è presente, il prompt e il validator del bridge rifiutano
fail-closed ogni draft il cui trigger non è nella lista. PhoneState è distinto in `.sms`/`.call`; Connectivity in `.wifi`,
`.bt`, `.power`. Un filtro SSID (`Connectivity.match`) richiede inoltre
`connectivity.wifi.identity`, pubblicato solo con location foreground+background. Il controllo
server non sostituisce la rivalidazione delle capability sul telefono.

I sensori usano `sensor.<kind>`. Il dominio corrente rappresenta soltanto `significant_motion`,
`stationary_detect`, `motion_detect`, `step_detector` e `step_counter`: accelerometro/giroscopio raw
e sampling high-rate non sono compilabili. Un kind entra nel manifest soltanto se coincidono
hardware con reporting mode corretto, grant runtime e backend realmente collegato. Il listener
P3-2B è implementato: non va più descritto come futuro, ma il manifest continua a pubblicare solo
i kind realmente armabili sul dispositivo corrente. I draft sensore
richiedono cooldown `60000..604800000` ms; `minimumEventCount` è 1 per i motion e `1..100000` per
gli step. I campi sampling/latency restano null finché una slice batched non li implementa.

`state_readers` è obbligatorio soltanto in v2. Pubblica famiglie chiuse realmente disponibili e
limiti esatti della policy, non un inventario di chiavi arbitrarie. Ordine, unicità, versione e
limiti sono parte del wire contract: un mismatch viene rifiutato prima di chiamare il modello.
Le famiglie sono:

- `builtin(key)` per le chiavi legacy dichiarate anche in `state_keys`;
- `setting(namespace,key)` con namespace `SYSTEM|SECURE|GLOBAL`;
- `system_property(name)`;
- `sysfs(path)`, con path validato e canonicalizzato sotto `/sys/` sul telefono;
- `dumpsys_field(service,field)`, con parsing bounded in-process.

Il bridge non riceve il valore letto. La query esatta viene provata localmente prima dell'arm; il
sample resta fuori da wire, log e fingerprint. La condizione approvata include invece parametri,
tipo, operatore, soglia e `policyVersion`, così un cambio semantico invalida il fingerprint.

`geofence` viene pubblicato soltanto con posizione **precisa** e accesso in background. Nel
contratto corrente accetta esclusivamente `transition=ENTER|EXIT` e `loiteringDelayMs=0`: il backend
framework Android non offre DWELL e il bridge non deve prometterlo.

`phone_state.sms` indica soltanto SMS telephony, non RCS/MMS. `copy_to_clipboard.extractionRegex`
deve usare il sottoinsieme RE2 a tempo lineare, perché viene applicata a testo controllato dal
mittente. Il bridge propone `(?:^|[^+0-9])([0-9]{4,8})(?:[^0-9]|$)` per gli OTP; Android resta
l'autorità finale sul parsing e conserva compatibilità solo col precedente pattern OTP noto.

Redazione device state:

- `values` contiene soltanto chiavi presenti in `manifest.state_keys` e valori primitivi ammessi;
- `foreground_app` deve essere un package name, non testo UI;
- le coordinate non vengono mai inviate: passa solo `location_available`; il placeholder “qui” viene
  risolto sul telefono al momento dell'approvazione.

Risposta v2:

```json
{
  "schema_version": 2,
  "request_id": "uuid-o-id-opaco",
  "reply": "Proposta pronta.",
  "meta": {
    "draft": {
      "name": "Voltaggio basso",
      "trigger": {"type":"time","cron":"0 8 * * *","at":null,"tz":"Europe/Rome","precision":"FLEXIBLE"},
      "actions": [{"type":"show_notification","title":"Argus","text":"Batteria"}],
      "conditions": {
        "type": "state_compare",
        "query": {"type":"dumpsys_field","service":"battery","field":"voltage"},
        "valueType": "NUMBER",
        "op": "LT",
        "expected": "3800",
        "policyVersion": 1
      }
    },
    "error_code": null
  }
}
```

Se serve una chiarificazione, `draft` è `null` e `error_code` è `clarification_required`. Un draft e
un `error_code` non nullo non possono coesistere.

Il client accetta il draft soltanto se:

1. `schema_version == 2`;
2. `request_id` coincide con la richiesta;
3. envelope e draft non hanno campi sconosciuti o tipi coercibili;
4. `reply` è una stringa entro il limite;
5. il draft decodifica esattamente nei sealed type di `engine-core`.

La decodifica non equivale all'approvazione: `DraftValidator`, fingerprint, conferma utente e
revalidazione al fire-time restano obbligatori.

### Compatibilità e rollout `/compile`

- Il server accetta request v1 e v2, rispondendo con la stessa versione ricevuta.
- Il v1 resta strict ma non può rappresentare `StateQuery`/`StateCompare`; non esiste conversione
  implicita a `StateEquals` e non esiste fallback `/chat`.
- Android corrente invia solo v2 e accetta solo response v2.
- Ordine di deploy: prima server compatibile `[1,2]`, poi APK v2. Il rollback dell'APK resta
  compatibile col server; il rollback del server con APK v2 fallisce visibilmente su `/health/v2`
  e `/compile`, senza degradare silenziosamente.
- Il deploy host comprende atomicamente `bridge.py`, `test_bridge.py` e
  `state_query_contract_v2.json`, seguito da suite remota, restart, health e confronto hash LF.

Request e output modello sono JSON strict: chiavi duplicate, `NaN`/`Infinity`, campi sconosciuti,
tipi coercibili o enum non stringa vengono rifiutati fail-closed.

### Regole del prompt di compilazione (server)

Il prompt di sistema in `bridge.py` vincola il modello, tra l'altro, a: usare SOLO i tool di
`available_tools` (regola 1 — un tool assente equivale a non esistente, quindi il probe Android
deve pubblicare `invoke_llm` e `invoke_llm_v2` quando il runtime generativo è pronto); e — **REGOLA 9, aggiunta
il 2026-07-14 dopo la caratterizzazione reale** — per le reply WhatsApp il trigger deve avere
`conversationId` scelto tra i `whitelisted_contacts` e `isGroup=false` ESPLICITO (mai null);
le risposte generate senza stato usano il profilo `invoke_llm` P1 esatto; quelle che richiedono
stato usano `invoke_llm_v2` con query, tipo, policy e classificazioni esplicite. `whatsapp_reply`
statica è riservata a testi dettati letteralmente dall'utente. Il draft resta comunque soggetto al
`DraftValidator` locale: la regola serve a produrre draft armabili al primo colpo, non a
sostituire i controlli.

Lo schema P4 aggiunge variabili tipate, capture, `if`, `while` bounded e `wait`. La postura
`TaintPolicy` corrente è **Aggressive**: il runtime Android consente una variabile TAINTED anche in
campi di autorità fingerprintati. La struttura del programma resta immutabile e la shell conserva
il trigger-gating, ma questa scelta non elimina la command injection dentro un template approvato.

Le sezioni legacy e P4 su `run_shell`, `write_setting` e state reader seguono ora la stessa semantica:
i programmi flat v1 restano letterali, mentre P4 può interpolare `${var}` secondo la `TaintPolicy`
approvata. `AgentMessageSupport` e `bridge.py` restano due implementazioni, ma una fixture semantica
condivisa fallisce se tornano a divergere sui vincoli critici.

In `/compile` v2 la regola 13 consente `state_compare` soltanto per famiglie pubblicate in
`manifest.state_readers`, impone `policyVersion=1` e chiede chiarimento se mancano soglia o unità.
Per il voltaggio del device corrente il profilo noto è `dumpsys_field(battery, voltage)` in
millivolt; il modello non può ripiegare su una `state_equals` lessicografica.

## `POST /act`

`/act` è il confine one-shot per generare il solo testo di una reply. Usa gli stessi header,
bearer, limiti e regole di idempotenza di `/compile`. Il `request_id` Android è
deterministico: `act-` seguito dal digest SHA-256 di `executionId + NUL + actionIndex`.

Request v1:

```json
{
  "schema_version": 1,
  "request_id": "act-<sha256>",
  "goal": "Rispondi in modo cordiale e conciso",
  "context_sources": ["notification", "state"],
  "allowed_tools": ["whatsapp_reply"],
  "context": {
    "notification": {
      "package": "com.whatsapp",
      "sender": "Moglie",
      "title": "Moglie",
      "text": "Arrivo tra dieci minuti",
      "is_group": false
    },
    "state": {
      "values": {"ringer": "normal"},
      "foreground_app": "com.whatsapp"
    }
  }
}
```

Il v1 resta operativo byte-for-byte per le regole `Action.InvokeLlm` già approvate. Il suo profilo
`state` legacy può inviare le chiavi builtin registrate (inclusa `screen`) e foreground app; non
viene esteso con reader parametrici impliciti.

Request v2 (`Action.InvokeLlmV2`):

```json
{
  "schema_version": 2,
  "request_id": "act-<sha256>",
  "goal": "Rispondi tenendo conto del voltaggio",
  "allowed_tools": ["whatsapp_reply"],
  "context": {
    "notification": {
      "package": "com.whatsapp",
      "sender": "Moglie",
      "title": "Moglie",
      "text": "Arrivo tra dieci minuti",
      "is_group": false
    },
    "state": [{
      "query_id": "state.reader.dumpsys_field.v1.<sha256>",
      "query": {"type": "dumpsys_field", "service": "battery", "field": "voltage"},
      "value_type": "NUMBER",
      "policy_version": 1,
      "integrity": "CLEAN",
      "confidentiality": "SECRET",
      "value": "4200"
    }]
  }
}
```

Invarianti aggiuntivi v2:

- da 1 a 16 query, senza duplicati; ID canonico, query, tipo e policy devono coincidere;
- il telefono legge al fire-time esclusivamente quelle query e rifiuta valore mancante, troncato,
  con control char o non convertibile: non invia stringhe vuote e non ripiega sullo snapshot v1;
- i reader locali hanno integrità `CLEAN`; la riservatezza minima è `PRIVATE` per builtin e
  `SECRET` per setting, system property, sysfs e dumpsys; il compilatore può alzarla, mai abbassarla;
- query, classificazioni, obiettivo, tool e timeout sono nel fingerprint. Il valore runtime non
  entra nel fingerprint né nei log;
- la review mostra parametri e classificazioni e il probe pre-arm verifica query e tipo senza
  mostrare il sample;
- una regola v1 passa a v2 soltanto con edit, nuova review e nuova approvazione.

Invarianti comuni e v1:

- `context_sources` accetta soltanto `notification` e `state`; `notification` è obbligatorio;
- `state` deve essere `null` se la regola non lo ha richiesto;
- in P1 l'unico `allowed_tools` accettato è esattamente `whatsapp_reply`;
- package ammessi: WhatsApp e WhatsApp Business; `is_group` deve essere `false` esplicito;
- `conversationId`, `StatusBarNotification.key`, fingerprint, coordinate e handle `RemoteInput`
  non attraversano mai il bridge;
- il server tratta goal e contesto come dati delimitati; il contenuto della notifica non può
  cambiare formato, tool o destinatario.

Risposta (stessa versione della request; esempio v2):

```json
{
  "schema_version": 2,
  "request_id": "act-<sha256>",
  "result": {"text": "Perfetto, a dopo!"},
  "error_code": null
}
```

`result` contiene esclusivamente `text`, massimo 4096 caratteri. Il modello non restituisce e non
può scegliere un target: conversazione, notifica attiva e invio restano vincolati localmente sul
telefono. In caso di rifiuto semantico `result` è `null` e `error_code` è uno snake_case bounded;
esattamente uno dei due deve essere valorizzato. Campi sconosciuti, target aggiunti, body incoerenti,
versione o request ID diversi sono errori di protocollo fail-closed.

### `/act` v3 risolto

Quando un `Action.InvokeLlm` P4 usa valori runtime nel goal, l'interpolatore Android li sostituisce
con marker opachi `{{ARGUS_RUNTIME_DATA_n}}`; i valori raw viaggiano esclusivamente nel campo
`runtime_data`. `deliver=CAPTURE_ONLY` salva il testo nella variabile senza reply/notifica; i sink
`WHATSAPP_REPLY` e `LOCAL_NOTIFICATION` possono invece consegnarlo in modo sincrono prima che il
programma continui.

Esempio minimo (la fixture completa condivisa è
`brain-android/src/test/resources/contracts/hermes-act-v3-request.json`):

```json
{
  "schema_version": 3,
  "request_id": "act-<sha256>",
  "goal": "Usa {{ARGUS_RUNTIME_DATA_1}}",
  "context_sources": [],
  "allowed_tools": [],
  "context": {"notification": null, "state": null},
  "runtime_data": [
    {"token": "ARGUS_RUNTIME_DATA_1", "value": "dato runtime non fidato"}
  ]
}
```

Il server richiede una corrispondenza biunivoca tra marker e record, token canonici e univoci,
nessun campo sconosciuto e limiti bounded su conteggio/lunghezza. Nel prompt del modello il blocco
runtime è JSON serializzato dentro una sezione DATA esplicitamente non fidata: newline e delimitatori
nel valore non possono modificare il framing.

Copertura attuale:

1. golden request serializzata in Kotlin e validata dallo stesso parser Python usato in produzione;
2. test ostili su marker mancanti/duplicati, newline e delimitatori;
3. health Android che richiede v3;
4. suite bridge eseguita anche sul deploy Hermes;
5. E2E device reale `trigger → interpolation → /act v3 → capture → branch → action`.

## Idempotenza e limiti

- Il server lega endpoint, versione schema, `request_id`, `Idempotency-Key` e SHA-256 dei byte del
  request. Endpoint e versioni hanno namespace separati, quindi lo stesso ID non collide tra
  operazioni o profili wire.
- La stessa richiesta entro 15 minuti restituisce la risposta cached senza richiamare il modello;
  due duplicati concorrenti attendono lo stesso risultato e producono una sola chiamata Hermes.
- Riutilizzare lo stesso ID con un body diverso restituisce `409 idempotency_conflict`.
- Cache massima: 128 richieste; un solo processo modello concorrente condiviso tra compile e act;
  richieste con ID diverso durante una chiamata ricevono `429`, senza aprire un secondo processo.
- Request massimo 256 KiB; response massimo 512 KiB. Il timeout modello del server è configurabile
  con `ARGUS_MODEL_TIMEOUT_SECONDS`; il client HTTP deve restare più lungo del timeout azione
  approvato, senza promettere un valore fisso diverso dalla configurazione deployata.
- I log contengono solo prefisso del request ID, status e durata: mai messaggio, stato, contatti o token.

## Errori HTTP

| Status | Significato |
| --- | --- |
| `400` | JSON o contratto request non valido |
| `401` | token assente/errato |
| `409` | schema incompatibile o stesso request ID con contenuto diverso |
| `413` / `415` | body troppo grande / content type errato |
| `429` | slot modello già occupato da un'altra compile/act |
| `503` | cache idempotenza temporaneamente piena oppure quota provider esaurita |
| `502` / `504` | modello fallito / timeout |

Il client non legge né propaga i body degli errori HTTP. `HermesBrain` espone solo codici stabili come
`bridge_auth`, `bridge_timeout`, `bridge_network`, `bridge_http` e `bridge_protocol`.
Un output CLI senza `@@META@@` o con metadati illeggibili è un errore upstream `502`, non una
risposta compile `200`; i marker diagnostici di quota diventano `503` e vengono cacheati in modo
idempotente senza includere stdout/stderr nella risposta.

## Android Local Network Protection

Su Android 16 la protezione LAN è opt-in tramite compat flag. Per app con target SDK 36 o inferiore
l'accesso resta implicitamente incluso in `INTERNET`; da Android 17/target 37 entra il runtime
permission `ACCESS_LOCAL_NETWORK`.

La definizione ufficiale LNP esclude però le connessioni instradate su una VPN: perciò il bridge
HTTPS su Tailscale deve restare raggiungibile anche col compat flag attivo. Il denial path va provato
separatamente verso un endpoint LAN diretto raggiungibile via Wi-Fi, non contro Hermes/Tailscale.
Sul device Tailscale può diventare la route predefinita anche per gli indirizzi RFC1918: il probe
deve quindi creare il socket dalla `SocketFactory` della `Network` Wi-Fi; un `Socket()` ordinario
passerebbe da `tun0`, sarebbe correttamente escluso da LNP e produrrebbe un falso negativo.
La VPN Android di Tailscale sul device di test è inoltre `bypassable=false`: finché è attiva il
kernel nega con `EPERM` il binding alla rete Wi-Fi sottostante, prima ancora che intervenga LNP.
La verifica è quindi intenzionalmente divisa in fasi: si mantiene ADB sull'indirizzo Wi-Fi, si
sospende Tailscale per misurare il probe LAN diretto, poi si riattiva Tailscale e si esegue
separatamente il test health autenticato del bridge.
Il test compat usa:

```text
adb shell am compat enable RESTRICT_LOCAL_NETWORK <package>
```

Dopo il reboot, con Tailscale sospeso il probe LAN diretto deve fallire senza crash; riattivata la
VPN, il test health Tailscale deve restare verde. Poi il flag viene disabilitato, il device riavviato
e la baseline LAN ripristinata. Fonti:
[Android Local Network Permission](https://developer.android.com/privacy-and-security/local-network-permission) e
[Local Network Definition](https://developer.android.com/privacy-and-security/local-network-definition).

Non è presente alcun `usesCleartextTraffic=true` o network security opt-in HTTP: TLS è gestito da
Tailscale Serve. Riferimento: [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config).
