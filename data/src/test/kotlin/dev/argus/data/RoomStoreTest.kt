package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomStoreTest {

    private lateinit var db: ArgusDatabase
    private lateinit var store: RoomAutomationStore
    private lateinit var sink: RoomAuditSink

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomAutomationStore(db.automationDao())
        sink = RoomAuditSink(db.auditDao())
    }

    @After
    fun tearDown() = db.close()

    // --- round-trip ----------------------------------------------------------

    @Test
    fun `round-trips a Time trigger with SetDnd action and TimeWindow condition`() = runTest {
        val a = signed(Automation(
            id = AutomationId("dnd-night"),
            name = "Dopo le 23 → DND",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.TOTAL)),
            conditions = Condition.TimeWindow("23:00", "07:00", "Europe/Rome"),
            enabled = true,
            priority = 5,
            cooldownMs = 60_000,
        ))
        persistForTest(a)
        assertEquals(a, store.get(a.id))
    }

    @Test
    fun `round-trips a Geofence trigger with generative action and nested conditions`() = runTest {
        val a = signed(Automation(
            id = AutomationId("home-arrive"),
            name = "A casa: wifi + riepilogo",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Geofence(lat = 45.4, lng = 11.9, radiusM = 120.0, transition = Transition.ENTER),
            actions = listOf(
                Action.SetWifi(on = true),
                Action.InvokeLlm(
                    goal = "riepiloga i messaggi non letti",
                    contextSources = listOf("chat"),
                    allowedTools = listOf("notify.show"),
                    replyTargetSender = false,
                ),
            ),
            conditions = Condition.And(
                listOf(
                    Condition.AppInForeground("com.whatsapp"),
                    Condition.Not(Condition.StateEquals("dnd", CmpOp.EQ, "TOTAL")),
                ),
            ),
            enabled = true,
            priority = 0,
        ))
        persistForTest(a)
        assertEquals(a, store.get(a.id))
    }

    @Test
    fun `round-trips a Connectivity trigger`() = runTest {
        val a = signed(baseArmed("power-plug").copy(
            trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
            actions = listOf(Action.SetBluetooth(on = false), Action.ShowNotification("t", "x")),
        ))
        persistForTest(a)
        assertEquals(a, store.get(a.id))
    }

    @Test
    fun `get returns null for unknown id`() = runTest {
        assertNull(store.get(AutomationId("missing")))
    }

    @Test
    fun `save is idempotent upsert and reflects edits`() = runTest {
        val a = baseArmed("edit-me")
        persistForTest(a)
        val edited = signed(a.copy(name = "nuovo nome", priority = 9))
        persistForTest(edited)
        assertEquals(edited, store.get(a.id))
    }

    // --- armed() filter ------------------------------------------------------

    @Test
    fun `armed returns only ARMED and enabled ordered by priority ascending`() = runTest {
        persistForTest(signed(baseArmed("p2").copy(priority = 2)))
        persistForTest(signed(baseArmed("p1").copy(priority = 1)))
        persistForTest(baseArmed("disabled").copy(enabled = false))
        persistForTest(baseArmed("pending").copy(status = AutomationStatus.PENDING_APPROVAL))
        persistForTest(baseArmed("disabled-status").copy(status = AutomationStatus.DISABLED))

        val armed = store.armed()
        assertEquals(listOf("p1", "p2"), armed.map { it.id.value })
        assertTrue(armed.all { it.status == AutomationStatus.ARMED && it.enabled })
    }

    @Test
    fun `disable updates status seen by get and drops it from armed`() = runTest {
        val a = baseArmed("toggle")
        persistForTest(a)
        assertEquals(1, store.armed().size)

        store.disable(a.id)
        assertEquals(AutomationStatus.DISABLED, store.get(a.id)?.status)
        assertTrue(store.armed().isEmpty())
    }

    @Test
    fun `conditional disable cannot consume a newer approved revision`() = runTest {
        val original = baseArmed("toggle-versioned")
        persistForTest(original)
        val originalFingerprint = requireNotNull(original.approvalFingerprint)
        val revised = signed(original.copy(name = "revised", approvalFingerprint = null))
        persistForTest(revised)

        assertTrue(!store.disableIfApproved(original.id, originalFingerprint))
        assertEquals(revised, store.get(original.id))
        assertTrue(store.disableIfApproved(revised.id, requireNotNull(revised.approvalFingerprint)))
        assertEquals(AutomationStatus.DISABLED, store.get(revised.id)?.status)
    }

    @Test
    fun `conditional needs review cannot quarantine a newer approved revision`() = runTest {
        val original = baseArmed("review-versioned")
        persistForTest(original)
        val oldFingerprint = requireNotNull(original.approvalFingerprint)
        val revised = signed(original.copy(name = "revised", approvalFingerprint = null))
        persistForTest(revised)

        assertTrue(!store.markNeedsReviewIfApproved(original.id, oldFingerprint))
        assertEquals(revised, store.get(original.id))
        assertTrue(
            store.markNeedsReviewIfApproved(
                revised.id,
                requireNotNull(revised.approvalFingerprint),
            ),
        )
        assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(revised.id)?.status)
    }

    @Test
    fun `enable restores only an unchanged approved automation`() = runTest {
        val automation = baseArmed("toggle-safe")
        persistForTest(automation)
        store.disable(automation.id)

        assertTrue(
            store.enableIfApproved(
                automation.id,
                requireNotNull(automation.approvalFingerprint),
            ),
        )
        assertEquals(AutomationStatus.ARMED, store.get(automation.id)?.status)
        store.disable(automation.id)

        // Un edit eseguibile conserva volontariamente la vecchia prova: deve fallire chiuso.
        persistForTest(
            automation.copy(name = "edit non approvato", status = AutomationStatus.DISABLED, enabled = false),
        )
        assertTrue(
            !store.enableIfApproved(
                automation.id,
                requireNotNull(automation.approvalFingerprint),
            ),
        )
        assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(automation.id)?.status)
    }

    @Test
    fun `conditional enable cannot arm or quarantine a newer approved revision`() = runTest {
        val original = baseArmed("enable-versioned")
        persistForTest(original)
        store.disable(original.id)
        val oldFingerprint = requireNotNull(original.approvalFingerprint)
        val revised = signed(
            original.copy(
                name = "revised",
                status = AutomationStatus.DISABLED,
                enabled = false,
                approvalFingerprint = null,
            ),
        )
        persistForTest(revised)

        assertTrue(!store.enableIfApproved(original.id, oldFingerprint))
        assertEquals(revised, store.get(original.id))
    }

    @Test
    fun `all and observeAll expose every rule in stable priority order`() = runTest {
        persistForTest(signed(baseArmed("p2").copy(priority = 2)))
        persistForTest(signed(baseArmed("p1").copy(priority = 1, status = AutomationStatus.DISABLED)))

        assertEquals(listOf("p1", "p2"), store.all().map { it.id.value })
        assertEquals(listOf("p1", "p2"), store.observeAll().first().map { it.id.value })
    }

    @Test
    fun `delete removes automation and cascades its persistent claims`() = runTest {
        val automation = baseArmed("delete-me")
        persistForTest(automation)
        assertEquals(FireClaimResult.Claimed, store.claimFire(claim(automation.id, "event-1", 1_000)))
        assertEquals(1, db.automationDao().claimCount(automation.id.value))

        store.delete(automation.id)

        assertNull(store.get(automation.id))
        assertEquals(0, db.automationDao().claimCount(automation.id.value))
    }

    // --- recordFired / lastFiredAt ------------------------------------------

    @Test
    fun `lastFiredAt is null before firing then returns recorded timestamp`() = runTest {
        val a = baseArmed("fire")
        persistForTest(a)
        assertNull(store.lastFiredAt(a.id))

        store.recordFired(a.id, 1_700_000_000_000)
        assertEquals(1_700_000_000_000, store.lastFiredAt(a.id))
    }

    @Test
    fun `save preserves lastFiredAt so re-save does not reset the cooldown`() = runTest {
        val a = baseArmed("fire-keep")
        persistForTest(a)
        store.recordFired(a.id, 42)
        persistForTest(signed(a.copy(name = "rinominata"))) // edit approvato dopo lo scatto
        assertEquals(42, store.lastFiredAt(a.id))
    }

    // --- atomic fire claim / idempotency ------------------------------------

    @Test
    fun `same event is claimed once and remains duplicate after newer events`() = runTest {
        val automation = baseArmed("claim-duplicate")
        persistForTest(automation)

        assertEquals(FireClaimResult.Claimed, store.claimFire(claim(automation.id, "event-1", 1_000)))
        assertEquals(FireClaimResult.Claimed, store.claimFire(claim(automation.id, "event-2", 2_000)))
        assertEquals(
            FireClaimResult.Duplicate(ExecutionId("exec-event-1")),
            store.claimFire(claim(automation.id, "event-1", 3_000)),
        )
        assertNull(db.automationDao().claimedExecutionId(automation.id.value, "event-1"))
        assertEquals(
            "exec-event-1",
            db.automationDao().claimedExecutionId(automation.id.value, identifierHash("event-1")),
        )
    }

    @Test
    fun `cooldown check and update are atomic across concurrent distinct events`() = runTest {
        val automation = baseArmed("claim-cooldown")
        persistForTest(automation)

        val results = coroutineScope {
            listOf("event-a", "event-b").map { eventId ->
                async {
                    eventId to store.claimFire(
                        claim(automation.id, eventId, atMillis = 10_000, cooldownMs = 5_000),
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.second == FireClaimResult.Claimed })
        assertEquals(1, results.count { it.second is FireClaimResult.Cooldown })
        assertEquals(10_000, store.lastFiredAt(automation.id))

        val suppressedEvent = results.single { it.second is FireClaimResult.Cooldown }.first
        assertEquals(
            FireClaimResult.Duplicate(ExecutionId("exec-$suppressedEvent")),
            store.claimFire(claim(automation.id, suppressedEvent, atMillis = 20_000, cooldownMs = 5_000)),
        )
    }

    @Test
    fun `disabled automation cannot acquire a fire claim`() = runTest {
        val automation = baseArmed("claim-disabled").copy(status = AutomationStatus.DISABLED)
        persistForTest(automation)
        assertEquals(
            FireClaimResult.NotEligible,
            store.claimFire(claim(automation.id, "event-1", atMillis = 1_000)),
        )
    }

    // --- decode-fail / schemaVersion → NEEDS_REVIEW (spec E8) ----------------

    @Test
    fun `corrupt json is surfaced as NEEDS_REVIEW placeholder, never thrown or dropped`() = runTest {
        // Riga scritta a mano con json invalido ma status ARMED (bypassa lo store).
        db.automationDao().upsert(
            AutomationEntity(
                id = "corrupt",
                name = "Regola rotta",
                status = AutomationStatus.ARMED,
                enabled = true,
                priority = 3,
                cooldownMs = 0,
                schemaVersion = 1,
                json = "{ this is definitely not valid json ]",
            )
        )

        val got = store.get(AutomationId("corrupt"))!!
        assertEquals(AutomationStatus.NEEDS_REVIEW, got.status)
        assertEquals("corrupt", got.id.value)   // id preservato per la UI
        assertEquals("Regola rotta", got.name)  // nome preservato per la UI
        assertTrue(got.actions.isEmpty())
        assertTrue(!got.enabled)

        // La quarantena è persistente e armed() non espone mai placeholder non armabili.
        val armed = store.armed()
        assertTrue(armed.isEmpty())
        val persisted = db.automationDao().getById("corrupt")!!
        assertEquals(AutomationStatus.NEEDS_REVIEW, persisted.status)
        assertTrue(!persisted.enabled)
    }

    @Test
    fun `incompatible schemaVersion yields NEEDS_REVIEW even with decodable json`() = runTest {
        val a = baseArmed("future")
        // json valido, ma schemaVersion futuro non gestibile.
        db.automationDao().upsert(
            AutomationEntity(
                id = a.id.value,
                name = a.name,
                status = AutomationStatus.ARMED,
                enabled = true,
                priority = 0,
                cooldownMs = 0,
                schemaVersion = 999,
                json = dev.argus.engine.model.ArgusJson.encodeToString(Automation.serializer(), a),
            )
        )

        val got = store.get(a.id)!!
        assertEquals(AutomationStatus.NEEDS_REVIEW, got.status)
        assertEquals(999, got.schemaVersion) // preservato per eventuale migrazione futura
        assertEquals(a.name, got.name)
        val persisted = db.automationDao().getById(a.id.value)!!
        assertEquals(AutomationStatus.NEEDS_REVIEW, persisted.status)
        assertTrue(!persisted.enabled)
    }

    // --- audit sink ----------------------------------------------------------

    @Test
    fun `audit sink records events retrievable per automation newest first`() = runTest {
        val id = AutomationId("aud")
        sink.record(AuditEvent(id, AuditKind.SUPPRESSED_COOLDOWN, atMillis = 100))
        sink.record(
            AuditEvent(
                id,
                AuditKind.FIRED,
                atMillis = 200,
                detail = "SetDnd",
                eventId = TriggerEventId("alarm:aud:1"),
                executionId = ExecutionId("exec-aud-1"),
            ),
        )
        sink.record(AuditEvent(AutomationId("other"), AuditKind.ERROR, atMillis = 150))

        val rows = db.auditDao().forAutomation(id.value)
        assertEquals(2, rows.size)
        assertEquals(AuditKind.FIRED, rows[0].kind)      // atMillis 200 (più recente)
        assertEquals("", rows[0].detail) // esiti per-azione nel journal, non nel campo libero
        assertEquals(identifierHash("alarm:aud:1"), rows[0].eventIdHash)
        assertEquals("exec-aud-1", rows[0].executionId)
        assertEquals(AuditKind.SUPPRESSED_COOLDOWN, rows[1].kind)
    }

    @Test
    fun `audit sink redacts arbitrary details but keeps safe cooldown metadata`() = runTest {
        val id = AutomationId("redaction")
        sink.record(AuditEvent(id, AuditKind.ERROR, 100, detail = "john@example.org: token segreto"))
        sink.record(AuditEvent(id, AuditKind.BLOCKED_POLICY, 200, detail = "jid_personale_42"))
        sink.record(AuditEvent(id, AuditKind.SUPPRESSED_COOLDOWN, 300, detail = "retry_at=123456"))
        sink.record(AuditEvent(id, AuditKind.BLOCKED_POLICY, 400, detail = "shell_external_trigger"))

        val rows = db.auditDao().forAutomation(id.value)
        assertEquals("shell_external_trigger", rows[0].detail)
        assertEquals("retry_at=123456", rows[1].detail)
        assertEquals("policy_blocked", rows[2].detail)
        assertEquals("execution_error", rows[3].detail)
    }

    @Test
    fun `audit SUPPRESSED_BUDGET conserva solo detail whitelisted`() = runTest {
        val id = AutomationId("budget-redaction")
        sink.record(AuditEvent(id, AuditKind.SUPPRESSED_BUDGET, 100, detail = "month_cost:openai"))
        sink.record(AuditEvent(id, AuditKind.SUPPRESSED_BUDGET, 200, detail = "http://leak.example/token?x=segreto"))

        val rows = db.auditDao().forAutomation(id.value)
        assertEquals("", rows[0].detail)                 // atMillis 200: detail libero -> redatto
        assertEquals("month_cost:openai", rows[1].detail) // atMillis 100: whitelisted sopravvive
    }

    @Test
    fun `audit VALIDATION_REJECTED tiene solo i code snake_case bounded`() = runTest {
        val id = AutomationId("validation-redaction")
        // Lista di code validi (costanti ValidationIssue) -> sopravvive integra.
        sink.record(
            AuditEvent(id, AuditKind.VALIDATION_REJECTED, 100, detail = "allowed_tools_unsupported,no_tools"),
        )
        // Testo libero (mai un code) -> azzerato dal filtro di forma.
        sink.record(
            AuditEvent(id, AuditKind.VALIDATION_REJECTED, 200, detail = "john@example.org ha rotto tutto"),
        )
        // Token misti: solo quelli a vocabolario chiuso passano.
        sink.record(
            AuditEvent(id, AuditKind.VALIDATION_REJECTED, 300, detail = "tz_invalid,DROP TABLE,cron_invalid"),
        )

        val rows = db.auditDao().forAutomation(id.value)
        assertEquals("tz_invalid,cron_invalid", rows[0].detail) // 300
        assertEquals("", rows[1].detail)                        // 200
        assertEquals("allowed_tools_unsupported,no_tools", rows[2].detail) // 100
    }

    @Test
    fun `audit ARM SCHEDULING ENABLE redigono reason fuori vocabolario`() = runTest {
        val id = AutomationId("reason-redaction")
        sink.record(AuditEvent(id, AuditKind.ARM_FAILED, 100, detail = "registrar_failed"))
        sink.record(AuditEvent(id, AuditKind.ARM_FAILED, 200, detail = "segreto: token=abc"))
        sink.record(AuditEvent(id, AuditKind.SCHEDULING_FAILED, 300, detail = "expired"))
        sink.record(AuditEvent(id, AuditKind.SCHEDULING_FAILED, 400, detail = "http://leak/x"))
        sink.record(AuditEvent(id, AuditKind.ENABLE_FAILED, 500, detail = "review_required"))
        sink.record(AuditEvent(id, AuditKind.ENABLE_FAILED, 600, detail = "expired"))

        val rows = db.auditDao().forAutomation(id.value)
        assertEquals("enable_failed", rows[0].detail)      // 600: "expired" non è un reason di enable
        assertEquals("review_required", rows[1].detail)    // 500
        assertEquals("scheduling_failed", rows[2].detail)  // 400: testo libero -> generico
        assertEquals("expired", rows[3].detail)            // 300
        assertEquals("arm_failed", rows[4].detail)         // 200: testo libero -> generico
        assertEquals("registrar_failed", rows[5].detail)   // 100
    }

    // --- helpers -------------------------------------------------------------

    /** Seed di fixture confinato ai test; il codice applicativo scrive solo via DraftRepository. */
    private suspend fun persistForTest(automation: Automation) {
        db.automationDao().upsertPreservingLastFired(
            AutomationEntity(
                id = automation.id.value,
                name = automation.name,
                status = automation.status,
                enabled = automation.enabled,
                priority = automation.priority,
                cooldownMs = automation.cooldownMs,
                schemaVersion = automation.schemaVersion,
                json = ArgusJson.encodeToString(Automation.serializer(), automation),
            ),
        )
    }

    private fun baseArmed(id: String) = signed(
        Automation(
            id = AutomationId(id),
            name = "auto-$id",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
            actions = listOf(Action.SetWifi(on = true)),
            conditions = null,
            enabled = true,
        ),
    )

    private fun signed(value: Automation): Automation =
        value.copy(approvalFingerprint = ApprovalFingerprints.of(value))

    private fun claim(
        automationId: AutomationId,
        eventId: String,
        atMillis: Long,
        cooldownMs: Long = 0,
    ) = FireClaimRequest(
        automationId = automationId,
        eventId = TriggerEventId(eventId),
        executionId = ExecutionId("exec-$eventId"),
        atMillis = atMillis,
        cooldownMs = cooldownMs,
    )
}
