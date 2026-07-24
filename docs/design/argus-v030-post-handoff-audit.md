# Argus v0.3.0 — audit post-handoff

Data audit: 2026-07-24

Intervallo revisionato: `3f249792de43d6dba7dee0372082087b04c10fb4..5a73ea62f9837f5371c1216fd4f9e57b5bc94c72`

Ampiezza: 31 commit, 97 file, +4.738/-221 righe

## Esito sintetico

La base è in buono stato: build pulita, test verdi, modello P4 e interprete deterministico sono
implementati con una copertura ampia. Tuttavia la dicitura di release “P4 complete” era troppo forte.
Il percorso principale `invoke_llm.captureAs` non funziona attraverso Hermes, alcuni programmi che il
compilatore accetta falliscono intenzionalmente a runtime e restano due incompatibilità di protocollo.

La v0.3.0 è quindi una buona milestone P4, non la chiusura completa di P4. I problemi sotto non
giustificano una riscrittura: richiedono un fix-pass mirato sui contratti fra Kotlin, Hermes e budget.

## Evidenza verificata

- Worktree isolato e pulito, creato da `github/master` al commit della release v0.3.0.
- Gate Gradle completo: 226/226 task, 1.073 test, 0 failure, 0 error, 0 skipped.
- Bridge Hermes: 58/58 test Python verdi.
- APK debug assemblato.
- Android Lint completato senza errori; gli avvisi sono in maggioranza aggiornamenti dipendenze e
  stile, con due rischi di distribuzione/runtime riportati sotto.
- Hash del bridge deployato uguale al file nel repository al momento della verifica.
- Prova diretta del parser Hermes: una richiesta `/act` con `schema_version: 3` riceve
  `409 schema_version_incompatible`.
- Verifica statica dell'intero diff e dei relativi test, non soltanto dei messaggi di commit.

I gate verdi provano le unità e i contratti coperti dai test. Non provano che i due lati di un
protocollo condividano davvero lo stesso schema quando manca un test cross-language.

## Finding

### P0 — il canale P4 risolto non esiste nel server Hermes

**Impatto:** una regola P4 che usa `invoke_llm` con `captureAs` e il bridge Hermes fallisce prima di
raggiungere il modello. Questo rende inutilizzabile tramite Hermes il caso principale
“cattura una risposta del Brain e usala nelle azioni successive”.

**Evidenza:**

- `CliBridgeTransport.ACT_RESOLVED_SCHEMA_VERSION` vale `3` e `actResolved()` invia una busta v3.
- `ops/hermes/bridge.py` dichiara soltanto `ACT_SCHEMA_VERSION = 1`,
  `ACT_V2_SCHEMA_VERSION = 2` e `SUPPORTED_ACT_SCHEMA_VERSIONS = (1, 2)`.
- `validate_act_request()` rifiuta la v3 con HTTP 409.
- La health Hermes annuncia `[1, 2]`, ma il client Android non richiede la v3 nella health:
  configurazione e stato risultano quindi verdi anche se il canale necessario manca.
- I 58 test del bridge non contengono una richiesta `/act` v3.

I transport diretti coperti dai test (`OpenAICompatTransport` e relativi provider) implementano
`actResolved`; il difetto è nel percorso Hermes.

**Fix richiesto:**

1. Definire una sola specifica v3 condivisa.
2. Implementare parser, validazione, idempotenza e risposta v3 in Hermes.
3. Annunciare la v3 in `/health` e renderla requisito quando il client abilita P4 resolved.
4. Aggiungere un golden test cross-language: JSON serializzato da Kotlin, validato dal parser Python.
5. Provare su device reale `captureAs -> confronto -> azione successiva`.

### P1 — `set_dark_mode` usa due enum wire incompatibili

**Impatto:** il modello viene istruito a produrre `off|on|auto`, ma Kotlin deserializza
`NightMode.OFF|ON|AUTO`. Una bozza naturale del modello può quindi diventare `draft_invalid` e
innescare inutilmente il retry.

**Evidenza:**

- `NightMode` non ha `@SerialName`, quindi Kotlin serializza i nomi maiuscoli.
- I prompt Android e Hermes descrivono valori minuscoli.
- `bridge.validate_action()` accetta i minuscoli e un test Python rifiuta esplicitamente `"ON"`.

**Fix richiesto:** scegliere un formato wire canonico e applicarlo a modello Kotlin, prompt,
validator Python e golden round-trip. La soluzione meno distruttiva è aggiungere `@SerialName`
minuscoli all'enum con una migrazione compatibile per eventuali bozze già salvate.

### P1 — il retry di compile può superare il budget HARD e sottostimare il consumo

**Impatto:** quando la prima compile restituisce `draft_invalid`, `TransportBackedBrain` effettua
una seconda chiamata. `MeteredBrain` esegue però un solo controllo pre-call e registra soltanto
l'usage restituito dalla seconda chiamata. Con una sola chiamata residua il limite HARD può essere
superato; costo e token della prima risposta vengono persi.

