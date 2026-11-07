package fr.montemps.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.montemps.domain.model.DefaultActivities

@Database(entities = [ActivityEntity::class, SubActivityEntity::class, SessionEntity::class], version = 3, exportSchema = true)
abstract class MonTempsDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun subActivityDao(): SubActivityDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val FILE_NAME = "montemps.db"

        fun build(context: Context, inMemory: Boolean = false): MonTempsDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, MonTempsDatabase::class.java)
            } else {
                Room.databaseBuilder(context, MonTempsDatabase::class.java, FILE_NAME)
            }
            return builder.addCallback(Callbacks).addMigrations(*MIGRATIONS).build()
        }

        /**
         * Version 2 : activités d'interruption et objectifs. Les données existantes sont conservées ;
         * Pause et Repas (identifiants stables) deviennent des interruptions.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN interruption INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE activities ADD COLUMN goal_minutes INTEGER")
                db.execSQL("ALTER TABLE activities ADD COLUMN goal_period TEXT")
                db.execSQL("UPDATE activities SET interruption = 1 WHERE id IN ('break', 'meal')")
            }
        }

        /**
         * Version 3 : sous-catégories. Données existantes conservées (sessions « sans précision ») ;
         * sous-catégories par défaut ajoutées aux activités par défaut encore présentes ; Conduite
         * devient Déplacement si son nom n'avait pas été changé ; activité Sommeil ajoutée (sauf si une
         * activité porte déjà ce nom).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_SUBACTIVITIES)
                db.execSQL(CREATE_SUBACTIVITIES_INDEX)
                db.execSQL("ALTER TABLE sessions ADD COLUMN sub_id TEXT")
                db.execSQL(
                    "UPDATE activities SET name = ? WHERE id = ? AND name = ?",
                    arrayOf<Any>(driving().name, DefaultActivities.DRIVING, DefaultActivities.OLD_DRIVING_NAME),
                )
                val sleep = DefaultActivities.all.first { it.id == DefaultActivities.SLEEP }
                db.execSQL(
                    "INSERT OR IGNORE INTO activities (id, name, color, icon, sort_order, archived, interruption) " +
                        "SELECT ?, ?, ?, NULL, (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM activities), 0, 0 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM activities WHERE lower(name) = lower(?))",
                    arrayOf<Any>(sleep.id, sleep.name, sleep.color, sleep.name),
                )
                insertDefaultSubs(db)
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        private fun driving() = DefaultActivities.all.first { it.id == DefaultActivities.DRIVING }

        /** Mêmes définitions que celles générées par Room (app/schemas/…/3.json). */
        private const val CREATE_SUBACTIVITIES =
            "CREATE TABLE IF NOT EXISTS `subactivities` (`id` TEXT NOT NULL, `activity_id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`activity_id`) REFERENCES `activities`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
        private const val CREATE_SUBACTIVITIES_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_subactivities_activity_id` ON `subactivities` (`activity_id`)"

        /** Sous-catégories par défaut, seulement pour les activités présentes (jamais en double). */
        internal fun insertDefaultSubs(db: SupportSQLiteDatabase) {
            DefaultActivities.defaultSubs.forEach { sub ->
                db.execSQL(
                    "INSERT OR IGNORE INTO subactivities (id, activity_id, name, sort_order, archived) " +
                        "SELECT ?, ?, ?, ?, 0 WHERE EXISTS (SELECT 1 FROM activities WHERE id = ?)",
                    arrayOf<Any>(sub.id, sub.activityId, sub.name, sub.sortOrder, sub.activityId),
                )
            }
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
                        put("interruption", if (a.interruption) 1 else 0)
                        a.goal?.let {
                            put("goal_minutes", it.minutes)
                            put("goal_period", it.period.id)
                        }
                    },
                )
            }
            insertDefaultSubs(db)
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
    // Version 3 : la sous-catégorie d'une session appartient à son activité.
    """
    CREATE TRIGGER IF NOT EXISTS sessions_valid_sub_insert
    BEFORE INSERT ON sessions
    WHEN NEW.sub_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM subactivities WHERE id = NEW.sub_id AND activity_id = NEW.activity_id)
    BEGIN SELECT RAISE(ABORT, 'invalid_sub'); END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS sessions_valid_sub_update
    BEFORE UPDATE ON sessions
    WHEN NEW.sub_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM subactivities WHERE id = NEW.sub_id AND activity_id = NEW.activity_id)
    BEGIN SELECT RAISE(ABORT, 'invalid_sub'); END
    """.trimIndent(),
)
