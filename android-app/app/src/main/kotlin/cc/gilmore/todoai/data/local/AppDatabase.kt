package cc.gilmore.todoai.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TodoEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao

    companion object {
        private const val TAG = "TodoAi.AppDatabase"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    Log.d(TAG, "building Room database…")
                    try {
                        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "todo-ai.db")
                            .addMigrations(MIGRATION_1_2)
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
    }
}
