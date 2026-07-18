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

### 2.1 Valori tipizzati e taint

```kotlin
@Serializable enum class VarType { TEXT, NUMBER, BOOLEAN }

/** Provenienza di un valore. UNTAINTED = solo letterali approvati e derivazioni pure di essi. */
@Serializable enum class Taint { UNTAINTED, DEVICE, TAINTED }
// DEVICE = letture di stato locale (battery, ssid…): non spoofabile da remoto ma non approvato
//          byte-per-byte. TAINTED = contenuto esterno o generato (SMS/notifiche/web/LLM/shell-out).
// Ordine: UNTAINTED < DEVICE < TAINTED; una derivazione prende il max degli ingressi.

data class VarValue(val text: String, val type: VarType, val taint: Taint)
```

Runtime-only: `VarValue` NON è serializzato nella regola (le variabili vivono nello scope di
UNA esecuzione). Nella regola approvata compaiono solo i NOMI e i BINDING.

### 2.2 Sorgenti di variabili (binding dichiarati e approvati)

```kotlin
@Serializable sealed interface VarBinding {           // discriminator "type"
    val name: String                                   // ^[a-z][a-z0-9_]{0,31}$, unico nella regola
    @Serializable @SerialName("trigger_payload")      // testo SMS/notifica, numero chiamante…
    data class TriggerPayload(override val name: String, val field: TriggerField,
                              val regexGroup: String? = null) : VarBinding   // taint = TAINTED
    @Serializable @SerialName("state")                // riusa StateQuerySpec di P3 (famiglie chiuse)
    data class State(override val name: String, val query: StateQuerySpec) : VarBinding
                                                       // taint = DEVICE
    @Serializable @SerialName("literal")
    data class Literal(override val name: String, val value: String,
                       val varType: VarType = VarType.TEXT) : VarBinding     // taint = UNTAINTED
}
```

Più i **binding di output**: alcune azioni possono dichiarare `captureAs: String?`
(@EncodeDefault(NEVER) per compat fingerprint) — es. `invoke_llm.captureAs` (output → TAINTED),
`run_shell.captureAs` (stdout → TAINTED). L'elenco delle azioni catturabili è CHIUSO.

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
data class VarCompare(val varName: String, val op: CmpOp, val expected: String,
                      val expectedVar: String? = null) : Condition
```

La torcia-lampeggiante di Lorenzo diventa: `while (true, maxIterations=20, delayBetweenMs=500)
{ set_flashlight(on), set_flashlight(off) }` — banale, come voleva lui.

### 2.4 Interpolazione `${var}` nei campi azione

- Sintassi: `${nome}` dentro i campi TESTO delle azioni. Parser lineare, niente espressioni.
- **Allowlist statica per campo** (vocabolario chiuso in `InterpolationPolicy`): ogni campo di
  ogni azione è classificato:
  - `SINK` (accetta anche TAINTED): `show_notification.title/text`, `copy_to_clipboard.text`,
    `whatsapp_reply.text` (stessa chat verificata), `invoke_llm.goal` — il tainted entra come
    DATO delimitato (il runtime lo passa già oggi con cleanUntrusted; resta così),
    `vibrate`/`set_flashlight` non hanno testo.
  - `DEVICE_OK` (accetta UNTAINTED e DEVICE, mai TAINTED): `set_volume.level`? no — numerici
    restano letterali in P4-A; `open_url.url` con var DEVICE? no: url = autorità. Vuoto per ora.
  - `AUTHORITY` (SOLO letterali, MAI interpolazione): `run_shell.cmd`, `write_setting.*`,
    `launch_app.pkg`, `open_url.url`, `set_alarm.*`, `open_settings_screen.*`, `tap`/`input_text`
    (input verso altre app = autorità), qualsiasi campo di routing/target.
- Enforcement DOPPIO: statico nel `DraftValidator` (un `${…}` in un campo AUTHORITY = errore,
  in un campo non in allowlist = errore, var non dichiarata = errore) e dinamico nel runtime
  (l'interpolatore rifiuta TAINTED/DEVICE dove non ammesso: fail-closed, audita
  `BLOCKED_POLICY` con reason `taint_blocked`). Il fingerprint copre il TEMPLATE approvato.

### 2.5 Bound del validator (chiusi, non negoziabili)

- ≤ 16 variabili per regola; nomi unici; regex nome come sopra.
- Profondità di annidamento if/while ≤ 4; ≤ 64 azioni TOTALI (albero appiattito).
- `while.maxIterations` 1..1000; `delayBetweenMs` 0..3_600_000; budget tempo TOTALE
  dell'esecuzione (somma sleep + azioni) ≤ 6h, verificato staticamente sul worst-case.
- `VarCompare` su var dichiarata; op numerici solo se `VarType.NUMBER` (coercizione esplicita).
- Niente `captureAs` fuori dall'elenco chiuso delle azioni catturabili.

### 2.6 Fingerprint & compat

- Nuovi sottotipi sealed (If/While/VarCompare/VarBinding) NON cambiano i byte delle regole
  esistenti ⇒ `V1FingerprintCompatibilityTest` resta verde per costruzione.
- Campi nuovi su azioni esistenti (`captureAs`) ⇒ `@EncodeDefault(NEVER)`.
- Il fixture-test di compat va ESTESO con una regola P4 pinnata (nuovo hash, additivo).

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
| **P4-A** | modello (VarBinding/If/While/VarCompare/Taint/InterpolationPolicy) + DraftValidator + serializzazione + fingerprint-compat pinnata | engine-core |
| **P4-B** | ProgramInterpreter + VarScope + interpolatore taint-aware + journal path | engine-core |
| **P4-C** | wiring runtime Android (ActionRunner, FGS per esecuzioni lunghe, cancellazione) | automation-android |
| **P4-D** | compile prompt (app EN + bridge) + validate bridge + verifica live | brain-android, ops/hermes |
| **P4-E** | rendering UI ricorsivo + badge provenienza (bilingue, post-i18n) | ui |
| **P4-F** | E2E device: torcia-lampeggiante, "while battery<20", capture invoke_llm → notifica | — |

P4-A e P4-B sono JVM-pure e testabili in millisecondi: partono stanotte. P4-E dipende
dall'i18n in corso (stessi file) e si accoda.
