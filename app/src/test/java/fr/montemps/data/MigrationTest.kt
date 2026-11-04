package fr.montemps.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.data.db.MonTempsDatabase
import fr.montemps.domain.model.GoalPeriod
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migration v1 → v2 sur une base créée avec le schéma v1 exporté par Room
 * (app/schemas/…/1.json) : les données existantes sont conservées.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private fun schema(version: Int) = Json.parseToJsonElement(
        File("schemas/fr.montemps.data.db.MonTempsDatabase/$version.json").readText(),
    ).jsonObject["database"]!!.jsonObject

    @Test
    fun version1DatabaseIsMigratedWithoutDataLoss() = runBlocking {
        val name = "migration-${System.nanoTime()}.db"
        val path = context().getDatabasePath(name).apply { parentFile?.mkdirs() }
        val v1 = schema(1)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            v1["entities"]!!.jsonArray.forEach { e ->
                val entity = e.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { i ->
                    db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            v1["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO activities (id, name, color, icon, sort_order, archived) VALUES ('work', 'Travail', -1, NULL, 0, 0)")
            db.execSQL("INSERT INTO activities (id, name, color, icon, sort_order, archived) VALUES ('break', 'Pause', -1, NULL, 1, 0)")
            db.execSQL("INSERT INTO activities (id, name, color, icon, sort_order, archived) VALUES ('mine', 'Lecture', -1, NULL, 2, 0)")
            db.execSQL(
                "INSERT INTO sessions (activity_id, start_utc_ms, end_utc_ms, zone_id, note, start_elapsed_ms, start_boot_count, source) " +
                    "VALUES ('work', 1000, 5000, 'Europe/Paris', 'note', NULL, NULL, 'app')",
            )
            db.version = 1
        }

        val migrated = Room.databaseBuilder(context(), MonTempsDatabase::class.java, name)
            .addCallback(MonTempsDatabase.Callbacks)
            .addMigrations(MonTempsDatabase.MIGRATION_1_2)
            .build()
        val store = RoomTrackingStore(migrated)
        val activities = store.activities().associateBy { it.id }
        assertEquals(setOf("work", "break", "mine"), activities.keys)
        assertTrue(activities.getValue("break").interruption)
        assertFalse(activities.getValue("work").interruption)
        assertFalse(activities.getValue("mine").interruption)
        assertNull(activities.getValue("work").goal)
        assertEquals("note", store.allSessions().single().note)
        // La version 2 est pleinement utilisable.
        store.upsertActivity(activities.getValue("mine").copy(goal = fr.montemps.domain.model.Goal(20, GoalPeriod.DAY)))
        assertEquals(20, store.activity("mine")!!.goal!!.minutes)
        migrated.close()
        path.delete()
        Unit
    }
}
