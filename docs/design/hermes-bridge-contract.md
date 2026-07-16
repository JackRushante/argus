# Contratto Argus ↔ Hermes bridge

Stato: protocollo v1 `/compile` operativo dal 2026-07-13; `/act` aggiunto il 2026-07-14.

> **Confine di versione P3:** `schema_version` in questo documento versiona esclusivamente il
> protocollo bridge. Non è la versione dello schema Room/automazioni e non è la versione del
> materiale canonico usato per il fingerprint di approvazione. Le tre evolvono separatamente
> secondo `argus-schema-versioning-adr.md`. `/compile` e `/act` v1 restano strict; i lettori
> parametrici e i futuri turni agentici richiederanno un contratto esplicitamente nuovo, mai un
> fallback implicito a `/chat`.

## Endpoint e confine di sicurezza

- Base URL: `https://hermes.tail04462d.ts.net` (raggiungibile solo dal tailnet).
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

## `GET /health`

Risposta `200 application/json`:

```json
{"schema_version":1,"status":"ok","model":"gpt-5.5"}
```

Il client rifiuta campi sconosciuti, versione diversa, status diverso da `ok`, body non JSON o body
oltre il limite.

## `POST /compile`

Header obbligatori:

```text
Authorization: Bearer <token>
Content-Type: application/json
Accept: application/json
Idempotency-Key: <request_id>
```

Request v1:

```json
{
  "schema_version": 1,
  "request_id": "uuid-o-id-opaco",
  "message": "Dopo le 23 attiva non disturbare prioritario",
  "manifest": {
    "device_model": "OnePlus CPH2747",
    "android_api": 36,
    "shizuku_available": true,
    "granted_permissions": ["android.permission.INTERNET"],
    "available_tools": ["set_dnd", "state.read", "toggle.set"],
    "available_triggers": [
      "time", "notification", "geofence", "phone_state.sms", "phone_state.call",
      "connectivity.wifi", "connectivity.wifi.identity",
      "connectivity.bt", "connectivity.power"
    ],
    "unavailable_tools": {},
    "whitelisted_contacts": [],
    "state_keys": {"dnd": "off|priority|total"}
  },
  "state": {
    "values": {"dnd": "off"},
    "foreground_app": null,
    "location_available": false
  }
}
```

`available_tools` contiene sia i discriminatori delle azioni compilabili (`set_dnd`,
`show_notification`, …), usati dal validator del bridge, sia gli eventuali tool di contesto/runtime
(`state.read`, `screen.capture`, …) selezionabili da azioni generative. Il probe Android deve
derivare entrambi dallo stesso snapshot di capability: un'azione non disponibile va esclusa e
riportata in `unavailable_tools` con il motivo.

`available_triggers` è opzionale soltanto per retrocompatibilità con i client pre-P2. Quando è
presente e non vuoto, il prompt e il validator del bridge rifiutano fail-closed ogni draft il cui
trigger non è nella lista. PhoneState è distinto in `.sms`/`.call`; Connectivity in `.wifi`,
`.bt`, `.power`. Un filtro SSID (`Connectivity.match`) richiede inoltre
`connectivity.wifi.identity`, pubblicato solo con location foreground+background. Il controllo
server non sostituisce la rivalidazione delle capability sul telefono.

`geofence` viene pubblicato soltanto con posizione **precisa** e accesso in background. Nel
contratto v1 accetta esclusivamente `transition=ENTER|EXIT` e `loiteringDelayMs=0`: il backend
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

Risposta v1:

```json
{
  "schema_version": 1,
  "request_id": "uuid-o-id-opaco",
  "reply": "Proposta pronta.",
  "meta": {
    "draft": {
      "name": "DND sera",
      "trigger": {"type":"time","cron":"0 23 * * *","at":null,"tz":"Europe/Rome","precision":"FLEXIBLE"},
      "actions": [{"type":"set_dnd","mode":"PRIORITY"}]
    },
    "error_code": null
  }
}
```

Se serve una chiarificazione, `draft` è `null` e `error_code` è `clarification_required`. Un draft e
un `error_code` non nullo non possono coesistere.

Il client accetta il draft soltanto se:

1. `schema_version == 1`;
2. `request_id` coincide con la richiesta;
3. envelope e draft non hanno campi sconosciuti o tipi coercibili;
4. `reply` è una stringa entro il limite;
5. il draft decodifica esattamente nei sealed type di `engine-core`.

La decodifica non equivale all'approvazione: `DraftValidator`, fingerprint, conferma utente e
revalidazione al fire-time restano obbligatori.

### Regole del prompt di compilazione (server)

Il prompt di sistema in `bridge.py` vincola il modello, tra l'altro, a: usare SOLO i tool di
`available_tools` (regola 1 — un tool assente equivale a non esistente, quindi il probe Android
deve pubblicare `invoke_llm` quando il runtime generativo è pronto); e — **REGOLA 9, aggiunta
il 2026-07-14 dopo la caratterizzazione reale** — per le reply WhatsApp il trigger deve avere
`conversationId` scelto tra i `whitelisted_contacts` e `isGroup=false` ESPLICITO (mai null);
le risposte generate usano il profilo `invoke_llm` P1 esatto, mentre `whatsapp_reply` statica è
riservata a testi dettati letteralmente dall'utente. Il draft resta comunque soggetto al
`DraftValidator` locale: la regola serve a produrre draft armabili al primo colpo, non a
sostituire i controlli.

## `POST /act`

`/act` è il confine one-shot P1 per generare il solo testo di una reply. Usa gli stessi header,
schema v1, bearer, limiti e regole di idempotenza di `/compile`. Il `request_id` Android è
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

Invarianti del request:

- `context_sources` accetta soltanto `notification` e `state`; `notification` è obbligatorio;
- `state` deve essere `null` se la regola non lo ha richiesto;
- in P1 l'unico `allowed_tools` accettato è esattamente `whatsapp_reply`;
- package ammessi: WhatsApp e WhatsApp Business; `is_group` deve essere `false` esplicito;
- `conversationId`, `StatusBarNotification.key`, fingerprint, coordinate e handle `RemoteInput`
  non attraversano mai il bridge;
- il server tratta goal e contesto come dati delimitati; il contenuto della notifica non può
  cambiare formato, tool o destinatario.

Risposta v1:

```json
{
  "schema_version": 1,
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

## Idempotenza e limiti

- Il server lega endpoint, `request_id`, `Idempotency-Key` e SHA-256 dei byte del request. Le cache
  `/compile` e `/act` sono namespace separate, quindi lo stesso ID non collide tra operazioni.
- La stessa richiesta entro 15 minuti restituisce la risposta cached senza richiamare il modello;
  due duplicati concorrenti attendono lo stesso risultato e producono una sola chiamata Hermes.
- Riutilizzare lo stesso ID con un body diverso restituisce `409 idempotency_conflict`.
- Cache massima: 128 richieste; un solo processo modello concorrente condiviso tra compile e act;
  richieste con ID diverso durante una chiamata ricevono `429`, senza aprire un secondo processo.
- Request massimo 256 KiB; response massimo 512 KiB; timeout server 55 s, client 60 s.
- I log contengono solo prefisso del request ID, status e durata: mai messaggio, stato, contatti o token.

## Errori HTTP

| Status | Significato |
| --- | --- |
| `400` | JSON o contratto request non valido |
| `401` | token assente/errato |
| `409` | schema incompatibile o stesso request ID con contenuto diverso |
| `413` / `415` | body troppo grande / content type errato |
| `429` | compile già in corso |
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
