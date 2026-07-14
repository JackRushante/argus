package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.data.entities.AuditEntity
import dev.argus.engine.runtime.AuditKind
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditLogQueryTest {
    private lateinit var db: ArgusDatabase

    @Before
    fun setUp() {
        db = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `reactive log keeps the audit id separate from the automation id`() = runTest {
        val rowId = db.auditDao().insert(
            AuditEntity(
                automationId = "automation-deleted",
                kind = AuditKind.ERROR,
                atMillis = 42,
                detail = "execution_error",
            ),
        )

        val record = db.auditDao().observeLog(limit = 10).first().single()

        assertEquals(rowId, record.id)
        assertEquals("automation-deleted", record.automationId)
        assertNull(record.automationName)
        assertEquals(AuditKind.ERROR, record.kind)
    }
}
