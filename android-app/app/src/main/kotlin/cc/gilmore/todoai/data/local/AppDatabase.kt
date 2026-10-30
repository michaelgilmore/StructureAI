package cc.gilmore.todoai.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TodoEntity::class, GoalEntity::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun goalDao(): GoalDao

    companion object {
        private const val TAG = "TodoAi.AppDatabase"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    Log.d(TAG, "building Room database…")
                    try {
                        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "todo-ai.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .build()
                            .also {
                                instance = it
                                Log.d(TAG, "Room database ready")
                            }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Room database build failed", e)
                        throw e
                    }
                }
            }

        /**
         * Additive-only, deliberately NOT fallbackToDestructiveMigration —
         * this table is also the offline outbox (see TodoEntity's doc
         * comment), so a destructive migration could silently drop real
         * unsynced edits (PENDING_CREATE/PENDING_UPDATE/PENDING_DELETE rows)
         * still waiting on SyncWorker. New rows default to NULL, matching
         * TodoEntity.completedAt's meaning for a not-yet-known value.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todos ADD COLUMN completedAt TEXT")
            }
        }

        /**
         * Adds the "goals" table — a brand-new table, not an ALTER on an
         * existing one, so (unlike MIGRATION_1_2) there's no pre-existing
         * data to preserve here; this just needs to match GoalEntity's
         * column set exactly. See android-app/README.md's "Goals" section.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goals` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT, " +
                        "`targetDate` TEXT, `status` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
                        "`syncState` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
