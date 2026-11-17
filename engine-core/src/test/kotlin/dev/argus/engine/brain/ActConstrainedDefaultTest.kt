package dev.argus.engine.brain

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.OutputKind
import dev.argus.engine.model.OutputSpec
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 0.8: senza output strutturato, ogni brain riceve il goal approvato più l'istruzione fissa della forma. */
class ActConstrainedDefaultTest {
    private val context = FireContext(
        event = TriggerEvent.TimeFired(AutomationId("a"), ApprovalFingerprint("f".repeat(64))),
        state = DeviceState(),
        automationId = AutomationId("a"),
        approvalFingerprint = ApprovalFingerprint("f".repeat(64)),
        eventId = TriggerEventId("e"),
        executionId = ExecutionId("x"),
        actionIndex = 0,
    )

    private class Recording : Brain {
        val goals = mutableListOf<String>()
        var resolved = 0

        override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult =
            error("not used")

        override suspend fun act(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
        ): ActResult {
            goals += goal
            return ActResult(text = "yes", metaError = null)
        }

        override suspend fun actResolved(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
            runtimeData: List<RuntimeDataBinding>,
        ): ActResult {
            resolved += 1
            goals += goal
            return ActResult(text = "no", metaError = null)
        }
    }

    @Test
    fun `the default appends the shape instruction and keeps the plain channel`() = runTest {
        val brain = Recording()
        val result = brain.actConstrained(context, "È urgente?", emptyList(), emptyList(), emptyList(), OutputSpec(OutputKind.BOOLEAN))

        assertEquals("yes", result.text)
        assertEquals(listOf("È urgente?\n\nAnswer with exactly yes or no and nothing else."), brain.goals)
        assertEquals(0, brain.resolved)
    }
}
