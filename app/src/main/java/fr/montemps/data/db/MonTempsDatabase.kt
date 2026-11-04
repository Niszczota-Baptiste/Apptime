package fr.montemps.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.montemps.domain.model.DefaultActivities

@Database(entities = [ActivityEntity::class, SessionEntity::class], version = 1, exportSchema = true)
abstract class MonTempsDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val FILE_NAME = "montemps.db"

        fun build(context: Context, inMemory: Boolean = false): MonTempsDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, MonTempsDatabase::class.java)
            } else {
                Room.databaseBuilder(context, MonTempsDatabase::class.java, FILE_NAME)
            }
            return builder.addCallback(Callbacks).build()
        }
    }

    /**
     * - À la création : insère les activités par défaut (dans la transaction de création).
     * - À chaque ouverture : (re)crée les triggers d'intégrité. Ils sont une défense en profondeur :
     *   la logique métier garantit déjà ces règles, mais la base refuse aussi une seconde session
     *   active ou une fin non postérieure au début, même en cas de bug.
     */
    object Callbacks : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            DefaultActivities.all.forEach { a ->
                db.insert(
                    "activities",
                    SQLiteDatabase.CONFLICT_IGNORE,
                    ContentValues().apply {
                        put("id", a.id)
                        put("name", a.name)
                        put("color", a.color)
                        put("icon", a.icon)
                        put("sort_order", a.sortOrder)
                        put("archived", if (a.archived) 1 else 0)
                    },
                )
            }
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            INTEGRITY_TRIGGERS.forEach(db::execSQL)
        }
    }
}

internal val INTEGRITY_TRIGGERS = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS sessions_single_active_insert
    BEFORE INSERT ON sessions
    WHEN NEW.end_utc_ms IS NULL AND EXISTS (SELECT 1 FROM sessions WHERE end_utc_ms IS NULL)
    BEGIN SELECT RAISE(ABORT, 'active_session_exists'); END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS sessions_single_active_update
    BEFORE UPDATE OF end_utc_ms ON sessions
    WHEN NEW.end_utc_ms IS NULL AND EXISTS (SELECT 1 FROM sessions WHERE end_utc_ms IS NULL AND id != NEW.id)
    BEGIN SELECT RAISE(ABORT, 'active_session_exists'); END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS sessions_valid_range_insert
    BEFORE INSERT ON sessions
    WHEN NEW.end_utc_ms IS NOT NULL AND NEW.end_utc_ms <= NEW.start_utc_ms
    BEGIN SELECT RAISE(ABORT, 'invalid_range'); END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS sessions_valid_range_update
    BEFORE UPDATE ON sessions
    WHEN NEW.end_utc_ms IS NOT NULL AND NEW.end_utc_ms <= NEW.start_utc_ms
    BEGIN SELECT RAISE(ABORT, 'invalid_range'); END
    """.trimIndent(),
)