**Evidenza:**

- `TransportBackedBrain.compile()` scarta l'intero primo `CompileResult` prima del retry.
- `MeteredBrain.compile()` vede il solo risultato finale e persiste un singolo evento.
- I test verificano che il retry avvenga una volta, non aggregazione usage o rispetto del budget.

**Fix richiesto:** spostare il retry dentro il choke-point metered oppure rendere ogni tentativo
esplicito e contabilizzato. Prima del secondo tentativo va rieseguito il gate HARD. L'usage dei due
tentativi deve essere aggregato o registrato come due eventi distinti.

### P1 — il compilatore ammette programmi che il runtime rifiuta

**Impatto:** una regola può essere approvata e armata, ma fallire sempre con
`p4_generative_deliver_unavailable`.

**Casi confermati:**

- `InvokeLlm` dentro un programma P4 senza `captureAs`: consegna reply/notifica non cablata.
- Qualsiasi `InvokeLlmV2` dentro P4, incluso `captureAs`: canale resolved v2 assente.
- Il modello, il prompt e il validator permettono questi alberi.

**Fix richiesto:** o implementare i canali sincroni mancanti, o rifiutare in compile/review le
combinazioni non eseguibili. Non deve esistere un percorso “validato e approvato” che sia noto come
impossibile a runtime.

Va inoltre reso esplicito il profilo di consegna. La decisione di prodotto prevedeva
`CAPTURE_ONLY`, `REPLY` e `NOTIFY`; oggi `CAPTURE_ONLY` è implicito nella presenza di `captureAs`,
mentre l'enum contiene soltanto `WHATSAPP_REPLY` e `LOCAL_NOTIFICATION`. Un campo wire esplicito
riduce le combinazioni ambigue, ma richiede una migrazione additiva e test sui fingerprint.

### P1 sicurezza — `SECRET` è un avviso, non una barriera di egress

**Impatto:** il nome `ConfidentialityLabel.SECRET` può far credere che il dato non possa lasciare il
telefono. In realtà, se una variabile SECRET viene interpolata in `invoke_llm`, il validator produce
solo il warning `secret_interpolation_disclosure`; dopo approvazione il valore viene inviato al Brain
configurato.

La separazione SYSTEM/DATA riduce il rischio di prompt injection, ma non è un sandbox. I valori
runtime possono contenere testo ostile e, nella postura Aggressive, alimentare campi di autorità.
Il programma resta strutturato e fingerprintato, ma un dato ostile inserito in un template shell,
setting o routing può comunque alterarne l'effetto.

**Fix richiesto:**

- rinominare/documentare le label come classificazione e redazione, non non-esportabilità;
- introdurre una policy egress separata per i sink remoti;
- rendere i segreti del futuro credential vault non esportabili per costruzione, fuori dalla normale
  interpolazione P4 e fuori dalla postura Aggressive;
- mostrare in review provider, destinazione e classi di dati esportate.

### P2 — prompt Android e Hermes si contraddicono

La sezione P4 dice che un valore TAINTED può essere usato liberamente in comando, routing e URL.
Sezioni legacy degli stessi prompt continuano invece a dichiarare shell statica, `write_setting`
letterale e divieto di usare state reader in campi di autorità. Il modello riceve quindi istruzioni
incompatibili e può evitare feature valide o produrre retry.

Il testo è duplicato in `AgentMessageSupport` e `ops/hermes/bridge.py`; i test cercano frammenti,
non equivalenza semantica.

**Fix richiesto:** una sorgente canonica/versionata del contratto e test di parità. Fino ad allora,
aggiungere almeno golden fixture condivise e rimuovere le sezioni legacy contraddittorie.

### P2 — `random_int` ha un contratto diverso fra Android e Hermes

Il prompt Android presenta `confidentiality` come campo opzionale del binding `random_int`; il
validator Python richiede invece esattamente `type`, `name` e `max` e rifiuta chiavi extra.

**Fix richiesto:** eliminare il campo dal prompt Android oppure supportarlo in entrambi i parser.
Per un valore generato localmente e non segreto, il default `PUBLIC` è sufficiente.

### P2 sicurezza — `copy_text` non ha scadenza della clipboard

`AndroidClipboardCopier` marca il clip come sensibile, ma non lo cancella. Il flag nasconde
l'anteprima: non garantisce cancellazione immediata né impedisce ogni lettura da IME, servizi o
app compatibili con la versione Android. Con P4, `copy_text` può ricevere anche valori interpolati.

**Fix richiesto:** timeout breve, cancellazione compare-and-clear solo se il clip è ancora quello
creato da Argus, audit privo del contenuto e avviso UI. Per password/TOTP la clipboard deve restare
un fallback eccezionale, non il canale normale.

