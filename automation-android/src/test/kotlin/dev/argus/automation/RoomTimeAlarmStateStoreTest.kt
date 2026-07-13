package dev.argus.automation

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.ArgusDatabase
import dev.argus.data.RoomAutomationStore
import dev.argus.data.entities.AutomationEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomTimeAlarmStateStoreTest {
    private lateinit var db: ArgusDatabase
    private lateinit var state: RoomTimeAlarmStateStore

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        state = RoomTimeAlarmStateStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `record round trips and survives automation deletion for OS cleanup`() = runTest {
        val automation = seed("a1")
        val alarm = record(automation)
        state.upsert(alarm)

        assertEquals(alarm, state.get(automation.id))
        RoomAutomationStore(db.automationDao()).delete(automation.id)

        assertNotNull(state.get(automation.id))
    }

    @Test
    fun `corrupt scheduler state is deleted without quarantining valid automation`() = runTest {
        val automation = seed("corrupt")
        state.upsert(record(automation))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE scheduled_time_alarms SET scheduledMode = 'UNKNOWN' WHERE automationId = 'corrupt'",
        )

        assertNull(state.get(automation.id))
        assertEquals(0, db.scheduledTimeAlarmDao().count())
        assertEquals(AutomationStatus.ARMED, db.automationDao().getById("corrupt")?.status)
    }

    private suspend fun seed(id: String): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = "auto-$id",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "UTC"),
            actions = listOf(Action.ShowNotification("Argus", "test")),
        )
        val automation = unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
        db.automationDao().upsert(
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
        return automation
    }

    private fun record(automation: Automation) = ScheduledTimeAlarm(
        automationId = automation.id,
        approvalFingerprint = requireNotNull(automation.approvalFingerprint),
        eventAtMillis = 1_800_000_000_000,
        wakeAtMillis = 1_800_000_000_000,
        requestedPrecision = TimePrecision.EXACT,
        scheduledMode = ScheduledAlarmMode.INEXACT,
        updatedAtMillis = 1_700_000_000_000,
    )
}
