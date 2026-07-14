package dev.argus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.argus.data.dao.AuditDao
import dev.argus.data.dao.AutomationDao
import dev.argus.data.dao.ContactWhitelistDao
import dev.argus.data.dao.DeferredReplyDao
import dev.argus.data.dao.DraftDao
import dev.argus.data.dao.ExecutionJournalDao
import dev.argus.data.dao.ObservedConversationDao
import dev.argus.data.dao.ScheduledTimeAlarmDao
import dev.argus.data.entities.ActionResultEntity
import dev.argus.data.entities.AuditEntity
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.DeferredReplyEntity
import dev.argus.data.entities.FireClaimEntity
import dev.argus.data.entities.PendingDraftEntity
import dev.argus.data.entities.ObservedConversationEntity
import dev.argus.data.entities.ScheduledTimeAlarmEntity
import dev.argus.data.entities.WhitelistedContactEntity

@Database(
    entities = [
        AutomationEntity::class,
        AuditEntity::class,
        FireClaimEntity::class,
        PendingDraftEntity::class,
        ActionResultEntity::class,
        ScheduledTimeAlarmEntity::class,
        WhitelistedContactEntity::class,
        ObservedConversationEntity::class,
        DeferredReplyEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ArgusDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun auditDao(): AuditDao
    abstract fun draftDao(): DraftDao
    abstract fun executionJournalDao(): ExecutionJournalDao
    abstract fun scheduledTimeAlarmDao(): ScheduledTimeAlarmDao
    abstract fun contactWhitelistDao(): ContactWhitelistDao
    abstract fun observedConversationDao(): ObservedConversationDao
    abstract fun deferredReplyDao(): DeferredReplyDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audit` ADD COLUMN `eventIdHash` TEXT")
                db.execSQL("ALTER TABLE `audit` ADD COLUMN `executionId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_eventIdHash` ON `audit` (`eventIdHash`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_executionId` ON `audit` (`executionId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fire_claims` (
                        `automationId` TEXT NOT NULL,
                        `eventIdHash` TEXT NOT NULL,
                        `executionId` TEXT NOT NULL,
                        `claimedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`automationId`, `eventIdHash`),
                        FOREIGN KEY(`automationId`) REFERENCES `automations`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_fire_claims_executionId` " +
                        "ON `fire_claims` (`executionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fire_claims_claimedAtMillis` " +
                        "ON `fire_claims` (`claimedAtMillis`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_drafts` (
                        `id` TEXT NOT NULL,
                        `automationId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `createdBy` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `quarantineCode` TEXT,
                        `draftJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_drafts_automationId` " +
                        "ON `pending_drafts` (`automationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_drafts_updatedAtMillis` " +
                        "ON `pending_drafts` (`updatedAtMillis`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pending_drafts` ADD COLUMN `baseAutomationFingerprint` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `fire_claims` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'INTERRUPTED'",
                )
                db.execSQL("ALTER TABLE `fire_claims` ADD COLUMN `completedAtMillis` INTEGER")
                db.execSQL(
                    "ALTER TABLE `fire_claims` ADD COLUMN `succeededCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `fire_claims` ADD COLUMN `failedCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `fire_claims` ADD COLUMN `submittedCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fire_claims_status` ON `fire_claims` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fire_claims_completedAtMillis` " +
                        "ON `fire_claims` (`completedAtMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `action_results` (
                        `executionId` TEXT NOT NULL,
                        `actionIndex` INTEGER NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `atMillis` INTEGER NOT NULL,
                        `errorCode` TEXT,
                        PRIMARY KEY(`executionId`, `actionIndex`),
                        FOREIGN KEY(`executionId`) REFERENCES `fire_claims`(`executionId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_action_results_executionId` " +
                        "ON `action_results` (`executionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_action_results_atMillis` " +
                        "ON `action_results` (`atMillis`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_time_alarms` (
                        `automationId` TEXT NOT NULL,
                        `approvalFingerprint` TEXT NOT NULL,
                        `eventAtMillis` INTEGER NOT NULL,
                        `wakeAtMillis` INTEGER NOT NULL,
                        `requestedPrecision` TEXT NOT NULL,
                        `scheduledMode` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`automationId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whitelisted_contacts` (
                        `conversationId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        PRIMARY KEY(`conversationId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_whitelisted_contacts_displayName` " +
                        "ON `whitelisted_contacts` (`displayName`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `fire_claims` ADD COLUMN `deferredCount` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `observed_conversations` (
                        `conversationId` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `isGroup` INTEGER,
                        `lastSeenAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observed_conversations_lastSeenAtMillis` " +
                        "ON `observed_conversations` (`lastSeenAtMillis`)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `deferred_replies` (
                        `executionId` TEXT NOT NULL,
                        `actionIndex` INTEGER NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `expiresAtMillis` INTEGER NOT NULL,
                        `consumedAtMillis` INTEGER,
                        `payload` TEXT NOT NULL,
                        PRIMARY KEY(`executionId`, `actionIndex`),
                        FOREIGN KEY(`executionId`) REFERENCES `fire_claims`(`executionId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_deferred_replies_expiresAtMillis` " +
                        "ON `deferred_replies` (`expiresAtMillis`)",
                )
            }
        }

        fun build(context: Context, name: String = "argus.db"): ArgusDatabase =
            Room.databaseBuilder(context, ArgusDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                .build()

        fun inMemory(context: Context): ArgusDatabase =
            Room.inMemoryDatabaseBuilder(context, ArgusDatabase::class.java).build()
    }
}
