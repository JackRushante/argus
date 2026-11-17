package dev.argus.ui.presentation

import dev.argus.engine.dryrun.ConditionCheck
import dev.argus.engine.dryrun.DryRunCodes
import dev.argus.engine.dryrun.DryRunEnd
import dev.argus.engine.dryrun.DryRunReport
import dev.argus.engine.dryrun.DryRunStep
import dev.argus.engine.dryrun.SimulatedInput
import dev.argus.engine.dryrun.SimulatedInputKind
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.HttpMethod
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.SecretRef
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.SpanPhase
import dev.argus.ui.model.DryRunCodeSpan
import dev.argus.ui.model.DryRunTone
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «Prova a secco»: the steps are rendered from the report's types, the final text in full, external parts marked. */
class DryRunRenderMapperTest {
    private val automation = Automation(
        AutomationId("d"), "Prova", CreatedBy.USER, AutomationStatus.ARMED, Trigger.Manual, listOf(Action.Vibrate(1)),
    )
    private val reason: (String) -> String = { "label:$it" }

    private fun report(vararg steps: DryRunStep, end: DryRunEnd = DryRunEnd.COMPLETED, asArmed: Boolean = false) =
        DryRunReport(automation, SpanPhase.START, Instant.parse("2026-11-17T18:12:00Z").toEpochMilli(), asArmed, steps.toList(), end)

    private fun leaf(
        action: Action,
        resolved: Action? = action,
        tainted: Map<String, List<IntRange>> = emptyMap(),
        code: String? = null,
        stopsRun: Boolean = false,
        input: SimulatedInput? = null,
        path: String = "1",
    ) = DryRunStep.Leaf(ActionPath(path), action, resolved, tainted, code, stopsRun, input)

    @Test
    fun `a shell command is shown in full with the external part marked`() {
        val cmd = "cmd notification post -t Urgente argus 'Sei libero'"
        val step = DryRunRenderMapper.steps(
            report(leaf(Action.RunShell(cmd), tainted = mapOf("cmd" to listOf(39 until cmd.length)))),
            RenderLanguage.IT,
            reason,
        ).single()

        assertEquals(DryRunTone.OK, step.tone)
        assertEquals("Eseguirebbe questo comando", step.title)
        assertTrue(step.shell)
        assertEquals(
            listOf(DryRunCodeSpan("cmd notification post -t Urgente argus "), DryRunCodeSpan("'Sei libero'", external = true)),
            step.code,
        )
        assertEquals(cmd, step.code!!.joinToString("") { it.text })
        assertTrue(step.codeNote!!.contains("apici"))
    }

    @Test
    fun `a secret appears by name only where its value would go`() {
        val call = Action.HttpRequest(
            HttpMethod.GET,
            "https://example.com/api",
            secretHeaders = listOf(SecretRef("Authorization", "ha_token", "Bearer ")),
        )
        val step = DryRunRenderMapper.steps(report(leaf(call)), RenderLanguage.IT, reason).single()
        val text = step.code!!.joinToString("") { it.text }
        assertTrue("GET https://example.com/api" in text)
        assertTrue("Authorization: Bearer ⟨segreto ha_token⟩" in text, text)
    }

    @Test
    fun `a blocked step says why with the label of its code`() {
        val en = DryRunRenderMapper.steps(
            report(leaf(Action.Vibrate(100), code = DryRunCodes.SHIZUKU_UNAVAILABLE)),
            RenderLanguage.EN,
            reason,
        ).single()
        assertEquals(DryRunTone.BLOCKED, en.tone)
        assertTrue(en.title.startsWith("Would not run: "))
        assertTrue(en.detail!!.startsWith("label:shizuku_unavailable"))
        assertTrue("The other actions do not change." in en.detail!!)

        val stop = DryRunRenderMapper.steps(
            report(leaf(Action.RunShell("\${msg} -x"), resolved = null, code = "shell_quote_context", stopsRun = true)),
            RenderLanguage.IT,
            reason,
        ).single()
        assertEquals(DryRunTone.STOPPED, stop.tone)
        // The approved command is still shown in full, never truncated.
        assertEquals("\${msg} -x", stop.code!!.joinToString("") { it.text })
        assertTrue("Il programma si fermerebbe qui." in stop.detail!!)
    }

