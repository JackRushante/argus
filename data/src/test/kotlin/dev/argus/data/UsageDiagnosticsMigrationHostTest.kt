package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageDiagnosticsMigrationHostTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v11_to_v12_adds_nullable_diagnostics_without_losing_usage() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                "INSERT INTO usage_events " +
                    "(timestampMs, providerId, model, kind, outcome, tokensIn, tokensOut, costMicros, pricingVersion) " +
                    "VALUES (123, 'custom_openai_compat', 'local', 'ACT', 'OK', 10, 4, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 12, true, ArgusDatabase.MIGRATION_11_12).use { db ->
            db.query("SELECT tokensIn, tokensOut, reasoningTokens, finishReason FROM usage_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals(10L, cursor.getLong(0))
                assertEquals(4L, cursor.getLong(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(true, cursor.isNull(3))
            }
        }
    }

    private companion object {
        const val TEST_DB = "argus-migration-v11-to-v12-host-test.db"
    }
}
