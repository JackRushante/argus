# Argus P3 — decision record

**Data:** 2026-07-16
**Stato:** ACCEPTED per le direttive esplicite di Lorenzo; le scelte tecniche qui sotto sono la
baseline da implementare e verificare per slice.
**Sostituisce:** le parti incompatibili di §15–§17 della spec rev 5 e dei vecchi handoff. Non
riscrive la storia: i divieti precedenti erano corretti nel loro contesto, ma sono stati revocati.

## 1. Obiettivo di P3

P3 non è più «aggiungere per forza un provider veloce e poi fare computer-use». L'ordine deciso è:

1. rendere espliciti data flow, taint, compatibilità e profili di distribuzione;
2. sostituire l'elenco chiuso di sette state key con famiglie chiuse di lettori parametrici;
3. aggiungere trigger sensore efficienti, partendo dai sensori hardware event-driven;
4. separare le azioni Android normali da quelle che richiedono Shizuku;
5. costruire il loop computer-use a due tier, con il percorso lento/gratuito pienamente valido.

P4 resta variabili + control flow. Le decisioni di questo documento impediscono che P4 richieda
un rewrite del motore o una nuova interpretazione ad hoc dell'injection.

## 2. D0 — un solo limite etico, quattro classi operative

La direttiva di Lorenzo è: non trasformare preferenze, costi o limiti correnti in divieti morali.
Ogni limite deve essere classificato:

1. **invariante etica:** contenuto esterno non fidato non ottiene autorità di esecuzione o routing;
2. **trade-off:** costo, latenza, batteria o UX; si misura e si offre una scelta;
3. **limite tecnico corrente:** dire cosa manca e quanto costa superarlo;
4. **scelta di prodotto di Lorenzo:** revocabile senza presentarla come legge tecnica.

Conseguenze già accettate:

- una chat WhatsApp 1:1 con `conversationId` whitelistato può innescare un `RunShell` letterale
  già approvato; SMS e caller ID restano esclusi perché spoofabili;
- il brain gratuito lento è valido per automazioni non presidiate;
- i sensori sono ammessi se implementati con meccanismi event-driven/batched e consumo misurato;
- i vincoli di Google Play sono classe 3 (canale di distribuzione), non limiti del prodotto full.

## 3. D1 — transport e loop a due tier

### 3.1 Contratto comune

Il loop dipende da un `AgentTurnTransport`, non da un provider specifico. Ogni turno riceve goal,
storia bounded, osservazioni tipizzate e catalogo tool; restituisce **un solo** risultato:

- tool call tipizzata;
- richiesta di chiarimento;
- risultato finale;
- errore bounded.

Il transport non esegue tool Android. L'app esegue localmente, registra il risultato e lo invia al
turno successivo. Target, capability e budget restano quindi controllati dal telefono.

### 3.2 Tier lento/gratuito — baseline, non fallback degradato

Il bridge Hermes avrà un endpoint versionato a turni. Ogni turno può richiedere decine di secondi;
per un task unattended anche alcuni minuti complessivi sono accettabili. Non si promette streaming.
Si preferisce `uiautomator dump` quando l'albero è leggibile; screenshot + vision è fallback per
canvas/UI opache. Timeout e numero massimo di turni sono fingerprintati nella session policy.

### 3.3 Tier veloce/opzionale

`OpenAICompatTransport`/provider diretto usa lo stesso contratto e può offrire streaming e tool-call
nativi. È una scelta di UX/costo per uso presidiato, non un prerequisito di P3. Nessun provider
specifico viene hardcoded nel dominio.

## 4. D2 — data flow e taint: policy per sink, non divieto globale

La frase «contenuto esterno non può parametrizzare nulla» è troppo larga: renderebbe illegittimi
l'OTP negli appunti, una futura TTS e persino una reply alla stessa conversazione. L'invariante
corretto è: **un dato non fidato non può creare o cambiare autorità**.

### 4.1 Etichetta d'integrità

Ogni valore dinamico P4 porterà una label monotona:

- `CLEAN`: letterali approvati, orologio, output di un lettore locale tipizzato approvato e valori
  derivati esclusivamente da input clean;
- `TAINTED`: testo/titolo/sender visuale di SMS o notifiche, schermo/a11y, web/rete/file/clipboard,
  output LLM e qualunque valore derivato anche parzialmente da questi.

La provenienza resta un set (`sms`, `notification`, `screen`, `web`, `model`, …) per audit. Le
trasformazioni propagano il join; regex, parsing, escaping o l'LLM **non declassificano**.

