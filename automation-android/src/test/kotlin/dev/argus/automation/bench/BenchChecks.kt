package dev.argus.automation.bench

import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.CompileMetaError
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.Condition
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VarBinding
import dev.argus.engine.runtime.CronSchedule
import dev.argus.engine.safety.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Categorie d'errore del rapporto: nomi stabili, usati nei test e nel `summary.md`. */
internal object FailureCategory {
    const val FORMAT = "format"
    const val TRANSPORT = "transport"
    const val TIMEOUT = "timeout"
    const val BENCH_ERROR = "bench_error"
    const val UNEXPECTED_PREFIX = "unexpected_"
    const val WRONG_TRIGGER = "wrong_trigger"
    const val WRONG_ACTION = "wrong_action"
    const val WRONG_PARAM = "wrong_param"
    const val WRONG_CONDITION = "wrong_condition"
    const val MISSING_VARS = "missing_vars"
    const val EXTRA_VARS = "extra_vars"
    const val MISSING_FLOW = "missing_flow"
    const val VALIDATOR = "validator"
    const val CAPABILITY = "capability"
    const val WRONG_LANGUAGE = "wrong_language"
    const val ACT_EMPTY = "act_empty"
    const val ACT_LANGUAGE = "act_language"
    const val ACT_LINK = "act_link"
    const val ACT_NUMBER = "act_number"
    const val ACT_CONTENT = "act_content"
}

internal data class Failure(val category: String, val message: String)

/** Esito reale di una compile, ridotto alle classi che il banco distingue. */
internal enum class ActualKind(val wire: String) {
    DRAFT("draft"),
    CLARIFICATION("clarification"),
    UNSUPPORTED("unsupported"),
    LIMIT("limit"),
    REFUSAL("refusal"),
    FORMAT_ERROR("format_error"),
    TRANSPORT_ERROR("transport_error"),
}

internal data class Evaluation(val actual: String, val failures: List<Failure>) {
    val passed: Boolean get() = failures.isEmpty()
}

internal object BenchText {
    private val MARKS = Regex("\\p{M}+")

    /** Minuscolo senza accenti: il confronto dei casi non deve dipendere da `è`/`e`. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    fun clip(value: String, max: Int): String = if (value.length <= max) value else value.take(max) + "…"
}

/** Navigazione della bozza come JSON del dominio: lo stesso formato che il modello deve produrre. */
internal object DraftTree {
    fun flatten(actions: List<Action>): List<Action> {
        val out = ArrayList<Action>()
        fun walk(action: Action) {
            out += action
            when (action) {
                is Action.If -> {
                    for (child in action.then) walk(child)
                    for (child in action.orElse) walk(child)
                }
                is Action.While -> for (child in action.body) walk(child)
                else -> Unit
            }
        }
        for (action in actions) walk(action)
        return out
    }

    fun actionJson(action: Action): JsonObject =
        ArgusJson.encodeToJsonElement(Action.serializer(), action).jsonObject

    fun actionType(action: Action): String = actionJson(action).getValue("type").jsonPrimitive.content

    fun triggerJson(trigger: Trigger): JsonObject =
        ArgusJson.encodeToJsonElement(Trigger.serializer(), trigger).jsonObject

    /**
     * Vista di CONFRONTO di un'azione: l'azione come JSON, con le forme equivalenti ricondotte a quella
     * che i casi si aspettano. Non cambia mai cio' che l'app esegue, solo come il banco lo legge:
     * - un `while` con `delayBetweenMs = 0` e una `wait` di N ms nel corpo equivale a `delayBetweenMs = N`
     *   (il ritardo effettivo e' `delayBetweenMs` piu' le `wait` dirette del corpo: se il modello mette
     *   N in entrambi i posti aspetta 2N e NON equivale);
     * - un `${var}` che nomina una variabile `literal` vale il valore della variabile (un letterale e'
     *   CLEAN: il motore lo inserisce cosi' com'e', anche in `run_shell.cmd`).
     */
    fun actionView(action: Action, vars: List<VarBinding>): JsonObject {
        var json = actionJson(action)
        if (action is Action.While) {
            val waits = action.body.filterIsInstance<Action.Wait>().sumOf { it.durationMs }
            if (waits > 0) {
                val effective = JsonPrimitive(action.delayBetweenMs + waits)
                json = JsonObject(json.toMutableMap().apply { put("delayBetweenMs", effective) })
            }
        }
        val literals = vars.filterIsInstance<VarBinding.Literal>().associate { it.name to it.value }
        return if (literals.isEmpty()) json else substituteLiterals(json, literals) as JsonObject
    }

