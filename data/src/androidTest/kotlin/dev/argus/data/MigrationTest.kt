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
 * conversazioni osservate v8, reply differite cifrate v9, consumo LLM v10 e path azioni P4 v11.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v1_to_v10() {
        helper.createDatabase(TEST_DB_V1, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V1,
            10,
            true,
            ArgusDatabase.MIGRATION_1_2,
            ArgusDatabase.MIGRATION_2_3,
            ArgusDatabase.MIGRATION_3_4,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
        ).close()
    }

    @Test
    fun migrate_v8_to_v10() {
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
            10,
            true,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
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
    fun migrate_v2_to_v10() {
        helper.createDatabase(TEST_DB_V2, 2).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V2,
            10,
            true,
            ArgusDatabase.MIGRATION_2_3,
            ArgusDatabase.MIGRATION_3_4,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
        ).close()
    }

    @Test
    fun migrate_v3_to_v10() {
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
            10,
            true,
            ArgusDatabase.MIGRATION_3_4,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
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
    fun migrate_v4_to_v10() {
        helper.createDatabase(TEST_DB_V4, 4).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V4,
            10,
            true,
            ArgusDatabase.MIGRATION_4_5,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM scheduled_time_alarms").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate_v5_to_v10() {
        helper.createDatabase(TEST_DB_V5, 5).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V5,
            10,
            true,
            ArgusDatabase.MIGRATION_5_6,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM whitelisted_contacts").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate_v6_to_v10() {
        helper.createDatabase(TEST_DB_V6, 6).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V6,
            10,
            true,
            ArgusDatabase.MIGRATION_6_7,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT deferredCount FROM fire_claims").use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
    }

    @Test
    fun migrate_v7_to_v10() {
        helper.createDatabase(TEST_DB_V7, 7).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V7,
            10,
            true,
            ArgusDatabase.MIGRATION_7_8,
            ArgusDatabase.MIGRATION_8_9,
            ArgusDatabase.MIGRATION_9_10,
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

    @Test
    fun migrate_v9_to_v10() {
        helper.createDatabase(TEST_DB_V9, 9).apply {
            execSQL(
                "INSERT INTO automations " +
                    "(id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('gen', 'gen', 'ARMED', 1, 0, 0, 1, NULL, '{}')",
            )
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB_V9,
            10,
            true,
            ArgusDatabase.MIGRATION_9_10,
        ).use { db ->
            db.query("SELECT id FROM automations").use { cursor ->
                cursor.moveToFirst()
                assertEquals("gen", cursor.getString(0))
            }
            db.execSQL(
                "INSERT INTO usage_events " +
                    "(timestampMs, providerId, model, kind, outcome, tokensIn, tokensOut, costMicros, pricingVersion) " +
                    "VALUES (123, 'openai', 'gpt-5.5', 'ACT_V2', 'OK', 10, 2, NULL, NULL)",
            )
            db.query(
                "SELECT timestampMs, providerId, model, kind, outcome, tokensIn, tokensOut " +
                    "FROM usage_events",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(123L, cursor.getLong(0))
                assertEquals("openai", cursor.getString(1))
                assertEquals("gpt-5.5", cursor.getString(2))
                assertEquals("ACT_V2", cursor.getString(3))
                assertEquals("OK", cursor.getString(4))
                assertEquals(10L, cursor.getLong(5))
                assertEquals(2L, cursor.getLong(6))
            }
        }
    }

    @Test
    fun migrate_v10_to_v11_backfills_legacy_action_paths() {
        helper.createDatabase(TEST_DB_V10, 10).apply {
            execSQL(
                "INSERT INTO automations " +
                    "(id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('legacy', 'legacy', 'ARMED', 1, 0, 0, 1, NULL, '{}')",
            )
            execSQL(
                "INSERT INTO fire_claims " +
                    "(automationId, eventIdHash, executionId, claimedAtMillis, status, " +
                    "completedAtMillis, succeededCount, failedCount, submittedCount, deferredCount) " +
                    "VALUES ('legacy', 'hash', 'legacy-execution', 123, 'SUCCEEDED', " +
                    "200, 1, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO action_results " +
                    "(executionId, actionIndex, actionType, outcome, atMillis, errorCode) " +
                    "VALUES ('legacy-execution', 2, 'set_wifi', 'SUCCEEDED', 200, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB_V10,
            11,
            true,
            ArgusDatabase.MIGRATION_10_11,
        ).use { db ->
            db.query(
                "SELECT actionPath FROM action_results WHERE executionId = 'legacy-execution'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("3", cursor.getString(0))
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
        const val TEST_DB_V9 = "argus-migration-v9-test.db"
        const val TEST_DB_V10 = "argus-migration-v10-test.db"
    }
}