### P2 — le primitive computer-use esistono, il ciclo osserva-decidi-agisci no

Il manifest espone `screen.capture` e `screen.dump_ui`; `DeviceTools` possiede screenshot, dump,
tap e input. Tuttavia:

- `Tap` e `InputText` terminano con `unsupported_phase`;
- il contratto generativo non autorizza un piano visuale tipato;
- non esistono target binding, verifica post-azione, stop su cambio finestra o loop bounded;
- non esiste un E2E di controllo UI reale.

Questa non è una regressione: sono fondamenta utili. È però scorretto descriverle come controllo
device già disponibile.

### P2 — package visibility può produrre falsi “app non disponibile”

Android Lint segnala `QueryPermissionsNeeded` nel controllo `Intent.resolveActivity()`.
`AndroidBaseActionSurface` e alcune superfici UI usano inoltre `getLaunchIntentForPackage()`, ma il
manifest non dichiara `<queries>`. Da Android 11 la visibilità dei package è filtrata: un'app
installata può quindi risultare non risolvibile nel percorso BASE, incluso l'apertura di Shizuku o
di un'app target. Il percorso privilegiato Shizuku non ha lo stesso limite.

**Fix richiesto:** evitare query preventive quando un Intent esplicito può essere avviato in modo
sicuro, oppure dichiarare soltanto le query realmente necessarie. Aggiungere test device con app
target non precedentemente interagita e con Shizuku installato/non installato.

### P2 distribuzione — richiesta diretta di esenzione batteria

Il manifest dichiara `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` e la UI apre direttamente
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Android Lint segnala che questo uso è soggetto ai
criteri restrittivi del Play Store. È coerente con un'app di automazione distribuita via F-Droid, ma
può bloccare o ritardare una futura release Play se il caso d'uso non viene accettato.

**Fix richiesto:** mantenere il gesto esplicito e una spiegazione chiara; prima di una release Play,
verificare l'idoneità corrente oppure usare la schermata generale delle ottimizzazioni senza
richiedere l'esenzione diretta.

### P2 — evidenza device insufficiente per la chiusura P4

Sono stati verificati su device reale shell capture e un programma random/branch/notification/torch.
Non risultano provati end-to-end:

- Hermes `invoke_llm.captureAs -> condizione/azione` (oggi impossibile per il P0);
- `InvokeLlmV2.captureAs`;
- consegna generativa annidata in P4;
- `set_dark_mode` generato dal compilatore;
- un matrix test su OEM/Android diversi dal device OnePlus usato nello sviluppo.

La copertura JVM è solida, ma non sostituisce questi attraversamenti reali.

## Lavoro valido da conservare

- AST P4 strutturato, senza `eval` o salti arbitrari.
- Interprete deterministico con deadline e loop bounded.
- Binding tipati, definite-assignment e `captureAs` nel fingerprint.
- Revalidation prima e dopo il modello nella lane resolved.
- Gating shell sul trigger reale e su Shizuku pronto.
- UI ricorsiva per review e rendering dei programmi.
- Suite di test ampia e generalmente ben mirata.
- Build F-Droid riproducibile, con artefatti ABI e firma verificata.

## Backlog di completamento consigliato

### Fase A — release stabilization

1. Hermes `/act` v3 + health capability + golden test Kotlin/Python.
2. Enum wire `NightMode` unico e round-trip cross-language.
3. Retry compile contabilizzato per tentativo e rispettoso del budget HARD.
4. Rifiuto anticipato o implementazione delle lane P4 generative oggi impossibili.
5. Contratto di consegna esplicito e migrazione fingerprint testata.
6. Prompt canonico, senza contraddizioni, e parità Android/Hermes.

### Fase B — sicurezza e semantica

1. Separare `confidentiality`, redazione, egress e authority in policy distinte.
2. Review UI che mostri chiaramente cosa lascia il dispositivo.
3. Test ostili su delimiter injection, newline, URL/setting/shell template e dati SECRET.
4. Clipboard con lease breve e compare-and-clear.

### Fase C — completezza device

1. E2E reale del percorso P4 resolved.
2. E2E delle nuove azioni su almeno due famiglie OEM e due versioni Android supportate.
3. Test di process death, revoca Shizuku, cambio provider e bridge aggiornato a metà esecuzione.
4. Test e hardening package-visibility sui percorsi BASE.
5. Verifica delle policy del canale di distribuzione per esenzione batteria e Accessibility.
6. Solo dopo, computer-use tipato con observe/plan/act/verify bounded.

## Criterio di chiusura

P4 può essere dichiarata completa quando ogni grammatica accettata dal compiler ha un percorso
runtime funzionante o viene rifiutata prima dell'approvazione, Android e Hermes condividono golden
contract test, il budget conta ogni chiamata e almeno un E2E reale attraversa
`compile -> approve -> fire -> actResolved -> capture -> branch -> action`.
