package dev.argus.automation.notification

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.notification.ParsedNotification
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTriggerDemandTest {
    @Test
    fun `without notification rules no package is of interest`() = runTest {
        val demand = demandFor(listOf(rule("time", Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"))))

        assertFalse(demand.mayConcern(MAPS))
        assertFalse(demand.mayConcern(WHATSAPP))
    }

    @Test
    fun `a rule on one package makes only that package interesting`() = runTest {
        val demand = demandFor(listOf(rule("wa", Trigger.Notification(pkg = WHATSAPP))))

        assertTrue(demand.mayConcern(WHATSAPP))
        assertFalse(demand.mayConcern(MAPS))
    }

    @Test
    fun `a trigger that does not filter by package makes every package interesting`() = runTest {
        val demand = demandFor(
            listOf(
                rule("wa", Trigger.Notification(pkg = WHATSAPP)),
                rule("any", Trigger.Notification(pkg = "")),
            ),
        )

        assertTrue(demand.mayConcern(MAPS))
        assertTrue(demand.mayConcern(WHATSAPP))
    }

    @Test
    fun `only armed and enabled rules count`() = runTest {
        val demand = demandFor(
            listOf(
                rule("disabled", Trigger.Notification(pkg = MAPS), enabled = false),
                rule("review", Trigger.Notification(pkg = MAPS), status = AutomationStatus.NEEDS_REVIEW),
                rule("pending", Trigger.Notification(pkg = MAPS), status = AutomationStatus.PENDING_APPROVAL),
                rule("off", Trigger.Notification(pkg = MAPS), status = AutomationStatus.DISABLED),
                rule("wa", Trigger.Notification(pkg = WHATSAPP)),
            ),
        )

        assertFalse(demand.mayConcern(MAPS))
        assertTrue(demand.mayConcern(WHATSAPP))
    }

    @Test
    fun `the demand follows the rules as they are armed and removed`() = runTest {
        val rules = MutableStateFlow(emptyList<Automation>())
        val demand = NotificationTriggerDemand(rules, backgroundScope)
        runCurrent()
        assertFalse(demand.mayConcern(WHATSAPP))

        rules.value = listOf(rule("wa", Trigger.Notification(pkg = WHATSAPP)))
        runCurrent()
        assertTrue(demand.mayConcern(WHATSAPP))

        rules.value = emptyList()
        runCurrent()
        assertFalse(demand.mayConcern(WHATSAPP))
    }

    @Test
    fun `before the first emission and after a failure every package stays interesting`() = runTest {
        val silent = NotificationTriggerDemand(MutableStateFlow(emptyList()), backgroundScope)
        // Il collector non e' ancora partito: nessuna notifica deve andare persa all'avvio.
        assertTrue(silent.mayConcern(MAPS))

        val failing = NotificationTriggerDemand(
            flow<List<Automation>> { error("db") },
            backgroundScope,
        )
        runCurrent()
        assertTrue(failing.mayConcern(MAPS))
    }

    @Test
    fun `a navigator update with no notification rule starts no work at all`() = runTest {
        val demand = demandFor(emptyList())
        val work = RecordedWork()

        work.gate(demand).onParsed(parsed(MAPS))

        assertEquals(0, work.started.size)
    }

    @Test
    fun `with a rule on whatsapp only whatsapp notifications start work`() = runTest {
        val demand = demandFor(listOf(rule("wa", Trigger.Notification(pkg = WHATSAPP))))
        val work = RecordedWork()
        val gate = work.gate(demand)

        gate.onParsed(parsed(MAPS))
        assertEquals(0, work.started.size)

        gate.onParsed(parsed(WHATSAPP))
        assertEquals(1, work.started.size)
        work.runStarted()
        assertEquals(listOf(WHATSAPP), work.dispatched)
        assertEquals(emptyList(), work.observedOnly)
    }

    @Test
    fun `a rule without package filter makes every notification start work`() = runTest {
        val demand = demandFor(listOf(rule("any", Trigger.Notification(pkg = ""))))
        val work = RecordedWork()
        val gate = work.gate(demand)

        gate.onParsed(parsed(MAPS))
        gate.onParsed(parsed(WHATSAPP))

        assertEquals(2, work.started.size)
    }

    @Test
    fun `a whatsapp conversation is still remembered for the contact picker without any rule`() = runTest {
        val demand = demandFor(emptyList())
        val work = RecordedWork()
        val gate = work.gate(demand)

        gate.onParsed(parsed(WHATSAPP, observed = true))
        gate.onParsed(parsed(MAPS, observed = true))

        // Una sola lavorazione, e solo di salvataggio: le regole non vengono nemmeno controllate.
        assertEquals(1, work.started.size)
        work.runStarted()
        assertEquals(emptyList(), work.dispatched)
        assertEquals(listOf(WHATSAPP), work.observedOnly)
    }

    @Test
    fun `an event that is not a notification is never dropped`() = runTest {
        val demand = demandFor(emptyList())
        val work = RecordedWork()
        val odd = ParsedNotification(
            envelope = TriggerEnvelope(
                TriggerEventId("odd"),
                TriggerEvent.ImmediateFired(AutomationId("x"), dev.argus.engine.model.ApprovalFingerprint("0".repeat(64))),
            ),
            observedConversation = null,
            identitySource = null,
        )

        work.gate(demand).onParsed(odd)

        assertEquals(1, work.started.size)
    }

    private fun TestScope.demandFor(rules: List<Automation>): NotificationTriggerDemand {
        val demand = NotificationTriggerDemand(MutableStateFlow(rules), backgroundScope)
        runCurrent()
        return demand
    }

    private fun rule(
        id: String,
        trigger: Trigger,
        status: AutomationStatus = AutomationStatus.ARMED,
        enabled: Boolean = true,
    ) = Automation(
        id = AutomationId(id),
        name = id,
        createdBy = CreatedBy.USER,
        status = status,
        trigger = trigger,
        actions = listOf(Action.SetWifi(false)),
        enabled = enabled,
    )

    private fun parsed(packageName: String, observed: Boolean = false) = ParsedNotification(
        envelope = TriggerEnvelope(
            TriggerEventId("notification:$packageName"),
            TriggerEvent.NotificationPosted(pkg = packageName, text = "x"),
        ),
        observedConversation = if (observed) {
            ObservedConversation(
                id = "conversation-1",
                packageName = packageName,
                displayName = "Contatto di prova",
                isGroup = false,
                lastSeenAtMillis = 1L,
            )
        } else {
            null
        },
        identitySource = null,
    )

    private class RecordedWork {
        val started = mutableListOf<suspend () -> Unit>()
        val dispatched = mutableListOf<String>()
        val observedOnly = mutableListOf<String>()

        fun gate(demand: NotificationTriggerDemand) = NotificationWorkGate(
            demand = demand,
            startWork = { started += it },
            persistAndDispatch = { dispatched += packageOf(it) },
            persistObserved = { observedOnly += packageOf(it) },
        )

        suspend fun runStarted() {
            started.toList().forEach { it() }
        }

        private fun packageOf(parsed: ParsedNotification): String =
            (parsed.envelope.event as TriggerEvent.NotificationPosted).pkg
    }

    private companion object {
        const val MAPS = "com.example.maps"
        const val WHATSAPP = "com.whatsapp"
    }
}
