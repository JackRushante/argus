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
import dev.argus.data.entities.AuditEntity
import dev.argus.data.entities.AutomationEntity
import dev.argus.data.entities.FireClaimEntity

@Database(
    entities = [AutomationEntity::class, AuditEntity::class, FireClaimEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ArgusDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun auditDao(): AuditDao

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

        fun build(context: Context, name: String = "argus.db"): ArgusDatabase =
            Room.databaseBuilder(context, ArgusDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): ArgusDatabase =
            Room.inMemoryDatabaseBuilder(context, ArgusDatabase::class.java).build()
    }
}
