package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/**
 * Verifica strutturale: claim/audit v2, bozze v3, journal v4/v7, scheduler v5, whitelist v6,
 * conversazioni osservate v8 e reply differite cifrate v9.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v1_to_v9() {
        helper.createDatabase(TEST_DB_V1, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V1,
            9,
            true,
            ArgusDatabase.MIGRATION_1_2,
            ArgusDatabase.MIGRATION_2_3,
            ArgusDatabase.MIGRATION_3_4,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).close()
    }

    @Test
    fun migrate_v8_to_v9() {
        helper.createDatabase(TEST_DB_V8, 8).apply {
            execSQL(
                "INSERT INTO automations " +
                    "(id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('gen', 'gen', 'ARMED', 1, 0, 0, 1, NULL, '{}')",
            )
            execSQL(
                "INSERT INTO fire_claims " +
                    "(automationId, eventIdHash, executionId, claimedAtMillis, status, " +
                    "completedAtMillis, succeededCount, failedCount, submittedCount, deferredCount) " +
                    "VALUES ('gen', 'hash', 'gen-execution', 123, 'DEFERRED', 200, 0, 0, 0, 1)",
            )
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB_V8,
            9,
            true,
            ArgusDatabase.MIGRATION_8_9,
        ).use { db ->
            db.execSQL(
                "INSERT INTO deferred_replies " +
                    "(executionId, actionIndex, packageName, createdAtMillis, expiresAtMillis, " +
                    "consumedAtMillis, payload) " +
                    "VALUES ('gen-execution', 0, 'com.whatsapp', 100, 200, NULL, 'ciphertext')",
            )
            db.query("SELECT payload FROM deferred_replies WHERE executionId = 'gen-execution'")
                .use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("ciphertext", cursor.getString(0))
                }
            // La FK CASCADE lega il ciphertext alla vita dell'esecuzione.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM fire_claims WHERE executionId = 'gen-execution'")
            db.query("SELECT COUNT(*) FROM deferred_replies").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate_v2_to_v9() {
        helper.createDatabase(TEST_DB_V2, 2).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V2,
            9,
            true,
            ArgusDatabase.MIGRATION_2_3,
            ArgusDatabase.MIGRATION_3_4,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).close()
    }

    @Test
    fun migrate_v3_to_v9() {
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
        helper.runMigrationsAndValidate(
            TEST_DB_V3,
            9,
            true,
            ArgusDatabase.MIGRATION_3_4,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).use { db ->
            db.query(
                "SELECT status, succeededCount, failedCount, submittedCount, deferredCount " +
                    "FROM fire_claims WHERE executionId = 'legacy-execution'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("INTERRUPTED", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
            }
        }
    }

    @Test
    fun migrate_v4_to_v9() {
        helper.createDatabase(TEST_DB_V4, 4).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V4,
            9,
            true,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM scheduled_time_alarms").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate_v5_to_v9() {
        helper.createDatabase(TEST_DB_V5, 5).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V5,
            9,
            true,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM whitelisted_contacts").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate_v6_to_v9() {
        helper.createDatabase(TEST_DB_V6, 6).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V6,
            9,
            true,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).use { db ->
            db.query("SELECT deferredCount FROM fire_claims").use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
    }

    @Test
    fun migrate_v7_to_v9() {
        helper.createDatabase(TEST_DB_V7, 7).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V7,
            9,
            true,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
        ).use { db ->
            db.execSQL(
                "INSERT INTO observed_conversations " +
                    "(conversationId, packageName, displayName, isGroup, lastSeenAtMillis) " +
                    "VALUES ('shortcut:com.whatsapp:hash', 'com.whatsapp', 'Fixture', 0, 123)",
            )
            db.query(
                "SELECT packageName, displayName, isGroup, lastSeenAtMillis " +
                    "FROM observed_conversations",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("com.whatsapp", cursor.getString(0))
                assertEquals("Fixture", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(123L, cursor.getLong(3))
            }
        }
    }

    private companion object {
        const val TEST_DB_V1 = "argus-migration-v1-test.db"
        const val TEST_DB_V2 = "argus-migration-v2-test.db"
        const val TEST_DB_V3 = "argus-migration-v3-test.db"
        const val TEST_DB_V4 = "argus-migration-v4-test.db"
        const val TEST_DB_V5 = "argus-migration-v5-test.db"
        const val TEST_DB_V6 = "argus-migration-v6-test.db"
        const val TEST_DB_V7 = "argus-migration-v7-test.db"
        const val TEST_DB_V8 = "argus-migration-v8-test.db"
    }
}
