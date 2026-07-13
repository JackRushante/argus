package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/**
 * Verifica strutturale: claim/audit v2, bozze v3 e journal per-azione v4.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v1_to_v4() {
        helper.createDatabase(TEST_DB_V1, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V1,
            4,
            true,
            ArgusDatabase.MIGRATION_1_2,
            ArgusDatabase.MIGRATION_2_3,
            ArgusDatabase.MIGRATION_3_4,
        ).close()
    }

    @Test
    fun migrate_v2_to_v4() {
        helper.createDatabase(TEST_DB_V2, 2).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V2,
            4,
            true,
            ArgusDatabase.MIGRATION_2_3,
            ArgusDatabase.MIGRATION_3_4,
        ).close()
    }

    @Test
    fun migrate_v3_to_v4() {
        helper.createDatabase(TEST_DB_V3, 3).apply {
            execSQL(
                "INSERT INTO automations " +
                    "(id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('legacy', 'legacy', 'ARMED', 1, 0, 0, 1, NULL, '{}')",
            )
            execSQL(
                "INSERT INTO fire_claims (automationId, eventIdHash, executionId, claimedAtMillis) " +
                    "VALUES ('legacy', 'hash', 'legacy-execution', 123)",
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB_V3, 4, true, ArgusDatabase.MIGRATION_3_4).use { db ->
            db.query(
                "SELECT status, succeededCount, failedCount, submittedCount " +
                    "FROM fire_claims WHERE executionId = 'legacy-execution'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("INTERRUPTED", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }
        }
    }

    private companion object {
        const val TEST_DB_V1 = "argus-migration-v1-test.db"
        const val TEST_DB_V2 = "argus-migration-v2-test.db"
        const val TEST_DB_V3 = "argus-migration-v3-test.db"
    }
}
