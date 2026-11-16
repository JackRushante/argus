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
 * 13 → 14 (0.8): valori conservati, elenco delle modalità e modalità attiva. Tabelle nuove e vuote; regole ed
 * etichette esistenti restano intatte; lo schema migrato coincide con `14.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoredStateMigrationHostTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v13_to_v14_adds_stored_values_and_modes_without_touching_rules() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                "INSERT INTO automations (id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('r1', 'Arrivo a casa', 'ARMED', 1, 0, 0, 1, 77, '{\"x\":1}')",
            )
            execSQL(
                "INSERT INTO automation_labels (automationId, labelKey, label, addedAtMillis) VALUES ('r1', 'casa', 'Casa', 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 14, true, ArgusDatabase.MIGRATION_13_14).use { db ->
            db.query("SELECT name, lastFiredAt, json FROM automations WHERE id = 'r1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Arrivo a casa", cursor.getString(0))
                assertEquals(77L, cursor.getLong(1))
                assertEquals("{\"x\":1}", cursor.getString(2))
            }
            db.query("SELECT label FROM automation_labels WHERE automationId = 'r1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Casa", cursor.getString(0))
            }
            listOf("stored_values", "user_modes", "user_mode_state").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0), table)
                }
            }
            // Le tabelle nuove accettano le righe che scrive l'app.
            db.execSQL(
                "INSERT INTO stored_values (storedKey, type, storedValue, integrity, confidentiality, writerRuleId, " +
                    "updatedAtMillis) VALUES ('caffe_oggi', 'NUMBER', '2', 'CLEAN', 'PUBLIC', 'r1', 5)",
            )
            db.execSQL("INSERT INTO user_modes (nameKey, name, position) VALUES ('casa', 'Casa', 0)")
            db.execSQL(
                "INSERT INTO user_mode_state (id, activeKey, changedByRuleId, changedAtMillis) VALUES (0, 'casa', NULL, 9)",
            )
            db.query("SELECT storedValue FROM stored_values WHERE storedKey = 'caffe_oggi'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("2", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "argus-migration-v13-to-v14-host-test.db"
    }
}
