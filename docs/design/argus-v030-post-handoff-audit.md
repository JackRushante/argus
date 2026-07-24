# Argus v0.3.0 → v0.3.1 — audit post-handoff e chiusura fix

Data audit/fix-pass: 2026-07-24

Baseline revisionata: `github/master` a `7cf0dfd`, includendo il lavoro Claude successivo alla
release `v0.3.0` (`5a73ea6`). Il lavoro è stato svolto nel worktree isolato
`fix/v030-audit-p0-p2`; il workspace Claude non è stato modificato.

## Esito

I finding P0/P1/P2 confermati non richiedevano una riscrittura. Sono stati corretti nei confini
Kotlin ↔ Hermes ↔ runtime Android, con difese fail-closed e test incrociati. Il percorso principale
P4 generativo è ora provato su device reale:

`trigger → binding/interpolazione → Hermes /act v3 → capture → VarCompare → azione Android`.

P4 può essere descritto come completo per `InvokeLlm`: capture e sink sincroni reply/notifica
esistono anche dentro `if`/`while`. `InvokeLlmV2` dentro P4 resta intenzionalmente fuori contratto e
viene rifiutato prima dell'approvazione; non esiste più un draft noto come impossibile che possa
essere armato.

## Finding e risoluzione

### P0 — Hermes non accettava `/act` v3: RISOLTO

- server e client supportano `/act` v1/v2/v3;
- `/health/v2` annuncia `[1,2,3]` e Android richiede v3;
- `runtime_data` ha schema exact, limiti bounded e corrispondenza biunivoca marker↔record;
- i dati runtime sono serializzati come JSON in un blocco DATA non fidato, separato dal SYSTEM;
- una golden request è condivisa tra test Kotlin e parser Python;
- suite Python eseguita sia nel repository sia sul bridge Hermes deployato;
- test device autenticato e E2E completo passati.

### P1 — enum wire `set_dark_mode`: RISOLTO

`NightModeSerializer` accetta il wire canonico lowercase prodotto dal compilatore e il legacy
uppercase già persistito. L'encoding resta uppercase per non alterare JSON/fingerprint esistenti.
Round-trip e compatibilità sono coperti dai test; una compile Hermes live sul device ha prodotto
`auto` e Android l'ha decodificato come `NightMode.AUTO`.

### P1 — retry compile fuori budget/non contabilizzato: RISOLTO

Il retry è stato rimosso dal transport puro e spostato in `MeteredBrain`, il choke-point che:

1. registra sempre usage/costo del primo tentativo;
2. riesegue il gate HARD;
3. esegue e registra separatamente il secondo tentativo solo se ancora ammesso.

Cancellation e codici stabili restano preservati.

### P1 — programmi generativi approvabili ma ineseguibili: RISOLTO

- `InvokeLlm` P4 viene eseguito sincronicamente per i sink `WHATSAPP_REPLY`,
  `LOCAL_NOTIFICATION` e `CAPTURE_ONLY`;
- `CAPTURE_ONLY` è un valore wire esplicito, richiede `captureAs` e vieta target/titolo di consegna;
- la lane rivalida il contratto prima e dopo il modello;
- `InvokeLlmV2` annidato in P4 viene rifiutato da prompt, bridge e `DraftValidator`, con difesa
  runtime `p4_invoke_llm_v2_unsupported`.

### P1 sicurezza — `SECRET` confuso con non-esportabilità: RISOLTO nel modello

`ConfidentialityLabel` è ora documentata come classificazione/redazione. La decisione di egress è
separata in `RemoteEgressPolicy`:

- provenienza `CREDENTIAL` → `DENY`, indipendentemente dalla postura Aggressive;
- altro `SECRET` → `REQUIRE_REVIEW`;
- dati non secret → `ALLOW`.

L'interpolatore blocca dinamicamente una credenziale diretta verso il Brain con
`remote_egress_blocked`, classificato come policy stop. La UI mostra classificazione e provenienza.
La futura integrazione vault deve creare `SecretValue`/provenienza `CREDENTIAL` fuori dalle normali
variabili stringa e non deve mai inviare password/TOTP a un LLM.

