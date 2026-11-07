package fr.montemps.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.domain.model.Session
import fr.montemps.testutil.context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Migrations v1 → v2 → v3 sur des bases créées avec les schémas exportés par Room
 * (app/schemas/…/N.json) : les données existantes sont conservées et la base migrée est
 * validée par Room à l'ouverture (même schéma qu'une installation neuve).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private fun schema(version: Int) = Json.parseToJsonElement(
        File("schemas/fr.montemps.data.db.MonTempsDatabase/$version.json").readText(),
    ).jsonObject["database"]!!.jsonObject

    /** Base au schéma [version] contenant [rows], puis ouverte par Room avec toutes les migrations. */
    private fun migrate(version: Int, vararg rows: String, check: suspend (RoomTrackingStore) -> Unit) = runBlocking {
        val name = "migration-$version-${System.nanoTime()}.db"
        val path = context().getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = schema(version)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema["entities"]!!.jsonArray.forEach { e ->
                val entity = e.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { i ->
                    db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            rows.forEach(db::execSQL)
            db.version = version
        }
        val migrated = Room.databaseBuilder(context(), MonTempsDatabase::class.java, name)
            .addCallback(MonTempsDatabase.Callbacks)
            .addMigrations(*MonTempsDatabase.MIGRATIONS)
            .build()
        try {
            check(RoomTrackingStore(migrated))
        } finally {
            migrated.close()
            path.delete()
        }
    }

    private val v1Activity = "INSERT INTO activities (id, name, color, icon, sort_order, archived) VALUES"
    private val v2Activity = "INSERT INTO activities (id, name, color, icon, sort_order, archived, interruption, goal_minutes, goal_period) VALUES"
    private val session = "INSERT INTO sessions (activity_id, start_utc_ms, end_utc_ms, zone_id, note, start_elapsed_ms, start_boot_count, source) VALUES"

    @Test
    fun version1DatabaseIsMigratedWithoutDataLoss() = migrate(
        1,
        "$v1Activity ('work', 'Travail', -1, NULL, 0, 0)",
        "$v1Activity ('break', 'Pause', -1, NULL, 1, 0)",
        "$v1Activity ('mine', 'Lecture', -1, NULL, 2, 0)",
        "$session ('work', 1000, 5000, 'Europe/Paris', 'note', NULL, NULL, 'app')",
    ) { store ->
        val activities = store.activities().associateBy { it.id }
        assertEquals(setOf("work", "break", "mine", DefaultActivities.SLEEP), activities.keys)
        assertTrue(activities.getValue("break").interruption)
        assertFalse(activities.getValue("work").interruption)
        assertFalse(activities.getValue("mine").interruption)
        assertNull(activities.getValue("work").goal)
        val old = store.allSessions().single()
        assertEquals("note", old.note)
        assertNull("les sessions existantes restent « sans précision »", old.subId)
        // Sous-catégories par défaut pour les activités par défaut présentes seulement.
        assertEquals(
            mapOf("work" to listOf("Perso", "Pro"), "break" to listOf("YouTube", "Jeu", "Autre")),
            store.subActivities().groupBy({ it.activityId }, { it.name }),
        )
        // La version 3 est pleinement utilisable.
        store.upsertActivity(activities.getValue("mine").copy(goal = fr.montemps.domain.model.Goal(20, GoalPeriod.DAY)))
        assertEquals(20, store.activity("mine")!!.goal!!.minutes)
        store.insertSession(Session(activityId = "work", start = Instant.ofEpochMilli(6000), end = Instant.ofEpochMilli(9000), zoneId = "UTC", subId = "work_pro"))
        assertEquals("work_pro", store.allSessions().last().subId)
    }

    @Test
    fun version2DatabaseGetsSubCategoriesRenamedTravelAndSleep() = migrate(
        2,
        "$v2Activity ('work', 'Travail', -1, NULL, 0, 0, 0, 120, 'day')",
        "$v2Activity ('driving', 'Conduite', -1, NULL, 1, 0, 0, NULL, NULL)",
        "$v2Activity ('sport', 'Sport', -1, NULL, 2, 1, 0, NULL, NULL)",
        "$v2Activity ('perso_1', 'Guitare', -1, NULL, 3, 0, 0, NULL, NULL)",
        "$session ('driving', 1000, 5000, 'Europe/Paris', NULL, NULL, NULL, 'app')",
        "$session ('work', 6000, NULL, 'Europe/Paris', NULL, 42, 3, 'widget')",
    ) { store ->
        val activities = store.activities().associateBy { it.id }
        assertEquals("Conduite non modifiée devient Déplacement", "Déplacement", activities.getValue("driving").name)
        assertEquals(120, activities.getValue("work").goal!!.minutes)
        assertTrue("une activité archivée le reste", activities.getValue("sport").archived)
        val sleep = activities.getValue(DefaultActivities.SLEEP)
        assertEquals("Sommeil", sleep.name)
        assertEquals("Sommeil ajouté en dernier", 4, sleep.sortOrder)
        val subs = store.subActivities().groupBy({ it.activityId }, { it.name })
        assertEquals(listOf("Voiture", "À pied", "Transports", "Autre"), subs["driving"])
        assertEquals(listOf("Muscu", "Foot", "Course", "Autre"), subs["sport"])
        assertNull(subs["perso_1"])
        // La session en cours continue, sans sous-catégorie.
        val active = store.activeSession()!!
        assertEquals("work", active.activityId)
        assertNull(active.subId)
        assertEquals(42L, active.startElapsedRealtimeMs)
        // Le trigger d'intégrité des sous-catégories est actif sur la base migrée.
        try {
            store.insertSession(Session(activityId = "work", start = Instant.ofEpochMilli(100), end = Instant.ofEpochMilli(200), zoneId = "UTC", subId = "driving_car"))
            fail("sous-catégorie d'une autre activité acceptée")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("invalid_sub"))
        }
    }

    @Test
    fun version2RenamedTravelAndExistingSleepAreLeftAlone() = migrate(
        2,
        "$v2Activity ('driving', 'Trajets', -1, NULL, 0, 0, 0, NULL, NULL)",
        "$v2Activity ('perso_2', 'sommeil', -1, NULL, 1, 0, 0, NULL, NULL)",
    ) { store ->
        val activities = store.activities()
        assertEquals(listOf("Trajets", "sommeil"), activities.map { it.name })
        assertEquals(listOf("driving"), store.subActivities().map { it.activityId }.distinct())
    }
}
