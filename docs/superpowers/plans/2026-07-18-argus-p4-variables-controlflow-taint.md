# Argus P4 — Variabili, control-flow e taint tracking (parità Tasker) — 2026-07-18

Direttiva (Lorenzo, D0): potenza Tasker-class senza limiti tecnici artificiali; unico confine
non negoziabile = il contenuto esterno non fidato non può creare o cambiare autorità di
esecuzione. Il taint NON limita le capacità: instrada i valori.

## 1. Il salto concettuale

Oggi una regola è `{trigger → conditions → actions}` con lista di azioni FLAT e valori tutti
LETTERALI approvati byte-per-byte. P4 introduce:

1. **Variabili** — valori dinamici prodotti a runtime (payload del trigger, letture di stato,
   output di azioni) e consumati dalle azioni successive.
2. **Control-flow strutturato** — `if/else` e `while` (mai goto): la combinatoria Tasker.
3. **Taint tracking per-valore** — ogni valore porta la sua provenienza; l'invariante D0 è
   applicata sul FLUSSO, non con divieti a priori sulle capacità.

Il principio architetturale resta: **l'utente approva un PROGRAMMA col fingerprint
byte-stabile; il runtime esegue quel programma deterministicamente**. Le variabili cambiano i
DATI che scorrono nel programma, mai la sua STRUTTURA (niente azioni generate a runtime,
niente eval).

## 2. Modello di dominio (engine-core)

### 2.1 Valori tipizzati, integrità, riservatezza e provenienza

```kotlin
@Serializable enum class VarType { TEXT, NUMBER, BOOLEAN }
@Serializable enum class IntegrityLabel { CLEAN, TAINTED }
@Serializable enum class ConfidentialityLabel { PUBLIC, PRIVATE, SECRET }
@Serializable enum class ValueProvenance {
    LITERAL, DEVICE_STATE, NOTIFICATION, SMS, PHONE, MODEL, SHELL
}

data class VarValue(
    val text: String,
    val type: VarType,
    val integrity: IntegrityLabel,
    val confidentiality: ConfidentialityLabel,
    val provenance: Set<ValueProvenance>,
)
```

Runtime-only: `VarValue` NON è serializzato nella regola (le variabili vivono nello scope di
UNA esecuzione). Nella regola approvata compaiono solo i NOMI e i BINDING. Integrità e
riservatezza sono assi separati, come stabilito dal decision record P3 §4: un reader locale è
`CLEAN` ma può essere `PRIVATE` o `SECRET`. Ogni derivazione propaga il join di integrità e
riservatezza e l'unione della provenienza; regex, parsing, escaping e LLM non declassificano.

### 2.2 Sorgenti di variabili (binding dichiarati e approvati)

```kotlin
@Serializable sealed interface VarBinding {           // discriminator "type"
    val name: String                                   // ^[a-z][a-z0-9_]{0,31}$, unico nella regola
    @Serializable @SerialName("trigger_payload")      // testo SMS/notifica, numero chiamante…
    data class TriggerPayload(override val name: String, val field: TriggerField,
                              val extractionRegex: String? = null,
                              val confidentiality: ConfidentialityLabel) : VarBinding
                                                       // integrity = TAINTED; floor PRIVATE
    @Serializable @SerialName("state")                // riusa StateQuerySpec di P3 (famiglie chiuse)
    data class State(override val name: String, val query: StateQuery,
                     val valueType: StateValueType, val policyVersion: Int,
                     val confidentiality: ConfidentialityLabel) : VarBinding
                                                       // integrity = CLEAN; floor P3 invariato
    @Serializable @SerialName("literal")
    data class Literal(override val name: String, val value: String,
                       val varType: VarType,
                       val confidentiality: ConfidentialityLabel) : VarBinding // integrity = CLEAN
}
```

Più i **binding di output**: alcune azioni possono dichiarare `captureAs: String?`
(@EncodeDefault(NEVER) per compat fingerprint) — `invoke_llm` v1/v2 (output `TAINTED`,
provenienza `MODEL`) e `run_shell` (stdout `TAINTED`, provenienza `SHELL`). La riservatezza
dell'output è almeno il join degli input e del floor del produttore. L'elenco delle azioni
catturabili è CHIUSO.

`TriggerField` è validato contro la famiglia concreta: Notification espone `TEXT`, `TITLE` e
`SENDER`; SMS espone `TEXT` e `NUMBER`; chiamata espone solo `NUMBER`. `SENDER` è sempre il nome
visuale, mai `conversationId`: quest'ultimo resta un capability token non serializzabile. Se
presente, `extractionRegex` usa RE2/J e restituisce il primo gruppo o l'intero match.