Identità e permessi non sono stringhe clean: sono **capability token non serializzabili**. Un
`conversationId` verificato autorizza la reply alla stessa chat, ma non può essere copiato in un
campo destinatario arbitrario.

Declassificazione consentita soltanto con gesto live dell'utente sul valore concreto e nuova
approvazione/fingerprint. Whitelistare un contatto autorizza alcuni trigger/sink, non ripulisce il
testo che quel contatto invia.

### 4.2 Matrice sink

| Sink | `CLEAN` | `TAINTED` | Regola |
|---|---:|---:|---|
| confronto/trigger booleano | sì | sì | il payload decide solo se scattare; non diventa argomento |
| UI locale / clipboard / TTS / notifica locale | sì | sì | output locale dichiarato; audit senza payload |
| contesto LLM | sì | sì | delimitato come dati, privacy warning e tool bounded |
| reply alla **stessa** capability di conversazione | sì | sì | target congelato dall'evento verificato |
| URL/package/path/setting key/destinatario scelto dinamicamente | sì | **no** | routing/authority sink |
| shell, codice, template comando, installazione, tap/input privilegiato | sì | **no** | execution sink |
| creazione/modifica/arm di automazioni | sì | **no** | mai compile autonoma da contenuto esterno |
| rete verso target approvato | sì | solo opt-in esplicito | target clean + disclosure privacy |

Il `RunShell` attuale resta un caso ancora più stretto: il comando è interamente letterale e nel
fingerprint; nessun template fino a P4. Il messaggio può scegliere il momento soltanto sui canali
esplicitamente autorizzati.

### 4.3 Riservatezza separata dall'integrità

Per la distribuzione pubblica servirà un asse distinto `PUBLIC|PRIVATE|SECRET`. Un valore locale può
essere clean ma privato (posizione, stato app, contatti). Questo asse genera redazione/disclosure e
blocca esfiltrazioni non approvate; non cambia la capacità del valore di comandare un sink.

## 5. D3 — state reader parametrici

### 5.1 Modello

Il closed-world diventa «famiglie chiuse, parametri aperti». Il dominio aggiungerà un sealed type
`StateQuery` con almeno:

- `Builtin(key)` per compatibilità con le sette chiavi attuali;
- `Setting(namespace, key)` con namespace enum `SYSTEM|SECURE|GLOBAL`;
- `SystemProperty(name)`;
- `Sysfs(path)` limitato a un percorso normalizzato sotto `/sys/`;
- `DumpsysField(service, field)` con estrattore key/value bounded, non regex arbitraria.

Una nuova `Condition.StateCompare(query, valueType, op, expected)` affianca `StateEquals` v1. Il
tipo (`TEXT|NUMBER|BOOLEAN`) evita il fallback lessicografico accidentale. Aggiungere un subtype è
wire-additive e non modifica il fingerprint delle regole v1.

### 5.2 Confini di esecuzione

- Comandi costruiti esclusivamente come argv di `ProcessBuilder`, mai concatenati in `sh -c`.
- Bound per argomenti, output e timeout; NUL/newline/control char rifiutati.
- `sysfs` risolve il canonical path e resta sotto `/sys/`; niente `/proc`, `/data` o file generici.
- `dumpsys` legge un servizio validato e fa parsing in-process; niente grep/awk/regex da LLM.
- I reader sono read-only. Scritture restano azioni separate e approvate.

### 5.3 Probe all'arm e semantica UNKNOWN

Prima di Arm, il telefono esegue ogni query esatta. Query non supportata, non leggibile, troncata o
non convertibile nel tipo dichiarato ⇒ ERROR e niente arm. La review mostra reader/parametri e un
esito di probe redatto; il valore campione non entra nel fingerprint.

Al fire-time una lettura mancante è `UNKNOWN`, mai false. `NOT UNKNOWN == UNKNOWN`; AND/OR usano
logica a tre valori e il confine finale esegue soltanto `TRUE`. Questo bug è già stato corretto in
`a9e2436` per le condizioni v1.

### 5.4 Lettura minima

Il motore deriva un `StateReadRequest` dalla singola regola e legge soltanto ciò che serve. Una
regola senza condizioni e senza contesto state non deve avviare Shizuku né `dumpsys`. Le richieste
nello stesso batch possono essere unite/cachate, senza trasformare lo snapshot in un inventario
completo. Il contesto LLM deve elencare query esplicite: «state» non significherà più «manda tutto».

