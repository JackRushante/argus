package dev.argus.engine.model

import dev.argus.engine.runtime.StateReadPlanner
import dev.argus.engine.runtime.journalType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ui_steps` (0.5.1): modello, wire, capability, privilegio e policy dei passi. */
class UiStepsModelTest {
    private val macro = Action.UiSteps(
        pkg = "com.example.shop",
        steps = listOf(
            UiStep.WaitFor(text = "Cerca", timeoutMs = 8_000),
            UiStep.Click(id = "search_box"),
            UiStep.Type("scarpe"),
            UiStep.Scroll(ScrollDirection.DOWN),
            UiStep.Click(text = "Aggiungi", index = 1),
            UiStep.Check(text = "Nel carrello", present = true),
            UiStep.Back,
            UiStep.Home,
        ),
    )

    @Test
    fun `the macro round trips through the wire format`() {
        val text = ArgusJson.encodeToString(Action.serializer(), macro)
        assertEquals(macro, ArgusJson.decodeFromString(Action.serializer(), text), text)
    }

    @Test
    fun `steps are discriminated by op and the action by type`() {
        val root = Json.parseToJsonElement(ArgusJson.encodeToString(Action.serializer(), macro)).jsonObject
        assertEquals("ui_steps", root.getValue("type").jsonPrimitive.content)
        val ops = root.getValue("steps").jsonArray.map { it.jsonObject.getValue("op").jsonPrimitive.content }
        assertEquals(
            listOf("wait_for", "click", "type", "scroll", "click", "check", "back", "home"),
            ops,
        )
        // Il passo `type` ha un campo `text`, mai un discriminatore `type` che lo confonderebbe con l'azione.
        val typeStep = root.getValue("steps").jsonArray[2].jsonObject
        assertFalse("type" in typeStep.keys)
    }

    @Test
    fun `the wire the model writes is understood`() {
        val wire = """
            {"type":"ui_steps","pkg":null,"steps":[
              {"op":"wait_for","text":"Invia","timeoutMs":3000},
              {"op":"click","text":"Invia","desc":null},
              {"op":"scroll","dir":"up"},
              {"op":"check","text":"Inviato","present":false}
            ]}
        """.trimIndent()
        val action = ArgusJson.decodeFromString(Action.serializer(), wire) as Action.UiSteps
        assertNull(action.pkg)
        assertEquals(UiStep.WaitFor(text = "Invia", timeoutMs = 3_000), action.steps[0])
        assertEquals(UiStep.Click(text = "Invia"), action.steps[1])
        assertEquals(UiStep.Scroll(ScrollDirection.UP), action.steps[2])
        assertEquals(UiStep.Check("Inviato", present = false), action.steps[3])
    }

    @Test
    fun `wait_for without a timeout gets the default and the engine writes the direction uppercase`() {
        val action = ArgusJson.decodeFromString(
            Action.serializer(),
            """{"type":"ui_steps","steps":[{"op":"wait_for","id":"ok"},{"op":"scroll","dir":"down"}]}""",
        ) as Action.UiSteps
        assertEquals(UiStepsPolicy.DEFAULT_WAIT_MS, (action.steps[0] as UiStep.WaitFor).timeoutMs)
        assertTrue("\"dir\":\"DOWN\"" in ArgusJson.encodeToString(Action.serializer(), action))
    }

    @Test
    fun `an unknown op or direction is not understood`() {
        assertFailsWith<Exception> {
            ArgusJson.decodeFromString(
                Action.serializer(),
                """{"type":"ui_steps","steps":[{"op":"swipe","dir":"UP"}]}""",
            )
        }
        assertFailsWith<Exception> {
            ArgusJson.decodeFromString(
                Action.serializer(),
                """{"type":"ui_steps","steps":[{"op":"scroll","dir":"LEFT"}]}""",
            )
        }
    }

    @Test
    fun `the action is deterministic, needs no shell and has its own capability`() {
        assertEquals(ActionTier.DETERMINISTIC, macro.tier)
        assertEquals(ActionPrivilege.BASE, ActionPrivileges.of(macro))
        assertFalse(ActionPrivileges.requiresShizuku(macro))
        val derived = CapabilityRequirements.derive(Trigger.Immediate, listOf(macro))
        assertEquals(setOf(CapabilityIds.TRIGGER_IMMEDIATE, "action.ui_steps"), derived)
        assertEquals("ui_steps", macro.journalType())
        assertTrue(StateReadPlanner.forAction(macro).isEmpty)
    }

    @Test
    fun `a nested macro still requires the capability`() {
        val nested = Action.If(Condition.BooleanLiteral(true), then = listOf(macro))
        assertTrue("action.ui_steps" in CapabilityRequirements.derive(Trigger.Immediate, listOf(nested)))
    }

    @Test
    fun `argus and the security screens are forbidden packages`() {
        listOf(
            "dev.argus",
            "dev.argus.debug",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        ).forEach { assertTrue(UiStepsPolicy.isForbiddenPackage(it), it) }
        listOf("com.example.shop", "dev.argusfake", "com.whatsapp").forEach {
            assertFalse(UiStepsPolicy.isForbiddenPackage(it), it)
        }
    }

    @Test
    fun `the budget counts the opening and each step and never passes the macro cap`() {
        val short = Action.UiSteps(pkg = null, steps = listOf(UiStep.Back, UiStep.Home))
        assertEquals(2 * UiStepsPolicy.SETTLE_MS, UiStepsPolicy.budgetMs(short))
        val waits = Action.UiSteps(
            pkg = "com.example.shop",
            steps = List(20) { UiStep.WaitFor(text = "x", timeoutMs = 15_000) },
        )
        assertEquals(UiStepsPolicy.MAX_MACRO_MS, UiStepsPolicy.budgetMs(waits))
    }

    @Test
    fun `step problems name the broken limit`() {
        fun problem(step: UiStep, hasApp: Boolean = true) = UiStepsPolicy.stepProblem(step, hasApp)
        assertNull(problem(UiStep.Click(text = "OK")))
        assertEquals(UiStepProblem.SELECTOR_MISSING, problem(UiStep.Click()))
        assertEquals(UiStepProblem.SELECTOR_EMPTY, problem(UiStep.Click(text = "  ")))
        assertEquals(UiStepProblem.SELECTOR_EMPTY, problem(UiStep.Click(text = "OK", desc = "")))
        assertEquals(UiStepProblem.SELECTOR_TOO_LONG, problem(UiStep.Click(text = "a".repeat(201))))
        assertNull(problem(UiStep.Click(text = "a".repeat(200))))
        assertEquals(UiStepProblem.SELECTOR_CONTROL, problem(UiStep.Click(id = "a\nb")))
        assertEquals(UiStepProblem.INDEX, problem(UiStep.Click(text = "OK", index = -1)))
        assertEquals(UiStepProblem.INDEX, problem(UiStep.Click(text = "OK", index = 51)))
        assertNull(problem(UiStep.Click(text = "OK", index = 50)))
        assertEquals(UiStepProblem.TIMEOUT, problem(UiStep.WaitFor(text = "x", timeoutMs = 15_001)))
        assertEquals(UiStepProblem.TIMEOUT, problem(UiStep.WaitFor(text = "x", timeoutMs = 50)))
        assertEquals(UiStepProblem.SELECTOR_EMPTY, problem(UiStep.WaitFor(text = "")))
        assertEquals(UiStepProblem.TYPE_EMPTY, problem(UiStep.Type("")))
        assertEquals(UiStepProblem.TYPE_TOO_LONG, problem(UiStep.Type("a".repeat(1_001))))
        assertEquals(UiStepProblem.SELECTOR_EMPTY, problem(UiStep.Check("", present = true)))
        assertNull(problem(UiStep.Back))
    }

    @Test
    fun `a wait with no target waits for the app, so it needs a package to open`() {
        val wait = UiStep.WaitFor(timeoutMs = 5_000)
        assertNull(UiStepsPolicy.stepProblem(wait, hasApp = true))
        assertEquals(UiStepProblem.WAIT_NO_TARGET, UiStepsPolicy.stepProblem(wait, hasApp = false))
        // Il tempo resta nei limiti anche per l'attesa dell'app.
        assertEquals(UiStepProblem.TIMEOUT, UiStepsPolicy.stepProblem(UiStep.WaitFor(timeoutMs = 20_000), hasApp = true))
        // Un bersaglio vuoto non è «nessun bersaglio»: è un errore a sé.
        assertEquals(UiStepProblem.SELECTOR_EMPTY, UiStepsPolicy.stepProblem(UiStep.WaitFor(id = " "), hasApp = true))

        val withApp = Action.UiSteps("com.example.shop", listOf(wait, UiStep.Click(text = "OK")))
        val withoutApp = Action.UiSteps(null, listOf(wait, UiStep.Click(text = "OK")))
        assertTrue(UiStepsPolicy.isResolvedShapeValid(withApp))
        assertFalse(UiStepsPolicy.isResolvedShapeValid(withoutApp))
    }

    @Test
    fun `waiting for the app costs its timeout in the budget`() {
        val action = Action.UiSteps("com.example.shop", listOf(UiStep.WaitFor(timeoutMs = 4_000)))
        assertEquals(UiStepsPolicy.OPEN_TIMEOUT_MS + 4_000, UiStepsPolicy.budgetMs(action))
    }

    @Test
    fun `placeholders are literal only outside the type step`() {
        assertTrue(UiStepsPolicy.literalFieldsOnly(UiStep.Type("\${sms}")))
        assertFalse(UiStepsPolicy.literalFieldsOnly(UiStep.Click(text = "\${x}")))
        assertFalse(UiStepsPolicy.literalFieldsOnly(UiStep.WaitFor(id = "\${x}")))
        assertFalse(UiStepsPolicy.literalFieldsOnly(UiStep.Check("\${x}", true)))
    }

    @Test
    fun `an approved macro keeps a stable fingerprint across a round trip`() {
        val rule = Automation(
            id = AutomationId("a1"),
            name = "macro",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Immediate,
            actions = listOf(macro),
        )
        val before = ApprovalFingerprints.of(rule)
        val reread = ArgusJson.decodeFromString(
            Automation.serializer(),
            ArgusJson.encodeToString(Automation.serializer(), rule),
        )
        assertEquals(before, ApprovalFingerprints.of(reread))
        val edited = rule.copy(actions = listOf(macro.copy(pkg = "com.example.other")))
        assertNotEquals(before, ApprovalFingerprints.of(edited))
    }
}
