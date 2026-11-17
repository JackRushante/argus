package dev.argus.automation.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.argus.automation.RenderLanguageSource
import dev.argus.automation.dryrun.DryRunRuleReader
import dev.argus.engine.brain.Brain
import dev.argus.engine.dryrun.DryRunFacts
import dev.argus.engine.dryrun.DryRunFactsSource
import dev.argus.engine.dryrun.DryRunField
import dev.argus.engine.dryrun.DryRunSimulator
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.GenerativeDeliverMode
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.ResolvedActionExecutor
import dev.argus.engine.runtime.SpanStore
import dev.argus.engine.runtime.StoredValueStore
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.UserModeStore
import dev.argus.engine.secrets.SecretVault
import dev.argus.ui.model.DryRunTone
import dev.argus.ui.presentation.RenderLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class DryRunViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<DryRunViewModel>()

    private val allow = object : FirePolicy {
        override suspend fun evaluate(automation: Automation, event: TriggerEvent) = FirePolicyDecision.Allow
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun signed(trigger: Trigger, actions: List<Action>): Automation {
        val unsigned = Automation(AutomationId("r1"), "Messaggio urgente", CreatedBy.USER, AutomationStatus.ARMED, trigger, actions)
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private fun viewModel(rule: Automation?, id: String = "r1"): DryRunViewModel = DryRunViewModel(
        savedStateHandle = SavedStateHandle(mapOf("id" to id)),
        rules = DryRunRuleReader(
            automation = { requested -> rule?.takeIf { it.id == requested } },
            drafts = { emptyList() },
            conversationLabels = { mapOf(CONV to "Marta") },
        ),
        simulator = DryRunSimulator(
            firePolicy = allow,
            readState = { DeviceState() },
            facts = DryRunFactsSource { DryRunFacts(whitelistedConversationIds = setOf(CONV)) },
        ),
        languageSource = RenderLanguageSource { RenderLanguage.IT },
    ).also(models::add)

    private val chat = Trigger.Notification("com.whatsapp", conversationId = CONV, isGroup = false)

    @Test
    fun `the first run uses the defaults and the fields repeat only on Try again`() {
        val model = viewModel(signed(chat, listOf(Action.ShowNotification("Nuovo", "Messaggio"))))
        val first = model.state.value
        assertEquals(false, first.loading)
        assertEquals(listOf(DryRunField.SENDER, DryRunField.TEXT), first.fields.map { it.field })
        assertEquals("Marta", first.fields.first().value)
        assertEquals("Il trigger corrisponde", first.steps.first().title)
        assertEquals(DryRunTone.OK, first.steps.last().tone)

        model.onFieldChange(DryRunField.SENDER, "Luca")
        assertEquals("Luca", model.state.value.fields.first().value)
        assertEquals("Il trigger corrisponde", model.state.value.steps.first().title)

        model.onRun()
        assertEquals("Il trigger non corrisponde", model.state.value.steps.single().title)
    }

    @Test
    fun `a picked AI answer runs again and takes the other branch`() {
        val ai = Action.InvokeLlm(
            "è urgente?", emptyList(), emptyList(), replyTargetSender = false,
            deliver = GenerativeDeliverMode.CAPTURE_ONLY, captureAs = "urgent", output = OutputSpec(OutputKind.BOOLEAN),
        )
        val branch = Action.If(
            Condition.VarCompare("urgent", CmpOp.EQ, expected = "true"),
            then = listOf(Action.Vibrate(100)),
            orElse = listOf(Action.ShowNotification("No", "niente")),
        )
        val model = viewModel(signed(Trigger.Manual, listOf(ai, branch)))
        val aiStep = model.state.value.steps.first { it.tone == DryRunTone.AI }
        assertEquals("true", aiStep.choice?.selected)

        model.onAnswer(aiStep.choice!!.key, "false")
        val after = model.state.value
        assertEquals("false", after.steps.first { it.tone == DryRunTone.AI }.choice?.selected)
        assertTrue(after.steps.any { it.detail?.contains("Prende «Altrimenti».") == true })
    }

    @Test
    fun `a rule that is gone says so`() {
        val model = viewModel(null)
        assertTrue(model.state.value.missing)
        assertTrue(model.state.value.steps.isEmpty())
    }

    @Test
    fun `the view model holds nothing that could act`() {
        val sinks = listOf(
            AutomationStore::class.java, ActionExecutor::class.java, ResolvedActionExecutor::class.java,
            AuditSink::class.java, ExecutionJournal::class.java, SpanStore::class.java, StoredValueStore::class.java,
            UserModeStore::class.java, SecretVault::class.java, Brain::class.java,
        )
        val held = DryRunViewModel::class.java.declaredFields.map { it.type } +
            DryRunRuleReader::class.java.declaredFields.map { it.type }
        assertEquals(emptyList(), held.filter { type -> sinks.any { it.isAssignableFrom(type) } })
    }

    private companion object {
        const val CONV = "wa:marta"
    }
}
