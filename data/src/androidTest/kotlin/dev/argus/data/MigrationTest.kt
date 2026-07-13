package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scaffolding migrazioni (T3 Step 4). Con lo schema v1 esportato in assets, valida che aprire un
 * DB v1 sia un no-op (nessuna migrazione ancora). Le future v1->v2 aggiungono `addMigrations(...)`
 * qui. Richiede un device/emulatore (instrumented) — fuori dallo scope del dry-run unit.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v1_isNoOp() {
        helper.createDatabase(TEST_DB, 1).close()
        // Nessuna migrazione registrata: riaprire/validare a v1 deve riuscire invariato.
        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    private companion object {
        const val TEST_DB = "argus-migration-test.db"
    }
}
