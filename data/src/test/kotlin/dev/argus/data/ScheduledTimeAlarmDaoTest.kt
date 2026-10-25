package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTimeAlarmDaoTest {
    private lateinit var db: ArgusDatabase

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private fun alarm(id: String, eventAt: Long) = ScheduledTimeAlarmEntity(
        automationId = id,
        approvalFingerprint = "f".repeat(64),
        eventAtMillis = eventAt,
        wakeAtMillis = eventAt,
        requestedPrecision = "EXACT",
        scheduledMode = "EXACT",
        updatedAtMillis = 0L,
    )

    @Test
    fun `observeAll exposes the persisted alarms ordered by automation id`() = runTest {
        val dao = db.scheduledTimeAlarmDao()
        dao.upsert(alarm("b", 2_000))
        dao.upsert(alarm("a", 1_000))

        assertEquals(listOf("a", "b"), dao.observeAll().first().map { it.automationId })
        assertEquals(listOf(1_000L, 2_000L), dao.observeAll().first().map { it.eventAtMillis })

        dao.delete("a")
        assertEquals(listOf("b"), dao.observeAll().first().map { it.automationId })
    }
}
