package dev.argus.ui.presentation

import dev.argus.engine.dryrun.ConditionCheck
import dev.argus.engine.dryrun.DryRunAnswer
import dev.argus.engine.dryrun.DryRunAnswerKeys
import dev.argus.engine.dryrun.DryRunCodes
import dev.argus.engine.dryrun.DryRunEnd
import dev.argus.engine.dryrun.DryRunReport
import dev.argus.engine.dryrun.DryRunSimulator
import dev.argus.engine.dryrun.DryRunStep
import dev.argus.engine.dryrun.SimulatedInput
import dev.argus.engine.dryrun.SimulatedInputKind
import dev.argus.engine.model.Action
import dev.argus.engine.model.Condition
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.UiStep
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.SpanPhase
import dev.argus.ui.model.DryRunChoiceUi
import dev.argus.ui.model.DryRunCodeSpan
import dev.argus.ui.model.DryRunOptionUi
import dev.argus.ui.model.DryRunStepUi
import dev.argus.ui.model.DryRunTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «Prova a secco»: the simulated run as screen steps, rendered from the TYPES of the report (invariant 4), with
 * the final text of each action in full and its external parts marked. Pure JVM, bilingual via [RenderLanguage].
 * [reason] turns a closed code into its human label (the Log's labels); a secret appears only by name.
 */
object DryRunRenderMapper {

    fun steps(
        report: DryRunReport,
        l: RenderLanguage,
        reason: (String) -> String,
        conversationLabels: Map<String, String> = emptyMap(),
    ): List<DryRunStepUi> = report.steps.map { step -> step(step, report, l, reason, conversationLabels) }

    /** How this run is simulated, said plainly. */
    fun notes(report: DryRunReport, verifiedScan: Boolean, l: RenderLanguage): List<String> = buildList {
        if (report.simulatedAsArmed) {
            add(
                l.pick(
                    "This rule is not armed now: the dry run simulates it as if it were.",
                    "Questa regola adesso non è armata: la prova la simula come se lo fosse.",
                ),
            )
        }
        if (report.phase == SpanPhase.EXIT) {
            add(
                l.pick(
                    "It simulates the end as if the rule were in progress. The values to put back are the ones the " +
                        "start would change; the real ones are read only when the rule starts.",
                    "Simula la fine come se la regola fosse in corso. I valori da rimettere sono quelli che l'inizio " +
                        "cambierebbe; quelli veri si leggono solo quando la regola comincia.",
                ),
            )
        }
        if (verifiedScan) {
            add(
                l.pick(
                    "Verified scan: this is a simulation, no tag was read.",
                    "Scansione verificata: è una simulazione, nessun tag è stato letto.",
                ),
            )
        }
        val unreadable = report.end == DryRunEnd.CONDITIONS_UNKNOWN || report.steps.any {
            it is DryRunStep.Flow && it.result == ConditionEvaluator.Result.STATE_UNAVAILABLE
        }
        if (unreadable) {
            add(
                l.pick(
                    "A state could not be read. The dry run does not use Shizuku or root: readings that need them " +
                        "count as unreadable.",
                    "Uno stato non si è potuto leggere. La prova non usa Shizuku né root: le letture che li richiedono " +
                        "risultano non leggibili.",
                ),
            )
        }
    }

    private fun step(
        step: DryRunStep,
        report: DryRunReport,
        l: RenderLanguage,
        reason: (String) -> String,
        labels: Map<String, String>,
    ): DryRunStepUi = when (step) {
        is DryRunStep.TriggerCheck -> {
            val line = withoutWhen(RuleRenderMapper.triggerLineOf(step.trigger, labels, l))
            val end = step.phase == SpanPhase.EXIT
            if (step.matched) {
                DryRunStepUi(
                    DryRunTone.OK,
                    if (end) l.pick("The end trigger matches", "Il trigger di fine corrisponde")
                    else l.pick("The trigger matches", "Il trigger corrisponde"),
                    line,
                )
            } else {
                DryRunStepUi(
                    DryRunTone.SKIPPED,
                    if (end) l.pick("The end trigger does not match", "Il trigger di fine non corrisponde")
                    else l.pick("The trigger does not match", "Il trigger non corrisponde"),
                    line + "\n" + l.pick(
                        "With this event the rule would not fire.",
                        "Con questo evento la regola non scatterebbe.",
                    ),
                )
            }
        }
        is DryRunStep.Blocked -> DryRunStepUi(
            DryRunTone.BLOCKED,
            l.pick("It would not fire", "Non scatterebbe"),
            reason(step.code) + "\n" + l.pick("No action would run.", "Nessuna azione partirebbe."),
        )
        is DryRunStep.Conditions -> conditions(step, report, l)
        is DryRunStep.Confirm -> DryRunStepUi(
            DryRunTone.ASK,
            l.pick("It would ask for confirmation first", "Chiederebbe conferma prima di eseguire"),
            l.pick(
                "In the dry run no notification appears. Pick the answer to simulate:",
                "In prova non compare nessuna notifica. Scegli la risposta da simulare:",
            ),
            choice = DryRunChoiceUi(
                key = step.key,
                options = listOf(
                    DryRunOptionUi(DryRunAnswerKeys.CONFIRM_RUN, l.pick("Run", "Esegui")),
                    DryRunOptionUi(DryRunAnswerKeys.CONFIRM_CANCEL, l.pick("Cancel", "Annulla")),
                    DryRunOptionUi("", l.pick("No answer", "Nessuna risposta"), noAnswer = true),
                ),
                selected = (step.answer as? DryRunAnswer.Text)?.value,
                noAnswerSelected = step.answer == DryRunAnswer.NoAnswer,
            ),
        )
        is DryRunStep.Restore -> DryRunStepUi(
            if (step.blocked.isEmpty()) DryRunTone.OK else DryRunTone.BLOCKED,
            l.pick("It would put back how it was", "Rimetterebbe com'era"),
            if (step.slots.isEmpty()) {
                l.pick("No value to put back.", "Nessun valore da rimettere.")
            } else {
                step.slots.joinToString("\n") { slot ->
                    val label = RuleSpanRender.slotLabel(slot, l)
                    step.blocked[slot.key]?.let { code -> "$label · ${l.pick("not", "no")}: ${reason(code)}" } ?: label
                }
            },
        )
        is DryRunStep.Flow -> {
            val lines = RuleRenderMapper.conditionLinesOf(step.action.flowCondition() ?: Condition.BooleanLiteral(true), l, step.loop)
            val result = resultWord(step.result, l)
            val outcome = when {
                step.result == ConditionEvaluator.Result.STATE_UNAVAILABLE ->
                    l.pick("The program would stop: a state cannot be read.", "Il programma si fermerebbe: uno stato non si legge.")
                step.loop && step.result == ConditionEvaluator.Result.MET -> l.pick("Another round.", "Un altro giro.")
                step.loop -> l.pick("The loop ends.", "Il ciclo finisce.")
                step.result == ConditionEvaluator.Result.MET -> l.pick("It takes «Then».", "Prende «Allora».")
                else -> l.pick("It takes «Otherwise».", "Prende «Altrimenti».")
            }
            DryRunStepUi(
                if (step.result == ConditionEvaluator.Result.STATE_UNAVAILABLE) DryRunTone.STOPPED else DryRunTone.INFO,
                (if (step.loop) l.pick("Loop condition $result", "Condizione del ciclo $result")
                else l.pick("Condition $result", "Condizione $result")),
                lines.joinToString("\n") + "\n" + outcome,
                depth = depthOf(step.path),
            )
        }
        is DryRunStep.Wait -> DryRunStepUi(
            DryRunTone.INFO,
            l.pick("It would wait ", "Aspetterebbe ") + RuleRenderMapper.humanizeDuration(step.durationMs, l),
            l.pick("In the dry run nothing waits.", "In prova non si aspetta."),
            depth = depthOf(step.path),
        )
        is DryRunStep.Question -> question(step, l, reason)
        is DryRunStep.Leaf -> leaf(step, l, reason)
        is DryRunStep.Stopped -> if (step.code == DryRunCodes.STEP_LIMIT) {
            DryRunStepUi(
                DryRunTone.STOPPED,
                l.pick("The dry run stops here", "La prova si ferma qui"),
                l.pick(
                    "After ${DryRunSimulator.MAX_STEPS} steps the simulation stops; a real run would go on.",
                    "Dopo ${DryRunSimulator.MAX_STEPS} passi la simulazione si ferma; un'esecuzione vera continuerebbe.",
                ),
            )
        } else {
            DryRunStepUi(DryRunTone.STOPPED, l.pick("The program would stop", "Il programma si fermerebbe"), reason(step.code))
        }
    }

    private fun conditions(step: DryRunStep.Conditions, report: DryRunReport, l: RenderLanguage): DryRunStepUi {
        val lines = mutableListOf<String>()
        fun walk(check: ConditionCheck, depth: Int) {
            val pad = "  ".repeat(depth)
            val line = RuleRenderMapper.conditionLinesOf(check.condition, l).first().trimStart()
            val observed = observedText(check, report.atMillis, l)
            val result = resultWord(check.result, l)
            lines += "$pad$line · $result" + (observed?.let { " · $it" } ?: "")
            check.children.forEach { walk(it, depth + 1) }
        }
        walk(step.check, 0)
        return when (step.result) {
            ConditionEvaluator.Result.MET -> DryRunStepUi(
                DryRunTone.OK,
                l.pick("Conditions true", "Condizioni vere"),
                lines.joinToString("\n"),
            )
            ConditionEvaluator.Result.NOT_MET -> DryRunStepUi(
                DryRunTone.SKIPPED,
                l.pick("Conditions false", "Condizioni false"),
                lines.joinToString("\n") + "\n" + l.pick("The rule would not fire now.", "Adesso la regola non scatterebbe."),
            )
            ConditionEvaluator.Result.STATE_UNAVAILABLE -> DryRunStepUi(
                DryRunTone.BLOCKED,
                l.pick("A condition cannot be read", "Una condizione non si legge"),
                lines.joinToString("\n") + "\n" + l.pick(
                    "A state that cannot be read never counts as true: the rule would not fire.",
                    "Uno stato che non si legge non vale mai come vero: la regola non scatterebbe.",
                ),
            )
        }
    }

    private fun observedText(check: ConditionCheck, atMillis: Long, l: RenderLanguage): String? {
        val condition = check.condition
        if (condition is Condition.TimeWindow) {
            val time = runCatching {
                Instant.ofEpochMilli(atMillis).atZone(ZoneId.of(condition.tz)).format(DateTimeFormatter.ofPattern("HH:mm"))
            }.getOrNull() ?: return null
            return l.pick("now it is $time", "adesso sono le $time")
        }
        val value = check.observed ?: return null
        val shown = if (value.isEmpty()) l.pick("none", "nessuna") else VisibleText.singleLine(value)
        return l.pick("now: $shown", "adesso: $shown")
    }

    private fun resultWord(result: ConditionEvaluator.Result, l: RenderLanguage): String = when (result) {
        ConditionEvaluator.Result.MET -> l.pick("true", "vera")
        ConditionEvaluator.Result.NOT_MET -> l.pick("false", "falsa")
        ConditionEvaluator.Result.STATE_UNAVAILABLE -> l.pick("unreadable", "non leggibile")
    }

    private fun question(step: DryRunStep.Question, l: RenderLanguage, reason: (String) -> String): DryRunStepUi {
        val prompt = step.prompt
        val options = step.action.choices.map { DryRunOptionUi(it, it) } +
            DryRunOptionUi("", l.pick("No answer", "Nessuna risposta"), noAnswer = true)
        val explain = l.pick(
            "In the dry run no notification appears. Pick the answer to simulate:",
            "In prova non compare nessuna notifica. Scegli la risposta da simulare:",
        )
        val code = step.code
        return DryRunStepUi(
            tone = if (code == null) DryRunTone.ASK else DryRunTone.STOPPED,
            title = l.pick("It would ask you", "Ti chiederebbe"),
            detail = if (code == null) explain else reason(code) + "\n" + l.pick(
                "The program would stop here.",
                "Il programma si fermerebbe qui.",
            ),
            code = prompt?.let { spans(it, step.promptTainted) },
            codeNote = externalNote(prompt != null && step.promptTainted.isNotEmpty(), shell = false, l),
            choice = DryRunChoiceUi(
                key = step.key,
                options = options,
                selected = (step.answer as? DryRunAnswer.Text)?.value,
                noAnswerSelected = step.answer == DryRunAnswer.NoAnswer,
            ),
            depth = depthOf(step.path),
        )
    }

    private fun leaf(step: DryRunStep.Leaf, l: RenderLanguage, reason: (String) -> String): DryRunStepUi {
        val shown = step.resolved ?: step.action
        val row = RuleRenderMapper.actionRowOf(shown, l)
        val tainted = if (step.resolved != null) step.tainted else emptyMap()
        val code = codeView(shown, tainted, l)
        val isShell = shown is Action.RunShell
        val external = tainted.values.any { it.isNotEmpty() }
        val input = step.input
        val ai = shown is Action.InvokeLlm || shown is Action.InvokeLlmV2
        val blocked = step.code
        val title = when {
            blocked != null -> l.pick("Would not run: ", "Non partirebbe: ") + row.label
            ai -> l.pick("It would ask the AI: «", "Chiederebbe all'AI: «") + aiGoal(step.action) + "»"
            isShell -> l.pick("It would run this command", "Eseguirebbe questo comando")
            else -> l.pick("It would run: ", "Eseguirebbe: ") + row.label
        }
        val detail = when {
            blocked != null -> reason(blocked) + "\n" + if (step.stopsRun) {
                l.pick("The program would stop here.", "Il programma si fermerebbe qui.")
            } else {
                l.pick("The other actions do not change.", "Le altre azioni non cambiano.")
            }
            ai && input != null -> l.pick(
                "In the dry run the AI is not called. Pick the answer to simulate:",
                "In prova l'AI non viene chiamata. Scegli la risposta da simulare:",
            )
            ai -> l.pick(
                "In the dry run the AI is not called: no reply and no notification.",
                "In prova l'AI non viene chiamata: nessuna risposta e nessuna notifica.",
            )
            input?.kind == SimulatedInputKind.SHELL_OUTPUT -> l.pick(
                "Its output becomes a variable. Type the output to simulate:",
                "La sua uscita diventa una variabile. Scrivi l'uscita da simulare:",
            )
            input?.kind == SimulatedInputKind.HTTP_RESPONSE -> l.pick(
                "The response becomes a variable, as external data. Type the response body to simulate:",
                "La risposta diventa una variabile, come dato esterno. Scrivi il corpo della risposta da simulare:",
            )
            code == null -> row.detail
            else -> null
        }
        return DryRunStepUi(
            tone = when {
                blocked != null && step.stopsRun -> DryRunTone.STOPPED
                blocked != null -> DryRunTone.BLOCKED
                ai -> DryRunTone.AI
                else -> DryRunTone.OK
            },
            title = title,
            detail = detail,
            code = code,
            shell = isShell,
            codeNote = externalNote(external, isShell, l),
            choice = input?.let { choiceFor(it, step.action, l) },
            depth = depthOf(step.path),
        )
    }

    private fun aiGoal(action: Action): String = when (action) {
        is Action.InvokeLlm -> VisibleText.singleLine(action.goal)
        is Action.InvokeLlmV2 -> VisibleText.singleLine(action.goal)
        else -> ""
    }

    private fun choiceFor(input: SimulatedInput, action: Action, l: RenderLanguage): DryRunChoiceUi {
        val output = input.output
        if (output != null) {
            return when (output.kind) {
                OutputKind.CHOICE -> DryRunChoiceUi(
                    key = input.key,
                    options = output.choices.map { DryRunOptionUi(it, it) },
                    selected = input.answer,
                )
                OutputKind.BOOLEAN -> DryRunChoiceUi(
                    key = input.key,
                    options = listOf(DryRunOptionUi("true", l.pick("yes", "sì")), DryRunOptionUi("false", "no")),
                    selected = when (input.answer.trim()) {
                        "yes", "true" -> "true"
                        "no", "false" -> "false"
                        else -> null
                    },
                )
                OutputKind.NUMBER -> DryRunChoiceUi(
                    key = input.key,
                    freeText = true,
                    text = input.answer,
                    numeric = true,
                    hint = l.pick(
                        "A whole number from ${output.min} to ${output.max}",
                        "Un numero intero da ${output.min} a ${output.max}",
                    ),
                )
            }
        }
        return when {
            input.kind == SimulatedInputKind.AI_ANSWER -> DryRunChoiceUi(
                key = input.key,
                freeText = true,
                text = input.answer,
                hint = l.pick("The AI's answer, simulated", "La risposta dell'AI, simulata"),
            )
            input.kind == SimulatedInputKind.SHELL_OUTPUT -> DryRunChoiceUi(
                key = input.key,
                freeText = true,
                text = input.answer,
                hint = l.pick("The command's output, simulated", "L'uscita del comando, simulata"),
            )
            else -> DryRunChoiceUi(
                key = input.key,
                freeText = true,
                text = input.answer,
                hint = ((action as? Action.HttpRequest)?.extract?.let {
                    l.pick("Response body; the rule reads $it", "Corpo della risposta; la regola legge $it")
                } ?: l.pick("Response body", "Corpo della risposta")),
            )
        }
    }

    private fun externalNote(external: Boolean, shell: Boolean, l: RenderLanguage): String? = when {
        !external -> null
        shell -> l.pick(
            "The highlighted part comes from outside (untrusted data): it goes in quoted, as a single argument.",
            "La parte evidenziata viene da fuori (dato non attendibile): entra tra apici, come un solo argomento.",
        )
        else -> l.pick(
            "The highlighted part comes from outside (untrusted data).",
            "La parte evidenziata viene da fuori (dato non attendibile).",
        )
    }

    /**
     * The final text of an action, as the executor would get it: the command, the request with every header (a
     * secret only by name), the message and its recipient, the intent. Null for actions whose row says it all.
     */
    internal fun codeView(action: Action, tainted: Map<String, List<IntRange>>, l: RenderLanguage): List<DryRunCodeSpan>? {
        fun field(label: String, text: String) = spans(text, tainted[label].orEmpty())
        val newline = DryRunCodeSpan("\n")
        return when (action) {
            is Action.RunShell -> field("cmd", action.cmd)
            is Action.OpenUrl -> field("url", action.url)
            is Action.ShowNotification -> field("title", action.title) + newline + field("text", action.text)
            is Action.WhatsAppReply -> field("text", action.text)
            is Action.NotificationReply -> field("text", action.text)
            is Action.CopyText -> field("text", action.text)
            is Action.Speak -> field("text", action.text)
            is Action.InputText -> field("text", action.text)
            is Action.SendSms -> listOf(DryRunCodeSpan(l.pick("To: ", "A: "))) + field("number", action.number) +
                newline + field("text", action.text)
            is Action.CallNumber -> field("number", action.number)
            is Action.SendIntent -> buildList {
                add(DryRunCodeSpan(action.kind.name.lowercase()))
                action.action?.let { add(DryRunCodeSpan(" -a $it")) }
                action.data?.let { add(DryRunCodeSpan(" -d $it")) }
                action.mimeType?.let { add(DryRunCodeSpan(" -t $it")) }
                val pkg = action.pkg
                val cls = action.cls
                when {
                    pkg != null && cls != null -> add(DryRunCodeSpan(" -n $pkg/$cls"))
                    pkg != null -> add(DryRunCodeSpan(" -p $pkg"))
                    cls != null -> add(DryRunCodeSpan(" -n $cls"))
                }
                action.extras.forEachIndexed { index, extra ->
                    add(DryRunCodeSpan("\n${extra.type.name.lowercase()} ${extra.name} = "))
                    addAll(field("extras.$index.value", extra.value))
                }
            }
            is Action.HttpRequest -> buildList {
                add(DryRunCodeSpan("${action.method.name} ${action.url}"))
                action.secretQuery.orEmpty().forEach { ref ->
                    add(DryRunCodeSpan("\n?${ref.name}=${ref.prefix}${secretMarker(ref.secret, l)}"))
                }
                action.headers.orEmpty().forEach { (name, value) -> add(DryRunCodeSpan("\n$name: $value")) }
                action.secretHeaders.orEmpty().forEach { ref ->
                    add(DryRunCodeSpan("\n${ref.name}: ${ref.prefix}${secretMarker(ref.secret, l)}"))
                }
                action.contentType?.let { add(DryRunCodeSpan("\nContent-Type: $it")) }
                action.body?.let { body ->
                    add(DryRunCodeSpan("\n\n"))
                    addAll(field("body", body))
                }
            }
            is Action.SetStored -> when (action.op) {
                StoredOp.SET -> listOf(DryRunCodeSpan("${action.key} = ")) + field("value", action.value.orEmpty())
                StoredOp.INCREMENT -> listOf(DryRunCodeSpan("${action.key} += ${action.value ?: "1"}"))
                StoredOp.TOGGLE -> listOf(DryRunCodeSpan("${action.key} = !${action.key}"))
                StoredOp.CLEAR -> listOf(DryRunCodeSpan(l.pick("clear ", "cancella ") + action.key))
            }
            is Action.WriteSetting -> listOf(DryRunCodeSpan("${action.namespace.name.lowercase()} ${action.key} = ")) +
                field("value", action.value)
            is Action.UiSteps -> action.steps.withIndex().filter { it.value is UiStep.Type }
                .flatMapIndexed { position, (index, step) ->
                    (if (position > 0) listOf(newline) else emptyList()) +
                        DryRunCodeSpan(l.pick("type: ", "scrive: ")) + field("steps.$index.text", (step as UiStep.Type).text)
                }
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun secretMarker(name: String, l: RenderLanguage): String = l.pick("⟨secret $name⟩", "⟨segreto $name⟩")

    /** [text] cut at [ranges] (sorted, inside the text): the marked parts are external. */
    internal fun spans(text: String, ranges: List<IntRange>): List<DryRunCodeSpan> {
        val valid = ranges.filter { it.first >= 0 && it.last < text.length && !it.isEmpty() }.sortedBy { it.first }
        if (valid.isEmpty()) return listOf(DryRunCodeSpan(text))
        val out = mutableListOf<DryRunCodeSpan>()
        var cursor = 0
        for (range in valid) {
            if (range.first < cursor) continue
            if (range.first > cursor) out += DryRunCodeSpan(text.substring(cursor, range.first))
            out += DryRunCodeSpan(text.substring(range.first, range.last + 1), external = true)
            cursor = range.last + 1
        }
        if (cursor < text.length) out += DryRunCodeSpan(text.substring(cursor))
        return out
    }

    /** How deep a step sits in `if` and `while` blocks (each adds a `.then.n`, `.else.n` or `.while[i].n`). */
    internal fun depthOf(path: ActionPath): Int = (path.value.count { it == '.' }) / 2

    private fun Action.flowCondition(): Condition? = when (this) {
        is Action.If -> condition
        is Action.While -> condition
        else -> null
    }

    private fun withoutWhen(line: String): String =
        listOf(RuleRenderMapper.whenPrefix(RenderLanguage.EN), RuleRenderMapper.whenPrefix(RenderLanguage.IT))
            .firstOrNull { line.startsWith(it) }?.let { line.removePrefix(it).replaceFirstChar(Char::uppercaseChar) } ?: line
}
