package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationSchema
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ExitSpec
import dev.argus.engine.model.SpanSlot
import dev.argus.engine.model.SpanValue
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VolumeStream
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.8 regole-intervallo: l'inizio registra i valori che sta per cambiare, la fine li rimette al contrario e poi
 * esegue le azioni di fine. Deduplica e pausa sono per fase; un intervallo sopravvive al processo e non resta mai
 * applicato a una versione approvata diversa.
 */
class SpanEngineTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC)
    private val carOn = Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED, "Auto")
    private val carOff = Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED, "Auto")
    private val carOnEvent = TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.CONNECTED, "Auto")
    private val carOffEvent = TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.DISCONNECTED, "Auto")

    private fun rule(
        actions: List<Action> = listOf(Action.SetVolume(VolumeStream.MEDIA, 100), Action.SetDnd(DndMode.PRIORITY)),
        exit: ExitSpec? = ExitSpec(carOff),
        trigger: Trigger = carOn,
        cooldownMs: Long = 0,
        confirm: ConfirmSpec? = null,
        vars: List<VarBinding> = emptyList(),
        name: String = "Auto",
    ): Automation {
        val draft = AutomationDraft(name, trigger, actions, vars = vars, cooldownMs = cooldownMs, confirm = confirm, exit = exit)
        val unsigned = Automation(
            AutomationId("span"), name, CreatedBy.USER, AutomationStatus.ARMED, trigger, actions,
            vars = vars,
            cooldownMs = cooldownMs,
            confirm = confirm,
            exit = exit,
            schemaVersion = AutomationSchema.versionFor(draft),
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    /** Il telefono finto: i valori cambiano quando l'esecutore applica le azioni, come quello vero. */
    private class Phone(initial: Map<SpanSlot, SpanValue?>) : SpanDevice {
        val values = initial.toMutableMap()
        val reads = mutableListOf<SpanSlot>()
        var cleared = 0

        override suspend fun read(slot: SpanSlot): SpanValue? {
            reads += slot
            return values[slot]
        }

        override suspend fun clearUserMode(context: FireContext): ActionResult {
            cleared++
            values[SpanSlot.UserMode] = SpanValue.Mode(null)
            return ActionResult.Success
        }
    }

    private class PhoneExecutor(private val phone: Phone, private val failing: Set<String> = emptySet()) : ActionExecutor {
        val executed = mutableListOf<Action>()

        override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
            executed += action
            if (action::class.simpleName in failing) return ActionResult.Failure("forced")
            when (action) {
                is Action.SetVolume -> phone.values[SpanSlot.Volume(action.stream)] = SpanValue.Percent(action.level)
                is Action.SetDnd -> phone.values[SpanSlot.Dnd] = SpanValue.Dnd(action.mode)
                is Action.SetWifi -> phone.values[SpanSlot.Wifi] = SpanValue.Flag(action.on)
                is Action.SetUserMode -> phone.values[SpanSlot.UserMode] = SpanValue.Mode(action.mode)
                else -> Unit
            }
            return ActionResult.Success
        }
    }

    private inner class Rig(
        rules: List<Automation>,
        val phone: Phone = Phone(
            mapOf(
                SpanSlot.Volume(VolumeStream.MEDIA) to SpanValue.Percent(30),
                SpanSlot.Dnd to SpanValue.Dnd(DndMode.OFF),
                SpanSlot.Wifi to SpanValue.Flag(false),
                SpanSlot.UserMode to SpanValue.Mode(null),
            ),
        ),
        val spans: SpanStore = MemorySpanStore(),
        policy: FirePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
        failing: Set<String> = emptySet(),
        val store: FakeAutomationStore = FakeAutomationStore(rules),
        var clockMillis: Long = 1_000,
        confirmations: ConfirmationGate = ConfirmationGate.UNAVAILABLE,
    ) {
        val executor = PhoneExecutor(phone, failing)
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val engine = Engine(
            store = store,
            executor = executor,
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = policy,
            audit = audit,
            journal = journal,
            confirmations = confirmations,
            spans = spans,
            spanDevice = phone,
            now = { clockMillis },
        )

        suspend fun fire(id: String, event: TriggerEvent) = engine.onTrigger(TriggerEnvelope(TriggerEventId(id), event)) { DeviceState() }
    }

    @Test
    fun `the end puts the recorded values back in reverse order, then runs the end actions`() = runTest {
        val rule = rule(exit = ExitSpec(carOff, actions = listOf(Action.Vibrate(50))))
        val rig = Rig(listOf(rule))

        rig.fire("on:1", carOnEvent)
        val span = assertNotNull(rig.spans.get(rule.id))
        assertEquals(listOf(SpanSlot.Volume(VolumeStream.MEDIA), SpanSlot.Dnd), span.records.map { it.slot })

        rig.executor.executed.clear()
        val outcome = rig.fire("off:1", carOffEvent).single()

        assertEquals(
            listOf(Action.SetDnd(DndMode.OFF), Action.SetVolume(VolumeStream.MEDIA, 30), Action.Vibrate(50)),
            rig.executor.executed,
        )
        assertEquals(3, outcome.results.size)
        assertNull(rig.spans.get(rule.id), "la fine chiude l'intervallo")
        assertEquals(SpanValue.Percent(30), rig.phone.values[SpanSlot.Volume(VolumeStream.MEDIA)])
        val fired = rig.audit.events.last()
        assertEquals(AuditKind.FIRED, fired.kind)
        assertEquals(SpanCodes.EXIT, fired.detail)
        // I passi di ripristino vanno nel journal dopo i numeri delle azioni di fine: nessun percorso doppio.
        val exitEntries = rig.journal.actions.filter { it.executionId == outcome.executionId }
        assertEquals(exitEntries.size, exitEntries.map { it.actionPath }.toSet().size)
        assertEquals(listOf("2", "3", "1"), exitEntries.map { it.actionPath })
    }

    @Test
    fun `a second start while in span records nothing again, and a value changed by hand is still put back`() = runTest {
        val rule = rule(actions = listOf(Action.SetVolume(VolumeStream.MEDIA, 100)))
        val rig = Rig(listOf(rule))

        rig.fire("on:1", carOnEvent)
        // L'utente abbassa a mano, poi la regola riparte (di nuovo connesso): il valore di prima resta 30.
        rig.phone.values[SpanSlot.Volume(VolumeStream.MEDIA)] = SpanValue.Percent(60)
        rig.fire("on:2", carOnEvent)
        assertEquals(SpanValue.Percent(30), rig.spans.get(rule.id)?.records?.single()?.value)
        assertEquals(1, rig.phone.reads.size, "il valore si legge una volta per intervallo")

        rig.phone.values[SpanSlot.Volume(VolumeStream.MEDIA)] = SpanValue.Percent(70)
        rig.fire("off:1", carOffEvent)
        assertEquals(SpanValue.Percent(30), rig.phone.values[SpanSlot.Volume(VolumeStream.MEDIA)])
    }

    @Test
    fun `an end while not in span does nothing`() = runTest {
        val rule = rule(exit = ExitSpec(carOff, actions = listOf(Action.Vibrate(50))))
        val rig = Rig(listOf(rule))

        assertTrue(rig.fire("off:1", carOffEvent).isEmpty())
        assertTrue(rig.executor.executed.isEmpty())
        assertTrue(rig.audit.events.isEmpty())
        assertTrue(rig.journal.completions.isEmpty())
    }

    @Test
    fun `the end is never held by the start cooldown, and the start never by the end`() = runTest {
        val rule = rule(actions = listOf(Action.SetVolume(VolumeStream.MEDIA, 100)), cooldownMs = 600_000)
        val rig = Rig(listOf(rule))

        rig.clockMillis = 1_000
        rig.fire("on:1", carOnEvent)
        rig.clockMillis = 2_000
        assertEquals(1, rig.fire("off:1", carOffEvent).size, "la fine passa subito dopo l'inizio")
        // Un nuovo inizio dentro la pausa dell'inizio: soppresso dalla SUA pausa.
        rig.clockMillis = 3_000
        assertTrue(rig.fire("on:2", carOnEvent).isEmpty())
        assertEquals(AuditKind.SUPPRESSED_COOLDOWN, rig.audit.events.last().kind)
        // Passata la pausa dell'inizio (contata dal primo inizio, non dalla fine) riparte.
        rig.clockMillis = 1_000 + 600_000
        assertEquals(1, rig.fire("on:3", carOnEvent).size)
    }

    @Test
    fun `the end keeps its own deduplication, even with the id of the start`() = runTest {
        val rule = rule(actions = listOf(Action.SetVolume(VolumeStream.MEDIA, 100)))
        val rig = Rig(listOf(rule))

        rig.fire("same-id", carOnEvent)
        // Stesso id dell'evento d'inizio (una sorgente che riusa gli id): la fine non è un suo doppione.
        assertEquals(1, rig.fire("same-id", carOffEvent).size)
        assertNull(rig.spans.get(rule.id))
        // Un doppione della fine sullo stesso intervallo, riaperto: è un duplicato della fine e basta.
        rig.fire("on:2", carOnEvent)
        assertTrue(rig.fire("same-id", carOffEvent).isEmpty())
        assertEquals(AuditKind.SUPPRESSED_DUPLICATE, rig.audit.events.last().kind)
        assertNotNull(rig.spans.get(rule.id), "il duplicato non chiude l'intervallo")
    }

    @Test
    fun `a span survives the death of the process`() = runTest {
        val rule = rule()
        val spans = MemorySpanStore()
        val phone = Phone(
            mapOf(
                SpanSlot.Volume(VolumeStream.MEDIA) to SpanValue.Percent(30),
                SpanSlot.Dnd to SpanValue.Dnd(DndMode.OFF),
            ),
        )
        val first = Rig(listOf(rule), phone = phone, spans = spans)
        first.fire("on:1", carOnEvent)

        // Un motore nuovo (processo nuovo) con lo stesso store persistito.
        val second = Rig(listOf(rule), phone = phone, spans = spans)
        second.fire("off:1", carOffEvent)
        assertEquals(
            listOf(Action.SetDnd(DndMode.OFF), Action.SetVolume(VolumeStream.MEDIA, 30)),
            second.executor.executed,
        )
        assertNull(spans.get(rule.id))
    }

    @Test
    fun `disabling or deleting a rule in span puts the values back first`() = runTest {
        listOf<suspend (FakeAutomationStore, Automation) -> Unit>(
            { store, rule -> store.disable(rule.id) },
            { store, rule -> store.delete(rule.id) },
            { store, rule -> store.markNeedsReview(rule.id) },
        ).forEach { change ->
            val rule = rule()
            val rig = Rig(listOf(rule))
            rig.fire("on:1", carOnEvent)
            rig.executor.executed.clear()

            change(rig.store, rule)
            assertEquals(listOf(rule.id), rig.engine.reconcileSpans())

            assertEquals(
                listOf(Action.SetDnd(DndMode.OFF), Action.SetVolume(VolumeStream.MEDIA, 30)),
                rig.executor.executed,
            )
            assertNull(rig.spans.get(rule.id))
            assertEquals(SpanCodes.RESTORED_RULE_CHANGED, rig.audit.events.last().detail)
            // Una seconda riconciliazione non ha più nulla da rimettere.
            assertTrue(rig.engine.reconcileSpans().isEmpty())
        }
    }

    @Test
    fun `a rule still armed with the same approval keeps its span`() = runTest {
        val rule = rule()
        val rig = Rig(listOf(rule))
        rig.fire("on:1", carOnEvent)
        assertTrue(rig.engine.reconcileSpans().isEmpty())
        assertNotNull(rig.spans.get(rule.id))
    }

    @Test
    fun `a re-approved rule never applies the old snapshot to the new version`() = runTest {
        val old = rule()
        val rig = Rig(listOf(old))
        rig.fire("on:1", carOnEvent)
        rig.executor.executed.clear()

        // Nuova versione approvata (volume diverso): impronta nuova.
        val revised = rule(actions = listOf(Action.SetVolume(VolumeStream.MEDIA, 80)))
        rig.store.upsert(revised)
        // La fine della nuova versione arriva prima di qualunque riconciliazione: rimette i valori della vecchia
        // apertura, poi non c'è un intervallo della nuova versione da chiudere.
        assertTrue(rig.fire("off:1", carOffEvent).isEmpty())
        assertEquals(
            listOf(Action.SetDnd(DndMode.OFF), Action.SetVolume(VolumeStream.MEDIA, 30)),
            rig.executor.executed,
        )
        assertNull(rig.spans.get(old.id))
        assertTrue(rig.audit.events.any { it.detail == SpanCodes.RESTORED_RULE_CHANGED })
    }

    @Test
    fun `a value that cannot be read is not guessed and not put back, the others are`() = runTest {
        val rule = rule()
        val phone = Phone(mapOf(SpanSlot.Volume(VolumeStream.MEDIA) to SpanValue.Percent(30), SpanSlot.Dnd to null))
        val rig = Rig(listOf(rule), phone = phone)

        rig.fire("on:1", carOnEvent)
        val span = assertNotNull(rig.spans.get(rule.id))
        assertEquals(listOf(SpanSlot.Volume(VolumeStream.MEDIA)), span.records.map { it.slot })
        assertTrue(SpanSlot.Dnd.key in span.handled)
        assertTrue(rig.audit.events.any { it.kind == AuditKind.SUPPRESSED_NOT_ELIGIBLE && it.detail == SpanCodes.VALUE_UNREADABLE })

        rig.executor.executed.clear()
        rig.fire("off:1", carOffEvent)
        assertEquals(listOf<Action>(Action.SetVolume(VolumeStream.MEDIA, 30)), rig.executor.executed)
    }

    @Test
    fun `a capability missing at the end fails that value closed and the others continue`() = runTest {
        val rule = rule()
        val policy = object : FirePolicy {
            override suspend fun evaluate(automation: Automation, event: TriggerEvent) = FirePolicyDecision.Allow
            override suspend fun evaluateRestore(action: Action): FirePolicyDecision =
                if (action is Action.SetDnd) {
                    FirePolicyDecision.Block(SpanCodes.RESTORE_CAPABILITY_UNAVAILABLE, needsReview = false)
                } else {
                    FirePolicyDecision.Allow
                }
        }
        val rig = Rig(listOf(rule), policy = policy)
        rig.fire("on:1", carOnEvent)
        rig.executor.executed.clear()

        val outcome = rig.fire("off:1", carOffEvent).single()
        assertEquals(listOf<Action>(Action.SetVolume(VolumeStream.MEDIA, 30)), rig.executor.executed)
        assertEquals(ActionResult.Failure(SpanCodes.RESTORE_CAPABILITY_UNAVAILABLE), outcome.results.first())
        assertNull(rig.spans.get(rule.id))
    }

    @Test
    fun `no mode before the start goes back to no mode at the end`() = runTest {
        val rule = rule(actions = listOf(Action.SetUserMode("Auto")))
        val rig = Rig(listOf(rule))
        rig.fire("on:1", carOnEvent)
        assertEquals(SpanValue.Mode("Auto"), rig.phone.values[SpanSlot.UserMode])
        rig.fire("off:1", carOffEvent)
        assertEquals(1, rig.phone.cleared)
        assertEquals(SpanValue.Mode(null), rig.phone.values[SpanSlot.UserMode])
    }

    @Test
    fun `without restore the span still ends only after a start, and nothing is recorded`() = runTest {
        val rule = rule(exit = ExitSpec(carOff, restore = false, actions = listOf(Action.SetWifi(true))))
        val rig = Rig(listOf(rule))
        assertTrue(rig.fire("off:0", carOffEvent).isEmpty())
        rig.fire("on:1", carOnEvent)
        assertTrue(rig.phone.reads.isEmpty())
        rig.executor.executed.clear()
        rig.fire("off:1", carOffEvent)
        assertEquals(listOf<Action>(Action.SetWifi(true)), rig.executor.executed)
    }

    @Test
    fun `confirm asks for the start only, the end never asks`() = runTest {
        val gate = object : ConfirmationGate {
            var asked = 0
            override suspend fun request(request: ConfirmationRequest): ConfirmationRequestResult {
                asked++
                return ConfirmationRequestResult.REQUESTED
            }
        }
        val rule = rule(confirm = ConfirmSpec(60_000))
        val rig = Rig(listOf(rule), confirmations = gate)

        rig.fire("on:1", carOnEvent)
        assertEquals(1, gate.asked)
        assertNull(rig.spans.get(rule.id), "finché non conferma, la regola non è in corso")
        val asked = ConfirmedFire("a".repeat(32), rule.id, requireNotNull(rule.approvalFingerprint), TriggerEnvelope(TriggerEventId("on:1"), carOnEvent))
        rig.engine.onConfirmed(asked) { DeviceState() }
        assertNotNull(rig.spans.get(rule.id))

        rig.executor.executed.clear()
        assertEquals(1, rig.fire("off:1", carOffEvent).size)
        assertEquals(1, gate.asked, "la fine non chiede")
        assertEquals(2, rig.executor.executed.size)
    }

    @Test
    fun `a time span tells start and end apart by the phase of the alarm`() = runTest {
        val start = Trigger.Time(cron = "0 22 * * *", tz = "Europe/Rome")
        val end = Trigger.Time(cron = "0 7 * * *", tz = "Europe/Rome")
        val rule = rule(trigger = start, actions = listOf(Action.SetDnd(DndMode.TOTAL)), exit = ExitSpec(end))
        val rig = Rig(listOf(rule))
        val fp = requireNotNull(rule.approvalFingerprint)

        // Una sveglia di fine senza intervallo in corso: niente.
        assertTrue(rig.fire("t:end:0", TriggerEvent.TimeFired(rule.id, fp, SpanPhase.EXIT)).isEmpty())
        rig.fire("t:start:1", TriggerEvent.TimeFired(rule.id, fp, SpanPhase.START))
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.TOTAL)), rig.executor.executed)
        rig.executor.executed.clear()
        rig.fire("t:end:1", TriggerEvent.TimeFired(rule.id, fp, SpanPhase.EXIT))
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.OFF)), rig.executor.executed)
    }

    @Test
    fun `an end program binds its variables to the end event, external text included`() = runTest {
        val whatsapp = "com.whatsapp"
        // Le variabili si legano sia all'inizio sia alla fine: entrambi i trigger hanno il testo del messaggio.
        val start = Trigger.Notification(pkg = whatsapp, conversationId = "c1", isGroup = false, textMatch = "parto")
        val end = Trigger.Notification(pkg = whatsapp, conversationId = "c1", isGroup = false, textMatch = "arrivato")
        val text = VarBinding.TriggerPayload("msg", TriggerField.TEXT, confidentiality = ConfidentialityLabel.PRIVATE)
        val rule = rule(
            trigger = start,
            actions = listOf(Action.SetVolume(VolumeStream.MEDIA, 100)),
            exit = ExitSpec(end, actions = listOf(Action.ShowNotification("Fine", "Ricevuto: \${msg}"))),
            vars = listOf(text),
        )
        val rig = Rig(listOf(rule))
        rig.fire(
            "n:0",
            TriggerEvent.NotificationPosted(whatsapp, conversationId = "c1", sender = "A", text = "parto ora", isGroup = false),
        )
        assertEquals(listOf<Action>(Action.SetVolume(VolumeStream.MEDIA, 100)), rig.executor.executed)
        rig.executor.executed.clear()

        rig.fire(
            "n:1",
            TriggerEvent.NotificationPosted(whatsapp, conversationId = "c1", sender = "A", text = "arrivato", isGroup = false),
        )
        // Il testo esterno (TAINTED) della FINE entra solo nel sink locale approvato.
        assertEquals(
            listOf(Action.SetVolume(VolumeStream.MEDIA, 30), Action.ShowNotification("Fine", "Ricevuto: arrivato")),
            rig.executor.executed,
        )
    }

    @Test
    fun `an exit event id stays within the event id limit`() {
        val long = TriggerEventId("x".repeat(512))
        val derived = Engine.exitEventId(long)
        assertTrue(derived.value.length <= 512)
        assertEquals(derived, Engine.exitEventId(long))
        assertEquals("abc#span_exit", Engine.exitEventId(TriggerEventId("abc")).value)
    }
}