### 5.5 Capability

La capability persistita identifica la famiglia (`state.reader.setting`, ecc.); i parametri esatti
sono già nel fingerprint. Il probe capability verifica binario/API/grant, quello all'arm verifica
la query concreta. Una revoca globale mette la regola in `NEEDS_REVIEW`; un outage Shizuku già
autorizzato è transitorio e blocca senza invalidare l'approvazione.

## 6. D4 — sensori smart

### 6.1 Ordine

1. `SIGNIFICANT_MOTION`: one-shot hardware wake-up; si ri-registra dopo ogni callback.
2. `STATIONARY`/`MOTION`: event-driven se presenti, con latenza dichiarata.
3. step detector/counter: soltanto con `ACTIVITY_RECOGNITION` e domanda utente esplicita.
4. luce/prossimità/soglie: solo quando hanno un caso reale; batching >30 s ove supportato.
5. accelerometro/giroscopio raw always-on: non baseline; richiede misura batteria e una ragione.

Inventario OnePlus 15 verificato il 2026-07-16: significant-motion wake-up one-shot, stationary
detect one-shot, step detector/counter, prossimità wake-up, luce e accelerometro con FIFO sono
presenti. La disponibilità resta runtime, mai assunta per altri device.

### 6.2 Runtime

Android 9+ non consegna normalmente i sensori alle app in background: i listener vivono in un FGS
user-visible. L'attuale sentinella `specialUse` va generalizzata in un unico runtime con un set di
«demand reasons» (Wi-Fi, power, sensor), non duplicata. Stop soltanto quando il set è vuoto.

I trigger one-shot devono essere rearmati anche dopo process death, package replace e boot. Stato
di registrazione e fingerprint restano locali; callback stale non eseguono. Cooldown e dedup sono
obbligatori perché i sensori descrivono transizioni fisiche rumorose.

### 6.3 Batteria e gate

Per ogni famiglia si registrano reporting mode, wake-up flag, FIFO, sampling/latency effettivi e
tempo FGS. Il gate non è «compila»: comprende callback reale con app background, process restart,
rearm dopo il primo evento e assenza di wake lock permanente.

## 7. D5 — distribuzione personale e pubblica

### 7.1 Profili, non un prodotto amputato

- **personal-full / sideload:** tutte le capability approvate da Lorenzo, Shizuku e permessi
  sensibili opt-in;
- **play-core:** nessun permesso che non abbia dichiarazione approvata; azioni Android normali,
  notifiche, brain configurabile e capability scoperte a runtime;
- **play-extended:** possibile soltanto dopo esito positivo delle dichiarazioni Play; non è assunto
  come garantito.

Le build non devono fingere capability assenti. Tailscale è un transport possibile, non una
dipendenza del prodotto: endpoint Hermes, provider diretto o servizio dell'utente implementano lo
stesso Brain contract. Token e segreti sono configurati per-installazione, mai nell'APK.

### 7.2 Correzione alla nota precedente su SMS

`RECEIVE_SMS` non è automaticamente impossibile sul Play Store. La policy Google Play corrente
elenca esplicitamente **Device automation** fra le eccezioni ammissibili per SMS/Call Log, soggetta
a core functionality, assenza di alternativa, declaration form e review. Quindi:

- OTP autocopy/full phone triggers sono **pubblicabili solo se Google approva l'eccezione**;
- `play-core` deve comunque poter essere prodotto senza tali permission;
- SMS Retriever non sostituisce l'automazione di OTP per app terze.

Altri gate Play correnti:

- background location: declaration, prominent disclosure, privacy policy e video;
- FGS `specialUse`: tipo/permission corretti, declaration e video; deve essere user-visible,
  stoppabile e necessario;
- Accessibility futura: Argus è automation tool, non `isAccessibilityTool`; declaration e consenso
  separato sono possibili, non automaticamente vietati;
- `SCHEDULE_EXACT_ALARM` è il percorso user-granted corretto; non usare `USE_EXACT_ALARM`;
- evitare `QUERY_ALL_PACKAGES`: query mirate per package/app scelte dall'utente.

### 7.3 Base senza Shizuku

Da separare dal `ShizukuActionExecutor`:

- Android/API normali: `LaunchApp`, `OpenUrl`, `SetDnd` (policy access), `SetRinger`, notifica,
  clipboard, reply RemoteInput e lettori disponibili senza shell;