### 2.3 Control-flow strutturato

Nuovi sottotipi di `Action` (sealed ⇒ i `when` esaustivi impongono ogni innesto a compile-time):

```kotlin
@Serializable @SerialName("if")
data class If(val condition: FlowCondition, val then: List<Action>,
              val orElse: List<Action> = emptyList()) : Action

@Serializable @SerialName("while")
data class While(val condition: FlowCondition, val body: List<Action>,
                 val maxIterations: Int,                  // OBBLIGATORIO, 1..1000
                 val delayBetweenMs: Long = 0) : Action   // 0..3_600_000
```

`FlowCondition` = le `Condition` esistenti + confronto su variabili:

```kotlin
@Serializable @SerialName("var_compare")
data class VarCompare(val varName: String, val op: CmpOp,
                      val expected: String? = null, val expectedVar: String? = null) : Condition
```

Sul wire `expected` ed `expectedVar` sono alternativi e ne deve comparire esattamente uno. I tipi
devono coincidere: `CONTAINS` solo TEXT, `GT/LT` solo NUMBER, `EQ/NEQ` sul tipo dichiarato. Una
`BooleanLiteral(value)` chiusa completa il dominio di `FlowCondition` e rende rappresentabile
direttamente `while(true)` senza variabili fittizie.

La torcia-lampeggiante di Lorenzo diventa: `while (true, maxIterations=20, delayBetweenMs=500)
{ set_flashlight(on), set_flashlight(off) }` — banale, come voleva lui.

### 2.4 Interpolazione `${var}` nei campi azione

- Sintassi: `${nome}` dentro i campi TESTO delle azioni. Parser lineare, niente espressioni.
- **Allowlist statica per campo** (vocabolario chiuso in `InterpolationPolicy`): ogni campo di
  ogni azione è classificato:
  - `SINK` (accetta anche `TAINTED`): `show_notification.title/text`,
    `whatsapp_reply.text` (stessa chat verificata), `invoke_llm.goal` — il tainted entra come
    DATO delimitato (il runtime lo passa già oggi con cleanUntrusted; resta così),
    `invoke_llm.notificationTitle`, `set_alarm.label` e `set_timer.label`; `vibrate` e
    `set_flashlight` non hanno testo.
  - `AUTHORITY` (accetta solo valori con integrità `CLEAN`): `run_shell.cmd`, `write_setting.*`,
    `launch_app.pkg`, `open_url.url`, `open_settings_screen.*`, `tap`/`input_text`
    (input verso altre app = autorità), qualsiasi campo di routing/target. Questo segue la matrice
    P3 §4.2: `CLEAN` sì, `TAINTED` no; un binding letterale CLEAN resta integralmente nel
    fingerprint. Campi non catalogati falliscono chiuso.
- Enforcement DOPPIO: statico nel `DraftValidator` (var `TAINTED` in campo AUTHORITY = errore,
  campo non catalogato = errore, var non dichiarata/non sicuramente assegnata = errore) e dinamico
  nel runtime (l'interpolatore rifiuta `TAINTED` dove non ammesso: fail-closed, audita
  `BLOCKED_POLICY` con reason `taint_blocked`). Il fingerprint copre il TEMPLATE approvato.

### 2.5 Bound del validator (chiusi, non negoziabili)

- ≤ 16 variabili per regola contando binding iniziali e `captureAs`; nomi unici; regex nome come
  sopra.
- Profondità di annidamento if/while ≤ 4; ≤ 64 azioni TOTALI (albero appiattito).
- `while.maxIterations` 1..1000; `delayBetweenMs` 0..3_600_000; budget tempo TOTALE
  dell'esecuzione (somma sleep + azioni) ≤ 6h, verificato staticamente sul worst-case.
- ≤ 64 condizioni TOTALI fra gate trigger-time e control-flow.
- `VarCompare` su var sicuramente assegnata; analisi sequenziale dei rami: un capture diventa
  disponibile dopo l'azione che lo produce, l'uscita di un `if` è l'intersezione dei due rami e
  un capture prodotto solo nel corpo di un `while` non è disponibile dopo il loop.
- Operatori e RHS di `VarCompare` devono essere compatibili col `VarType` dichiarato.
- Niente `captureAs` fuori dall'elenco chiuso delle azioni catturabili.
- Il preflight dell'albero è iterativo: profondità e conteggi vengono verificati prima di qualunque
  visita ricorsiva, così un oggetto ostile non causa stack overflow nel validator.