Residuo P2 di prodotto: la review mostra sink, tool, classificazione e provenance, ma non ancora una
riga provider/dominio remoto dedicata. Va aggiunta insieme al credential-autofill, quando il
provider di destinazione diventerà parte del contratto approvato.

### P2 — prompt Android/Hermes incoerenti e `random_int`: RISOLTO

Le regole flat-v1 e P4 su shell, setting e state reader non si contraddicono più; `random_int` ha lo
stesso schema su entrambi i lati. Una fixture semantica condivisa controlla frammenti
obbligatori/vietati. I test ostili verificano framing JSON, newline e delimitatori.

### P2 sicurezza — clipboard senza lease: RISOLTO nel processo

`AndroidClipboardCopier` usa un owner label univoco, TTL 60 secondi e compare-and-clear. Il task
scaduto cancella solo il clip Argus ancora identico: un nuovo clip Argus o una sostituzione
utente/app sopravvivono. Un errore OEM di lettura lascia la clipboard intatta. Il timer è
process-local: un kill del processo può impedirne l'esecuzione, quindi per password/TOTP il percorso
normale futuro deve restare Autofill/Accessibility diretto e la clipboard soltanto fallback.

### P2 — capability computer-use fantasma: RISOLTO come dichiarazione onesta

`screen.capture`, `screen.dump_ui`, `toggle.set` e `app.launch` non sono più pubblicati come
available. Gli alias/primitive senza Action wire o ciclo observe→act→verify restano esplicitamente
unavailable. `state.read` rimane una capability runtime interna per `contextSources=["state"]`, non
un'azione compilabile. `tap`/`input_text` restano riservati e rifiutati.

### P2 — package visibility: RISOLTO

I percorsi base non eseguono più query preventive filtrate da Android 11. App, settings, Shizuku,
alarm e timer vengono avviati con Intent package-scoped/direct e il risultato reale viene gestito
fail-closed (`ActivityNotFound`/background restriction). Aggiunti test Robolectric di regressione.

### P2 distribuzione — esenzione batteria: DECISIONE DOCUMENTATA

Il gesto esplicito `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` resta coerente con l'attuale canale
F-Droid/sideload e con un'app di automazione background. Non è dichiarato Play-ready: un'eventuale
build Play dovrà riesaminare la policy corrente o usare il pannello generale senza richiesta
diretta.

## Evidenza prodotta

- test bridge Python locali e sul deploy Hermes;
- hash raw del bridge deployato uguale al repository al momento del deploy;
- health produzione: compile v1/v2, act v1/v2/v3;
- suite JVM/Robolectric mirate per engine, brain, automation e UI;
- gate finale `test lint assembleDebug assembleDebugAndroidTest`: 759 task Gradle e 1.853
  esecuzioni test, zero failure/error/skip; Lint senza errori;
- APK debug assemblato, installato come upgrade con dati/permessi preservati;
- test instrumented Hermes autenticati sul OnePlus/Android 16;
- E2E reale P4 resolved attraverso Engine, Hermes, capture, branch e azione.

Le build release firmate/riproducibili e i quattro asset F-Droid non fanno parte del gate di branch:
vanno prodotti solo dal commit finale taggato, come descritto nel runbook F-Droid.

## Residui reali (non blocker v0.3.1)

1. `InvokeLlmV2` annidato P4 richiede un futuro schema resolved dedicato; oggi è correttamente
   non compilabile/armabile.
2. Il controllo UI tipato/vision non esiste ancora: le primitive raw non sono una feature.
3. Manca una matrice reale multi-OEM/multi-versione Android.
4. Il canale Play richiede una decisione separata su esenzione batteria e futuro Accessibility.
5. Il futuro autofill credenziali deve tenere vault/secret executor fuori da P4, log e Brain.

## Criterio operativo per non regredire

- nessuna versione bridge può essere usata senza capability negotiation;
- nessuna grammar accettata può avere un runtime noto come impossibile;
- ogni chiamata modello deve passare dal budget metered;
- tool assenti non devono comparire in `available_tools`;
- credenziali vault non devono essere convertite in normali stringhe esportabili;
- i test device devono dimostrare la catena, non soltanto i singoli componenti.
