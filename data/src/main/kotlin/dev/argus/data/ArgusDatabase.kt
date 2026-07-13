package dev.argus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.argus.data.dao.AuditDao
import dev.argus.data.dao.AutomationDao
import dev.argus.data.entities.AuditEntity
import dev.argus.data.entities.AutomationEntity

@Database(
    entities = [AutomationEntity::class, AuditEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ArgusDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun auditDao(): AuditDao

    companion object {
        /** DB persistente (default per l'app). */
        fun build(context: Context, name: String = "argus.db"): ArgusDatabase =
            Room.databaseBuilder(context, ArgusDatabase::class.java, name).build()

        /** DB volatile (test / usi effimeri). I DAO restano suspend → nessuna query su main thread. */
        fun inMemory(context: Context): ArgusDatabase =
            Room.inMemoryDatabaseBuilder(context, ArgusDatabase::class.java).build()
    }
}