- Shizuku: toggle Wi-Fi/Bluetooth, shell, screencap/uiautomator/input e altre op privilegiate;
- Accessibility opzionale può offrire gesture/UI su build pubblica, ma non viene introdotta di
  nascosto come sostituto universale.

## 8. D6 — schema, fingerprint e compatibilità

Tre versioni sono distinte:

1. schema automazione persistito;
2. materiale/fingerprint di approvazione;
3. protocollo bridge (`/compile`, `/act`, futuri turni).

Non vanno incrementate insieme per comodità. Le estensioni P3 usano nuovi sealed subtype, evitando
di aggiungere default fields ai tipi eseguibili v1: `ArgusJson.encodeDefaults=true` cambierebbe il
fingerprint di ogni regola esistente. Qualunque modifica a un tipo esistente richiede canonical
material versionato e golden test che provi che gli hash v1 non cambiano dopo l'upgrade.

Il bridge P3 usa una nuova versione/profilo capability senza riaprire `/chat`. Android e host sono
deployati insieme e confrontati con hash normalizzati (non SHA dei byte CRLF/LF).

## 9. D7 — failure e audit

- Missing state ⇒ UNKNOWN e `CONDITION_STATE_UNAVAILABLE`, distinto da condizione falsa.
- Sensor/reader non disponibile all'arm ⇒ ERROR visibile, mai regola morta silenziosa.
- Ogni arm, disable, enable, edit, delete e auto-quarantena deve produrre lifecycle audit senza
  payload sensibili. È necessario per spiegare regole «scomparse».
- Output dinamici futuri hanno valore + label + provenance; journal/audit persiste tipo, hash e
  outcome, non il contenuto salvo storage esplicitamente cifrato.
- Fallimento di una action non impedisce le successive, ma un valore mancante non può essere
  sostituito con stringa vuota dentro template o routing.

## 10. Audit indipendente eredità P2 (risultati al 2026-07-16)

Corretto e testato:

- fix geofence di ieri ignorava accuratezza/età del fix e poteva vetoare un bordo vero;
- FirePolicy non legava la chat live alla chat del trigger approvato per `RunShell`;
- cleanup harness cancellava Room ma non le registrazioni OS;
- harness stampava l'indirizzo geocodificato nonostante il commento contrario;
- coordinate molto vicine a zero mostravano `-0`;
- `NOT` trasformava un reader indisponibile in condizione vera;
- ogni fire leggeva tutte le sette key + foreground anche senza necessità;
- `Condition.LocationIn` non riceveva mai `DeviceState.location` e non aveva capability runtime;
- spec principale, `CLAUDE.md`, contratto bridge e piano P2 avevano drift sulle decisioni correnti.

Resta da chiudere nelle slice P3:

- il contesto LLM `state` sovra-raccoglie lo snapshot invece di elencare query approvate;
- azioni non privilegiate sono erroneamente nascoste quando Shizuku è offline;
- manca audit lifecycle;
- il bridge compile v1 non conosce ancora `StateQuery`: serve P3-1C strict v2 prima di esporre i
  reader al linguaggio naturale.

## 11. Gate di accettazione P3

Una slice è «fatta» soltanto con:

1. TDD JVM/Robolectric e full gate verde;
2. bridge host testato se cambia wire contract;
3. capability/revocation/outage testati fail-closed;
4. device reale per API Android non simulabili;
5. nessun log/CLI con token, indirizzi, numeri, payload o coordinate;
6. evidenza separata in VERIFIED / REASONED / NOT PROVEN;
7. worktree pulito, commit atomico e push sull'hub Unraid.

## 12. Fonti normative/tecniche verificate

- Android Location accuracy/freshness: <https://developer.android.com/reference/android/location/Location>
- Android Sensor overview/background: <https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview>
- Significant motion: <https://developer.android.com/reference/android/hardware/Sensor#TYPE_SIGNIFICANT_MOTION>
- Sensor batching: <https://developer.android.com/reference/android/hardware/SensorManager>
- Play SMS/Call Log (include Device automation):
  <https://support.google.com/googleplay/android-developer/answer/10208820?hl=en>
- Play background location:
  <https://support.google.com/googleplay/android-developer/answer/9799150?hl=en>
- Play foreground services:
  <https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB>
- Play AccessibilityService:
  <https://support.google.com/googleplay/android-developer/answer/10964491?hl=en-GB>
- Play package visibility:
  <https://support.google.com/googleplay/android-developer/answer/10158779?hl=en>
