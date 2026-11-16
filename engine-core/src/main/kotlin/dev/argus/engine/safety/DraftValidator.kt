package dev.argus.engine.safety

import dev.argus.engine.model.*
import dev.argus.engine.runtime.CalendarTriggerPlanner
import dev.argus.engine.runtime.CronSchedule
import dev.argus.engine.runtime.ProgramInterpreter
import java.net.URI
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

enum class Severity { ERROR, WARNING }
/**
 * [code] è il vocabolario chiuso mostrato in UI (testo localizzato: `ValidationMessages`); [message]
 * è solo il testo diagnostico in italiano. [detail] porta, quando serve, il dato che il codice da
 * solo non dice (per esempio la capability mancante), in forma già sicura da mostrare.
 */
data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val detail: String? = null,
)

/**
 * Gate di dominio per i draft LLM. ERROR blocca l'arm; WARNING è mostrato in approvazione.
 *
 * [whitelistedIds] è intenzionalmente obbligatoria: un caller che dimentica la policy destinatari
 * non deve ottenere implicitamente una whitelist aperta. Per draft senza reply passare [emptySet].
 */
class DraftValidator(
    private val knownTools: Set<String>,
    private val stateKeys: Set<String> = StateKeys.ALL.keys,
    private val deniedNotificationPackages: Set<String> = setOf(ARGUS_PACKAGE),
    /** Solo per il controllo `cron_never_fires`: un cron deve avere uno scatto nell'orizzonte da adesso. */
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        val FORBIDDEN_IN_INVOKE_LLM = setOf("shell.run", "app.install")
        const val FORBIDDEN_PREFIX = "automation."
        const val FORBIDDEN_PREFIX_BARE = "automation"

        val WHATSAPP_PACKAGES = ReplyChannels.WHATSAPP_PACKAGES
        val REPLY_TOOLS = setOf("whatsapp_reply")
        const val ARGUS_PACKAGE = "dev.argus"

        private const val MAX_NAME_LENGTH = 120
        private const val MAX_CONDITIONS = 64
        private const val MAX_CONDITION_DEPTH = 8
        private const val MAX_TEXT_LENGTH = 4_000
        private const val MAX_COMMAND_LENGTH = 8_192
        private const val MAX_TOOL_COUNT = 32
        private const val MAX_GEOFENCE_RADIUS_M = 100_000.0
        private const val MAX_LOITERING_MS = 86_400_000L
        private const val MAX_LLM_TIMEOUT_MS = 120_000L

        // 0.8: condizioni di contesto e calendario. Un SSID è al massimo 32 byte, un nome Bluetooth 248.
        private const val MAX_ZONE_ID_CHARS = 64
        private const val MAX_SSID_CHARS = 32
        private const val MAX_BLUETOOTH_DEVICE_CHARS = 248
        private const val MAX_CALENDAR_FILTER_CHARS = 200

        /** Ritardo relativo one-shot (Trigger.Time.afterMs): da 1 secondo a 7 giorni. */
        const val MIN_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_TIMER_SECONDS = 86_400
        private const val MAX_VOLUME_LEVEL = 100
        private const val MAX_VIBRATE_MS = 10_000
        private const val MAX_SPEAK_LENGTH = 1_000
        private const val MAX_BRIGHTNESS_LEVEL = 100

        // Stime worst-case 0.4.1 per il budget tempo: la richiesta HTTP ha il suo timeout (15 s), la
        // lettura ad alta voce aspetta la fine del parlato fino a 60 s.
        private const val HTTP_ACTION_BUDGET_MS = HttpRequestPolicy.TIMEOUT_MS
        private const val SPEAK_ACTION_BUDGET_MS = 60_000L

        // --- Bound P4 §2.5 (chiusi, non negoziabili) ---
        private const val MAX_VARS = 16
        private const val MAX_RANDOM_INT = 1_000_000
        private const val MAX_FLOW_DEPTH = 4
        private const val MAX_TOTAL_ACTIONS = 64
        private const val MIN_WHILE_ITERATIONS = 1
        private const val MAX_WHILE_ITERATIONS = 1_000
        private const val MAX_WHILE_DELAY_MS = 3_600_000L
        private const val MAX_WAIT_MS = 3_600_000L
        private const val MAX_TIME_BUDGET_MS = 6L * 60 * 60 * 1_000 // 6 ore

        // Stime worst-case per il budget tempo statico (§2.5): dominato dai delay dei while, ma
        // le azioni lunghe (shell, generative) contribuiscono col loro tetto operativo.
        private const val SHELL_ACTION_BUDGET_MS = 30_000L // limite operativo shell 30 s
        private const val LEAF_ACTION_BUDGET_MS = 1_000L // nominale per azioni deterministiche brevi

        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }

    fun validate(d: AutomationDraft, whitelistedIds: Set<String>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun err(code: String, msg: String) { issues += ValidationIssue(Severity.ERROR, code, msg) }
        fun warn(code: String, msg: String) { issues += ValidationIssue(Severity.WARNING, code, msg) }
        fun errDetail(code: String, msg: String, detail: String) {
            issues += ValidationIssue(Severity.ERROR, code, msg, detail)
        }

        if (d.name.isBlank() || d.name.length > MAX_NAME_LENGTH)
            err("name_invalid", "Nome regola obbligatorio e lungo al massimo $MAX_NAME_LENGTH caratteri")
        if (d.actions.isEmpty()) err("no_actions", "Il draft non contiene azioni")
        if (d.cooldownMs < 0) err("cooldown_invalid", "Cooldown non può essere negativo")

        // 0.8 scaffold: un tipo o un campo dietro un FeatureGate spento non si arma mai. Un errore per
        // interruttore, col suo nome nel dettaglio (vedi FeatureGates).
        FeatureGates.disabledIn(d).forEach { gate ->
            errDetail("not_available_yet", "Non disponibile in questa versione", gate.wireName)
        }

        validateTrigger(d.trigger, ::err, ::warn)
        if (d.trigger is Trigger.Sensor &&
            d.cooldownMs !in SensorTriggerPolicy.MIN_COOLDOWN_MS..SensorTriggerPolicy.MAX_COOLDOWN_MS
        ) {
            err(
                "sensor_cooldown_invalid",
                "I trigger sensore richiedono un cooldown tra " +
                    "${SensorTriggerPolicy.MIN_COOLDOWN_MS} e ${SensorTriggerPolicy.MAX_COOLDOWN_MS} ms",
            )
        }

        var conditionCount = 0
        fun countCondition() = ++conditionCount
        // VarCompare NON è ammesso fra le condizioni trigger-time (le var non esistono ancora lì).
        checkConditions(d.conditions, 1, ::countCondition, ::err, allowVarCompare = false)

        // --- P4 §2.5: variabili, capture, control-flow, interpolazione, budget ---
        validateVars(d.vars, d.trigger, ::err)
        val tree = preflightActions(d.actions, ::err)
        if (AutomationSchema.versionFor(d) == AUTOMATION_SCHEMA_VERSION_P4 && tree.hasInvokeLlmV2) {
            err(
                "p4_invoke_llm_v2_unsupported",
                "InvokeLlm v2 non è ancora ammesso nei programmi P4; usa invoke_llm o una regola flat",
            )
        }
        validateCaptureNames(tree.captures, d.vars, ::err)
        if (d.vars.size + tree.captures.size > MAX_VARS) {
            err("too_many_vars", "Massimo $MAX_VARS variabili per regola, inclusi i captureAs")
        }
        val knownVars = d.vars.mapTo(mutableSetOf()) { it.name }.apply {
            addAll(tree.captures.map { it.name })
        }
        val declaredTypes = buildMap<String, VarType> {
            d.vars.forEach { put(it.name, it.declaredType) }
            tree.captures.forEach { putIfAbsent(it.name, it.type) }
        }
        val declaredIntegrity = buildMap<String, IntegrityLabel> {
            d.vars.forEach { put(it.name, it.integrity) }
            tree.captures.forEach { putIfAbsent(it.name, it.integrity) }
        }
        val declaredConfidentiality = buildMap<String, ConfidentialityLabel> {
            d.vars.forEach { put(it.name, it.confidentiality) }
            tree.captures.forEach { putIfAbsent(it.name, it.confidentiality) }
        }

        if (tree.totalActions > MAX_TOTAL_ACTIONS)
            err("too_many_actions_total", "Massimo $MAX_TOTAL_ACTIONS azioni totali nell'albero appiattito")

        if (!tree.tooDeep && tree.totalActions <= MAX_TOTAL_ACTIONS) {
            walkActions(
                actions = d.actions,
                trigger = d.trigger,
                whitelist = whitelistedIds,
                knownVars = knownVars,
                declaredTypes = declaredTypes,
                declaredIntegrity = declaredIntegrity,
                declaredConfidentiality = declaredConfidentiality,
                availableVars = d.vars.mapTo(mutableSetOf()) { it.name },
                depth = 0,
                countCondition = ::countCondition,
                err = ::err,
                warn = ::warn,
                errDetail = ::errDetail,
            )
            if (worstCaseBudgetMs(d.actions) > MAX_TIME_BUDGET_MS)
                err(
                    "time_budget_exceeded",
                    "Budget tempo worst-case oltre 6 ore: riduci maxIterations/delay o le azioni lunghe",
                )
        }

        if (conditionCount > MAX_CONDITIONS)
            err("conditions_too_many", "Massimo $MAX_CONDITIONS condizioni per regola")

        if (tree.hasGenerative && d.cooldownMs < 60_000)
            warn("cooldown_raised", "Cooldown sotto 60 s su regola generativa: l'engine imporrà 60 s")
        if (tree.hasGenerativeInLoop)
            warn(
                "generative_in_loop",
                "Chiamata AI dentro un ciclo: può ripetersi a ogni giro, fino a " +
                    "${ProgramInterpreter.MAX_GENERATIVE_CALLS_PER_EXECUTION} chiamate per scatto",
            )

        return issues
    }

    // ---------------------------------------------------------------------------------------------
    // P4: variabili, control-flow, interpolazione (§2.5)
    // ---------------------------------------------------------------------------------------------

    /** Valida i binding, incluse classificazioni P3 e matrice trigger-field chiusa. */
    private fun validateVars(vars: List<VarBinding>, trigger: Trigger, err: (String, String) -> Unit) {
        if (vars.size > MAX_VARS) err("too_many_vars", "Massimo $MAX_VARS variabili per regola")
        val names = vars.map { it.name }
        names.forEach {
            if (!VarBinding.NAME_REGEX.matches(it)) err("var_name_invalid", "Nome variabile '$it' non valido")
        }
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            err("var_name_duplicate", "Nome variabile duplicato: '$it'")
        }
        vars.forEach { binding ->
            when (binding) {
                is VarBinding.State -> {
                    if (!StateQueryPolicy.validQuery(binding.query, stateKeys)) {
                        err("var_state_query_invalid", "Reader di stato o parametri non validi per '${binding.name}'")
                    }
                    if (binding.policyVersion != StateQueryPolicy.VERSION) {
                        err("var_state_policy_incompatible", "Policy reader non compatibile per '${binding.name}'")
                    }
                    if (!StateContextClassification.validValueType(binding.query, binding.valueType)) {
                        err("var_state_type_invalid", "Tipo dichiarato non valido per '${binding.name}'")
                    }
                    val minimum = StateContextClassification.minimumConfidentiality(binding.query)
                    if (!StateContextClassification.covers(binding.confidentiality, minimum)) {
                        err(
                            "var_state_underclassified",
                            "Reader '${binding.name}' classificato sotto il minimo ${minimum.name}",
                        )
                    }
                }
                is VarBinding.RandomInt -> {
                    // Intero casuale generato dal motore, CLEAN: serve solo un bound sano. max in
                    // [0, max) ⇒ max >= 1; tetto ragionevole per evitare conteggi assurdi.
                    if (binding.max < 1 || binding.max > MAX_RANDOM_INT) {
                        err(
                            "var_random_max_invalid",
                            "random_int '${binding.name}' richiede max tra 1 e $MAX_RANDOM_INT",
                        )
                    }
                }
                is VarBinding.Literal -> {
                    if (binding.value.length > MAX_TEXT_LENGTH || binding.value.any(Char::isISOControl)) {
                        err("var_literal_invalid", "Letterale '${binding.name}' troppo lungo o con caratteri di controllo")
                    }
                    when (binding.varType) {
                        VarType.NUMBER -> if (binding.value.toDoubleOrNull()?.isFinite() != true)
                            err("var_literal_type_invalid", "Letterale NUMBER '${binding.name}' non numerico")
                        VarType.BOOLEAN -> if (binding.value !in setOf("true", "false"))
                            err("var_literal_type_invalid", "Letterale BOOLEAN '${binding.name}' deve essere true/false")
                        VarType.TEXT -> Unit
                    }
                }
                is VarBinding.TriggerPayload -> {
                    val fields = triggerPayloadFields(trigger)
                    if (fields.isEmpty()) {
                        err(
                            "var_trigger_payload_unsupported",
                            "La variabile '${binding.name}' legge il payload del trigger, ma questo trigger non ne ha",
                        )
                    } else if (binding.field !in fields) {
                        err(
                            "var_trigger_field_unsupported",
                            "Il campo ${binding.field.name} non esiste per questo trigger ('${binding.name}')",
                        )
                    }
                    if (!StateContextClassification.covers(
                            binding.confidentiality,
                            ConfidentialityLabel.PRIVATE,
                        )
                    ) {
                        err(
                            "var_trigger_underclassified",
                            "Il payload esterno '${binding.name}' deve essere almeno PRIVATE",
                        )
                    }
                    binding.extractionRegex?.let { pattern ->
                        if (pattern.isBlank() || !SafeExtractionRegex.isValid(pattern)) {
                            err(
                                "var_extraction_regex_invalid",
                                "extractionRegex non sicura/compatibile RE2 per '${binding.name}'",
                            )
                        }
                    }
                }
                is VarBinding.ClockTime -> {
                    // Ora allo scatto (0.5.0): il fuso deve esistere (ZoneId.of) e il formato deve essere
                    // accettato da DateTimeFormatter E applicabile a un istante con fuso (formattazione di prova).
                    binding.tz?.let { tz ->
                        if (ClockVariable.parseZone(tz) == null) {
                            err(
                                "var_clock_tz_invalid",
                                "clock '${binding.name}': fuso orario non valido (id IANA, es. Europe/Rome, max ${ClockVariable.MAX_TZ_CHARS} caratteri)",
                            )
                        }
                    }
                    binding.pattern?.let { pattern ->
                        if (ClockVariable.parseFormatter(pattern, Locale.ENGLISH) == null) {
                            err(
                                "var_clock_pattern_invalid",
                                "clock '${binding.name}': formato non valido (DateTimeFormatter, 1..${ClockVariable.MAX_PATTERN_CHARS} caratteri)",
                            )
                        }
                    }
                }
                // 0.8 scaffold: spento (not_available_yet). Chi lo accende valida qui chiave e riservatezza.
                is VarBinding.Stored -> Unit
            }
        }
    }

    private fun triggerPayloadFields(trigger: Trigger): Set<TriggerField> = when (trigger) {
        is Trigger.Notification -> setOf(TriggerField.TEXT, TriggerField.TITLE, TriggerField.SENDER)
        is Trigger.PhoneState -> when (trigger.event) {
            PhoneEvent.SMS_RECEIVED -> setOf(TriggerField.TEXT, TriggerField.NUMBER)
            PhoneEvent.INCOMING_CALL, PhoneEvent.CALL_ENDED, PhoneEvent.MISSED_CALL -> setOf(TriggerField.NUMBER)
        }
        // 0.8: il titolo dell'evento (dato esterno: un invito lo scrive un altro).
        is Trigger.CalendarEvent -> setOf(TriggerField.TITLE)
        else -> emptySet()
    }

    private data class CaptureDeclaration(
        val name: String,
        val type: VarType = VarType.TEXT,
        val integrity: IntegrityLabel = IntegrityLabel.TAINTED,
        val confidentiality: ConfidentialityLabel,
    )

    private data class ActionFrame(val action: Action, val depth: Int, val inLoop: Boolean = false)
    private data class ActionTreeSummary(
        val totalActions: Int,
        val captures: List<CaptureDeclaration>,
        val tooDeep: Boolean,
        val hasGenerative: Boolean,
        val hasInvokeLlmV2: Boolean,
        /** Una chiamata AI dentro un `while`: può partire una volta per giro (tetto per esecuzione). */
        val hasGenerativeInLoop: Boolean = false,
    )

    /** Preflight iterativo: nessuna ricorsione prima del bound profondità/numero nodi. */
    private fun preflightActions(
        actions: List<Action>,
        err: (String, String) -> Unit,
    ): ActionTreeSummary {
        val pending = ArrayDeque<ActionFrame>()
        actions.forEach { pending.addLast(ActionFrame(it, 0)) }
        val captures = mutableListOf<CaptureDeclaration>()
        var total = 0
        var tooDeep = false
        var hasGenerative = false
        var hasInvokeLlmV2 = false
        var hasGenerativeInLoop = false
        while (pending.isNotEmpty()) {
            val (action, depth, inLoop) = pending.removeFirst()
            total = (total + 1).coerceAtMost(MAX_TOTAL_ACTIONS + 1)
            if (depth > MAX_FLOW_DEPTH) {
                if (!tooDeep) err("flow_too_deep", "Annidamento if/while oltre profondità $MAX_FLOW_DEPTH")
                tooDeep = true
                continue
            }
            captureDeclaration(action)?.let(captures::add)
            when (action) {
                is Action.InvokeLlm -> {
                    hasGenerative = true
                    if (inLoop) hasGenerativeInLoop = true
                }
                is Action.InvokeLlmV2 -> {
                    hasGenerative = true
                    hasInvokeLlmV2 = true
                    if (inLoop) hasGenerativeInLoop = true
                }
                is Action.If -> {
                    action.then.forEach { pending.addLast(ActionFrame(it, depth + 1, inLoop)) }
                    action.orElse.forEach { pending.addLast(ActionFrame(it, depth + 1, inLoop)) }
                }
                is Action.While -> action.body.forEach { pending.addLast(ActionFrame(it, depth + 1, inLoop = true)) }
                else -> Unit
            }
        }
        return ActionTreeSummary(total, captures, tooDeep, hasGenerative, hasInvokeLlmV2, hasGenerativeInLoop)
    }

    private fun captureDeclaration(action: Action): CaptureDeclaration? = when (action) {
        is Action.RunShell -> action.captureAs?.let {
            CaptureDeclaration(it, confidentiality = ConfidentialityLabel.SECRET)
        }
        is Action.InvokeLlm -> action.captureAs?.let {
            // Il goal può interpolare qualunque sink, incluso un valore SECRET: fino al join
            // runtime P4-B il floor statico conservativo dell'output del modello è SECRET.
            CaptureDeclaration(it, confidentiality = ConfidentialityLabel.SECRET)
        }
        is Action.InvokeLlmV2 -> action.captureAs?.let {
            CaptureDeclaration(it, confidentiality = ConfidentialityLabel.SECRET)
        }
        // 0.8 scaffold: la scelta dell'utente è una delle etichette approvate, ma finché `ask` è spento
        // resta TAINTED (default fail-closed); chi la accende decide se può essere CLEAN.
        is Action.Ask -> CaptureDeclaration(action.captureAs, confidentiality = ConfidentialityLabel.PRIVATE)
        // 0.8 scaffold: la risposta di un server esterno è TAINTED; SECRET come l'output shell, perché la
        // richiesta può portare intestazioni segrete.
        is Action.HttpRequest -> action.captureAs?.let {
            CaptureDeclaration(it, confidentiality = ConfidentialityLabel.SECRET)
        }
        else -> null
    }

    /** captureAs: nome valido, unico fra loro e non in collisione con i binding (§2.5). */
    private fun validateCaptureNames(
        captures: List<CaptureDeclaration>,
        vars: List<VarBinding>,
        err: (String, String) -> Unit,
    ) {
        captures.forEach { capture ->
            if (!VarBinding.NAME_REGEX.matches(capture.name))
                err("capture_name_invalid", "captureAs '${capture.name}' non valido")
        }
        captures.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.forEach {
            err("capture_name_duplicate", "captureAs duplicato: '$it'")
        }
        val bindingNames = vars.mapTo(mutableSetOf()) { it.name }
        captures.map { it.name }.filter { it in bindingNames }.toSet().forEach {
            err("capture_name_duplicate", "captureAs '$it' collide con un binding omonimo")
        }
    }

    /**
     * Cammina l'albero delle azioni: profondità di annidamento (≤4), validazione per-azione (foglie),
     * bound while, condizione di flusso (incl. VarCompare sullo scope var) e interpolazione ${…}.
     */
    private fun walkActions(
        actions: List<Action>,
        trigger: Trigger,
        whitelist: Set<String>,
        knownVars: Set<String>,
        declaredTypes: Map<String, VarType>,
        declaredIntegrity: Map<String, IntegrityLabel>,
        declaredConfidentiality: Map<String, ConfidentialityLabel>,
        availableVars: MutableSet<String>,
        depth: Int,
        countCondition: () -> Int,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
        errDetail: (String, String, String) -> Unit,
    ): Set<String> {
        if (depth > MAX_FLOW_DEPTH) {
            err("flow_too_deep", "Annidamento if/while oltre profondità $MAX_FLOW_DEPTH")
            return availableVars
        }
        for (action in actions) {
            checkInterpolation(
                action,
                knownVars,
                availableVars,
                declaredIntegrity,
                declaredConfidentiality,
                err,
                warn,
            )
            validateAction(action, trigger, whitelist, err, warn, errDetail)
            when (action) {
                is Action.If -> {
                    if (action.then.isEmpty() && action.orElse.isEmpty())
                        err("flow_empty_branch", "if senza azioni in nessun ramo")
                    checkFlowCondition(
                        action.condition,
                        knownVars,
                        availableVars,
                        declaredTypes,
                        countCondition,
                        err,
                    )
                    val thenOut = walkActions(
                        action.then, trigger, whitelist, knownVars, declaredTypes,
                        declaredIntegrity, declaredConfidentiality, availableVars.toMutableSet(),
                        depth + 1, countCondition, err, warn, errDetail,
                    )
                    val elseOut = walkActions(
                        action.orElse, trigger, whitelist, knownVars, declaredTypes,
                        declaredIntegrity, declaredConfidentiality, availableVars.toMutableSet(),
                        depth + 1, countCondition, err, warn, errDetail,
                    )
                    availableVars.retainAll(thenOut.intersect(elseOut))
                }
                is Action.While -> {
                    if (action.body.isEmpty()) err("flow_empty_branch", "while senza corpo")
                    validateWhileIterations(action, knownVars, availableVars, declaredTypes, err)
                    if (action.delayBetweenMs !in 0..MAX_WHILE_DELAY_MS)
                        err("while_delay_invalid", "delayBetweenMs fuori intervallo 0..$MAX_WHILE_DELAY_MS")
                    checkFlowCondition(
                        action.condition,
                        knownVars,
                        availableVars,
                        declaredTypes,
                        countCondition,
                        err,
                    )
                    // Il corpo può non partire: i suoi capture non sono definiti dopo il loop.
                    walkActions(
                        action.body, trigger, whitelist, knownVars, declaredTypes,
                        declaredIntegrity, declaredConfidentiality, availableVars.toMutableSet(),
                        depth + 1, countCondition, err, warn, errDetail,
                    )
                }
                else -> captureDeclaration(action)?.let { availableVars += it.name }
            }
        }
        return availableVars
    }

    /** Valida una condizione di flusso (if/while): struttura + VarCompare sullo scope var. */
    private fun checkFlowCondition(
        condition: Condition,
        knownVars: Set<String>,
        availableVars: Set<String>,
        declaredTypes: Map<String, VarType>,
        countCondition: () -> Int,
        err: (String, String) -> Unit,
    ) {
        checkConditions(
            condition,
            1,
            countCondition,
            err,
            allowVarCompare = true,
            knownVars = knownVars,
            availableVars = availableVars,
            declaredTypes = declaredTypes,
        )
    }

    /**
     * Conteggio iterazioni del while: ESATTAMENTE UNO tra maxIterations (letterale 1..1000) e
     * maxIterationsVar (variabile NUMBER, letta e clampata a runtime). Fail-closed sull'invariante XOR.
     */
    private fun validateWhileIterations(
        action: Action.While,
        knownVars: Set<String>,
        availableVars: Set<String>,
        declaredTypes: Map<String, VarType>,
        err: (String, String) -> Unit,
    ) {
        val varName = action.maxIterationsVar
        if (varName != null) {
            // XOR: il ramo variabile esclude il letterale.
            if (action.maxIterations != null) {
                err(
                    "while_iterations_ambiguous",
                    "while richiede esattamente uno tra maxIterations e maxIterationsVar",
                )
                return
            }
            when (varName) {
                !in knownVars ->
                    err("while_iterations_var_undeclared", "maxIterationsVar '$varName' non dichiarata")
                !in availableVars ->
                    err("var_not_definitely_assigned", "Variabile '$varName' non sicuramente assegnata")
                else -> if (declaredTypes[varName] != VarType.NUMBER)
                    err(
                        "while_iterations_var_type_invalid",
                        "maxIterationsVar '$varName' deve essere di tipo NUMBER",
                    )
            }
        } else {
            val max = action.maxIterations
            if (max == null) {
                err(
                    "while_iterations_ambiguous",
                    "while richiede esattamente uno tra maxIterations e maxIterationsVar",
                )
            } else if (max !in MIN_WHILE_ITERATIONS..MAX_WHILE_ITERATIONS) {
                err(
                    "while_iterations_invalid",
                    "maxIterations fuori intervallo $MIN_WHILE_ITERATIONS..$MAX_WHILE_ITERATIONS",
                )
            }
        }
    }

    private fun validateVarCompare(
        c: Condition.VarCompare,
        knownVars: Set<String>,
        availableVars: Set<String>,
        declaredTypes: Map<String, VarType>,
        err: (String, String) -> Unit,
    ) {
        fun validateAvailable(name: String) {
            when (name) {
                !in knownVars -> err("var_compare_undeclared", "VarCompare su variabile non dichiarata: '$name'")
                !in availableVars -> err("var_not_definitely_assigned", "Variabile '$name' non sicuramente assegnata")
            }
        }
        validateAvailable(c.varName)

        // IS_EVEN/IS_ODD sono UNARI: operano solo sulla variabile di sinistra, senza alcun RHS.
        // Rilassano il vincolo "esattamente uno tra expected/expectedVar": qui NESSUNO dei due è
        // ammesso. Sul tipo si comportano come GT/LT: NUMBER nativo o TEXT coercibile a intero a
        // runtime (fail-closed), BOOLEAN escluso.
        if (c.op == CmpOp.IS_EVEN || c.op == CmpOp.IS_ODD) {
            if (c.expected != null || c.expectedVar != null) {
                err("var_compare_rhs_invalid", "L'operatore ${c.op} è unario: nessun expected/expectedVar ammesso")
                return
            }
            val leftType = declaredTypes[c.varName] ?: return
            if (leftType == VarType.BOOLEAN) {
                err("var_compare_type_invalid", "Operatore ${c.op} incompatibile con $leftType")
            }
            return
        }

        c.expectedVar?.let(::validateAvailable)

        if ((c.expected == null) == (c.expectedVar == null)) {
            err("var_compare_rhs_invalid", "VarCompare richiede esattamente uno tra expected ed expectedVar")
            return
        }
        val leftType = declaredTypes[c.varName] ?: return
        val rightType = c.expectedVar?.let(declaredTypes::get) ?: leftType
        val expected = c.expected
        if (expected != null) {
            if (expected.length > MAX_TEXT_LENGTH || expected.any(Char::isISOControl)) {
                err("var_compare_invalid", "Valore atteso troppo lungo o con caratteri di controllo")
                return
            }
            val validLiteral = when (leftType) {
                VarType.TEXT -> true
                VarType.NUMBER -> expected.toDoubleOrNull()?.isFinite() == true
                VarType.BOOLEAN -> expected in setOf("true", "false")
            }
            if (!validLiteral) {
                err("var_compare_type_invalid", "RHS letterale incompatibile con $leftType")
            }
        }
        val validOperator = when (c.op) {
            CmpOp.CONTAINS -> leftType == VarType.TEXT && rightType == VarType.TEXT
            // GT/LT sono numerici ma ammessi anche su operandi TEXT (device-found: l'output di una
            // capture LLM è sempre TEXT). La coercizione numerica avviene a runtime, fail-closed se
            // non parsabile. BOOLEAN resta escluso; gli operandi devono avere lo stesso tipo, coerente
            // con l'interprete che confronta solo var dello stesso tipo.
            CmpOp.GT, CmpOp.LT -> leftType == rightType && leftType != VarType.BOOLEAN
            CmpOp.EQ, CmpOp.NEQ -> leftType == rightType
            // Unari, già gestiti e ritornati sopra: irraggiungibili qui.
            CmpOp.IS_EVEN, CmpOp.IS_ODD -> false
        }
        if (!validOperator) {
            err("var_compare_type_invalid", "Operatore ${c.op} incompatibile con $leftType/$rightType")
        }
    }

    /** Interpolazione ${…} nei campi testo (§2.4): SINK con var dichiarata; altrove vietata. */
    private fun checkInterpolation(
        action: Action,
        knownVars: Set<String>,
        availableVars: Set<String>,
        declaredIntegrity: Map<String, IntegrityLabel>,
        declaredConfidentiality: Map<String, ConfidentialityLabel>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        // Visita ogni azione (anche annidata) come questo controllo: qui anche i caratteri invisibili.
        warnInvisibleCharacters(action, warn)
        for (field in InterpolationPolicy.textFields(action)) {
            if (!InterpolationPolicy.containsInterpolation(field.value)) continue
            val parsed = InterpolationPolicy.parse(field.value)
            if (parsed.malformed) {
                err("interpolation_malformed", "Interpolazione malformata nel campo ${field.label}")
            }
            parsed.refs.toSet().forEach { name ->
                when (name) {
                    !in knownVars ->
                        err("interpolation_undeclared_var", "Variabile '\${$name}' non dichiarata (campo ${field.label})")
                    !in availableVars ->
                        err("var_not_definitely_assigned", "Variabile '$name' non sicuramente assegnata")
                    else -> {
                        // Posture anti-injection centralizzata in TaintPolicy: in AGGRESSIVO un
                        // valore TAINTED è ammesso anche nei campi AUTHORITY. Il floor SECRET e lo
                        // shell-gating sui mittenti restano enforced altrove, indipendenti da qui.
                        if (field.cls == InterpolationPolicy.FieldClass.AUTHORITY &&
                            declaredIntegrity[name] != IntegrityLabel.CLEAN &&
                            !TaintPolicy.allowTaintedInAuthority()
                        ) {
                            err(
                                "interpolation_tainted_authority",
                                "Variabile TAINTED '$name' non ammessa nel campo di autorità ${field.label}",
                            )
                        }
                        if ((action is Action.InvokeLlm || action is Action.InvokeLlmV2) &&
                            declaredConfidentiality[name] == ConfidentialityLabel.SECRET
                        ) {
                            warn(
                                "secret_interpolation_disclosure",
                                "La variabile SECRET '$name' sarà inviata al Brain configurato",
                            )
                        }
                        if (action is Action.HttpRequest &&
                            declaredConfidentiality[name] == ConfidentialityLabel.SECRET
                        ) {
                            warn(
                                "http_body_secret_disclosure",
                                "La variabile SECRET '$name' sarà inviata all'indirizzo della richiesta HTTP",
                            )
                        }
                    }
                }
            }
            // D1: a runtime un dato esterno entra nel comando solo quotato per contesto. Se il punto
            // del template non è quotabile in sicurezza l'azione verrebbe bloccata: meglio dirlo ora.
            if (field.shellQuoted && !parsed.malformed) {
                val references = InterpolationPolicy.references(field.value)
                val placements = ShellQuoting.placements(field.value, references.map { it.range })
                val external = references.indices.filter { index ->
                    val name = references[index].name
                    name in knownVars && declaredIntegrity[name] != IntegrityLabel.CLEAN
                }.map { placements[it] }
                val contexts = external.mapTo(mutableSetOf()) { it.context }
                // S3: il dato sceglierebbe il programma, o il comando lo rilegge come codice: l'azione
                // non potrebbe mai partire, quindi è un errore.
                if (ShellQuoting.Context.COMMAND_POSITION in contexts) {
                    err(
                        "interpolation_shell_command_position",
                        "Un dato esterno è nella posizione del nome del comando: sceglierebbe quale programma parte",
                    )
                }
                if (ShellQuoting.Context.REEVALUATED in contexts) {
                    err(
                        "interpolation_shell_reevaluated",
                        "Un dato esterno è passato a un comando che lo rilegge come codice " +
                            "(sh -c, eval, su, xargs, awk, sed, find -exec…): gli apici non lo proteggono",
                    )
                }
                if (ShellQuoting.Context.UNSAFE in contexts) {
                    warn(
                        "interpolation_shell_context",
                        "Un dato esterno è usato in un punto del comando shell che non si può quotare " +
                            "in sicurezza (sostituzione di comando, backtick, here-doc, commento, nome di " +
                            "variabile o numero con altro testo attaccato): a runtime l'azione sarebbe bloccata",
                    )
                }
                // S2: dove la shell valuta un numero, solo un intero passa a runtime.
                if (ShellQuoting.Context.NUMERIC in contexts) {
                    warn(
                        "interpolation_shell_numeric",
                        "Un dato esterno è usato dove la shell calcola un numero: a runtime deve essere " +
                            "un numero intero, altrimenti l'azione si blocca",
                    )
                }
                if (external.any { it.optionLike }) {
                    warn(
                        "interpolation_shell_option",
                        "Un dato esterno apre un argomento del comando senza un `--` prima: un valore che " +
                            "inizia con `-` sarebbe letto come un'opzione",
                    )
                }
            }
        }
        // Body JSON di http_request: a runtime un dato esterno entra solo dentro una stringa JSON (con
        // escape); fuori da una stringa l'azione verrebbe bloccata. Meglio dirlo ora.
        if (action is Action.HttpRequest) {
            for (field in InterpolationPolicy.textFields(action)) {
                if (field.encoding != InterpolationPolicy.ValueEncoding.JSON_STRING) continue
                if (InterpolationPolicy.parse(field.value).malformed) continue
                val references = InterpolationPolicy.references(field.value)
                val inString = HttpRequestPolicy.jsonStringContexts(field.value, references.map { it.range })
                val unquotable = references.indices.any { index ->
                    val name = references[index].name
                    !inString[index] && name in knownVars && declaredIntegrity[name] != IntegrityLabel.CLEAN
                }
                if (unquotable) {
                    warn(
                        "interpolation_json_context",
                        "Un dato esterno è nel body JSON fuori da una stringa tra virgolette: " +
                            "a runtime l'azione sarebbe bloccata",
                    )
                }
            }
        }
        // D2: la scrittura di un'impostazione guidata da un dato esterno (messaggio, notifica, testo
        // letto dal telefono, uscita catturata).
        if (action is Action.WriteSetting) {
            val external = InterpolationPolicy.textFields(action)
                .flatMap { InterpolationPolicy.parse(it.value).refs }
                .any { declaredIntegrity[it] == IntegrityLabel.TAINTED }
            if (external) {
                warn(
                    "write_setting_external_value",
                    "Il valore scritto arriva da un dato esterno (messaggio, notifica o testo letto dal telefono).",
                )
            }
        }
    }

    /**
     * Caratteri che non si vedono o che cambiano la direzione del testo (categorie Cf, Zl, Zp e i
     * controlli Cc) nei campi che danno autorità: la review mostrerebbe un comando, un indirizzo o
     * un'impostazione diversi da quelli che girano. Nel comando shell tab e a capo sono normali.
     */
    private fun warnInvisibleCharacters(action: Action, warn: (String, String) -> Unit) {
        val fields: List<Pair<String, Boolean>> = when (action) {
            is Action.RunShell -> listOf(action.cmd to true)
            is Action.OpenUrl -> listOf(action.url to false)
            is Action.HttpRequest -> listOf(action.url to false) + action.headers.orEmpty().values.map { it to false }
            is Action.WriteSetting -> listOf(action.key to false, action.value to false)
            else -> emptyList()
        }
        val hidden = fields.any { (text, shell) ->
            text.codePoints().anyMatch { code ->
                when (Character.getType(code).toByte()) {
                    Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
                    Character.CONTROL -> !(shell && (code == '\t'.code || code == '\n'.code))
                    else -> false
                }
            }
        }
        if (hidden) {
            warn(
                "invisible_characters",
                "Il comando, l'indirizzo o l'impostazione contiene caratteri invisibili o che cambiano " +
                    "la direzione del testo: quello che vedi può non essere quello che viene eseguito",
            )
        }
    }

    /** Budget tempo worst-case statico (§2.5): somma sleep + azioni, con aritmetica satura. */
    private fun worstCaseBudgetMs(actions: List<Action>): Long =
        actions.fold(0L) { acc, action -> saturatingAdd(acc, actionBudgetMs(action)) }

    private fun actionBudgetMs(action: Action): Long = when (action) {
        is Action.InvokeLlm -> action.timeoutMs.coerceAtLeast(0)
        is Action.InvokeLlmV2 -> action.timeoutMs.coerceAtLeast(0)
        is Action.RunShell -> SHELL_ACTION_BUDGET_MS
        is Action.HttpRequest -> HTTP_ACTION_BUDGET_MS
        is Action.Speak -> SPEAK_ACTION_BUDGET_MS
        is Action.UiSteps -> UiStepsPolicy.budgetMs(action)
        // 0.8 scaffold: la domanda aspetta la risposta fino al suo timeout.
        is Action.Ask -> action.timeoutMs.coerceAtLeast(0)
        is Action.Wait -> action.durationMs.coerceAtLeast(0)
        is Action.If -> maxOf(worstCaseBudgetMs(action.then), worstCaseBudgetMs(action.orElse))
        // Con maxIterationsVar il conteggio è ignoto a compile-time: worst-case = MAX_WHILE_ITERATIONS
        // (1000), così il gate 6h non è bypassabile spostando il conteggio in una variabile.
        is Action.While -> saturatingMul(
            (action.maxIterations?.toLong() ?: MAX_WHILE_ITERATIONS.toLong()).coerceAtLeast(0),
            saturatingAdd(action.delayBetweenMs.coerceAtLeast(0), worstCaseBudgetMs(action.body)),
        )
        else -> LEAF_ACTION_BUDGET_MS
    }

    private fun saturatingAdd(a: Long, b: Long): Long =
        if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun saturatingMul(a: Long, b: Long): Long =
        if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b

    private fun validateTrigger(
        trigger: Trigger,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        when (trigger) {
            // Immediate non ha campi propri (nessun cron/at/tz): è valido di per sé, fira all'arm.
            is Trigger.Immediate -> Unit
            is Trigger.Time -> {
                // Esattamente uno tra {cron, at, afterMs, solar}: conta i non-null.
                val specCount = listOfNotNull(trigger.cron, trigger.at, trigger.afterMs, trigger.solar).size
                if (specCount != 1)
                    err("time_spec", "Time richiede esattamente uno tra cron, at, afterMs e solar")
                runCatching { ZoneId.of(trigger.tz) }
                    .onFailure { err("tz_invalid", "Timezone '${trigger.tz}' non valida") }
                trigger.cron?.let { cron ->
                    runCatching { CronSchedule.parse(cron) }
                        .onFailure { err("cron_invalid", "Cron '$cron' non valido: ${it.message}") }
                        .onSuccess { schedule ->
                            // Una data che non esiste (30 febbraio, 31 aprile) si armerebbe senza mai
                            // scattare: stesso orizzonte di ricerca del motore. Con un fuso non valido
                            // decide già tz_invalid.
                            val zone = runCatching { ZoneId.of(trigger.tz) }.getOrNull()
                            if (zone != null && schedule.nextFireAfter(clock.instant(), zone) == null)
                                err("cron_never_fires", "Cron '$cron' non ha nessuno scatto: la data non esiste")
                        }
                }
                trigger.at?.let { at ->
                    runCatching { LocalDateTime.parse(at) }
                        .onFailure { err("at_invalid", "Datetime '$at' non valido (ISO locale atteso)") }
                }
                trigger.afterMs?.let { afterMs ->
                    if (afterMs !in MIN_DELAY_MS..MAX_DELAY_MS)
                        err("after_ms_invalid", "Ritardo relativo fuori intervallo (1s..7g)")
                }
                trigger.solar?.let { solar ->
                    if (!SolarTriggerPolicy.validOffset(solar.offsetMinutes))
                        err(
                            "solar_offset_invalid",
                            "Lo spostamento da alba/tramonto ammette " +
                                "${SolarTriggerPolicy.MIN_OFFSET_MINUTES}..${SolarTriggerPolicy.MAX_OFFSET_MINUTES} minuti",
                        )
                    // La posizione la scrive l'app all'arm: in bozza mancano entrambe, mai una sola.
                    val lat = solar.lat
                    val lng = solar.lng
                    val locationOk = (lat == null && lng == null) ||
                        (lat != null && lng != null &&
                            SolarTriggerPolicy.validLatitude(lat) && SolarTriggerPolicy.validLongitude(lng))
                    if (!locationOk)
                        err("solar_location_invalid", "Posizione di alba/tramonto non valida")
                }
            }

            is Trigger.Geofence -> {
                if (!trigger.radiusM.isFinite() || trigger.radiusM <= 0 || trigger.radiusM > MAX_GEOFENCE_RADIUS_M)
                    err("radius_invalid", "Raggio geofence deve essere finito e compreso tra 0 e ${MAX_GEOFENCE_RADIUS_M.toInt()} m")
                else if (trigger.radiusM < 100)
                    warn(
                        "radius_small",
                        "Raggio ${trigger.radiusM.toInt()} m sotto i 100 m consigliati; " +
                            "lo scatto in background può arrivare con minuti di ritardo",
                    )

                if (trigger.loiteringDelayMs < 0 || trigger.loiteringDelayMs > MAX_LOITERING_MS)
                    err("loitering_invalid", "Loitering delay fuori intervallo")
                if (trigger.transition == Transition.DWELL)
                    err(
                        "geofence_transition_unsupported",
                        "Il runtime geofence supporta ENTER/EXIT; DWELL non è disponibile",
                    )
                if (trigger.loiteringDelayMs != 0L)
                    err(
                        "geofence_loitering_unsupported",
                        "Il loitering delay richiede DWELL, non disponibile nel runtime corrente",
                    )

                if (!trigger.resolveCurrentLocation) {
                    val valid = trigger.lat.isFinite() && trigger.lng.isFinite() &&
                        trigger.lat in -90.0..90.0 && trigger.lng in -180.0..180.0 &&
                        !(trigger.lat == 0.0 && trigger.lng == 0.0)
                    if (!valid) err("geofence_coords", "Coordinate geofence mancanti o fuori intervallo")
                }
            }

            is Trigger.Notification -> {
                validatePackage(trigger.pkg, "pkg_blank", err)
                if (trigger.pkg in deniedNotificationPackages)
                    err("notification_own_package", "Il package Argus non può innescare proprie automazioni")
                if (trigger.conversationId != null && trigger.conversationId.isBlank())
                    err("conversation_id_invalid", "conversationId non può essere vuoto")
                if (trigger.conversationId == null && !trigger.sender.isNullOrBlank())
                    warn("sender_spoofable", "Match per display name: spoofabile, preferire conversationId")
                validateOptionalText(trigger.sender, "sender_invalid", err)
                validateOptionalText(trigger.titleMatch, "title_match_invalid", err)
                validateOptionalText(trigger.textMatch, "text_match_invalid", err)
            }

            is Trigger.PhoneState -> {
                validateOptionalText(trigger.number, "number_invalid", err)
                validateOptionalText(trigger.textMatch, "sms_text_match_invalid", err)
                if (trigger.textMatch != null && trigger.event != PhoneEvent.SMS_RECEIVED)
                    err("sms_text_match_invalid", "Il filtro sul testo vale solo per gli SMS in arrivo")
                trigger.sim?.let { sim ->
                    if (!SimSlotPolicy.validSlot(sim))
                        err(
                            "sim_invalid",
                            "Il filtro SIM ammette solo lo slot ${SimSlotPolicy.MIN_SLOT} o ${SimSlotPolicy.MAX_SLOT}",
                        )
                }
            }
            is Trigger.Connectivity -> validateOptionalText(trigger.match, "match_invalid", err)
            is Trigger.Sensor -> {
                if (!SensorTriggerPolicy.validEventCount(trigger.kind, trigger.minimumEventCount)) {
                    err(
                        "sensor_event_count_invalid",
                        "Conteggio eventi non valido per ${trigger.kind.wireName}; " +
                            "i sensori motion sono one-shot e gli step ammettono 1.." +
                            SensorTriggerPolicy.MAX_EVENT_COUNT,
                    )
                }
                if (trigger.samplingPeriodUs != null || trigger.maxReportLatencyUs != null) {
                    err(
                        "sensor_sampling_unsupported",
                        "Sampling continuo/high-rate non disponibile: usare soltanto i kind " +
                            "event-driven approvati",
                    )
                }
            }
            // --- 0.4.1: trigger senza Shizuku ---
            is Trigger.Screen -> Unit
            is Trigger.Battery -> {
                val below = trigger.below
                val above = trigger.above
                if ((below == null) == (above == null))
                    err("battery_spec", "Batteria richiede esattamente uno tra below e above")
                listOfNotNull(below, above).forEach { threshold ->
                    if (!BatteryTriggerPolicy.validThreshold(threshold))
                        err(
                            "battery_threshold_invalid",
                            "La soglia batteria ammette ${BatteryTriggerPolicy.MIN_THRESHOLD}.." +
                                "${BatteryTriggerPolicy.MAX_THRESHOLD}",
                        )
                }
            }
            is Trigger.AudioOutput -> Unit
            is Trigger.ModeChanged -> trigger.to?.let { target ->
                val allowed = trigger.what.allowedValues
                if (target !in allowed)
                    err(
                        "mode_to_invalid",
                        "Valore '${target.take(32)}' non valido per ${trigger.what.name}; " +
                            "ammessi: ${allowed.joinToString("|")}",
                    )
            }
            is Trigger.Media -> Unit
            // Avvio manuale: nessun campo da validare, parte solo su invocazione dell'utente.
            is Trigger.Manual -> Unit
            // App aperta/chiusa (0.5.1): il package deve avere la forma dei package Android. La direzione
            // è un enum chiuso, quindi non c'è altro da controllare.
            is Trigger.AppEvent -> {
                validatePackage(trigger.pkg, "pkg_blank", err)
                if (trigger.pkg.length > AppPackagePolicy.MAX_LENGTH)
                    err("package_invalid", "Package troppo lungo (massimo ${AppPackagePolicy.MAX_LENGTH})")
            }
            // 0.8 scaffold: spenti (not_available_yet, vedi validate). Chi accende un trigger scrive qui i
            // suoi limiti; i sensori shake e flip_face_down passano già dal ramo Sensor.
            is Trigger.CalendarEvent -> {
                if (trigger.offsetMinutes !in -CalendarTriggerPlanner.MAX_OFFSET_MINUTES..CalendarTriggerPlanner.MAX_OFFSET_MINUTES)
                    err(
                        "calendar_offset_invalid",
                        "Offset calendario fuori da -${CalendarTriggerPlanner.MAX_OFFSET_MINUTES}.." +
                            "${CalendarTriggerPlanner.MAX_OFFSET_MINUTES} minuti",
                    )
                validateCalendarFilters(trigger.titleMatch, trigger.calendar, err)
            }

            is Trigger.NfcTag -> Unit

            is Trigger.NextAlarm -> Unit

            is Trigger.AppUsage -> Unit

            is Trigger.UserModeChanged -> Unit
        }
    }

    private fun checkConditions(
        condition: Condition?,
        depth: Int,
        count: () -> Int,
        err: (String, String) -> Unit,
        allowVarCompare: Boolean = false,
        knownVars: Set<String> = emptySet(),
        availableVars: Set<String> = emptySet(),
        declaredTypes: Map<String, VarType> = emptyMap(),
    ) {
        if (condition == null) return
        count()
        if (depth > MAX_CONDITION_DEPTH) {
            err("condition_too_deep", "Albero condizioni oltre profondità $MAX_CONDITION_DEPTH")
            return
        }
        fun recurse(child: Condition) =
            checkConditions(
                child,
                depth + 1,
                count,
                err,
                allowVarCompare,
                knownVars,
                availableVars,
                declaredTypes,
            )
        when (condition) {
            is Condition.And -> {
                if (condition.all.isEmpty()) err("condition_empty", "AND senza condizioni")
                condition.all.forEach { recurse(it) }
            }
            is Condition.Or -> {
                if (condition.any.isEmpty()) err("condition_empty", "OR senza condizioni")
                condition.any.forEach { recurse(it) }
            }
            is Condition.Not -> recurse(condition.cond)
            // VarCompare: ammesso SOLO nelle condizioni di flusso (if/while), mai trigger-time.
            is Condition.VarCompare ->
                if (allowVarCompare) {
                    validateVarCompare(condition, knownVars, availableVars, declaredTypes, err)
                }
                else err("var_compare_outside_flow", "Il confronto su variabili è ammesso solo dentro if/while")
            is Condition.BooleanLiteral -> Unit
            is Condition.StateEquals -> validateStateCondition(condition, err)
            is Condition.StateCompare -> validateStateCompare(condition, err)
            is Condition.TimeWindow -> {
                runCatching {
                    ZoneId.of(condition.tz)
                    LocalTime.parse(condition.startLocal)
                    LocalTime.parse(condition.endLocal)
                }.onFailure {
                    err("time_window_invalid", "Finestra temporale non valida: usare HH:mm e timezone IANA")
                }
            }
            is Condition.AppInForeground -> validatePackage(condition.pkg, "package_invalid", err)
            is Condition.LocationIn -> {
                if (!condition.lat.isFinite() || !condition.lng.isFinite() ||
                    condition.lat !in -90.0..90.0 || condition.lng !in -180.0..180.0)
                    err("location_invalid", "Coordinate condizione fuori intervallo")
                if (!condition.radiusM.isFinite() || condition.radiusM <= 0 || condition.radiusM > MAX_GEOFENCE_RADIUS_M)
                    err("radius_invalid", "Raggio condizione non valido")
            }
            // 0.8 scaffold: spente (not_available_yet, vedi validate). Chi accende una condizione scrive
            // qui i suoi limiti (un campo personale svuotato dall'export è "" e va rifiutato).
            is Condition.Weekday ->
                if (condition.days.isEmpty() || !validZoneId(condition.tz))
                    err("weekday_invalid", "Giorni della settimana: almeno un giorno e un fuso IANA valido")

            is Condition.WifiConnected -> condition.ssid?.let { ssid ->
                if (!validContextText(ssid, MAX_SSID_CHARS))
                    err("wifi_ssid_invalid", "Nome della rete Wi-Fi vuoto, troppo lungo o con caratteri di controllo")
            }

            is Condition.BluetoothConnected -> condition.device?.let { device ->
                if (!validContextText(device, MAX_BLUETOOTH_DEVICE_CHARS))
                    err("bluetooth_device_invalid", "Dispositivo Bluetooth vuoto, troppo lungo o con caratteri di controllo")
            }

            is Condition.HeadsetConnected -> Unit

            is Condition.CalendarBusy -> validateCalendarFilters(condition.titleMatch, condition.calendar, err)

            is Condition.StoredCompare -> Unit

            is Condition.UserModeIs -> Unit
        }
    }

    // --- 0.8: condizioni di contesto e trigger del calendario ---------------------------------------

    private fun validZoneId(tz: String): Boolean =
        tz.length <= MAX_ZONE_ID_CHARS && runCatching { ZoneId.of(tz) }.isSuccess

    /**
     * Un filtro di testo letterale: non vuoto (il PersonalDataStripper lascia "" da completare), limitato,
     * senza caratteri di controllo.
     */
    private fun validContextText(value: String, maxChars: Int): Boolean =
        value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)

    private fun validateCalendarFilters(titleMatch: String?, calendar: String?, err: (String, String) -> Unit) {
        if (listOfNotNull(titleMatch, calendar).any { !validContextText(it, MAX_CALENDAR_FILTER_CHARS) })
            err("calendar_filter_invalid", "Filtro del calendario vuoto, troppo lungo o con caratteri di controllo")
    }

    private fun validateStateCondition(c: Condition.StateEquals, err: (String, String) -> Unit) {
        if (c.key !in stateKeys) {
            err("state_key_unknown", "Chiave di stato '${c.key}' fuori dal registry StateKeys")
            return
        }
        val valid = when (c.key) {
            StateKeys.BATTERY -> c.op in setOf(CmpOp.EQ, CmpOp.NEQ, CmpOp.GT, CmpOp.LT) &&
                c.value.toIntOrNull()?.let { it in 0..100 } == true
            else -> c.op in setOf(CmpOp.EQ, CmpOp.NEQ) &&
                c.value in StateKeys.ALL.getValue(c.key).split('|')
        }
        if (!valid) err("state_value_invalid", "Valore/op '${c.op}:${c.value}' non valido per '${c.key}'")
    }

    private fun validateStateCompare(c: Condition.StateCompare, err: (String, String) -> Unit) {
        if (c.policyVersion != StateQueryPolicy.VERSION) {
            err("state_query_policy_incompatible", "Versione policy reader non compatibile")
            return
        }
        if (!StateQueryPolicy.validQuery(c.query, stateKeys)) {
            err("state_query_invalid", "Reader di stato o parametri non validi")
            return
        }
        if (!StateQueryPolicy.validComparison(
                c.query,
                c.valueType,
                c.op,
                c.expected,
                stateKeys,
            )
        ) {
            err("state_compare_invalid", "Tipo, operatore o valore atteso non validi per il reader")
        }
    }

    private fun validateAction(
        action: Action,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
        errDetail: (String, String, String) -> Unit,
    ) {
        when (action) {
            is Action.SetWifi, is Action.SetBluetooth, is Action.SetMobileData, is Action.SetDnd -> Unit
            is Action.SetRinger -> if (!InterpolationPolicy.containsInterpolation(action.mode) &&
                action.mode !in setOf("normal", "vibrate", "silent")
            )
                err("ringer_mode_invalid", "Modalità suoneria non valida")
            is Action.LaunchApp -> if (!InterpolationPolicy.containsInterpolation(action.pkg)) {
                validatePackage(action.pkg, "package_invalid", err)
            }
            is Action.OpenUrl -> when {
                // `https://banca.example@altro.example/` si legge come la banca ma apre l'altro host.
                urlHasUserInfo(action.url) ->
                    err("url_userinfo", "L'URL contiene un nome o credenziali prima di @: non è ammesso")
                !InterpolationPolicy.containsInterpolation(action.url) && !validHttpUrl(action.url) ->
                    err("url_invalid", "URL non valido o schema non consentito")
            }
            is Action.ShowNotification -> {
                validateRequiredText(action.title, 120, "title_invalid", err)
                // Il corpo può restare vuoto (notifica di solo titolo, come ammette il bridge):
                // qui conta solo il bound.
                if (action.text.length > MAX_TEXT_LENGTH)
                    err("text_invalid", "Testo notifica oltre $MAX_TEXT_LENGTH caratteri")
            }
            is Action.Tap -> if (action.x !in 0..10_000 || action.y !in 0..10_000)
                err("coordinates_invalid", "Coordinate tap fuori intervallo")
            is Action.InputText -> validateRequiredText(action.text, MAX_TEXT_LENGTH, "text_invalid", err)
            is Action.WhatsAppReply -> {
                validateRequiredText(action.text, MAX_TEXT_LENGTH, "text_invalid", err)
                validateReplyTarget(trigger, whitelist, err, ReplyKind.WHATSAPP)
            }
            is Action.RunShell -> {
                validateRequiredText(action.cmd, MAX_COMMAND_LENGTH, "shell_invalid", err)
                if ('\u0000' in action.cmd)
                    err("shell_invalid", "Il comando shell contiene un carattere NUL non eseguibile")
                if (!StaticShellSafety.allows(trigger, whitelist))
                    err(
                        "shell_external_trigger",
                        "La shell autonoma è ammessa con trigger Time, Geofence o Connectivity, " +
                            "oppure da una chat WhatsApp 1:1 con contatto in whitelist. SMS e " +
                            "chiamate restano esclusi: mittente e caller ID sono falsificabili",
                    )
                warn(
                    "shell_review",
                    "Comando autonomo approvato letteralmente; limite operativo 30 s e, per " +
                        "trigger broadcast, preferire comandi brevi",
                )
                // Il rischio non è più solo "cosa esegue" ma "chi può farlo partire": con un
                // trigger notification il comando parte quando decide il contatto, non tu.
                if (trigger is Trigger.Notification)
                    warn(
                        "shell_contact_trigger",
                        "Questo comando potrà essere avviato dal contatto in whitelist ogni " +
                            "volta che il messaggio corrisponde: il comando resta quello " +
                            "approvato, ma il momento lo sceglie lui",
                    )
            }
            is Action.CopyToClipboard -> {
                val textual = trigger is Trigger.Notification ||
                    (trigger is Trigger.PhoneState && trigger.event == PhoneEvent.SMS_RECEIVED)
                if (!textual)
                    err("clipboard_source_missing", "Copia negli appunti richiede un trigger con testo (SMS o notifica)")
                action.extractionRegex?.let { pattern ->
                    if (!InterpolationPolicy.containsInterpolation(pattern) && !SafeExtractionRegex.isValid(pattern))
                        err(
                            "extraction_regex_invalid",
                            "Regex non sicura/compatibile RE2 o oltre " +
                                "${SafeExtractionRegex.MAX_PATTERN_CHARS} caratteri",
                        )
                }
            }
            // Clipboard letterale: nessun trigger testuale richiesto (a differenza di CopyToClipboard).
            // `text` è SINK interpolabile: basta che sia non vuoto e bounded (i ${'$'}{var} passano il
            // bound; la risoluzione taint-aware valida a runtime la stringa resa).
            is Action.CopyText -> validateRequiredText(action.text, MAX_TEXT_LENGTH, "text_invalid", err)
            is Action.SetAlarm -> {
                if (action.hour !in 0..23 || action.minute !in 0..59)
                    err("alarm_time_invalid", "Ora sveglia fuori intervallo: hour 0..23, minute 0..59")
                validateOptionalText(action.label, "alarm_label_invalid", err)
            }
            is Action.SetTimer -> {
                if (action.seconds !in 1..MAX_TIMER_SECONDS)
                    err("timer_seconds_invalid", "Durata timer fuori intervallo: 1..$MAX_TIMER_SECONDS secondi")
                validateOptionalText(action.label, "timer_label_invalid", err)
            }
            is Action.SetVolume -> {
                // Stream è un enum chiuso (compile-enforced). `level` è una PERCENTUALE 0..100 che
                // l'executor mappa sul massimo reale dello stream a runtime; qui basta il range.
                if (action.level !in 0..MAX_VOLUME_LEVEL)
                    err("volume_level_invalid", "Livello volume fuori intervallo: 0..$MAX_VOLUME_LEVEL")
            }
            is Action.SetFlashlight -> Unit // solo booleano, nulla da validare
            is Action.SetDarkMode -> Unit // NightMode è un enum chiuso: nulla da validare
            is Action.OpenSettingsScreen -> {
                // Enum chiuso: nessuna action-string arbitraria. `pkg` è obbligatorio e valido solo
                // per APP_DETAILS; per le altre schermate va lasciato assente.
                if (action.screen == SettingsScreen.APP_DETAILS) {
                    if (action.pkg == null) err("settings_pkg_missing", "APP_DETAILS richiede un package")
                    else if (!InterpolationPolicy.containsInterpolation(action.pkg)) {
                        validatePackage(action.pkg, "settings_pkg_invalid", err)
                    }
                } else if (action.pkg != null) {
                    err("settings_pkg_unexpected", "Il package è ammesso solo per la schermata APP_DETAILS")
                }
            }
            is Action.Vibrate -> {
                if (action.durationMs !in 1..MAX_VIBRATE_MS)
                    err("vibrate_duration_invalid", "Durata vibrazione fuori intervallo: 1..$MAX_VIBRATE_MS ms")
            }
            is Action.Wait -> {
                if (action.durationMs !in 1..MAX_WAIT_MS)
                    err("wait_duration_invalid", "Durata pausa fuori intervallo: 1..$MAX_WAIT_MS ms")
            }
            is Action.WriteSetting -> {
                // PARAMETRICA (D0: nessuna allowlist di chiavi). Solo validazione di forma via
                // WriteSettingPolicy: key stile QUERY_NAME, value bounded, control char rifiutati.
                // S7: la chiave è sempre letterale, così il controllo "alto impatto" vede quella vera.
                if (InterpolationPolicy.containsInterpolation(action.key))
                    err("write_setting_key_interpolated", "La chiave dell'impostazione deve essere letterale, senza variabili")
                else if (!WriteSettingPolicy.validKey(action.key))
                    err("write_setting_key_invalid", "Chiave impostazione non valida (forma/limite)")
                if (!InterpolationPolicy.containsInterpolation(action.value) && !WriteSettingPolicy.validValue(action.value))
                    err(
                        "write_setting_value_invalid",
                        "Valore impostazione vuoto, troppo lungo o con caratteri di controllo",
                    )
                // D2: la scrittura crea autorità; la review mostra namespace, chiave e valore integrali.
                warn(
                    "write_setting_review",
                    "Scrittura impostazione approvata letteralmente: " +
                        "${action.namespace.name.lowercase()} ${action.key} = ${action.value}",
                )
                if (WriteSettingPolicy.isHighImpact(action.namespace, action.key)) {
                    warn(
                        "write_setting_high_impact",
                        "Questa regola cambia un'impostazione di sicurezza del telefono (debug, " +
                            "accessibilità, tastiera, verifica delle app, accesso alle notifiche, " +
                            "compilazione automatica, assistente, proxy o DNS). Armala solo se sai cosa fa.",
                    )
                }
            }
            is Action.Speak ->
                validateRequiredText(action.text, MAX_SPEAK_LENGTH, "speak_text_invalid", err)
            is Action.MediaControl -> Unit // MediaCommand è un enum chiuso: nulla da validare
            is Action.SetBrightness -> {
                // ESATTAMENTE UNO tra level (0..100) e auto.
                if ((action.level == null) == (action.auto == null)) {
                    err("brightness_spec", "set_brightness richiede esattamente uno tra level e auto")
                } else if (action.level != null && action.level !in 0..MAX_BRIGHTNESS_LEVEL) {
                    err("brightness_level_invalid", "Luminosità fuori intervallo: 0..$MAX_BRIGHTNESS_LEVEL")
                }
            }
            is Action.SetRotation -> Unit // solo booleano, nulla da validare
            is Action.HttpRequest -> validateHttpRequest(action, err)
            // 0.5.1: azioni privilegiate tipizzate. Solo booleani/nessun campo: nulla da validare.
            is Action.SetAirplane,
            is Action.SetLocation,
            is Action.SetNfc,
            is Action.SetBatterySaver,
            is Action.LockScreen,
            is Action.Screenshot,
            -> Unit
            is Action.AppControl -> validateAppControl(action, err, warn)
            is Action.AppPermission -> validateAppPermission(action, err, warn)
            is Action.UiSteps -> validateUiSteps(action, trigger, whitelist, err, warn, errDetail)
            is Action.InvokeLlm -> validateInvokeLlm(action, trigger, whitelist, err, warn)
            is Action.InvokeLlmV2 -> validateInvokeLlmV2(action, trigger, whitelist, err, warn)
            // Contenitori control-flow: bound (while), rami non vuoti, condizione di flusso e
            // ricorsione sono gestiti da walkActions (che ha lo scope var). Qui nessun campo foglia.
            is Action.If, is Action.While -> Unit
            // 0.8 scaffold: spente (not_available_yet, vedi validate). Chi accende un'azione scrive qui
            // i suoi limiti; i campi letterali (numeri, chiavi, etichette) non devono contenere `${'$'}{`.
            is Action.Ask -> Unit

            is Action.SetStored -> Unit

            is Action.SetUserMode -> Unit

            is Action.NotificationReply -> {
                validateRequiredText(action.text, MAX_TEXT_LENGTH, "text_invalid", err)
                validateReplyTarget(trigger, whitelist, err, ReplyKind.NOTIFICATION)
            }

            is Action.NotificationAction -> validateNotificationAction(action, trigger, err, warn)

            is Action.SendSms -> Unit

            is Action.CallNumber -> Unit

            is Action.SendIntent -> Unit

            is Action.ScreenCall -> Unit
        }
    }

    /**
     * `http_request` (0.4.1). URL e intestazioni sono LETTERALI: un segnaposto lì è un errore, perché i
     * dati esterni possono entrare solo nel body. https obbligatorio (http solo verso loopback),
     * nessun userinfo; al massimo 10 intestazioni con nomi validi, senza `Host`, `Content-Length`,
     * `Transfer-Encoding`, `Connection`; body fino a 16 KB. Le stesse regole valgono a runtime
     * ([HttpRequestPolicy.isResolvedShapeValid]).
     */
    private fun validateHttpRequest(action: Action.HttpRequest, err: (String, String) -> Unit) {
        if (InterpolationPolicy.containsInterpolation(action.url)) {
            err("http_url_interpolated", "L'URL della richiesta HTTP è letterale: niente \${…}")
        } else if (!HttpRequestPolicy.isUrlAllowed(action.url)) {
            err(
                "http_url_invalid",
                "URL non valido: serve https:// (http:// solo per localhost), senza utente:password",
            )
        }

        val headers = action.headers.orEmpty()
        if (headers.size > HttpRequestPolicy.MAX_HEADERS) {
            err("http_headers_too_many", "Massimo ${HttpRequestPolicy.MAX_HEADERS} intestazioni")
        }
        val interpolated = headers.any { (name, value) ->
            InterpolationPolicy.containsInterpolation(name) || InterpolationPolicy.containsInterpolation(value)
        }
        if (interpolated) {
            err("http_header_interpolated", "Le intestazioni sono letterali: niente \${…}")
        } else {
            val lowerNames = headers.keys.map { it.lowercase(java.util.Locale.ROOT) }
            // I caratteri non ASCII hanno un codice loro (0.7.2): il client HTTP li rifiuta a ogni scatto.
            val notAscii = headers.values.any(HttpRequestPolicy::hasNonAsciiChars)
            val invalid = headers.any { (name, value) ->
                !HttpRequestPolicy.isHeaderNameValid(name) ||
                    HttpRequestPolicy.isHeaderNameForbidden(name) ||
                    (!HttpRequestPolicy.isHeaderValueValid(value) && !HttpRequestPolicy.hasNonAsciiChars(value))
            }
            if (invalid || lowerNames.size != lowerNames.toSet().size) {
                err(
                    "http_header_invalid",
                    "Intestazione non valida (nome, valore vuoto o con caratteri di controllo, " +
                        "duplicata, o vietata: Host, Content-Length, Transfer-Encoding, Connection)",
                )
            }
            if (notAscii) {
                err(
                    "http_header_not_ascii",
                    "Valore di intestazione con caratteri non ASCII (lettere accentate, virgolette tipografiche, emoji)",
                )
            }
        }

        action.contentType?.let { contentType ->
            if (InterpolationPolicy.containsInterpolation(contentType) ||
                !HttpRequestPolicy.isContentTypeValid(contentType)
            ) {
                err("http_content_type_invalid", "Content type non valido")
            }
        }
        if (HttpRequestPolicy.contentTypeConflicts(action)) {
            err("http_content_type_invalid", "Il content type e l'intestazione Content-Type sono diversi")
        }

        val body = action.body
        if (body != null) {
            if (action.method == HttpMethod.GET) {
                err("http_body_not_allowed", "Una richiesta GET non ha body")
            }
            if (HttpRequestPolicy.bodyBytes(body) > HttpRequestPolicy.MAX_BODY_BYTES) {
                err("http_body_too_large", "Body oltre ${HttpRequestPolicy.MAX_BODY_BYTES} byte")
            }
        }
    }

    /**
     * `app_control` (0.5.1). `pkg` è LETTERALE: la forma del package esclude `${'$'}{…}`, quindi un dato esterno
     * non può scegliere quale app fermare. Vietato su Argus e sui package di sistema essenziali; il
     * launcher in uso lo conosce solo il telefono ed è controllato dall'esecutore. FREEZE è distruttivo.
     */
    private fun validateAppControl(
        action: Action.AppControl,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        when (PrivilegedActionPolicy.appControlTarget(action.pkg)) {
            PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID ->
                err("priv_pkg_invalid", "Package non valido: serve un package letterale (niente \${…})")
            PrivilegedActionPolicy.TargetVerdict.FORBIDDEN ->
                err(
                    "priv_target_forbidden",
                    "Argus, il launcher e i package di sistema essenziali non possono essere controllati",
                )
            PrivilegedActionPolicy.TargetVerdict.OK -> Unit
        }
        if (action.op == AppControlOp.FREEZE && PrivilegedActionPolicy.isValidPackage(action.pkg)) {
            warn(
                "app_control_freeze_review",
                "Congelare disattiva l'app ${action.pkg}: sparisce dal launcher e non parte finché non " +
                    "viene riattivata",
            )
        }
    }

    /**
     * `app_permission` (0.5.1). `pkg` e `permission` sono LETTERALI. Il permesso deve essere un permesso
     * runtime dell'elenco chiuso ([PrivilegedActionPolicy.RUNTIME_PERMISSIONS]); vietato su Argus. La
     * revoca è distruttiva: l'app perde un accesso e può essere terminata da Android.
     */
    private fun validateAppPermission(
        action: Action.AppPermission,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        when (PrivilegedActionPolicy.appPermissionTarget(action.pkg)) {
            PrivilegedActionPolicy.TargetVerdict.PACKAGE_INVALID ->
                err("priv_pkg_invalid", "Package non valido: serve un package letterale (niente \${…})")
            PrivilegedActionPolicy.TargetVerdict.FORBIDDEN ->
                err("priv_target_forbidden", "I permessi di Argus non possono essere modificati da una regola")
            PrivilegedActionPolicy.TargetVerdict.OK -> Unit
        }
        if (!PrivilegedActionPolicy.isRuntimePermission(action.permission)) {
            err(
                "priv_permission_invalid",
                "Permesso fuori dall'elenco dei permessi runtime ammessi (android.permission.*)",
            )
        }
        if (!action.grant && PrivilegedActionPolicy.isValidPackage(action.pkg)) {
            warn(
                "app_permission_revoke_review",
                "Revocare un permesso toglie un accesso all'app ${action.pkg}: ${action.permission}",
            )
        }
    }

    /**
     * `ui_steps` (0.5.1). Da 1 a 20 passi a vocabolario chiuso; `pkg` è un package letterale e mai uno
     * di quelli vietati (Argus, Impostazioni, installatore, dialoghi dei permessi); solo il testo di
     * `type` ammette segnaposto. Il trigger deve poter avviare anche una shell ([StaticShellSafety]):
     * niente SMS, chiamate, media né app aperta o chiusa, perché chiunque può provocarli. A runtime
     * gli stessi limiti sono riverificati ([UiStepsPolicy.isResolvedShapeValid]).
     */
    private fun validateUiSteps(
        action: Action.UiSteps,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
        errDetail: (String, String, String) -> Unit,
    ) {
        if (action.steps.isEmpty()) {
            err("ui_steps_empty", "La macro sull'interfaccia non ha passi")
        } else if (action.steps.size > UiStepsPolicy.MAX_STEPS) {
            err("ui_steps_too_many", "Massimo ${UiStepsPolicy.MAX_STEPS} passi per macro")
        }
        val pkg = action.pkg
        if (pkg != null) {
            if (InterpolationPolicy.containsInterpolation(pkg) || !UiStepsPolicy.isPackageName(pkg)) {
                err("ui_pkg_invalid", "Il package dell'app da aprire non è valido (letterale, senza \${…})")
            } else if (UiStepsPolicy.isForbiddenPackage(pkg)) {
                err(
                    "ui_pkg_forbidden",
                    "Una macro non può agire su Argus, sulle Impostazioni, sull'installatore di pacchetti " +
                        "né sui dialoghi dei permessi",
                )
            }
        }
        // Un errore per passo, col numero del passo (da 1) in `detail`: l'utente e il modello vedono quale passo
        // correggere e perché. Il messaggio qui è la diagnostica italiana; il testo EN/IT sta in ValidationMessages.
        action.steps.take(UiStepsPolicy.MAX_STEPS).forEachIndexed { index, step ->
            val n = (index + 1).toString()
            if (!UiStepsPolicy.literalFieldsOnly(step)) {
                errDetail("ui_step_interpolated", "Passo $n: solo il testo di un passo «type» ammette \${…}", n)
            } else when (UiStepsPolicy.stepProblem(step, hasApp = pkg != null)) {
                null -> Unit
                UiStepProblem.WAIT_NO_TARGET -> errDetail(
                    "ui_step_wait_no_target",
                    "Passo $n: «wait_for» senza testo né id e senza app da aprire (pkg)",
                    n,
                )
                UiStepProblem.SELECTOR_MISSING -> errDetail(
                    "ui_step_selector_missing",
                    "Passo $n: serve almeno un testo, un id o una descrizione",
                    n,
                )
                UiStepProblem.SELECTOR_EMPTY -> errDetail(
                    "ui_step_selector_empty",
                    "Passo $n: testo, id o descrizione vuoti",
                    n,
                )
                UiStepProblem.SELECTOR_TOO_LONG -> errDetail(
                    "ui_step_selector_too_long",
                    "Passo $n: testo, id o descrizione oltre ${UiStepsPolicy.MAX_SELECTOR_CHARS} caratteri",
                    n,
                )
                UiStepProblem.SELECTOR_CONTROL -> errDetail(
                    "ui_step_selector_control",
                    "Passo $n: testo, id o descrizione con caratteri di controllo",
                    n,
                )
                UiStepProblem.TIMEOUT -> errDetail(
                    "ui_step_timeout",
                    "Passo $n: l'attesa deve stare tra ${UiStepsPolicy.MIN_WAIT_MS} e ${UiStepsPolicy.MAX_WAIT_MS} ms",
                    n,
                )
                UiStepProblem.INDEX -> errDetail(
                    "ui_step_index",
                    "Passo $n: l'indice deve stare tra 0 e ${UiStepsPolicy.MAX_INDEX}",
                    n,
                )
                UiStepProblem.TYPE_EMPTY -> errDetail("ui_step_type_empty", "Passo $n: il testo da scrivere è vuoto", n)
                UiStepProblem.TYPE_TOO_LONG -> errDetail(
                    "ui_step_type_too_long",
                    "Passo $n: il testo da scrivere supera ${UiStepsPolicy.MAX_TYPE_CHARS} caratteri",
                    n,
                )
            }
        }
        if (!StaticShellSafety.allows(trigger, whitelist)) {
            err(
                "ui_steps_external_trigger",
                "Una macro sull'interfaccia parte solo da trigger che nessun altro può provocare: " +
                    "tempo, geofence, connettività, sensori, schermo, batteria, audio, modalità, avvio manuale " +
                    "o una chat WhatsApp 1:1 in whitelist. Non da SMS, chiamate, media né app aperta o chiusa",
            )
        }
        warn(
            "ui_steps_review",
            "La macro agisce sull'interfaccia come farebbe l'utente: tocca, scrive e scorre nell'app indicata",
        )
    }

    private fun validateInvokeLlmV2(
        action: Action.InvokeLlmV2,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        validateRequiredText(action.goal, MAX_TEXT_LENGTH, "goal_invalid", err)
        if (action.stateContext.isEmpty()) {
            err("state_context_empty", "InvokeLlm v2 richiede almeno un reader di stato esplicito")
        }
        if (action.stateContext.size > StateContextClassification.MAX_QUERIES) {
            err(
                "state_context_too_large",
                "InvokeLlm v2 ammette al massimo ${StateContextClassification.MAX_QUERIES} reader",
            )
        }
        if (action.stateContext.map { it.query.canonicalId }.distinct().size != action.stateContext.size) {
            err("state_context_duplicated", "Reader di stato duplicati in InvokeLlm v2")
        }
        action.stateContext.forEach { context ->
            if (context.policyVersion != StateQueryPolicy.VERSION) {
                err("state_context_policy_incompatible", "Policy reader non compatibile")
            }
            if (!StateQueryPolicy.validQuery(context.query, stateKeys)) {
                err("state_context_query_invalid", "Reader di stato o parametri non validi")
            }
            if (!StateContextClassification.validValueType(context.query, context.valueType)) {
                err("state_context_type_invalid", "Tipo dichiarato non valido per il reader")
            }
            if (context.integrity != IntegrityLabel.CLEAN) {
                err(
                    "state_context_integrity_invalid",
                    "Un reader locale approvato deve avere integrità CLEAN",
                )
            }
            val minimum = StateContextClassification.minimumConfidentiality(context.query)
            if (!StateContextClassification.covers(context.confidentiality, minimum)) {
                err(
                    "state_context_underclassified",
                    "Reader ${context.query.family.wireName} classificato sotto il minimo ${minimum.name}",
                )
            }
        }

        validateGenerativeReplyContract(
            allowedTools = action.allowedTools,
            replyTargetSender = action.replyTargetSender,
            timeoutMs = action.timeoutMs,
            trigger = trigger,
            whitelist = whitelist,
            err = err,
        )
        warn(
            "state_context_disclosure",
            "I reader elencati saranno inviati al Brain configurato con classificazione esplicita",
        )
        if (action.stateContext.any { it.confidentiality == ConfidentialityLabel.SECRET }) {
            warn(
                "secret_state_context",
                "Questa regola condivide stato classificato SECRET con Hermes/provider cloud",
            )
        }
    }

    private fun validateGenerativeReplyContract(
        allowedTools: List<String>,
        replyTargetSender: Boolean,
        timeoutMs: Long,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
    ) {
        if (!GenerativeContract.isAllowedToolset(allowedTools)) {
            err(
                "allowed_tools_unsupported",
                "allowed_tools deve contenere whatsapp_reply e al più web.search",
            )
        }
        if (allowedTools.isEmpty()) err("no_tools", "Azione generativa senza allowed_tools")
        if (allowedTools.size > MAX_TOOL_COUNT) err("too_many_tools", "Troppi tool nell'azione generativa")
        if (timeoutMs !in 1_000..MAX_LLM_TIMEOUT_MS) {
            err("timeout_invalid", "Timeout InvokeLlm fuori intervallo")
        }
        for (tool in allowedTools) {
            val norm = tool.lowercase()
            val forbidden = norm in FORBIDDEN_IN_INVOKE_LLM ||
                norm == FORBIDDEN_PREFIX_BARE || norm.startsWith(FORBIDDEN_PREFIX)
            when {
                tool.isBlank() || tool.length > 120 -> err("tool_invalid", "Nome tool non valido")
                forbidden -> err("tool_forbidden", "Tool '$tool' vietato al fire-time generativo")
                tool !in knownTools -> err("tool_unknown", "Tool '$tool' non nel catalogo")
            }
        }
        val hasReplyTool = allowedTools.any { it.lowercase() in REPLY_TOOLS }
        if (hasReplyTool && !replyTargetSender) {
            err("reply_target_unbound", "Un tool di reply deve essere vincolato al mittente del trigger")
        }
        if (replyTargetSender) {
            if (!hasReplyTool) err("reply_target_without_tool", "replyTargetSender senza tool di reply")
            validateReplyTarget(trigger, whitelist, err, ReplyKind.GENERATIVE)
        }
    }

    private fun validateInvokeLlm(
        action: Action.InvokeLlm,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        // Controlli comuni ai tre sink (goal + forma delle context sources).
        validateRequiredText(action.goal, MAX_TEXT_LENGTH, "goal_invalid", err)
        if (action.contextSources.size > MAX_TOOL_COUNT || action.contextSources.any { it.isBlank() || it.length > 120 })
            err("context_sources_invalid", "Context sources non valide o troppe")

        when (action.deliver) {
            GenerativeDeliverMode.WHATSAPP_REPLY ->
                validateInvokeLlmReplyDeliver(action, trigger, whitelist, err, warn)
            GenerativeDeliverMode.LOCAL_NOTIFICATION ->
                validateInvokeLlmNotificationDeliver(action, err)
            GenerativeDeliverMode.CAPTURE_ONLY ->
                validateInvokeLlmCaptureOnly(action, err)
        }

        // Difesa in profondità per-tool + timeout: valgono per tutti i sink (web.search è in
        // knownTools; shell.run/automation.* restano vietati e i tool ignoti respinti).
        if (action.allowedTools.size > MAX_TOOL_COUNT) err("too_many_tools", "Troppi tool in InvokeLlm")
        if (action.timeoutMs !in 1_000..MAX_LLM_TIMEOUT_MS)
            err("timeout_invalid", "Timeout InvokeLlm fuori intervallo")
        for (tool in action.allowedTools) {
            val norm = tool.lowercase()
            val forbidden = norm in FORBIDDEN_IN_INVOKE_LLM ||
                norm == FORBIDDEN_PREFIX_BARE || norm.startsWith(FORBIDDEN_PREFIX)
            when {
                tool.isBlank() || tool.length > 120 -> err("tool_invalid", "Nome tool non valido")
                forbidden -> err("tool_forbidden", "Tool '$tool' vietato al fire-time generativo")
                tool !in knownTools -> err("tool_unknown", "Tool '$tool' non nel catalogo")
            }
        }
    }

    /** Sink interno P4: genera testo per captureAs, senza reply né notifica. */
    private fun validateInvokeLlmCaptureOnly(
        action: Action.InvokeLlm,
        err: (String, String) -> Unit,
    ) {
        if (action.captureAs == null) {
            err("capture_required", "CAPTURE_ONLY richiede captureAs")
        }
        if (action.replyTargetSender) {
            err("reply_target_forbidden", "CAPTURE_ONLY non può vincolare un destinatario")
        }
        if (action.notificationTitle != null) {
            err("notification_title_forbidden", "CAPTURE_ONLY non usa un titolo notifica")
        }
        if (!GenerativeContract.isNotificationToolset(action.allowedTools)) {
            err(
                "allowed_tools_unsupported",
                "CAPTURE_ONLY ammette al più web.search, mai whatsapp_reply",
            )
        }
        action.contextSources.filterNot { it == GenerativeContract.CONTEXT_STATE }.forEach { source ->
            err("context_source_unsupported", "Context source '$source' non supportata da CAPTURE_ONLY")
        }
        if (action.contextSources.size != action.contextSources.distinct().size) {
            err("context_sources_duplicated", "Context sources duplicate in CAPTURE_ONLY")
        }
    }

    /**
     * Sink REPLY (profilo P1, invariato): l'unico profilo che la lane generativa esegue davvero
     * al fire-time. Un draft più permissivo passerebbe la review e fallirebbe con
     * action_contract_invalid.
     */
    private fun validateInvokeLlmReplyDeliver(
        action: Action.InvokeLlm,
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        if (action.contextSources.isEmpty()) {
            err("context_sources_empty", "InvokeLlm richiede almeno la context source 'notification'")
        } else {
            if (GenerativeContract.CONTEXT_NOTIFICATION !in action.contextSources)
                err("context_notification_required", "Il contesto InvokeLlm deve includere 'notification'")
            if (action.contextSources.size != action.contextSources.distinct().size)
                err("context_sources_duplicated", "Context sources duplicate in InvokeLlm")
            action.contextSources.filterNot { it in GenerativeContract.CONTEXT_SOURCES }.forEach { source ->
                err("context_source_unsupported", "Context source '$source' non supportata in P1")
            }
        }
        if (!GenerativeContract.isAllowedToolset(action.allowedTools))
            err(
                "allowed_tools_unsupported",
                "allowed_tools deve contenere whatsapp_reply e al più web.search",
            )
        if (action.allowedTools.isEmpty()) err("no_tools", "InvokeLlm senza allowed_tools")

        val hasReplyTool = action.allowedTools.any { it.lowercase() in REPLY_TOOLS }
        if (hasReplyTool && !action.replyTargetSender)
            err("reply_target_unbound", "Un tool di reply deve essere vincolato al mittente del trigger")
        if (action.replyTargetSender) {
            if (!hasReplyTool) err("reply_target_without_tool", "replyTargetSender senza tool di reply")
            validateReplyTarget(trigger, whitelist, err, ReplyKind.GENERATIVE)
        }

        if (action.allowedTools.any { it.startsWith("screen.") || it == "state.read" } && hasReplyTool)
            warn("read_plus_reply", "Tool di lettura + canale in uscita: possibile esfiltrazione")
    }

    /**
     * Sink NOTIFICA (#59): il testo generato diventa una notifica locale, da un trigger qualsiasi.
     * Il titolo è LETTERALE (dal fingerprint approvato, mai dal contenuto del trigger — D2). Nessun
     * vincolo di reply-target: la notifica È il sink, non un canale verso un contatto.
     */
    private fun validateInvokeLlmNotificationDeliver(
        action: Action.InvokeLlm,
        err: (String, String) -> Unit,
    ) {
        val title = action.notificationTitle
        if (title.isNullOrBlank()) {
            err("notification_title_invalid", "Il sink notifica richiede un titolo non vuoto")
        } else if (title.length > MAX_NAME_LENGTH || title.any(Char::isISOControl)) {
            err("notification_title_invalid", "Titolo notifica oltre $MAX_NAME_LENGTH caratteri o con caratteri di controllo")
        }
        if (!GenerativeContract.isNotificationToolset(action.allowedTools))
            err(
                "allowed_tools_unsupported",
                "Il sink notifica ammette al più web.search, mai whatsapp_reply",
            )
        if (action.replyTargetSender)
            err("reply_target_forbidden", "Il sink notifica non può vincolare un destinatario di reply")
        // contextSources: vuota OPPURE subset di {state} — mai 'notification' (il testo nasce dal goal).
        action.contextSources.filterNot { it == GenerativeContract.CONTEXT_STATE }.forEach { source ->
            err("context_source_unsupported", "Context source '$source' non supportata dal sink notifica")
        }
        if (action.contextSources.size != action.contextSources.distinct().size)
            err("context_sources_duplicated", "Context sources duplicate in InvokeLlm")
    }

    /** Chi risponde: `whatsapp_reply`, `notification_reply` (0.8) o l'AI (entrambe le famiglie). */
    private enum class ReplyKind { WHATSAPP, NOTIFICATION, GENERATIVE }

    private fun validateReplyTarget(
        trigger: Trigger,
        whitelist: Set<String>,
        err: (String, String) -> Unit,
        kind: ReplyKind,
    ) {
        val notification = trigger as? Trigger.Notification
        if (notification == null) {
            err("reply_target_no_notification", "Reply richiede un trigger Notification")
            return
        }
        val pkg = notification.pkg
        val packageAllowed = when (kind) {
            ReplyKind.WHATSAPP -> ReplyChannels.isWhatsApp(pkg)
            ReplyKind.NOTIFICATION -> ReplyChannels.isNotificationReplyPackage(pkg)
            ReplyKind.GENERATIVE -> ReplyChannels.isReplyPackage(pkg)
        }
        when {
            packageAllowed -> Unit
            kind != ReplyKind.WHATSAPP && pkg in ReplyChannels.REFUSED_PACKAGES ->
                err("reply_app_unsupported", "App di messaggi senza una chiave di conversazione affidabile")
            kind == ReplyKind.NOTIFICATION ->
                err("reply_wrong_package", "notification_reply richiede Telegram, Signal o Google Messaggi (WhatsApp usa whatsapp_reply)")
            else ->
                err("reply_wrong_package", "WhatsAppReply richiede un package WhatsApp riconosciuto")
        }
        if (notification.isGroup != false)
            err("reply_target_group", "Reply automatiche solo su chat 1:1 verificata")
        val conversationId = notification.conversationId
        if (conversationId.isNullOrBlank())
            err("reply_needs_conversation_id", "Reply automatiche richiedono conversationId")
        else if (conversationId !in whitelist)
            err("target_not_whitelisted", "Conversazione non in whitelist")
        // Fuori da WhatsApp la chat è solo lo shortcut di conversazione della STESSA app (ReplyChannels).
        if (packageAllowed && !conversationId.isNullOrBlank() &&
            !ReplyChannels.isStableConversation(pkg, conversationId)
        ) err("reply_conversation_unstable", "La conversazione non è lo shortcut di conversazione dell'app")
    }

    /**
     * `notification_action` (0.8): agisce sulla notifica del trigger. CLICK preme il pulsante col testo
     * approvato: mai su Argus né sulle app vietate alle macro, ed è sempre segnalato in revisione.
     */
    private fun validateNotificationAction(
        action: Action.NotificationAction,
        trigger: Trigger,
        err: (String, String) -> Unit,
        warn: (String, String) -> Unit,
    ) {
        val notification = trigger as? Trigger.Notification
        if (notification == null)
            err("notification_action_no_notification", "notification_action richiede un trigger Notification")
        NotificationActionPolicy.problems(action).forEach { problem ->
            when (problem) {
                NotificationActionPolicy.Problem.LABEL_MISSING,
                NotificationActionPolicy.Problem.LABEL_UNEXPECTED,
                NotificationActionPolicy.Problem.LABEL_INVALID,
                -> err(
                    "notification_action_label_invalid",
                    "CLICK richiede il testo del pulsante (1..${NotificationActionPolicy.MAX_LABEL_CHARS} " +
                        "caratteri, una riga); DISMISS e SNOOZE non ne hanno",
                )
                NotificationActionPolicy.Problem.LABEL_INTERPOLATED ->
                    err("notification_action_label_interpolated", "Il testo del pulsante è letterale, senza variabili")
                NotificationActionPolicy.Problem.SNOOZE_MISSING,
                NotificationActionPolicy.Problem.SNOOZE_UNEXPECTED,
                NotificationActionPolicy.Problem.SNOOZE_RANGE,
                -> err(
                    "notification_action_snooze_invalid",
                    "SNOOZE richiede snoozeMinutes tra ${NotificationActionPolicy.MIN_SNOOZE_MINUTES} e " +
                        "${NotificationActionPolicy.MAX_SNOOZE_MINUTES}; le altre operazioni non lo usano",
                )
            }
        }
        if (action.op == NotificationActionOp.CLICK) {
            if (notification != null && NotificationActionPolicy.isForbiddenTarget(notification.pkg))
                err("notification_action_forbidden_app", "Non si premono pulsanti nelle notifiche di questa app")
            warn(
                "notification_click_review",
                "Argus premerà per te il pulsante «${action.label.orEmpty()}» della notifica di " +
                    "${notification?.pkg.orEmpty()}",
            )
        }
    }

    private fun validatePackage(value: String, blankCode: String, err: (String, String) -> Unit) {
        if (value.isBlank()) err(blankCode, "Package mancante")
        else if (!PACKAGE_NAME.matches(value)) err("package_invalid", "Package '$value' non valido")
    }

    private fun validateOptionalText(value: String?, code: String, err: (String, String) -> Unit) {
        if (value != null && (value.isBlank() || value.length > MAX_TEXT_LENGTH))
            err(code, "Campo testuale vuoto o troppo lungo")
    }

    private fun validateRequiredText(value: String, max: Int, code: String, err: (String, String) -> Unit) {
        if (value.isBlank() || value.length > max) err(code, "Campo obbligatorio, massimo $max caratteri")
    }

    private fun validHttpUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    /** Una `@` letterale nell'autorità del template (tra `://` e il primo `/`, `?` o `#`). */
    private fun urlHasUserInfo(template: String): Boolean {
        val start = template.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return false
        val end = template.indexOfAny(charArrayOf('/', '?', '#'), start).takeIf { it >= 0 } ?: template.length
        return '@' in template.substring(start, end)
    }
}
