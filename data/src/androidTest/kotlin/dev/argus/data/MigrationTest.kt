package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica strutturale della migrazione che introduce claim idempotenti e correlazione audit.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v1_to_v2() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, ArgusDatabase.MIGRATION_1_2).close()
    }

    private companion object {
        const val TEST_DB = "argus-migration-test.db"
    }
}