    private val VAR_REFERENCE = Regex("""\$\{([a-z][a-z0-9_]{0,31})\}""")

    private fun substituteLiterals(element: JsonElement, literals: Map<String, String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { substituteLiterals(it.value, literals) })
        is JsonArray -> JsonArray(element.map { substituteLiterals(it, literals) })
        is JsonNull -> element
        is JsonPrimitive -> if (element.isString) {
            JsonPrimitive(VAR_REFERENCE.replace(element.content) { match -> literals[match.groupValues[1]] ?: match.value })
        } else {
            element
        }
    }

    fun conditions(root: Condition?): List<Condition> {
        if (root == null) return emptyList()
        val out = ArrayList<Condition>()
        fun walk(condition: Condition) {
            out += condition
            when (condition) {
                is Condition.And -> for (child in condition.all) walk(child)
                is Condition.Or -> for (child in condition.any) walk(child)
                is Condition.Not -> walk(condition.cond)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    fun conditionJson(condition: Condition): JsonObject =
        ArgusJson.encodeToJsonElement(Condition.serializer(), condition).jsonObject

    /** Risolve `a.b.0.c` dentro [root]: oggetti per chiave, array per indice. */
    fun resolve(root: JsonElement, path: List<String>): JsonElement? {
        var current: JsonElement? = root
        for (segment in path) {
            current = when (val node = current) {
                is JsonObject -> node[segment]
                is JsonArray -> segment.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }
}

/** Valuta una risposta di compile contro un caso, con la stessa catena di controlli dell'app. */
internal object CompileEvaluator {
    fun classify(result: CompileResult): ActualKind {
        val code = result.metaError
        return when {
            code == null -> if (result.draft != null) ActualKind.DRAFT else ActualKind.FORMAT_ERROR
            code == CompileMetaError.CLARIFICATION_REQUIRED -> ActualKind.CLARIFICATION
            code == CompileMetaError.UNSUPPORTED_CAPABILITY -> ActualKind.UNSUPPORTED
            code == CompileMetaError.LIMIT_EXCEEDED -> ActualKind.LIMIT
            code.startsWith("bridge_") || code.startsWith("budget_") -> ActualKind.TRANSPORT_ERROR
            CompileMetaError.isModelAnswer(code) -> ActualKind.REFUSAL
            else -> ActualKind.FORMAT_ERROR
        }
    }

    fun evaluate(case: BenchCase, result: CompileResult, world: BenchWorld, today: LocalDate): Evaluation {
        val kind = classify(result)
        val label = when (kind) {
            ActualKind.FORMAT_ERROR, ActualKind.TRANSPORT_ERROR, ActualKind.REFUSAL ->
                "${kind.wire}:${result.metaError}"
            else -> kind.wire
        }
        return when (kind) {
            ActualKind.TRANSPORT_ERROR -> Evaluation(
                label,
                listOf(Failure(FailureCategory.TRANSPORT, "errore di trasporto ${result.metaError}")),
            )
            ActualKind.FORMAT_ERROR -> Evaluation(
                label,
                listOf(
                    Failure(
                        FailureCategory.FORMAT,
                        "risposta non utilizzabile: ${result.metaError ?: "compile_without_draft"}" +
                            (result.detail?.let { " ($it)" } ?: ""),
                    ),
                ),
            )
            else -> {
                val failures = ArrayList<Failure>()
                if (!isAllowed(case.outcomes, kind)) {
                    failures += Failure(
                        FailureCategory.UNEXPECTED_PREFIX + kind.wire,
                        "esito ${kind.wire}, atteso ${case.outcomes.joinToString("|") { it.wire }}",
                    )
                } else if (kind == ActualKind.DRAFT) {
                    checkDraft(case, requireNotNull(result.draft), world, today, failures)
                }
                checkLanguage(case, result, failures)
                Evaluation(label, failures)
            }
        }
    }

    private fun isAllowed(expected: Set<Outcome>, actual: ActualKind): Boolean = when (actual) {
        ActualKind.DRAFT -> Outcome.DRAFT in expected
        ActualKind.CLARIFICATION -> Outcome.CLARIFICATION in expected || Outcome.NO_DRAFT in expected
        ActualKind.UNSUPPORTED -> Outcome.UNSUPPORTED in expected || Outcome.NO_DRAFT in expected
        ActualKind.LIMIT -> Outcome.LIMIT in expected || Outcome.NO_DRAFT in expected
        ActualKind.REFUSAL -> Outcome.REFUSAL in expected || Outcome.NO_DRAFT in expected
        ActualKind.FORMAT_ERROR, ActualKind.TRANSPORT_ERROR -> false
    }

    private fun checkDraft(
        case: BenchCase,
        draft: AutomationDraft,
        world: BenchWorld,
        today: LocalDate,
        failures: MutableList<Failure>,
    ) {
        // 1. Validatore reale: tutti gli ERRORE bloccano l'armamento nell'app.
        val errors = world.validator.validate(draft, world.whitelistedIds).filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) {
            failures += Failure(
                FailureCategory.VALIDATOR,
                "errori del validatore: " + errors.joinToString(", ") { it.code },
            )
        }
        // 2. Capability: la revisione dell'app segna ERRORE quelle non disponibili sul telefono.
        val missing = CapabilityRequirements.derive(draft.trigger, draft.actions, draft.conditions, draft.vars) -
            world.availableCapabilities(case.manifest)
        if (missing.isNotEmpty()) {
            failures += Failure(
                FailureCategory.CAPABILITY,
                "capability non disponibili: " + missing.sorted().joinToString(", "),
            )
        }
        val expect = case.draft
        checkTrigger(expect, draft, world, today, failures)
        checkActions(expect, draft, failures)
        checkVarsAndFlow(expect, draft, failures)
        checkConditions(expect, draft, failures)
    }

    // --- lingua ----------------------------------------------------------------------------------

    /**
     * Ogni testo scritto PER l'utente deve essere nella lingua dell'interfaccia (`case.lang`, la stessa
     * che il banco passa al modello in `manifest.reply_language`): la frase di risposta e, se c'e' una
     * bozza, nome, titoli e testi delle notifiche, goal. I testi sotto [LanguageCheck.MIN_WORDS] parole
     * non si giudicano.
     */
    private fun checkLanguage(case: BenchCase, result: CompileResult, failures: MutableList<Failure>) {
        val texts = ArrayList<Pair<String, String>>()
        texts += "risposta" to result.reply
        result.draft?.let { texts += userTexts(it) }
        for ((what, text) in texts) {
            if (LanguageCheck.judge(case.lang, text) == false) {
                failures += Failure(
                    FailureCategory.WRONG_LANGUAGE,
                    "$what non e' in ${case.lang}: \"${BenchText.clip(text.replace('\n', ' '), 80)}\"",
                )
            }
        }
    }

    /** I testi della bozza che l'utente legge o che il modello genera per lui. */
    fun userTexts(draft: AutomationDraft): List<Pair<String, String>> = buildList {
        add("nome" to draft.name)
        for (action in DraftTree.flatten(draft.actions)) {
            when (action) {
                is Action.ShowNotification -> {
                    add("titolo della notifica" to action.title)
                    add("testo della notifica" to action.text)
                }
                is Action.InvokeLlm -> {
                    add("goal" to action.goal)
                    action.notificationTitle?.let { add("titolo della notifica" to it) }
                }
                is Action.InvokeLlmV2 -> add("goal" to action.goal)
                else -> Unit
            }
        }
    }

    // --- trigger ---------------------------------------------------------------------------------

    private fun checkTrigger(
        expect: DraftExpectation,
        draft: AutomationDraft,
        world: BenchWorld,
        today: LocalDate,
        failures: MutableList<Failure>,
    ) {
        val trigger = DraftTree.triggerJson(draft.trigger)
        val typeMatcher = expect.trigger["type"]
        if (typeMatcher != null && !JsonMatch.matches(trigger["type"], typeMatcher)) {
            failures += Failure(
                FailureCategory.WRONG_TRIGGER,
                "trigger type ${describe(trigger["type"])}, atteso ${describe(typeMatcher)}",
            )
            return
        }
        for ((field, matcher) in expect.trigger) {
            if (field == "type") continue
            if (!JsonMatch.matches(trigger[field], matcher)) {
                failures += Failure(
                    FailureCategory.WRONG_TRIGGER,
                    "trigger.$field = ${describe(trigger[field])}, atteso ${describe(matcher)}",
                )
            }
        }
        expect.contact?.let { name ->
            val expectedId = world.contactId(name)
            val actualId = (draft.trigger as? Trigger.Notification)?.conversationId
            if (expectedId == null || actualId != expectedId) {
                failures += Failure(
                    FailureCategory.WRONG_TRIGGER,
                    "conversationId non e' il contatto '$name' (atteso l'id in whitelist, trovato ${describe(trigger["conversationId"])})",
                )
            }
        }
        val time = draft.trigger as? Trigger.Time
        if (time != null) {
            if (time.tz != world.zone.id) {
                failures += Failure(
                    FailureCategory.WRONG_TRIGGER,
                    "tz '${time.tz}' diverso dal fuso del telefono ${world.zone.id}",
                )
            }
            checkCron(expect, time, world.zone, failures)
            checkAt(expect, time, world.zone, today, failures)
        } else if (expect.cronFires.isNotEmpty() || expect.cronSilent.isNotEmpty() || expect.at != null) {
            failures += Failure(FailureCategory.WRONG_TRIGGER, "atteso un trigger time, trovato ${describe(trigger["type"])}")
        }
    }

    private fun checkCron(expect: DraftExpectation, time: Trigger.Time, zone: ZoneId, failures: MutableList<Failure>) {
        if (expect.cronFires.isEmpty() && expect.cronSilent.isEmpty()) return
        val cron = time.cron
        if (cron == null) {
            failures += Failure(FailureCategory.WRONG_TRIGGER, "atteso un cron, trovato at/afterMs")
            return
        }
        val schedule = try {
            CronSchedule.parse(cron)
        } catch (error: IllegalArgumentException) {
            failures += Failure(FailureCategory.WRONG_TRIGGER, "cron '$cron' non interpretabile")
            return
        }
        val cronZone = try {
            ZoneId.of(time.tz)
        } catch (error: DateTimeException) {
            zone
        }
        fun fires(local: LocalDateTime): Boolean {
            val instant = local.atZone(cronZone).toInstant()
            return schedule.nextFireAfter(instant.minusSeconds(1), cronZone) == instant
        }
        expect.cronFires.filterNot { fires(it) }.forEach { local ->
            failures += Failure(FailureCategory.WRONG_TRIGGER, "il cron '$cron' non scatta alle $local")
        }
        expect.cronSilent.filter { fires(it) }.forEach { local ->
            failures += Failure(FailureCategory.WRONG_TRIGGER, "il cron '$cron' scatta alle $local, non doveva")
        }
    }

    private fun checkAt(
        expect: DraftExpectation,
        time: Trigger.Time,
        zone: ZoneId,
        today: LocalDate,
        failures: MutableList<Failure>,
    ) {
        val at = time.at ?: return
        val parsed = try {
            LocalDateTime.parse(at)
        } catch (error: DateTimeException) {
            return // il validatore lo segnala come at_invalid
        }
        if (!parsed.atZone(zone).toInstant().isAfter(java.time.Instant.now())) {
            failures += Failure(FailureCategory.WRONG_TRIGGER, "at '$at' e' gia' passato")
        }
        val wanted = expect.at ?: return
        wanted.daysFromToday?.let { days ->
            if (parsed.toLocalDate() != today.plusDays(days.toLong())) {
                failures += Failure(
                    FailureCategory.WRONG_TRIGGER,
                    "at '$at': giorno atteso ${today.plusDays(days.toLong())}",
                )
            }
        }
        wanted.monthDay?.let { monthDay ->
            if (parsed.monthValue != monthDay.monthValue || parsed.dayOfMonth != monthDay.dayOfMonth) {
                failures += Failure(FailureCategory.WRONG_TRIGGER, "at '$at': data attesa $monthDay")
            }
        }
        wanted.time?.let { expectedTime ->
            if (parsed.toLocalTime().truncatedTo(ChronoUnit.MINUTES) != expectedTime) {
                failures += Failure(FailureCategory.WRONG_TRIGGER, "at '$at': ora attesa $expectedTime")
            }
        }
    }

    // --- azioni ----------------------------------------------------------------------------------

    private fun checkActions(expect: DraftExpectation, draft: AutomationDraft, failures: MutableList<Failure>) {
        val actions = DraftTree.flatten(draft.actions)
        val views = actions.map { DraftTree.actionType(it) to DraftTree.actionView(it, draft.vars) }
        val types = actions.map(DraftTree::actionType)
        val typeSet = types.toSet()
        for (entry in expect.actionsInclude) {
            if (entry.split('|').none { it in typeSet }) {
                failures += Failure(
                    FailureCategory.WRONG_ACTION,
                    "manca l'azione $entry (presenti: ${types.distinct().joinToString(", ")})",
                )
            }
        }
        for (entry in expect.actionsExclude) {
            entry.split('|').filter { it in typeSet }.forEach { forbidden ->
                failures += Failure(FailureCategory.WRONG_ACTION, "azione vietata presente: $forbidden")
            }
        }
        val listed = expect.actionsInclude.flatMap { it.split('|') }.toSet()
        for ((path, matcher) in expect.params) {
            val segments = path.split('.')
            val root = segments.first()
            val candidates = views.filter { it.first == root }.map { it.second }
            if (candidates.isEmpty()) {
                if (root !in listed) {
                    failures += Failure(FailureCategory.WRONG_PARAM, "$path: nessuna azione $root")
                }
                continue
            }
            val ok = candidates.any { view -> JsonMatch.matches(DraftTree.resolve(view, segments.drop(1)), matcher) }
            if (!ok) {
                val found = candidates.joinToString(" / ") { describe(DraftTree.resolve(it, segments.drop(1))) }
                failures += Failure(
                    FailureCategory.WRONG_PARAM,
                    "$path = $found, atteso ${describe(matcher)}",
                )
            }
        }
        if (expect.actionsText.isNotEmpty()) {
            // Il testo delle azioni com'e' scritto e come risulta con i letterali al loro posto.
            val text = JsonArray(actions.map(DraftTree::actionJson)).toString() + "\n" +
                JsonArray(views.map { it.second }).toString()
            for (pattern in expect.actionsText) {
                if (!Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    failures += Failure(FailureCategory.WRONG_PARAM, "nelle azioni manca /$pattern/")
                }
            }
        }
    }

    private fun checkVarsAndFlow(expect: DraftExpectation, draft: AutomationDraft, failures: MutableList<Failure>) {
        if (expect.vars.isNotEmpty() || expect.varsExclude.isNotEmpty()) {
            val varTypes = draft.vars.map {
                (ArgusJson.encodeToJsonElement(dev.argus.engine.model.VarBinding.serializer(), it) as JsonObject)
                    .getValue("type").jsonPrimitive.content
            }.toSet()
            for (entry in expect.varsExclude) {
                entry.split('|').filter { it in varTypes }.forEach { forbidden ->
                    failures += Failure(FailureCategory.EXTRA_VARS, "variabile non necessaria presente: $forbidden")
                }
            }
            for (entry in expect.vars) {
                if (entry.split('|').none { it in varTypes }) {
                    failures += Failure(
                        FailureCategory.MISSING_VARS,
                        "manca la variabile $entry (presenti: ${varTypes.joinToString(", ").ifEmpty { "nessuna" }})",
                    )
                }
            }
        }
        if (expect.controlFlow.isNotEmpty()) {
            val types = DraftTree.flatten(draft.actions).map(DraftTree::actionType).toSet()
            for (entry in expect.controlFlow) {
                if (entry.split('|').none { it in types }) {
                    failures += Failure(FailureCategory.MISSING_FLOW, "manca il costrutto $entry")
                }
            }
        }
    }

    // --- condizioni ------------------------------------------------------------------------------

    private fun checkConditions(expect: DraftExpectation, draft: AutomationDraft, failures: MutableList<Failure>) {
        val nodes = DraftTree.conditions(draft.conditions)
        // I fusi delle finestre orarie sono quelli del telefono, come per il trigger orario.
        nodes.filterIsInstance<Condition.TimeWindow>().forEach { window ->
            if (window.tz != BenchWorld.ZONE_ID) {
                failures += Failure(
                    FailureCategory.WRONG_CONDITION,
                    "time_window tz '${window.tz}' diverso dal fuso del telefono ${BenchWorld.ZONE_ID}",
                )
            }
        }
        if (expect.conditionsInclude.isEmpty() && expect.conditionsParams.isEmpty() && expect.conditionsText.isEmpty()) {
            return
        }
        val jsons = nodes.map(DraftTree::conditionJson)
        val types = jsons.map { it.getValue("type").jsonPrimitive.content }.toSet()
        for (entry in expect.conditionsInclude) {
            if (entry.split('|').none { it in types }) {
                failures += Failure(
                    FailureCategory.WRONG_CONDITION,
                    "manca la condizione $entry (presenti: ${types.joinToString(", ").ifEmpty { "nessuna" }})",
                )
            }
        }
        val listed = expect.conditionsInclude.flatMap { it.split('|') }.toSet()
        for ((path, matcher) in expect.conditionsParams) {
            val segments = path.split('.')
            val root = segments.first()
            val candidates = jsons.filter { it.getValue("type").jsonPrimitive.content == root }
            if (candidates.isEmpty()) {
                if (root !in listed) failures += Failure(FailureCategory.WRONG_CONDITION, "$path: nessuna condizione $root")
                continue
            }
            if (candidates.none { JsonMatch.matches(DraftTree.resolve(it, segments.drop(1)), matcher) }) {
                failures += Failure(
                    FailureCategory.WRONG_CONDITION,
                    "$path = " + candidates.joinToString(" / ") { describe(DraftTree.resolve(it, segments.drop(1))) } +
                        ", atteso ${describe(matcher)}",
                )
            }
        }
        if (expect.conditionsText.isNotEmpty()) {
            val text = JsonArray(jsons).toString()
            for (pattern in expect.conditionsText) {
                if (!Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    failures += Failure(FailureCategory.WRONG_CONDITION, "nelle condizioni manca /$pattern/")
                }
            }
        }
    }

    private fun describe(element: JsonElement?): String = when (element) {
        null, is JsonNull -> "assente"
        is JsonPrimitive -> if (element.isString) "\"${element.content}\"" else element.content
        else -> BenchText.clip(element.toString(), 160)
    }
}

/** Controlli sulle risposte generative (sink notifica): lingua, link, numeri. */
internal object ActEvaluator {
    private val MARKDOWN_LINK = Regex("\\[[^\\]]*]\\([^)]*\\)")
    private val URL = Regex("(?i)\\bhttps?://|\\bwww\\.")
    private val DIGIT = Regex("\\d")

    fun evaluate(case: ActCase, result: ActResult): Evaluation {
        val text = result.text
        if (text == null) {
            val code = result.metaError.orEmpty()
            val transport = code.startsWith("bridge_") || code.startsWith("budget_")
            val category = if (transport) FailureCategory.TRANSPORT else FailureCategory.ACT_EMPTY
            return Evaluation(
                "error:$code",
                listOf(Failure(category, "nessun testo generato ($code)")),
            )
        }
        val failures = ArrayList<Failure>()
        if (text.isBlank()) failures += Failure(FailureCategory.ACT_EMPTY, "testo vuoto")
        if (!LanguageCheck.looksLike(case.lang, text)) {
            failures += Failure(FailureCategory.ACT_LANGUAGE, "il testo non e' in ${case.lang}")
        }
        if (MARKDOWN_LINK.containsMatchIn(text) || URL.containsMatchIn(text)) {
            failures += Failure(FailureCategory.ACT_LINK, "il testo contiene un link")
        }
        if (case.expectNumber && !DIGIT.containsMatchIn(text)) {
            failures += Failure(FailureCategory.ACT_NUMBER, "il testo non contiene alcun numero")
        }
        if (case.mustContainAny.isNotEmpty()) {
            val haystack = BenchText.normalize(text)
            if (case.mustContainAny.none { haystack.contains(BenchText.normalize(it)) }) {
                failures += Failure(
                    FailureCategory.ACT_CONTENT,
                    "manca uno tra: " + case.mustContainAny.joinToString(", "),
                )
            }
        }
        return Evaluation("text", failures)
    }
}

/**
 * Euristica di lingua a parole funzionali: un testo e' "nella lingua giusta" se le parole dell'altra
 * lingua non lo dominano. Le liste contengono solo parole che esistono in UNA delle due lingue (niente
 * `a`, `i`, `in`, `no`, `me`, `come`, `do`...) e le elisioni italiane (`l'ora`, `un'ora`, `dell'ufficio`)
 * contano come parole italiane, le contrazioni inglesi (`it's`, `don't`) come inglesi. Un testo breve
 * senza parole funzionali (un prezzo, un nome proprio) non e' giudicabile e passa.
 */
internal object LanguageCheck {
    /** Sotto questa soglia di parole un testo (un titolo, un nome) non si giudica. */
    const val MIN_WORDS = 3

    private val ITALIAN = setOf(
        "il", "lo", "la", "gli", "le", "un", "uno", "una", "di", "del", "dello", "della", "dei", "degli", "delle",
        "che", "è", "sono", "per", "con", "non", "più", "nel", "nello", "nella", "nei", "negli", "nelle", "sul",
        "sullo", "sulla", "sui", "sugli", "sulle", "alla", "alle", "allo", "al", "agli", "dal", "dalla",
        "dalle", "dai", "da", "ma", "anche", "oggi", "ora", "ha", "hanno", "ho", "hai", "ci", "ti", "mi", "tra",
        "fra", "questo", "questa", "questi", "queste", "molto", "circa", "attualmente", "prezzo", "costa", "vale",
        "buongiorno", "buona", "ricorda", "ricordati", "quando", "se", "altrimenti", "quindi", "perché", "perche",
        "già", "sempre", "ogni", "tutti", "tutto", "ecco", "regola", "notifica", "ricevo", "ricevi", "mostra",
        "mostrami", "imposto", "propongo", "attiva", "mentre", "dopo", "prima", "sera", "mattina", "giorno",
        "tuo", "tua", "tuoi", "mio", "mia", "suo", "sua", "viene", "vuoi", "voglio", "posso", "puoi", "può",
        "fai", "fare", "sarà", "verrà", "scatta", "invece", "ancora", "poi", "così", "cosa", "chi",
    )
    private val ENGLISH = setOf(
        "the", "is", "are", "was", "were", "of", "and", "to", "for", "with", "you", "your", "it", "its",
        "this", "that", "at", "as", "on", "has", "have", "will", "today", "now", "currently", "current",
        "about", "from", "by", "be", "an", "or", "not", "but", "price", "right", "good", "remember",
        "when", "if", "then", "else", "otherwise", "every", "each", "only", "also", "can", "should", "would",
        "rule", "notification", "show", "set", "turn", "open", "here", "there", "they", "their", "what",
        "which", "who", "how", "into", "after", "before", "while", "until", "once", "again", "just",
        "propose", "proposed",
    )
    private val WORD = Regex("[\\p{L}']+")
    private val ITALIAN_ELISION = setOf(
        "l", "d", "un", "dell", "all", "nell", "sull", "dall", "quell", "quest", "c", "gl", "anch", "dov", "com",
    )
    private val ENGLISH_CLITIC = setOf("s", "t", "re", "ll", "m", "ve", "d")

    /** Parole totali e parole funzionali per lingua di un testo. */
    data class Count(val words: Int, val italian: Int, val english: Int)

    fun count(text: String): Count {
        var words = 0
        var italian = 0
        var english = 0
        fun plain(word: String) {
            words++
            when (word) {
                in ITALIAN -> italian++
                in ENGLISH -> english++
            }
        }
        for (match in WORD.findAll(text.lowercase(Locale.ROOT).replace('’', '\''))) {
            val token = match.value.trim('\'')
            if (token.isEmpty()) continue
            val cut = token.indexOf('\'')
            if (cut < 0) {
                plain(token)
                continue
            }
            val head = token.substring(0, cut)
            val tail = token.substring(cut + 1)
            when {
                // `l'ora`, `dell'ufficio`: l'elisione e' italiana, poi il resto e' una parola.
                head in ITALIAN_ELISION && tail.isNotEmpty() -> {
                    italian++
                    plain(tail)
                }
                // `it's`, `don't`, `you're`: contrazione inglese.
                tail in ENGLISH_CLITIC -> {
                    words++
                    english++
                }
                else -> {
                    plain(head)
                    if (tail.isNotEmpty()) plain(tail)
                }
            }
        }
        return Count(words, italian, english)
    }

    private fun looksLike(lang: String, count: Count): Boolean {
        val (own, other) = if (lang == "it") count.italian to count.english else count.english to count.italian
        return !(other >= 2 && other > own)
    }

    fun looksLike(lang: String, text: String): Boolean = looksLike(lang, count(text))

    /** `true`/`false` se il testo e' giudicabile (almeno [MIN_WORDS] parole), `null` se e' troppo corto. */
    fun judge(lang: String, text: String): Boolean? {
        val count = count(text)
        return if (count.words < MIN_WORDS) null else looksLike(lang, count)
    }
}
