package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
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
        val a = Automation(
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
        )
        store.save(a)
        assertEquals(a, store.get(a.id))
    }

    @Test
    fun `round-trips a Geofence trigger with generative action and nested conditions`() = runTest {
        val a = Automation(
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
        )
        store.save(a)
        assertEquals(a, store.get(a.id))
    }

    @Test
    fun `round-trips a Connectivity trigger`() = runTest {
        val a = baseArmed("power-plug").copy(
            trigger = Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED),
            actions = listOf(Action.SetBluetooth(on = false), Action.ShowNotification("t", "x")),
        )
        store.save(a)
        assertEquals(a, store.get(a.id))
    }

    @Test
    fun `get returns null for unknown id`() = runTest {
        assertNull(store.get(AutomationId("missing")))
    }

    @Test
    fun `save is idempotent upsert and reflects edits`() = runTest {
        val a = baseArmed("edit-me")
        store.save(a)
        val edited = a.copy(name = "nuovo nome", priority = 9)
        store.save(edited)
        assertEquals(edited, store.get(a.id))
    }

    // --- armed() filter ------------------------------------------------------

    @Test
    fun `armed returns only ARMED and enabled ordered by priority ascending`() = runTest {
        store.save(baseArmed("p2").copy(priority = 2))
        store.save(baseArmed("p1").copy(priority = 1))
        store.save(baseArmed("disabled").copy(enabled = false))
        store.save(baseArmed("pending").copy(status = AutomationStatus.PENDING_APPROVAL))
        store.save(baseArmed("disabled-status").copy(status = AutomationStatus.DISABLED))

        val armed = store.armed()
        assertEquals(listOf("p1", "p2"), armed.map { it.id.value })
        assertTrue(armed.all { it.status == AutomationStatus.ARMED && it.enabled })
    }

    @Test
    fun `setStatus updates status seen by get and drops it from armed`() = runTest {
        val a = baseArmed("toggle")
        store.save(a)
        assertEquals(1, store.armed().size)

        store.setStatus(a.id, AutomationStatus.DISABLED)
        assertEquals(AutomationStatus.DISABLED, store.get(a.id)?.status)
        assertTrue(store.armed().isEmpty())
    }

    // --- recordFired / lastFiredAt ------------------------------------------

    @Test
    fun `lastFiredAt is null before firing then returns recorded timestamp`() = runTest {
        val a = baseArmed("fire")
        store.save(a)
        assertNull(store.lastFiredAt(a.id))

        store.recordFired(a.id, 1_700_000_000_000)
        assertEquals(1_700_000_000_000, store.lastFiredAt(a.id))
    }

    @Test
    fun `save preserves lastFiredAt so re-save does not reset the cooldown`() = runTest {
        val a = baseArmed("fire-keep")
        store.save(a)
        store.recordFired(a.id, 42)
        store.save(a.copy(name = "rinominata")) // edit dopo lo scatto
        assertEquals(42, store.lastFiredAt(a.id))
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

        // armed() non deve né lanciare né perderla silenziosamente: appare come NEEDS_REVIEW.
        val armed = store.armed()
        assertEquals(1, armed.size)
        assertEquals(AutomationStatus.NEEDS_REVIEW, armed.single().status)
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
    }

    // --- audit sink ----------------------------------------------------------

    @Test
    fun `audit sink records events retrievable per automation newest first`() = runTest {
        val id = AutomationId("aud")
        sink.record(AuditEvent(id, AuditKind.SUPPRESSED_COOLDOWN, atMillis = 100))
        sink.record(AuditEvent(id, AuditKind.FIRED, atMillis = 200, detail = "SetDnd"))
        sink.record(AuditEvent(AutomationId("other"), AuditKind.ERROR, atMillis = 150))

        val rows = db.auditDao().forAutomation(id.value)
        assertEquals(2, rows.size)
        assertEquals(AuditKind.FIRED, rows[0].kind)      // atMillis 200 (più recente)
        assertEquals("SetDnd", rows[0].detail)
        assertEquals(AuditKind.SUPPRESSED_COOLDOWN, rows[1].kind)
    }

    // --- helpers -------------------------------------------------------------

    private fun baseArmed(id: String) = Automation(
        id = AutomationId(id),
        name = "auto-$id",
        createdBy = CreatedBy.USER,
        status = AutomationStatus.ARMED,
        trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC"),
        actions = listOf(Action.SetWifi(on = true)),
        conditions = null,
        enabled = true,
    )
}
