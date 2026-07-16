package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Migrazione 9→10 (tabella usage_events) validata sull'host via Robolectric + MigrationTestHelper.
 * Semina come [MigrationTest] su device: una riga automations preesistente deve sopravvivere, poi
 * si verifica che usage_events accetti una scrittura completa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageEventsMigrationHostTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v9_to_v10_preserves_data_and_validates_schema() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                "INSERT INTO automations " +
                    "(id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('gen', 'gen', 'ARMED', 1, 0, 0, 1, NULL, '{}')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
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
                "SELECT timestampMs, providerId, model, kind, outcome, tokensIn, tokensOut, costMicros, pricingVersion " +
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
                assertEquals(true, cursor.isNull(7))
                assertEquals(true, cursor.isNull(8))
            }
        }
    }

    private companion object {
        const val TEST_DB = "argus-migration-v9-to-v10-host-test.db"
    }
}