### 2.6 Schema automazione, materiale fingerprint e compatibilità

- Le tre versioni dell'ADR restano indipendenti. P4 introduce `automation_schema_version = 2`
  e `fingerprint_material_version = 2`; il protocollo bridge non cambia finché non arriva P4-D.
- L'app continua a leggere/eseguire regole legacy schema v1. Una regola che usa `vars`, control-flow
  o `captureAs` DEVE dichiarare schema v2; una combinazione feature/versione incoerente fallisce
  chiuso e va in review.
- Nuovi sottotipi sealed (If/While/VarCompare/VarBinding) NON cambiano i byte delle regole v1
  esistenti ⇒ `V1FingerprintCompatibilityTest` resta verde per costruzione.
- Campi nuovi su azioni esistenti (`captureAs`) ⇒ `@EncodeDefault(NEVER)`.
- `ApprovalFingerprints` usa il prefisso materiale v1 per regole legacy e v2 per programmi P4,
  senza riscrivere gli hash già approvati. Il fixture-test va esteso con una regola P4 pinnata.

## 3. Esecuzione (interprete deterministico)

- Nuovo `ProgramInterpreter` in engine-core: cammina l'albero, valuta FlowCondition su uno
  `VarScope` mutabile per-esecuzione, delega le azioni foglia all'`ActionRunner` astratto già
  esistente (nessun cambiamento ai runner Android per il flusso semplice).
- `while`: ri-valuta la condizione a ogni iterazione con stato AGGIORNATO (per `state`-based:
  ri-lettura via reader; per var: scope corrente). Contatore iterazioni + deadline dura.
- Cancellazione: disable/delete della regola interrompe il programma al bordo dell'azione
  corrente (cooperative), con audit `ERROR`/`BLOCKED_POLICY` reason `cancelled`.
- Journal: ogni azione foglia journalata come oggi (actionIndex → percorso nell'albero, es.
  "2.while[3].1" per la 2ª azione della 3ª iterazione), così il Log resta leggibile.
- L'esecuzione lunga (while con delay) vive nella lane FGS già esistente per i lavori lunghi
  (riusare l'infrastruttura della lane generativa/FGS sensori — da verificare in P4-C).

## 4. Compile (prompt) e bridge

- Schema draft esteso: `vars: [VarBinding]` (opzionale) + azioni `if`/`while` + `${var}` nei
  campi SINK + `captureAs`. Prompt: spiegare la POLITICA di interpolazione (dove si può) con
  esempi ("every 30 min while battery < 20 flash the torch" → while + state var).
- `bridge.py validate_action`: rami `if`/`while` ricorsivi con gli stessi bound del validator;
  `validate_draft` valida `vars`. Fail-closed speculare all'app.
- Regola prompt anti-injection INVARIATA: il contenuto del messaggio resta un interruttore;
  ora può anche RIEMPIRE variabili, che però entrano solo nei campi SINK.

## 5. UI

- `RuleRenderMapper`: rendering ricorsivo indentato di if/while ("Ripeti max 20 volte ogni
  0,5 s: …"), variabili mostrate come `${nome}` col badge della provenienza (esterno/dispositivo/
  letterale) — l'utente DEVE vedere in approvazione dove finisce il contenuto esterno.
- Approvazione: nessun cambiamento di flusso — il programma è nel fingerprint come tutto il resto.

## 6. Fasi di consegna (ognuna: verde+committata o revertata)

| Fase | Contenuto | Moduli |
|---|---|---|
| **P4-A** | modello (VarBinding/If/While/VarCompare/label/provenance/InterpolationPolicy) + DraftValidator + schema/fingerprint compat + persistenza Room | engine-core, data |
| **P4-B** | ProgramInterpreter + VarScope + interpolatore taint-aware + journal path | engine-core |
| **P4-C** | wiring runtime Android (ActionRunner, FGS per esecuzioni lunghe, cancellazione) | automation-android |
| **P4-D** | compile prompt (app EN + bridge) + validate bridge + verifica live | brain-android, ops/hermes |
| **P4-E** | rendering UI ricorsivo + badge provenienza (bilingue, post-i18n) | ui |
| **P4-F** | E2E device: torcia-lampeggiante, "while battery<20", capture invoke_llm → notifica | — |

P4-A e P4-B sono JVM-pure e testabili in millisecondi: partono stanotte. P4-E dipende
dall'i18n in corso (stessi file) e si accoda.
