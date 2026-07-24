# Argus — piano per autofill assistito da Vaultwarden

Stato: proposta da validare, nessuna implementazione

Data: 2026-07-24

## Obiettivo

Fornire un fallback affidabile quando Bitwarden non riconosce o non completa username, password e
TOTP in app Android, Firefox, Brave o Chrome. Argus deve poter comprendere la schermata, selezionare
il record corretto dal vault e compilare i campi, senza dare a vision o LLM accesso ai segreti.

Il risultato non è una generica azione `input_text` libera. È una capability separata,
`CREDENTIAL_FILL`, con target verificato, gesto esplicito dell'utente, autenticazione biometrica,
lease del segreto brevissima e audit privo di valori.

## Punto architetturale non negoziabile

Vaultwarden conserva dati cifrati secondo il modello zero-knowledge: il server non possiede le chiavi
per restituire username, password o seed TOTP in chiaro. Bitwarden documenta che cifratura e
decifratura avvengono sul client e che il server conserva il vault cifrato
([Encryption Protocols](https://bitwarden.com/help/what-encryption-is-used/)).

“Accesso diretto a Vaultwarden” deve quindi significare:

1. sincronizzare sul telefono i record cifrati tramite un client compatibile;
2. decifrarli sul telefono dopo autenticazione dell'utente;
3. tenere il plaintext soltanto in memoria per il tempo strettamente necessario al fill.

Non significa creare su Unraid un endpoint che restituisce password in chiaro. Un `bw serve`
raggiungibile in rete trasformerebbe una session key in un oracle del vault. La Vault Management API
ufficiale richiede infatti un processo CLI locale avviato con `bw serve`
([Bitwarden APIs](https://bitwarden.com/help/bitwarden-apis/)); può essere utile in uno spike
isolato, non è un'architettura di produzione.

## Strategia consigliata

### Prima iterazione: Argus come fallback, Bitwarden resta il provider principale

Bitwarden continua a gestire l'autofill normale. Quando non compare o non riconosce una pagina,
l'utente invoca “Compila con Argus” da quick tile, notifica persistente o accessibility button.
Argus usa un servizio Accessibility strettamente limitato alla sessione di fill.

È la via più utile nel breve periodo perché:

- non sostituisce subito l'autofill che già funziona nella maggioranza dei casi;
- copre split login e custom fields, che Bitwarden dichiara non supportati nel mobile autofill
  ([Bitwarden Android autofill](https://bitwarden.com/help/auto-fill-android/));
- consente di diagnosticare target e campi prima di costruire un password manager completo.

### Seconda iterazione opzionale: provider Android completo

Se il fallback si dimostra stabile, Argus può implementare `AutofillService` per Android 8+ e un
Credential Manager provider per Android 14+. L'Autofill Framework riceve un `AssistStructure` e
restituisce `Dataset`, evitando coordinate e clipboard
([Android AutofillService](https://developer.android.com/identity/autofill/autofill-services)).
Credential Manager può aggregare più provider e verifica i browser privilegiati tramite package e
certificato
([Credential provider](https://developer.android.com/identity/sign-in/credential-provider)).

Questa fase cambia il ruolo di Argus: diventa anche password-manager client. Va trattata come
prodotto e threat model autonomi, non come una piccola automazione.

## Architettura

```text
Gesto utente
    |
    v
TargetResolver ------> package + firma / browser + origin
    |                         |
    | target ambiguo          +----> abort
    v
FieldPlanner --------> Accessibility tree, poi vision redatta se necessaria
    |
    v
CredentialMatcher ---> soli metadati cifrati/decifrati localmente
    |
    v
Conferma + BiometricPrompt
    |
    v
SecretLease ---------> password/TOTP mai esposti al planner
    |
    v
SecretExecutor ------> Dataset o ACTION_SET_TEXT; clipboard solo emergenza
    |
    v
Post-verifica + zeroize + audit redatto
```

### 1. `TargetResolver`

Produce un'identità immutabile del destinatario prima di cercare credenziali.

Per app native:

- package name;
- digest del certificato di firma;
- user/profile Android;
- window ID e activity corrente;
- associazione record-target approvata dall'utente al primo utilizzo.

Il solo package name non basta: anche Bitwarden avverte che un'app malevola può imitarlo
([troubleshooting Android](https://bitwarden.com/help/auto-fill-android-troubleshooting/)).

Per browser:

- package e firma del browser consentito;
- origin HTTPS esatto, non soltanto testo visibile o titolo pagina;
- `AssistStructure.webDomain`, origin di Credential Manager o integrazione browser affidabile;
- deny su origin assente, HTTP non esplicitamente autorizzato, WebView ambiguo, iframe non
  verificabile o cambio origin durante il fill.

Vision non può decidere che “sembra GitHub” e quindi autorizzare una password GitHub. Può classificare
la forma della pagina soltanto dopo che l'origin è stato determinato da un canale di sistema.

### 2. `VaultClient`

Componente on-device, senza passaggio dal Brain:

- sync TLS diretto con l'istanza Vaultwarden configurata;
- database locale sempre cifrato;
- riuso, se tecnicamente e legalmente possibile, di componenti crypto/protocollo Bitwarden
  sottoposti ad audit;
- nessuna implementazione crypto originale prima di uno spike e di una review dedicata;
- chiave locale protetta da Android Keystore;
- unwrap auth-per-use tramite `BiometricPrompt.CryptoObject`;
- lock su timeout, screen lock, cambio account, revoca biometrica e richiesta utente;
- token, master password, chiavi e plaintext mai in Room normale, log, crash report, analytics,
  `SavedStateHandle`, clipboard o variabili P4.

Android Keystore permette di richiedere autenticazione per ogni uso della chiave
([Android Keystore](https://developer.android.com/privacy-and-security/keystore)); BiometricPrompt
lega la conferma a un'operazione crittografica di sistema
([BiometricPrompt](https://developer.android.com/reference/androidx/biometric/BiometricPrompt)).

Una sessione `bw` non è una scorciatoia innocua: `bw unlock` restituisce una session key che abilita
la decifratura ([Bitwarden CLI](https://bitwarden.com/en-gb/help/cli/)). Non va salvata in chiaro,
passata ad Argus Brain o esposta come variabile d'ambiente di un servizio di rete.

### 3. `CredentialMatcher`

Matching deterministico, compatibile con le regole URI del vault:

- origin/app identity esatti prima;
- policy URI equivalenti a Bitwarden, senza fuzzy matching LLM;
- scelta manuale quando esistono più account;
- allowlist e denylist per target;
- record e mapping memorizzati soltanto nel dominio cifrato.

La UI può mostrare un'etichetta account dopo unlock, ma planner e vision ricevono solo ID effimeri
opachi. Username, password, custom fields e seed TOTP non entrano mai nel loro contesto.

### 4. `FieldPlanner`

Usa una gerarchia dal canale più affidabile al più fragile.

1. **Autofill structure:** hint, tipo input, `AutofillId`, `webDomain`.
2. **Accessibility tree:** class, editability, hint, label, bounds, relazione label-field.
3. **Vision locale:** screenshot della sola finestra, redatta e ridimensionata, usata soltanto se i
   primi due livelli non bastano.

Il servizio Accessibility può impostare testo con `ACTION_SET_TEXT` da Android 5
([AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction)).
Da Android 11 può acquisire screenshot e da Android 14 può acquisire la singola finestra, API
documentata anche per comprensione visuale ML
([AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)).

Il planner restituisce un piano tipato:

```json
{
  "windowToken": "opaque",
  "screenDigest": "sha256:…",
  "fields": [
    {"node": "n17", "role": "USERNAME", "confidence": 0.99},
    {"node": "n23", "role": "PASSWORD", "confidence": 0.99}
  ],
  "submit": null
}
```

Non restituisce valori. Coordinate nude sono ammesse solo come ultima risorsa e devono essere legate
a window token, screen digest, bounds e scadenza.

Regole vision:

- modello locale come default;
- screenshot catturato prima di sbloccare il vault;
- OCR locale e redazione di notifiche, testo già presente, tastiera, barra di stato e aree non
  necessarie;
- nessun provider remoto nella modalità normale;
- eventuale diagnostica remota soltanto con opt-in esplicito, screenshot senza valori e senza
  accesso al vault;
- bitmap distrutta dopo il piano, mai salvata o allegata ai log.

### 5. `SecretExecutor`

Riceve il piano congelato e una `SecretLease` dopo la biometria. Prima di ogni write ricontrolla:

- package, firma, origin, window ID e screen digest;
- nodo ancora esistente, editabile e coerente con il ruolo;
- app in foreground e assenza di overlay non riconosciuti;
- lease non scaduta e sessione non cancellata.

Canali, in ordine:

1. `Dataset` Autofill;
2. `AccessibilityNodeInfo.ACTION_SET_TEXT`;
3. focus + input controllato soltanto su node/bounds verificati;
4. clipboard sensibile come fallback manuale ed esplicito.

La clipboard deve avere TTL breve e compare-and-clear: Argus la cancella solo se contiene ancora il
clip da lui creato, per non distruggere appunti successivi dell'utente. Android raccomanda sia il flag
sensibile sia la cancellazione
([secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)).

Il tap sul pulsante “Continua” è separato dal fill, ricontrolla la schermata e non equivale ad
auto-submit. Submit automatico è escluso dalla prima release.

### 6. TOTP

- seed letto dal record già selezionato, mai dal server in plaintext;
- generazione RFC 6238 locale e deterministica;
- verifica clock/drift prima del fill;
- codice generato dopo la biometria e il più vicino possibile alla write;
- retry solo al cambio della finestra temporale, con nuova conferma se il target è cambiato;
- nessun codice in log, screenshot, notifica, Room o Brain;
- auto-copy disabilitato se `ACTION_SET_TEXT` funziona.

### 7. Conferma utente

Prima dello sblocco mostra una superficie Argus protetta:

- nome app oppure origin verificato;
- firma/attendibilità del target;
- account selezionato in forma riconoscibile ma non segreta;
- campi che saranno compilati;
- richiesta biometrica;
- pulsante annulla/stop sempre disponibile.

Le Activity Argus relative al vault usano `FLAG_SECURE`. Al cambio finestra o origin la sessione
viene cancellata, non ripianificata silenziosamente.

## Stato della sessione

```text
IDLE
  -> DETECTED
  -> TARGET_VERIFIED
  -> PLAN_READY
  -> USER_CONFIRMED
  -> AUTHORIZED
  -> SECRET_LEASED
  -> FILLING
  -> VERIFIED
  -> CLEARED
```

Ogni mismatch, timeout, process death, screen change, lock, overlay sospetto o errore di write porta
direttamente a `ABORTED -> CLEARED`. Nessun resume automatico dopo process death.

## Integrazione con il modello Argus

La capability non deve essere una combinazione libera di `screen.capture`, `input_text`,
`copy_text` e variabili.

Nuovo boundary proposto:

```kotlin
CredentialFillRequest(
    sessionId: EphemeralId,
    verifiedTarget: VerifiedTarget,
    requestedFields: Set<CredentialField>,
    userInitiated: Boolean = true,
)
```

Vincoli:

- nuovo privilege tier `CREDENTIAL`, separato da BASE e Shizuku;
- invocabile soltanto da gesto foreground dell'utente, mai da SMS, notifica, geofence, MQTT,
  timer, webhook o reply generativa;
- nessuna interpolazione P4 di `SecretValue`;
- `SecretValue` è un tipo non serializzabile e non implementa il modello delle variabili;
- TaintPolicy Aggressive non si applica al dominio credenziali;
- il Brain può richiedere “trova i campi”, non “dammi o scrivi questa password”;
- executor deterministico possiede l'unico handle alla lease;
- capability non inclusa nel normale `availableTools` del compiler.

## Threat model minimo

| Minaccia | Contromisura |
| --- | --- |
| App clone con stesso package | certificato di firma + enrollment + origine installazione attendibile |
| Phishing web | origin di sistema, HTTPS, deny se ambiguo, mai fidarsi della vision |
| Prompt injection nella pagina | vision senza segreti; piano tipato; executor non interpreta testo libero |
| LLM/provider compromesso | nessun segreto, token, seed o elenco vault nel contesto |
| Screenshot sensibile | cattura pre-unlock, crop/redazione locale, bitmap volatile |
| Session key rubata | Keystore, auth-per-use, timeout, no storage plaintext |
| Overlay/tapjacking | rilevamento finestre/overlay, conferma di sistema, abort su cambio |
| Coordinate stale | window token + screen digest + TTL + revalidation |
| Clipboard sniffing | non usarla normalmente; sensitive flag + TTL + compare-and-clear |
| Log/crash dump | tipi redatti, lint/test anti-secret, audit solo esiti e ID effimeri |
| Replay di un fill | nonce sessione monouso, target binding, lease consumabile |
| Auto-submit indesiderato | escluso per default; azione separata e confermata |

## Piano di lavoro

### Fase 0 — diagnosi del problema attuale

Prima di duplicare Bitwarden, raccogliere una matrice reale dei fallimenti:

- app/browser, pagina/origin, uno o due step, campi custom;
- presenza di `AssistStructure` e hint;
- URI salvato nel record Bitwarden;
- integrazioni Chrome/Brave abilitate;
- stato Accessibility, battery optimization e servizio dopo force-stop;
- comportamento Firefox;
- username/password/TOTP, distinguendo “nessun suggerimento”, “record non trovato” e “campo non
  scritto”.

Log soltanto metadati e ruoli, mai contenuti. Questa fase può già risolvere una parte dei casi tramite
URI/matching e impostazioni OEM, come indicato dalla guida Bitwarden.

**Exit gate:** almeno dieci casi riproducibili classificati e una lista dei pattern non risolvibili
con la configurazione esistente.

### Fase 1 — spike Vaultwarden on-device

- mappare protocollo sync, KDF, unlock, rotazione chiavi e TOTP;
- valutare riuso del codice upstream rispetto a un client minimale;
- provare encrypted cache + Keystore + auth-per-use;
- testare logout, revoca token, cambio master password, rotazione encryption key e offline;
- nessuna UI automation.

**Exit gate:** un record di test viene sincronizzato, decifrato dopo biometria e azzerato dalla
memoria; nessun plaintext persistente.

### Fase 2 — fallback Accessibility senza vision

- quick action esplicita;
- target binding app/browser;
- parser albero Accessibility;
- username/password su nodi con ruolo certo;
- split-login con una conferma per la sessione e revalidation a ogni schermata;
- nessun tap submit.

**Exit gate:** E2E reali su app native e almeno Firefox, Brave e Chrome, inclusi cambio origin e app
clone di test.

### Fase 3 — vision fallback

- classificatore locale dei campi;
- screenshot single-window pre-unlock;
- piano tipato, confidence threshold e manual correction;
- fallback coordinate con digest/TTL;
- test di prompt injection visuale e layout che cambia durante il fill.

**Exit gate:** vision non riceve mai segreti e non può autorizzare target o record.

### Fase 4 — TOTP

- generatore locale e test vector RFC;
- field role OTP;
- gestione split step e scadenza;
- clock drift e retry controllato.

**Exit gate:** OTP compilato senza clipboard e senza comparire in log/screenshot.

### Fase 5 — provider nativo opzionale

- `AutofillService`/Dataset;
- Credential Manager su Android 14+;
- trusted-browser allowlist con package + certificate fingerprint;
- decisione prodotto su Argus come preferred provider.

**Exit gate:** nessuna regressione rispetto al fallback e UX di selezione provider comprensibile.

### Fase 6 — hardening e distribuzione

- security review indipendente del vault boundary;
- dependency/SBOM e supply-chain review;
- fuzz di parser vault, origin e Accessibility tree;
- test process death, low-memory, lock, revoca permessi e update app;
- documentazione disclosure Accessibility e privacy;
- verifica policy di distribuzione F-Droid/Play prima di abilitare il servizio in una build pubblica.

## Matrice minima E2E

- OnePlus corrente più un Pixel/AOSP e un Samsung;
- Android 11, una versione intermedia e la versione target corrente;
- Firefox, Brave e Chrome aggiornati;
- app native Compose, Views, WebView, React Native e Flutter;
- pagina singola, split username/password, campo custom, OTP separato;
- due account per lo stesso sito;
- origin errato, redirect, iframe, HTTP, certificato browser non atteso;
- finestra che cambia fra planning e fill;
- overlay ostile;
- vault bloccato/offline/token revocato;
- process death dopo biometria;
- verifica automatica che log, database, screenshot e crash artifact non contengano canary secrets.

## Decisioni ancora da prendere

1. **Fallback o provider completo:** raccomandazione iniziale: fallback Accessibility user-initiated,
   lasciando Bitwarden provider principale.
2. **Codice crypto:** riuso upstream se praticabile; altrimenti il progetto richiede un audit
   crittografico dedicato prima della produzione.
3. **Vision remota:** raccomandazione: vietata nella modalità credenziali; solo locale.
4. **Durata unlock:** raccomandazione: auth-per-fill per password/TOTP, non finestra temporale lunga.
5. **Auto-submit:** raccomandazione: fuori scope; rivalutabile solo come opt-in per singolo target.
6. **Release pubblica:** valutare un modulo/flavor separato se il servizio Accessibility rende
   incompatibile la policy del canale di distribuzione.

## Definition of done

La feature è pronta soltanto quando:

- nessun componente AI può leggere o scrivere direttamente un segreto;
- target e origin sono verificati da segnali di sistema;
- ogni fill è iniziato dall'utente e autorizzato biometricamente;
- plaintext e screenshot non persistono;
- il canale normale non usa clipboard;
- i test ostili e la matrice device/browser sono verdi;
- un audit indipendente approva protocollo vault, storage locale e boundary Accessibility;
- la documentazione pubblica dichiara chiaramente permessi, limiti e rischio residuo.
