package dev.argus.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 12 → 13 (0.7.2): la tabella delle etichette legata alla regola (CASCADE) e la colonna delle etichette delle
 * bozze. Regole e bozze esistenti restano intatte; lo schema migrato coincide con `13.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleLabelsMigrationHostTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArgusDatabase::class.java,
    )

    @Test
    fun migrate_v12_to_v13_adds_labels_without_touching_rules_or_drafts() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL(
                "INSERT INTO automations (id, name, status, enabled, priority, cooldownMs, schemaVersion, lastFiredAt, json) " +
                    "VALUES ('r1', 'Arrivo a casa', 'ARMED', 1, 0, 0, 1, 77, '{\"x\":1}')",
            )
            execSQL(
                "INSERT INTO pending_drafts (id, automationId, name, revision, fingerprint, createdBy, priority, " +
                    "schemaVersion, createdAtMillis, updatedAtMillis, baseAutomationFingerprint, quarantineCode, draftJson) " +
                    "VALUES ('d1', 'r2', 'Bozza', 1, 'fp', 'LLM', 0, 1, 5, 5, NULL, NULL, '{}')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 13, true, ArgusDatabase.MIGRATION_12_13).use { db ->
            db.query("SELECT name, lastFiredAt, json FROM automations WHERE id = 'r1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Arrivo a casa", cursor.getString(0))
                assertEquals(77L, cursor.getLong(1))
                assertEquals("{\"x\":1}", cursor.getString(2))
            }
            db.query("SELECT draftJson, labels FROM pending_drafts WHERE id = 'd1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("{}", cursor.getString(0))
                assertTrue(cursor.isNull(1), "una bozza esistente non ha etichette")
            }
            db.query("SELECT COUNT(*) FROM automation_labels").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }

            // Eliminare la regola elimina le sue etichette.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO automation_labels (automationId, labelKey, label, addedAtMillis) VALUES ('r1', 'casa', 'Casa', 1)",
            )
            db.execSQL("DELETE FROM automations WHERE id = 'r1'")
            db.query("SELECT COUNT(*) FROM automation_labels").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "argus-migration-v12-to-v13-host-test.db"
    }
}