    @Test
    fun `an AI step offers the approved answers and the picked one`() {
        val ai = Action.InvokeLlm(
            "è urgente?", emptyList(), emptyList(), replyTargetSender = false,
            deliver = GenerativeDeliverMode.CAPTURE_ONLY, captureAs = "urgent", output = OutputSpec(OutputKind.BOOLEAN),
        )
        val step = DryRunRenderMapper.steps(
            report(leaf(ai, input = SimulatedInput(SimulatedInputKind.AI_ANSWER, "step:1", "false", ai.output))),
            RenderLanguage.IT,
            reason,
        ).single()
        assertEquals(DryRunTone.AI, step.tone)
        assertEquals("Chiederebbe all'AI: «è urgente?»", step.title)
        val choice = assertNotNull(step.choice)
        assertEquals(listOf("sì", "no"), choice.options.map { it.label })
        assertEquals("false", choice.selected)
        assertFalse(choice.freeText)
    }

    @Test
    fun `conditions show each result and the value read now`() {
        val window = Condition.TimeWindow("08:00", "22:00", "UTC")
        val wifi = Condition.StateEquals("wifi", dev.argus.engine.model.CmpOp.EQ, "on")
        val check = ConditionCheck(
            Condition.And(listOf(window, wifi)),
            ConditionEvaluator.Result.NOT_MET,
            listOf(
                ConditionCheck(window, ConditionEvaluator.Result.MET),
                ConditionCheck(wifi, ConditionEvaluator.Result.NOT_MET, observed = "off"),
            ),
        )
        val step = DryRunRenderMapper.steps(
            report(DryRunStep.Conditions(check, ConditionEvaluator.Result.NOT_MET), end = DryRunEnd.CONDITIONS_NOT_MET),
            RenderLanguage.IT,
            reason,
        ).single()
        assertEquals(DryRunTone.SKIPPED, step.tone)
        val detail = step.detail!!
        assertTrue("adesso sono le 18:12" in detail, detail)
        assertTrue("falsa · adesso: off" in detail, detail)
    }

    @Test
    fun `nested steps are indented by their path`() {
        assertEquals(0, DryRunRenderMapper.depthOf(ActionPath("3")))
        assertEquals(1, DryRunRenderMapper.depthOf(ActionPath("2.then.1")))
        assertEquals(2, DryRunRenderMapper.depthOf(ActionPath("1.while[3].2.else.1")))
    }

    @Test
    fun `the notes say when the rule is simulated as armed`() {
        val notes = DryRunRenderMapper.notes(report(asArmed = true), verifiedScan = true, RenderLanguage.EN)
        assertEquals(2, notes.size)
        assertTrue(notes.first().contains("not armed"))
    }

    @Test
    fun `spans cut the text at the external ranges`() {
        assertEquals(
            listOf(DryRunCodeSpan("a "), DryRunCodeSpan("b", true), DryRunCodeSpan(" c")),
            DryRunRenderMapper.spans("a b c", listOf(2..2)),
        )
        assertEquals(listOf(DryRunCodeSpan("abc")), DryRunRenderMapper.spans("abc", listOf(5..9)))
    }

    @Test
    fun `dry run strings exist in both languages, translated, with escaped apostrophes`() {
        val string = Regex("""<string name="([^"]+)">(.*?)</string>""")
        fun read(path: String) = string.findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
            .filterKeys { it.startsWith("dry_run_") }
        val english = read("src/main/res/values/strings.xml")
        val italian = read("src/main/res/values-it/strings.xml")
        assertTrue(english.size >= 25)
        assertEquals(english.keys, italian.keys)
        english.forEach { (key, text) ->
            if (key != "dry_run_tone_ai") assertNotEquals(text, italian.getValue(key), key)
        }
        (english.values + italian.values).forEach { text ->
            assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), text)
        }
        assertEquals("Prova a secco", italian["dry_run_title"])
        assertEquals(
            "Simulazione: non viene eseguito nulla. Le condizioni si leggono dal telefono in questo momento.",
            italian["dry_run_banner"],
        )
        assertEquals(
            "Nel Log questa prova non compare: non conta come esecuzione e non consuma il cooldown.",
            italian["dry_run_footer"],
        )
    }
}
