package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.device.DeviceController
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.StoredOp
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredValueOps
import dev.argus.engine.model.StoredWrite
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.StoredModeActions
import dev.argus.engine.runtime.StoredValueStore
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.UserModeStore
import dev.argus.engine.runtime.UserModeSwitcher
import dev.argus.engine.runtime.VarScope
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `set_stored` e `set_user_mode` nell'esecutore di produzione: nessun privilegio, nessun device toccato. */
class ShizukuActionExecutorStoredModes08Test {
    private class Memory : StoredValueStore, UserModeStore {
        val values = mutableMapOf<String, StoredValue>()
        var active: String? = null

        override suspend fun read(keys: Set<String>) = values.filterKeys(keys::contains)

        override suspend fun apply(
            action: Action.SetStored,
            integrity: IntegrityLabel,
            confidentiality: ConfidentialityLabel,
            author: ChangeAuthor,
            atMillis: Long,
        ): StoredWrite {
            val write = StoredValueOps.plan(action, values[action.key], values.size, integrity, confidentiality, author, atMillis)
            when (write) {
                is StoredWrite.Put -> values[write.value.key] = write.value
                is StoredWrite.Remove -> values.remove(write.key)
                else -> Unit
            }
            return write
        }

        override suspend fun snapshot() = UserModeSnapshot(listOf("Casa", "Lavoro"), active)

        override suspend fun activate(mode: String?, author: ChangeAuthor, atMillis: Long): ModeSwitch {
            val target = mode?.let { name -> listOf("Casa", "Lavoro").firstOrNull { it.equals(name, true) } ?: return ModeSwitch.UnknownMode }
            if (target == active) return ModeSwitch.Unchanged
            return ModeSwitch.Changed(active, target).also { active = target }
        }
    }

    private val noTools: DeviceController = Proxy.newProxyInstance(
        DeviceController::class.java.classLoader,
        arrayOf(DeviceController::class.java),
    ) { _, method, _ -> error("valori e modalità non toccano il device: ${method.name}") } as DeviceController

    private fun executor(storedModes: StoredModeActions?) = ShizukuActionExecutor(
        tools = noTools,
        notifier = AutomationNotifier { _, _, _ -> error("nessuna notifica") },
        generativeLane = GenerativeLane { _, _ -> error("nessuna AI") },
        replies = NotificationReplyGateway { NotificationReplyDelivery.Failed("reply_channel_unavailable") },
        clipboard = object : ClipboardCopier {
            override fun copy(event: TriggerEvent, extractionRegex: String?) = ActionResult.Success
            override fun copyLiteral(text: String) = ActionResult.Success
        },
        shizukuReady = { false },
        storedModes = storedModes,
    )

    private fun context(event: TriggerEvent = TriggerEvent.NotificationPosted("com.example")) = FireContext(
        event = event,
        state = DeviceState(),
        automationId = AutomationId("rule-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    @Test
    fun `set_stored keeps the labels of the filled-in template`() = runTest {
        val memory = Memory()
        val dispatched = mutableListOf<TriggerEnvelope>()
        val executor = executor(StoredModeActions(memory, UserModeSwitcher(memory, { dispatched += it }), now = { 3L }))
        val scope = VarScope(
            mapOf(
                "testo" to VarValue("ciao", VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.NOTIFICATION)),
            ),
        )
        val resolved = assertIs<ActionResolution.Resolved>(
            TaintAwareInterpolator().resolve(Action.SetStored("ultimo", StoredOp.SET, "\${testo}"), scope),
        ).value

        assertEquals(ActionResult.Success, executor.execute(resolved, context()).result)
        val saved = memory.values.getValue("ultimo")
        assertEquals("ciao", saved.text)
        assertEquals(IntegrityLabel.TAINTED, saved.integrity)
        assertEquals(ChangeAuthor.Rule(AutomationId("rule-1")), saved.writer)

        // Senza la foglia risolta non si scrive niente: le etichette non si possono inventare.
        assertEquals(ActionResult.Failure("stored_unresolved"), executor.execute(Action.SetStored("altro", StoredOp.CLEAR), context()))
    }

    @Test
    fun `set_user_mode switches the mode and a missing one fails closed`() = runTest {
        val memory = Memory()
        val dispatched = mutableListOf<TriggerEnvelope>()
        val executor = executor(StoredModeActions(memory, UserModeSwitcher(memory, { dispatched += it }), now = { 3L }))

        assertEquals(ActionResult.Success, executor.execute(Action.SetUserMode("lavoro"), context()))
        assertEquals("Lavoro", memory.active)
        assertEquals(1, dispatched.size)
        assertEquals(ActionResult.Failure(StoredModeActions.USER_MODE_UNKNOWN), executor.execute(Action.SetUserMode("Notte"), context()))

        // Il cambio fatto da una regola scattata da un cambio di modalità non accende altre regole.
        assertEquals(ActionResult.Success, executor.execute(Action.SetUserMode("Casa"), context(TriggerEvent.UserModeChanged("Lavoro", null))))
        assertEquals("Casa", memory.active)
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `without the store both actions fail closed`() = runTest {
        val executor = executor(null)
        assertEquals(ActionResult.Failure("base_executor_unavailable"), executor.execute(Action.SetUserMode("Casa"), context()))
        val resolved = assertIs<ActionResolution.Resolved>(
            TaintAwareInterpolator().resolve(Action.SetStored("k", StoredOp.TOGGLE), VarScope()),
        ).value
        assertTrue(executor.execute(resolved, context()).result is ActionResult.Failure)
    }
}
