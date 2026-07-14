# Contratto Argus ↔ Hermes bridge

Stato: protocollo v1 operativo dal 2026-07-13.

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

## Idempotenza e limiti

- Il server lega `request_id`, `Idempotency-Key` e SHA-256 dei byte del request.
- La stessa richiesta entro 15 minuti restituisce la risposta cached senza richiamare il modello;
  due duplicati concorrenti attendono lo stesso risultato e producono una sola chiamata Hermes.
- Riutilizzare lo stesso ID con un body diverso restituisce `409 idempotency_conflict`.
- Cache massima: 128 richieste; un solo compile Hermes concorrente; richieste con ID diverso durante
  un compile ricevono `429`, senza aprire un secondo processo modello.
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
