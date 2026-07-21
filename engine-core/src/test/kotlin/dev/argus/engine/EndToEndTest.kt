package dev.argus.engine
import dev.argus.engine.brain.CliBridgeParser
import dev.argus.engine.model.*
import dev.argus.engine.runtime.*
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
class EndToEndTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)
    private val allowAll = FirePolicy { _, _ -> FirePolicyDecision.Allow }
    private fun engine(store: AutomationStore, ex: ActionExecutor, clockIso: String) =
        Engine(store, ex, ConditionEvaluator(clock(clockIso)), TriggerMatcher(), allowAll, NoopAuditSink) { 1000 }
    private val validator = DraftValidator(knownTools = setOf("whatsapp_reply", "notify.show"))
    private fun approved(value: Automation) =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    @Test fun `example2 - compile, validate, arm, fire DND after 23`() = runTest {
        // 1) compile: output Hermes -> draft
        val raw = "Fatto.\n@@META@@ {\"draft\":{\"name\":\"dnd\",\"trigger\":{\"type\":\"time\",\"cron\":\"0 23 * * *\",\"tz\":\"Europe/Rome\"}," +
            "\"conditions\":{\"type\":\"state_equals\",\"key\":\"ringer\",\"op\":\"NEQ\",\"value\":\"silent\"}," +
            "\"actions\":[{\"type\":\"set_dnd\",\"mode\":\"PRIORITY\"}]}}"
        val draft = assertNotNull(CliBridgeParser().parseCompile(raw).draft)
        // 2) validator verde
        assertEquals(emptyList(), validator.validate(draft, emptySet()).filter { it.severity == Severity.ERROR })
        // 3) approva -> Automation ARMED
        val auto = approved(
            Automation(AutomationId("a1"), draft.name, CreatedBy.LLM, AutomationStatus.ARMED,
                draft.trigger, draft.actions, conditions = draft.conditions),
        )
        // 4) next-fire calcolabile (P0-B lo passa ad AlarmManager)
        assertNotNull(TimeSpecs.nextFire(draft.trigger as Trigger.Time, Instant.parse("2026-07-12T10:00:00Z")))
        // 5) fire alle 23:30 con suoneria "normal"
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        engine(store, ex, "2026-07-12T21:30:00Z")
            .onTrigger(
                TriggerEnvelope(
                    TriggerEventId("alarm:a1:1"),
                    TriggerEvent.TimeFired(auto.id, requireNotNull(auto.approvalFingerprint)),
                ),
            ) {
                DeviceState(values = mapOf("ringer" to "normal"))
            }
        assertEquals(listOf<Action>(Action.SetDnd(DndMode.PRIORITY)), ex.executed)
    }

    @Test fun `example1 - placeholder resolved at arm, geofence exit toggles wifi off bt on`() = runTest {
        // draft con resolveCurrentLocation (il compile non conosce il GPS, spec §7): warning raggio, nessun ERROR
        val draft = AutomationDraft("geo casa",
            Trigger.Geofence(radiusM = 50.0, transition = Transition.EXIT, resolveCurrentLocation = true),
            listOf(Action.SetWifi(false), Action.SetBluetooth(true)))
        val issues = validator.validate(draft, emptySet())
        assertEquals(emptyList(), issues.filter { it.severity == Severity.ERROR })
        assertTrue(issues.any { it.code == "radius_small" })
        // all'ARM l'app risolve le coordinate correnti
        val resolved = (draft.trigger as Trigger.Geofence).copy(lat = 45.4, lng = 11.0, resolveCurrentLocation = false)
        val auto = approved(
            Automation(AutomationId("g1"), draft.name, CreatedBy.LLM, AutomationStatus.ARMED, resolved, draft.actions),
        )
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        engine(store, ex, "2026-07-12T10:00:00Z")
            .onTrigger(TriggerEnvelope(
                TriggerEventId("geofence:g1:1"),
                TriggerEvent.GeofenceTransitioned(
                    auto.id,
                    Transition.EXIT,
                    requireNotNull(auto.approvalFingerprint),
                ),
            )) { DeviceState() }
        assertEquals(listOf<Action>(Action.SetWifi(false), Action.SetBluetooth(true)), ex.executed)
    }

    @Test fun `example3 - whatsapp 1-1 from whitelisted conversation triggers generative reply as Submitted`() = runTest {
        val auto = approved(
            Automation(AutomationId("w1"), "reply", CreatedBy.LLM, AutomationStatus.ARMED,
                Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
                listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), replyTargetSender = true)),
                conditions = Condition.TimeWindow("18:00", "22:00", "Europe/Rome")),
        )
        val ex = FakeActionExecutor(); val store = FakeAutomationStore(listOf(auto))
        val outcomes = engine(store, ex, "2026-07-12T16:30:00Z")   // 18:30 CEST, dentro la finestra
            .onTrigger(TriggerEnvelope(
                TriggerEventId("sbn:wa:1"),
                TriggerEvent.NotificationPosted("com.whatsapp", conversationId = "jid:42",
                    sender = "Moglie", text = "ciao", isGroup = false, notificationKey = "sbn:1"),
            )) { DeviceState() }
        assertEquals(1, ex.executed.size)
        assertEquals(ActionTier.GENERATIVE, ex.executed.first().tier)
        assertEquals(listOf<ActionResult>(ActionResult.Submitted), outcomes.single().results)  // lane async, engine non blocca
    }

    @Test fun `security gate - draft asking for shell at generative fire-time never reaches the engine`() = runTest {
        val malicious = AutomationDraft("innocua",
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.InvokeLlm("aiuta", listOf("notification"), listOf("whatsapp_reply", "shell.run"), true)))
        val errors = validator.validate(malicious, whitelistedIds = setOf("jid:42"))
            .filter { it.severity == Severity.ERROR }
        assertTrue(errors.any { it.code == "tool_forbidden" })   // ERROR => canArm=false, mai ARMED
    }
}
