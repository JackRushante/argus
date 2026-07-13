package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica strutturale delle migrazioni: claim/audit v2 e bozze revisionate v3.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v1_to_v3() {
        helper.createDatabase(TEST_DB_V1, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB_V1,
            3,
            true,
            ArgusDatabase.MIGRATION_1_2,
            ArgusDatabase.MIGRATION_2_3,
        ).close()
    }

    @Test
    fun migrate_v2_to_v3() {
        helper.createDatabase(TEST_DB_V2, 2).close()
        helper.runMigrationsAndValidate(TEST_DB_V2, 3, true, ArgusDatabase.MIGRATION_2_3).close()
    }

    private companion object {
        const val TEST_DB_V1 = "argus-migration-v1-test.db"
        const val TEST_DB_V2 = "argus-migration-v2-test.db"
    }
}
